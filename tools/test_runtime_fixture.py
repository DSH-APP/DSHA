"""统一 Python 发布门禁的测试夹具身份校验。"""
from pathlib import Path
import hashlib,json,os
ROOT=Path(__file__).resolve().parents[1]
def sha(path):
 h=hashlib.sha256()
 with path.open('rb') as f:
  for block in iter(lambda:f.read(1024*1024),b''):h.update(block)
 return h.hexdigest()
def runtime(kind='raw',selected=None,full=False,require_current_archive=False):
 if selected is None:
  pointer=ROOT/'app/build/test-runtimes/current.json'
  if not pointer.is_file():raise ValueError('Run python tools/prepare-test-runtime.py first')
  selected=json.loads(pointer.read_text(encoding='utf8'))[kind]
 directory=Path(selected).resolve();proof=json.loads((directory/'dsha-test-runtime.json').read_text(encoding='utf8'))
 expected=json.loads((ROOT/'tools/dsh-runtime/package.json').read_text(encoding='utf8'))['dependencies']['@deepseek-ai/dsh']
 actual=json.loads((directory/'node_modules/@deepseek-ai/dsh/package.json').read_text(encoding='utf8'))['version']
 if proof.get('kind')!=kind or proof.get('dshVersion')!=expected or actual!=expected or proof.get('lockSha256')!=sha(ROOT/'tools/dsh-runtime/package-lock.json'):raise ValueError('TEST_FIXTURE_IDENTITY_MISMATCH')
 for file,digest in {**proof.get('archiveRecipeInputs',{}),**proof.get('overlayInputs',{})}.items():
  if sha(ROOT/file)!=digest:raise ValueError('TEST_FIXTURE_PATCH_INPUT_CHANGED:'+file)
 if require_current_archive and (not proof.get('archiveCurrent') or proof.get('archiveSha256')!=sha(ROOT/'app/src/main/assets/dsh-runtime.bin')):raise ValueError('TEST_FIXTURE_ARCHIVE_STALE')
 if full:
  if not proof.get('moduleHashes'):raise ValueError('TEST_FIXTURE_FILE_PROOFS_MISSING')
  for file,digest in proof['moduleHashes'].items():
   if sha(directory/file)!=digest:raise ValueError('TEST_FIXTURE_BYTES_CHANGED:'+file)
 return directory
