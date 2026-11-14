/**
 * core:contract 的 web 镜像，与 `core/contract/src/main/kotlin/com/ufi_axis_core/contract/` 一一对应。
 *
 * **改动本文件必须同步修改 Kotlin 侧，反之亦然。**
 * 校验器：`node scripts/verify-api-contract.mjs`（端点差集 + WS 频道差集，输出的 P0 级差异必须为空）。
 *
 * 本文件为手工同步，未采用代码生成：当前不存在 exportContract → gen-types 链路。
 * 手工同步的正确性由上述校验器保证 —— 端点声明与 core 不一致会以 P0 级差异报出。
 */

/**
 * 端点路径（**带前导斜杠**，与 axios 调用现状一致）。
 *
 * 只登记真实存在的端点，不登记模块前缀：形如 api + 模块名的前缀不是端点，
 * 写入本表会被校验器判定为「客户端声明了不存在的端点」并报 P0。
 */
export const Endpoints = {
  alerts: {
    list: '/api/alerts/list',
    config: '/api/alerts/config',
    ack: '/api/alerts/ack',
    ackAll: '/api/alerts/ack-all',
    ackResolved: '/api/alerts/ack-resolved',
    delete: '/api/alerts/delete',
  },
  config: {
    root: '/api/config',
    version: '/api/config/version',
    reset: '/api/config/reset',
  },
  monitor: {
    history: '/api/monitor/history',
    storage: '/api/monitor/storage',
    clean: '/api/monitor/clean',
    control: '/api/monitor/control',
  },
  /**
   * 后台服务控制。
   * stop 只停止数据采集与告警检测，不停止 HTTP 服务，因此 start 始终可达；
   * restart 重启后端服务（重建 Service 组件，非进程级），接口中断约 10 秒。
   */
  service: {
    status: '/api/service/status',
    start: '/api/service/start',
    stop: '/api/service/stop',
    restart: '/api/service/restart',
    autostart: '/api/service/autostart',
  },
  /**
   * DLNA MediaServer。
   *
   * 开关门控在 core：目录未配置（status.ready=false）时 enabled=true 会被 400 拒绝，
   * 前端据此把开关置灰，而不是等请求报错。
   */
  dlna: {
    status: '/api/dlna/status',
    config: '/api/dlna/config',
  },
  /**
   * 设备能力与设备侧只读信息。
   *
   * ## 单独分组的理由
   * `/api/device/led`、`/api/device/samba`、`/api/device/thermal` 等端点目前仍以裸字符串
   * 写在各个视图里（`DeviceView.vue`、`ThermalModal.vue` 等），契约中此前没有 `device` 组。
   * 这种不一致不改变本组的约定：**新增端点必须有单一出处**。
   * `capabilities` 尤其要被能力门禁的基座引用 —— 同一取值散成多处字面量必然出现分歧。
   */
  device: {
    /**
     * 设备能力集。`{ plugin_id, capabilities: string[] }`。
     *
     * 返回**数组**而非 `{sms:true}` 形式的 map（core 侧刻意如此）：map 形状让旧客户端
     * 无法区分「这是一个未知的新能力」与「设备不支持」，而两者的界面行为完全相反。
     * 因此判断一律使用 `capabilities.includes(wire)`，不得使用 `caps.sms === false`。
     *
     * 取值是 `Capability` 的 wire 名，见本文件下方的 `Capability` 常量（两端镜像，
     * 守门测试在 `core/contract/.../CapabilityWireTest.kt`）。
     */
    capabilities: '/api/device/capabilities',
    /**
     * EPS 承载 QoS：`{ available, cid, qci, downlink_kbps, uplink_kbps, downlink_display, uplink_display }`。
     *
     * 两条必须遵守的约束：
     * ① **失败也返回 HTTP 200**，只是 `available: false` + 空字段（AT 通道在很多机型上
     *    不可用，这属于预期状态而非错误）⇒ 客户端按 `available` 显示「—」，不得按网络错误处理；
     * ② `*_display` 是 core 已换算好的文案（如 `500 Mbps`），**必须直接使用**：
     *    两端各写一份换算逻辑会出现不一致（如 500 Mbps 与 500.0 Mbps）。
     * core 侧有 5 分钟缓存（`CacheTTL.DEVICE_QOS`），前端无需自行节流。
     */
    qos: '/api/device/qos',
    /**
     * 流量校准：body `{ target: 'data' | 'time', value: string }` → `{ success }`。
     *
     * `target=data` 校准已用流量、`target=time` 校准已用时长；`value` **必须是字符串**
     * （设备侧按文本接收，传数字会与 profile 的 WriteSpec 类型不匹配）。
     * 设备拒绝（值非法/越界）返回 **400**，成功时 core 失效 `device:traffic-limit` 缓存。
     *
     * 旧字段 `way` + `data`/`time` 仍兼容，但会记录 WARN 日志；新代码统一使用 `target` + `value`。
     */
    flowCalibration: '/api/device/flow-calibration',
  },
  network: {
    status: '/api/network/status',
    /** 入参走 NetworkMode 的别名集，core 会映射成 BearerPreference 再下发 */
    mode: '/api/network/mode',
    band: '/api/network/band',
    bandStatus: '/api/network/band-status',
    connectionMode: '/api/network/connection-mode',
  },
  shell: {
    /** { root, uid, method } —— 替代不存在的 adb 状态端点 */
    root: '/api/shell/root',
    /** 执行一条 shell 命令；与 at 通道共用终端页历史（服务端在写侧记账） */
    exec: '/api/shell/exec',
    /**
     * 读一条系统属性：`{ key, value }`。
     *
     * **优先使用本端点，而不是 `shell.exec('getprop …')`**：core 侧会校验 key 白名单
     * （仅允许 `[a-zA-Z0-9._]`，防命令注入），而 `exec` 把拼好的命令交给 shell，
     * 校验责任落在调用方；此外 `exec` 会写入终端历史，读取只读属性不应污染历史记录。
     *
     * key 非法返回 **400**（不是 500）。`value` 是 `getprop` 的原始 stdout（含结尾换行），
     * 客户端需自行 `trim()`。
     */
    prop: (key: string) => `/api/shell/prop/${encodeURIComponent(key)}`,
  },
  /**
   * AT 命令通道。
   *
   * `command` 执行单条 AT 指令，`status` / `platform` 是只读探测。
   * 执行历史不由本组写入 —— 真源是服务端在 shell / at 路由执行后记录的历史。
   */
  at: {
    command: '/api/at/command',
    status: '/api/at/status',
    platform: '/api/at/platform',
  },
  tasks: {
    root: '/api/tasks',
    rules: '/api/rules',
  },
  /**
   * 应用管理。install-url 的 sha256 为必填：core 下载后流式校验，不匹配则拒绝安装，
   * 防止任意 URL 触发静默 root 安装。
   */
  apps: {
    root: '/api/apps',
    install: '/api/apps/install',
    /** POST { url, sha256 } —— sha256 必填（64 hex），http:// 仅内网调试地址豁免 */
    installUrl: '/api/apps/install-url',
    uninstall: '/api/apps/uninstall',
  },
  /**
   * 短信拦截：规则（号码黑名单 + 关键词）与拦截记录。
   *
   * 挂在既有的 sms 组下，不新开 sms-filter 组，与 core 的 `RootSmsRoutes` 一致。
   * 注意：本段说明中不得写入带前导斜杠的 api 路径字面量 —— 契约校验器会将其视为
   * 「客户端声明了该端点」，而模块前缀必然报 P0。
   *
   * 两条约束：
   * ① `PUT rules/{id}` 是**字段级合并**：只修改 `enabled` 就只传 `enabled`，
   *    全量提交会把 pattern/scope 一并覆盖为表单中的值；
   * ② `GET blocked` 的列表字段名是 **`records`**（不是 `logs`），
   *    keyset 游标参数是 `limit` / `cursor_ts` / `cursor_id`，
   *    响应返回 `next_cursor_ts` / `next_cursor_id` / `has_more`。
   *
   * 「验证码豁免关键词拦截」不在本组：它是布尔配置项，走既有的 config 端点
   * （键名 `sms_filter_exempt_verification_code`，默认 true）。
   */
  sms: {
    rules: '/api/sms/rules',
    rule: (id: number) => `/api/sms/rules/${id}`,
    blocked: '/api/sms/blocked',
    blockedRecord: (id: number) => `/api/sms/blocked/${id}`,
    /**
     * 批量删除多条短信。
     * body `{ ids: number[] }` → `{ success, deleted, requested }`；`ids` 为空返回 **400**。
     *
     * 本端点与 `/api/sms/delete`（单条）、`/api/sms/delete-conversation`（按号码整段）
     * 刻意分为三条：三者的删除范围语义不同，合并成一条带 mode 的接口后，
     * 调用点无法表达自己删除了什么。
     *
     * 注意：`deleted` 可能**小于** `requested`（部分记录已不存在），而 `success` 的判据是
     * `deleted > 0` —— 因此「一条都没删掉」返回 `success: false`。文案必须按 `deleted` 陈述，
     * 不得把 `success: false` 直接报成「删除失败」。
     */
    deleteBatch: '/api/sms/delete-batch',
  },
  /**
   * 邮件通知（仅 SMTP 一种通道）。
   * 路径**保持 `sms-forward` 不变**：app 与 API 手册均按此引用，改名会破坏跨端契约。
   * 写操作是 **POST 而非 PUT**；`POST /config` 是字段级合并，凭据字段传空串等同于不传
   * （保留原值，无法通过该端点清空）。
   * `POST /test` 使用已持久化的配置，未保存的改动不生效；失败同样返回 HTTP 200 + `{success:false,error}`。
   *
   * **`POST /config` 可能返回 400**：`daily_limit` 越界或 `min_level` 无法识别时返回 400 + 中文 `error`。
   * 客户端必须实现这条错误分支 —— 沿用「保存必成功」的假设会让用户看到「已保存」，
   * 而设备侧并未写入。
   *
   * `history` 是**三条渠道共用**的投递记录（`mail_send_records` 表，按 `channel` 列区分），
   * 不限于邮件；路径留在 sms-forward 组是因为表与端点先有邮件。三条约束：
   * ① `cursor_ts` 与 `cursor_id` **必须成对**，缺一个 core 按首页处理（只带 ts 时
   *    `id < NULL` 恒为 NULL，同一毫秒的边界行会被整段跳过）；
   * ② `DELETE` 的 `channel` 参数**不传即全部清除**，按渠道清空时必须带上它；
   * ③ `failed_total` 与 `skipped_total` 是两个独立计数，**不得相加**（跳过不是失败），
   *    且都是该渠道的全表计数，不随 `result` 筛选变化。
   *
   * `historyStats` 与 `historyRecord` 是三条渠道共用的另两条端点：
   * ④ `historyStats` 的 `channel` **缺失或为空 = 全渠道**（与 `history` / `DELETE history` 同口径），
   *    返回 `total` / `sent` / `failed` / `skipped` / `last_sent_at` —— 比列表响应多出
   *    `sent` 与 `last_sent_at` 两项，且**不受 `result` 筛选影响**；
   * ⑤ `historyRecord` 删除单条，`deleted = 0`（行已不存在）**仍返回 `success: true`**：
   *    客户端可直接移除本地行，不得把「本来就没有」报成删除失败。
   */
  smsForward: {
    config: '/api/sms-forward/config',
    diagnose: '/api/sms-forward/diagnose',
    test: '/api/sms-forward/test',
    history: '/api/sms-forward/history',
    historyStats: '/api/sms-forward/history/stats',
    historyRecord: (id: number) => `/api/sms-forward/history/${id}`,
  },
  /**
   * 另两条通知渠道：Webhook 与本机短信。
   * 与上面的 smsForward（邮件）并列，三条渠道各自独立存储配置，均不属于 notifications 组
   * —— 那一组管理的是「客户端是否弹出通知」。
   *
   * 本段说明中的路径统一写作 api/notify/...（**不带前导斜杠**）：契约校验器会把注释中
   * 带前导斜杠的 api 路径字面量也算作「web 声明了该端点」，而模块前缀必然报 P0。
   *
   * 四条约束：
   * ① 写操作是 **PUT 且字段级合并**（未出现或为 null 的键保留服务端现值），
   *    唯一例外是 Webhook 的 `headers`：传入即**整体替换**，因此修改任一头部
   *    都必须提交全量 map，否则无法删除旧的 Authorization；
   * ② 取值域与预设表由 GET 返回（Webhook 的 `presets` / `placeholders`，**三条渠道共有**的
   *    `levels` / `daily_limit_min` / `daily_limit_max`，见 [ChannelRules]），客户端只渲染，
   *    不得手抄第二份。`placeholders` 是 `{ name, desc }` 对象数组（文案真源在 core）；
   *    每个预设还带 `secret_label` / `secret_marker` / `secret_target`（`none` / `url` / `body`），
   *    即「用户只需填写这一项」的机器可读声明；替换时必须整段连同尖括号一并替换，
   *    残留裸 `<` 会让 core 判定配置不完整、渠道永不投递；
   * ③ 两个 test 端点**失败也返回 HTTP 200** + `{ success: false, error }`；
   *    Webhook 的 `status_code` 为 null 表示请求未完成（超时 / 连接失败 / DNS / TLS）；
   * ④ 本机短信的 test **会真的通过设备 SIM 发出一条短信**，产生费用并占用一条当日配额，
   *    调用前必须由用户确认。
   */
  notify: {
    webhookConfig: '/api/notify/webhook/config',
    webhookTest: '/api/notify/webhook/test',
    smsConfig: '/api/notify/sms/config',
    smsTest: '/api/notify/sms/test',
  },
  /**
   * 客户端通知偏好：总开关、免打扰窗口、后台守护、历史上限，以及 CRITICAL 兜底
   * （`critical_override_enabled`，见 [CRITICAL_OVERRIDE_DEFAULT]）。
   *
   * **不是渠道配置** —— 三条渠道各自的开关与投递规则在各自的端点里（见 [Endpoints.smsForward]
   * 与 [Endpoints.notify]）。PUT 是**字段级合并**，可以只回传变化的那一个键。
   */
  notifications: {
    config: '/api/notifications/config',
  },
  /**
   * 流量统计。
   *
   * `usage` 的响应形状与取数封装在 [api/traffic.ts](../api/traffic.ts)；四条 traffic 路径
   * 统一在本组登记，调用点不得再写裸路径字面量。
   *
   * 三条约束：
   * ① `realtime` 在调度器尚未预热时返回 **HTTP 200 + 失败信封**（`code = NO_DATA_YET`），
   *    不是 4xx —— 按 HTTP 码判断成功会把「尚无数据」当成有数据，读到大量 undefined；
   * ② `history` 的 `records[]` 字段是 **camelCase**（`rxBytes` / `txSpeed` / `timestamp`），
   *    这是直接序列化 Room 实体的结果，与全仓 snake_case 惯例相反，不得照其它端点推断；
   * ③ `usage` 的 `range` / `anchor` **都不会返回 400**：range 无法识别 → `day`，anchor 解析失败 → now。
   *    翻页锚点一律原样透传响应中的 `prev_anchor` / `next_anchor`，前端不得自行做日历运算
   *    （月长度、闰年、DST 已由 core 计算）。
   */
  traffic: {
    realtime: '/api/traffic/realtime',
    history: '/api/traffic/history',
    summary: '/api/traffic/summary',
    usage: '/api/traffic/usage',
    /**
     * 流量日/周/月报。
     * query `period` ∈ `day|week|month`（**默认 week**，其它值返回 400）。
     *
     * 返回 `{ ok, period, range_start, range_end, rx_bytes, tx_bytes, total_bytes, summary, days[] }`，
     * `days[]` 每项是 `{ day_start, day_label, rx_bytes, tx_bytes, total_bytes }`；
     * `day_label` 已由 core 按 `MM-dd` 格式化，客户端**不得再次格式化**
     * （时区与补零写法不一致会出现 10-4 与 10-04 并存）。
     *
     * 按**自然日**分桶（不是滑动 24 小时），因此「今天」是不完整的一天，看趋势应使用 week/month。
     */
    report: '/api/traffic/report',
  },
  /**
   * 天气（core 侧 `WeatherRoutes`，上游 Open-Meteo，**无需 API key**）。
   *
   * 四条约束：
   * ① `GET /api/weather` 有**两种 200 响应形状**：未设置城市时只返回
   *    `{configured: false, enabled, message}`，其余字段均不存在 —— 见 [WeatherNow]。
   *    直接读取 `temperature` 会得到 undefined，必须先判断 `configured`；
   * ② 上游不可达返回 **502**（`OPERATION_FAILED`），不是 200 + 失败信封；
   * ③ `weather_code` → 中文描述的映射已在 **core** 完成（响应的 `description` 字段），
   *    客户端不得另建 WMO 码表 —— 两份映射一旦分歧，会与手机端给出不同的天气描述；
   * ④ `PUT /config` 是字段级 patch，但会**校验**：纬度 -90..90、经度 -180..180、
   *    `unit` 仅接受 `celsius` / `fahrenheit`、`city` ≤ 64 字符，越界返回 400。
   *    `latitude = 0 && longitude = 0` 是「未设置」的哨兵值，不是有效坐标。
   */
  weather: {
    now: '/api/weather',
    search: '/api/weather/search',
    config: '/api/weather/config',
  },
  /**
   * 每日诗词（core 侧 `PoetryRoutes`，上游 jinrishici.com，**无需 API key**）。
   *
   * 两条约束：
   * ① `refresh=1` 只绕过 **core 本地的 10 分钟缓存**，上游自身也有约 10 分钟缓存 ——
   *    因此「刷新」可能返回同一首。这是上游缓存行为而非缺陷，界面不得承诺「换一首」；
   * ② 没有 tag 参数：选哪首由上游按设备出口 IP 的地理位置、当地天气、时辰与节气决定，
   *    命中的标签在响应的 `match_tags` 中回读。
   */
  poetry: {
    now: '/api/poetry',
    config: '/api/poetry/config',
  },
  /**
   * 国家/地区探测。诗词与天气的上游选源都依赖它。
   *
   * `country` 是 ISO 3166-1 alpha-2，**空串 = 从未探测成功**；此时 `source` 一定是 `unknown`
   * （即便刚刚确实发起过一次抓取），因此判断「有无结果」只看 `country` 是否非空。
   * `POST /detect` 在三个地理源全部不可达时返回 **502**，并保留上一次的结果不清空。
   */
  geo: {
    root: '/api/geo',
    detect: '/api/geo/detect',
  },
  /**
   * 设备本机媒体库（core 侧 `MediaRoutes`）。
   * web 侧界面在 `views/media/`（视频 / 音乐 / 图片三个独立页面）与文件预览弹窗。
   *
   * 六条约束：
   * ① 几乎每条都要 `type` ∈ `video` / `audio` / `image`，缺失或无法识别返回 400；
   *    权限不足返回 **403，但 code 仍是 `BAD_REQUEST`**（没有专用错误码），
   *    并带 `extra = {permission, type}`；
   * ② `thumbnail` / `cover` 返回**二进制图片**（带 ETag + `Cache-Control: private, max-age=86400`），
   *    不是 JSON。浏览器中只能使用 `<img>` + blob URL，因为 `<img src>` 无法携带 Bearer 头与设备签名 ——
   *    与 APK 下载受同一限制（见 [Endpoints.update] 的说明）；
   * ③ `PUT /thumbnail` 的 body 是**裸 JPEG 字节**（不是 JSON、不是 multipart），
   *    上限 512 KB，且必须以 `FF D8 FF` 开头，否则返回 400；
   * ④ `browse` 在「配置了多个根目录且未传 path」时返回**选择根目录**的形状：
   *    `path: ''` + `parent: null` + 非空 `roots` + 空 `folders`/`items`，不是「该目录为空」；
   * ⑤ **播放字节流不在本组**：走 `/media/stream?ticket=…`（挂在 `/api` 之外的免鉴权区），
   *    需先 `POST /api/files/stream-ticket` 换取一张只授权该文件、滑动过期 10 分钟的票据；
   *    **必须使用响应中的 `url`**，不得自行拼接；
   * ⑥ `subtitles` 的 `supported` 字段**不能直接当作「能播放」** —— 见该字段的说明。
   */
  media: {
    status: '/api/media/status',
    list: '/api/media/list',
    /**
     * 音频分组聚合（专辑 / 歌手 / 文件夹）。
     * 界面在 `views/media/components/GroupBrowserPanel.vue`，取数在 `views/media/mediaGroups.ts`。
     *
     * query：`type` 必须是 `audio`（其它值返回 400 —— `album` / `artist` 是 MediaStore.Audio
     * 独有的维度，视频与图片没有等价物，它们的「文件夹视图」走 [Endpoints.media.browse]）；
     * `by` ∈ `album` / `artist` / `folder`，其它值同样返回 400。未授权返回 **403，但 code 仍是
     * `BAD_REQUEST`**，与 [Endpoints.media.list] 同一约定。
     *
     * 返回 `{ type, by, groups: [{ key, title, subtitle, count, cover_id }], total }`。
     * `total` 是**分组总数**，不是曲目总数（曲目数在每组的 `count` 中）。
     *
     * 三条必须遵守，否则会静默给出错误结果：
     * ① 排序由 core 完成（`count` 降序 → `title` 升序），客户端**不得再次排序**；
     * ② 组内曲目要用 `key` 回查 [Endpoints.media.list]：`by=album` → `?album=<key>`、
     *    `by=artist` → `?artist=<key>`、`by=folder` → `?dir=<key>`
     *    （**是 `dir` 不是 `folder`**，见 `mediaShared.MediaFilter`）。
     *    `album` / `artist` 中无标签的曲目会聚成一个 `key` 为空串的分组
     *    （`title` 是「未知专辑」/「未知歌手」）；空 `key` 送回 `/list` 会被当作「未传」
     *    而返回整库，因此该分组只能用于展示，不能作为筛选条件；
     * ③ 封面走 `GET [Endpoints.media.thumbnail]?type=audio&id=<cover_id>`；`cover_id <= 0`
     *    表示组内没有可用条目（`_ID` 从 1 开始，因此 0 天然表示「没有」），此时
     *    **不得发起缩略图请求** —— 与 [Endpoints.playlists] 的 `cover_id` 同一约定。
     */
    groups: '/api/media/groups',
    /**
     * 视频元信息（ffmpeg 探测）。query: id。
     * MediaStore 列在本机经常缺 duration/width/height（codec 栈缺失），
     * core 用 ffmpeg 自主探测真值；结果永久缓存（媒体文件不可变）。
     * 404 = 无法解析。source: cache|ffmpeg。
     */
    videoInfo: '/api/media/video-info',
    browse: '/api/media/browse',
    thumbnail: '/api/media/thumbnail',
    // 单条重抽封面用（type+id 查询参数）；不带 id 表示整类或全部清除
    thumbnailCache: '/api/media/thumbnail-cache',
    // 手动触发一轮封面预热（POST；开关未开启时 core 返回 409）
    thumbnailPrewarm: '/api/media/thumbnail-prewarm',
    cover: '/api/media/cover',
    lyrics: '/api/media/lyrics',
    tags: '/api/media/tags',
    config: '/api/media/config',
    rescan: '/api/media/rescan',
    /**
     * 某个视频可用的**外挂字幕列表**。
     *
     * 必须由 core 列举的原因：`.srt` 不是视频，**永远不会出现在 MediaStore 的视频集合里**，
     * 因此 `/list` 与 `/browse` 都发现不了它；而「哪个字幕属于哪个视频」的匹配规则
     * （同名、`movie.zh.srt`、`movie - 中文.srt` 等）若放在客户端，就得在每个播放入口各写一遍。
     *
     * query：`path`（视频真实路径）、`scope`
     *  · `matched`（默认）：只返回按文件名判定属于该视频的字幕，用于打开时自动挂载；
     *  · `folder`：同目录**所有**字幕，用于手动选字幕（实际使用中字幕名与视频名经常不一致）。
     *
     * 注意：**`supported` 不等于「客户端能播放」**。它的语义是「core 能转码并给出字幕 MIME」，
     * 对齐的是 **app 端 media3** 的解析能力。浏览器 `<track>` **只支持 WebVTT** ——
     * srt 依赖播放器内部转换、ass/ssa 需要额外渲染插件、ttml 没有通用方案。
     * 因此 web 侧另有一份自己的能力映射（`composables/subtitleFormat.ts`），
     * 两份**刻意保持独立**，不得为了「统一」而合并 —— 合并会导致至少一端给出错误结论。
     *
     * `supported: false` 的条目**仍然返回**（MicroDVD `.sub`、SAMI `.smi`）：
     * 让客户端显示「格式不支持」，而不是让用户面对目录中确实存在的文件怀疑程序漏扫。
     */
    subtitles: '/api/media/subtitles',
    /**
     * 字幕文件内容，**已由 core 统一转成 UTF-8**（响应头显式带 `charset=utf-8`）。
     *
     * 不直接拉取字节流的原因：字幕解析器按 UTF-8 解码，而中文字幕大量是 GB18030/Big5，
     * 直接使用原始字节会得到乱码，且播放器不提供「更换编码重试」的入口。
     * core 把探测与转码收在服务端（BOM → **严格** UTF-8 → GB18030 → Big5 → Latin-1 兜底），
     * 客户端只会见到 UTF-8。**这正是本端点存在的原因。**
     *
     * query：`path`（字幕真实路径）。后缀无法识别返回 **415**，读取失败返回 500。
     */
    subtitle: '/api/media/subtitle',
    /**
     * 排除名单：「从音乐库移除」的路径清单。**只对 `type=audio` 生效。**
     *
     * `GET {excluded}` → `{ paths, total, max, full }`。`max` **不是产品层面的取舍**：
     * 名单会变成 `/list` 查询中的 `_data NOT IN (?,…)`，每条占用一个 SQLite 绑定变量（上限 999）。
     * `full` 是 core 计算好的 `total >= max`，客户端据此**提前**提示，避免用户操作后才发现未生效。
     *
     * `POST {exclude}` body `{ paths: string[], action: 'add' | 'remove' | 'clear' }`
     * → `{ success, action, affected, requested, total, full }`。
     * **`affected < requested` 是客户端发现「只生效了一部分」的唯一途径**（达到上限被截断）。
     *
     * 本端点与删文件刻意分为两条接口，不得合并成带 mode 的一条：本端点只改名单、**随时可撤销**；
     * 删文件是 `POST /api/files/delete`，不可逆。合并后「可撤销」这一性质在调用点上就消失了。
     *
     * 被排除的路径**从 `/list` 的结果中直接消失**，因此客户端拿不到它们的元数据 ——
     * 管理界面只能按路径显示文件名。写操作成功后 core 失效 `media:*` 缓存并推送
     * `media:playlists`（复用该 topic，目前没有独立 topic）。
     */
    excluded: '/api/media/excluded',
    exclude: '/api/media/exclude',
  },
  /**
   * 音频歌单（core 侧 `PlaylistRoutes`）。
   * web 侧界面是音乐页里的歌单面板（`views/media/components/PlaylistPanel.vue`）。
   *
   * 四条必须遵守的约定：
   * ① **曲目以真实路径为标识**，不是 MediaStore 的 id —— id 在重扫后会变，导致歌单整份失效。
   *    所有加歌 / 移出 / 重排的 body 都是 `{ paths: string[] }`；
   * ② `GET {root}/:id/items` 返回的 item 与 media.list 的 `items[]` **形状完全一致**，
   *    额外多一个 `missing` 布尔。`missing: true` 的条目 `id` 为 0（媒体库中查不到：
   *    文件被删 / 存储卡未插入 / 尚未被扫描），必须渲染「已失效」占位并禁止播放，
   *    **不得自动移出** —— 存储卡短暂拔出就清空歌单是不可接受的；
   * ③ `items` 的顺序就是播放顺序，由用户决定。前端不得再按时间 / 名称排序；
   * ④ 移出用 DELETE。body 与 `?path=` 都支持（部分代理会丢弃 DELETE 的 body），
   *    axios 侧走 `{ data: { paths } }`。
   *
   * 失败码全部复用通用码：歌单不存在 404 `NOT_FOUND`、名称空 400 `BLANK_VALUE`、
   * 同名 409 `ALREADY_EXISTS`、数量到顶 400 `OUT_OF_RANGE`、无媒体权限 403 `FORBIDDEN`。
   */
  playlists: {
    root: '/api/playlists',
    /** 某个歌单的曲目集合：GET 取、POST 加、DELETE 移出、PUT 整表重排。 */
    items: (id: string) => `/api/playlists/${id}/items`,
    /** 单个歌单本体：GET 取元信息、PUT 重命名、DELETE 删除。 */
    one: (id: string) => `/api/playlists/${id}`,
  },
  /**
   * SIM 卡。
   * 本组只有 switch 一条：core 原先的「SIM 信息」查询端点与 dashboard summary 的
   * device_info.identity 同源，却多一层 15 分钟缓存、phone_type 恒为 GSM、sim_state 更粗糙，
   * 两端均不应使用，该端点已在 core 侧删除。
   * PIN/PUK 状态查询端点同样已在 core 侧删除，web 不再展示 PIN 信息。
   */
  sim: {
    switch: '/api/sim/switch',
  },
  /**
   * 后端 APK 自更新与前端 ZIP 更新是**两套独立通道**：
   * update 组管理 core APK，web 组管理 web 资源，各有独立的 status/state 机。
   *
   * 注意：本文件中描述端点时不得用反引号包裹 api 路径通配写法（形如 update 斜杠星号）：
   * 契约校验器会把带引号的 api 路径字面量视为「客户端声明了该端点」，通配路径必然报 P0。
   *
   * 三条约束：① update 组的失败信封是真实 HTTP 码 + `{success,ok,error,message,code}`，
   * 而 web 组只返回 `{error}`；② 安装 core 时进程会自杀重启，轮询失败属预期，不得当作错误；
   * ③ **check 不是只读检查**，而是「检查 + 下载 + 校验 + 安装 + 重启」的完整流程，
   * 调用即开始安装。需要「先确认有无新版本」时必须请求 backendInfo（只读清单 + 版本比对，
   * 不触碰 core 的更新状态机），确认有新版并取得用户二次确认后才允许调用 check。
   *
   * frontendInfo 是「手机 App 安装包」信息（读清单，缓存 5 分钟）；core 另有一个带鉴权的
   * APK 代理下载端点，web 无法使用（`<a download>` 无法携带 Bearer 头），因此 web 直接使用
   * 清单中的 `apk_url` 上游直链。
   */
  update: {
    check: '/api/update/check',
    status: '/api/update/status',
    upload: '/api/update/upload',
    installLocal: '/api/update/install-local',
    reset: '/api/update/reset',
    frontendInfo: '/api/update/frontend-info',
    /** 只检查 core 自身版本：{ current_version, latest_version, has_update, changelog, apk_url, apk_size, sha256 } */
    backendInfo: '/api/update/backend-info',
    /**
     * 更新源决策快照（只读、无入参）：`{ mode, country, use_mirror, mirror_prefixes }`。
     * 供 app 使用（其 APK 自更新不经 core 代理，只取决策自行拼接 URL）；web 目前不需要它，
     * 因为 web 不下载任何内容 —— 登记在此是为了保持契约完整，避免被当作未登记端点。
     */
    source: '/api/update/source',
  },
  webAssets: {
    check: '/api/web/check',
    status: '/api/web/status',
    version: '/api/web/version',
    update: '/api/web/update',
    rollback: '/api/web/rollback',
    clear: '/api/web/clear',
  },
  /**
   * 可选二进制组件（frpc / cloudflared）。
   *
   * ## 登记原因
   * 该组端点此前已在调用点（`composables/useComponentInstaller.ts`）以裸字面量使用。
   * 「资源中心」是**第二个**消费者，两个调用点各写一遍字面量必然出现分歧
   * （尤其 `/{id}/install` 这类带参路径），因此统一登记在本组。
   * 既有调用点尚未改为引用本组，新增调用点必须引用本组。
   *
   * ## 三条必须遵守的约定
   * ① 进度是**真百分比**：`GET /api/components/status` 的 `percent` 是 0-100 的实数；
   *    并且 `ComponentManager.State` 比 `WebUpdateManager.State` **多一档 `EXTRACTING`**
   *    （裸 tar.gz 需要先解包），前端词表见 `updateShared.ts` 的 `COMPONENT_STATE_META`。
   * ② 上传走 **raw body**（`application/octet-stream`），不是 multipart —— cloudflared
   *    裸二进制约 36MB，multipart 方案需要先在内存中完整缓存，低端设备会 OOM。
   * ③ 上传上限在**列表端点**的 `max_upload_bytes`（core 侧 96MB），**不在** status 端点中；
   *    前端据此在选文件时即拦截超限的包（413 需要等 body 全部推送完才能读到，几十 MB 上行会被浪费）。
   */
  components: {
    /** `{ components: [...], max_upload_bytes, id, state, percent, message, manifest_error, ... }` */
    list: '/api/components',
    /** 只要进度那几档（`{ id, state, percent, message, ... }`），比列表端点轻 */
    status: '/api/components/status',
    /** POST，异步受理：200 = 已开始下载，400 = 拒绝（未知组件 / 已有任务在跑） */
    install: (id: string) => `/api/components/${encodeURIComponent(id)}/install`,
    /** POST，同步：409 = 还有实例在跑 */
    uninstall: (id: string) => `/api/components/${encodeURIComponent(id)}/uninstall`,
    /** POST，raw body 同步安装，200 回 `{ success, version }` */
    upload: (id: string) => `/api/components/${encodeURIComponent(id)}/upload`,
  },
  /**
   * 第三方程序商店（core `ProgramRoutes`）。
   *
   * 与上面的 `components` 不是同一类：components 是 frpc/cloudflared 单文件组件，
   * programs 是带 data/conf/run/state 的第三方 Linux 程序（OpenList 等）。
   *
   * 注意单槽运行时：同一时刻最多只有一个程序在运行。start/stop/restart/open 都会校验
   * 目标 id 与 currentId；UI 侧也必须按「一个活动程序」呈现，不得把列表中的每一行都
   * 当作可独立启动。
   */
  programs: {
    /** `{ programs, count, native_exec, total_bytes }` */
    list: '/api/programs',
    /** 全局安装进度：`{ id, phase, downloaded_bytes, total_bytes, candidate_index, message }` */
    status: '/api/programs/status',
    /** 内容目录可选落点：`{ volumes: [{ id, label, path, removable, free_bytes }] }` */
    volumes: '/api/programs/volumes',
    detail: (id: string) => `/api/programs/${encodeURIComponent(id)}`,
    /** POST，标准鉴权；200 accepted，409 = 已有安装任务/程序在运行 */
    install: (id: string) => `/api/programs/${encodeURIComponent(id)}/install`,
    upload: (id: string) => `/api/programs/${encodeURIComponent(id)}/upload`,
    uninstall: (id: string) => `/api/programs/${encodeURIComponent(id)}/uninstall`,
    start: (id: string) => `/api/programs/${encodeURIComponent(id)}/start`,
    stop: (id: string) => `/api/programs/${encodeURIComponent(id)}/stop`,
    restart: (id: string) => `/api/programs/${encodeURIComponent(id)}/restart`,
    accessMode: (id: string) => `/api/programs/${encodeURIComponent(id)}/access-mode`,
    /** PATCH；设置弹窗：`access_mode` / `internal_port` / `external_port` / `autostart`。运行中改端口会被 409 拒绝 */
    settings: (id: string) => `/api/programs/${encodeURIComponent(id)}/settings`,
    open: (id: string) => `/api/programs/${encodeURIComponent(id)}/open`,
    /** POST；返回一次性初始口令并消费 */
    secret: (id: string) => `/api/programs/${encodeURIComponent(id)}/secret`,
    /** POST；停止后重置管理员口令（旧数据/丢失口令） */
    resetSecret: (id: string) => `/api/programs/${encodeURIComponent(id)}/reset-secret`,
    /** GET；程序 stdout/stderr 尾部（可能含 token） */
    logs: (id: string) => `/api/programs/${encodeURIComponent(id)}/logs`,
  },
  /**
   * 配置备份与恢复（Kotlin 侧见同名 Endpoints.Backup）。
   *
   * 四个端点都在鉴权块内。三条约束：
   * ① 明文导出（`encrypted: false`）**必须**带 `acknowledge_plaintext: true`，否则返回 400 ——
   *    备份包含设备后台密码与隧道凭据，服务端不接受静默导出。调用前必须已向用户
   *    展示风险（本页的明文确认弹窗即该展示）；
   * ② preview / import 的 body 是备份包二进制，口令走 `X-Backup-Passphrase` 请求头
   *    （不得放入 query：query 会进入访问日志与浏览器历史）；
   * ③ 导出响应是二进制附件，axios 需带 `responseType: 'blob'`；错误响应体同样是 Blob，
   *    读取错误文案前需先 `.text()`（见 BackupPanel 的 readBlobError）。
   */
  backup: {
    info: '/api/backup/info',
    export: '/api/backup/export',
    preview: '/api/backup/preview',
    import: '/api/backup/import',
  },
  /**
   * 终端命令历史。
   *
   * **只读写历史、不执行命令** —— 真源是设备端实际执行过的命令，由 shell / AT 路由
   * 在执行后写入。import 用于把升级前的本地历史一次性导入，客户端必须保证只调用一次
   * （服务端不去重，重复调用只会在列表中产生重复行）。
   *
   * 分页是 keyset 游标：`limit` / `cursor_ts` / `cursor_id`，响应返回
   * `next_cursor_ts` / `next_cursor_id` / `has_more`。
   */
  console: {
    history: '/api/console/history',
    historyImport: '/api/console/history/import',
    historyRecord: (id: number) => `/api/console/history/${id}`,
  },
  dashboard: {
    summary: '/api/dashboard/summary',
  },
  /**
   * 测速。`relay`：Web 外网测速经 core 白名单转发（节点无 CORS，浏览器不能直连）。
   * App 直连外网节点，不走 relay。
   */
  speedtest: {
    root: '/api/speedtest',
    upload: '/api/speedtest/upload',
    relay: '/api/speedtest/relay',
    /** POST：手动触发一轮定时测速（执行 + 入库 + WS 广播 + 通知） */
    run: '/api/speedtest/run',
    /** GET ?limit=：定时测速历史（新的在前） */
    history: '/api/speedtest/history',
  },
  /** 网络诊断工具集：ping/DNS/TCP/TTL traceroute，全部无需 root */
  diagnostic: {
    /** GET；app / web 共用的默认目标，不各自硬编码 */
    presets: '/api/diagnostic/presets',
    ping: '/api/diagnostic/ping',
    dns: '/api/diagnostic/dns',
    tcp: '/api/diagnostic/tcp',
    traceroute: '/api/diagnostic/traceroute',
  },
  /**
   * 诊断中心（`/diagnostics`）使用的**只读探针**端点。
   *
   * ## 不并入 [diagnostic] 组的原因
   * 那一组是「网络诊断工具集」：四条 POST、必须传入 host、执行的是 ping 与 Java socket，
   * 语义是对某个**外部目标**做一次主动探测。本组是读取**设备自身**的状态：
   * 全部为 GET、无入参、与目标地址无关。两者的入参形状、失败模式、调用方都不同，
   * 合并成一组会让「该条是否需要 host」变成必须查看实现才能确定的隐式问题。
   *
   * ## 只登记七条的原因
   * `monitor/storage`、`media/status`、`service/status`、`diagnose` 已在各自的组中，
   * 诊断中心**直接引用那些既有条目**，不在此重复登记 —— 同一路径存在两处出处必然出现分歧。
   * 本组只收此前 web 侧未使用、或仍以裸字面量使用的端点
   * （裸字面量的先例见 `views/network/components/modals/DiagnosticModal.vue`；
   * 这种写法在契约校验器看来与未登记等价）。
   */
  diagnostics: {
    /**
     * CPU 控制能力探测（core `CpuProbeRoutes`）：一条 GET 跑 20+ 条 shell，5~15s。
     * 回 `{ ok, ts, kernel, board, cores_declared, shell_uid, policies[], online_nodes[], thermal_zones[] }`。
     *
     * 两条必须知道的：
     * ① 全部命令**只读或写回原值**（试写 scaling_max_freq 用当前值写回，online 节点只写 1）
     *    —— 它不会改设备状态，可以放心在诊断页里点；
     * ② 失败**也是 HTTP 200**，形状变成 `{ ok: false, error }`，所以不能按 HTTP 码判成败。
     */
    cpuProbe: '/api/cpu-probe',
    /**
     * 特权通道探测：`{ hasRoot }`。
     *
     * 判据是 `ShellExecutor.hasRootAccess()`，即「ADB 特权通道是否可用」——
     * 底层 ADB shell 是 **uid 2000，不是 uid=0**。它与 `shell/root` 的 `uid` 输出、
     * 与 `diagnose` 的 `root`（`executeAsRoot("id")` 里含 uid=0）**是三个不同的探针**，
     * 实测会出现「通道可用但 shell 不是 root」，界面上必须分开说。
     */
    rootCheck: '/api/system/root-check',
    /**
     * SELinux 状态：`{ selinux }`，值是 `getenforce` 原文（Enforcing / Permissive / Unknown）。
     *
     * ⚠ 与 `service/status` 的 `native_exec.selinux` **不是同一个探针**：那个说的是
     * "打包的原生二进制能不能 exec"（enforcing 设备上恒为 false），这个说的是系统策略模式。
     */
    selinux: '/api/device/selinux',
    /**
     * 名字叫 magisk，**实现已经不是 Magisk 检测**：core 的 `SystemController.getMagiskStatus()`
     * 现在只是 `getPrivilegedShellStatus()` 的兼容别名，回 `{ adb_shell, method, uid }`。
     * 路径保留是因为 app 与 API 手册都按它引用，改名只会破坏跨端契约。
     */
    magisk: '/api/device/magisk',
    /**
     * 上一次 core 崩溃：`{ success, ok, crashed, timestamp, summary, file, age_ms, recent, uptime_ms }`。
     *
     * `crashed` 是 `timestamp > 0` 的派生值，**没有 ack 接口**（app 与 web 是两个独立展示端，
     * 任一端先 ack 都会导致另一端永远看不到）—— 去重由各端存入本地存储。
     *
     * `recent` 是 core 用**自身设备时钟**计算出的 7 天新鲜度窗口（`age_ms` 是原始年龄，
     * -1 = 从未崩溃）。`crashed` 对三个月前的记录也恒为 true，两端只在 `recent` 为真时弹窗；
     * 旧 core 不返回这两个字段，此时按「新鲜」处理以保持兼容。
     */
    serviceCrash: '/api/service/crash',
    /**
     * QoS 诊断快照：`{ enabled, shell_qos, goform_qos, cpu_temp, dynamic_pool }`。
     *
     * ⚠ `cpu_temp` 是**毫摄氏度原始整数**，读不到时是 `0`（不是 null）——
     * 直接当摄氏度显示会得到「0°C」这种假读数，要先判 `<= 0`。
     */
    qosStatus: '/api/qos/status',
    /**
     * 落盘的日志**文件清单**：`{ files: [{ name, size, modified }], total, total_bytes, dir }`，
     * `name` 是相对路径（形如 `YYYY-MM-DD/app.log`，日志按日期分目录）。
     *
     * 注意：与下面的 [debugLogs] **内存缓冲**是两回事 —— 后者返回 `{ logs, total }` 且带
     * `limit`/`level`。本页只需文件名与大小（判断日志是否在落盘），读取内容属于设置页的日志面板。
     */
    debugLogFiles: '/api/debug-logs/files',
    /**
     * 内存中的调试日志缓冲：`{ logs: string[], total }`，`logs` 是**字符串数组**
     * （格式 `HH:mm:ss.SSS [LEVEL] [TAG] message`，不是对象数组），支持 `limit` / `level`。
     *
     * 登记原因：诊断包（导出 `.txt`）需要附带最近一段日志，诊断中心是该端点的第二个消费者。
     * 设置页 `LogsPanel` 目前仍以裸字面量读取同一端点（既有漂移），该调用点不在本组范围内；
     * 新增调用点必须引用本组。
     */
    debugLogs: '/api/debug-logs',
    /**
     * 设备数据分区占用（`StatFs("/data")`）：`{ total, available, used, usage_percent }`，
     * `usage_percent` 是 0-100 的浮点数；**读取抛异常时返回空对象 `{}`**（不是 null、不是错误码）。
     *
     * 登记原因与 [debugLogFiles] 相同：web 侧此前只有「关于」面板的
     * `SYSTEM_ENDPOINTS.storage` 一个裸字面量在使用，诊断中心是该端点的第二个消费者。
     * **同一路径存在两处出处必然出现分歧**，因此收进本组；「关于」面板的调用点仍以裸字面量读取，
     * 属既有不一致。
     *
     * 与 [monitor.storage]（监控数据库各表占用）**不是同一概念**：后者回答「自身数据占用了多少」，
     * 本端点回答「设备是否还有可用空间」。
     */
    diskUsage: '/api/system/storage',
    /**
     * 服务端响应缓存统计：`{ count, max_entries, any_cache_count, total_bytes_estimate, stale, entries[] }`。
     *
     * 登记原因同 [diskUsage]：设置页 `PerformancePanel`（真源，含清空 / 按模式失效两个
     * **写入**动作）一直以裸字面量读取该端点，诊断中心需要读取同一份数据生成**只读**健康摘要。
     *
     * 两个字段需要特别注意，它们只在诊断页展示（core 侧确实会返回，见 `ResponseCache.getStats`）：
     *  · `stale` —— 缓存被标记为「可能过期」（WS 客户端全部断开、内部刷新失败）；
     *  · `expired_count` —— 已过期但**惰性删除尚未触发**的条目数，健康状态下应远小于 `count`。
     *    它不出现在 core 手册的响应示例中，但 `ResponseCache.kt` 会返回该键 ——
     *    不得按手册的字段表写死。
     */
    cacheStats: '/api/cache/stats',
    /**
     * Core **自身**的权限自检（core `PermissionRoutes`）。
     *
     * ## 存在原因
     * 「core 需要哪些权限」的真源是 core 自身的 AndroidManifest，而实际执行授予的只有
     * 安装脚本（`scripts/UFI-AXIS-Core-install-*`）与 Android 安装器，**升级 core 时都不会重跑**。
     * 因此新版本新增的权限无人补充：界面存在、点击无效、日志中也没有「权限不足」记录。
     * 客户端无法自行推导（既看不到 manifest，也读不到 appop 模式），判据必须由 core 现场查询：
     * `requestedPermissions`（需要哪些）+ `checkPermission`（runtime 是否授予）+
     * `AppOpsManager`（appop 是否放行）。**不是**对「能否使用」的间接推测。
     *
     * ## 响应
     * `{ package, sdk_int, uid, shell_method, shell_privileged, requested_count, granted_count,
     *    missing_count, missing[], blocked[], not_applicable[], granted[], checked_at }`。
     *
     * 三条必须理解：
     * ① `missing[]` 与 `blocked[]` **不是同一类**：`missing` 是「缺失且 shell 可补」（每条带
     *    `fixable: true` 与原文 `fix_command`），`blocked` 是「缺失但 shell 无法补」（签名级权限，
     *    只有以系统应用方式安装才能获得）—— 界面必须分开表述，否则用户会点击一个必然失败的按钮；
     * ② `not_applicable[]` 是**本机不需要**的一类（`kind` 为 `superseded` / `unknown`）：
     *    典型是 `WRITE_EXTERNAL_STORAGE`（Android 11 起对 targetSdk≥30 不再授予）与
     *    `READ_EXTERNAL_STORAGE`（Android 13 起被 `READ_MEDIA_*` 取代）。**既不算缺失，也不算
     *    不可补** —— 报成缺失会让用户去补一个补了也无用的权限（core 侧按
     *    `SUPERSEDED_MAX_SDK` 排除，与安装脚本的 maxSdk 同口径）；
     * ③ `shell_privileged` 是「能否补全」的前提：`pm grant` 要求 uid 0/2000（core 通过内置 adb
     *    自连接），通道不可用时本端点会**逐条失败**而不是伪装成功。为 false 时界面应提示
     *    「先修复 ADB 通道」，而不是提供补全按钮。
     *
     * 注意：旧 core 上该端点是 **404**（不是 500）。客户端必须把 404 表述为「该 core 尚无自检端点」，
     * 并给出可手工执行的兜底命令（见 `diagnosticRegistry.ts` 的 `core-permission`）。
     */
    corePermissions: '/api/system/permissions',
    /**
     * 补全 core 缺失的权限（**写动作**）。
     *
     * body 可为空；`{ permissions: ['READ_MEDIA_AUDIO', …] }` 只补指定条目（短名与全名均支持），
     * 不传则补全所有「缺失且可补」的权限。返回 `{ success, attempted, granted, failed, results[],
     * auto_revoke, permissions }` —— `permissions` 是**补全后重新探测**的完整快照
     * （表达的是实际状态，而不是「已发送过命令」）。
     *
     * ## 写动作，调用方必须二次确认
     * 本端点会修改设备状态（`pm grant` / `appops set … allow`），且**无法通过本端点撤销**
     * （没有 revoke 端点 —— 撤销 core 自身的权限只会导致功能失效）。回退只能使用原文命令：
     * runtime 走 `pm revoke <pkg> <perm>`、appop 走 `appops set <pkg> <OP> default`。
     *
     * 本端点还会把 `AUTO_REVOKE_PERMISSIONS_IF_UNUSED` 设为 `ignore`（避免补全后一段时间
     * 又被系统回收），结果在 `auto_revoke` 中返回；该步骤失败**不计为整体失败**。
     */
    corePermissionsGrant: '/api/system/permissions/grant',
  },
  /**
   * 赞助感谢名单（`/sponsors`）。
   *
   * 返回 `{ source: 'bundled' | 'remote', updatedAt, sponsors: [...] }`。
   * **消费方必须假定该数据可能无效**：core 侧数据来自 12 小时一次的后台刷新
   * （从仓库 main 分支拉取 sponsors.json），内容不经过编译期校验，
   * 因此 web 与 core 都必须按 `sponsors.json` 的字段规则逐条校验，
   * **不合规时整份丢弃**（保留上一层：远端无效则回退内置版本，内置无效则整块不渲染）。
   *
   * 字段规则（与仓库根 `sponsors.json` 的 `_note` 同源）：
   * 每条**恰好**包含 `name`（非空、trim 后 ≤32）或 `anonymous: true` 之一；
   * `at` 必填且形如 `YYYY-MM` 或 `YYYY-MM-DD`；`version` 必须等于支持值。
   *
   * 数据分为**保底 + 更新**两层：页面先用构建期打入 bundle 的版本渲染（0 延迟、不白屏），
   * 取得本端点且校验通过后才替换。core 不可达时停留在保底版本 —— 这正是「保底」的含义。
   */
  sponsors: {
    root: '/api/sponsors',
  },
  /** 套餐档案（用量到量与到期融合）：core 侧语义层，goform 无套餐概念 */
  plan: {
    read: '/api/device/plan',
    write: '/api/device/plan',
  },
  diagnose: '/api/diagnose',
  wsRealtime: '/ws/realtime',
  /**
   * 存活探测（**免鉴权**，不在 /api 鉴权块内）：`{ status: 'ok', timestamp, ws_* , cache_stale }`。
   * 用途：core 自更新会重启进程，用它判断「重启后是否已就绪」——此期间连接失败属预期。
   */
  health: '/health',
} as const;

