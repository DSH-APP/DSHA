#!/usr/bin/env python3
"""Guard the reviewed ownership boundaries, without pretending to prove all Java dependencies."""
import re
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/'app/src/main/java/com/deepseekharness/app'
errors=[]
for file in (JAVA/'util').glob('*.java'):
    source=file.read_text(encoding='utf8')
    if re.search(r'^import\s+(?:android|androidx)\.',source,re.M):errors.append(str(file)+': Android import in pure policy')
for file in (JAVA/'runtime').glob('*.java'):
    source=file.read_text(encoding='utf8')
    if re.search(r'com\.deepseekharness\.app\.(?:BackupManager|core\.(?:RuntimeTasks|MaintenanceCoordinator|ConfigStore|ColdInstallDiagnostics))\b',source):
        errors.append(str(file)+': runtime depends on Android maintenance authority')
for tree in ('main','standard','low'):
    for file in (ROOT/f'app/src/{tree}/java').rglob('*.java'):
        source=file.read_text(encoding='utf8')
        constructors=re.findall(r'new\s+(?:com\.deepseekharness\.app\.core\.)?HarnessController\s*\(',source)
        if file.name=='HarnessController.java':
            continue
        if file.name=='DshaApp.java' and tree=='main':
            if len(constructors)!=1 or not re.search(
                    r'harnessOwner\.get\s*\(\s*\(\s*\)\s*->\s*new\s+(?:com\.deepseekharness\.app\.core\.)?HarnessController\s*\(\s*this\s*\)',source):
                errors.append(str(file)+': application composition must own exactly one lazy controller construction')
        elif constructors:
            errors.append(str(file)+': independent production controller owner')
coordinator=(JAVA/'core/MaintenanceCoordinator.java').read_text(encoding='utf8')
if re.search(r'\b(?:PtyTerminalFragment|TerminalFragment)\b',coordinator):
    errors.append('MaintenanceCoordinator depends on terminal view owners')
for name in ('PtyTerminalFragment','TerminalFragment'):
    source=(JAVA/f'ui/{name}.java').read_text(encoding='utf8')
    if re.search(r'new\s+TerminalTabs\s*<',source):
        errors.append(name+': independent terminal tab owner')
    if re.search(r'\b(?:ptyTabs|simpleTabs)\(\)\.(?:add|remove|select|beginClose|closeFailed)\s*\(',source):
        errors.append(name+': terminal view mutates owner table')
owner=(JAVA/'core/TerminalSessionOwner.java').read_text(encoding='utf8')
if re.search(r'public\s+TerminalTabs<[^>]+>\s+(?:ptyTabs|simpleTabs)\s*\(',owner):
    errors.append('TerminalSessionOwner exposes mutable tab table')
tabbar=(JAVA/'ui/TerminalTabBar.java').read_text(encoding='utf8')
if not re.search(r'render\s*\(View\s+root,\s*TerminalTabs\.ReadOnly<',tabbar):
    errors.append('TerminalTabBar must consume read-only tab view')
if errors:raise SystemExit('\n'.join(errors))
print('PASS pure policies; runtime maintenance/configuration/diagnostic ports; exactly one application-owned controller construction')
