#!/usr/bin/env python3
"""把 app/src/main/res/drawable/ic_*.xml 的 Android 矢量图转成 SVG 片段，写入 kit/icons.js。
只做离线转换：颜色统一改为 currentColor（与 app:tint 一致），不读取任何用户数据。"""
import json, pathlib, re, sys
import xml.etree.ElementTree as ET
A = '{http://schemas.android.com/apk/res/android}'
here = pathlib.Path(__file__).resolve().parent
repo = here.parents[3]
src = repo / 'app/src/main/res/drawable'
out = {}
def conv(node):
    parts = []
    for el in node:
        tag = el.tag
        if tag == 'path':
            d = el.get(A + 'pathData', '')
            fill = el.get(A + 'fillColor')
            stroke = el.get(A + 'strokeColor')
            fa = el.get(A + 'fillAlpha'); sa = el.get(A + 'strokeAlpha')
            transparent = lambda c: c is None or 'transparent' in c or re.fullmatch(r'#00[0-9A-Fa-f]{6}', c or '')
            attrs = [f'd="{d}"', 'fill="%s"' % ('none' if transparent(fill) else 'currentColor')]
            if fa: attrs.append(f'fill-opacity="{fa}"')
            if el.get(A + 'fillType', '').lower() == 'evenodd': attrs.append('fill-rule="evenodd"')
            if not transparent(stroke):
                attrs.append('stroke="currentColor"')
                attrs.append('stroke-width="%s"' % el.get(A + 'strokeWidth', '1'))
                attrs.append('stroke-linecap="%s"' % el.get(A + 'strokeLineCap', 'butt'))
                attrs.append('stroke-linejoin="%s"' % el.get(A + 'strokeLineJoin', 'miter'))
                if sa: attrs.append(f'stroke-opacity="{sa}"')
            parts.append('<path %s/>' % ' '.join(attrs))
        elif tag == 'group':
            t = []
            tx, ty = el.get(A + 'translateX'), el.get(A + 'translateY')
            if tx or ty: t.append(f'translate({tx or 0} {ty or 0})')
            r = el.get(A + 'rotation')
            if r: t.append('rotate(%s %s %s)' % (r, el.get(A + 'pivotX', '0'), el.get(A + 'pivotY', '0')))
            sx, sy = el.get(A + 'scaleX'), el.get(A + 'scaleY')
            if sx or sy: t.append(f'scale({sx or 1} {sy or 1})')
            parts.append('<g%s>%s</g>' % (f' transform="{" ".join(t)}"' if t else '', conv(el)))
    return ''.join(parts)
for f in sorted(src.glob('ic_*.xml')):
    try:
        root = ET.parse(f).getroot()
    except ET.ParseError:
        continue
    if root.tag != 'vector':
        continue
    vw, vh = root.get(A + 'viewportWidth'), root.get(A + 'viewportHeight')
    out[f.stem[3:]] = f'<svg viewBox="0 0 {vw} {vh}" aria-hidden="true">{conv(root)}</svg>'
# 别名：layer-list / level-list 只包一层 @drawable/ic_xxx 时，按第一个引用解析。
for f in sorted(src.glob('ic_*.xml')):
    key = f.stem[3:]
    if key in out:
        continue
    try:
        root = ET.parse(f).getroot()
    except ET.ParseError:
        continue
    for item in root.iter('item'):
        ref = item.get(A + 'drawable', '')
        if ref.startswith('@drawable/ic_') and ref[13:] in out:
            out[key] = out[ref[13:]]
            break
dst = here.parent / 'kit/icons.js'
dst.write_text('// 由 tools/icons-from-drawables.py 从 res/drawable/ic_*.xml 生成，勿手改。\n'
               'window.ICONS = ' + json.dumps(out, ensure_ascii=False, indent=0) + ';\n', encoding='utf-8')
print(len(out), 'icons ->', dst)
