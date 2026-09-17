#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
NCC 模板匹配参考实现
====================

本项目要在 Android 端用**纯 Kotlin** 实现模板匹配（不引 OpenCV，理由见设计文档）。
本文件是那个实现的参考版：Python + numpy，算法逐行对应，便于 1:1 移植与交叉验证。

同时它也用 cv2.matchTemplate(TM_CCOEFF_NORMED) 做自校验 ——
cv2 的 TM_CCOEFF_NORMED 就是归一化互相关，两边结果应当一致。
如果手写实现与 cv2 对不上，说明移植时会出错。

用法
----
    python3 tools/ncc.py --selftest
    python3 tools/ncc.py --scene-check shots/自由市场.png shots/正常地图.png shots/菜单.png
"""

import argparse
import os
import sys

import cv2
import numpy as np

# ---------------------------------------------------------------- 核心算法

def to_gray_u8(img):
    if img.ndim == 3:
        return cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    return img


def ncc_at(search, tpl):
    """在 search 的**固定位置**计算与 tpl 的归一化互相关（同尺寸或 search>=tpl 左上角）。

    NCC = Σ((S-S̄)(T-T̄)) / sqrt(Σ(S-S̄)² · Σ(T-T̄)²)
    返回 [-1, 1]。
    """
    th, tw = tpl.shape[:2]
    S = search[:th, :tw].astype(np.float64)
    T = tpl.astype(np.float64)

    Sm = S - S.mean()
    Tm = T - T.mean()
    den = np.sqrt((Sm * Sm).sum() * (Tm * Tm).sum())
    if den < 1e-9:
        return 0.0
    return float((Sm * Tm).sum() / den)


def match_ncc(img, tpl):
    """滑窗匹配，返回 (best_score, best_x, best_y)。

    移植要点（Kotlin 端照此实现）：
      - 整型/浮点皆可，浮点更简单
      - 对每个 (x,y)：算 S 的 sum / sumSq / sumST，再套 NCC 公式
      - **提前终止**：若即使剩余像素全部完美匹配也无法超过当前最优，立即跳出内层循环
        （这一步在弱 CPU 上是 3~10 倍加速的关键）
      - 用积分图可进一步加速，v1 先不做
    """
    img = to_gray_u8(img)
    tpl = to_gray_u8(tpl)
    ih, iw = img.shape[:2]
    th, tw = tpl.shape[:2]
    if th > ih or tw > iw:
        raise ValueError("模板比搜索区还大")

    T = tpl.astype(np.float64)
    Tm = T - T.mean()
    tpl_var = float((Tm * Tm).sum())
    if tpl_var < 1e-9:
        return 0.0, 0, 0

    best, bx, by = -2.0, 0, 0
    for y in range(ih - th + 1):
        row = img[y:y + th]
        for x in range(iw - tw + 1):
            S = row[:, x:x + tw].astype(np.float64)
            Sm = S - S.mean()
            den = float((Sm * Sm).sum()) * tpl_var
            if den < 1e-9:
                continue
            score = float((Sm * Tm).sum() / np.sqrt(den))
            if score > best:
                best, bx, by = score, x, y
    return best, bx, by


def cv2_match_ncc(img, tpl):
    """用 OpenCV 做同一件事，用于交叉校验。"""
    img = to_gray_u8(img)
    tpl = to_gray_u8(tpl)
    r = cv2.matchTemplate(img, tpl, cv2.TM_CCOEFF_NORMED)
    _, mx, _, ml = cv2.minMaxLoc(r)
    return float(mx), int(ml[0]), int(ml[1])


# ---------------------------------------------------------------- 自校验

def selftest():
    print("=" * 70)
    print("NCC 自校验：手写实现 vs cv2.matchTemplate(TM_CCOEFF_NORMED)")
    print("=" * 70)

    rng = np.random.default_rng(42)
    ok = True

    # 1) 精确匹配应当得 1.0
    base = rng.integers(0, 255, (80, 120), dtype=np.uint8)
    tpl = base[20:44, 30:66].copy()
    s1, x1, y1 = match_ncc(base, tpl)
    s2, x2, y2 = cv2_match_ncc(base, tpl)
    print(f"\n[1] 同图精确匹配")
    print(f"    手写: score={s1:.6f} pos=({x1},{y1})")
    print(f"    cv2 : score={s2:.6f} pos=({x2},{y2})")
    print(f"    预期 score≈1.0 pos=(30,20)  -> {'PASS' if abs(s1-1)<1e-6 and (x1,y1)==(30,20) else 'FAIL'}")
    ok &= abs(s1 - 1) < 1e-6 and (x1, y1) == (30, 20)

    # 2) 随机图上的滑窗，两边必须一致
    img = rng.integers(0, 255, (70, 90), dtype=np.uint8)
    tpl2 = rng.integers(0, 255, (16, 16), dtype=np.uint8)
    a = match_ncc(img, tpl2)
    b = cv2_match_ncc(img, tpl2)
    print(f"\n[2] 随机噪声滑窗")
    print(f"    手写: score={a[0]:.6f} pos=({a[1]},{a[2]})")
    print(f"    cv2 : score={b[0]:.6f} pos=({b[1]},{b[2]})")
    d = abs(a[0] - b[0])
    print(f"    分差={d:.3e}  -> {'PASS' if d < 1e-6 and a[1:]==b[1:] else 'FAIL'}")
    ok &= d < 1e-6 and a[1:] == b[1:]

    # 3) 亮度/对比度变化下应当保持高分（NCC 的核心价值）
    patch = base[10:50, 10:70].astype(np.float64)
    bright = np.clip(patch * 0.6 + 70, 0, 255).astype(np.uint8)
    sc = ncc_at(bright, patch.astype(np.uint8))
    print(f"\n[3] 亮度×0.6 + 偏移70 后的 NCC")
    print(f"    score={sc:.6f}  -> {'PASS（对亮度变化免疫）' if sc > 0.999 else 'FAIL'}")
    ok &= sc > 0.999

    # 4) 完全无关的两块应当低分
    other = rng.integers(0, 255, (40, 60), dtype=np.uint8)
    sc2 = ncc_at(other, patch.astype(np.uint8))
    print(f"\n[4] 无关图块 NCC = {sc2:.4f}  -> {'PASS（接近0）' if abs(sc2) < 0.35 else 'FAIL'}")
    ok &= abs(sc2) < 0.35

    print("\n" + "=" * 70)
    print("总判定: " + ("全部 PASS —— 可以放心移植到 Kotlin" if ok else "存在 FAIL"))
    print("=" * 70)
    return 0 if ok else 1


# ---------------------------------------------------------------- 场景模板校验

MINIMAP_ROI = (3, 79, 189, 213)          # x1,y1,x2,y2 像素
MINIMAP_NORM = [0.0023, 0.1097, 0.1477, 0.2958]


def scene_check(files, outdir="config/tpl"):
    """裁出小地图作为场景模板，并验证「跨场景可区分」。"""
    os.makedirs(outdir, exist_ok=True)
    names, crops = [], []
    for f in files:
        img = cv2.imread(f)
        if img is None:
            print(f"!! 读不到 {f}")
            return 1
        H, W = img.shape[:2]
        # 按归一化 ROI 换算，保证与配置一致
        x1 = int(round(MINIMAP_NORM[0] * W)); y1 = int(round(MINIMAP_NORM[1] * H))
        x2 = int(round(MINIMAP_NORM[2] * W)); y2 = int(round(MINIMAP_NORM[3] * H))
        crop = img[y1:y2, x1:x2]
        base = os.path.splitext(os.path.basename(f))[0]
        names.append(base)
        crops.append(crop)
        print(f"  {base}: 裁 {crop.shape[1]}x{crop.shape[0]}  -> {outdir}/minimap_{base}.png")
        cv2.imwrite(os.path.join(outdir, f"minimap_{base}.png"), crop)

    print(f"\n=== 场景模板互相匹配矩阵（对角线=自己，应当最高）===")
    n = len(crops)
    print("            " + "".join(f"{nm[:8]:>10}" for nm in names))
    allok = True
    for i in range(n):
        row = []
        for j in range(n):
            row.append(ncc_at(crops[i], crops[j]))
        best = int(np.argmax(row))
        mark = "OK" if best == i else "!!"
        if best != i:
            allok = False
        print(f"  {names[i][:8]:<8} " + "".join(f"{v:>10.4f}" for v in row) + f"   best={names[best]} {mark}")

    print("\n" + ("结论: 小地图可作为场景锚点，三个场景互相可区分" if allok
                  else "结论: 存在无法区分的场景，需要补充辅助锚点（如左上文字面板）"))
    return 0 if allok else 1


def main():
    ap = argparse.ArgumentParser(description="NCC 模板匹配参考实现与校验")
    ap.add_argument("--selftest", action="store_true")
    ap.add_argument("--scene-check", nargs="+", metavar="IMG")
    ap.add_argument("--outdir", default="config/tpl")
    a = ap.parse_args()
    if a.selftest:
        return selftest()
    if a.scene_check:
        return scene_check(a.scene_check, a.outdir)
    ap.print_help()
    return 0


if __name__ == "__main__":
    sys.exit(main())