/**
 * 负面清单：**core 从不存在的端点**，任何一端都禁止声明。
 * 详见 `docs/UFI-AXIS-Core-API-Reference.md` 末尾「文档幻影端点」小节。
 *
 * 这里故意**不写完整路径**（不带 api 前缀）：契约校验器会把源码里的 api 路径
 * 字面量当成"客户端声明了该端点"，负面清单自己写全路径反而会被报成 P0。
 */
export const PHANTOM_ENDPOINT_SUFFIXES = [
  'adb/status',
  'adb/ping',
  'adb/auto-start',
  'adb/start',
  'adb/stop',
  'device/usb-mode',
] as const;

/** 定时任务 / 自动化规则动作类型（权威来源：core ActionExecutor.VALID_ACTION_TYPES）。 */
export const ActionType = {
  DATA_TOGGLE: 'data_toggle',
  WIFI_TOGGLE: 'wifi_toggle',
  AIRPLANE_TOGGLE: 'airplane_toggle',
  REBOOT: 'reboot',
  SHUTDOWN: 'shutdown',
  LED_TOGGLE: 'led_toggle',
  PERFORMANCE_MODE: 'performance_mode',
  ROAMING_TOGGLE: 'roaming_toggle',
  NETWORK_MODE: 'network_mode',
  CUSTOM_SHELL: 'custom_shell',
} as const;

