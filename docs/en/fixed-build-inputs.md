# Fixed build inputs (public-repository prerelease asset)

Updated 2026-11-05 (build328 adds the 12th fixed input, `gh-cli.tar.gz` = the official GitHub CLI v2.103.3 linux-arm64 archive, as the only source of the managed `opt/gh/bin/gh`; the 0.2.2-rc.1 runtime and emergency archives are unchanged; since build330 the APK ships the same bytes under the name `gh-cli.bin`, the source fixed-input name stays unchanged and the bundle is not rebuilt). The `fixed-inputs-20261105e` bundle is uploaded and published as a prerelease; the lock file is [`ci/sunset-inputs.lock.json`](../../ci/sunset-inputs.lock.json).

## Why the fixed inputs are not stored in git

The 8 large fixed binaries/archives (the largest, `offline-rootfs.bin`, is 304 MB; `gh-cli.tar.gz` is 13.7 MB) plus the 2 independent recovery
archives total about 684 MiB (the bundle is 716,787,167 B). Committing them would exceed repository and git single-file limits, permanently bloat
history, and duplicate bytes that managed recipes can regenerate and that are already pinned by SHA-256. They are
therefore published only as **GitHub Release assets**:

- the repository keeps the lock file (URL, SHA-256, format, release, asset) and the per-input pins in
  [`app/src/main/assets/runtime-descriptor.json`](../../app/src/main/assets/runtime-descriptor.json),
  [`tools/ci-build-inputs.lock.json`](../../tools/ci-build-inputs.lock.json),
  [`app/src/main/assets/adb-wheels.lock.json`](../../app/src/main/assets/adb-wheels.lock.json), and
  [`tools/recovery-runtime/lock.json`](../../tools/recovery-runtime/lock.json);
- the large bytes live in the prerelease asset and are restored from the lock after cloning; any mismatch is rejected
  before anything is written.

The 2 provenance `*.inputs.json` files are small and remain tracked in the repository. They are also packed into the
asset so the restoration set stays a single 12-member source; restored bytes are identical to the tracked files.
`.gitignore` lists the 10 non-tracked inputs; because the two `*.inputs.json` files are already tracked, the ignore
rules do not apply to them, which is expected.

## Asset contents (12 members, STORED zip)

| ZIP member | Bytes | Current pin source |
|---|---:|---|
| `app/src/main/assets/offline-rootfs.bin` | 304037588 | `runtime-descriptor.json` |
| `app/src/main/assets/dsh-runtime.bin` | 131056363 | `runtime-descriptor.json` |
| `app/src/main/assets/ubuntu-tools.bin` | 29919475 | `runtime-descriptor.json` |
| `app/src/main/assets/pnpm-runtime.bin` | 3837861 | `runtime-descriptor.json` |
| `app/src/main/assets/python-support.bin` | 904360 | `tools/ci-build-inputs.lock.json` (size + digest) |
| `app/src/main/assets/glibc-python.tar.gz` | 6565414 | `runtime-descriptor.json` |
| `app/src/main/assets/adb-wheels.tar.gz` | 9951074 | `app/src/main/assets/adb-wheels.lock.json` |
| `app/src/main/assets/gh-cli.tar.gz` | 13661485 | `tools/gh-cli/packages.lock.json` (archive + member + license digest) |
| `app/src/main/assets/dsh-runtime.inputs.json` | 11629 | provenance (no separate binary pin) |
| `app/src/main/assets/ubuntu-tools.inputs.json` | 592 | provenance (no separate binary pin) |
| `tools/recovery-runtime/archives/recovery-rootfs.bin` | 85783057 | `tools/recovery-runtime/lock.json` |
| `tools/recovery-runtime/archives/recovery-dsh-runtime.bin` | 131056363 | `tools/recovery-runtime/lock.json` |

The set covers the Ubuntu offline rootfs, DSH runtime, ubuntu tools, pnpm runtime, Python support, glibc Python,
ADB wheels, the GitHub CLI (gh), plus the independent emergency DSH rootfs/runtime archives. The S1 archive bundles DSH
`0.2.2-rc.1` (131,056,363 B / SHA-256 `c1bd68330a10776f304a92a19d8f82c184c1abf523275a9e28dbb2c2e764e4fe`). S1 synchronizes the independent emergency lock to the same `0.2.2-rc.1` bytes and the T54+T58 rebuild re-synchronizes it to the new archive bytes, with separate locks and extracted roots; `recovery-asset-locations.json` maps only the package bytes. build328 adds `gh-cli.tar.gz` without changing the emergency lock: `tools/recovery-runtime/lock.json` and both recovery archive bytes are untouched and are still carried verbatim in this bundle. The locks and verification contracts remain independent; a regular upgrade never changes the emergency lock automatically.

