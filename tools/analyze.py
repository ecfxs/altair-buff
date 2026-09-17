#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
截图分析器 (Screenshot Analyzer)
=================================

用途：在没有视觉模型、无法"用眼睛看"截图的情况下，从游戏截图中程序化提取
标定所需的全部信息，并直接产出 rules.json 的候选片段。

它同时是 P3 阶段 PC 端标定工具的分析内核。

用法
----
    python3 tools/analyze.py shot.png
    python3 tools/analyze.py shot.png --ascii --cols 110
    python3 tools/analyze.py shot.png --roi 0.80,0.02,0.99,0.26 --ascii --cols 70
    python3 tools/analyze.py market.png field.png --json out.json   # 多图：找稳定区（好的场景锚点）

输出
----
    [1] 基本信息
    [2] 全屏 ASCII 概览（亮度图 + 色相图，两通道）
    [3] 主色板
    [4] UI 元素候选
        4.1 小型方形图标阵列  -> BUFF 栏候选
        4.2 圆形部件          -> 虚拟摇杆候选
        4.3 小地图面板        -> 小地图 ROI 候选
        4.4 高饱和孤立小色块   -> 小地图角色定位点候选
        4.5 面板/弹窗矩形
    [5] 多图差异图（给多张截图时）-> 稳定区域 = 好的场景锚点
    [6] 候选配置片段 (JSON)
