import type { SVGProps } from 'react';

/** 内联 SVG 图标，零依赖（path 直接搬自旧面板 tools/control-server.py）。 */
export const ICONS = {
  play: '<path d="M8 5v14l11-7z"/>',
  stop: '<path d="M6 6h12v12H6z"/>',
  log: '<path d="M4 5h16v2H4zm0 5h16v2H4zm0 5h10v2H4z"/>',
  phone:
    '<path d="M17 1H7a2 2 0 0 0-2 2v18a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V3a2 2 0 0 0-2-2zm0 18H7V5h10v14z"/>',
  clock: '<path d="M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm1 11H9v-2h2V6h2z"/>',
  gear: '<path d="M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8zm9 4a9 9 0 0 0-.1-1.3l2-1.6-2-3.4-2.4 1a9 9 0 0 0-2.2-1.3L15.8 2h-3.9l-.4 2.4a9 9 0 0 0-2.2 1.3l-2.4-1-2 3.4 2 1.6A9 9 0 0 0 6.8 12c0 .4 0 .9.1 1.3l-2 1.6 2 3.4 2.4-1a9 9 0 0 0 2.2 1.3l.4 2.4h3.9l.4-2.4a9 9 0 0 0 2.2-1.3l2.4 1 2-3.4-2-1.6c.1-.4.1-.9.1-1.3z"/>',
  refresh: '<path d="M12 5V1L7 6l5 5V7a6 6 0 1 1-6 6H4a8 8 0 1 0 8-8z"/>',
  // 以下为旧面板没有、新页面需要的最小补充（同一套 Material 风格 path）
  home: '<path d="M12 3 2 12h3v9h6v-6h2v6h6v-9h3z"/>',
  list: '<path d="M3 5h18v2H3zm0 6h18v2H3zm0 6h18v2H3z"/>',
  shield: '<path d="M12 2 4 5v6c0 5 3.4 9.4 8 11 4.6-1.6 8-6 8-11V5z"/>',
  image: '<path d="M3 4h18v16H3zm2 2v9l4-4 3 3 3-4 4 5V6z"/>',
  copy: '<path d="M8 3h11v14h-2V5H8zm-3 4h11v14H5z"/>',
  close: '<path d="M6.4 5 5 6.4 10.6 12 5 17.6 6.4 19 12 13.4 17.6 19 19 17.6 13.4 12 19 6.4 17.6 5 12 10.6z"/>',
  chart: '<path d="M4 20V10h4v10zm6 0V4h4v16zm6 0v-7h4v7z"/>',
  search: '<path d="M10 3a7 7 0 1 0 4.2 12.6l4.6 4.6 1.4-1.4-4.6-4.6A7 7 0 0 0 10 3zm0 2a5 5 0 1 1 0 10 5 5 0 0 1 0-10z"/>',
  check: '<path d="M9 16.2 4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4z"/>',
  warn: '<path d="M12 2 1 21h22zm0 5 7.5 13h-15zM11 10h2v5h-2zm0 6h2v2h-2z"/>',
  bolt: '<path d="M13 2 4 14h6l-1 8 9-12h-6z"/>',
} as const;

export type IconName = keyof typeof ICONS;

type Props = SVGProps<SVGSVGElement> & {
  name: IconName;
  /** 16px 大图标（旧面板 .icon.lg），默认 14px。 */
  size?: number;
};

export function Icon({ name, size = 14, className, ...rest }: Props) {
  return (
    <svg
      viewBox="0 0 24 24"
      width={size}
      height={size}
      aria-hidden="true"
      focusable="false"
      className={`shrink-0 fill-current ${className ?? ''}`}
      {...rest}
      dangerouslySetInnerHTML={{ __html: ICONS[name] }}
    />
  );
}
