/**
 * `/api/carrier/*` 的唯一入口（2026-10-31）。
 *
 * 与 App 共用 core 的会话：Web 登录后 App 打开账单页直接可用，反之亦然。
 * 发码会产生真实短信费用，触发前在 UI 上单独确认。
 */
import type { AxiosInstance } from 'axios';

export type CarrierClient = Pick<AxiosInstance, 'get' | 'post' | 'put' | 'patch' | 'delete'>;

export interface CarrierStatus {
  state:
    | 'not_configured'
    | 'login_required'
    | 'code_sent'
    | 'qr_pending'
    | 'ready'
    | 'error'
    // 联通扫码轮询的中间态（GET /unicom/login/status）
    | 'waiting'
    | 'scanned'
    | 'expired';
  carrier?: 'mobile' | 'unicom' | 'iot_card';
  phone?: string;
  message?: string;
  expires_in?: number;
  retry_after?: number;
  retry_after_ms?: number;
  qr_png_base64?: string;
  last_fetched_at?: number;
}

export interface CarrierFlow {
  general_used_gb: number;
  general_total_gb: number;
  general_remaining_gb: number;
  directed_used_gb: number;
  directed_total_gb: number;
  directed_remaining_gb: number;
  regional_used_gb?: number | null;
  regional_total_gb?: number | null;
  regional_remaining_gb?: number | null;
  general_unlimited?: boolean;
}

export interface CarrierPackage {
  kind: 'general' | 'directed' | 'regional';
  name: string;
  remaining_gb: number;
  total_gb: number;
  valid_from?: string | null;
  valid_to?: string | null;
}

export interface CloudOrder {
  name: string;
  amount_yuan?: number | null;
  paid_yuan?: number | null;
  status_text: string;
  pay_type_text?: string | null;
  created_at?: string | null;
  effective_time?: string | null;
  expiration_time?: string | null;
}

export interface CloudCard {
  provider_id?: 'cloud' | 'xj';
  provider_name?: string;
  card_no: string;
  iccid?: string | null;
  series_name?: string | null;
  expires_at?: string | null;
  days_left?: number | null;
  status_text?: string | null;
  real_name_text?: string | null;
  real_used_gb?: number | null;
  real_total_gb?: number | null;
  real_remaining_gb?: number | null;
  orders?: CloudOrder[];
  refreshed_at?: string | null;
}

export interface CarrierBill {
  carrier: 'mobile' | 'unicom' | 'iot_card';
  access_method?: 'carrier' | 'iot_card';
  cloud_card?: CloudCard | null;
  phone: string;
  plan_name?: string | null;
  owner_name?: string | null;
  tenure_months?: number | null;
  star_level?: number | null;
  balance_yuan?: number | null;
  last_month_bill_yuan?: number | null;
  current_bill_yuan?: number | null;
  current_bill_period?: string | null;
  current_bill_as_of?: string | null;
  points?: number | null;
  flow: CarrierFlow;
  monthly_used_gb?: number | null;
  voice?: { used: number; total: number; remaining: number } | null;
  sms?: { used: number; total: number; remaining: number } | null;
  packages: CarrierPackage[];
}

export interface CarrierBillResponse {
  state: string;
  bill?: CarrierBill | null;
  warnings?: string[];
  /** core 没有有效会话，但返回了磁盘缓存里的最近一次数据。 */
  login_required?: boolean;
  /** 实时刷新失败，返回的是旧缓存。 */
  stale?: boolean;
  /** 缓存拉取时刻（epoch ms）。 */
  cached_at?: number | null;
  message?: string;
}

export function getCarrierStatus(client: CarrierClient): Promise<CarrierStatus> {
  return client.get('/api/carrier/status').then((r) => r.data);
}

export function requestCarrierCode(client: CarrierClient, phone: string): Promise<CarrierStatus> {
  return client.post('/api/carrier/mobile/login/request', { phone }).then((r) => r.data);
}

export function verifyCarrierCode(client: CarrierClient, code: string): Promise<CarrierStatus> {
  return client.post('/api/carrier/mobile/login/verify', { code }).then((r) => r.data);
}

