#!/usr/bin/env python3
"""
「工具图标被切」的**三阶段对照图** —— 用真实矢量路径 + 真实几何，方框就是**位图边界**。

## 三个阶段（同一个根因，两层）

| 阶段 | 代码 | `k` | 内容方块位置 | 症状 |
| --- | --- | --- | --- | --- |
| A 修前（v1.1.8） | `if (k < 1f) canvas.translate(…)`，PLATE 的 extent = 0.833 | **1.19** | 从 (0,0) 起画 | 白圆直径 1.19× 画布，**右下**被切 ⇒ 用户「无法完全显示」 |
| B 只居中（2026-10-16 的修法） | 无条件 translate，extent 仍 0.833 | **1.19** | 居中 | 切从「右下」变成**四边一起切** ⇒ 用户「四个角被切分了」 |
| C 修好（2026-10-17） | 无条件 translate + PLATE 的 extent = **1.0** | **0.99** | 居中 | 白圆 = `占比 × 画布`，正中间，一格不切 |

## 为什么是 PLATE

`PLATE` 的白垫层是为**深色背景**（轮盘衬底 / 底栏下面那层遮罩）加的 —— 在白卡片上它完全隐形，
所以早年按「看不见 ⇒ 和 BARE 同值（20/24）」算。可**深色上它一眼就看得见**，那条假设就破了：
`extent = 0.833` ⇒ `k = 占比/0.833` ≈ **1.19** ⇒ 白圆比画布大 19%。

真机取证（用户 2026-10-17 截图，PMX110 1272×2772，底栏图标框 101px）：
白圆 bbox = 101×101、每行宽度 67→101 → 与**半径 60 的圆**（= 1.19 × 101/2）逐行吻合。

## 坐标口径（与 Kotlin 侧逐项对齐）

    画布 = 0..size（svg viewBox 就是它，超出即被裁 —— 这正是真机上位图的裁切）
    canvas.translate(dx,dy)                     → <g transform="translate(dx,dy)">
    glyph.setBounds(inset, inset, sidePx-inset) →  再 translate(inset,inset) scale(sidePx/24)
    drawPlate: drawCircle(side/2, side/2, side/2) → <circle> 在内容方块坐标系里
    ic_tool_*.xml 里那层 <group>                 → 照抄（scale + translate，线宽也是真值）

用法：python3 tools/gen_tool_icon_centering_check.py   # 写 /tmp/tool_icons/centering.html
"""
import html as htmlmod
import xml.etree.ElementTree as ET
from pathlib import Path

AND = "{http://schemas.android.com/apk/res/android}"
RES = Path("/Users/her/Desktop/FlymeFreeform/app/src/main/res/drawable")
OUT = Path("/tmp/tool_icons")
OUT.mkdir(parents=True, exist_ok=True)

TOOLS = [
    ("pay", "ic_tool_paycode", "微信付款码（竖条）", "#10B981", "#047857"),
    ("screen", "ic_tool_screen_text", "识屏", "#06B6D4", "#0E7490"),
    ("shot", "ic_tool_screenshot", "截屏", "#8B5CF6", "#6D28D9"),
]

# ---- 真机参数（PMX110：短边 1272 / density 3.5 → 底栏图标框 0.080×1272 = 101px）----
BOX = 101.0
FRACTION = 0.99          # 这台机器实测的 typicalIconFraction
EXTENT_OLD = 20 / 24     # 修前 PLATE 用的（错）
EXTENT_NEW = 1.0         # 修后 PLATE 用的（白垫层就是可见内容）
RATIO = 2.0              # 图里放大一点好观察（比例不变）

STAGES = [
    ("A", "修前 v1.1.8", "k = 1.19 · 内容从 (0,0) 起画", FRACTION / EXTENT_OLD, False, "bad"),
    ("B", "只居中（10-16 那一版）", "k = 1.19 · 居中 ⇒ 四边一起切", FRACTION / EXTENT_OLD, True, "warn"),
    ("C", "修好（10-17）", "extent = 1.0 ⇒ k = 0.99 · 居中", FRACTION / EXTENT_NEW, True, "good"),
]


