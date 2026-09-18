import type { InputHTMLAttributes, ReactNode, SelectHTMLAttributes } from 'react';

export const inputCls =
  'w-full rounded-input border border-line-2 bg-inset px-[9px] py-1.5 text-xs text-input-fg outline-none focus:border-brand';

/** 表单格子：标签 + 控件（旧面板 .form label）。 */
export function Field({
  label,
  hint,
  children,
  full = false,
}: {
  label: ReactNode;
  hint?: ReactNode;
  children: ReactNode;
  full?: boolean;
}) {
  return (
    <label className={`flex flex-col gap-[5px] text-[11px] text-muted ${full ? 'col-span-full' : ''}`}>
      <span>{label}</span>
      {children}
      {hint ? <span className="text-[10.5px] text-muted-2">{hint}</span> : null}
    </label>
  );
}

/** 表单外框（旧面板 .form，自适应多列）。 */
export function FormGrid({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <div
      className={`card-shell grid gap-[11px] p-4 ${className ?? ''}`}
      style={{ gridTemplateColumns: 'repeat(auto-fit,minmax(210px,1fr))' }}
    >
      {children}
    </div>
  );
}

export function TextInput(props: InputHTMLAttributes<HTMLInputElement>) {
  return <input {...props} className={`${inputCls} ${props.className ?? ''}`} />;
}

export function NumberInput({
  value,
  onChange,
  min,
  max,
  width = 62,
  suffix,
}: {
  value: number;
  onChange: (n: number) => void;
  min?: number;
  max?: number;
  width?: number;
  suffix?: string;
}) {
  return (
    <span className="inline-flex items-center gap-1.5 text-[11.5px] text-dim">
      <input
        type="number"
        className={inputCls}
        style={{ width }}
        value={value}
        min={min}
        max={max}
        onChange={(e) => {
          const n = Number.parseInt(e.target.value, 10);
          onChange(Number.isFinite(n) ? n : 0);
        }}
      />
      {suffix ? <span>{suffix}</span> : null}
    </span>
  );
}

export function Select({
  value,
  onChange,
  children,
  className,
}: {
  value: string;
  onChange: (v: string) => void;
  children: ReactNode;
  className?: string;
}) {
  return (
    <select
      className={`${inputCls} cursor-pointer ${className ?? ''}`}
      value={value}
      onChange={(e) => onChange(e.target.value)}
    >
      {children}
    </select>
  );
}

/** 复选框 + 文案。 */
export function Check({
  checked,
  onChange,
  label,
}: {
  checked: boolean;
  onChange: (v: boolean) => void;
  label?: ReactNode;
}) {
  return (
    <label className="inline-flex cursor-pointer items-center gap-1.5 text-[11.5px] text-dim">
      <input
        type="checkbox"
        className="size-[14px] accent-brand"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
      />
      {label}
    </label>
  );
}