export function startUnicomQr(client: CarrierClient): Promise<CarrierStatus> {
  return client.post('/api/carrier/unicom/login/qr').then((r) => r.data);
}

export function pollUnicomQr(client: CarrierClient): Promise<CarrierStatus> {
  return client.get('/api/carrier/unicom/login/status').then((r) => r.data);
}

export function getCarrierBill(client: CarrierClient, refresh = false): Promise<CarrierBillResponse> {
  return client.get('/api/carrier/bill', { params: refresh ? { refresh: 1 } : {} }).then((r) => r.data);
}

export function logoutCarrier(client: CarrierClient): Promise<CarrierStatus> {
  return client.post('/api/carrier/logout').then((r) => r.data);
}

export interface CarrierAutoLoginState {
  supported: boolean;
  carrier?: 'mobile' | 'unicom' | 'iot_card';
  sim_operator?: string;
  operator_name?: string;
  enabled: boolean;
  phone?: string;
  phase: string;
  message?: string;
  last_login_at?: number;
  next_check_at?: number;
  attempts_today: number;
}

export interface CarrierAutoLoginConfig {
  enabled: boolean;
  phone: string;
  check_interval_minutes: number;
  relogin_interval_minutes: number;
  max_attempts_per_day: number;
}

export function getAutoLogin(client: CarrierClient): Promise<CarrierAutoLoginState> {
  return client.get('/api/carrier/auto-login').then((r) => r.data);
}

export function saveAutoLogin(client: CarrierClient, config: CarrierAutoLoginConfig): Promise<CarrierAutoLoginState> {
  return client.put('/api/carrier/auto-login', config).then((r) => r.data);
}

export function runAutoLogin(client: CarrierClient): Promise<CarrierAutoLoginState> {
  return client.post('/api/carrier/auto-login/run').then((r) => r.data);
}

/* ───────────────────────── 多账号（2026-11-05，P4）─────────────────────────
 *
 * 老接口语义 = 「当前账号」，保持原样；下面这一组带 `/accounts/{id}` 前缀。
 * 老 core 没有这些路由 → 404，调用方据此退回单账号（计划 E4）。
 */

export interface CarrierAccount {
  id: string;
  access_method?: 'carrier' | 'iot_card';
  /** 物联卡站点：cloud=云上卡，xj=小鲸卡。 */
  iot_provider?: 'cloud' | 'xj';
  carrier?: 'mobile' | 'unicom' | 'iot_card';
  /** 已脱敏。 */
  phone: string;
  label: string;
  state: 'ready' | 'login_required' | 'not_configured' | 'error';
  created_at: number;
}

export interface CarrierAccountsResponse {
  active_id?: string | null;
  accounts: CarrierAccount[];
}

export function getCarrierAccounts(client: CarrierClient): Promise<CarrierAccountsResponse> {
  return client.get('/api/carrier/accounts').then((r) => r.data);
}

export function addCarrierAccount(
  client: CarrierClient,
  body: { carrier: 'mobile' | 'unicom'; phone: string; label?: string }
): Promise<CarrierAccount> {
  return client.post('/api/carrier/accounts', body).then((r) => r.data);
}

export function addIotCardAccount(
  client: CarrierClient,
  body: { card: string; label?: string; provider?: 'cloud' | 'xj' }
): Promise<CarrierAccount> {
  return client.post('/api/carrier/accounts', { access_method: 'iot_card', ...body }).then((r) => r.data);
}

export function loginCloudCard(
  client: CarrierClient,
  id: string,
  card: string,
  verify?: string
): Promise<CarrierStatus> {
  return client
    .post(`/api/carrier/accounts/${id}/login/cloud`, {
      card,
      ...(verify ? { verify } : {}),
    })
    .then((r) => r.data);
}

export function renameCarrierAccount(client: CarrierClient, id: string, label: string): Promise<{ success: boolean }> {
  return client.patch(`/api/carrier/accounts/${id}`, { label }).then((r) => r.data);
}

export function deleteCarrierAccount(client: CarrierClient, id: string): Promise<{ success: boolean }> {
  return client.delete(`/api/carrier/accounts/${id}`).then((r) => r.data);
}

