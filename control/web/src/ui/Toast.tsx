import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';

type Tone = 'info' | 'ok' | 'error';
type Item = { id: number; text: string; tone: Tone };

type Ctx = { push: (text: string, tone?: Tone) => void };

const ToastCtx = createContext<Ctx | null>(null);

const TONES: Record<Tone, string> = {
  info: 'border-line-2 text-fg',
  ok: 'border-card-on text-ok',
  error: 'border-card-off text-error',
};

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<Item[]>([]);
  const seq = useRef(0);

  const push = useCallback((text: string, tone: Tone = 'info') => {
    const id = ++seq.current;
    setItems((prev) => [...prev, { id, text, tone }]);
    setTimeout(() => setItems((prev) => prev.filter((i) => i.id !== id)), 6000);
  }, []);

  const value = useMemo(() => ({ push }), [push]);

  return (
    <ToastCtx.Provider value={value}>
      {children}
      <div className="pointer-events-none fixed bottom-5 left-1/2 z-[60] flex -translate-x-1/2 flex-col items-center gap-2">
        {items.map((i) => (
          <div
            key={i.id}
            className={`pointer-events-auto max-w-[80vw] rounded-ctl border bg-surface-2 px-4 py-2 text-xs shadow-lg ${TONES[i.tone]}`}
          >
            {i.text}
          </div>
        ))}
      </div>
    </ToastCtx.Provider>
  );
}

/** 取全局 toast；必须在 ToastProvider 内使用。 */
export function useToast(): Ctx {
  const ctx = useContext(ToastCtx);
  if (!ctx) throw new Error('useToast 必须在 <ToastProvider> 内使用');
  return ctx;
}
