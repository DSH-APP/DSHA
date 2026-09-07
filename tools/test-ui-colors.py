#!/usr/bin/env python3
"""从真实日夜色板核对正文、弱提示、状态和按钮各状态的 WCAG 对比度。"""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1] / 'app/src/main/res'
def rgba(value):
    raw=value.lstrip('#')
    return [int(raw[i:i+2],16) for i in range(0,8,2)] if len(raw)==8 else [255]+[int(raw[i:i+2],16) for i in (0,2,4)]
def over(top, base):
    a,*rgb=top
    return [255]+[(rgb[i]*a+base[i+1]*(255-a))/255 for i in range(3)]
def luminance(color):
    rgb=[v/255 for v in color[1:]]
    linear=[v/12.92 if v<=.04045 else ((v+.055)/1.055)**2.4 for v in rgb]
    return sum(v*w for v,w in zip(linear,[.2126,.7152,.0722]))
def contrast(a,b):
    low,high=sorted([luminance(a),luminance(b)])
    return (high+.05)/(low+.05)
class ColorsTest(unittest.TestCase):
    def test_all_text_roles_and_button_states(self):
        minimum=100
        for mode in ['values','values-night']:
            colors={e.attrib['name']:rgba(e.text) for e in ET.parse(ROOT/mode/'colors.xml').getroot()}
            pairs=[]
            for fg in ['text','text_secondary','text_muted','primary','ok','warn','err']:
                for bg in ['surface','card','raised','disabled']:
                    pairs.append((fg+'/'+bg,colors[fg],colors[bg]))
            for bg in ['surface','card','raised']:
                selected=over(colors['ripple'],colors[bg])
                pairs += [('selected/'+bg,colors['primary'],selected),('selected-pressed/'+bg,colors['primary'],over(colors['ripple'],selected))]
            for fg,bg in [('accent_on','primary'),('error_on','err')]:
                pairs += [(bg,colors[fg],colors[bg]),(bg+'-pressed',colors[fg],over(colors['primary_ripple'],colors[bg]))]
            for name,fg,bg in pairs:
                value=contrast(fg,bg); minimum=min(minimum,value)
                with self.subTest(mode=mode,pair=name): self.assertGreaterEqual(value,4.5)
        print('Minimum palette contrast: %.3f:1'%minimum)
if __name__=='__main__': unittest.main()
