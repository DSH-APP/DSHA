#!/usr/bin/env python3
"""Generated writer bytes and real small generator checks; no full runtime/tar generation."""
import ast
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from source_text import write_text, matches_text

ROOT=Path(__file__).resolve().parents[1]


def module(name):
    spec=importlib.util.spec_from_file_location(name.replace('-','_'),ROOT/'tools'/name)
    result=importlib.util.module_from_spec(spec);spec.loader.exec_module(result);return result


class SourceTextTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
    def tearDown(self):self.temp.cleanup()
    def test_explicit_writer_is_lf_even_when_default_open_is_windows_translation(self):
        real_open=Path.open
        def windows_default(path,*args,**kwargs):
            if args and args[0]=='w' and 'newline' not in kwargs:kwargs['newline']='\r\n'
            return real_open(path,*args,**kwargs)
        text='first\n第二行\n'
        with patch.object(Path,'open',windows_default):write_text(self.root/'generated.txt',text)
        self.assertEqual(text.encode('utf-8'),(self.root/'generated.txt').read_bytes())
    def test_exact_checks_reject_crlf_instead_of_hiding_it(self):
        target=self.root/'metadata.json';target.write_bytes(b'{\r\n}\r\n')
        self.assertFalse(matches_text(target,'{\n}\n'))
        write_text(target,'{\n}\n');self.assertTrue(matches_text(target,'{\n}\n'))
    def test_package_recipe_bytes_are_not_normalized_or_doubled(self):
        raw=b'const original=1;\r\nconst tail=2;\n';target=self.root/'recipe-derived.js'
        write_text(target,raw.decode('utf-8'));self.assertEqual(raw,target.read_bytes())
    def test_real_ui_generator_repairs_preexisting_crlf(self):
        ui=module('prepare-ui-languages.py');source=self.root/'tools/i18n/messages.json';source.parent.mkdir(parents=True)
        source.write_bytes(json.dumps([dict(zh='完整模板 %s',en='Complete template %s',uiFormat=True)]).encode('utf-8'))
        output=self.root/'output';target=output/'com/deepseekharness/app/util/UiMessages.java'
        with contextlib.redirect_stdout(io.StringIO()):ui.generate(self.root,output)
        expected=target.read_bytes();self.assertNotIn(b'\r',expected);target.write_bytes(expected.replace(b'\n',b'\r\n'))
        with contextlib.redirect_stdout(io.StringIO()):ui.generate(self.root,output)
        self.assertEqual(expected,target.read_bytes())
    def test_real_builtin_verify_refuses_crlf_then_writer_repairs_it(self):
        generator=module('generate-builtin-plugins.py');generator.ROOT=self.root
        source=self.root/'app/src/main/assets/builtin-plugins.json';source.parent.mkdir(parents=True);source.write_bytes((ROOT/'app/src/main/assets/builtin-plugins.json').read_bytes())
        target=self.root/'app/src/main/java/com/deepseekharness/app/util/BuiltinPluginRegistry.java';target.parent.mkdir(parents=True)
        expected=generator.generate().encode('utf-8');target.write_bytes(expected.replace(b'\n',b'\r\n'))
        with patch('sys.argv',['generator','--verify']):
            with self.assertRaises(SystemExit):generator.main()
        with patch('sys.argv',['generator']):generator.main()
        self.assertEqual(expected,target.read_bytes())
    def test_real_descriptor_writer_and_check_use_exact_bytes(self):
        descriptor=module('prepare-runtime-descriptor.py');descriptor.ROOT=self.root;target=self.root/'app/src/main/assets/runtime-descriptor.json';target.parent.mkdir(parents=True)
        with patch.object(descriptor,'build_descriptor',return_value={'version':1,'runtimeId':'synthetic-fixture'}),contextlib.redirect_stdout(io.StringIO()):
            descriptor.main(['--write']);expected=target.read_bytes();self.assertNotIn(b'\r',expected)
            target.write_bytes(expected.replace(b'\n',b'\r\n'))
            with self.assertRaises(SystemExit):descriptor.main(['--check'])
            descriptor.main(['--write']);descriptor.main(['--check'])
            self.assertEqual(expected,target.read_bytes())
    def test_all_requested_generators_use_explicit_writer_and_py39_helper(self):
        names=['build-dsh-runtime.py','prepare-backup-assets.py','prepare-runtime-descriptor.py','prepare-standard-assets.py','prepare-recovery-assets.py','prepare-ui-languages.py','prepare-dsh-runtime.py','generate-builtin-plugins.py','prepare-runtime-tools.py','prepare-test-runtime.py']
        for name in names:
            tree=ast.parse((ROOT/'tools'/name).read_text(encoding='utf-8'))
            self.assertFalse(any(isinstance(n,ast.Call) and isinstance(n.func,ast.Attribute) and n.func.attr=='write_text' for n in ast.walk(tree)),name)
            self.assertTrue(any(isinstance(n,ast.ImportFrom) and n.module=='source_text' for n in ast.walk(tree)),name)
        ast.parse((ROOT/'tools/source_text.py').read_text(encoding='utf-8'),feature_version=(3,9))
        credential=module('generate-credential-paths.py');value=credential.generate(credential.SOURCE.read_bytes());self.assertNotIn(b'\r',value)


if __name__=='__main__':unittest.main()
