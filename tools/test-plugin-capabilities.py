import importlib.util,json,tempfile,unittest,hashlib,subprocess
import contextlib,io,os
from unittest.mock import patch
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];ASSETS=ROOT/'app/src/main/assets'
spec=importlib.util.spec_from_file_location('capabilities',ASSETS/'plugin-capabilities.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
class CapabilityTest(unittest.TestCase):
    def test_manager_detail_reads_the_installed_candidate_without_changing_or_running_it(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ,{'DSHA_TEST_ROOT':temp,'DSH_HOME':'/root/.dsh','DSHA_PLUGIN_TASK':'1'*32}):
            root=Path(temp);package=root/'root/.dsh/profiles/web/node_modules/test-plugin';package.mkdir(parents=True)
            manifest=package.parents[1]/'package.json'
            manifest.write_text(json.dumps({'dependencies':{'test-plugin':'^1.0.0'},'dsh':{'profile':{'bundles':[]}}}),encoding='utf8')
            pkg={'name':'test-plugin','version':'1.0.0','main':'index.js','scripts':{'install':'must-not-run'},'dsh':{'bundle':{'patch':'cordis.patch.yml'}}}
            (package/'package.json').write_text(json.dumps(pkg),encoding='utf8')
            (package/'cordis.patch.yml').write_text('[]\n',encoding='utf8')
            (package/'index.js').write_text("throw Error('must not execute'); export const inject=['tools'];",encoding='utf8')
            before={str(file.relative_to(package)):file.read_bytes() for file in package.rglob('*') if file.is_file()};profile=manifest.read_bytes()
            spec=importlib.util.spec_from_file_location('plugin_capability_manager',ASSETS/'plugin-manager.py');manager=importlib.util.module_from_spec(spec);spec.loader.exec_module(manager)
            with patch('sys.argv',['plugin-manager.py','capabilities','test-plugin']),patch.object(manager,'result') as result,contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0,manager.main())
            report=result.call_args.kwargs['capabilities']
            self.assertTrue(any(item['kind']=='server-inject' and item['value']=='tools' for item in report['findings']))
            self.assertEqual(before,{str(file.relative_to(package)):file.read_bytes() for file in package.rglob('*') if file.is_file()})
            self.assertEqual(profile,manifest.read_bytes())
    def test_pinned_parser_bytes_and_license(self):
        lock=json.loads((ROOT/'tools/plugin-capabilities/parser-lock.json').read_text())
        self.assertEqual('8.15.0',lock['version'])
        self.assertEqual(lock['sourceSha256'],hashlib.sha256((ASSETS/'plugin-acorn.cjs').read_bytes()).hexdigest())
        self.assertEqual(lock['licenseSha256'],hashlib.sha256((ASSETS/'plugin-acorn.LICENSE').read_bytes()).hexdigest())
    def test_literals_are_evidence_dynamic_is_unknown_and_never_executed(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);pkg={'name':'test','version':'1.0.0','main':'index.js','scripts':{'install':'echo sk-secret'},'dsh':{'client':{'inject':['uiWorkspace']}}}
            (root/'package.json').write_text(json.dumps(pkg));(root/'index.js').write_text("throw Error('must not execute'); export const inject=['agents','tools']; fetch('http://127.0.0.1:3090/app/device?token=secret'); fetch(computed);",encoding='utf8')
            report=module.analyze(root,pkg,candidate_sha='a'*64)
            self.assertEqual(['uiWorkspace'],report['clientInject']);self.assertEqual(['install'],report['scripts'])
            self.assertNotIn('sk-secret',json.dumps(report));self.assertNotIn('token=secret',json.dumps(report))
            self.assertTrue(any(r['kind']=='bridge-reference' and r['value']=='/app/device' for r in report['findings']))
            self.assertEqual(2,len([r for r in report['findings'] if r['kind']=='server-inject']))
            self.assertTrue(any('dynamic-bridge' in v for v in report['unknown']))
            before=report['inputSha256'];(root/'index.js').write_text('export const inject = makeServices();',encoding='utf8')
            changed=module.analyze(root,pkg);self.assertNotEqual(before,changed['inputSha256']);self.assertTrue(any('dynamic-inject' in v for v in changed['unknown']))
    def test_bad_code_and_budget_are_unknown_not_failure_or_safety_pass(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);pkg={'name':'test','version':'1.0.0','main':'entry.js'};(root/'package.json').write_text(json.dumps(pkg))
            (root/'entry.js').write_text('export const inject = [ ...untrusted ]; !broken {}',encoding='utf8')
            report=module.analyze(root,pkg);self.assertTrue(any('unparsed-source' in v for v in report['unknown']))
            (root/'entry.js').write_bytes(b'x'*(module.MAX_FILE+1));report=module.analyze(root,pkg)
            self.assertEqual([],report['findings']);self.assertTrue(any('entry not inspected' in v for v in report['unknown']))
    def test_commonjs_and_default_object_inject_are_static_evidence(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);pkg={'name':'test','version':'1.0.0','main':'entry.js'};(root/'package.json').write_text(json.dumps(pkg))
            (root/'entry.js').write_text("exports.inject=['tools']; module.exports={inject:{required:['agents']},apply(){throw Error('no execution')}}; export default {inject:['server'],apply(){}}",encoding='utf8')
            report=module.analyze(root,pkg);values={r['value'] for r in report['findings'] if r['kind']=='server-inject'}
            self.assertEqual({'tools','required: agents','server'},values)
    def test_parent_entries_are_not_followed(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);pkg={'name':'test','version':'1.0.0','main':'../outside.js'};(root/'package.json').write_text(json.dumps(pkg))
            report=module.analyze(root,pkg);self.assertEqual([],report['findings'])
    def test_link_entries_are_not_followed(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);pkg={'name':'test','version':'1.0.0','main':'link.js'}
            (root/'package.json').write_text(json.dumps(pkg));(root/'actual.js').write_text("export const inject=['secret'];")
            try:(root/'link.js').symlink_to(root/'actual.js')
            except OSError:self.skipTest('Windows symlink privilege unavailable')
            report=module.analyze(root,pkg);self.assertEqual([],report['findings'])
if __name__=='__main__': unittest.main()
