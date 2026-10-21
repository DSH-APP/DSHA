# Host data archive v5

> Translation verified: 2026-10-21. Source baseline: the current Chinese v5 format document and the F10 `087724c` maintenance baseline. This is a format and implementation reference, not a new build or device acceptance result. Final build identity and executed scope are in the [English README](../../README.en.md) and [release introduction](release-introduction.md).

Build 131 added backward-compatible `dataCompatibility` metadata recording the model schema and observed runtime/read-write implementation digests. Without a reliable format declaration, `formatId` and `formatEpoch` are empty and state is `unknown`. APK version, runtime version, and data format are different generations. A sender's declaration is not local verification: preflight continues to show an unconfirmed format and does not use it for blind downgrades or automatic deletion of originals. Host restore with an established scope still applies. Retained-copy and old-tree entry points are described in the [historical stability acceptance record](../stability-acceptance.md).

Implementations are `backup/BackupArchive.java` and `backup/PortableBackupCrypto.java`. Integration and validation history is in the [historical acceptance record](../backup-upgrade-acceptance.md). v5 does not redefine historical v1–v4; the existing Python engine actually writes format 4.

## Portable files

A portable file is named `DSHA-data-v5-<UUID>.tar.gz`. It is standard tar/gzip readable by ordinary tools and contains `portable.dshbak` and `README.txt`. All user exports are password-free. The inner `DSHADATA` container has a complete trailing digest; the UI has no new-password or encryption checkbox. Internal automatic copies remain encrypted with a host-generated random key and stored as `portable.dshbak`. Exporting an automatic copy first authenticates/decrypts it and generates a new password-free container, preserving the encrypted original. Re-export does not collect the workspace again or increase the actual scope or integrity level.

Import fully checks gzip CRC, tar headers, and the end of the stream. The outer archive may contain only one `portable.dshbak` and a short `README.txt`, both ordinary files. Extra members, links, escaping paths, oversized entries, and truncation are rejected. Inner digest checks and actual-data-scope preflight then apply. Historical encrypted inputs still require full AEAD authentication; a `.tar.gz` filename is not evidence of a full backup. Old bare `.dshbak` and historical `.tar.gz`/`.tgz`/`.tar` backups remain readable by content headers. Renaming a raw encrypted/DSHADATA container to gzip is not a valid conversion.

Importing a historical encrypted backup or re-exporting a historical manually encrypted copy still requires its original password to read that original. Unreadable originals are retained and reported; encrypted payload is not copied unchanged and described as password-free. New password-free input does not show a password field. After complete authentication and source-digest checks, re-export changes `sensitivePolicy=UNENCRYPTED`, retains the other manifest facts and records, and regenerates and verifies the trailing digest.

All multibyte integers use big-endian order. Historical encrypted containers and internal automatic copies have a 48-byte encryption header:

| Offset | Length | Meaning |
|---:|---:|---|
| 0 | 8 | ASCII `DSHABAK5` |
| 8 | 4 | Format version, 5 |
| 12 | 4 | PBKDF2 iteration count; 600000 for new files |
| 16 | 4 | Algorithm suite, 1 |
| 20 | 16 | Random salt |
| 36 | 12 | Random GCM nonce |

Suite 1 derives a 32-byte AES key using PBKDF2-HMAC-SHA256 and encrypts using AES-256-GCM. The complete header is AAD. Ciphertext follows the header; the last 16 bytes are the GCM authentication tag. Each internal encrypted copy generates an independent salt and nonce. Readers accept only 600000–1200000 iterations and reject untrusted parameters before derivation.

Passwords are encoded directly as UTF-8 without Unicode normalization. The current interface accepts 12–1024 Java UTF-16 units. Passwords, derived keys, and intermediate buffers are not written into task records or preferences. Clearable memory arrays are erased at task completion, without a promise to eliminate every historical copy in the VM or flash storage.

A decryption stream may emit plaintext before tag verification, so it writes only to a private quarantine file owned by that task. Archive parsing and restore planning start only after `doFinal` succeeds and the outer input is fully consumed. SHA-256 establishes content integrity, not sender identity. Excluding the native API key does not mean all contents are redacted: chats, projects, `.env`, and plugin settings may contain secrets. Password-free exports provide no confidentiality and should be stored in trusted locations; user export offers no password-encryption option.

Derivation parameters follow the [OWASP PBKDF2-HMAC-SHA256 guidance](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html). Cryptographic code uses the locked Bouncy Castle lightweight API without replacing the system Provider. Library versions, digests, and licenses are in `tools/backup-dependencies.lock.json` and `THIRD_PARTY_NOTICES.md`.

## Plaintext container

The container is an uncompressed streaming sequence of records:

1. Eight ASCII bytes `DSHADATA`, followed by a four-byte version number 5.
2. Each record: four-byte JSON metadata length, UTF-8 JSON, eight-byte payload length, then payload.
3. Four-byte `-1` ends the records, followed by a four-byte manifest length and UTF-8 manifest.
4. A 32-byte SHA-256 covers every preceding container byte, including the end marker, manifest length, and manifest contents. EOF must immediately follow the digest.

