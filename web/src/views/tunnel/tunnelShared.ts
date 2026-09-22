/**
 * 隧道页各分片共用的小工具与类型。
 *
 * 与 `views/settings/settingsShared.ts` 同一个定位：拆分后被多个子组件用到、
 * 但不值得各自抄一份的东西放这里。**不要**往这里塞 API 调用 ——
 * 数据获取归各自的组件/composable，这里只放纯函数与类型。
 */

/** 隧道实例。FRP 与 CF 共用同一形状，`server_*` / `proxy_count` 只有 FRP 有，`token_set` 只有 CF 有。 */
export interface Instance {
  name: string;
  running: boolean;
  status: string;
  last_error: string;
  server_addr?: string;
  server_port?: number;
  proxy_count?: number;
  token_set?: boolean;
}

/**
 * core 的 /api/tunnel 有三种失败形态，必须都认：
 * ① respondFail：真实 HTTP 码 + { success:false, ok:false, error, message, code }（400/404）
 * ② 业务失败：**HTTP 200** + { success:false, message:中文原因 }（start/stop/delete 都是这种）
 * ③ 裸异常：{ error } 单字段（body 非 JSON 对象 / 未捕获异常 500）
 *
 * 本函数只负责从 ① ③ 里抽人话；② 因为 HTTP 是 200，得由调用方自己看 `data.success`
 * （隧道页里那一层在 `TunnelView.act()`）。
 */
export function errText(e: any, fallback: string): string {
  const d = e?.response?.data;
  return d?.message || d?.error || fallback;
}

/**
 * 状态文案。只有异常态需要它 —— 运行/停止由列表行左侧的状态点表达
 * （2026-09-21 行版式重排后，原先那个 `statusTagType()` 已无消费方，一并删除）。
 */
export function statusLabel(s: string): string {
  const map: Record<string, string> = { Running: '运行中', Stopped: '已停止', Error: '异常' };
  return map[s] || s;
}