def read_vector(name):
    g = ET.parse(RES / f"{name}.xml").getroot().find("group")
    return (float(g.get(f"{AND}translateX")), float(g.get(f"{AND}translateY")),
            float(g.get(f"{AND}scaleX")),
            float(g.find("path").get(f"{AND}strokeWidth")),
            [p.get(f"{AND}pathData") for p in g.findall("path")])


def cell(key, res, label, c1, c2, k, centered):
    tx, ty, sx, w, paths = read_vector(res)
    side = BOX * k
    dx = (BOX - side) / 2 if centered else 0.0
    px = int(BOX * RATIO)
    gid = f"g-{key}-{int(k * 1000)}"
    body = "".join(f'<path d="{htmlmod.escape(d, quote=True)}"/>' for d in paths)
    return (
        f'<div class="cell">'
        f'<div class="frame"><svg width="{px}" height="{px}" viewBox="0 0 {BOX:.0f} {BOX:.0f}">'
        f'<defs><linearGradient id="{gid}" gradientUnits="userSpaceOnUse"'
        f' x1="0" y1="0" x2="{side:.3f}" y2="{side:.3f}">'
        f'<stop offset="0" stop-color="{c1}"/><stop offset="1" stop-color="{c2}"/></linearGradient></defs>'
        f'<rect class="box" x="0.5" y="0.5" width="{BOX - 1:.0f}" height="{BOX - 1:.0f}"/>'
        f'<g transform="translate({dx:.4f},{dx:.4f})">'
        # 白垫层（drawPlate：圆心 side/2、半径 side/2）
        f'<circle cx="{side / 2:.4f}" cy="{side / 2:.4f}" r="{side / 2:.4f}" fill="#FFFFFF"/>'
        f'<g transform="scale({side / 24:.4f})">'
        f'<g transform="translate({tx:.4f},{ty:.4f}) scale({sx:.4f})" fill="none"'
        f' stroke="url(#{gid})" stroke-width="{w:.4f}"'
        f' stroke-linecap="round" stroke-linejoin="round">{body}</g></g></g></svg></div>'
        f'<div class="nm">{label}</div></div>'
    )


rows = ""
for tag, title, note, k, centered, cls in STAGES:
    cells = "".join(cell(*t, k, centered) for t in TOOLS)
    rows += (
        f'<div class="row {cls}"><div class="hd"><b>{tag} · {title}</b>'
        f'<span>{note}（白圆直径 {BOX * k:.0f}px，画布 {BOX:.0f}px）</span></div>'
        f'<div class="cells">{cells}</div></div>'
    )

page = f'<div class="wrap">{rows}</div>'
CSS = """
body{margin:0;background:#F5F6F8}
.wrap{display:flex;flex-direction:column;gap:14px;padding:16px;font-family:-apple-system,'PingFang SC',sans-serif}
.row{background:#FFFFFF;border:1px solid #E6E9EE;border-radius:14px;padding:14px 16px}
.row.bad{border-color:#F3C6C6}.row.warn{border-color:#F5DFB0}.row.good{border-color:#BEE3D2}
.hd{display:flex;flex-direction:column;gap:3px;margin-bottom:12px}
.hd b{font-size:13px;color:#111827;font-weight:600}
.hd span{font-size:11.5px;color:#6B7280;line-height:1.6}
.cells{display:flex;gap:24px;align-items:flex-start;flex-wrap:wrap}
.cell{text-align:center;width:230px}
.frame{display:inline-block;background:#666666;border-radius:4px}
.box{fill:none;stroke:#FFFFFFAA;stroke-width:1;stroke-dasharray:4 3}
.nm{font-size:11px;color:#374151;margin-top:6px}
"""
(OUT / "centering.html").write_text(f"<style>{CSS}</style>{page}", encoding="utf-8")
print("html:", OUT / "centering.html", len(page), "chars")
for tag, title, note, k, centered, _ in STAGES:
    print(f'  {tag}: k={k:.3f} 白圆={BOX * k:.0f}px 画布={BOX:.0f}px 居中={centered}')
