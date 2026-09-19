#!/usr/bin/env python3
"""从已锁定的 dsh 覆盖层生成包来源证明；只排除内容及文件位完全一致的可重建依赖。"""
from pathlib import Path
import tarfile,hashlib,json
root=Path(__file__).resolve().parents[1]
archive=root/'app/src/main/assets/dsh-runtime.bin'
prefix='usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/'
records={}
with tarfile.open(archive,'r:gz') as tar:
 for item in tar:
  if not item.name.startswith(prefix):continue
  rel=item.name[len(prefix):];parts=rel.split('/');count=2 if parts[0].startswith('@') else 1
  if len(parts)<=count:continue
  name='/'.join(parts[:count]);path='/'.join(parts[count:])
  if item.isfile():
   sha=hashlib.sha256()
   with tar.extractfile(item) as stream:
    for chunk in iter(lambda:stream.read(1024*1024),b''):sha.update(chunk)
   value=path+'\0FILE\0'+str(item.mode&0o777)+'\0'+sha.hexdigest()+'\n'
  elif item.issym():value=path+'\0LINK\0'+item.linkname+'\n'
  elif item.isdir():continue
  else:raise ValueError('unsupported package entry')
  records.setdefault(name,[]).append((path,value))
proofs={name:hashlib.sha256(''.join(value for path,value in sorted(rows,key=lambda row:row[0].encode('utf-16be'))).encode()).hexdigest() for name,rows in records.items()}
output={'version':1,'archiveSha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'packages':dict(sorted(proofs.items()))}
(root/'app/src/main/assets/managed-package-proofs.json').write_text(json.dumps(output,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
print('managed package proofs:',len(proofs))
