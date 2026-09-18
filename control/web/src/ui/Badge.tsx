import type { ReactNode } from 'react';
import type { BadgeTone } from '@/lib/status';

const TONES: Record<BadgeTone, string> = {
  run: 'bg-badge-run text-ok',
  cast: 'bg-badge-cast text-warn-2',
  idle: 'bg-badge-idle text-muted',
  err: 'bg-badge-err text-error',
};

export function Badge({ tone = 'idle', children }: { tone?: BadgeTone; children: ReactNode }) {
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-badge px-[9px] py-[2px] text-[11px] font-medium ${TONES[tone]}`}
    >
      {children}
    </span>
  );
}

/** 自由配色的徽章（协议版本/同步状态等）。 */
export function Chip({
  children,
  tone = 'idle',
  active = false,
  title,
}: {
  children: ReactNode;
  tone?: 'idle' | 'ok' | 'warn';
  active?: boolean;
  title?: string;
}) {
  const base = 'rounded-[5px] border px-2 py-[2px] text-[10.5px]';
  const cls = active
    ? 'border-card-on text-ok bg-chip'
    : tone === 'warn'
      ? 'border-card-warm text-warn bg-chip'
      : 'border-line text-muted bg-chip';
  return (
    <span className={`${base} ${cls}`} title={title}>
      {children}
    </span>
  );
}
