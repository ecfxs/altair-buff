import type { components } from './schema';

/** 契约里的全部 schema 类型，直接来自 ../api/openapi.yaml（pnpm gen 生成）。 */
export type Schemas = components['schemas'];

export type DeviceSummary = Schemas['DeviceSummary'];
export type EngineStatus = Schemas['EngineStatus'];
export type Heartbeat = Schemas['Heartbeat'];
export type Buff = Schemas['Buff'];
export type DeviceConfig = Schemas['DeviceConfig'];
export type ConfigEnvelope = Schemas['ConfigEnvelope'];
export type ConfigRevision = Schemas['ConfigRevision'];
export type DevicesResponse = Schemas['DevicesResponse'];
export type DeviceDetail = Schemas['DeviceDetail'];
export type ReportRow = Schemas['ReportRow'];
export type HistoryPoint = Schemas['HistoryPoint'];
export type LogLine = Schemas['LogLine'];
export type Screenshot = Schemas['Screenshot'];
export type AuditEntry = Schemas['AuditEntry'];
export type Settings = Schemas['Settings'];
export type Me = Schemas['Me'];
export type SessionInfo = Schemas['SessionInfo'];
export type DesiredState = Schemas['DesiredState'];

/** `/devices` 里 totals 的字段（契约里是内联对象）。 */
export type Totals = NonNullable<DevicesResponse['totals']>;

/** scope = `default` 或具体 deviceId。 */
export const DEFAULT_SCOPE = 'default';
