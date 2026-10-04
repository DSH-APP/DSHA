"""Verify required actual license bytes in both APKs and inventory shipped npm packages."""
import argparse,hashlib,importlib.util,json,tarfile,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('third_party_gate',ROOT/'tools/check-third-party-notices.py')
gate=importlib.util.module_from_spec(spec);spec.loader.exec_module(gate)

def stream_digest(stream):
 value=hashlib.sha256()
 for block in iter(lambda:stream.read(1048576),b''):value.update(block)
 return value.hexdigest()
def main():
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('apks',type=Path,nargs=2);parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
 required=gate.REQUIRED_LICENSES
 gate.verify_licenses();gate.verify_native_members()
 notices=(ROOT/'THIRD_PARTY_NOTICES.md').read_text(encoding='utf-8')
 if notices!=gate.generator.render():raise ValueError('THIRD_PARTY_NOTICES_STALE')
 with (ROOT/'app/src/main/assets/dsh-runtime.bin').open('rb') as source:runtime_sha=stream_digest(source)
 receipts=[]
 for path in args.apks:
  with zipfile.ZipFile(path) as apk:
   for name in required:
    source=(ROOT/'app/src/main/assets/licenses'/name).read_bytes()
    if apk.read('assets/licenses/'+name)!=source:raise ValueError('APK_LICENSE_BYTES:'+name)
   if apk.read('assets/builtin-plugins/dsh-web-mobile/LICENSE')!=(ROOT/'app/src/main/assets/builtin-plugins/dsh-web-mobile/LICENSE').read_bytes():raise ValueError('APK_MOBILE_LICENSE_BYTES')
   if apk.read('assets/web-integration/core-js.LICENSE')!=(ROOT/'app/src/main/assets/web-integration/core-js.LICENSE').read_bytes():raise ValueError('APK_CORE_JS_LICENSE_BYTES')
   with apk.open('assets/dsh-runtime.bin') as stream:
    if stream_digest(stream)!=runtime_sha:raise ValueError('APK_THIRD_PARTY_RUNTIME_CHANGED')
   receipts.append(dict(apk=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),verifiedLicenses=required,runtimeSha256=runtime_sha))
 packages=gate.inventory(ROOT/'app/src/main/assets/dsh-runtime.bin',
                         json.loads((ROOT/'tools/dsh-runtime/package-lock.json').read_text(encoding='utf-8')),notices)
 report=dict(schema=1,apks=receipts,shippedPackageRecords=packages,note='Actual archive members; declarations are not a legal conclusion or byte-for-byte source reproducibility proof.')
 args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
 print('PASS actual APK license bytes and',len(packages),'shipped npm package records')
if __name__=='__main__':main()
