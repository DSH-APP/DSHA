#!/usr/bin/env python3
"""显式迁移已审阅的应用文案。只替换源代码字面量，不扫描运行时用户文本。"""
from pathlib import Path
import argparse,json,re,xml.etree.ElementTree as ET
from ui_language_catalog import LANGUAGES, RESOURCE_FOLDERS, load_catalog, format_signature

ROOT=Path(__file__).resolve().parents[1]
TOKEN=re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|//[^\n]*|/\*[\s\S]*?\*/|\'(?:\\.|[^\'\\])*\'')
HAN=re.compile('[\u3400-\u9fff]')
ANDROID='http://schemas.android.com/apk/res/android'
T='com.deepseekharness.app.util.UiText.text'
PRESERVED=('手机存储','环境任务进行中','重置失败','查看详情','检查插件更新','更新至 ','回退至 ','导出插件包','删除插件','复制插件名称','复制来源链接')
CODE=re.compile(r'\b(?:echo|grep|printf|throw Error|throw new|RuntimeError|function|process\.|const |await |def |import |exec |chmod|export |print\(|if old|if not|if \[)|\$\(|\\x[0-9a-fA-F]|\^|\(\?')

def decode(raw):
    return re.sub(r'\\(u[0-9a-fA-F]{4}|[0-7]{1,3}|.)',lambda m:chr(int(m[1][1:],16)) if m[1].startswith('u') else chr(int(m[1],8)) if m[1][0].isdigit() else {'n':'\n','r':'\r','t':'\t','b':'\b','f':'\f','"':'"',"'":"'",'\\':'\\'}.get(m[1],m[0]),raw)

def source(file,translated):
    text=file.read_text(encoding='utf-8');edits=[]
    if file.name in ('UiText.java','UiLanguagePreference.java'):return 0
    for match in TOKEN.finditer(text):
        raw=match.group()
        if not raw.startswith('"') or raw.startswith('"""'):continue
        value=decode(raw[1:-1]);before=text[max(0,match.start()-160):match.start()]
        if not HAN.search(raw) and value.strip() not in ('：','；','，','。','（','）','「','」'):continue
        if value not in translated or re.search(r'UiText\.(?:text|choose)\(\s*$',before):continue
        if any(marker in value for marker in PRESERVED) or CODE.search(value):continue
        # 静态目录和动作键保留原值，展示时单独取译文，避免切换语言改变标识。
        declaration=text[text.rfind(';',0,match.start())+1:match.start()]
        if re.search(r'\bstatic\s+final\b',declaration) and '=' in declaration:continue
        if re.search(r'\bcase\s*$',before):continue
        if re.search(r'\.(?:contains|startsWith|endsWith|equals|matches|indexOf|replace|replaceAll|split)\(\s*$',before):continue
        if file.name=='DshaAccessibilityService.java' and not (value.startswith(('[ERR]','OK ')) or 'Toast.makeText' in before):continue
        if file.name=='RootShellMain.java' or file.name=='RuntimeTools.java' and not ('throw new IOException(' in before or 'Log.' in before):continue
        edits.append((match.start(),match.end(),T+'('+raw+')'))
    for start,end,new in reversed(edits):text=text[:start]+new+text[end:]
    # 只给应用控件中的目录/状态取译文；输入框与终端输出保持原文。
    if '/ui/' in str(file).replace('\\','/'):
        tokens=list(TOKEN.finditer(text));masked=list(text)
        for token in tokens:
            for i in range(token.start(),token.end()):masked[i]=' '
        mask=''.join(masked);wrappers=[]
        for call in re.finditer(r'\.set(?:Text|Hint|Title|Message|ContentDescription|ContentTitle|ContentText)\(',mask):
            start=call.end();depth=1;end=start
            while end<len(mask) and depth:
                if mask[end]=='(':depth+=1
                elif mask[end]==')':depth-=1
                if depth:end+=1
            expr=text[start:end].strip();receiver=text[max(0,call.start()-60):call.start()]
            if not expr or expr=='null' or 'UiText.' in expr or expr.startswith('R.'):continue
            if re.search(r'(?i)(?:input|edit|api.?key|workdir|term_output|outputText|launchLog|fileName|pathField|modelField)',receiver):continue
            if expr in ('item.name','item.source','name','path','text','title','value','cs'):continue
            if file.name=='PtyTerminalFragment.java' and 'SensitiveData.redact(t.trim())' in expr:continue
            if ',' in mask[start:end]:continue
            wrappers.append((start,end,T+'('+text[start:end]+')'))
        for start,end,new in reversed(wrappers):text=text[:start]+new+text[end:]
    old=file.read_text(encoding='utf-8')
    if old!=text:file.write_text(text,encoding='utf-8',newline='\n')
    return len(edits)

def resource_value(value):
    value=value.replace('\\n','\n').replace('\\t','\t')
    return '"'+value.replace('\\','\\\\').replace('"','\\"').replace('\n','\\n').replace('\t','\\t')+'"'

