#!/usr/bin/env python3
"""插件市场的网络策略；仅负责只读下载与有限换源，不重放正式提交。"""
import concurrent.futures
import os
import re
import ssl
import threading
import urllib.error
import urllib.parse
import urllib.request

OFFICIAL = 'https://registry.npmjs.org'
MIRROR = 'https://registry.npmmirror.com'
REGISTRIES = (OFFICIAL, MIRROR)


def checked_url(url):
    uri = urllib.parse.urlsplit(url)
    if uri.scheme != 'https' or not uri.hostname or uri.username or uri.password:
        raise ValueError('下载地址必须是无内嵌凭据的 HTTPS 链接')
    return uri


class HttpsRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # 在发出重定向请求前核验，不能先访问 HTTP 再检查 response.url。
        checked_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def retryable(error):
    if isinstance(error, urllib.error.HTTPError):
        return error.code in (404, 408, 429, 500, 502, 503, 504)
    if isinstance(error, ssl.SSLError):
        return False
    if isinstance(error, urllib.error.URLError):
        return not isinstance(error.reason, ssl.SSLError)
    return isinstance(error, (TimeoutError, ConnectionError))


class Network:
    def __init__(self, manager):
        self.g = manager
        # 没有由原生入口指定策略的 CLI 保持用户 npmrc，不改全局设置。
        value = os.environ.get('DSHA_PLUGIN_DOWNLOAD_SOURCE', '')
        self.mode = value if value in ('auto', 'official', 'mirror') else ''
        self._selected = None
        self._lock = threading.Lock()

    def request(self, url, timeout=12):
        self.g['check_cancel']()
        uri = checked_url(url)
        req = urllib.request.Request(url, headers={
            'User-Agent': 'DSHA-plugin-manager',
            'Accept': 'application/vnd.github+json' if uri.hostname == 'api.github.com' else '*/*'})
        response = urllib.request.build_opener(HttpsRedirect()).open(req, timeout=timeout)
        try:
            checked_url(response.url)
        except Exception:
            response.close()
            raise
        return response

    def probe(self, registry):
        with self.request(registry + '/-/ping', timeout=3) as response:
            if response.status != 200 or len(response.read(4097)) > 4096:
                raise ValueError('下载源探测失败')
        return registry

    def registries(self):
        if self.mode == 'official':
            return [OFFICIAL]
        if self.mode == 'mirror':
            return [MIRROR, OFFICIAL]
        if self.mode != 'auto':
            return []
        with self._lock:
            if self._selected is None:
                self.g['progress']('network', '正在选择可用的 npm 下载源…')
                pool = concurrent.futures.ThreadPoolExecutor(max_workers=2)
                futures = [pool.submit(self.probe, registry) for registry in REGISTRIES]
                try:
                    for future in concurrent.futures.as_completed(futures):
                        self.g['check_cancel']()
                        try:
                            self._selected = future.result()
                            break
                        except self.g['PluginCancelled']:
                            raise
                        except Exception:
                            continue
                    if self._selected is None:
                        self._selected = OFFICIAL
                finally:
                    # 最多两个3秒只读探测；不中断已启动请求，不让慢探测阻塞赢家。
                    pool.shutdown(wait=False, cancel_futures=True)
            return [self._selected, MIRROR if self._selected == OFFICIAL else OFFICIAL]

    def open(self, url):
        uri = checked_url(url)
        base = 'https://' + uri.netloc
        # 带签名/凭据的直链只访问原目标，不能把其查询参数转交镜像。
        choices = self.registries() if base in REGISTRIES and not uri.query and not uri.fragment else []
        urls = [registry + url[len(base):] for registry in choices] or [url]
        for index, candidate in enumerate(urls):
            try:
                return self.request(candidate)
            except Exception as error:
                self.g['check_cancel']()
                if index + 1 == len(urls) or not retryable(error):
                    raise
                self.g['progress']('network', '当前 npm 源不可用，正在切换备用源…')

    def package_command(self, argv, cwd, *, frozen=False, offline=False):
        registries = [] if offline else self.registries()
        choices = registries or [None]
        # Only a read-only npm publication download can retry. Dependency install
        # may already have run pnpmfile/lifecycle hooks or mutated a lock/candidate,
        # even when its final diagnostic looks like an ordinary network error.
        separator = argv.index('--') if '--' in argv else len(argv)
        safe_pack = len(argv) > 1 and argv[:2] == ['npm', 'pack'] \
            and '--ignore-scripts' in argv[:separator] \
            and not any(arg in ('--ignore-scripts=false', '--config.ignore-scripts=false') for arg in argv[:separator])
        if not safe_pack:
            choices = choices[:1]
        prefix = '--config.' if argv[0] == 'pnpm' else '--'
        tuning = [prefix + 'fetch-retries=1', prefix + 'fetch-retry-mintimeout=1000',
                  prefix + 'fetch-retry-maxtimeout=3000', prefix + 'fetch-timeout=60000']
        if frozen:
            tuning += ['--prefer-offline']
        for index, registry in enumerate(choices):
            self.g['check_cancel']()
            args = list(argv)
            options = tuning + ([prefix + 'registry=' + registry] if registry else [])
            # npm pack 的 -- 之后是包名；网络参数必须放在分隔符之前。
            at = args.index('--') if '--' in args else len(args)
            args[at:at] = options
            # Source fallback keeps the caller's explicit lock policy unchanged.
            if registry:
                label = 'npm 官方源' if registry == OFFICIAL else 'npmmirror 镜像'
                self.g['progress']('network', '正在下载插件及依赖：' + label)
            try:
                result = self.g['run_package_command'](args, cwd=cwd)
            except TimeoutError as error:
                # Missing/unknown exit proof is never inferred from exception type.
                self.g['check_cancel']()
                if not safe_pack or not getattr(error, 'confirmed_exit', False) or index + 1 == len(choices):
                    raise
                self.g['progress']('network', '当前 npm 源不可用，正在切换备用源…')
                continue
            if result.returncode == 0 or index + 1 == len(choices) or not getattr(result, 'confirmed_exit', False):
                return result
            text = (result.stderr or '') + '\n' + (result.stdout or '')
            # 只重试临时目录的读取失败。完整性、锁冲突、权限及脚本错误不换源。
            if re.search(r'EINTEGRITY|TARBALL_INTEGRITY|CERT|SSL|LOCKFILE|EACCES|EPERM', text, re.I) \
                    or not re.search(r'ETIMEDOUT|ESOCKETTIMEDOUT|ECONNRESET|ECONNREFUSED|EAI_AGAIN|ENOTFOUND|ERR_PNPM_FETCH_(?:404|408|429|5\d\d)|\bE(?:404|408|429|5\d\d)\b', text):
                return result
            self.g['progress']('network', '当前 npm 源不可用，正在切换备用源…')
