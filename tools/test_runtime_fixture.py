"""统一 Python 发布门禁的测试夹具身份校验。"""
from pathlib import Path
import hashlib,json,os
from runtime_fixture_proof import validate as validate_content
ROOT=Path(__file__).resolve().parents[1]
def sha(path):
 h=hashlib.sha256()
 with path.open('rb') as f:
  for block in iter(lambda:f.read(1024*1024),b''):h.update(block)
 return h.hexdigest()

def runtime_input_sha(root,file,archive_recipe=False):
 path=Path(root)/file
 if archive_recipe and file=='app/src/main/assets/managed-runtime-inputs.json':
  installs=[row for row in json.loads(path.read_text(encoding='utf8'))['installs']
            if row.get('target','').startswith('usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/dsha-destructive-policy/')]
  return hashlib.sha256(json.dumps(installs,sort_keys=True,separators=(',',':')).encode()).hexdigest()
 return sha(path)
def identity(kind='raw',selected=None,require_current_archive=False):
 if kind not in ('raw','managed'):raise ValueError('TEST_FIXTURE_KIND_INVALID')
 if selected is None:
  selected=os.environ.get('DSHA_TEST_RUNTIME' if kind=='raw' else 'DSHA_TEST_MANAGED_RUNTIME')
 if not selected:
  pointer=ROOT/'app/build/test-runtimes/current.json'
  if not pointer.is_file():raise ValueError('Run python tools/prepare-test-runtime.py first')
  selected=json.loads(pointer.read_text(encoding='utf8'))[kind]
 directory=Path(selected).absolute()
 if directory.is_symlink() or getattr(directory,'is_junction',lambda:False)():raise ValueError('TEST_FIXTURE_ROOT_LINK')
 directory=directory.resolve();marker=directory/'dsha-test-runtime.json'
 if marker.is_symlink() or not marker.is_file():raise ValueError('TEST_FIXTURE_MARKER_TYPE')
 proof=json.loads(marker.read_text(encoding='utf8'))
 expected=json.loads((ROOT/'tools/dsh-runtime/package.json').read_text(encoding='utf8'))['dependencies']['@deepseek-ai/dsh']
 actual=json.loads((directory/'node_modules/@deepseek-ai/dsh/package.json').read_text(encoding='utf8'))['version']
 if proof.get('kind')!=kind or proof.get('dshVersion')!=expected or actual!=expected or proof.get('lockSha256')!=sha(ROOT/'tools/dsh-runtime/package-lock.json'):raise ValueError('TEST_FIXTURE_IDENTITY_MISMATCH')
 for file,digest in proof.get('archiveRecipeInputs',{}).items():
  if runtime_input_sha(ROOT,file,True)!=digest:raise ValueError('TEST_FIXTURE_PATCH_INPUT_CHANGED:archive:'+file)
 for file,digest in proof.get('overlayInputs',{}).items():
  if runtime_input_sha(ROOT,file)!=digest:raise ValueError('TEST_FIXTURE_PATCH_INPUT_CHANGED:overlay:'+file)
 if require_current_archive and (not proof.get('archiveCurrent') or proof.get('archiveSha256')!=sha(ROOT/'app/src/main/assets/dsh-runtime.bin')):raise ValueError('TEST_FIXTURE_ARCHIVE_STALE')
 return directory,proof

def runtime(kind='raw',selected=None,full=False,require_current_archive=False):
 directory,proof=identity(kind,selected,require_current_archive)
 from runtime_fixture_session import consume
 # A runner receipt requires the live parent, independent secret and exact current marker/metadata.
 # Standalone callers always compute the complete byte proof.
 if not consume(directory,proof):validate_content(directory,proof)
 return directory