export function activateCarrierAccount(client: CarrierClient, id: string): Promise<CarrierStatus> {
  return client.post(`/api/carrier/accounts/${id}/activate`).then((r) => r.data);
}

export function requestCarrierCodeFor(client: CarrierClient, id: string, phone: string): Promise<CarrierStatus> {
  return client.post(`/api/carrier/accounts/${id}/login/mobile/request`, { phone }).then((r) => r.data);
}

export function verifyCarrierCodeFor(client: CarrierClient, id: string, code: string): Promise<CarrierStatus> {
  return client.post(`/api/carrier/accounts/${id}/login/mobile/verify`, { code }).then((r) => r.data);
}

export function startUnicomQrFor(client: CarrierClient, id: string): Promise<CarrierStatus> {
  return client.post(`/api/carrier/accounts/${id}/login/unicom/qr`).then((r) => r.data);
}

export function pollUnicomQrFor(client: CarrierClient, id: string): Promise<CarrierStatus> {
  return client.get(`/api/carrier/accounts/${id}/login/unicom/status`).then((r) => r.data);
}

export function getCarrierBillOf(client: CarrierClient, id: string, refresh = false): Promise<CarrierBillResponse> {
  return client.get(`/api/carrier/accounts/${id}/bill`, { params: refresh ? { refresh: 1 } : {} }).then((r) => r.data);
}

export function getAutoLoginOf(client: CarrierClient, id: string): Promise<CarrierAutoLoginState> {
  return client.get(`/api/carrier/accounts/${id}/auto-login`).then((r) => r.data);
}

export function saveAutoLoginOf(
  client: CarrierClient,
  id: string,
  config: CarrierAutoLoginConfig
): Promise<CarrierAutoLoginState> {
  return client.put(`/api/carrier/accounts/${id}/auto-login`, config).then((r) => r.data);
}

export function runAutoLoginOf(client: CarrierClient, id: string): Promise<CarrierAutoLoginState> {
  return client.post(`/api/carrier/accounts/${id}/auto-login/run`).then((r) => r.data);
}

/* ───────────────────────── 设备级账单设置（2026-11-13）─────────────────────────
 *
 * 自动登录参数是设备级：所有参与账号共用一条短信通道、一个调度器、一个每日额度。
 * 账号侧只保留 participating（参与/不参与）。与 App 的「账单设置 › 自动登录」同源。
 */

export interface CarrierBillSettingsAccount {
  id: string;
  label: string;
  /** 已脱敏。 */
  phone: string;
  carrier: string;
  participating: boolean;
  supported: boolean;
  phase: string;
  message?: string | null;
  attempts_today: number;
  last_login_at?: number | null;
  next_check_at?: number | null;
}

export interface CarrierBillSettings {
  refresh: {
    enabled: boolean;
    interval_minutes: number;
  };
  keepalive: {
    enabled: boolean;
    check_interval_minutes: number;
    relogin_interval_minutes: number;
    daily_sms_limit: number;
    attempts_today: number;
    next_check_at?: number | null;
  };
  accounts: CarrierBillSettingsAccount[];
}

export interface CarrierBillSettingsPatch {
  refresh?: {
    enabled?: boolean;
    interval_minutes?: number;
  };
  keepalive?: {
    enabled?: boolean;
    check_interval_minutes?: number;
    relogin_interval_minutes?: number;
    daily_sms_limit?: number;
  };
}

export function getBillSettings(client: CarrierClient): Promise<CarrierBillSettings> {
  return client.get('/api/carrier/bill/settings').then((r) => r.data);
}

export function saveBillSettings(client: CarrierClient, patch: CarrierBillSettingsPatch): Promise<CarrierBillSettings> {
  return client.put('/api/carrier/bill/settings', patch).then((r) => r.data);
}

export function saveBillSettingsAccount(
  client: CarrierClient,
  id: string,
  participating: boolean
): Promise<CarrierBillSettings> {
  return client.put(`/api/carrier/bill/settings/accounts/${id}`, { participating }).then((r) => r.data);
}
