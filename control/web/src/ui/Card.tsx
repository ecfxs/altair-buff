import type { HTMLAttributes, ReactNode } from 'react';
import type { Freshness } from '@/lib/time';

/** 深色卡片；按在线状态换边框色（旧面板 .card.on/.warm/.off）。 */
export function Card({
  freshness,
  className,
  children,
  ...rest
}: HTMLAttributes<HTMLDivElement> & { freshness?: Freshness; children?: ReactNode }) {
  const tone =
    freshness === 'on' ? 'card-on' : freshness === 'warm' ? 'card-warm' : freshness === 'off' ? 'card-off' : '';
  return (
    <div className={`card-shell p-3.5 ${tone} ${className ?? ''}`} {...rest}>
      {children}
    </div>
  );
}

/** 卡片内部分区块（旧面板 .sec：上分隔线）。 */
export function Section({ className, children }: { className?: string; children: ReactNode }) {
  return <div className={`mt-[11px] border-t border-line-3 pt-2.5 ${className ?? ''}`}>{children}</div>;
}

/** 「标签 —— 值」行（旧面板 .rowk）。 */
export function KeyRow({
  k,
  children,
  tone,
}: {
  k: ReactNode;
  children: ReactNode;
  tone?: 'normal' | 'error';
}) {
  const color = tone === 'error' ? 'text-error' : 'text-fg';
  return (
    <div className="my-[2px] flex justify-between gap-3 text-[11.5px] text-dim">
      <span className="shrink-0">{k}</span>
      <b className={`text-right font-medium ${color}`}>{children}</b>
    </div>
  );
}

/** 小标题（旧面板 h2）。 */
export function SectionTitle({ children }: { children: ReactNode }) {
  return <h2>{children}</h2>;
}
