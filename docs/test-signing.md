# 测试包固定签名

测试包（`.test` / `.debug` / `.dev`）如果每次 CI 都用一次性密钥签名，两次构建之间无法覆盖安装，
真机升级路径就没法连续验证。本仓库把测试证书的 **SHA-256 固定**在 `ci/test-signing.json`，
并只把公开证书提交进仓库（`docs/test-signing/test-cert.pem`）；私钥只存在仓库 Secrets 里。

## 固定值

| 项 | 位置 |
|---|---|
| 证书指纹 | `ci/test-signing.json` → `certificateSha256` |
| 证书公钥 | `docs/test-signing/test-cert.pem` |
| 私钥 | GitHub Secrets `SUNSET_TEST_KEYSTORE_B64`（PKCS#12 的 base64） |
| 口令 | GitHub Secrets `SUNSET_TEST_STORE_PASSWORD` |

测试证书必须与 `mod`（A4B0）和 `upstream`（E7E3）两个正式证书**都不同**；`tools/test-signing.py`
会强制检查，工作流里也会再比对一次。

## 配置 / 轮换

需要仓库 admin 才能写 Secrets。在本机生成新证书并把指纹写进仓库：

```bash
python3 tools/test-signing.py --bootstrap --out /secure/dsha-test-signing --write-pin
# 产物：sunset-test.p12、keystore.b64、password.txt、test-cert.pem
```

然后把 `keystore.b64` 的内容设为 `SUNSET_TEST_KEYSTORE_B64`、`password.txt` 的内容设为
`SUNSET_TEST_STORE_PASSWORD`，提交 `ci/test-signing.json`、`docs/test-signing/test-cert.pem`
与本文档。轮换会让新旧测试包不能互相覆盖，属于预期行为。

## 工作流行为

- `_node-test-packages.yml`：签名后用 `python3 tools/test-signing.py --check-pem` 核对指纹。
- `require_pinned_cert=true`（发布或正式 Release 附带测试包时）：钉为空或不一致直接失败。
- `test-package-variants.yml` 的 `publish=true`：要求已配置测试证书且指纹一致，然后把 APK 发布为
  `test-build-<run>-<时间>` prerelease；未配置证书时不会用一次性密钥冒充“固定签名”。
- 未配置证书的普通手动构建仍可用一次性密钥，但会在日志里明确告警，产物不能覆盖升级。

## 校验

```bash
python3 tools/test-signing.py --show
python3 tools/test-signing.py --check-distinct
python3 tools/test-signing.py --check-pem docs/test-signing/test-cert.pem --require-pinned
```
