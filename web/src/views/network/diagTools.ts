/**
 * 网络诊断四工具的登记表（2026-10-22）。
 *
 * 为什么单独一个文件：网络页的「诊断中心」要拆成**四条独立路由 + 一个选择弹窗**，
 * 而路由（router/index.ts）、弹窗、页面都要知道有哪四条、各自的 id 是什么。
 * 三处各写一份 `'ping' | 'dns' | 'tcp' | 'traceroute'` 迟早会漂移；这里只放数据，
 * 不放图标（图标是渲染层的事，留在弹窗组件里）。
 *
 * `id` 必须与 `views/diagnostics/diagnosticRegistry.ts` 里的 `net-*` 对齐：
 * 弹窗按 id 去注册表取执行函数与渲染器，**不重写任何请求逻辑**。
 */
export type NetworkDiagTool = 'ping' | 'dns' | 'tcp' | 'traceroute';

export interface NetworkDiagToolSpec {
  key: NetworkDiagTool;
  /** 诊断注册表里的 item.id */
  id: string;
  label: string;
  desc: string;
}

export const NETWORK_DIAG_TOOLS: NetworkDiagToolSpec[] = [
  { key: 'ping', id: 'net-ping', label: 'Ping', desc: 'ICMP 连通性与往返时延' },
  { key: 'dns', id: 'net-dns', label: 'DNS 解析', desc: '用设备自己的 resolver 解析目标' },
  { key: 'tcp', id: 'net-tcp', label: 'TCP 探测', desc: '到指定端口的连接是否可达' },
  { key: 'traceroute', id: 'net-traceroute', label: '路由追踪', desc: '逐跳查看到目标的路径（最长 15 跳）' },
];
