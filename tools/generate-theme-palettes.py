#!/usr/bin/env python3
"""生成原生调色板资源：强调色与几层底色经主题属性间接引用，日夜各一套。

设计：
- 约 30 个调色板色名（PALETTE_NAMES）不再是固定 <color>，而是 res/color/<名>.xml 选择器，
  唯一一项指向 ?attr/dsha_pal_<名>。原有 1100 多处 @color/<名> 与 R.color.<名> 引用不用改。
- 每套调色板的日夜实际色值写在 values(-night)/theme_palettes.xml（pal_<id>_<名>），
  ThemeOverlay.DSHA.Palette.<Id> 把属性指过去；Theme.DSHA.PaletteDefaults 带标准色默认值。
- 标准色逐字保留原值；其余调色板按色相推导：强调色按对比度搜索亮度（色相改变后同样的 HSL
  亮度对比度差很多），柔和色与底色沿用标准色的饱和度与亮度、只换色相。
- 生成时校验 WCAG 对比度（≥ 4.5，搜索时留 4.6 余量），任何一套不达标即失败。

用法：--write 生成；--check（默认）核对已提交文件与生成结果一致。
"""
import colorsys
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'app/src/main/res'

# 强调色族：随调色板变化。
ACCENT = ['primary', 'primary_dark', 'accent_on', 'ripple', 'logo_top',
          'terminal_accent', 'terminal_accent_bg', 'terminal_accent_soft_2', 'terminal_accent_line',
          'terminal_on_accent', 'terminal_ripple', 'terminal_state_idle',
          'shell_accent', 'shell_accent_soft', 'shell_accent_tonal', 'shell_on_accent', 'shell_nav_ripple', 'onb_accent_line']
# 底色族：标准色是微蓝灰，其他调色板换成各自色相的微色调。
SURFACE = ['surface', 'card', 'raised', 'disabled', 'preview',
           'shell_bg', 'shell_surface', 'shell_raised', 'shell_nav_bg', 'shell_glass',
           'terminal_panel', 'terminal_panel_card', 'terminal_raised', 'terminal_tile', 'terminal_tile_pressed']
PALETTE_NAMES = ACCENT + SURFACE

# 标准色：迁移前 values(-night) 中的原值，逐字保留（日, 夜）。
STANDARD = {
    'primary': ('#1E50D0', '#6AA4FF'), 'primary_dark': ('#1A46B8', '#3A75DA'),
    'accent_on': ('#FFFFFF', '#081428'), 'ripple': ('#141E50D0', '#146AA4FF'),
    'logo_top': ('#E8EFFF', '#13233C'), 'terminal_accent': ('#1E50D0', '#8AB4FF'),
    'terminal_accent_bg': ('#E8EFFF', '#13233C'), 'terminal_accent_soft_2': ('#DBE7FF', '#1B3050'),
    'terminal_accent_line': ('#B9CCF4', '#2C4C7C'), 'terminal_on_accent': ('#FFFFFF', '#081428'),
    'terminal_ripple': ('#1F1E50D0', '#338AB4FF'), 'terminal_state_idle': ('#1E50D0', '#8AB4FF'),
    'shell_accent': ('#1E50D0', '#6AA4FF'), 'shell_accent_soft': ('#141E50D0', '#146AA4FF'),
    # 下载记录页的浅色主操作底：比 accent_soft 深一档，主色文字仍满足对比度。
    'shell_accent_tonal': ('#1F1E50D0', '#246AA4FF'),
    'shell_on_accent': ('#FFFFFF', '#091729'), 'shell_nav_ripple': ('#141E50D0', '#146AA4FF'),
    'onb_accent_line': ('#471E50D0', '#4D6AA4FF'),
    'surface': ('#F3F6FB', '#0B0F16'), 'card': ('#FCFDFF', '#131923'), 'raised': ('#E7EDF6', '#1B2432'),
    'disabled': ('#E7EDF6', '#1B2432'), 'preview': ('#E7EDF6', '#05090E'),
    'shell_bg': ('#F3F6FB', '#0B0F16'), 'shell_surface': ('#FCFDFF', '#131923'),
    'shell_raised': ('#E7EDF6', '#1B2432'), 'shell_nav_bg': ('#FCFDFF', '#131923'),
    'shell_glass': ('#E0FAFCFF', '#DB192230'), 'terminal_panel': ('#FFFFFF', '#0B0F16'),
    'terminal_panel_card': ('#F7F9FD', '#10151E'), 'terminal_raised': ('#EAEFF7', '#151C27'),
    'terminal_tile': ('#F1F4F9', '#141B25'), 'terminal_tile_pressed': ('#E6EBF3', '#1B2431'),
}

