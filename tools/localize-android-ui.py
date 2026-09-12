#!/usr/bin/env python3
"""显式迁移已审阅的应用文案。只替换源代码字面量，不扫描运行时用户文本。"""
from pathlib import Path
import argparse,json,re,xml.etree.ElementTree as ET

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

def resources(messages):
    lookup={item['zh']:item for item in messages if item['en']};used={}
    for file in (ROOT/'app/src/main/res').rglob('*.xml'):
        if file.name=='ui_strings.xml' or 'values-en' in str(file):continue
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
    existing=ROOT/'app/src/main/res/values/ui_strings.xml'
    if existing.exists():
        by_id={'ui_'+m['id']:m for m in messages if m['en']}
        for node in ET.parse(existing).getroot():
            if node.attrib['name'] in by_id:used[node.attrib['name']]=by_id[node.attrib['name']]
    originals=ET.parse(ROOT/'app/src/main/res/values/strings.xml').getroot()
    english={node.attrib['name']:lookup[node.text] for node in originals if node.text in lookup}
    for folder,items,field in [('values',used,'zh'),('values-en',dict(used,**english),'en')]:
        doc=ET.Element('resources')
        for name,item in sorted(items.items()):ET.SubElement(doc,'string',name=name,formatted='false').text=resource_value(item[field])
        ET.indent(doc,space='    ');target=ROOT/f'app/src/main/res/{folder}/ui_strings.xml';target.parent.mkdir(parents=True,exist_ok=True)
        ET.ElementTree(doc).write(target,encoding='utf-8',xml_declaration=True)
    print('XML language resources:',len(used),'+',len(english),'existing names')

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--scope',choices=['ui','all'],default='ui');args=parser.parse_args()
    messages=json.loads((ROOT/'tools/i18n/messages.json').read_text(encoding='utf-8'));translated={m['zh'] for m in messages if m['en']}
    total=0
    for tree in ['app/src/main/java/com/deepseekharness/app','app/src/low/java']:
        for file in (ROOT/tree).rglob('*.java'):
            if args.scope=='all' or '/ui/' in str(file).replace('\\','/'):total+=source(file,translated)
    resources(messages);print('Localized source literals:',total)
