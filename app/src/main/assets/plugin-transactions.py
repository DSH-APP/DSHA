#!/usr/bin/env python3
"""插件现有替换流程的持久化日志；恢复只接受固定目标与已记录的原件摘要。"""
import contextlib
import hashlib
import json
import os
import shutil
import stat
import uuid


def sha(path):
    value = hashlib.sha256()
    with open(path, 'rb') as stream:
        for block in iter(lambda: stream.read(65536), b''):
            value.update(block)
    return value.hexdigest()


class Transactions:
    def __init__(self, manager):
        self.g = manager
        self.home = manager['local'](manager['DSH_HOME'])
        self.directory = os.path.join(self.home, 'plugin-install-operations')

    def path(self, operation):
        if str(uuid.UUID(operation)) != operation:
            raise ValueError('插件事务标识无效')
        path = os.path.join(self.directory, operation)
        if os.path.islink(path) or os.path.commonpath([os.path.realpath(self.home), os.path.realpath(path)]) != os.path.realpath(self.home):
            raise ValueError('插件事务目录越界')
        return path

    def pending(self):
        if not os.path.isdir(self.directory):
            return []
        names = sorted(os.listdir(self.directory))
        if len(names) > 256:
            raise ValueError('保留的插件事务超过上限')
        result = []
        for name in names:
            path = self.path(name)
            if not os.path.isfile(os.path.join(path, 'plan.json')):
                continue
            if self.marker(path, 'committed') or self.marker(path, 'rolled-back'):
                continue
            result.append(path)
        return result

    @staticmethod
    def marker(path, name):
        marker = os.path.join(path, name)
        if not os.path.lexists(marker):
            return False
        if os.path.islink(marker) or not os.path.isfile(marker) or os.path.getsize(marker) > 128:
            raise ValueError('插件事务标记无效，原件已保留')
        with open(marker, encoding='ascii') as stream:
            if stream.read() != os.path.basename(path) + '\n' + name + '\n':
                raise ValueError('插件事务标记无效，原件已保留')
        return True

    @staticmethod
    def mark(path, name):
        if Transactions.marker(path, name):
            return
        with open(os.path.join(path, name), 'x', encoding='ascii') as stream:
            stream.write(os.path.basename(path) + '\n' + name + '\n'); stream.flush(); os.fsync(stream.fileno())

    @contextlib.contextmanager
    def workspace(self):
        os.makedirs(self.directory, exist_ok=True)
        if self.pending():
            raise ValueError('存在中断的插件事务，请先恢复')
        if len(os.listdir(self.directory)) >= 128:
            raise ValueError('保留的插件事务已达上限，原件未自动删除')
        work = self.path(str(uuid.uuid4())); os.mkdir(work)
        # 成功/失败均保留已归属日志和历史原件；不让 TemporaryDirectory 清理唯一旧版本。
        yield work

    def targets(self, name):
        if not self.g['builtin'].valid_name(name):
            raise ValueError('插件事务包名无效')
        return {'manifest': self.g['local'](self.g['builtin'].MANIFEST), 'sources': self.g['local'](self.g['SOURCES']),
                'marker': self.g['builtin'].marker_path(name), 'activation': os.path.join(self.home, 'plugin-activations.json')}

    def state(self, path):
        if not os.path.lexists(path):
            return {'kind': 'missing'}
        info = os.lstat(path)
        if stat.S_ISLNK(info.st_mode):
            return {'kind': 'link', 'target': os.readlink(path)}
        if not stat.S_ISREG(info.st_mode) or info.st_size > 8 * 1024 * 1024:
            raise ValueError('插件事务配置类型或大小异常')
        return {'kind': 'file', 'sha256': sha(path), 'mode': stat.S_IMODE(info.st_mode)}

    def prepare(self, work, name, prepared, manifest, sources, marker_bytes, link_target=None, remove_marker=False):
        dest = self.g['local'](os.path.join(self.g['PLUGIN_SRC'], name))
        before = {}; after = {}
        for key, path in self.targets(name).items():
            before[key] = self.state(path)
            if before[key]['kind'] == 'file':
                shutil.copy2(path, os.path.join(work, 'before-' + key))
            if key == 'activation':
                after[key] = before[key]
                continue
            if key == 'marker' and remove_marker:
                after[key] = {'kind': 'missing'}
                continue
            if key == 'marker' and marker_bytes is None:
                after[key] = before[key]
                continue
            output = os.path.join(work, 'after-' + key)
            if key == 'marker':
                with open(output, 'xb') as stream:
                    stream.write(marker_bytes); stream.flush(); os.fsync(stream.fileno())
            else:
                self.g['write_json'](output, manifest if key == 'manifest' else sources)
            after[key] = self.state(output)
        history = self.g['lifecycle']().history_path(name)
        if os.path.lexists(history) and (os.path.islink(history) or not os.path.isdir(history)):
            raise ValueError('插件历史目录类型异常，原件已保留')
        plan = {'format': 2, 'id': os.path.basename(work), 'name': name,
                'before': before, 'after': after, 'oldPresent': os.path.isdir(dest) and link_target is None,
                'oldTree': self.g['dependencies']().tree(dest, self.g['check_cancel'])[0] if os.path.isdir(dest) and link_target is None else '',
                'newTree': self.g['dependencies']().tree(prepared, self.g['check_cancel'])[0] if link_target is None else self.g['dependencies']().current(prepared)['sha256'],
                'linkOnly': link_target is not None, 'linkAfter': link_target or dest,
                'linkBefore': self.state_link(name),
                'oldVersion': self.g['read_json'](os.path.join(dest, 'package.json'), {}).get('version', ''),
                'historyBefore': self.g['dependencies']().tree(history, self.g['check_cancel'])[0] if os.path.isdir(history) else ''}
        self.g['write_json'](os.path.join(work, 'plan.json'), plan)
        return plan

    def activation(self, work, plan, value):
        # 先持久化新摘要及恢复材料，再写激活观察记录。进程中断时与插件目录一起恢复。
        output = os.path.join(work, 'after-activation')
        self.g['write_json'](output, value)
        plan['after']['activation'] = self.state(output)
        self.g['write_json'](os.path.join(work, 'plan.json'), plan)
        self.apply_file(work, 'activation', plan)

    def state_link(self, name):
        path = os.path.join(self.g['local'](self.g['builtin'].NODE_MODULES), name)
        if not os.path.lexists(path):
            return None
        if not os.path.islink(path):
            raise ValueError('插件事务链接已被实体替换，原件已保留')
        return os.readlink(path)

    def apply_file(self, work, key, plan):
        path = self.targets(plan['name'])[key]
        if plan['after'][key] == plan['before'][key]:
            return
        if self.state(path) not in (plan['before'][key], plan['after'][key]):
            raise ValueError('插件配置在提交期间变化，原件已保留')
        self.replace_file(work, path, key, 'after', plan['after'][key])

    def replace_file(self, work, path, key, side, wanted):
        if self.state(path) == wanted:
            return
        os.makedirs(os.path.dirname(path), exist_ok=True)
        if wanted['kind'] == 'missing':
            if os.path.lexists(path):
                os.unlink(path)
        elif wanted['kind'] == 'link':
            if os.path.lexists(path):
                os.unlink(path)
            os.symlink(wanted['target'], path)
        else:
            saved = os.path.join(work, side + '-' + key)
            if self.state(saved) != wanted:
                raise ValueError('插件事务原件摘要不一致')
            temporary = path + '.plugin-transaction-' + os.path.basename(work)
            if os.path.lexists(temporary):
                if self.state(temporary) != wanted:
                    raise ValueError('插件事务暂存文件已变化，原件已保留')
            else:
                # Windows 的 fsync 需要可写描述符；写入句柄同步后再恢复原文件属性。
                with open(saved, 'rb') as source, open(temporary, 'xb') as stream:
                    shutil.copyfileobj(source, stream)
                    stream.flush(); os.fsync(stream.fileno())
                shutil.copystat(saved, temporary)
            os.replace(temporary, path)

    def recover(self, work):
        operation = os.path.basename(work)
        if self.path(operation) != work:
            raise ValueError('插件事务目录无效')
        if self.marker(work, 'committed') or self.marker(work, 'rolled-back'):
            return
        plan_file = os.path.join(work, 'plan.json')
        if self.state(plan_file)['kind'] != 'file':
            raise ValueError('插件事务记录类型异常')
        plan = self.g['read_json'](plan_file, {})
        if plan.get('format') not in (1, 2) or plan.get('id') != operation or not self.g['builtin'].valid_name(plan.get('name')):
            raise ValueError('插件事务记录无效，原件已保留')
        name = plan['name']; targets = self.targets(name)
        if plan['format'] == 1:
            targets.pop('activation')  # 历史三文件日志只恢复它实际记录的范围。
        if type(plan.get('oldPresent')) is not bool or not isinstance(plan.get('newTree'), str) or len(plan['newTree']) != 64:
            raise ValueError('插件事务目录映射无效')
        if set(plan.get('before', {})) != set(targets) or set(plan.get('after', {})) != set(targets):
            raise ValueError('插件事务配置映射无效')
        for key, path in targets.items():
            if self.state(path) not in (plan['before'][key], plan['after'][key]):
                raise ValueError('插件配置在中断后变化，未覆盖新内容')
        dest = self.g['local'](os.path.join(self.g['PLUGIN_SRC'], name))
        original = os.path.join(work, 'old')
        history_root = self.g['lifecycle']().history_path(name)
        history = os.path.join(history_root, 'package')
        if not plan.get('linkOnly') and os.path.islink(dest):
            raise ValueError('插件目标变为链接，原件已保留')
        if not plan.get('linkOnly') and os.path.lexists(dest) and not os.path.isdir(dest):
            raise ValueError('插件目标类型在中断后变化，原件已保留')
        if not plan.get('linkOnly') and os.path.isdir(dest):
            current = self.g['dependencies']().tree(dest, self.g['check_cancel'])[0]
            if current == plan['oldTree'] and plan['oldPresent']:
                original = dest
            elif current == plan['newTree']:
                failed = os.path.join(work, 'failed')
                if os.path.lexists(failed):
                    raise ValueError('插件失败候选已存在，原件已保留')
                os.replace(dest, failed)
                self.boundary('rollback-new')
            else:
                raise ValueError('插件在中断后被修改，未覆盖新内容')
        if plan['oldPresent'] and original != dest:
            location = original if os.path.lexists(original) else history
            if os.path.islink(location) or not os.path.isdir(location) or self.g['dependencies']().tree(location, self.g['check_cancel'])[0] != plan['oldTree']:
                raise ValueError('无法确认插件原件，已保留全部现场')
            os.replace(location, dest)
        saved_history = os.path.join(work, 'previous-history')
        if os.path.lexists(saved_history):
            if os.path.islink(saved_history) or not os.path.isdir(saved_history) or self.g['dependencies']().tree(saved_history, self.g['check_cancel'])[0] != plan.get('historyBefore'):
                raise ValueError('插件历史原件已变化，全部现场保留')
            if os.path.exists(history_root):
                history_state = self.g['read_json'](os.path.join(history_root, 'state.json'), {})
                if os.path.islink(history_root) or (os.listdir(history_root) and (history_state.get('name') != name or history_state.get('version') != plan.get('oldVersion'))):
                    raise ValueError('插件历史在中断后变化，全部现场保留')
                os.rename(history_root, os.path.join(work, 'history-after-failure'))
            os.rename(saved_history, history_root)
        link = os.path.join(self.g['local'](self.g['builtin'].NODE_MODULES), name)
        current_link = self.state_link(name)
        if current_link not in (None, plan.get('linkAfter', dest), plan['linkBefore']):
            raise ValueError('插件链接在中断后变化，未覆盖新内容')
        if current_link != plan['linkBefore']:
            if os.path.lexists(link):
                os.unlink(link)
            if plan['linkBefore'] is not None:
                os.makedirs(os.path.dirname(link), exist_ok=True); os.symlink(plan['linkBefore'], link, target_is_directory=True)
        for key, path in targets.items():
            self.replace_file(work, path, key, 'before', plan['before'][key])
            self.boundary('rollback-' + key)
        self.mark(work, 'rolled-back')

    def recover_all(self):
        with self.g['builtin'].operation_lock(self.g['check_cancel']):
            for work in self.pending():
                self.recover(work)

    def boundary(self, name):
        # 仅隔离夹具可以暂停在持久化边界，由测试父进程真正强杀。
        root = os.environ.get('DSHA_TEST_ROOT', '')
        if root and os.environ.get('DSHA_PLUGIN_TEST_BOUNDARY') == name:
            marker = os.path.join(root, 'plugin-boundary')
            with open(marker, 'x', encoding='ascii') as stream:
                stream.write(name); stream.flush(); os.fsync(stream.fileno())
            import time
            while True:
                time.sleep(1)
