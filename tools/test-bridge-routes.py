#!/usr/bin/env python3
"""3090 桥的路由分发：剥掉查询串后精确匹配。

为什么用源码断言而不是单测：路由落在 handle(Socket) 里，要跑起来需要 Activity、
SharedPreferences、DshaAccessibilityService 一整串。这里钉住的是「判据的形状」，
防止后来的重构把它悄悄改回 startsWith。

历史教训（1.1.x 支线 c2b58bc 记下来的）：`/app/overlay` 用 startsWith 就意味着
`/app/overlayXXX` 也命中它，而 `/app/overlay/reply` 只是靠「写在前面」才没被吃掉
—— 顺序型防御一次改动就会破。而 `/app/readfile`、`/app/export`、`/app/share`
是凭据敏感端点，被前缀吃掉的代价不是路由错了，是凭据被读走了。

跑法：python3 tools/test-bridge-routes.py
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/com/deepseekharness/app/HttpShellService.java'

# 唯一允许保留前缀的组：它是一个端点命名空间，真子路径在 appUi 内再精确分发。
ALLOWED_PREFIXES = ('/app/ui/',)
# 凭据敏感端点：必须精确匹配。
SENSITIVE = ('/app/readfile', '/app/export', '/app/share')


class RouteDispatch(unittest.TestCase):
    def setUp(self):
        self.src = JAVA.read_text(encoding='utf-8')

    def exact_routes(self):
        return set(re.findall(r'\broute\.equals\("([^"]+)"\)', self.src))

    def prefixes(self):
        return set(re.findall(r'\broute\.startsWith\("([^"]+)"\)', self.src))

    def test_query_string_is_stripped_before_matching(self):
        # 判据必须建立在剥掉查询串的路由上，?token=… 不该参与路由选择。
        self.assertIn('String route = path.split("\\\\?", 2)[0];', self.src)

    def test_no_app_route_matches_by_prefix(self):
        for prefix in sorted(self.prefixes()):
            self.assertIn(prefix, ALLOWED_PREFIXES,
                          f"{prefix} 仍是前缀匹配：/x{prefix.lstrip('/')}XXX 也会命中它")

    def test_no_legacy_starts_with_dispatch(self):
        # path.startsWith(...) 是旧的写法：查询串没剥，又是前缀。
        for m in re.finditer(r'path\.startsWith\("([^"]+)"\)', self.src):
            self.fail(f'仍在用 path.startsWith 分发：{m.group(1)}')

    def test_sensitive_endpoints_are_exact(self):
        exact = self.exact_routes()
        for route in SENSITIVE:
            self.assertIn(route, exact,
                          f'{route} 必须是精确路由：它决定凭据能否被读出')

    def test_sensor_pair_is_not_collapsed_to_a_prefix(self):
        # /app/sensors（列表）与 /app/sensor（读单个）名字互为前缀，
        # 精确匹配后它俩是两条独立路由；合并成一个前缀就会恢复那个顺序依赖。
        exact = self.exact_routes()
        self.assertIn('/app/sensors', exact)
        self.assertIn('/app/sensor', exact)

    def test_ui_namespace_dispatches_on_exact_subroutes(self):
        # appUi 内部也必须剥掉查询串再精确匹配：startsWith 会让 /app/ui/shotXXX
        # 命中截屏，而截屏会把当前画面留到磁盘。
        self.assertIn('String r = path.split("\\\\?", 2)[0];', self.src)
        for sub in ('/app/ui/dump', '/app/ui/tap', '/app/ui/input', '/app/ui/key',
                    '/app/ui/swipe'):
            self.assertIn(f'r.equals("{sub}")', self.src, f'appUi 里 {sub} 不是精确匹配')
        self.assertIn('r.equals("/app/ui/screenshot") || r.equals("/app/ui/shot")', self.src)
        for m in re.finditer(r'path\.startsWith\("/app/ui', self.src):
            self.fail('appUi 内部仍在用 path.startsWith 分发')


if __name__ == '__main__':
    unittest.main(verbosity=2)