def android_resources(root):
    data=json.loads((root/'tools/i18n/android-resources.json').read_text(encoding='utf-8'))
    if data.get('schema')!=1:raise ValueError('UI_ANDROID_RESOURCE_SCHEMA')
    for key in ('standardEdition','pluginCount'):
        if set(data.get(key,{}))!=set(LANGUAGES):raise ValueError('UI_ANDROID_RESOURCE_LANGUAGES: '+key)
    required={'ar':{'zero','one','two','few','many','other'},'ru':{'one','few','many','other'}}
    for tag in LANGUAGES:
        edition=data['standardEdition'][tag]
        if not isinstance(edition,str) or not edition.strip():raise ValueError('UI_ANDROID_EDITION_EMPTY: '+tag)
        items=data['pluginCount'][tag]
        if not isinstance(items,dict) or not required.get(tag,{'other'}).issubset(items):
            raise ValueError('UI_ANDROID_PLURAL_QUANTITIES: '+tag)
        if set(items)-{'zero','one','two','few','many','other'}:raise ValueError('UI_ANDROID_PLURAL_QUANTITY: '+tag)
        for value in items.values():
            if not isinstance(value,str) or format_signature(value)!=['d']:
                raise ValueError('UI_ANDROID_PLURAL_FORMAT: '+tag)
    return data

def write_resource(target,doc,check):
    ET.indent(doc,space='    ')
    expected=ET.tostring(doc,encoding='utf-8',xml_declaration=True)
    if check:
        if not target.exists() or target.read_bytes()!=expected:raise ValueError('UI_RESOURCE_STALE: '+str(target))
    else:
        target.parent.mkdir(parents=True,exist_ok=True)
        if not target.exists() or target.read_bytes()!=expected:target.write_bytes(expected)

def resources(messages,catalogs,root=ROOT,migrate=True,check=False):
    typed=android_resources(root)
    lookup={item['zh']:item for item in messages if item['en']};used={}
    for file in (root/'app/src/main/res').rglob('*.xml') if migrate else []:
        if file.name=='ui_strings.xml' or file.parent.name.startswith('values-'):continue
        original=file.read_text(encoding='utf-8');text=original
        def replace(match):
            raw=match[2]
            try:value=ET.fromstring('<x value="'+raw+'" />').attrib['value']
            except ET.ParseError:return match[0]
            item=lookup.get(value)
            if item is None:return match[0]
            used['ui_'+item['id']]=item
            return match[1]+'"@string/ui_'+item['id']+'"'
        text=re.sub(r'(android:(?:text|hint|contentDescription|label|title|summary|prompt)=)"([^"]*)"',replace,text)
        if text!=original:file.write_text(text,encoding='utf-8',newline='\n')
    # 保持已生成的条目，使重复执行不会清掉现有资源引用。
    existing=root/'app/src/main/res/values/ui_strings.xml'
    if existing.exists():
        by_id={'ui_'+m['id']:m for m in messages if m['en']}
        for node in ET.parse(existing).getroot():
            if node.attrib['name'] in by_id:used[node.attrib['name']]=by_id[node.attrib['name']]
    named={}
    for file in (root/'app/src/main/res/values').glob('*.xml'):
        if file.name=='ui_strings.xml':continue
        for node in ET.parse(file).getroot():
            if node.tag=='string' and node.get('translatable')!='false' and node.text in lookup:
                named[node.attrib['name']]=lookup[node.text]
    for tag in LANGUAGES:
        folder=RESOURCE_FOLDERS[tag]
        items=used if tag=='zh' else dict(used,**named)
        # Existing hand-maintained locale resources already own these names.
        existing_names=set()
        existing_plurals={}
        for file in (root/f'app/src/main/res/{folder}').glob('*.xml'):
            if file.name!='ui_strings.xml':
                for node in ET.parse(file).getroot():
                    if node.tag=='string':existing_names.add(node.get('name'))
                    elif node.tag=='plurals':existing_plurals[node.get('name')]=node
        doc=ET.Element('resources')
        for name,item in sorted(items.items()):
            if name not in existing_names:
                ET.SubElement(doc,'string',name=name,formatted='false').text=resource_value(catalogs[tag][item['id']])
        plugin_count=typed['pluginCount'][tag]
        if 'plugin_count' in existing_plurals:
            actual={item.get('quantity'):item.text for item in existing_plurals['plugin_count']}
            if actual!=plugin_count:raise ValueError('UI_ANDROID_EXISTING_PLURAL_STALE: '+tag)
        else:
            plural=ET.SubElement(doc,'plurals',name='plugin_count')
            for quantity,value in plugin_count.items():ET.SubElement(plural,'item',quantity=quantity).text=resource_value(value)
        write_resource(root/f'app/src/main/res/{folder}/ui_strings.xml',doc,check)
        edition=ET.Element('resources')
        ET.SubElement(edition,'string',name='edition_description').text=resource_value(typed['standardEdition'][tag])
        write_resource(root/f'app/src/standard/res/{folder}/edition.xml',edition,check)
    print('XML language resources:',len(used),'+',len(named),'existing names x',len(LANGUAGES),'languages')

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--scope',choices=['ui','all'],default='ui')
    parser.add_argument('--root',type=Path,default=ROOT)
    parser.add_argument('--resources-only',action='store_true',help='Generate locale resources without rewriting Java/layout sources')
    parser.add_argument('--check',action='store_true',help='Check deterministic resources without writing files')
    args=parser.parse_args();ROOT=args.root
    messages,catalogs=load_catalog(ROOT);translated={m['zh'] for m in messages if m['en']}
    total=0
    if not args.resources_only and not args.check:
        for tree in ['app/src/main/java/com/deepseekharness/app']:
            for file in (ROOT/tree).rglob('*.java'):
                if args.scope=='all' or '/ui/' in str(file).replace('\\','/'):total+=source(file,translated)
    resources(messages,catalogs,ROOT,migrate=not args.resources_only and not args.check,check=args.check)
    print('Localized source literals:',total)
