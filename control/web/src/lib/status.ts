import { CYCLE_FACTOR } from './time';

/** 引擎状态徽章文案（旧面板 stateBadge 的等价实现）。 */
export const ENGINE_STATES = ['WAITING', 'CASTING', 'IDLE', 'PAUSED', 'ERROR'] as const;
export type EngineState = (typeof ENGINE_STATES)[number];

export type BadgeTone = 'run' | 'cast' | 'idle' | 'err';

const MAP: Record<string, { tone: BadgeTone; text: string }> = {
  WAITING: { tone: 'run', text: '挂机中 · 等待' },
  CASTING: { tone: 'cast', text: '正在补 BUFF' },
  IDLE: { tone: 'idle', text: '已停止' },
  PAUSED: { tone: 'idle', text: '已暂停' },
  ERROR: { tone: 'err', text: '出错已熔断' },
};

export function stateBadge(state: string | null | undefined): { tone: BadgeTone; text: string } {
  const key = (state ?? 'IDLE').toUpperCase();
  return MAP[key] ?? { tone: 'idle', text: key };
}

/** 引擎状态是否属于「挂机中」（统计条口径）。 */
export function isRunning(engine: { running?: boolean | null; state?: string | null } | null | undefined): boolean {
  if (!engine) return false;
  if (typeof engine.running === 'boolean') return engine.running;
  const s = (engine.state ?? '').toUpperCase();
  return s === 'WAITING' || s === 'CASTING';
}

export function inputMethodLabel(m: string | null | undefined): string {
  return m === 'touch' ? '触摸点击' : m === 'keyevent' ? '键盘按键' : '—';
}

/** 面板里那句必须保留的提示文案。 */
export const CYCLE_HINT = `启用的 BUFF 里取最短时长 × ${CYCLE_FACTOR} 作为循环周期`;

/** 空态引导（旧面板原文）。 */
export const EMPTY_DEVICES_HINT =
  '还没有设备上报。在 App 里填服务器地址 + Token，点「上报一次」。';
