export function ProgressBar({ pct }: { pct: number }) {
  return (
    <div className="prog my-[7px] mb-1">
      <i style={{ width: `${Math.max(0, Math.min(100, pct)).toFixed(1)}%` }} />
    </div>
  );
}

export function StatusDot({ color, size = 8 }: { color: string; size?: number }) {
  return (
    <span
      className="inline-block shrink-0 rounded-full"
      style={{ width: size, height: size, background: color }}
    />
  );
}

/** 统计条里的一格（旧面板 .stat）。 */
export function StatBox({
  value,
  label,
  color,
  title,
}: {
  value: string | number;
  label: string;
  color?: string;
  title?: string;
}) {
  return (
    <div className="stat-box" title={title}>
      <b style={color ? { color } : undefined}>{value}</b>
      <span>{label}</span>
    </div>
  );
}