Required record fields are `root`, `path`, `kind`, `scope`, `size`, and `sha256`. Optional `mode` is a permission value from 0–0777. `FILE` carries its actual payload and per-file SHA-256; other kinds must have zero payload and an empty digest. Kinds include `DIRECTORY`, `LINK`, `MISSING`, `UNREADABLE`, and `EXCLUDED`, keeping explicit empty directories, absent directories, and read errors distinct. Links carry `target`; failed/excluded records may carry `reason`.

Scopes are `application`, `sessions`, `settings`, `plugins`, and `projects`. An `application` logical root may contain records of different categories; a root declared with a specific scope must contain only that scope. Android exports split data into subroots, and local mapping determines restore targets. Unknown logical roots or scopes do not automatically become a full application restore.

The manifest must contain format, entry count, payload bytes, creation time, app version, runtime provenance, operation type, data-format declaration, sensitive-content policy, plugin-dependency information, logical-root descriptions, and integrity level. Root descriptions must match the roots actually present in the payload in both directions. Duplicate members, conflicting paths, incorrect sizes/digests, truncation, and trailing bytes are rejected.

Integrity levels:

- `QUIESCENT`: application writers have stopped and sources have been checked again.
- `EXTERNAL_CHECKED`: application writers have stopped and sources that external apps may still change are read and checked repeatedly; other apps are not claimed to be frozen.
- `BEST_EFFORT`: read-only rescue without an application-level quiescence guarantee.
- `PARTIAL`: missing, unreadable, or changed items exist, with the specific entries/sources recorded in the manifest.

## Limits and paths

Limits are 16 GiB total payload, 100000 records, and 512 logical roots. Per-record metadata is limited to 16 KiB, the final manifest to 2 MiB, aggregate record metadata to 32 MiB, paths to 2048 characters and 64 levels, and link depth to 40. The container/encrypted stream has an additional allowance of at most 256 MiB structural overhead. Counts use actual bytes read/written.

Absolute write paths, drive letters, backslashes, NUL, empty path segments, `.`, `..`, and newlines are rejected. An empty relative path within a root denotes that logical root itself. Collision keys use NFC plus case folding to conservatively reject names that might collide across filesystems. Unsupported naming combinations return an error rather than silently dropping entries.

The parser writes payload into private `payload/<sequence>` slots; archive paths do not directly become active write locations. Android target mapping rechecks allowed roles/names: native settings use a whitelist; plugins, executable scripts, and shared Cordis patches enter quarantine. PID, environment markers, bridge credentials, and old transaction state remain in the input copy rather than applying to this device.

## Historical readers

`LegacyTarReader` checks tar headers, PAX/GNU long-name limits, member types, and outer gzip CRC/ISIZE, and reads through complete EOF. It rejects special files, sparse extensions, escaping paths, duplicate members, concatenated gzip, and trailing data. Old soft/hard links may refer only to verified payload inside the archive, not to host paths asserted by the archive.

`LegacyBackupImporter` privately converts recognized v1–v4 inputs to v5 before using the same preflight. v3/v4 require inventory/payload agreement in both directions. An old archive without scope enters a confirmation-required import flow only if an explicit historical naming pattern is recognized; unknown scope is rejected. Historical coverage remains mainly synthetic samples and does not establish validation of every old user-data path.

## Independent fixed vector

`tools/generate-backup-vector.mjs` uses Node/OpenSSL to generate `app/src/test/resources/backups/v5-aesgcm-vector.json`. Its password, salt, nonce, plaintext, and key are public synthetic test data, never production values. `PortableBackupCryptoTest.independentOpenSslFixedVectorAndJceInteroperate` checks that Bouncy Castle decryption and JCE derivation/decryption yield identical results.

## When an export is complete

Private output is read in full and checked for format/digest; an encrypted source also requires complete authenticated decryption. New user exports write password-free tar.gz into a newly created SAF/Downloads target. Readback compares the digest of that actual output file, not an old ciphertext digest. If readback is unavailable, the UI reports “Written; destination readback unverified,” retains private verified output, and does not update the last successful backup. Cancel/failure does not replace the previous successful destination record.

The native page can re-export a retained private copy. `VerifiedBackupCopy` first reads its original `verified.json` and checks type, size, format marker, and full source-file SHA-256. `NativeBackupJobs.reexport` then uses `PasswordFreeBackupExport` to fully verify the source, authenticate/decrypt old encrypted payload, and generate a new password-free tar.gz. Internal automatic copies use the local password; historical manually encrypted copies still require their original password to read. The encrypted original is not modified. This relies on the prior local verification record and current source checks, not sender authentication. New records retain `requestedScope`; older records derive scope from declared logical roots and reject missing/unknown scope. Re-export does not recollect workspaces or increase PARTIAL, BEST_EFFORT, or dependency-warning levels. Unavailable destination readback still prevents an update to the last successful backup. See [backup and restore](backup-and-restore.md) for operations and failure boundaries.
