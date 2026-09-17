#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
标注器 (Annotator)
==================

把自动检测结果画在截图上，生成人可读的标注图。

存在的理由：本项目的主开发者（AI）不具备图像输入能力，无法直接看图。
因此所有自动检测结果都必须"可视化出来给人确认"，由人合上验证闭环。
这也是 P3 阶段标定工具的核心组件。

用法
----
    python3 tools/annotate.py shots/正常地图.png
    python3 tools/annotate.py shots/*.png --outdir shots/_analysis --top 14
"""

import argparse
import os
import sys

import cv2
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from analyze import find_icon_rows, find_minimap, find_player_dot  # noqa: E402

# 颜色（BGR）
C_GRID = (90, 90, 90)
C_GRID_TXT = (200, 200, 200)
C_FLAT = (60, 200, 60)      # 平坦区域 = UI 候选
C_ICONROW = (0, 165, 255)   # 图标行 = BUFF 栏候选
C_MINIMAP = (255, 120, 0)   # 小地图候选
C_DOT = (0, 0, 255)         # 角色定位点
C_BORDER = (255, 255, 255)


def flat_regions(img, min_area=1500, local_win=9, std_th=6):
    g = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY).astype(np.float32)
    m1 = cv2.blur(g, (local_win, local_win))
    m2 = cv2.blur(g * g, (local_win, local_win))
    std = np.sqrt(np.maximum(m2 - m1 * m1, 0))
    u = (std < std_th).astype(np.uint8) * 255
    u = cv2.morphologyEx(u, cv2.MORPH_OPEN, np.ones((5, 5), np.uint8))
    n, lab, st, ce = cv2.connectedComponentsWithStats(u, connectivity=8)
    out = []
    for i in range(1, n):
        x, y, w, h, a = st[i]
        if a < min_area:
            continue
        out.append({"area": int(a), "box": (int(x), int(y), int(w), int(h)),
                    "fill": round(a / float(w * h), 2)})
    out.sort(key=lambda r: -r["area"])
    return out


def draw_grid(vis, W, H, step=0.1):
    for i in range(1, int(1 / step)):
        x, y = int(i * step * W), int(i * step * H)
        cv2.line(vis, (x, 0), (x, H), C_GRID, 1)
        cv2.line(vis, (0, y), (W, y), C_GRID, 1)
    for i in range(0, int(1 / step)):
        x, y = int(i * step * W), int(i * step * H)
        cv2.putText(vis, f"{i*step:.1f}", (x + 3, 14), cv2.FONT_HERSHEY_SIMPLEX,
                    0.38, C_GRID_TXT, 1, cv2.LINE_AA)
        cv2.putText(vis, f"{i*step:.1f}", (3, y + 14), cv2.FONT_HERSHEY_SIMPLEX,
                    0.38, C_GRID_TXT, 1, cv2.LINE_AA)


def annotate(path, outdir, top, min_area):
    img = cv2.imread(path)
    if img is None:
        print(f"!! 读不到 {path}")
        return None
    H, W = img.shape[:2]
    base = os.path.splitext(os.path.basename(path))[0]

    vis = img.copy()
    draw_grid(vis, W, H)

    rows_out = []

    # --- 平坦区域 = UI 候选 ---
    flats = flat_regions(img, min_area=min_area)[:top]
    for i, r in enumerate(flats):
        x, y, w, h = r["box"]
        cv2.rectangle(vis, (x, y), (x + w, y + h), C_FLAT, 2)
        tag = f"F{i+1}"
        (tw, th), _ = cv2.getTextSize(tag, cv2.FONT_HERSHEY_SIMPLEX, 0.6, 2)
        cv2.rectangle(vis, (x, max(0, y - th - 6)), (x + tw + 6, y), C_FLAT, -1)
        cv2.putText(vis, tag, (x + 3, max(12, y - 4)), cv2.FONT_HERSHEY_SIMPLEX,
                    0.6, (0, 0, 0), 2, cv2.LINE_AA)
        rows_out.append({
            "tag": tag, "kind": "平坦区(UI候选)", "area": r["area"], "fill": r["fill"],
            "pixelBox": [x, y, w, h],
            "roi": [round(x / W, 4), round(y / H, 4), round((x + w) / W, 4), round((y + h) / H, 4)],
        })

    # --- BUFF 栏候选（全图搜，不限于上部）---
    icon_rows = []
    for top_pct in (0.30, 1.0):
        got, _ = find_icon_rows(img, top_pct=top_pct)
        if got:
            icon_rows = got
            break
    for i, r in enumerate(icon_rows[:3]):
        x1, y1, x2, y2 = r["pixelBox"]
        cv2.rectangle(vis, (x1, y1), (x2, y2), C_ICONROW, 3)
        cv2.putText(vis, f"I{i+1} n={r['itemCount']}", (x1, max(14, y1 - 6)),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.6, C_ICONROW, 2, cv2.LINE_AA)
        rows_out.append({
            "tag": f"I{i+1}", "kind": "图标行(BUFF栏候选)", "count": r["itemCount"],
            "pitch_px": r["avgGapPx"], "size_px": r["avgSizePx"],
            "regularity": r["gapRegularity"],
            "pixelBox": [x1, y1, x2 - x1, y2 - y1],
            "roi": [round(x1 / W, 4), round(y1 / H, 4), round(x2 / W, 4), round(y2 / H, 4)],
        })

    # --- 小地图候选 ---
    maps, _ = find_minimap(img)
    for i, m in enumerate(maps[:2]):
        x, y, w, h = m["pixelBox"]
        cv2.rectangle(vis, (x, y), (x + w, y + h), C_MINIMAP, 3)
        cv2.putText(vis, f"M{i+1}", (x, max(14, y - 6)), cv2.FONT_HERSHEY_SIMPLEX,
                    0.6, C_MINIMAP, 2, cv2.LINE_AA)
        rows_out.append({
            "tag": f"M{i+1}", "kind": "小地图候选", "corner": m["corner"],
            "detail": m["detailScore"],
            "pixelBox": [x, y, w, h],
            "roi": [round(x / W, 4), round(y / H, 4), round((x + w) / W, 4), round((y + h) / H, 4)],
        })
        dots = find_player_dot(img, [x / W, y / H, (x + w) / W, (y + h) / H])
        for d in dots[:3]:
            cx = int(x + d["centerInRoiNorm"][0] * w)
            cy = int(y + d["centerInRoiNorm"][1] * h)
            cv2.circle(vis, (cx, cy), 9, C_DOT, 2)
            rows_out.append({
                "tag": "•", "kind": "角色定位点候选(在小地图内)",
                "pixelXY": [cx, cy], "area_px": d["areaPx"], "hsv": d["hsv"],
                "roi_in_minimap": d["centerInRoiNorm"],
            })

    os.makedirs(outdir, exist_ok=True)
    out = os.path.join(outdir, f"{base}_annotated.png")
    cv2.imwrite(out, vis)

    # --- 逐区域放大图（接触印相），方便人快速辨认每个区域是什么 ---
    sheet_items = [r for r in rows_out if r.get("pixelBox")][:12]
    tiles = []
    for r in sheet_items:
        x, y, w, h = r["pixelBox"]
        x, y = max(0, x), max(0, y)
        w, h = min(w, W - x), min(h, H - y)
        if w < 4 or h < 4:
            continue
        sub = img[y:y + h, x:x + w]
        tw = 200
        th = max(60, int(round(tw * h / w)))
        if th > 200:
            th = 200
            tw = max(60, int(round(th * w / h)))
        tile = cv2.resize(sub, (tw, th), interpolation=cv2.INTER_NEAREST)
        canvas = np.full((214, 224, 3), 26, np.uint8)
        canvas[6:6 + th, 12:12 + tw] = tile
        cv2.rectangle(canvas, (12, 6), (12 + tw, 6 + th), (90, 90, 90), 1)
        label = f"{r['tag']} {r['kind']}"
        cv2.putText(canvas, label[:30], (10, 205), cv2.FONT_HERSHEY_SIMPLEX,
                    0.42, (210, 210, 210), 1, cv2.LINE_AA)
        cv2.putText(canvas, f"{r.get('roi')}", (10, 194), cv2.FONT_HERSHEY_SIMPLEX,
                    0.34, (150, 200, 150), 1, cv2.LINE_AA)
        tiles.append(canvas)
    if tiles:
        cols = min(4, len(tiles))
        rows_n = (len(tiles) + cols - 1) // cols
        sheet = np.full((rows_n * 214, cols * 224, 3), 18, np.uint8)
        for i, t in enumerate(tiles):
            rr, cc = divmod(i, cols)
            sheet[rr * 214:(rr + 1) * 214, cc * 224:(cc + 1) * 224] = t
        sheet_path = os.path.join(outdir, f"{base}_regions.png")
        cv2.imwrite(sheet_path, sheet)
    else:
        sheet_path = None

    print(f"\n########## {path}  ({W}x{H}) ##########")
    print(f"  标注图   : {out}")
    if sheet_path:
        print(f"  区域印相 : {sheet_path}")
    print(f"  共 {len(rows_out)} 个候选：")
    for r in rows_out:
        extra = " ".join(f"{k}={v}" for k, v in r.items() if k not in ("tag", "kind", "roi", "pixelBox"))
        print(f"    [{r['tag']:>2}] {r['kind']:<24} {str(r.get('roi','')):<44} {extra}")
    return out


def main():
    ap = argparse.ArgumentParser(description="把检测结果画到截图上供人工确认")
    ap.add_argument("images", nargs="+")
    ap.add_argument("--outdir", default="shots/_analysis")
    ap.add_argument("--top", type=int, default=14)
    ap.add_argument("--min-area", type=int, default=1500)
    a = ap.parse_args()
    for p in a.images:
        annotate(p, a.outdir, a.top, a.min_area)


if __name__ == "__main__":
    main()