"""

import argparse
import json
import sys
from collections import Counter

import cv2
import numpy as np

# ---------------------------------------------------------------- 基础工具

LUMA_RAMP = " .:-=+*#%@"

HUE_BANDS = [
    (0, 15, "R"), (15, 45, "O"), (45, 70, "Y"), (70, 160, "G"),
    (160, 195, "C"), (195, 260, "B"), (260, 315, "P"), (315, 361, "R"),
]


def hue_char(h, s, v):
    """把一个 HSV 像素映射成一个字符。大写=高饱和，小写=低饱和，'.'=灰/黑。"""
    if s < 40 or v < 30:
        return "."
    for lo, hi, ch in HUE_BANDS:
        if lo <= h < hi:
            return ch if s >= 90 else ch.lower()
    return "?"


def load(path):
    img = cv2.imread(path, cv2.IMREAD_COLOR)
    if img is None:
        raise SystemExit(f"无法读取图片: {path}")
    return img


def norm_roi(roi, w, h):
    """归一化 ROI -> 像素 (x1,y1,x2,y2)，并做越界裁剪。"""
    x1, y1, x2, y2 = roi
    px1, py1 = int(round(x1 * w)), int(round(y1 * h))
    px2, py2 = int(round(x2 * w)), int(round(y2 * h))
    px1, px2 = max(0, min(px1, px2)), min(w, max(px1, px2))
    py1, py2 = max(0, min(py1, py2)), min(h, max(py1, py2))
    return px1, py1, px2, py2


def px_to_norm(x1, y1, x2, y2, w, h):
    return [round(x1 / w, 4), round(y1 / h, 4), round(x2 / w, 4), round(y2 / h, 4)]


def crop(img, roi):
    h, w = img.shape[:2]
    x1, y1, x2, y2 = norm_roi(roi, w, h)
    return img[y1:y2, x1:x2], (x1, y1, x2, y2)


# ---------------------------------------------------------------- ASCII 渲染

def ascii_luma(gray, cols=100, aspect=0.5):
    h, w = gray.shape[:2]
    rows = max(1, int(round(cols * aspect * h / w)))
    small = cv2.resize(gray, (cols, rows), interpolation=cv2.INTER_AREA)
    out = []
    for r in range(rows):
        out.append("".join(LUMA_RAMP[min(9, int(small[r, c]) * 10 // 256)] for c in range(cols)))
    return out


def ascii_hue(hsv, cols=100, aspect=0.5):
    h, w = hsv.shape[:2]
    rows = max(1, int(round(cols * aspect * h / w)))
    small = cv2.resize(hsv, (cols, rows), interpolation=cv2.INTER_AREA)
    out = []
    for r in range(rows):
        line = []
        for c in range(cols):
            hh, ss, vv = small[r, c]
            line.append(hue_char(int(hh) * 2, int(ss), int(vv)))  # OpenCV H ∈ [0,180)
        out.append("".join(line))
    return out


def print_two_channel(img, title, cols=100, aspect=0.5):
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
    lum = ascii_luma(gray, cols, aspect)
    hue = ascii_hue(hsv, cols, aspect)
    print(f"\n--- {title} : 亮度图 ---")
    for l in lum:
        print("  " + l)
    print(f"--- {title} : 色相图 (大写=高饱和 小写=低饱和 .=灰/黑) ---")
    for l in hue:
        print("  " + l)


# ---------------------------------------------------------------- 主色板

def palette(img, k=8):
    small = cv2.resize(img, (160, 90), interpolation=cv2.INTER_AREA)
    z = small.reshape(-1, 3).astype(np.float32)
    crit = (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 20, 1.0)
    _, labels, centers = cv2.kmeans(z, k, None, crit, 3, cv2.KMEANS_PP_CENTERS)
    cnt = Counter(labels.flatten().tolist())
    total = sum(cnt.values())
    out = []
    for idx, c in cnt.most_common():
        b, g, r = [int(round(v)) for v in centers[idx]]
        patch = np.uint8([[[b, g, r]]])
        h, s, v = [int(x) for x in cv2.cvtColor(patch, cv2.COLOR_BGR2HSV)[0][0]]
        out.append({
            "hex": f"#{r:02X}{g:02X}{b:02X}",
            "rgb": [r, g, b],
            "hsv": [h * 2, s, v],
            "coverage": round(c / total, 4),
        })
    return out


# ---------------------------------------------------------------- 4.1 BUFF 栏

def _rect_candidates(region, min_side, max_side, min_contrast=18.0, max_count=400):
    """在区域内找"矩形 + 与周围环带颜色明显不同"的小块。

    相比"高饱和度像素"的做法，这里的判据是**形状**（矩形填充度）而不是颜色，
    因此在色彩丰富的游戏背景上依然稳定：背景再花哨也不是规则矩形小块。
    返回 (x, y, w, h) 元组列表。
    """
    gray = cv2.cvtColor(region, cv2.COLOR_BGR2GRAY)
    edges = cv2.Canny(gray, 50, 140)
    edges = cv2.morphologyEx(edges, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8))
    contours, _ = cv2.findContours(edges, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

    out = []
    for c in contours:
        x, y, bw, bh = cv2.boundingRect(c)
        if bw < min_side or bh < min_side or bw > max_side or bh > max_side:
            continue
        area = cv2.contourArea(c)
        if area <= 0:
            continue
        # 矩形填充度：正方形≈1.0，圆形≈0.785，三角形≈0.5
        rect_fill = area / float(bw * bh)
        if rect_fill < 0.80:
            continue
        peri = cv2.arcLength(c, True)
        circ = 4 * np.pi * area / (peri * peri) if peri > 0 else 1.0
        if circ > 0.86:                      # 太圆 -> 不是图标
            continue

        # 内部中位色 vs 周围环带中位色的距离：UI 元素与背景差异明显
        pad = max(2, int(0.30 * min(bw, bh)))
        ry1, ry2 = max(0, y - pad), min(region.shape[0], y + bh + pad)
        rx1, rx2 = max(0, x - pad), min(region.shape[1], x + bw + pad)
        patch = region[ry1:ry2, rx1:rx2].astype(np.float32)
        if patch.size == 0:
            continue
        inner = region[y:y + bh, x:x + bw].astype(np.float32)
        ring_mask = np.ones(patch.shape[:2], bool)
        ring_mask[y - ry1:y - ry1 + bh, x - rx1:x - rx1 + bw] = False
        ring_px = patch[ring_mask]
        if ring_px.shape[0] < 8 or inner.size == 0:
            continue
        contrast = float(np.linalg.norm(
            np.median(inner.reshape(-1, 3), axis=0) - np.median(ring_px, axis=0)))
        if contrast < min_contrast:
            continue
        out.append((int(x), int(y), int(bw), int(bh)))
        if len(out) >= max_count:
            break
    return out


def find_icon_rows(img, top_pct=0.30):
    """在上部区域寻找"一排等距等大的小方块"——BUFF 图标的典型形态。"""
    h, w = img.shape[:2]
    region = img[0:int(h * top_pct), :]
    gray = cv2.cvtColor(region, cv2.COLOR_BGR2GRAY)
    hsv = cv2.cvtColor(region, cv2.COLOR_BGR2HSV)

    # 判据是"矩形小块 + 与周围对比明显"，而非颜色阈值（见 _rect_candidates 说明）
    boxes = _rect_candidates(region,
                             max(10, int(0.012 * w)),
                             max(14, int(0.060 * w)))
    _ = (gray, hsv)

    # 按 y 聚类成"行"
    rows = []
    for b in sorted(boxes, key=lambda t: t[1]):
        placed = False
        for row in rows:
            ry = np.mean([bb[1] for bb in row])
            if abs(b[1] - ry) < max(4, b[3] * 0.5):
                row.append(b)
                placed = True
                break
        if not placed:
            rows.append([b])

    results = []
    for row in rows:
        if len(row) < 2:
            continue
        row.sort(key=lambda t: t[0])
        sizes = [bb[2] for bb in row]
        if max(sizes) - min(sizes) > 0.35 * max(sizes):
            continue  # 大小差异太大，不像图标行
        centers = [bb[0] + bb[2] / 2 for bb in row]
        gaps = np.diff(centers)
        if len(gaps) == 0:
            continue
        # 等距性检查：间距的变异系数
        cv_gap = float(np.std(gaps) / np.mean(gaps)) if np.mean(gaps) > 0 else 9.9
        if cv_gap > 0.45:
            continue
        x1 = min(bb[0] for bb in row)
        x2 = max(bb[0] + bb[2] for bb in row)
        y1 = min(bb[1] for bb in row)
        y2 = max(bb[1] + bb[3] for bb in row)
        results.append({
            "itemCount": len(row),
            "pixelBox": [int(x1), int(y1), int(x2), int(y2)],
            "avgSizePx": int(round(float(np.mean(sizes)))),
            "avgGapPx": int(round(float(np.mean(gaps)))),
            "gapRegularity": round(1.0 - min(cv_gap, 1.0), 3),
        })
    results.sort(key=lambda r: (-r["itemCount"], -r["gapRegularity"]))
    return results, (w, h)


# ---------------------------------------------------------------- 4.2 圆形 / 摇杆

def find_circles(img):
    h, w = img.shape[:2]
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    gray = cv2.medianBlur(gray, 5)
    rmin = int(0.025 * min(h, w))
    rmax = int(0.16 * min(h, w))
    if rmax <= rmin:
        return [], (w, h)
    circles = cv2.HoughCircles(
        gray, cv2.HOUGH_GRADIENT, dp=1.4, minDist=int(0.08 * min(h, w)),
        param1=110, param2=38, minRadius=rmin, maxRadius=rmax,
    )
    out = []
    if circles is not None:
        for cx, cy, r in np.round(circles[0]).astype(int):
            if r <= 0:
                continue
            nx, ny = cx / w, cy / h
            # 摇杆几乎总在左下角
            in_bl = nx < 0.42 and ny > 0.52
            out.append({
                "centerNorm": [round(float(nx), 4), round(float(ny), 4)],
                "radiusNorm": round(float(r) / float(min(w, h)), 4),
                "clockwise": "bottom-left" if in_bl else "elsewhere",
                "joystickLikely": bool(in_bl),
            })
    out.sort(key=lambda c: (not c["joystickLikely"], -c["radiusNorm"]))
    return out, (w, h)


# ---------------------------------------------------------------- 4.3 小地图面板

def find_minimap(img):
    """小地图通常是某个角落的矩形面板：有边界、内部颜色方差大（是张地图）。"""
    h, w = img.shape[:2]
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
    edges = cv2.Canny(gray, 60, 160)
    edges = cv2.morphologyEx(edges, cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8))
    contours, _ = cv2.findContours(edges, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    cands = []
    for c in contours:
        x, y, bw, bh = cv2.boundingRect(c)
        if bw < 0.08 * w or bh < 0.08 * h:
            continue
        if bw > 0.55 * w or bh > 0.55 * h:
            continue
        area = cv2.contourArea(c)
        if area <= 0:
            continue
        rect_fill = area / float(bw * bh)
        if rect_fill < 0.80:            # 圆形约 0.785 —— 摇杆会被排除
            continue
        peri = cv2.arcLength(c, True)
        circ = 4 * np.pi * area / (peri * peri) if peri > 0 else 1.0
        if circ > 0.86:
            continue
        roi = img[y:y + bh, x:x + bw]
        if roi.size == 0:
            continue
        proj = cv2.Laplacian(cv2.cvtColor(roi, cv2.COLOR_BGR2GRAY), cv2.CV_64F).var()
        sat = float(hsv[y:y + bh, x:x + bw, 1].mean())
        # 地图：细节丰富（proj 高）且有一定饱和度
        # 同类游戏的 UI 布局习惯：小地图通常在角落，右上尤其常见
        cx_n, cy_n = (x + bw / 2) / w, (y + bh / 2) / h
        corner_bonus = 1.6 if (cx_n > 0.62 and cy_n < 0.42) else 1.0
        score = proj * (1.0 + sat / 255.0) * corner_bonus * rect_fill
        cands.append({
            "pixelBox": [int(x), int(y), int(bw), int(bh)],
            "detailScore": round(float(proj), 1),
            "meanSat": round(sat, 1),
            "score": round(float(score), 1),
            "corner": ("top-right" if x + bw / 2 > w * 0.65 and y + bh / 2 < h * 0.4 else
                       "bottom-right" if x + bw / 2 > w * 0.65 else
                       "top-left" if y + bh / 2 < h * 0.4 else "bottom-left"),
        })
    cands.sort(key=lambda c: -c["score"])
    return cands[:5], (w, h)


# ---------------------------------------------------------------- 4.4 角色定位点

def find_player_dot(img, roi):
    """在小地图 ROI 内找"孤立的、高饱和/高亮的小色块"——角色定位点候选。"""
    sub, (x1, y1, x2, y2) = crop(img, roi)
    if sub.size == 0:
        return []
    hsv = cv2.cvtColor(sub, cv2.COLOR_BGR2HSV)
    h, s, v = hsv[:, :, 0], hsv[:, :, 1], hsv[:, :, 2]
    sub_h, sub_w = sub.shape[:2]
    area_total = sub_h * sub_w

    masks = {
        "highSat": ((s > 140) & (v > 140)).astype(np.uint8) * 255,
        "veryBright": ((v > 225) & (s < 90)).astype(np.uint8) * 255,
        "veryDark": ((v < 45)).astype(np.uint8) * 255,
    }
    out = []
    for name, m in masks.items():
        m = cv2.morphologyEx(m, cv2.MORPH_OPEN, np.ones((2, 2), np.uint8))
        n, labels, stats, cents = cv2.connectedComponentsWithStats(m, connectivity=8)
        for i in range(1, n):
            ax, ay, aw, ah, area = stats[i]
            if area < 3 or area > 0.02 * area_total:
                continue
            if aw == 0 or ah == 0:
                continue
            aspect = aw / ah
            if not (0.35 <= aspect <= 2.8):
                continue
            pad = 3
            yy1, yy2 = max(0, ay - pad), min(sub_h, ay + ah + pad)
            xx1, xx2 = max(0, ax - pad), min(sub_w, ax + aw + pad)
            ring = np.ones((yy2 - yy1, xx2 - xx1), bool)
            ring[ay - yy1:ay - yy1 + ah, ax - xx1:ax - xx1 + aw] = False
            ring_vals = sub[yy1:yy2, xx1:xx2][ring]
            if ring_vals.size == 0:
                continue
            hh = int(round(float(np.median(h[labels == i]))))
            ss = int(round(float(np.median(s[labels == i]))))
            vv = int(round(float(np.median(v[labels == i]))))
            out.append({
                "method": name,
                "centerInRoiNorm": [round(float(cents[i][0]) / sub_w, 4),
                                    round(float(cents[i][1]) / sub_h, 4)],
                "areaPx": int(area),
                "sizePx": [int(aw), int(ah)],
                "hsv": [hh * 2, ss, vv],
                "hsvRangeSuggestion": {
                    "h": [max(0, hh * 2 - 12), min(180, hh * 2 + 12)],
                    "s": [max(0, ss - 70), 255],
                    "v": [max(0, vv - 70), 255],
                },
            })
    out.sort(key=lambda d: d["areaPx"])
    return out[:8]


# ---------------------------------------------------------------- 4.5 面板矩形

def find_panels(img, min_pct=0.06):
    h, w = img.shape[:2]
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    _, th = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    contours, _ = cv2.findContours(th, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    out = []
    for c in contours:
        x, y, bw, bh = cv2.boundingRect(c)
        if bw < min_pct * w or bh < min_pct * h:
            continue
        if bw > 0.95 * w and bh > 0.95 * h:
            continue
        peri = cv2.arcLength(c, True)
        approx = cv2.approxPolyDP(c, 0.02 * peri, True)
        if len(approx) != 4:
            continue
        out.append({"pixelBox": [int(x), int(y), int(bw), int(bh)],
                    "areaPct": round(bw * bh / (w * h), 4)})
    out.sort(key=lambda r: -r["areaPct"])
    # 去重（近似重叠）
    ded = []
    for r in out:
        if all(abs(r["pixelBox"][0] - d["pixelBox"][0]) > 8 or
               abs(r["pixelBox"][1] - d["pixelBox"][1]) > 8 or
               abs(r["pixelBox"][2] - d["pixelBox"][2]) > 8 for d in ded):
            ded.append(r)
    return ded[:8]


# ---------------------------------------------------------------- 多图差异

def stability_map(imgs, grid=(12, 8)):
    """多张截图的逐格差异：差异小 = 静态 UI（好的场景锚点）；差异大 = 动态内容。"""
    gh, gw = grid
    mats = []
    for img in imgs:
        small = cv2.resize(img, (gw, gh), interpolation=cv2.INTER_AREA).astype(np.float32)
        mats.append(small)
    stack = np.stack(mats, axis=0)
    var = stack.std(axis=0).mean(axis=2)  # (gh, gw)
    return var


# ---------------------------------------------------------------- main

def analyze(path, args):
    img = load(path)
    h, w = img.shape[:2]
    aspect_ratio = w / h
    orientation = "landscape" if w > h else ("portrait" if h > w else "square")

    print("=" * 78)
    print(f"截图分析: {path}")
    print("=" * 78)

    # [1] 基本信息
    print("\n[1] 基本信息")
    print(f"    分辨率        : {w} x {h}")
    print(f"    方向          : {orientation}")
    print(f"    宽高比        : {aspect_ratio:.4f}  ({'16:9' if abs(aspect_ratio-16/9)<0.02 else '非16:9'})")
    print(f"    Q-04 回答     : {'横屏' if orientation=='landscape' else '竖屏'}")

    # [2] ASCII
    if args.ascii:
        print_two_channel(img, "全屏", cols=args.cols, aspect=args.aspect)

    # [3] 主色板
    print("\n[3] 主色板 (k-means k=8)")
    for p in palette(img):
        print(f"    {p['hex']}  rgb={str(p['rgb']):<18} hsv={str(p['hsv']):<18} 占比 {p['coverage']*100:5.1f}%")

    # [4.1] BUFF 栏
    print("\n[4.1] 小型方形图标阵列  —— BUFF 栏候选")
    rows, _ = find_icon_rows(img)
    if not rows:
        print("    未找到规则图标阵列。可能：BUFF 栏不在上部 / 图标被文字覆盖 / 截图时无 BUFF。")
    for i, r in enumerate(rows[:4]):
        x1, y1, x2, y2 = r["pixelBox"]
        roi = px_to_norm(x1, y1, x2, y2, w, h)
        print(f"    #{i+1} 数量={r['itemCount']:<3} 尺寸≈{r['avgSizePx']}px 间距≈{r['avgGapPx']}px "
              f"等距度={r['gapRegularity']}")
        print(f"        pixelBox={r['pixelBox']}  归一化ROI={roi}")

    # [4.2] 圆形
    print("\n[4.2] 圆形部件  —— 虚拟摇杆候选")
    circles, _ = find_circles(img)
    if not circles:
        print("    未检出圆形。若游戏用虚拟摇杆，请人工确认左下角；也可能是被技能图标干扰。")
    for c in circles[:5]:
        tag = "  <== 疑似摇杆(左下)" if c["joystickLikely"] else ""
        print(f"    中心={c['centerNorm']} 半径={c['radiusNorm']} 位置={c['clockwise']}{tag}")

    # [4.3] 小地图
    print("\n[4.3] 面板/地图区域  —— 小地图候选")
    maps, _ = find_minimap(img)
    if not maps:
        print("    未检出候选面板。")
    for m in maps:
        x, y, bw, bh = m["pixelBox"]
        roi = px_to_norm(x, y, x + bw, y + bh, w, h)
        print(f"    {m['corner']:<13} pixelBox={m['pixelBox']} 细节={m['detailScore']:<8} "
              f"饱和度={m['meanSat']:<6} 归一化ROI={roi}")

    # [4.4] 角色点
    dot_roi = None
    if args.dot_roi:
        dot_roi = [float(v) for v in args.dot_roi.split(",")]
    elif maps:
        x, y, bw, bh = maps[0]["pixelBox"]
        dot_roi = px_to_norm(x, y, x + bw, y + bh, w, h)
    if dot_roi:
        print(f"\n[4.4] 小地图内孤立小色块  —— 角色定位点候选  (搜索ROI={dot_roi})")
        dots = find_player_dot(img, dot_roi)
        if not dots:
            print("    未找到候选定位点。=> Q-08 可能为「没有」；走位需降级为定时长行走。")
            print("    也可用 --dot-roi 手工指定更精确的小地图范围后重试。")
        for d in dots:
            print(f"    {d['method']:<11} ROI内位置={d['centerInRoiNorm']} 面积={d['areaPx']}px "
                  f"尺寸={d['sizePx']} hsv={d['hsv']}")
            print(f"        HSV阈值建议: {d['hsvRangeSuggestion']}")

    # [4.5] 面板
    print("\n[4.5] 面板/弹窗矩形候选")
    panels = find_panels(img)
    if not panels:
        print("    无。")
    for p in panels[:5]:
        x, y, bw, bh = p["pixelBox"]
        print(f"    pixelBox={p['pixelBox']} 占屏={p['areaPct']*100:.1f}% "
              f"归一化={px_to_norm(x, y, x + bw, y + bh, w, h)}")

    # [6] 配置片段
    frag = {"meta": {"refWidth": w, "refHeight": h, "orientation": orientation}}
    if rows:
        r = rows[0]
        x1, y1, x2, y2 = r["pixelBox"]
        frag["screens.buffBar"] = {
            "roi": px_to_norm(x1, y1, x2, y2, w, h),
            "slotCount": r["itemCount"],
            "slotPitch": round(r["avgGapPx"] / w, 4),
            "slotSize": round(r["avgSizePx"] / w, 4),
            "_confidence": r["gapRegularity"],
        }
    if circles:
        c = next((x for x in circles if x["joystickLikely"]), circles[0])
        frag["screens.joystick"] = {
            "center": c["centerNorm"], "radius": c["radiusNorm"],
            "_confidence": "high" if c["joystickLikely"] else "low",
        }
    if maps:
        x, y, bw, bh = maps[0]["pixelBox"]
        frag["screens.minimap"] = {
            "roi": px_to_norm(x, y, x + bw, y + bh, w, h),
            "_confidence": maps[0]["corner"],
        }
        if dot_roi:
            dots = find_player_dot(img, dot_roi)
            if dots:
                d = dots[0]
                frag["screens.minimap.playerColor"] = d["hsvRangeSuggestion"]
                frag["screens.minimap.playerAreaRange"] = [max(3, d["areaPx"] - 4), d["areaPx"] * 4]
    print("\n[6] 候选配置片段 (需人工确认后合并进 rules.json)")
    print(json.dumps(frag, ensure_ascii=False, indent=2))
    return frag


def main():
    ap = argparse.ArgumentParser(description="游戏截图分析器：为标定提取候选配置")
    ap.add_argument("images", nargs="+", help="截图路径（可多张，多张时额外输出稳定性差异图）")
    ap.add_argument("--ascii", action="store_true", help="输出全屏 ASCII 概览（亮度+色相双通道）")
    ap.add_argument("--cols", type=int, default=100, help="ASCII 宽度（默认100）")
    ap.add_argument("--aspect", type=float, default=0.5, help="ASCII 字符宽高比补偿（默认0.5）")
    ap.add_argument("--roi", default=None, help="只分析指定 ROI，格式 x1,y1,x2,y2（归一化）")
    ap.add_argument("--dot-roi", default=None, help="角色定位点搜索范围，格式 x1,y1,x2,y2（归一化）")
    ap.add_argument("--json", default=None, help="把候选配置片段写入 JSON 文件")
    args = ap.parse_args()

    frags = []
    for p in args.images:
        img = load(p)
        if args.roi:
            h, w = img.shape[:2]
            x1, y1, x2, y2 = norm_roi([float(v) for v in args.roi.split(",")], w, h)
            sub = img[y1:y2, x1:x2]
            tmp = "/tmp/_roi_crop.png"
            cv2.imwrite(tmp, sub)
            print(f"[ROI 模式] 裁剪 {args.roi} -> {sub.shape[1]}x{sub.shape[0]}  临时文件 {tmp}")
            if args.ascii:
                print_two_channel(sub, f"ROI {args.roi}", cols=args.cols, aspect=args.aspect)
                args.ascii = False      # 已打印过，避免 analyze() 里对裁剪图重复渲染
            p = tmp
        frags.append(analyze(p, args))

    if len(args.images) > 1:
        print("\n" + "=" * 78)
        print("[5] 多图稳定性分析  —— 找「场景锚点」该放哪儿")
        print("=" * 78)
        imgs = [load(p) for p in args.images]
        base = imgs[0].shape[:2]
        imgs = [cv2.resize(i, (base[1], base[0])) for i in imgs]
        var = stability_map(imgs)
        gh, gw = var.shape
        print(f"    网格 {gw} x {gh}，数值 = 各图在该格的标准差（越小越稳定）")
        print("    稳定的格子 = 所有截图里都一样 = 适合当场景锚点")
        print("\n    稳定性图 (数字越小越稳定, '.'<6 ':'<15 '+'<40 '*'<80 '#'>=80):")
        for r in range(gh):
            line = []
            for c in range(gw):
                v = var[r, c]
                line.append("." if v < 6 else ":" if v < 15 else "+" if v < 40 else "*" if v < 80 else "#")
            print(f"      {''.join(line)}")
        # 好的锚点 = 稳定（各图一致） 且 有辨识度（纹理丰富）。
        # 只"稳定"没用：一片纯色地面在两张图里都稳定，但当锚点毫无区分能力。
        g0 = cv2.cvtColor(imgs[0], cv2.COLOR_BGR2GRAY).astype(np.float64)
        lap = np.abs(cv2.Laplacian(g0, cv2.CV_64F))
        cell_h, cell_w = max(1, g0.shape[0] // gh), max(1, g0.shape[1] // gw)
        detail = np.zeros((gh, gw), np.float64)
        for r in range(gh):
            for c in range(gw):
                cell = lap[r * cell_h:(r + 1) * cell_h, c * cell_w:(c + 1) * cell_w]
                detail[r, c] = float(cell.mean()) if cell.size else 0.0
        ranked = sorted(
            ((float(detail[r, c] / (var[r, c] + 1.0)), float(var[r, c]), float(detail[r, c]), r, c)
             for r in range(gh) for c in range(gw)),
            reverse=True)
        print("\n    推荐锚点 top5（稳定 × 有辨识度，分数越高越好）:")
        for sc, v, d, r, c in ranked[:5]:
            roi = [round(c / gw, 4), round(r / gh, 4), round((c + 1) / gw, 4), round((r + 1) / gh, 4)]
            print(f"      分数={sc:7.2f}  std={v:6.2f}  细节={d:6.2f}  网格({c},{r})  归一化ROI={roi}")

    if args.json:
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump(frags if len(frags) > 1 else frags[0], f, ensure_ascii=False, indent=2)
        print(f"\n候选配置已写入: {args.json}")

    print("\n提示：所有候选都需人工确认。BUFF 栏候选尤其要用 --ascii 目视核对后再定稿。")


if __name__ == "__main__":
    main()