# 不随调色板变化的中性色（用于对比度校验），与 values(-night) 中的定义一致。
NEUTRAL = {
    'text': ('#18273F', '#EDF1F7'), 'text_secondary': ('#50617A', '#B0BBCC'),
    'text_muted': ('#5A6B86', '#8895AA'), 'ok': ('#287453', '#93CCAF'),
    'warn': ('#8C600D', '#E4BE83'), 'err': ('#AF404B', '#EEA3A6'),
    'shell_text': ('#18273F', '#EDF1F7'), 'shell_text_secondary': ('#50617A', '#B0BBCC'),
    'shell_text_muted': ('#5A6B86', '#8895AA'), 'terminal_panel_text': ('#152033', '#E4E8F0'),
    'terminal_muted': ('#59657A', '#9DA8BB'), 'primary_ripple': ('#1FFFFFFF', '#20FFFFFF'),
}

# id, 资源后缀, 色相（度）, 强调色饱和度。标准色不推导。
PALETTES = [
    ('standard', 'Standard', None, None),
    ('aurora', 'Aurora', 188, 0.80),
    ('sakura', 'Sakura', 335, 0.72),
    ('cyber', 'Cyber', 268, 0.70),
    ('amber', 'Amber', 32, 0.85),
]
MARGIN = 4.6
REQUIRED = 4.5


def rgba(value):
    raw = value.lstrip('#')
    if len(raw) == 6:
        raw = 'FF' + raw
    return [int(raw[i:i + 2], 16) for i in range(0, 8, 2)]


def hexa(a, r, g, b):
    return ('#%02X%02X%02X' % (r, g, b)) if a == 255 else ('#%02X%02X%02X%02X' % (a, r, g, b))


def over(top, base):
    a, *rgb = rgba(top)
    b = rgba(base)
    return hexa(255, *[round((rgb[i] * a + b[i + 1] * (255 - a)) / 255) for i in range(3)])


def luminance(value):
    rgb = [v / 255 for v in rgba(value)[1:]]
    linear = [v / 12.92 if v <= .04045 else ((v + .055) / 1.055) ** 2.4 for v in rgb]
    return sum(v * w for v, w in zip(linear, [.2126, .7152, .0722]))


def contrast(a, b):
    low, high = sorted([luminance(a), luminance(b)])
    return (high + .05) / (low + .05)


def hsl(h, s, l, alpha=255):
    r, g, b = colorsys.hls_to_rgb((h % 360) / 360, max(0, min(1, l)), max(0, min(1, s)))
    return hexa(alpha, round(r * 255), round(g * 255), round(b * 255))


def rehue(value, hue):
    """换色相并保持相对亮度：同样的 HSL 亮度在紫、蓝上更暗、在黄、青上更亮，按亮度二分回原值，
    中性文字在新底色上的对比度与标准色一致。透明度与饱和度不变。"""
    a, r, g, b = rgba(value)
    _, l, s = colorsys.rgb_to_hls(r / 255, g / 255, b / 255)
    target = luminance(hexa(255, r, g, b))
    low, high = 0.0, 1.0
    for _ in range(40):
        mid = (low + high) / 2
        if luminance(hsl(hue, s, mid)) < target:
            low = mid
        else:
            high = mid
    best = min((hsl(hue, s, x) for x in (low, high)), key=lambda c: abs(luminance(c) - target))
    return with_alpha(best, a)


def with_alpha(value, alpha):
    _, r, g, b = rgba(value)
    return hexa(alpha, r, g, b)


def alpha_of(value):
    return rgba(value)[0]


