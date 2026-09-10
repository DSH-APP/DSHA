#!/usr/bin/env python3
# 只建立应用拥有的独立基础配置，保留 web profile 和共享用户数据。
import importlib.util
import json
import os
import secrets

spec = importlib.util.spec_from_file_location('register', os.path.join(os.path.dirname(__file__), 'register-builtin-plugins.py'))
register = importlib.util.module_from_spec(spec)
spec.loader.exec_module(register)

def prepare():
    home = register.local(register.DSH_HOME)
    profiles = os.path.join(home, 'profiles')
    os.makedirs(profiles, exist_ok=True)
    # 每次使用新目录，避免旧恢复配置被手动改坏；不覆盖同名目录。
    name = 'dsha-recovery-' + secrets.token_hex(8)
    directory = os.path.join(profiles, name)
    os.mkdir(directory)
    doc = register.new_manifest({})
    doc['name'] = name
    with open(os.path.join(directory, 'package.json'), 'w', encoding='utf-8') as stream:
        json.dump(doc, stream)
    for file, text in [('cordis.patch.yml', '[]\n'), ('pnpm-workspace.yaml', register.PROFILE_WORKSPACE_TEMPLATE)]:
        with open(os.path.join(directory, file), 'w', encoding='utf-8') as stream:
            stream.write(text)
    return name

if __name__ == '__main__':
    with register.operation_lock():
        print('DSHA_RECOVERY_PROFILE=' + prepare(), flush=True)