export const ACTION_TYPES_ALL: string[] = Object.values(ActionType);

/**
 * WebSocket 频道。
 * 订阅报文 `{ subscribe: [...] }` 是**替换语义**；core 按频道严格过滤，没订阅就收不到。
 * 最大连接数 4（App + Web 共享），超限以 1013 关闭。
 */
export const WsChannel = {
  TRAFFIC: 'traffic',
  SIGNAL: 'signal',
  /**
   * 实时连接态（`ppp_status`），与 `SIGNAL` 同频推送，不额外请求设备。
   * 只表示链路态；移动数据开关是用户意图（`mobile_data`），不得混用。
   */
  CONNECTION: 'connection',
  CPU: 'cpu',
  MEMORY: 'memory',
  ALERT: 'alert',
  NOTIFICATION: 'notification',
  DATA_CHANGED: 'data_changed',
  CONFIG_CHANGED: 'config_changed',
  UPDATE: 'update',
} as const;

export const WS_CHANNELS_ALL: string[] = Object.values(WsChannel);

/**
 * `data_changed` 帧里 `data.changed` 的取值表（对齐 Kotlin `WsDataTopic`）。
 * 注意这些**不是**频道名，频道只有 `data_changed` 一个。
 *
 * 消费端一律按完整 key 匹配（或按 `namespace:` 前缀分流）：`ResponseCache.invalidate`
 * 若把 `device:traffic-limit` 截成 `device`，两端的精准刷新会全部静默失效。
 * 新增取值必须同步 Kotlin 侧。
 */
