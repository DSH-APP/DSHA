#!/usr/bin/env python3
"""将用户指定原图按比例缩小为 Android 图标；保留全图，不重新绘制或裁去文字。"""
from pathlib import Path
import argparse
from PIL import Image, ImageOps

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('source',type=Path)
args=parser.parse_args()
root=Path(__file__).resolve().parents[1]
original=ImageOps.exif_transpose(Image.open(args.source)).convert('RGB')
square=Image.new('RGB',(max(original.size),)*2,'black')
square.paste(original,((square.width-original.width)//2,(square.height-original.height)//2))
resources=root/'app/src/main/res'
brand=resources/'drawable-nodpi/dsha_brand.png';brand.parent.mkdir(parents=True,exist_ok=True)
square.resize((512,512),Image.Resampling.LANCZOS).save(brand,optimize=True)
for density,size in [('mdpi',48),('hdpi',72),('xhdpi',96),('xxhdpi',144),('xxxhdpi',192)]:
 for name in ('ic_launcher.png','ic_launcher_v137.png','ic_launcher_round_v137.png'):
  target=resources/f'mipmap-{density}/{name}'
  square.resize((size,size),Image.Resampling.LANCZOS).save(target,optimize=True)
print('保留完整原图，生成 5 种密度图标、v137 缓存刷新资源和 512px 品牌图。')
