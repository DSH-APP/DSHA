#!/usr/bin/env python3
"""3090 桥的路由分发：实际编译生产 Java 路由表并执行端点行为矩阵。

HTTP 门禁与处理器接线做窄源码顺序检查；完整 HTTP 端到端验收仍需 Android 服务。

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

class NativeBridgeRouteBehavior(unittest.TestCase):
    """Compile and execute the production Java route selector against hostile paths."""

    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='dsha-bridge-routes-')
        cls.addClassCleanup(cls.temp.cleanup)
        cls.java, javac = shutil.which('java'), shutil.which('javac')
        if not cls.java or not javac:
            raise RuntimeError('路由行为测试需要 JDK 17+ 的 java/javac')
        probe = Path(cls.temp.name) / 'BridgeRouteProbe.java'
        probe.write_text("""import com.deepseekharness.app.util.BridgeRoutes;
public class BridgeRouteProbe {
  public static void main(String[] args) {
    for (String route : args) {
      BridgeRoutes.Route result = BridgeRoutes.match(route);
      System.out.println(result.name() + ":" + result.commandParameter());
    }
  }
}""", encoding='utf-8')
        subprocess.run([javac, '--release', '17', '-encoding', 'UTF-8', '-d', cls.temp.name,
                        str(ROOT / 'app/src/main/java/com/deepseekharness/app/util/BridgeRoutes.java'),
                        str(probe)], check=True, capture_output=True, text=True)

    def routes(self, *paths):
        result = subprocess.run([self.java, '-cp', self.temp.name,
                                 'BridgeRouteProbe', *paths],
                                check=True, capture_output=True, text=True)
        return result.stdout.splitlines()

    def test_all_public_endpoints_and_command_metadata(self):
        expected = {
            '/device/vscreen/start': 'DEVICE_VSCREEN_START:true',
            '/device/vscreen/commit': 'DEVICE_VSCREEN_COMMIT:false',
            '/device/plan': 'DEVICE_PLAN:true',
            '/device/execute': 'DEVICE_EXECUTE:true',
            '/app/notify': 'NOTIFY:false', '/app/toast': 'TOAST:false',
            '/app/readfile': 'READ_FILE:false', '/health': 'HEALTH:false',
            '/app/ui/dump': 'UI:false', '/app/vscreen/status': 'VSCREEN:false',
            '/app/device': 'DEVICE:false', '/app/apps': 'APPS:false',
            '/app/launch': 'LAUNCH:false', '/app/clip': 'CLIP:false',
            '/app/share': 'SHARE:false', '/app/open': 'OPEN:false',
            '/app/vibrate': 'VIBRATE:false', '/app/ask': 'ASK:false',
            '/app/version': 'VERSION:false', '/app/help': 'HELP:false',
            '/app/plugins': 'PLUGINS:false', '/app/overlay': 'OVERLAY:false',
            '/app/location': 'LOCATION:false', '/app/sensors': 'SENSORS:false',
            '/app/sensor': 'SENSOR:false', '/app/torch': 'TORCH:false',
            '/app/export': 'EXPORT:false', '/confirm': 'CONFIRM:true',
            '/exec': 'EXEC:true',
        }
        self.assertEqual(list(expected.values()), self.routes(*expected))

    def test_sensitive_suffixes_and_unknown_namespaces_cannot_inherit_handlers(self):
        invalid = (
            '/app/readfileXXX', '/app/readfile/child', '/app/share2',
            '/app/export/child', '/app/overlay/reply', '/app/sensorsXXX',
            '/app/sensor/child', '/exec/child', '/device/execute/child',
            '/app/ui', '/app/vscreen', '/app/ui%2fdump',
            '/app/vscreen%2fstatus', '/app/ui-extra/tap',
        )
        self.assertEqual(['UNKNOWN:false'] * len(invalid), self.routes(*invalid))

    def test_http_dispatch_keeps_protocol_and_auth_before_handlers(self):
        src = JAVA.read_text(encoding='utf-8')
        method = src.split('private void handle(Socket client, long headerDeadline)', 1)[1]
        method = method.split('private String dispatch(', 1)[0]
        self.assertLess(method.index('HttpProtocol.readHead('),
                        method.index('BridgeRoutes.match(route)'))
        self.assertLess(method.index('request.method.equals("POST")'),
                        method.index('BridgeRoutes.match(route)'))
        self.assertLess(method.index('BridgeCredentialAuth.authorized('),
                        method.index('dispatch(selected, bridgeRequest)'))
        self.assertLess(method.index('RuntimeTasks.begin()'),
                        method.index('dispatch(selected, bridgeRequest)'))
        self.assertIn('VscreenBridgeRequest.readPost(', src)
        self.assertIn('ROUTE_HANDLERS.get(route)', src)
        route_src = (ROOT / 'app/src/main/java/com/deepseekharness/app/util/BridgeRoutes.java').read_text(encoding='utf-8')
        declared = set(re.findall(r'\b([A-Z_]+)\((?:true|false)\)',
                                  route_src.split('private final boolean commandParameter;', 1)[0]))
        registered = re.findall(r'routes\s*\.\s*put\s*\(\s*BridgeRoutes\.Route\.([A-Z_]+)', src)
        self.assertEqual(len(registered), len(set(registered)), '每个路由只允许一个处理器')
        handlers = set(registered)
        self.assertEqual(declared - {'UNKNOWN'}, handlers,
                         '新增路由必须注册实际处理器，UNKNOWN 只返回历史错误')

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