export const WsDataTopic = {
  TASK_LIST: 'task:list',
  TASK_RULES: 'task:rules',
  CONSOLE_AT: 'console:at',
  CONSOLE_SHELL: 'console:shell',
  DEVICE_TRAFFIC_LIMIT: 'device:traffic-limit',
  /** 音频歌单集合变了（新建 / 重命名 / 删除 / 加歌 / 移出 / 重排）。 */
  MEDIA_PLAYLISTS: 'media:playlists',
  /**
   * 新设备批准请求有变化（core 在登记成功后主动推送，App 收到后即请求
   * `/api/pairing/approvals` 并弹窗）。载荷中**没有配对码**，只是一次唤醒。
   *
   * Web 端目前**不消费**该键（它是发出请求的一方）；在此登记仅为与 Kotlin
   * `WsDataTopic` 保持一致。
   */
  PAIRING_APPROVALS: 'pairing:approvals',
} as const;

/**
 * 设备能力（`GET /api/device/capabilities` 的 `capabilities` 数组取值）。
 *
 * ## Kotlin `Capability.wire` 的**手工镜像**
 * 单一出处是 `core/contract/src/main/kotlin/com/ufi_axis_core/contract/Capabilities.kt`，
 * 守门测试 `CapabilityWireTest.kt` 把那 11 个字符串逐个写死。两端镜像的一致性测试
 * 在 core 侧尚未建立（见该测试的类注释），因此这里没有编译期约束 ——
 * 修改任何一个字符串都必须**同时**修改 Kotlin 侧，否则 web 会把「设备不支持」理解成
 * 「未知能力」（反之亦然），而两者的界面行为正好相反。
 *
 * ## 使用字符串常量而非收窄联合类型做运行时判断的原因
 * 与服务端保持同一形状：服务端返回的就是 `string[]`。`CapabilityWire` 联合类型只用于标注
 * **我们主动查询的能力**；服务端多下发的未知 wire 必须能被安静忽略
 * （旧客户端遇到新能力的正确行为是「视其不存在」，而不是在类型断言上崩溃）。
 */
export const Capability = {
  SMS: 'sms',
  SIM_SLOT_SWITCH: 'sim_slot_switch',
  BAND_LOCK: 'band_lock',
  CELL_LOCK: 'cell_lock',
  NETWORK_MODE: 'network_mode',
  SAMBA: 'samba',
  USB_DEBUG: 'usb_debug',
  FOTA: 'fota',
  PERFORMANCE_MODE: 'performance_mode',
  TRAFFIC_LIMIT: 'traffic_limit',
  BATTERY: 'battery',
} as const;

export type CapabilityWire = (typeof Capability)[keyof typeof Capability];

/**
 * 网络模式：与 Kotlin `NetworkMode` 对齐。
 *
 * 两个取值域必须分清：
 * - **别名集**（客户端提交给 `POST /api/network/mode` 与定时任务 `network_mode.mode`，大小写不敏感）；
 * - **BearerPreference**（设备实际取值，大小写敏感，如 `Only_5G`）—— 只出现在
 *   `GET /api/device/settings` 的回读与 `POST /api/network/mode` 响应的 `bearer` 字段中。
 * 客户端**不得**直接提交 BearerPreference。
 */
export const NetworkMode = {
  AUTO: 'AUTO',
  ONLY_5G: '5G_ONLY',
  LTE_AND_5G: 'LTE_AND_5G',
  ONLY_LTE: 'ONLY_LTE',
  WCDMA_AND_LTE: 'WCDMA_AND_LTE',
  ONLY_WCDMA: 'WCDMA_ONLY',
} as const;