def accent_pairs(c, mode):
    """强调色族必须满足的对比度（与 tools/test-ui-colors.py 一致）。"""
    pairs = []
    for bg in ['surface', 'card', 'raised', 'disabled']:
        pairs.append(('primary/' + bg, c['primary'], c[bg]))
    for bg in ['surface', 'card', 'raised']:
        selected = over(c['ripple'], c[bg])
        pairs += [('selected/' + bg, c['primary'], selected),
                  ('selected-pressed/' + bg, c['primary'], over(c['ripple'], selected))]
    pairs += [('accent_on/primary', c['accent_on'], c['primary']),
              ('accent_on/primary-pressed', c['accent_on'], over(c['primary_ripple'], c['primary']))]
    for bg in ['shell_bg', 'shell_surface', 'shell_raised']:
        pairs.append(('shell_accent/' + bg, c['shell_accent'], c[bg]))
    pairs.append(('shell_on_accent/shell_accent', c['shell_on_accent'], c['shell_accent']))
    for bg in ['terminal_panel', 'terminal_panel_card']:
        pairs.append(('terminal_accent/' + bg, c['terminal_accent'], c[bg]))
    pairs.append(('terminal_on_accent/terminal_accent', c['terminal_on_accent'], c['terminal_accent']))
    return pairs


def neutral_pairs(c):
    pairs = []
    for fg in ['text', 'text_secondary', 'text_muted', 'ok', 'warn', 'err']:
        for bg in ['surface', 'card', 'raised', 'disabled']:
            pairs.append((fg + '/' + bg, c[fg], c[bg]))
    for fg in ['shell_text', 'shell_text_secondary', 'shell_text_muted']:
        for bg in ['shell_bg', 'shell_surface', 'shell_raised']:
            pairs.append((fg + '/' + bg, c[fg], c[bg]))
    for fg in ['terminal_panel_text', 'terminal_muted']:
        for bg in ['terminal_panel', 'terminal_panel_card']:
            pairs.append((fg + '/' + bg, c[fg], c[bg]))
    return pairs


def effective(values, mode):
    c = {name: pair[mode] for name, pair in NEUTRAL.items()}
    c.update({name: values[name][mode] for name in PALETTE_NAMES})
    return c


def derive(hue, sat):
    """按色相推导一套调色板：底色换色相；强调色按对比度逐档搜索亮度。"""
    values = {name: [rehue(STANDARD[name][0], hue), rehue(STANDARD[name][1], hue)] for name in PALETTE_NAMES}
    for mode in (0, 1):
        dark = mode == 1
        on = '#FFFFFF' if not dark else hsl(hue, 0.6, 0.08)
        steps = [x / 1000 for x in (range(600, 199, -5) if not dark else range(400, 901, 5))]
        chosen = None
        for light in steps:
            accent = hsl(hue, sat, light)
            terminal = hsl(hue, sat, min(light + (0.07 if dark else 0), 0.9))
            trial = {k: list(v) for k, v in values.items()}
            for name, value in [('primary', accent), ('shell_accent', accent), ('terminal_accent', terminal),
                                ('terminal_state_idle', terminal), ('accent_on', on), ('shell_on_accent', on),
                                ('terminal_on_accent', on)]:
                trial[name][mode] = value
            for name, source in [('ripple', 'primary'), ('shell_accent_soft', 'primary'), ('shell_accent_tonal', 'primary'),
                                 ('shell_nav_ripple', 'primary'), ('onb_accent_line', 'primary'),
                                 ('terminal_ripple', 'terminal_accent')]:
                trial[name][mode] = with_alpha(trial[source][mode], alpha_of(STANDARD[name][mode]))
            c = effective(trial, mode)
            if all(contrast(fg, bg) >= MARGIN for _, fg, bg in accent_pairs(c, mode)):
                chosen = trial
                break
        if chosen is None:
            raise SystemExit('PALETTE_ACCENT_UNREACHABLE: hue=%s mode=%s' % (hue, mode))
        _, l_accent, _ = colorsys.rgb_to_hls(*[v / 255 for v in rgba(chosen['primary'][mode])[1:]])
        chosen['primary_dark'][mode] = hsl(hue, sat * (0.9 if not dark else 0.7),
                                           l_accent - (0.05 if not dark else 0.15))
        values = chosen
    return values


