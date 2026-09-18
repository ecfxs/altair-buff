import { useEffect, useRef, useState } from 'react';

/**
 * 每 `intervalMs` 触发一次重渲染（倒计时用）。
 * 只在需要时才跑：传 `enabled=false` 完全不挂定时器。
 */
export function useTicker(intervalMs = 250, enabled = true): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!enabled) return;
    setNow(Date.now());
    const id = window.setInterval(() => setNow(Date.now()), intervalMs);
    return () => window.clearInterval(id);
  }, [intervalMs, enabled]);
  return now;
}

/**
 * 「距刷新」倒计时（秒，保留 1 位小数），归零时回调一次并自动重置。
 * 旧面板行为：每 250ms 走一格，到 0 就 refresh()。
 */
export function useRefreshCountdown(totalSec = 5, onElapsed?: () => void): number {
  const [left, setLeft] = useState(totalSec);
  const cb = useRef(onElapsed);
  cb.current = onElapsed;

  useEffect(() => {
    const step = 0.25;
    const id = window.setInterval(() => {
      setLeft((prev) => {
        const next = prev - step;
        if (next <= 0) {
          cb.current?.();
          return totalSec;
        }
        return next;
      });
    }, step * 1000);
    return () => window.clearInterval(id);
  }, [totalSec]);

  return left;
}