/** UI 档位（顺序与中文名对齐 Kotlin `NetworkMode.UI_OPTIONS` / `LABELS`）。 */
export const NetworkModeOptions: ReadonlyArray<{ label: string; value: string }> = [
  { label: '5G/4G/3G', value: NetworkMode.AUTO },
  { label: '5G NSA', value: NetworkMode.LTE_AND_5G },
  { label: '5G SA', value: NetworkMode.ONLY_5G },
  { label: '4G/3G', value: NetworkMode.WCDMA_AND_LTE },
  { label: '仅4G', value: NetworkMode.ONLY_LTE },
  { label: '仅3G', value: NetworkMode.ONLY_WCDMA },
];

/**
 * 设备回读的 BearerPreference / net_select → 别名。
 *
 * 与 Kotlin `NetworkMode.fromBearer` 同步：真机/老固件会回 `NR5G_ONLY`、`LTE_NR5G`、
 * `WCDMA_AND_LTE_AND_5G`、小写 `only_5g` 等写法，漏映射会让切换回读永远对不上目标档位。
 * **查表统一走 [bearerToNetworkMode]**（先转大写），不得直接下标取值。
 */
export const BearerToNetworkMode: Record<string, string> = {
  WL_AND_5G: NetworkMode.AUTO,
  AUTO: NetworkMode.AUTO,
  // 真机默认档位（F50）：语义等于 5G/4G/3G 自动
  WCDMA_AND_LTE_AND_5G: NetworkMode.AUTO,
  ONLY_5G: NetworkMode.ONLY_5G,
  NR5G_ONLY: NetworkMode.ONLY_5G,
  ONLY_NR5G: NetworkMode.ONLY_5G,
  '5G_ONLY': NetworkMode.ONLY_5G,
  LTE_AND_5G: NetworkMode.LTE_AND_5G,
  LTE_NR5G: NetworkMode.LTE_AND_5G,
  LTE_AND_NR5G: NetworkMode.LTE_AND_5G,
  NR5G_NSA: NetworkMode.LTE_AND_5G,
  '5G_NSA': NetworkMode.LTE_AND_5G,
  ONLY_LTE: NetworkMode.ONLY_LTE,
  LTE_ONLY: NetworkMode.ONLY_LTE,
  WCDMA_AND_LTE: NetworkMode.WCDMA_AND_LTE,
  LTE_WCDMA: NetworkMode.WCDMA_AND_LTE,
  ONLY_WCDMA: NetworkMode.ONLY_WCDMA,
  WCDMA_ONLY: NetworkMode.ONLY_WCDMA,
};

/**
 * 设备回读值 → 别名（大小写不敏感，与 Kotlin `fromBearer` 的 `uppercase()` 对齐）。
 *
 * 设备侧同一档位有多种大小写写法（`Only_5G` / `only_5g`），若在表中逐一列举，
 * 漏列一种就会静默落到「未知」。无法识别的取值**原样返回**，不伪装成「自动」。
 */
export function bearerToNetworkMode(raw: string): string {
  const key = String(raw ?? '').trim();
  if (!key) return '';
  return BearerToNetworkMode[key.toUpperCase()] || key;
}

/**
 * 切换制式后的「回读确认」预算，与 Kotlin `NetworkMode.SwitchProbe` 逐字对齐。
 *
 * 需要该预算的原因：`POST /api/network/mode` 返回成功只表示**固件已接受**该命令，设备还需
 * 重新注册网络，此期间 `GET /api/device/settings` 返回的**仍是旧档位**。
 * 写入后只回读一次就渲染，界面会停留在切换前的档位，直到其它位置偶然再次拉取设置
 * —— 表现为「切换已生效但界面仍显示旧制式」。
 *
 * 上限是硬要求：每次回读都会真实请求设备（core 写成功后清除 `device:settings` 缓存），
 * 无上限轮询会耗尽 goform 查询配额。
 */
export const NetworkModeSwitchProbe = {
  /** 下发成功后到第一次回读的等待（设备写入到查询接口可见约 600ms）。 */
  firstDelayMs: 600,
  /** 快档间隔：前 `fastAttempts` 次用它。 */
  intervalMs: 1500,
  /** 慢档间隔：设备重新注册期间降频。 */
  slowIntervalMs: 3000,
  /** 快档次数。 */
  fastAttempts: 5,
  /** 回读次数上限（**含**第一次）。13 次 ≈ 30.6s，覆盖真机十几到二十几秒的切换时间。 */
  maxAttempts: 13,
} as const;

/** 第 attemptNo 次回读之后等多久再读。与 Kotlin `SwitchProbe.intervalMsAfter` 同一判据。 */
export function modeProbeIntervalMs(attemptNo: number): number {
  return attemptNo < NetworkModeSwitchProbe.fastAttempts
    ? NetworkModeSwitchProbe.intervalMs
    : NetworkModeSwitchProbe.slowIntervalMs;
}

/**
 * 总时长上限，用于**超时文案**。与 Kotlin `SwitchProbe.TOTAL_BUDGET_MS` 同一算式。
 *
 * 确认器超时提示（`useApiOperation.ts` 的
 * `${title}未在预期时间内完成（已等待约 N 秒）…`）由该常量计算 N —— 文案中的秒数
 * 必须随预算变化，不得写死具体数字。
 */
export const NetworkModeSwitchBudgetMs = (() => {
  let total = NetworkModeSwitchProbe.firstDelayMs;
  for (let i = 1; i < NetworkModeSwitchProbe.maxAttempts; i += 1) total += modeProbeIntervalMs(i);
  return total;
})();

/**
 * 是否需要再次回读。与 Kotlin `SwitchProbe.shouldKeepProbing` 同一判据。
 *
 * @param attemptNo 刚刚完成的是第几次回读（从 1 开始）
 * @param reachedTarget 这次回读到的档位是否已等于目标档位
 *
 * 两个终止条件都是硬性的：读到目标即停止（成功），次数达到上限即停止（超时）。
 * 不得再叠加「按总时长判断」：两套上限并存时，谁先生效会变成偶发行为。
 */
export function shouldKeepProbingMode(attemptNo: number, reachedTarget: boolean): boolean {
  return !reachedTarget && attemptNo < NetworkModeSwitchProbe.maxAttempts;
}

// ────────────────────────────────────────────────────────────
// WiFi 安全模式 —— POST /api/wifi/config 的 auth_mode + encryp_type
// ────────────────────────────────────────────────────────────

/**
 * 界面上「加密方式」的一档 = 设备侧 **两个**参数（`auth_mode` + `encryp_type`）。
 *
 * 取值来自真机抓包。core 侧 `WifiRoutes.kt` 的 `/wifi/config` 只是把
 * `auth_mode` / `encryp_type` 原样透传给 goform（仅对 OPEN 特判），因此**配对关系由客户端负责**。
 *
 * 绑定为一档而不提供两个下拉的原因：这两个参数在设备侧必须配对下发，分开选择可能造出
 * 「WPA3PSK + NONE」这类设备接受却不生效的组合，等于制造一个无效开关。
 *
 * 注意字段名是 `encryp_type`（**不是** `encrypt_type`），与设备后台一致。
 */
export interface WifiSecurityPreset {
  /** 下拉显示名 */
  label: string;
  /** 下拉 value，同时就是要下发的 `auth_mode`（设备参数值即键，省掉一层映射） */
  value: string;
  /** 与 value 配对下发的 `encryp_type` */
  encrypType: string;
  /** false = 开放网络，提交时**不带** `passphrase` */
  needsPassphrase: boolean;
}

export const WifiSecurityPresets: ReadonlyArray<WifiSecurityPreset> = [
  { label: '开放（无密码）', value: 'OPEN', encrypType: 'NONE', needsPassphrase: false },
  { label: 'WPA2(AES)-PSK', value: 'WPA2PSK', encrypType: 'CCMP', needsPassphrase: true },
  { label: 'WPA3-PSK', value: 'WPA3PSK', encrypType: 'CCMP', needsPassphrase: true },
  { label: 'WPA2-PSK/WPA3-PSK', value: 'WPA2PSKWPA3PSK', encrypType: 'CCMP', needsPassphrase: true },
];

/** 读不到设备当前值时的兜底档位，与 core 缺省写的 `WPA2PSK` 一致。 */
export const WIFI_AUTH_MODE_DEFAULT = 'WPA2PSK';

/** 按 `auth_mode` 找预设。找不到 = 设备用了表外写法（老固件/别的型号），返回 undefined。 */
export function findWifiSecurityPreset(authMode: string | undefined | null): WifiSecurityPreset | undefined {
  const key = String(authMode ?? '').trim();
  if (!key) return undefined;
  return WifiSecurityPresets.find((p) => p.value === key);
}

/**
 * 该档是否需要密码输入框。
 *
 * 表外写法按「需要密码」处理：宁可多保留一个输入框，也不得把已加密网络
 * 当作开放网络提交（那会丢弃密码，导致所有已连接设备立即掉线）。
 */
export function wifiSecurityNeedsPassphrase(authMode: string | undefined | null): boolean {
  return findWifiSecurityPreset(authMode)?.needsPassphrase ?? true;
}

/**
 * 下拉选项 = 4 个标准档位 +（设备当前值若为表外写法则置顶并入）。
 *
 * 并入当前值是必须的：否则 select 对回读 `WPAPSK` 的设备显示空白，
 * 用户保存其余字段时会把加密方式一并改成其它档位。
 *
 * 返回**可变数组**：naive-ui 的 `n-select :options` 形参是 `SelectMixedOption[]`，
 * 传入 readonly 会在 vue-tsc 中报 TS4104。
 */
export function wifiSecurityOptions(currentAuthMode?: string | null): Array<{ label: string; value: string }> {
  const base = WifiSecurityPresets.map((p) => ({ label: p.label, value: p.value }));
  const cur = String(currentAuthMode ?? '').trim();
  if (cur && !findWifiSecurityPreset(cur)) {
    return [{ label: `${cur}（设备当前值，保持不变）`, value: cur }, ...base];
  }
  return base;
}

/**
 * `max_sta_num` 的合法闭区间 `[1, 10]`。
 *
 * 上限 10 对应**中兴 F50** 的规格，与 core 侧
 * `ZteGoformProfile.AP_MAX_STA_NUM_RANGE`（`validateApConfig` 用它拒绝 1..10 之外的值）对齐。
 * 导出是为了让两个 WiFi 表单的 `n-input-number :min/:max` 与本文件的提交守门共用同一份取值，
 * 而不是各写一个字面量。
 */
export const WifiMaxStaNumRange = { min: 1, max: 10 } as const;

/**
 * `max_sta_num` 是否可提交。
 *
 * `null` = 留空，语义是「不修改」（core 未收到该字段时不下发 ApMaxStationNumber）。
 * 其余必须是 [WifiMaxStaNumRange] 闭区间内的整数：越界值会被 core 侧 `validateApConfig`
 * 拒绝，在此提前拦截，用户才能看到具体原因而不是「保存失败」。
 *
 * 这一层是**必须**的：`n-input-number` 的 `:max` 只在用步进器时强制夹取，
 * 用户手工输入 `99` 仍会进入表单值。
 */
export function isWifiMaxStaNumAcceptable(v: number | null | undefined): boolean {
  if (v === null || v === undefined) return true;
  return Number.isInteger(v) && v >= WifiMaxStaNumRange.min && v <= WifiMaxStaNumRange.max;
}

/**
 * 「保持不变」档的文案。选项首项用它 —— 「不下发 `max_sta_num`」在界面上只有这一种说法。
 *
 * 与 app 侧 `WifiSettingsDialog.kt` 的 `optionLabel = { it?.toString() ?: "保持不变" }` 同字。
 */
export const WIFI_MAX_STA_NUM_KEEP_LABEL = '保持不变';

/**
 * 「保持不变」档在**选择器控件层**的取值（哨兵）。
 *
 * 为什么不是 `null`：naive-ui 的 `n-select` 在 `value === null` 时直接判「无选中」
 *（`_internal/select-menu/src/SelectOption.mjs` 的 `if (value === null) return false`
 * 与 `select/src/Select.mjs` 的 `selectedOptionRef`），那一档既不高亮也不打勾，
 * 只能靠 placeholder 假装 —— 而 app 侧同一个弹窗的「保持不变」是能正常高亮的
 *（`UfiDropdown` 按值比较，`Int?` 的 `null` 正常选中），两端观感必须一致。
 *
 * 为什么用 `0` 而不另造一个哨兵：`0` 本来就已经被 [isWifiMaxStaNumAcceptable] 判为不合法
 *（区间是 `[1, 10]`），也就是说这个哨兵**不需要额外的守门**，任何把它当真值下发的路径
 * 都会被既有判据挡住。
 *
 * 它只活在控件层：过边界要先经 [wifiMaxStaNumForPayload] 折成 `null`，
 * **不得**泄漏到 [WifiConfigFormInput] 之外。
 */
export const WIFI_MAX_STA_NUM_KEEP_VALUE = 0;

/**
 * 「最大连接数」下拉的选项 = 「保持不变」([WIFI_MAX_STA_NUM_KEEP_VALUE]) + [WifiMaxStaNumRange]
 * 闭区间内每个整数。
 *
 * 选择类控件统一使用下拉：十选一的值域由**选项本身**约束，
 * 用户无法输入 `99`，因此不存在「输入后被拒」这条路径（`n-input-number` 的 `:max`
 * 只在用步进器时强制夹取，手工输入仍会进入 form）。提交守门 [isWifiMaxStaNumAcceptable]
 * 仍然保留，它同时约束「不下发」这一语义，**不得放宽**。
 *
 * 首项是**显式**的「保持不变」而不是 `clearable`：与 app 侧
 * `WifiSettingsDialog.kt` 的 `maxStaOptions = listOf<Int?>(null) + WIFI_MAX_STA_RANGE`
 * 两端同构，且显式选项比清除按钮更容易被发现。
 *
 * 值域唯一来源仍是 [WifiMaxStaNumRange]：修改上限只需改该处，这里随之变化，
 * 两个 WiFi 弹窗共用这一份，不存在第二份 1..10。
 *
 * 保持 `ReadonlyArray`（取值域常量不应被外部 push / 改写）。naive-ui 的
 * `n-select :options` 形参是 `SelectMixedOption[]`，直接传入 readonly 会报 TS4104；
 * 妥协放在调用点：组件里 `[...WifiMaxStaNumOptions]` 浅拷贝一次（在 setup 中计算一次，
 * 不在模板表达式中拷贝，避免每次渲染都重建 treemate）。
 */
export const WifiMaxStaNumOptions: ReadonlyArray<{ label: string; value: number }> = [
  { label: WIFI_MAX_STA_NUM_KEEP_LABEL, value: WIFI_MAX_STA_NUM_KEEP_VALUE },
  ...Array.from({ length: WifiMaxStaNumRange.max - WifiMaxStaNumRange.min + 1 }, (_, i) => {
    const n = WifiMaxStaNumRange.min + i;
    return { label: String(n), value: n };
  }),
];

