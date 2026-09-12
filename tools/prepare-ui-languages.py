#!/usr/bin/env python3
"""从已审阅的中英文文案生成 Java 字典；不在运行时猜测或翻译用户内容。"""
import argparse,json,hashlib
from pathlib import Path

def generate(root,output):
    messages=json.loads((root/'tools/i18n/messages.json').read_text(encoding='utf-8'))
    translated=[item for item in messages if item['en']]
    unique={item['zh']:item['en'] for item in translated}
    lines=['package com.deepseekharness.app.util;','import java.util.*;',
           '/** 由 tools/prepare-ui-languages.py 生成，请修改文案目录。 */',
           'final class UiMessages {','static final Map<String,String> EN = build();',
           'private static Map<String,String> build() { Map<String,String> values=new HashMap<>();']
    pairs=list(unique.items())
    for i in range(0,len(pairs),80):lines.append(f'part{i//80}(values);')
    lines+=['return Collections.unmodifiableMap(values);','}']
    for i in range(0,len(pairs),80):
        lines.append(f'private static void part{i//80}(Map<String,String> values) {{')
        for zh,en in pairs[i:i+80]:
            lines.append('values.put('+json.dumps(zh,ensure_ascii=False)+','+json.dumps(en,ensure_ascii=False)+');')
        lines.append('}')
    lines.append('static final String[][] FORMATS = new String[][] {')
    for item in translated:
        if item.get('uiFormat'):
            lines.append('{'+json.dumps(item['zh'],ensure_ascii=False)+','+json.dumps(item['en'],ensure_ascii=False)+'},')
    lines.append('};')
    lines.append('}')
    target=output/'com/deepseekharness/app/util/UiMessages.java';target.parent.mkdir(parents=True,exist_ok=True)
    value='\n'.join(lines)+'\n'
    if not target.exists() or target.read_text(encoding='utf-8')!=value:target.write_text(value,encoding='utf-8',newline='\n')
    print(f'UI language catalog: {len(translated)}/{len(messages)} entries')

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[1]);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();generate(args.root,args.output)