**APK packaging name separated from the source name (build330)**: the source fixed input is always
`app/src/main/assets/gh-cli.tar.gz`, and both this bundle and the `runtime-descriptor.json` pin keep that name; the APK
member is `assets/gh-cli.bin`. AGP 9.1.1's asset merge automatically gunzips `.gz` files and drops the extension, so the
official build328/329 APKs only contain `assets/gh-cli.tar` and a managed install opening `gh-cli.tar.gz` must fail. The
`sourceTransforms` entry in `tools/asset-deployment.json` and `tools/prepare-standard-assets.py` perform the rename,
`RuntimeTools` opens the packaged name (falling back to the source name for old packages), and
`tools/verify-apk-managed-assets.py` checks the actual APK member names, byte digests and the pinned gh member in the
production and test-package build chains so a silent rename cannot recur.

## Lock and restoration

Fields in [`ci/sunset-inputs.lock.json`](../../ci/sunset-inputs.lock.json):

```json
{
  "schema": 1,
  "url": "https://github.com/SunsetRNE/DSHA-Sunset-mod/releases/download/fixed-inputs-20261105e/dsha-a-su-fixed-inputs-20261105e.zip",
  "sha256": "be97d9f0a45f408d3a7de83ae55a00a85a5154343589571c6d159a0fe58f08f5",
  "format": "bundle",
  "release": "fixed-inputs-20261105e",
  "asset": "dsha-a-su-fixed-inputs-20261105e.zip",
  "bytes": 716787167
}
```

When no explicit paired `SUNSET_DISPATCH_*` / `SUNSET_REPOSITORY_*` override is provided,
[`tools/sunset-ci-inputs.py`](../../tools/sunset-ci-inputs.py) reads this lock and emits `url` / `sha256` / `format`.
CI passes `--source-archive` to [`tools/ci-assets.py`](../../tools/ci-assets.py) only when `format == "source-archive"`;
`bundle` restores the fixed 12-member set directly. Manual overrides require a URL + SHA pair; half a pair fails.

Restore from the lock (the CI path):

```bash
python3 tools/ci-assets.py --url "<lock.url>" --sha256 "<lock.sha256>"
```

Or reuse a local archive:

```bash
python3 tools/ci-assets.py --archive dsha-a-su-fixed-inputs-20261105e.zip \
  --sha256 be97d9f0a45f408d3a7de83ae55a00a85a5154343589571c6d159a0fe58f08f5
```

`ci-assets.py` verifies, in order: whole-archive SHA-256 → member whitelist/path/type/duplicate and total size →
extraction into a staging directory and per-item pin checks against `runtime-descriptor.inputs`,
`ci-build-inputs.lock.json`, `adb-wheels.lock.json`, and `recovery-runtime/lock.json` → atomic replacement only after
everything passes. Any failure leaves existing inputs untouched and never executes anything from the archive.

## Current asset

| Item | Value |
|---|---|
| release tag | `fixed-inputs-20261105e` (prerelease, target `main`) |
| release id | `435405154` |
| asset | `dsha-a-su-fixed-inputs-20261105e.zip` |
| bytes | `716787167` |
| SHA-256 | `be97d9f0a45f408d3a7de83ae55a00a85a5154343589571c6d159a0fe58f08f5` |
| download URL | `https://github.com/SunsetRNE/DSHA-Sunset-mod/releases/download/fixed-inputs-20261105e/dsha-a-su-fixed-inputs-20261105e.zip` |

> The bundle was uploaded and published as a public prerelease on 2026-11-05T16:07:48Z.
> `gh api repos/SunsetRNE/DSHA-Sunset-mod/releases/435405154` reports release id `435405154`, asset id `713925229` (audit only),
> `dsha-a-su-fixed-inputs-20261105e.zip` with `size=716787167`, `state=uploaded`, and remote digest
> `sha256:be97d9f0a45f408d3a7de83ae55a00a85a5154343589571c6d159a0fe58f08f5` matching the lock; the anonymous direct URL returned HTTP 200 afterwards.
> The direct URL is determined by the tag plus the asset filename and does not depend on the asset id. A same-tag `--clobber` replacement keeps the filename
> but changes the content and SHA-256, so the lock must be updated.

