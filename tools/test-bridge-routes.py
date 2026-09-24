#!/usr/bin/env python3
"""3090 桥的路由分发：剥掉查询串后精确匹配。

HTTP 外层做源码接线检查；虚拟屏的纯路由判据由临时 JVM 夹具执行真实行为矩阵，
不需要启动 Android 服务或设备能力。完整 Java 单测另覆盖同一纯逻辑入口。

历史教训（1.1.x 支线 c2b58bc 记下来的）：`/app/overlay` 用 startsWith 就意味着
`/app/overlayXXX` 也命中它，而 `/app/overlay/reply` 只是靠「写在前面」才没被吃掉
—— 顺序型防御一次改动就会破。而 `/app/readfile`、`/app/export`、`/app/share`
是凭据敏感端点，被前缀吃掉的代价不是路由错了，是凭据被读走了。

跑法：python3 tools/test-bridge-routes.py
"""
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/com/deepseekharness/app/HttpShellService.java'

# 这两组是命名空间；内部必须精确分发，未知子路径不得触发授权或动作。
ALLOWED_PREFIXES = ('/app/ui/', '/app/vscreen/')
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

    def test_vscreen_rejects_unknown_route_before_authorization(self):
        handler = self.src.split('private String appVscreen(String path)', 1)[1].split('private int intParam', 1)[0]
        self.assertIn('VirtualScreenRoutes.operation(route)', handler)
        self.assertLess(handler.index('if (operation.isEmpty())'), handler.index('uiAuthorized('))
        self.assertNotIn('endsWith(', handler)
        manager = (ROOT / 'app/src/main/java/com/deepseekharness/app/vscreen/VirtualScreenManager.java').read_text(encoding='utf-8')
        bridge = manager.split('public static String bridge(', 1)[1].split('private static JSONObject remember', 1)[0]
        self.assertIn('VirtualScreenRoutes.operation(route)', bridge)
        self.assertLess(bridge.index('if(name.isEmpty())'), bridge.index('switch(name)'))


class VirtualRouteBehavior(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='dsha-vscreen-routes-')
        cls.addClassCleanup(cls.temp.cleanup)
        cls.java = shutil.which('java')
        javac = shutil.which('javac')
        if not cls.java or not javac:
            raise RuntimeError('路由行为测试需要 JDK 17+ 的 java/javac')
        probe = Path(cls.temp.name) / 'RouteProbe.java'
        probe.write_text('''import com.deepseekharness.app.util.VirtualScreenRoutes;
public class RouteProbe { public static void main(String[] args) {
    for (String route : args) { String result = VirtualScreenRoutes.operation(route);
        System.out.println(result.isEmpty() ? "UNKNOWN_ROUTE" : result); }
} }''', encoding='utf-8')
        subprocess.run([javac, '--release', '17', '-encoding', 'UTF-8', '-d', cls.temp.name,
                        str(ROOT / 'app/src/main/java/com/deepseekharness/app/util/VirtualScreenRoutes.java'),
                        str(probe)], check=True, capture_output=True, text=True)

    def test_actual_java_dispatch_rejects_nested_suffixed_and_unknown_paths(self):
        operations = ('create', 'status', 'launch', 'tree', 'node', 'editor', 'edit', 'submit',
                      'touch', 'preview', 'see', 'tap', 'swipe', 'key', 'type', 'close')
        routes, expected = [], []
        for operation in operations:
            routes.append('/app/vscreen/' + operation)
            expected.append(operation)
            for invalid in ('/app/vscreen/unknown/' + operation, '/app/vscreen/' + operation + 'XXX',
                            '/app/vscreen//' + operation, '/app/vscreen/../' + operation,
                            '/app/vscreen/' + operation + '/', '/app/vscreen%2f' + operation):
                routes.append(invalid)
                expected.append('UNKNOWN_ROUTE')
        routes.extend(('/app/vscreen/unknown', '/app/vscreen/', '/app/vscreen/status?fake=1'))
        expected.extend(('UNKNOWN_ROUTE',) * 3)
        result = subprocess.run([self.java, '-cp', self.temp.name, 'RouteProbe', *routes],
                                check=True, capture_output=True, text=True)
        self.assertEqual(expected, result.stdout.splitlines())


if __name__ == '__main__':
    unittest.main(verbosity=2)
