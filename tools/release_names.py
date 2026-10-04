"""Release file names stay independent from the diagnostic APK version name."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

def apk_name_version(root=ROOT):
    build = (Path(root) / 'app/build.gradle').read_text(encoding='utf-8')
    match = re.search(r"ext\.dshaApkNameVersion\s*=\s*['\"]([^'\"]+)['\"]", build)
    if not match:
        match = re.search(r'versionName\s+"([^"]+)"', build)
    if not match or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,100}', match[1]):
        raise ValueError('DELIVERY_APK_NAME_VERSION')
    return match[1]

def apk_filename(flavor, root=ROOT):
    if flavor not in ('standard', 'low'):
        raise ValueError('DELIVERY_FLAVOR')
    return f'dsha-{apk_name_version(root)}{"low" if flavor == "low" else ""}.apk'