## Upgrade flow (new prerelease asset)

1. After inputs or pins change, repack (STORED; the member set is defined by `allowed()` in `ci-assets.py`):

   ```bash
   python3 tools/ci-assets.py --pack /root/DSHA/build-inputs/dsha-a-su-fixed-inputs-YYYYMMDD.zip
   ```

   Record the printed `sha256` and `bytes`. `--pack` first validates all 12 inputs against current pins and produces
   no asset on failure.
2. Create a new prerelease in the public repository (new dated tag such as `fixed-inputs-YYYYMMDD`). The notes must list
   members, bytes, SHA-256, and purpose. Upload the STORED bundle:

   ```bash
   gh release create fixed-inputs-YYYYMMDD --repo SunsetRNE/DSHA-Sunset-mod \
     --title '固定构建输入' --prerelease --notes-file <notes>
   gh release upload fixed-inputs-YYYYMMDD dsha-a-su-fixed-inputs-YYYYMMDD.zip \
     --repo SunsetRNE/DSHA-Sunset-mod
   ```

   Large uploads are slow; run them in the background and confirm the API-reported `size` afterwards. Keep the old
   asset for traceability; regular upgrades do not reuse an old tag.
3. Check the upload result (name/size/state; the asset id is audit-only and is not stored in the lock):

   ```bash
   gh api repos/SunsetRNE/DSHA-Sunset-mod/releases/tags/fixed-inputs-YYYYMMDD \
     --jq '.assets[] | {id, name, size, state}'
   ```

4. Update [`ci/sunset-inputs.lock.json`](../../ci/sunset-inputs.lock.json): `url` is the deterministic direct link
   `https://github.com/SunsetRNE/DSHA-Sunset-mod/releases/download/<tag>/<asset>` (determined by tag and filename,
   not by asset id), together with `sha256`, `format` (`bundle`), `release`, `asset`, and `bytes`; also update the
   corresponding assertions in [`tools/test-sunset-ci-inputs.py`](../../tools/test-sunset-ci-inputs.py).
5. Verify with `python3 tools/test-sunset-ci-inputs.py`, then restore once with `ci-assets.py` (or at minimum compare the
   API asset `size` with the local bundle and spot-check its SHA-256). During restore `ci-assets.py` checks the
   descriptor/input pins in `runtime-descriptor.json`, `ci-build-inputs.lock.json`, `adb-wheels.lock.json`, and
   `recovery-runtime/lock.json`; pin changes go through their own generators/review instead of being accommodated by
   editing the lock.

### Clobbering within one tag

Only corrections (e.g. a broken asset after an interrupted upload, or wrong notes) may replace an asset in the same tag
with `gh release upload --clobber`. Replacement changes the asset id, so the lock must be updated and the replacement
reason recorded. Normal input upgrades use a new tag and a new dated filename, preserving old assets and SHA-256
evidence.

## Related files

- [`ci/sunset-inputs.lock.json`](../../ci/sunset-inputs.lock.json): the current asset lock.
- [`tools/ci-assets.py`](../../tools/ci-assets.py): the single entry point for pack/download/verify/restore.
- [`tools/sunset-ci-inputs.py`](../../tools/sunset-ci-inputs.py): CI input resolution (lock plus explicit paired overrides).
- [`tools/test-sunset-ci-inputs.py`](../../tools/test-sunset-ci-inputs.py): host regression for the lock and resolution behavior.
- [`tools/test-ci-assets.py`](../../tools/test-ci-assets.py): regression for member-set and pin validation.
- [`app/src/main/assets/runtime-descriptor.json`](../../app/src/main/assets/runtime-descriptor.json),
  [`tools/ci-build-inputs.lock.json`](../../tools/ci-build-inputs.lock.json),
  [`app/src/main/assets/adb-wheels.lock.json`](../../app/src/main/assets/adb-wheels.lock.json),
  [`tools/recovery-runtime/lock.json`](../../tools/recovery-runtime/lock.json): per-item pin sources.