def build():
    palettes = {}
    for pid, _, hue, sat in PALETTES:
        values = {k: list(v) for k, v in STANDARD.items()} if hue is None else derive(hue, sat)
        for mode in (0, 1):
            c = effective(values, mode)
            for name, fg, bg in accent_pairs(c, mode) + neutral_pairs(c):
                ratio = contrast(fg, bg)
                if ratio < REQUIRED:
                    raise SystemExit('PALETTE_CONTRAST: %s %s %s %.2f' % (pid, 'night' if mode else 'day', name, ratio))
        palettes[pid] = values
    return palettes


HEADER = '<?xml version="1.0" encoding="utf-8"?>\n<!-- 由 tools/generate-theme-palettes.py 生成，勿手改。 -->\n'


def outputs():
    palettes = build()
    files = {}
    attrs = [HEADER + '<resources>']
    for name in PALETTE_NAMES:
        attrs.append('    <attr name="dsha_pal_%s" format="color" />' % name)
    files['values/theme_palette_attrs.xml'] = '\n'.join(attrs + ['</resources>', ''])
    for mode, folder in ((0, 'values'), (1, 'values-night')):
        lines = [HEADER + '<resources>']
        for pid, _, _, _ in PALETTES:
            for name in PALETTE_NAMES:
                lines.append('    <color name="pal_%s_%s">%s</color>' % (pid, name, palettes[pid][name][mode]))
        files[folder + '/theme_palettes.xml'] = '\n'.join(lines + ['</resources>', ''])
    styles = [HEADER + '<resources>',
              '    <!-- 标准色默认值：Theme.DSHA.Base 的父主题，未叠加调色板时即为标准色。 -->',
              '    <style name="Theme.DSHA.PaletteDefaults" parent="Theme.Material3.DayNight.NoActionBar">']
    styles += ['        <item name="dsha_pal_%s">@color/pal_standard_%s</item>' % (n, n) for n in PALETTE_NAMES]
    styles.append('    </style>')
    for pid, suffix, _, _ in PALETTES:
        styles.append('    <style name="ThemeOverlay.DSHA.Palette.%s" parent="">' % suffix)
        styles += ['        <item name="dsha_pal_%s">@color/pal_%s_%s</item>' % (n, pid, n) for n in PALETTE_NAMES]
        styles.append('    </style>')
    files['values/theme_palette_styles.xml'] = '\n'.join(styles + ['</resources>', ''])
    for name in PALETTE_NAMES:
        files['color/%s.xml' % name] = (
            HEADER + '<selector xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <item android:color="?attr/dsha_pal_%s" />\n</selector>\n' % name)
    return files


def lint_selectors(files):
    """手写的颜色选择器不能嵌套引用调色板色：嵌套引用在无主题加载时会被缓存成默认色。"""
    problems = []
    for path in sorted((RES / 'color').glob('*.xml')):
        if 'color/' + path.name in files:
            continue
        text = path.read_text(encoding='utf-8')
        for name in PALETTE_NAMES:
            if '@color/%s"' % name in text:
                problems.append('%s 引用 @color/%s，应改为 ?attr/dsha_pal_%s' % (path.relative_to(ROOT), name, name))
    for folder in ('values', 'values-night'):
        for path in sorted((RES / folder).glob('*.xml')):
            if folder + '/' + path.name in files:
                continue
            text = path.read_text(encoding='utf-8')
            for name in PALETTE_NAMES:
                if '<color name="%s">' % name in text:
                    problems.append('%s 仍定义调色板色 %s' % (path.relative_to(ROOT), name))
    return problems


def main(argv):
    mode = argv[0] if argv else '--check'
    files = outputs()
    if mode == '--write':
        for relative, content in files.items():
            target = RES / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding='utf-8')
        print('wrote %d files' % len(files))
    elif mode == '--check':
        stale = [r for r, c in files.items() if not (RES / r).exists() or (RES / r).read_text(encoding='utf-8') != c]
        if stale:
            raise SystemExit('THEME_PALETTES_STALE: ' + ', '.join(stale[:6]) + '；运行 tools/generate-theme-palettes.py --write')
    else:
        raise SystemExit('usage: generate-theme-palettes.py [--check|--write]')
    problems = lint_selectors(files)
    if problems:
        raise SystemExit('THEME_PALETTE_REFERENCES:\n' + '\n'.join(problems))
    print('PASS %d palettes × day/night, %d colors each' % (len(PALETTES), len(PALETTE_NAMES)))


if __name__ == '__main__':
    main(sys.argv[1:])
