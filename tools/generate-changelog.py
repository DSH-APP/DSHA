"""Generate the bundled locale changelog from release source notes."""
import argparse
import re
from pathlib import Path
from source_text import write_text
ROOT=Path(__file__).resolve().parents[1]
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--check',action='store_true');a=p.parse_args()
 match=re.search(r'^\s*versionCode\s+(\d+)\s*$', (ROOT/'app/build.gradle').read_text(encoding='utf8'), re.M)
 if not match:raise SystemExit('CHANGELOG_VERSION_CODE_MISSING')
 code=int(match.group(1))
 for locale,folder in [('zh','raw'),('en','raw-en')]:
  source=ROOT/f'docs/releases/build{code}-changes.{locale}.md'
  target=ROOT/f'app/src/main/res/{folder}/changelog.md'
  text=source.read_text(encoding='utf8')
  if a.check:
   if not target.is_file() or target.read_text(encoding='utf8')!=text:raise SystemExit('CHANGELOG_STALE:'+locale)
  else:target.parent.mkdir(parents=True,exist_ok=True);write_text(target,text,encoding='utf8')
 print('PASS bundled changelog from locale release notes')
