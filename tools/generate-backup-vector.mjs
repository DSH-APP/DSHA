#!/usr/bin/env node
// 固定测试向量由 Node/OpenSSL 独立生成；这些固定 salt/nonce 绝不用于生产导出。
import {pbkdf2Sync,createCipheriv} from 'node:crypto';
import {mkdir,writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const password='DSHA fixture 备份 2026',plaintext=Buffer.from('DSHA v5 interoperability fixture\n对话与附件\n','utf8');
const salt=Buffer.from('000102030405060708090a0b0c0d0e0f','hex'),nonce=Buffer.from('202122232425262728292a2b','hex');
const header=Buffer.alloc(48);header.write('DSHABAK5');header.writeUInt32BE(5,8);header.writeUInt32BE(600000,12);header.writeUInt32BE(1,16);salt.copy(header,20);nonce.copy(header,36);
const key=pbkdf2Sync(Buffer.from(password,'utf8'),salt,600000,32,'sha256'),cipher=createCipheriv('aes-256-gcm',key,nonce);cipher.setAAD(header);
const ciphertext=Buffer.concat([cipher.update(plaintext),cipher.final()]),tag=cipher.getAuthTag();
const record={fixture:'Synthetic test-only data. Not a production key, nonce or password.',generator:'Node.js/OpenSSL PBKDF2-HMAC-SHA256 + AES-256-GCM',password,plaintextHex:plaintext.toString('hex'),headerHex:header.toString('hex'),derivedKeyHex:key.toString('hex'),ciphertextHex:ciphertext.toString('hex'),tagHex:tag.toString('hex'),archiveHex:Buffer.concat([header,ciphertext,tag]).toString('hex')};
const directory=path.join(root,'app/src/test/resources/backups');await mkdir(directory,{recursive:true});await writeFile(path.join(directory,'v5-aesgcm-vector.json'),JSON.stringify(record,null,2)+'\n');key.fill(0);
console.log('Generated synthetic v5 interoperability vector.');
