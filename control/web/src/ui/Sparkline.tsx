import { useMemo } from 'react';

export type Series = {
  /** 折线数据（按时间升序），null 表示该点缺失。 */
  values: Array<number | null>;
  color: string;
  label: string;
  /** 纵轴最小值/最大值提示，仅用于图例展示。 */
  unit?: string;
};

/**
 * 轻量 SVG 折线（不引图表库）。
 * 多条序列共享同一时间轴，各自独立归一化，用于「轮次 / 电量 / 温度 / 延迟」曲线。
 */
export function Sparkline({
  series,
  height = 120,
  width = 640,
}: {
  series: Series[];
  height?: number;
  width?: number;
}) {
  const paths = useMemo(() => {
    const pad = 6;
    const h = height - pad * 2;
    const w = width - pad * 2;
    const n = Math.max(1, ...series.map((s) => s.values.length));

    return series.map((s) => {
      const nums = s.values.filter((v): v is number => v != null && Number.isFinite(v));
      const min = nums.length ? Math.min(...nums) : 0;
      const max = nums.length ? Math.max(...nums) : 1;
      const span = max - min || 1;
      let d = '';
      let started = false;
      s.values.forEach((v, i) => {
        if (v == null || !Number.isFinite(v)) {
          started = false;
          return;
        }
        const x = pad + (n === 1 ? w / 2 : (i / (n - 1)) * w);
        const y = pad + h - ((v - min) / span) * h;
        d += `${started ? 'L' : 'M'}${x.toFixed(1)} ${y.toFixed(1)} `;
        started = true;
      });
      return { d: d.trim(), color: s.color, label: s.label, min, max, unit: s.unit };
    });
  }, [series, height, width]);

  const hasData = paths.some((p) => p.d.length > 0);

  return (
    <div className="space-y-2">
      <svg
        viewBox={`0 0 ${width} ${height}`}
        className="h-[120px] w-full rounded-input border border-line-3 bg-inset"
        preserveAspectRatio="none"
        role="img"
        aria-label="上报历史曲线"
      >
        {[0.25, 0.5, 0.75].map((f) => (
          <line
            key={f}
            x1="6"
            x2={width - 6}
            y1={6 + (height - 12) * f}
            y2={6 + (height - 12) * f}
            stroke="#212832"
            strokeWidth="1"
          />
        ))}
        {paths.map((p, i) =>
          p.d ? (
            <path
              key={i}
              d={p.d}
              fill="none"
              stroke={p.color}
              strokeWidth="1.6"
              strokeLinejoin="round"
              strokeLinecap="round"
              vectorEffect="non-scaling-stroke"
            />
          ) : null,
        )}
      </svg>
      <div className="flex flex-wrap gap-3 text-[10.5px] text-muted-3">
        {paths.map((p) => (
          <span key={p.label} className="inline-flex items-center gap-1.5">
            <span className="inline-block h-[3px] w-4 rounded" style={{ background: p.color }} />
            {p.label}
            <span className="num text-muted-2">
              [{p.min}–{p.max}
              {p.unit ?? ''}]
            </span>
          </span>
        ))}
        {hasData ? null : <span>暂无足够数据点</span>}
      </div>
    </div>
  );
}