/**
 * 选择器控件层取值 → [WifiConfigFormInput.maxStaNum]（`null` = 不下发）。
 *
 * 唯一的折算处，两个 WiFi 弹窗共用：哨兵 `0`（「保持不变」）以及任何**不可提交**的值
 * 一律折成 `null`。判据直接复用 [isWifiMaxStaNumAcceptable]，不另写一遍区间比较
 * —— 两份实现必然与守门条件产生分歧。
 *
 * 结论：经过这一步之后，`0` 不可能出现在 `max_sta_num` 上（[buildWifiConfigPayload]
 * 还会再判一次），控件层的哨兵不会进入 HTTP 报文。
 */
export function wifiMaxStaNumForPayload(selected: number): number | null {
  return isWifiMaxStaNumAcceptable(selected) ? selected : null;
}

// ────────────────────────────────────────────────────────────
// WiFi 频段 —— POST /api/wifi/band
// ────────────────────────────────────────────────────────────

/**
 * 频段的**传输取值域**，使用设备自身的词汇。
 *
 * 设备侧只有一条命令能真正切换频段：
 * `goformId=switchWiFiChip&ChipEnum=chip1|chip2&GuestEnable=0`（真机抓包验证，
 * chip1 = 2.4G、chip2 = 5G），core 将其封装为 `POST /api/wifi/band`，接收 `{ chip }`。
 *
 * 经 `POST /api/wifi/config` 的 `chip_index` 落到设备 `setAccessPointInfo` → `ChipIndex`
 * 的方式**设备不接受**（修改后不生效），该映射已从 [buildWifiConfigPayload] 中移除，
 * 不得重新引入。
 *
 * 只接受 `chip1` / `chip2`：**不得**另造 `'1'` / `'2'`（那是读侧展示字段 `chip_index` 的编码）
 * 或 `'2.4G'` / `'5G'`（那是界面文案）作为传输值，否则 core 侧 profile 的 validate 会直接返回 400。
 */
export const WifiBands = ['chip1', 'chip2'] as const;
export type WifiBand = (typeof WifiBands)[number];

/** 读不到设备当前频段时的兜底值（设备只有单频段在跑，2.4G 是出厂档）。 */
export const WIFI_BAND_DEFAULT: WifiBand = 'chip1';

/**
 * 频段选择器的选项。文案是人话（「2.4 GHz」），value 是设备词汇（`chip1`）——
 * 界面上看到什么与线上发出去什么在这里一次对齐，免得组件里各写一份字面量。
 *
 * 保持 `ReadonlyArray`：这是**取值域常量**，不该被外部 push / 改写。
 * naive-ui 的 `n-select :options` 形参是 `SelectMixedOption[]`，直接喂 readonly 会报
 * TS4104，妥协放在调用点 —— 组件里 `[...WifiBandOptions]` 浅拷贝一次（同
 * [WifiMaxStaNumOptions]、[wifiSecurityOptions] 的处理）。
 */
export const WifiBandOptions: ReadonlyArray<{ label: string; value: WifiBand }> = [
  { label: '2.4 GHz', value: 'chip1' },
  { label: '5 GHz', value: 'chip2' },
];

/**
 * 读侧字段 `chip_index`（`'1'` / `'2'`，由 `normalizeWifiSettings` 从 `wifi_chip` 归一出来，
 * **只用于展示**）→ 写侧取值域 [WifiBand]。
 *
 * 表单需要用「设备当前频段」与用户的选择比对才能判断是否下发，而它只有展示字段，
 * 这个翻译必须有且只有一处：读侧编码一旦改动，跟着改这里就够了。
 */
export function wifiBandFromChipIndex(chipIndex: string | null | undefined): WifiBand {
  return chipIndex === '2' ? 'chip2' : WIFI_BAND_DEFAULT;
}

/** 表单侧的 WiFi 配置输入，由 [buildWifiConfigPayload] 翻成 `POST /api/wifi/config` 的报文。 */
export interface WifiConfigFormInput {
  ssid: string;
  /** 加密方式下拉的 value，即要下发的 `auth_mode` */
  authMode: string;
  passphrase: string;
  /** null = 留空不修改 */
  maxStaNum: number | null;
  /** true = **隐藏** SSID，对应 `broadcast_disabled=1` */
  hidden: boolean;
  /** 设备回读的 `encryp_type`，仅在 authMode 是表外写法时作为透传兜底 */
  fallbackEncrypType?: string;
}

/**
 * 拼 `POST /api/wifi/config` 的报文。**两个 WiFi 设置表单共用这一份**。
 *
 * 收敛到此处的原因：OPEN 档不能携带 `passphrase`，`encryp_type` 不传会被 core 强制写成 CCMP；
 * 这两条规则若各写两份，必然有一份遗漏。
 */
export function buildWifiConfigPayload(input: WifiConfigFormInput): Record<string, unknown> {
  const preset = findWifiSecurityPreset(input.authMode);
  const payload: Record<string, unknown> = {
    ssid: input.ssid,
    auth_mode: input.authMode,
    // 预设档使用配对值；表外写法把设备回读值原样带回。
    // 两者都不传时 core 会把 EncrypType 强制写成 CCMP，等同于修改 SSID 时破坏了加密方式。
    encryp_type: preset ? preset.encrypType : input.fallbackEncrypType || undefined,
    // 语义是「隐藏」：1 = 隐藏（不广播），0 = 广播。不得按字面理解为「广播开关」。
    broadcast_disabled: input.hidden ? 1 : 0,
  };
  // 频段**不在此处**：`chip_index` 走 setAccessPointInfo，设备不接受（修改后不生效）；
  // 真正切换频段需请求 `POST /api/wifi/band`，取值域见 [WifiBands]。
  // 留空 / 越界均不下发：core 未收到该字段时保持设备现值，比下发一个设备会拒绝的值更安全。
  // 判定复用 [isWifiMaxStaNumAcceptable]（它对 null 返回 true，因此还需排除 null）：
  // 在此另写一遍区间比较会与守门条件产生分歧。
  if (input.maxStaNum !== null && isWifiMaxStaNumAcceptable(input.maxStaNum)) {
    payload.max_sta_num = input.maxStaNum;
  }
  // 开放网络**不携带** passphrase：真机 OPEN 档只发 AuthMode=OPEN + EncrypType=NONE，
  // 多带一个密码字段会让设备按「有密码」处理，导致选择开放网络后无法连接。
  if (preset ? preset.needsPassphrase : true) payload.passphrase = input.passphrase;
  return payload;
}

/**
 * 前台 UI 订阅集合 = 全集去掉 `notification`。

 * core 对同一条告警会 `notification` + `alert` 双发（兼容旧客户端），
 * 两个都订会让同一告警被处理两次。与 Kotlin 侧 `WsChannel.UI_TOPICS` 对齐。
 */
export const WS_UI_TOPICS: string[] = WS_CHANNELS_ALL.filter((c) => c !== WsChannel.NOTIFICATION);

/** **禁止订阅**：core 从不广播。电量走 /api/dashboard/summary REST。 */
export const WS_NEVER_BROADCAST = ['battery', 'sms_contacts'] as const;

export const WS_MAX_CONNECTIONS = 4;

/**
 * 告警类型 / 级别（注意：core 侧目前**无白名单校验**，这是约定而非强制）。
 * 与 Kotlin `Alerts.Type.ALL` / `Alerts.Level` 对齐。
 *
 * 后三类由 `AlertEngine` 检测（checkTrafficLimit / recordDeviceEvent），但 `typeEnabled` 的判据是
 * `perType[type] == true`（**缺键 = 关**），因此它们必须出现在本数组中，分类开关才能真正开启。
 * `traffic_limit` 是**按套餐用量百分比**预警，与按绝对 MB 阈值的 `traffic` 是两类，不得混用。
 */
export const AlertType = [
  'temperature',
  'battery',
  'traffic',
  'signal',
  'connectivity',
  'traffic_limit',
  'plan_expiry',
  'device_online',
  'device_offline',
] as const;
export const AlertLevel = ['info', 'warning', 'critical'] as const;

/** `AlertConfig.perType` 的键域 —— 分类开关用它做穷尽性检查（漏一类会编译不过）。 */
export type AlertTypeKey = (typeof AlertType)[number];

/** /api/alerts/list 的钳制：limit 1..200，默认 50；cursor 是 base64 `ts.id`，向更早翻页。 */
export const AlertListLimits = { min: 1, max: 200, default: 50 } as const;
export const clampAlertLimit = (n: number): number =>
  Math.min(AlertListLimits.max, Math.max(AlertListLimits.min, Math.trunc(n)));

/**
 * 短信拦截规则的取值域（权威来源：core `SmsFilter.SCOPES` / `SmsFilter.MATCH_TYPES`）。
 *
 * **没有正则**：去掉正则之后 contains/equals/prefix/suffix 的行为是可预测的，
 * 「规则有没有生效」由命中次数 + 拦截记录回答，所以 core 也没有 `/rules/test` 试算端点。
 *
 * 号码黑名单 = `scope: 'sender'` + `match_type: 'equals'`；
 * 关键词 = `scope: 'body'` + `match_type: 'contains'`（core 侧新规则的默认值）。
 */
export const SmsRuleScope = ['sender', 'body', 'both'] as const;
export const SmsRuleMatch = ['contains', 'equals', 'prefix', 'suffix'] as const;

/** 取值联合类型 —— 下面的 `Record<Union, …>` 文案表靠它做穷尽性检查（漏一个分支编译不过）。 */
export type SmsRuleScope = (typeof SmsRuleScope)[number];
export type SmsRuleMatch = (typeof SmsRuleMatch)[number];

export const SmsRuleScopeLabels: Record<SmsRuleScope, string> = {
  sender: '发件人',
  body: '正文',
  both: '发件人或正文',
};

export const SmsRuleMatchLabels: Record<SmsRuleMatch, string> = {
  contains: '包含',
  equals: '完全相同',
  prefix: '开头是',
  suffix: '结尾是',
};

/**
 * 拦截记录的 `blocked_path`：三条**写**路径，逗号拼接去重（`"mail,push"`）。
 * 读路径（列表 / 计数）只过滤不写记录，所以没有对应取值。
 */
export const SmsBlockedPath = ['mail', 'push', 'vc'] as const;
export type SmsBlockedPath = (typeof SmsBlockedPath)[number];

export const SmsBlockedPathLabels: Record<SmsBlockedPath, string> = {
  mail: '邮件',
  push: '推送',
  vc: '验证码',
};

/**
 * `blocked_path` → 「邮件·推送·验证码」。
 * 认不出的段**丢掉**而不是原样显示英文：界面上冒出一个 `foo` 只会让人以为是 bug。
 */
export function describeBlockedPath(raw: string | undefined): string {
  return String(raw ?? '')
    .split(',')
    .map((s) => s.trim())
    .filter((s): s is SmsBlockedPath => (SmsBlockedPath as readonly string[]).includes(s))
    .map((s) => SmsBlockedPathLabels[s])
    .join('·');
}

/** `GET /api/sms/rules` 的条目，字段名与 core 的 Room entity 一致（snake_case）。 */
export interface SmsRuleItem {
  id: number;
  enabled: boolean;
  scope: SmsRuleScope;
  match_type: SmsRuleMatch;
  pattern: string;
  note: string;
  hit_count: number;
  last_hit_at: number;
  created_at: number;
}

/**
 * `GET /api/sms/blocked` 的条目（响应里的键是 `records`）。
 *
 * `rule_*` 是命中规则的**快照**：表间无外键，规则删掉之后这条记录还得读得懂，
 * 因此展示命中规则一律使用 `rule_pattern`，不得用 `rule_id` 反查规则表。
 */
export interface SmsBlockedRecord {
  id: number;
  msg_id: number;
  sender: string;
  snippet: string;
  body: string;
  rule_id: number;
  rule_pattern: string;
  rule_scope: string;
  rule_match: string;
  blocked_path: string;
  blocked_at: number;
}

/**
 * 投递渠道 id（`mail_send_records.channel` 列 / `history` 的 `channel` 查询参数）。
 *
 * 推送渠道不在这里：WS 广播是一对多的，没有"逐端投递结果"这种东西，core 也不给它留记录。
 * 这三个值同时出现在渠道卡入口与记录视图里，写错一个的表现是"点进去列表是空的"，
 * 而空列表本身是完全正常的状态，肉眼分不出来 —— 所以只留这一份常量。
 */
export const DeliveryChannel = {
  MAIL: 'mail',
  WEBHOOK: 'webhook',
  LOCAL_SMS: 'local_sms',
} as const;

export type DeliveryChannel = (typeof DeliveryChannel)[keyof typeof DeliveryChannel];

/**
 * 三条渠道（邮件 / Webhook / 本机短信）的 config 响应里**同构**的规则字段。
 * 与 Kotlin 侧 `ChannelRules` 对应。
 *
 * 三条渠道的规则同构：每条渠道都有「最低级别」+「每日上限」，字段名与语义逐字一致，
 * 只有取值域刻意不同（见 [daily_limit_min] / [daily_limit_max]）。因此这里只登记
 * **一份**形状，三处配置界面共用 —— 各写一份会让三处演变成三种语义。
 *
 * 每条渠道各有**独立的计数器**（core 侧物理隔离在三份 prefs 中）：共享计数器意味着
 * 邮件发送量会占用本机短信的额度，而这类故障在统计上看不出异常。
 */
export interface ChannelRules {
  /**
   * 最低投递级别的 wire name（**小写**：`info` / `warning` / `critical`）。
   * 取值域由同一响应的 [levels] 给出；传 core 认不出的值 → **400**（不再静默回落）。
   */
  min_level: string;
  /** 每日条数上限；[DAILY_LIMIT_UNLIMITED] = 不限（只有邮件与 Webhook 允许这一档）。 */
  daily_limit: number;
  /** 只读：今天已发出几条（按**设备本地日期**跨天重置，不是 24 小时滑动窗口）。 */
  sent_today: number;
  /**
   * 只读：今天还剩几条。**`daily_limit = 0`（不限）时是 `null`**。
   *
   * 必须渲染成「不限」而不是 0：0 的含义是「已经用尽」，与「不限」恰好相反 ——
   * 按数字直接显示会给出相反结论，用户会误以为该渠道当日不再发送。
   */
  quota_remaining: number | null;
  /**
   * 级别取值域（下拉按其渲染）。**显示名一律取 core 下发的 `label`**，见 [NotifyLevelOption]：
   * web 不保留第二份级别表 —— 手抄一份时 core 新增一档不会报错，只会导致界面少一项，
   * 且措辞必然与手机端、与 core 的 `NotifyLevel.label` 产生分歧。
   */
  levels: NotifyLevelWire[];
  /**
   * 每日上限的取值域。三条渠道**刻意不同**，界面上要说清：
   * 邮件 `0..500`、Webhook `0..1000`（两者 0 = 不限）、本机短信 `1..50`（**不允许 0** —— 这条渠道花钱）。
   */
  daily_limit_min: number;
  daily_limit_max: number;
}

