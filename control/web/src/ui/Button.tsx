import type { ButtonHTMLAttributes, ReactNode } from 'react';
import { Icon, type IconName } from './Icon';

type Variant = 'default' | 'primary' | 'danger';

const VARIANTS: Record<Variant, string> = {
  default: 'bg-[#242c36] text-fg border-line-2 hover:bg-[#2e3844]',
  primary: 'bg-brand text-white border-brand hover:bg-brand-hover',
  danger: 'bg-badge-err text-error border-card-off hover:bg-[#4a2228]',
};

type Props = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: Variant;
  icon?: IconName;
  children?: ReactNode;
};

export function Button({ variant = 'default', icon, children, className, ...rest }: Props) {
  return (
    <button
      type="button"
      className={`inline-flex cursor-pointer items-center gap-1.5 rounded-ctl border px-3 py-[7px] text-xs leading-none transition-colors disabled:cursor-not-allowed disabled:opacity-50 ${
        VARIANTS[variant]
      } ${className ?? ''}`}
      {...rest}
    >
      {icon ? <Icon name={icon} /> : null}
      {children}
    </button>
  );
}
