/**
 * 网络四条工具共用的目标地址（2026-10-14 新建）。
 *
 * ## 为什么单独一个文件，而不是放在 `diagnosticRegistry.ts` 里
 * 这份状态有**两个消费者**：四条描述符的 `run()`（在注册表里）与输入区组件
 * `NetworkTargetBar.vue`。注册表要 import 那个组件（它是 group 的 `toolbar`），
 * 组件又要读这份状态 —— 放在注册表里就形成 `registry ⇄ NetworkTargetBar` 的循环 import。
 *
 * 循环在 ESM 里通常"能跑"（组件只在渲染时才读这个绑定，那时注册表已经求值完了），
 * 但它是一条**依赖求值顺序**的隐式约定：哪天有人在组件的 `<script setup>` 顶层
 * 读一下 `networkTarget`，就会变成 TDZ 报错，而且报错现场（组件）与病因（循环）隔得很远。
 * 抽成叶子模块后依赖是单向的：`registry → networkTarget`、`NetworkTargetBar → networkTarget`。
 *
 * ## 为什么是模块作用域的共享对象，不是每条描述符各持一份
 * 旧 `DiagnosticModal` 就是一个 host 输入框 + 一个端口输入框管四个按钮：用户在 ping 里
 * 填完地址再点 traceroute 不该重填，四条也应当打在**同一个目标**上（否则结论没法互相印证）。
 * 放在这里而不是页面里，是因为描述符的 `run()` 必须读到它，而描述符不认识组件实例。
 */
import { reactive } from 'vue';

/**
 * `port` 允许 `null`：`n-input-number` 被清空时给的就是 null，而它的 `value` 形参类型是
 * `number | null` —— 这里声明成 `number` 会让 vue-tsc 在 `v-model:value` 上报 TS2322。
 * 消费侧用 `?? DEFAULT_TCP_PORT` 兜回默认值。
 */
export const networkTarget = reactive<{ host: string; port: number | null }>({ host: '', port: 443 });

/** 端口留空时 TCP 探测用的默认值（与旧 `DiagnosticModal` 的初值一致）。 */
export const DEFAULT_TCP_PORT = 443;