/**
 * 级别取值域中的一项：wire name + **core 下发的显示名**（Kotlin 侧 `NotifyLevel.name/label`）。
 *
 * 显示名必须由 core 下发的原因：级别名此前在 app 与 web 各有一张映射表，措辞已经分歧，
 * 同一档在两端使用不同名称会让人误以为是两道不同的限制机制。
 * core 的 `NotifyLevel.label` 是唯一真源，客户端只负责渲染。
 */
export interface NotifyLevelOption {
  /** wire name（**小写**），提交给 `min_level` 的就是这个值。 */
  name: string;
  /** 显示名，直接渲染，不再本地翻译。 */
  label: string;
}

/**
 * `levels` 的两种线上形状。
 *
 * **这是对外部响应的版本容错，不是长期设计**：旧版本 core 回的是纯 wire name 数组
 * （`["info","warning","critical"]`），新版本回 [NotifyLevelOption]。设备端是用户自己升级的，
 * 所以两种形状会在同一时期同时存在。
 *
 * 删除条件：core 侧三条渠道的 config 全部改回对象数组、且不再支持旧固件回读之后，
 * 把这里收窄成 `NotifyLevelOption[]`，并删掉 [normalizeNotifyLevels] 里的字符串分支。
 */
export type NotifyLevelWire = string | NotifyLevelOption;

/**
 * 把 `levels` 归一成 [NotifyLevelOption]。
 *
 * 旧 core 只返回字符串时**以 wire name 作为显示名**（界面上即 `info` / `warning` / `critical`）——
 * 刻意不在 web 侧补充兜底级别表：该表在 core 新增一档时不会报错，只会静默显示错误的名称。
 * 无法识别的形状整项丢弃（不足以渲染为一个可选项）。
 */
export function normalizeNotifyLevels(levels: readonly NotifyLevelWire[] | undefined | null): NotifyLevelOption[] {
  if (!Array.isArray(levels)) return [];
  const out: NotifyLevelOption[] = [];
  for (const item of levels) {
    if (typeof item === 'string') {
      if (item) out.push({ name: item, label: item });
    } else if (item && typeof item.name === 'string' && item.name) {
      out.push({ name: item.name, label: item.label || item.name });
    }
  }
  return out;
}

/**
 * `daily_limit` 的「不限」哨兵（Kotlin 侧 `ChannelRules.UNLIMITED`）。
 *
 * 使用 0 而不是 -1：0 是「一条都不发送」在语义上唯一不合理的取值（该诉求由渠道开关表达）。
 * 本机短信的 `daily_limit_min` 是 1，**它不接受这个值**。
 */
export const DAILY_LIMIT_UNLIMITED = 0;

/**
 * `NotificationConfig.critical_override_enabled`（CRITICAL 兜底）的默认值 ——
 * **那个结构里唯一默认开启的字段**。
 *
 * 单独登记成常量是因为本地初值必须与 core 逐字一致：通知配置回读失败时界面沿用本地初值，
 * 这里写成 false 就会出现「UI 显示关着、设备上其实开着」的反向假开关。
 *
 * 语义：级别为 `critical` 的事件穿透**免打扰时段 / 该渠道未勾选的场景 / 该渠道的最低级别**，
 * 但**穿不透**通知总开关、渠道配置不完整、当日配额已用尽。判定只有 core 一处
 * （`NotificationDispatcher.deliverTo`），web 不复现。
 */
export const CRITICAL_OVERRIDE_DEFAULT = true;

/**
 * 一条投递记录（`GET /api/sms-forward/history` 的 `records[]`）。
 *
 * 不含正文：正文可能带验证码与短信全文，历史只回答"发了什么主题、成没成、没成是为什么"。
 */
export interface MailSendRecord {
  id: number;
  /** 渠道 id，取值见 [DeliveryChannel]。 */
  channel: string;
  /** 触发场景 id（与 `notifyShared` 的场景词表同一套；投递记录里还会出现 `test`）。 */
  scene: string;
  subject: string;
  /** 投递目标：邮件是收件地址，Webhook 只有 scheme+host，本机短信是脱敏号码（core 已脱敏）。 */
  recipient: string;
  /**
   * 三态结果原值（core DB v12 新增的 `outcome` 列）：`sent` / `failed` / `skipped`。
   *
   * **不得直接比较该字符串进行分类**，一律走 [deliveryOutcomeOf] —— 全 web 唯一一处判定。
   */
  outcome?: string;
  /**
   * `outcome === 'sent'` 的副本。
   *
   * 三态之后它不足以分类（skipped 行的 `success` 也是 false），只在 `outcome` 缺失时用得上。
   */
  success: boolean;
  /**
   * 结果说明：failed 是摊平的异常链，skipped 是**跳过原因的中文说明**（core 侧 `SkipReason.label`，
   * 如「通知总开关已关闭」「处于免打扰时段」），sent 是空串。
   *
   * 文案由 core 生成，web 侧**不维护第二张原因映射表** —— 手抄一份会导致 core 新增原因时
   * 界面显示空白。**也不得按这段文案做分类或高亮**：分类只认 `outcome`（见 [deliveryOutcomeOf]），
   * 按文案匹配属于「core 调整措辞即静默失效」的判定方式 —— 把 `GATE` 拆成
   * `MASTER_OFF`（通知总开关已关闭）与 `QUIET_HOURS`（处于免打扰时段）即属此类改动。
   */
  error: string;
  sent_at: number;
}

/**
 * `GET /api/sms-forward/history` 的响应（keyset 游标，形态与 [SmsBlockedRecord] 那个列表一致）。
 *
 * `failed_total` / `skipped_total` 都是该渠道的**全表**计数、**互不包含也不得相加**：
 * 跳过是按用户配置主动拦截，计入失败数会让人排查一个不存在的故障。
 * 两者也不随 `result` 筛选变化，因此摘要在任何筛选档下都是同一组数字。
 */
export interface MailHistoryResponse {
  records: MailSendRecord[];
  count: number;
  total: number;
  failed_total: number;
  skipped_total: number;
  next_cursor_ts?: number | null;
  next_cursor_id?: number | null;
  has_more: boolean;
}

/** 一条投递记录的三态结论。分类入口只有 [deliveryOutcomeOf] 一处。 */
export type DeliveryOutcome = 'sent' | 'failed' | 'skipped';

/**
 * `GET /api/sms-forward/history/stats` 的响应（三条渠道共用，`channel` 缺失 = 全渠道）。
 *
 * 与 [MailHistoryResponse] 中那三个计数**是同一批数据的另一种切分**，多出的是
 * `sent` 与 `last_sent_at` —— 列表响应只提供 total/failed/skipped，「已发出多少条」需要自行相减，
 * 而 `total - failed - skipped` 在 core 尚未升级到 DB v12 的设备上会算错
 * （这类 core 不产生 skipped 行，相减虽无误，但也不提供本端点）。
 *
 * **旧固件没有该端点**：返回 404 时不得把整张记录视图判为失败，回落到列表响应中的三个计数即可
 * （少显示 `sent` 与 `last_sent_at` 两项，其余功能不受影响）。
 *
 * `last_sent_at` 是全表最后一条记录的时间戳（ms），**不是「最后一次成功」的时间**；无记录时为 0。
 */
export interface DeliveryHistoryStats {
  total: number;
  sent: number;
  failed: number;
  skipped: number;
  last_sent_at: number;
}

/**
 * 把 [MailSendRecord.outcome] 读成三态结论。**全 web 唯一一处分类判定。**
 *
 * `outcome` 缺失时回落到 `success`。这不是兼容 shim，而是**外部响应的版本容错**：
 * web 资源与 core 是两套独立更新的产物（前端 ZIP / core APK 各有自己的更新通道），
 * 可能出现 web 已是当前版本、设备上的 core 仍为 DB v11（没有 `outcome` 列）的情况。
 * 这是一个诊断视图 —— 把「失败」渲染成「已发出」是它最严重的错误，会导致用户停止排查。
 * 旧 core 只记录发起过投递的行，此时 `success` 即完整判据，回落不会产生错误结论
 * （这类 core 根本不产生 skipped 行）。
 *
 * core 全量升级到 v12（`outcome` 恒有值）之后，`!outcome` 的两个分支可以删除。
 */
export function deliveryOutcomeOf(record: Pick<MailSendRecord, 'outcome' | 'success'>): DeliveryOutcome {
  switch (record.outcome) {
    case 'sent':
      return 'sent';
    case 'failed':
      return 'failed';
    case 'skipped':
      return 'skipped';
    default:
      // outcome 缺失（老 core）：success 是唯一判据。
      return record.success ? 'sent' : 'failed';
  }
}

/**
 * `history` 的 `result` 查询参数取值。
 *
 * 与记录行的 `outcome` **不是同一套**：已发出那一档在 `result` 里叫 `success`、在 `outcome`
 * 里叫 `sent`（`result` 早于三态存在，改它会破坏跨端契约）。其它值 core 按"不过滤"处理。
 */
export const HistoryResultFilter = {
  SENT: 'success',
  FAILED: 'failed',
  SKIPPED: 'skipped',
} as const;

/** `GET /api/sms-forward/history` 的 limit 钳制（core 侧 `coerceIn(1, 200)`，默认 50）。 */
export const HistoryListLimits = { min: 1, max: 200, default: 50 } as const;

/**
 * /api/config 取值范围。core 会把越界字段回报到 `rejected_fields`（带 min/max），
 * 但客户端仍应先钳制输入框，避免用户提交注定被拒绝的值。
 */
export const ConfigLimits = {
  port: [1024, 65535],
  goformPort: [1, 65535],
  qosShellMaxConcurrent: [1, 10],
  qosCacheTtlMs: [500, 30000],
  qosGoformQueryMax: [1, 8],
  qosGoformSetMax: [1, 4],
  smsCodeCleanupHours: [0, 720],
} as const;

/**
 * 更新下载方式（决策下沉至 core）。
 *
 * `update_source_mode` 是**唯一决策字段**，客户端只读写它；「是否使用镜像 / 使用哪个镜像 /
 * 失败后如何换源」全部由 core 的 `MirrorResolver` 决定。`update_url` 与 `update_mirror_base`
 * 是 core 的实现细节（清单地址 + 自定义前缀覆盖），**web 只读不写**：写入等于引入
 * 第二个决策方，与单一决策源的约定冲突。
 */
export const UpdateSourceMode = {
  /** 按设备出口地区：`CN` 走镜像，其他地区与「未测出」都直连 */
  AUTO: 'auto',
  MIRROR: 'mirror',
  DIRECT: 'direct',
} as const;

export type UpdateSourceModeValue = (typeof UpdateSourceMode)[keyof typeof UpdateSourceMode];

/** `PUT /api/config` 响应 `rejected_fields[].reason`，与 Kotlin `ErrorCode` 同名同值。 */
export const ConfigRejectReason = {
  OUT_OF_RANGE: 'OUT_OF_RANGE',
  MASKED_VALUE: 'MASKED_VALUE',
  BLANK_VALUE: 'BLANK_VALUE',
  WRONG_TYPE: 'WRONG_TYPE',
} as const;

export interface ConfigRejectedField {
  field: string;
  reason: string;
  min?: number;
  max?: number;
}

/** 把 rejected_fields 条目翻译成可直接展示的中文原因。 */
export function describeConfigReject(r: ConfigRejectedField): string {
  switch (r.reason) {
    case ConfigRejectReason.OUT_OF_RANGE:
      return `${r.field}：取值需在 ${r.min} ~ ${r.max} 之间`;
    case ConfigRejectReason.MASKED_VALUE:
      return `${r.field}：不能回写脱敏值（含 ***）`;
    case ConfigRejectReason.BLANK_VALUE:
      return `${r.field}：不能为空`;
    case ConfigRejectReason.WRONG_TYPE:
      return `${r.field}：值类型不正确`;
    default:
      return `${r.field}：${r.reason}`;
  }
}

/** 单位与哨兵值约定，与 Kotlin 侧 Units.kt 对齐。 */
export const Units = {
  /** 时间戳全链路 ms，**不得再 * 1000** */
  timestamp: 'ms',
  /** rx_speed / tx_speed 是 bytes/s；Mbps = *8/1e6 */
  trafficSpeed: 'bytes/s',
  /** 下载进度 0..1（不是 0..100），-1 = 元数据阶段进度未知 */
  downloadProgressUnknown: -1,
  /** totalSize -1 = 未知 */
  downloadTotalSizeUnknown: -1,
  /** 温度已由 core 换算为 °C；电池电压已换算为 V；电量 -1 = 未知 */
  batteryPercentUnknown: -1,
  /** 测速 ckSize = 1MiB 块数，默认 10，钳制 1..4096 */
  speedtestChunks: { default: 10, min: 1, max: 4096 },
  /** /api/debug-logs 返回字符串数组 */
  debugLogsShape: 'string[]',
} as const;

/**
 * 失败信封：**HTTP 200 也可能是失败**（`{ success: false }` / `{ ok: false, error }`）。
 * core 尚未统一失败信封（`ok()` / `fail()` 尚未落地），客户端必须同时识别两种键。
 */
export const isFailure = (body: any): boolean =>
  body != null && typeof body === 'object' && (body.success === false || body.ok === false);

/**
 * 设备数据字段契约 —— 与 Kotlin 侧 `DeviceFields.kt` 一一对应的手工镜像。
 *
 * 这是「无论设备（goform）侧字段如何变化，core 对外始终返回这些 key」的冻结清单。
 * 设备侧的可变性由 core 的 `:core:device-schema` / `DeviceProfile` 吸收，
 * **前端只与 core 交互，永远不直接请求 goform**。
 *
 * 三条约定（与 Kotlin 侧同源）：
 * 1. 既有 key 一律不改名（命名不一致是从透传时期继承的历史遗留，冻结即接受）
 * 2. 新增 key 必须使用 snake_case
 * 3. 字段缺失 = 该 key 不出现，而不是值为 `null`
 *
 * 每个分组的 `all` 数组会被 `scripts/verify-api-contract.mjs` 与 Kotlin 侧的
 * `ALL` 列表逐项比对，不一致会报 P0。
 */
