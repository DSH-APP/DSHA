#!/usr/bin/env python3
"""Real isolated filesystem proof/tamper tests; installer calls are explicit fixture fakes, not npm network."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import io
from unittest.mock import patch
from runtime_fixture_proof import SCHEMA, content, validate, reuse, io_path, logical_path

ROOT = Path(__file__).resolve().parents[1]


class RuntimeProofTest(unittest.TestCase):
    def test_command_extension_proof_has_the_same_windows_policy_in_python_and_node(self):
        self.put(self.fixture,'node_modules/tool/entry.cmd','@echo off\n')
        proof=self.proof();validate(self.fixture,proof)
        self.assertEqual(0,self.node(proof).returncode)
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.fixture = self.root / 'fixture'
        self.fixture.mkdir()
        self.put(self.fixture, 'package.json', '{}')
        self.put(self.fixture, 'package-lock.json', '{}')
        self.put(self.fixture, 'node_modules/third-party/index.js', 'actual third-party bytes')
        self.put(self.fixture, 'node_modules/dsha-support/data.node', b'\0real binary fixture bytes')

    def tearDown(self):
        # Only this test's exact TemporaryDirectory, never a computed parent.
        resolved=self.root.resolve()
        if resolved!=Path(self.temp.name).resolve() or resolved.parent!=Path(tempfile.gettempdir()).resolve():raise AssertionError('TEST_TEMP_BOUNDARY')
        if self.root.exists():shutil.rmtree(io_path(self.root))
        self.temp.cleanup()

    def put(self, root, name, body):
        target = io_path(root / name)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(body.encode() if isinstance(body, str) else body)
        return target

    def proof(self):
        return dict(version=SCHEMA, kind='raw', installation='npm-ci-ignore-scripts', installationId='a'*32, contentProof=content(self.fixture))

    def node(self, proof):
        target = self.root / 'proof.json'
        target.write_text(json.dumps(proof), encoding='utf-8')
        module = (ROOT/'tools/test-runtime-fixture.mjs').as_uri()
        code = "const {verifyRuntimeContent}=await import(process.argv[1]);const fs=await import('node:fs');verifyRuntimeContent(process.argv[2],JSON.parse(fs.readFileSync(process.argv[3],'utf8')));"
        return subprocess.run([shutil.which('node') or 'node','--input-type=module','-e',code,module,str(self.fixture),str(target)],capture_output=True,text=True)

    def test_complete_bytes_and_members_include_dependencies_support_and_binary(self):
        proof = self.proof()
        self.assertIn('node_modules/dsha-support/data.node',proof['contentProof']['entries'])
        validate(self.fixture,proof);self.assertEqual(0,self.node(proof).returncode)
        self.put(self.fixture,'node_modules/third-party/index.js','tampered same-role dependency')
        with self.assertRaisesRegex(ValueError,'BYTES_OR_MEMBERS_CHANGED'):validate(self.fixture,proof)
        self.assertNotEqual(0,self.node(proof).returncode)

    def test_added_and_removed_files_cannot_pass_a_previous_proof(self):
        proof=self.proof();extra=self.put(self.fixture,'node_modules/unexpected/new.js','added')
        with self.assertRaises(ValueError):validate(self.fixture,proof)
        extra.unlink();(self.fixture/'node_modules/third-party/index.js').unlink()
        with self.assertRaises(ValueError):validate(self.fixture,proof)

    def test_managed_support_changes_are_rejected_by_both_consumers(self):
        proof=self.proof();proof['kind']='managed';validate(self.fixture,proof)
        self.put(self.fixture,'node_modules/dsha-support/data.node',b'changed actual support bytes')
        with self.assertRaisesRegex(ValueError,'BYTES_OR_MEMBERS_CHANGED'):validate(self.fixture,proof)
        self.assertNotEqual(0,self.node(proof).returncode)

    @unittest.skipIf(os.name=='nt','Actual symlink creation requires administrator privilege on this Windows host')
    def test_internal_link_proof_and_external_link_rejection(self):
        link=self.fixture/'node_modules/entry';link.symlink_to('third-party/index.js')
        proof=self.proof();validate(self.fixture,proof);self.assertEqual(0,self.node(proof).returncode)
        outside=self.put(self.root,'outside-secret-fixture','outside');link.unlink();link.symlink_to(outside)
        with self.assertRaisesRegex(ValueError,'LINK_OUTSIDE'):content(self.fixture)
        self.assertNotEqual(0,self.node(proof).returncode)

    def test_legacy_marker_is_not_upgraded_by_authenticating_old_bytes(self):
        marker=self.fixture/'dsha-test-runtime.json';marker.write_text(json.dumps({'version':1,'moduleHashes':{}}))
        self.assertIsNone(reuse(self.fixture,{}))
        with self.assertRaisesRegex(ValueError,'REINSTALL_REQUIRED'):validate(self.fixture,{'version':1})
        self.assertNotEqual(0,self.node({'version':1}).returncode)
        self.assertEqual(1,json.loads(marker.read_text())['version'])

    def test_real_long_dependency_path_is_fully_proven_and_consumed(self):
        relative='node_modules/third-party/'+'/'.join(['deep-member-'+str(i)+'-'+'x'*65 for i in range(5)])+'/ProcessDetector.js.map'
        target=io_path(self.fixture/relative);target.parent.mkdir(parents=True);target.write_bytes(b'long real dependency bytes')
        proof=self.proof();self.assertIn(relative,proof['contentProof']['entries']);self.assertNotIn('\\\\?\\',json.dumps(proof))
        validate(self.fixture,proof);self.assertEqual(0,self.node(proof).returncode)
        target.write_bytes(b'changed long dependency bytes')
        with self.assertRaisesRegex(ValueError,'BYTES_OR_MEMBERS_CHANGED'):validate(self.fixture,proof)
        self.assertNotEqual(0,self.node(proof).returncode)

    def producer(self):
        spec=importlib.util.spec_from_file_location('fixture_producer',ROOT/'tools/prepare-test-runtime.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        repository=self.root/'producer';assets=repository/'app/src/main/assets';store=repository/'app/build/test-runtimes'
        self.put(repository,'tools/dsh-runtime/package.json',json.dumps({'dependencies':{'@deepseek-ai/dsh':'0.2.0-rc.2'}}))
        self.put(repository,'tools/dsh-runtime/package-lock.json',json.dumps({'packages':{}}))
        self.put(assets,'.keep','x');archive=self.put(assets,'dsh-runtime.bin','synthetic-archive');self.put(assets,'dsh-runtime.inputs.json',json.dumps({'archive_sha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'inputs':{}}))
        self.put(assets,'runtime-patches.json',json.dumps({'schema':1,'dshVersion':'0.2.0-rc.2','active':[],'specialized':[],'retired':[]}))
        for folder in ['runtime-fs','session-compat','client-combo-cache']:self.put(assets,folder+'/index.cjs','signed-helper-fixture')
        module.ROOT=repository;module.ASSETS=assets;module.STORE=store
        builder=type('Builder',(),{'recipe_inputs':lambda self:{},'patched_content':lambda self,path,body:body})()
        args=type('Args',(),dict(platform='host',offline=True,cache=None,allow_stale_archive=False))()
        calls=[]
        def install(command,check):
            calls.append(command);self.assertIn('--ignore-scripts',command);self.assertIn('--offline',command)
            prefix=Path(command[command.index('--prefix')+1]);self.assertFalse((prefix/'node_modules').exists())
            self.put(prefix,'node_modules/@deepseek-ai/dsh/package.json',json.dumps({'version':'0.2.0-rc.2'}))
            self.put(prefix,'node_modules/third-party/index.js','fresh locked install bytes')
            self.put(prefix,'node_modules/third-party/'+'/'.join(['long-install-member-'+str(i)+'-'+'q'*55 for i in range(4)])+'/index.js','fresh long locked member')
            return subprocess.CompletedProcess(command,0)
        patches=[patch.object(module.shutil,'which',side_effect=lambda name: 'node' if name=='node' else 'npm'),patch.object(module.subprocess,'check_output',return_value=json.dumps({'os':'linux' if os.name!='nt' else 'win32','cpu':'x64'})),patch.object(module.subprocess,'run',side_effect=install),patch.object(module,'load_builder',return_value=builder)]
        return module,args,calls,patches,store

    def with_producer(self,callback):
        import contextlib
        module,args,calls,patches,store=self.producer()
        with contextlib.ExitStack() as stack:
            for p in patches:stack.enter_context(p)
            with contextlib.redirect_stdout(io.StringIO()):callback(module,args,calls,store)

    def test_normal_same_input_reuse_does_not_reinstall_or_rebless_changed_bytes(self):
        def run(module,args,calls,store):
            module.prepare(args);pointer=json.loads((store/'current.json').read_text());raw=Path(pointer['raw']);before=(raw/'dsha-test-runtime.json').read_bytes()
            module.prepare(args);self.assertEqual(1,len(calls));self.assertEqual(before,(raw/'dsha-test-runtime.json').read_bytes())
            self.put(raw,'node_modules/third-party/index.js','mutated cached bytes')
            with self.assertRaisesRegex(ValueError,'BYTES_OR_MEMBERS_CHANGED'):module.prepare(args)
            self.assertEqual(1,len(calls));self.assertEqual(before,(raw/'dsha-test-runtime.json').read_bytes())
        self.with_producer(run)

    def test_legacy_reinstall_uses_fresh_tree_and_retains_old_original(self):
        def run(module,args,calls,store):
            module.prepare(args);raw=Path(json.loads((store/'current.json').read_text())['raw']);marker=raw/'dsha-test-runtime.json';legacy=json.loads(marker.read_text());legacy['version']=1;marker.write_text(json.dumps(legacy));self.put(raw,'node_modules/third-party/index.js','old legacy mutated bytes')
            module.prepare(args);self.assertEqual(2,len(calls));self.assertEqual('fresh locked install bytes',(raw/'node_modules/third-party/index.js').read_text())
            retained=list(raw.parent.glob('raw-retained-*'));self.assertEqual(1,len(retained));self.assertEqual('old legacy mutated bytes',(retained[0]/'node_modules/third-party/index.js').read_text())
        self.with_producer(run)

    def test_specialized_old_recipe_requires_explicit_retirement(self):
        def run(module,args,calls,store):
            self.put(module.ASSETS,'old-tooltip.json',json.dumps({'dshVersion':'0.1.5-rc.2','module':'third-party/index.js','patches':[{'before':'fresh locked install bytes','after':'wrong old minifier patch'}]}))
            self.put(module.ASSETS,'runtime-patches.json',json.dumps({'schema':1,'dshVersion':'0.2.0-rc.2','active':[],'specialized':['old-tooltip.json'],'retired':[]}))
            with self.assertRaisesRegex(ValueError,'SPECIALIZED_PATCH_VERSION:old-tooltip.json'):module.prepare(args)
            self.assertFalse((store/'current.json').exists())
        self.with_producer(run)

    def test_ordinary_registry_order_is_preserved(self):
        def run(module,args,calls,store):
            self.put(module.ASSETS,'z-first.json',json.dumps({'dshVersion':'0.2.0-rc.2','module':'third-party/index.js','patches':[{'before':'fresh locked install bytes','after':'step-one'}]}))
            self.put(module.ASSETS,'a-second.json',json.dumps({'dshVersion':'0.2.0-rc.2','module':'third-party/index.js','patches':[{'before':'step-one','after':'step-two'}]}))
            self.put(module.ASSETS,'runtime-patches.json',json.dumps({'schema':1,'dshVersion':'0.2.0-rc.2','active':[{'asset':'z-first.json'},{'asset':'a-second.json'}],'specialized':[],'retired':[]}))
            module.prepare(args);managed=Path(json.loads((store/'current.json').read_text())['managed'])
            self.assertEqual('step-two',(managed/'node_modules/third-party/index.js').read_text())
        self.with_producer(run)

    def test_active_builder_owned_after_form_is_verified_without_duplicate_prepend(self):
        def run(module,args,calls,store):
            self.put(module.ASSETS,'prepend-helper.js','helper();')
            self.put(module.ASSETS,'deepseek-messages-compat-patch.json',json.dumps({'dshVersion':'0.2.0-rc.2','module':'third-party/index.js','patches':[{'before':'fresh locked install bytes','after':'fresh locked install bytes','prependAsset':'prepend-helper.js'}]}))
            self.put(module.ASSETS,'runtime-patches.json',json.dumps({'schema':1,'dshVersion':'0.2.0-rc.2','active':[{'asset':'deepseek-messages-compat-patch.json'}],'specialized':[],'retired':[]}))
            class Builder:
                def recipe_inputs(self):return {}
                def patched_content(self,path,body):return b'helper();\n'+body if path.as_posix()=='third-party/index.js' else body
            with patch.object(module,'load_builder',return_value=Builder()):module.prepare(args)
            managed=Path(json.loads((store/'current.json').read_text())['managed']);proof=json.loads((managed/'dsha-test-runtime.json').read_text())
            self.assertEqual('helper();\nfresh locked install bytes',(managed/'node_modules/third-party/index.js').read_text())
            self.assertEqual('builder_owned_verified',proof['specializedReceipts']['deepseek-messages-compat-patch.json']['status'])
        self.with_producer(run)

    def selector_repository(self):
        repository=self.root/'selectors'
        selector=self.put(repository,'tools/test-runtime-fixture.mjs',(ROOT/'tools/test-runtime-fixture.mjs').read_bytes())
        version=json.loads((ROOT/'tools/dsh-runtime/package.json').read_text())['dependencies']['@deepseek-ai/dsh']
        self.put(repository,'tools/dsh-runtime/package.json',json.dumps({'dependencies':{'@deepseek-ai/dsh':version}}))
        lock=self.put(repository,'tools/dsh-runtime/package-lock.json','{"packages":{}}')
        selected={}
        for kind in ('raw','managed'):
            directory=repository/kind
            self.put(directory,'package.json','{}');self.put(directory,'package-lock.json','{}')
            self.put(directory,'node_modules/@deepseek-ai/dsh/package.json',json.dumps({'version':version}))
            proof={'version':SCHEMA,'installation':'npm-ci-ignore-scripts','installationId':'a'*32,
                   'kind':kind,'dshVersion':version,'lockSha256':hashlib.sha256(lock.read_bytes()).hexdigest(),
                   'archiveRecipeInputs':{},'overlayInputs':{},'contentProof':content(directory)}
            self.put(directory,'dsha-test-runtime.json',json.dumps(proof));selected[kind]=str(directory)
        self.put(repository,'app/build/test-runtimes/current.json',json.dumps(selected))
        return repository,logical_path(selector),selected

    def select_node(self,selector,kind,environment):
        code="const {testRuntime}=await import(process.argv[1]);console.log(testRuntime(process.argv[2]));"
        return subprocess.run([shutil.which('node') or 'node','--input-type=module','-e',code,selector.as_uri(),kind],
                              capture_output=True,text=True,env=environment)

    def test_managed_selector_ignores_raw_override_and_reads_its_own_pointer(self):
        repository,selector,selected=self.selector_repository()
        environment={key:value for key,value in os.environ.items() if not key.startswith('DSHA_')}
        environment['DSHA_TEST_RUNTIME']=str(repository/'invalid-raw-override')
        result=self.select_node(selector,'managed',environment)
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertEqual(selected['managed'],result.stdout.strip())
        python_spec=importlib.util.spec_from_file_location('python_selectors',ROOT/'tools/test_runtime_fixture.py')
        consumer=importlib.util.module_from_spec(python_spec);python_spec.loader.exec_module(consumer);consumer.ROOT=repository
        with patch.dict(os.environ,environment,clear=True):
            self.assertEqual(Path(selected['managed']),consumer.runtime('managed'))
        environment['DSHA_TEST_MANAGED_RUNTIME']=selected['raw']
        result=self.select_node(selector,'managed',environment)
        self.assertNotEqual(0,result.returncode)
        self.assertIn('wrong fixture kind',result.stderr)
        with patch.dict(os.environ,environment,clear=True),self.assertRaisesRegex(ValueError,'IDENTITY_MISMATCH'):
            consumer.runtime('managed')

    def test_selectors_reject_unknown_kind_stale_version_and_changed_current_inputs(self):
        repository,selector,selected=self.selector_repository()
        environment={key:value for key,value in os.environ.items() if not key.startswith('DSHA_')}
        unknown=self.select_node(selector,'historical',environment)
        self.assertNotEqual(0,unknown.returncode);self.assertIn('KIND_INVALID',unknown.stderr)
        marker=Path(selected['raw'])/'dsha-test-runtime.json';proof=json.loads(marker.read_text())
        proof['dshVersion']='0.1.5-rc.2';marker.write_text(json.dumps(proof))
        stale=self.select_node(selector,'raw',environment)
        self.assertNotEqual(0,stale.returncode);self.assertIn('kind/version',stale.stderr)
        proof['dshVersion']=json.loads((repository/'tools/dsh-runtime/package.json').read_text())['dependencies']['@deepseek-ai/dsh']
        self.put(repository,'app/src/main/assets/current-patch.json','current patch')
        proof['overlayInputs']={'app/src/main/assets/current-patch.json':hashlib.sha256(b'current patch').hexdigest()}
        marker.write_text(json.dumps(proof));self.put(repository,'app/src/main/assets/current-patch.json','changed current patch')
        changed=self.select_node(selector,'raw',environment)
        self.assertNotEqual(0,changed.returncode);self.assertIn('patch inputs changed',changed.stderr)

    def test_wrong_dsh_generation_fails_before_upstream_module_execution(self):
        repository,selector,selected=self.selector_repository()
        self.put(Path(selected['raw']),'node_modules/@deepseek-ai/dsh/package.json',json.dumps({'version':'0.1.7-rc.2'}))
        result=self.select_node(selector,'raw',{key:value for key,value in os.environ.items() if not key.startswith('DSHA_')})
        self.assertNotEqual(0,result.returncode);self.assertIn('Wrong runtime generation',result.stderr)


if __name__=='__main__':unittest.main()
