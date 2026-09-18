import { useEffect, type ReactNode } from 'react';
import { Button } from './Button';
import { Icon, type IconName } from './Icon';

/** 居中弹窗（旧面板 #modal / .sheet）。ESC 与点遮罩关闭。 */
export function Modal({
  open,
  title,
  icon = 'log',
  onClose,
  children,
  footer,
  wide = false,
}: {
  open: boolean;
  title: ReactNode;
  icon?: IconName;
  onClose: () => void;
  children: ReactNode;
  footer?: ReactNode;
  wide?: boolean;
}) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  if (!open) return null;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-6"
      onClick={onClose}
      role="presentation"
    >
      <div
        className={`flex max-h-[82vh] w-full flex-col overflow-hidden rounded-xl border border-line-2 bg-surface ${
          wide ? 'max-w-[1080px]' : 'max-w-[820px]'
        }`}
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
      >
        <div className="flex items-center gap-[10px] border-b border-line-3 px-4 py-[13px]">
          <Icon name={icon} size={16} className="text-muted" />
          <b className="font-mono text-[13px] font-semibold text-ok">{title}</b>
          <Button className="ml-auto" onClick={onClose} icon="close">
            关闭
          </Button>
        </div>
        <div className="min-h-0 flex-1 overflow-auto">{children}</div>
        {footer ? <div className="border-t border-line-3 px-4 py-3">{footer}</div> : null}
      </div>
    </div>
  );
}

/** 右侧抽屉（日志/参数就地编辑用）。 */
export function Drawer({
  open,
  title,
  icon = 'log',
  onClose,
  children,
  footer,
}: {
  open: boolean;
  title: ReactNode;
  icon?: IconName;
  onClose: () => void;
  children: ReactNode;
  footer?: ReactNode;
}) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex justify-end bg-black/60" onClick={onClose} role="presentation">
      <aside
        className="flex h-full w-full max-w-[720px] flex-col border-l border-line-2 bg-surface"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
      >
        <div className="flex items-center gap-[10px] border-b border-line-3 px-4 py-[13px]">
          <Icon name={icon} size={16} className="text-muted" />
          <b className="font-mono text-[13px] font-semibold text-ok">{title}</b>
          <Button className="ml-auto" onClick={onClose} icon="close">
            关闭
          </Button>
        </div>
        <div className="min-h-0 flex-1 overflow-auto">{children}</div>
        {footer ? <div className="border-t border-line-3 px-4 py-3">{footer}</div> : null}
      </aside>
    </div>
  );
}