export const DeviceFields = {
  version: 'v1',

  /** GET /api/device/settings —— 值域全是字符串，布尔见 bool */
  deviceSettings: {
    indicatorLight: 'indicator_light_switch',
    performanceMode: 'performance_mode',
    samba: 'samba_switch',
    usbPort: 'usb_port_switch',
    restartSchedule: 'restart_schedule_switch',
    restartTime: 'restart_time',
    wifiSleepIdleMinutes: 'sleep_sysIdleTimeToSleep',
    bearerPreference: 'BearerPreference',
    netSelect: 'net_select',
    connectionMode: 'connection_mode',
    roam: 'roam_setting_option',
    dialRoam: 'dial_roam_setting_option',
    /** FOTA 自动检查更新：'1' = 开，'0' = 关（与写侧 auto_update 同向） */
    fotaAutoUpdate: 'UpgMode',
    all: [
      'indicator_light_switch',
      'performance_mode',
      'samba_switch',
      'usb_port_switch',
      'restart_schedule_switch',
      'restart_time',
      'sleep_sysIdleTimeToSleep',
      'BearerPreference',
      'net_select',
      'connection_mode',
      'roam_setting_option',
      'dial_roam_setting_option',
      'UpgMode',
    ],
  },

  /** GET /api/device/lan-settings —— dhcpLease_hour 单位是小时，需 *3600 */
  lanSettings: {
    lanIp: 'lan_ipaddr',
    lanNetmask: 'lan_netmask',
    macAddress: 'mac_address',
    dhcpEnabled: 'dhcpEnabled',
    dhcpStart: 'dhcpStart',
    dhcpEnd: 'dhcpEnd',
    dhcpLease: 'dhcpLease',
    dhcpLeaseHour: 'dhcpLease_hour',
    mtu: 'mtu',
    tcpMss: 'tcp_mss',
    all: [
      'lan_ipaddr',
      'lan_netmask',
      'mac_address',
      'dhcpEnabled',
      'dhcpStart',
      'dhcpEnd',
      'dhcpLease',
      'dhcpLease_hour',
      'mtu',
      'tcp_mss',
    ],
  },

  /** GET /api/wifi/settings —— broadcastSsid 的 '1' 表示**隐藏** */
  wifiSettings: {
    chip: 'wifi_chip',
    ssid: 'wifi_chip1_ssid1_ssid',
    passphrase: 'wifi_chip1_ssid1_passphrase',
    authMode: 'wifi_chip1_ssid1_auth_mode',
    encryptType: 'wifi_chip1_ssid1_encryp_type',
    broadcastSsid: 'wifi_chip1_ssid1_broadcast_ssid',
    maxStaNum: 'wifi_chip1_ssid1_max_sta_num',
    moduleSwitch: 'WiFiModuleSwitch',
    /** @deprecated core 不再输出（已归一到 moduleSwitch），响应中不包含该字段 */
    enable: 'wifi_enable',
    /** @deprecated 同 enable */
    onoffState: 'wifi_onoff_state',
    all: [
      'wifi_chip',
      'wifi_chip1_ssid1_ssid',
      'wifi_chip1_ssid1_passphrase',
      'wifi_chip1_ssid1_auth_mode',
      'wifi_chip1_ssid1_encryp_type',
      'wifi_chip1_ssid1_broadcast_ssid',
      'wifi_chip1_ssid1_max_sta_num',
      'WiFiModuleSwitch',
    ],
  },

  /** GET /api/wifi/clients —— 值可能是数组，也可能是数组的 JSON 字符串，两种都要解 */
  wifiClients: {
    stationList: 'station_list',
    lanStationList: 'lan_station_list',
    itemHostname: 'hostname',
    itemIp: 'ip_addr',
    itemMac: 'mac_addr',
    all: ['station_list', 'lan_station_list'],
    itemAll: ['hostname', 'ip_addr', 'mac_addr'],
  },

  /** GET /api/network/band-status —— 值是纯数字逗号串，'0'/'all' = 未锁定 */
  bandStatus: {
    lteBandLock: 'lte_band_lock',
    nrBandLock: 'nr_band_lock',
    unlockedValues: ['0', 'all'],
    all: ['lte_band_lock', 'nr_band_lock'],
  },

  /**
   * POST /api/network/band 响应的半失败透传字段。
   * LTE/NR 是两次独立的 goform 写入；一路成功一路失败时 success 仍为 true
   * （网络栈已按「新值 + 旧值」的混合状态重启），客户端凭 partial 提示部分未生效。
   */
  bandLockApply: {
    lteApplied: 'lte_applied',
    nrApplied: 'nr_applied',
    partial: 'partial',
  },

  /** GET /api/network/cell-info · /api/network/neighbor-cells */
  cellInfo: {
    neighborCellInfo: 'neighbor_cell_info',
    lockedCellInfo: 'locked_cell_info',
    ltePci: 'Lte_pci',
    lteEarfcn: 'Lte_fcn',
    lteBands: 'Lte_bands',
    lteRsrp: 'lte_rsrp',
    lteRsrq: 'lte_rsrq',
    lteSnr: 'lte_snr',
    itemPci: 'pci',
    itemEarfcn: 'earfcn',
    itemRsrp: 'rsrp',
    itemRsrq: 'rsrq',
    itemSinr: 'sinr',
    itemRat: 'rat',
    all: [
      'neighbor_cell_info',
      'locked_cell_info',
      'Lte_pci',
      'Lte_fcn',
      'Lte_bands',
      'lte_rsrp',
      'lte_rsrq',
      'lte_snr',
    ],
    itemAll: ['pci', 'earfcn', 'rsrp', 'rsrq', 'sinr', 'rat'],
  },

  /**
   * GET /api/device/traffic-limit
   * 设备侧的复合串（'470_1024' = 470 GB）与恒为 'MB' 的 unit 字段已由 core 归一：
   * 读使用 limit_value / limit_unit_display / limit_bytes，写使用 limit_value + limit_unit。
   */
  trafficLimit: {
    enabled: 'enabled',
    limitValue: 'limit_value',
    limitUnitDisplay: 'limit_unit_display',
    limitBytes: 'limit_bytes',
    alertPercent: 'alert_percent',
    autoClear: 'auto_clear',
    clearDate: 'clear_date',
    usedBytes: 'used_bytes',
    monthlyRxBytes: 'monthly_rx_bytes',
    monthlyTxBytes: 'monthly_tx_bytes',
    monthlyTime: 'monthly_time',
    all: [
      'enabled',
      'limit_value',
      'limit_unit_display',
      'limit_bytes',
      'alert_percent',
      'auto_clear',
      'clear_date',
      'used_bytes',
      'monthly_rx_bytes',
      'monthly_tx_bytes',
      'monthly_time',
    ],
  },

  /** GET /api/device/identity —— 含 PII，core 侧按 Sensitivity 决定是否脱敏 */
  identity: {
    msisdn: 'msisdn',
    imei: 'imei',
    imsi: 'imsi',
    iccid: 'iccid',
    language: 'Language',
    crVersion: 'cr_version',
    innerVersion: 'wa_inner_version',
    all: ['msisdn', 'imei', 'imsi', 'iccid', 'Language', 'cr_version', 'wa_inner_version'],
  },

  /**
   * 信号字段（REST 与 WS `signal` 频道共用）。
   * 注意 lte_snr / nr_snr / lte_band 是 core 自有的小写归一名，**不是**设备原名
   * （设备侧是 Lte_snr / Nr_snr / Lte_bands），一个都不能改。
   *
   * band / band_label / arfcn / band_width / signal_strength / pci 是 core 派生的
   * **服务小区统一字段**（NR 优先、LTE 兜底，按字段存在性判定而不是看 rat 字符串）。
   * 前端读这几个即可，不需要再按制式 if；band_label 已由 core 拼成 'n78' / 'B3'。
   * band_width 设备经常不填，缺失就没有这个 key。
   */
  signal: {
    band: 'band',
    bandLabel: 'band_label',
    arfcn: 'arfcn',
    bandWidth: 'band_width',
    signalStrength: 'signal_strength',
    pci: 'pci',
    all: [
      'rsrp',
      'rsrq',
      'sinr',
      'rssi',
      'rat',
      'operator',
      'cell_id',
      'network_registered',
      'band',
      'band_label',
      'arfcn',
      'band_width',
      'signal_strength',
      'pci',
      'nr_arfcn',
      'nr_band',
      'nr_band_width',
      'nr_signal_strength',
      'nr_snr',
      'nr_pci',
      'nr_cell_id',
      'lte_arfcn',
      'lte_band',
      'lte_band_width',
      'lte_signal_strength',
      'lte_snr',
      'lte_pci',
      'lte_cell_id',
      'lte_ca_status',
    ],
  },

  /** 连接状态（多个端点共用）。network_type 已过 core 的值映射（'5G' 而不是 '20'） */
  connection: {
    pppStatus: 'ppp_status',
    networkType: 'network_type',
    networkProvider: 'network_provider',
    all: ['ppp_status', 'network_type', 'network_provider'],
  },

  /** 设备侧布尔有两套编码，读取时两套都要认 */
  bool: {
    truthy: ['1', 'on', 'true', 'SERVER'],
    falsy: ['0', 'off', 'false'],
  },

  /**
   * 不属于稳定契约的端点：原始 dump / 无白名单平铺 / 裸 goform 命令通道，
   * 客户端不应依赖其字段名。
   * 两个 device/goform 子路径是 POST 命令通道（默认关，开关 `goform_command_enabled`），
   * 返回值不归一化也不脱敏 —— 只供排查故障，不得接入界面。
   */
  unstableEndpoints: ['api/device/goform', 'api/device/goform/query', 'api/device/goform/set', 'api/wifi/module-info'],
} as const;

// ────────────────────────────────────────────────────────────
// 基站信息（GET /api/network/cell-info）—— 展示与回读确认共用的解析
// ────────────────────────────────────────────────────────────

/**
 * 小区信息里「数组字段」的取值：真数组，或**数组的 JSON 字符串**（双重编码，取决于固件）。
 *
 * 展示与锁小区的回读确认需要解析同样的两种形态，因此统一收敛到此处：
 * 两份实现必然出现分歧（core 侧 `ZteGoformProfile.normalizeCellLists` 也在做同一件事，
 * 但客户端不能假设所有固件都被 core 归一过，见 `DeviceFields.CellInfo` 的 KDoc）。
 */
export function parseCellList(v: unknown): any[] {
  if (!v) return [];
  if (Array.isArray(v)) return v;
  if (typeof v === 'string') {
    try {
      const parsed = JSON.parse(v);
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return [];
    }
  }
  return [];
}

/**
 * 小区标识字段的可比对形式。
 *
 * 设备把 PCI / 频点写成数字或字符串两种（`Lte_pci: 301` / `"301"`），直接 `===` 比会把
 * 「已经驻留到目标小区」判成没到。数字归一成十进制字符串（`301` 与 `"301"` 相等），
 * 读不到（null / undefined / 空串）一律返回 `''` = **没读到**，调用方不得当成相等。
 */
export function cellFieldKey(v: unknown): string {
  if (v === null || v === undefined) return '';
  return String(v).trim();
}

/**
 * 服务小区（PCI / EARFCN），字段名取 [DeviceFields.cellInfo]。
 *
 * ⚠ 服务小区只有 **LTE** 那一组字段（`Lte_pci` / `Lte_fcn`）：NR 的 `Nr_pci` / `Nr_fcn`
 * 归 SIGNAL 分组（core `DeviceFields.Signal`），cell-info 端点里根本没有。
 * 所以「NR 目标小区」不能靠服务小区比对确认 —— 调用方要自己处理这一支。
 */
export function cellInfoServingCell(raw: unknown): { pci: string; earfcn: string } {
  const src: any = raw ?? {};
  return {
    pci: cellFieldKey(src[DeviceFields.cellInfo.ltePci] ?? src[DeviceFields.cellInfo.itemPci]),
    earfcn: cellFieldKey(src[DeviceFields.cellInfo.lteEarfcn] ?? src[DeviceFields.cellInfo.itemEarfcn]),
  };
}

/** 已锁定小区列表（只取 pci / earfcn 两个判据字段；`rat` 与信号量不参与判定）。 */
export function cellInfoLockedCells(raw: unknown): Array<{ pci: string; earfcn: string }> {
  const src: any = raw ?? {};
  return parseCellList(src[DeviceFields.cellInfo.lockedCellInfo]).map((c: any) => ({
    pci: cellFieldKey(c?.[DeviceFields.cellInfo.itemPci]),
    earfcn: cellFieldKey(c?.[DeviceFields.cellInfo.itemEarfcn]),
  }));
}

/** `GET /api/weather/config` 配置。`0, 0` 是「未设置城市」的哨兵值，不是有效坐标。 */
export const WeatherUnit = { CELSIUS: 'celsius', FAHRENHEIT: 'fahrenheit' } as const;
export type WeatherUnit = (typeof WeatherUnit)[keyof typeof WeatherUnit];

/**
 * `GET /api/weather` 在**已配置**（`configured: true`）时的完整形状。
 * 未配置时只有 `{ configured: false, enabled, message }`。
 *
 * `hourly_times` / `hourly_temperatures` 是等长的 24 点数组（index 0 = 当前小时）。
 * `weather_code` 是 WMO 码；中文描述取 `description`，**不得在 web 侧再次翻译**。
 */
export interface WeatherNow {
  configured: boolean;
  enabled?: boolean;
  message?: string;
  city?: string;
  temperature?: number;
  apparent_temperature?: number;
  humidity?: number;
  precipitation?: number;
  wind_speed?: number;
  weather_code?: number;
  description?: string;
  is_day?: boolean;
  temp_max?: number;
  temp_min?: number;
  sunrise?: string;
  sunset?: string;
  hourly_times?: string[];
  hourly_temperatures?: number[];
  unit?: string;
  timezone?: string;
  updated_at?: number;
}

/** `GET /api/weather/search` 的响应。 */
export interface WeatherSearchResult {
  name: string;
  latitude: number;
  longitude: number;
  country: string;
  admin1?: string;
  timezone?: string;
}

/** `GET /api/poetry` 的响应。 */
export interface PoetryNow {
  content: string;
  title: string;
  dynasty: string;
  author: string;
  full_content: string[];
  translate: string[];
  match_tags: string[];
  popularity: number;
  updated_at: number;
}

/** `GET /api/geo` 的响应。 */
export interface GeoInfo {
  country: string;
  detected_at: number;
  source: 'cache' | 'fresh' | 'unknown';
}

/** 设备侧布尔判定，与 Kotlin 侧 DeviceFields.Bool.isTrue 同语义。 */
export const isDeviceTrue = (raw: unknown): boolean =>
  raw != null && DeviceFields.bool.truthy.some((t) => t.toLowerCase() === String(raw).toLowerCase());
