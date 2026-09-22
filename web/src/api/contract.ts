/**
 * core:contract 的 web 镜像 —— 与 `core/contract/src/main/kotlin/com/ufi_axis_core/contract/` 一一对应。
 *
 * **改动这里必须同步改 Kotlin 侧，反之亦然。**
 * 校验器：`node scripts/verify-api-contract.mjs`（端点差集 + WS 频道差集，P0 必须为空）。
 *
 * 为什么是手抄而不是代码生成：当前没有 exportContract → gen-types 链路（T03 第 4 步，可延后）。
 * 手抄的代价由校验器兜住 —— 端点写错会被报成 P0。
 */

/**
 * 端点路径（**带前导斜杠**，与 axios 调用现状一致）。
 *
 * 只列**真实存在的端点**，不列模块前缀（形如 api + 模块名 的前缀不是端点，
 * 写进来会被校验器当成"客户端声明了不存在的端点"报 P0）。
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
   * 后台服务控制（2026-08-26 新增）。
   * stop 只停数据采集与告警检测，HTTP 服务不停 → start 永远可达；
   * restart 重启后端服务（Service 组件重建，非进程级），接口中断约 10 秒。
   */
  service: {
    status: '/api/service/status',
    start: '/api/service/start',
    stop: '/api/service/stop',
    restart: '/api/service/restart',
    autostart: '/api/service/autostart',
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
  },
  /**
   * AT 命令通道（2026-09-12 入 contract）。
   *
   * `command` 执行单条 AT 指令，`status` / `platform` 是只读探测。
   * 执行历史不由本组写入 —— 真源是服务端在 shell / at 路由执行后记的账。
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
   * 短信拦截：规则（号码黑名单 + 关键词）与拦截记录（2026-09-08）。
   *
   * 挂在既有的 sms 组下而不是新开一个 sms-filter 组，与 core 的 `RootSmsRoutes` 一致
   * （注意：这段说明里不要写带前导斜杠的 api 路径字面量，契约校验器会把它当成
   * 「客户端声明了该端点」，模块前缀必然报 P0）。
   *
   * 两个坑：
   * ① `PUT rules/{id}` 是**字段级合并** —— 只想切 `enabled` 就只传 `enabled`，
   *    全量提交会把 pattern/scope 一起覆盖回表单里的值；
   * ② `GET blocked` 的列表字段名是 **`records`**（不是 `logs`），
   *    keyset 游标参数是 `limit` / `cursor_ts` / `cursor_id`，
   *    响应回 `next_cursor_ts` / `next_cursor_id` / `has_more`。
   *
   * 「验证码豁免关键词拦截」不在这里，它是布尔配置项，走既有的 config 端点
   * （键名 `sms_filter_exempt_verification_code`，默认 true）。
   */
  sms: {
    rules: '/api/sms/rules',
    rule: (id: number) => `/api/sms/rules/${id}`,
    blocked: '/api/sms/blocked',
    blockedRecord: (id: number) => `/api/sms/blocked/${id}`,
  },
  /**
   * 邮件通知（2026-08-27 web 接入；2026-08-29 由「短信转发」改名，只剩 SMTP 一种通道）。
   * 路径**保持 `sms-forward` 不变**：app 与 API 手册都按它引用，改名只会破坏跨端契约。
   * 写操作是 **POST 而非 PUT**；`POST /config` 是字段级合并，凭据字段传空串等于不传
   * （保留原值，无法用该端点清空）。
   * `POST /test` 用的是已持久化的配置，未保存的改动不生效；且失败也是 HTTP 200 + `{success:false,error}`。
   *
   * **`POST /config` 从"永不报错"变成了可能 400**（2026-09-10 规则同构）：`daily_limit` 越界或
   * `min_level` 认不出时回 400 + 中文 `error`。客户端必须有这条错误分支 —— 沿用"保存必成功"的
   * 假设会让用户看到"已保存"而设备侧根本没写进去。
   *
   * `history` 是**三条渠道共用**的投递记录（`mail_send_records` 表，`channel` 列区分），
   * 不只是邮件的 —— 路径留在 sms-forward 组是因为表和端点都先有邮件。三个坑：
   * ① `cursor_ts` 与 `cursor_id` **必须成对**，缺一个 core 就当首页（只带 ts 会让
   *    `id < NULL` 恒为 NULL，同一毫秒的边界行被整段跳过）；
   * ② `DELETE` 的 `channel` 参数**不传就是全清**，所以按渠道的清空按钮必须带上它；
   * ③ `failed_total` 与 `skipped_total` 是两个独立计数，**不能相加**（跳过不是失败），
   *    且都是该渠道的全表计数，不随 `result` 筛选变化。
   *
   * `historyStats` 与 `historyRecord` 是三条渠道共用的另两条（core 侧一直存在，2026-09-19 web 接入）：
   * ④ `historyStats` 的 `channel` **缺失或空 = 全渠道**（与 `history` / `DELETE history` 同口径），
   *    回的是 `total` / `sent` / `failed` / `skipped` / `last_sent_at` —— 比列表响应多出
   *    `sent` 与 `last_sent_at` 两项，且**不受 `result` 筛选影响**；
   * ⑤ `historyRecord` 删单条，`deleted = 0`（行已不在）**仍回 `success: true`**：
   *    客户端可直接把本地行摘掉，不要把"本来就没有"报成删除失败。
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
   * 另两条通知渠道（2026-09-09 web 接入）：Webhook 与本机短信。
   * 与上面的 smsForward（邮件）并列，三条渠道各自独立存配置，都不进 notifications 那一组
   * ——那一组管的是「客户端要不要弹通知」。
   *
   * 这段说明里的路径一律写成 api/notify/... （**不带前导斜杠**）：契约校验器会把注释里
   * 带前导斜杠的 api 路径字面量也算成「web 声明了该端点」，模块前缀必然报 P0。
   *
   * 四处坑：
   * ① 写操作是 **PUT 且字段级合并**（未出现或为 null 的键保留服务端现值），
   *    唯一例外是 Webhook 的 `headers` —— 传了就**整体替换**，所以改任何一个头部
   *    都必须提交全量 map，否则删不掉旧的 Authorization；
   * ② 取值域与预设表由 GET 回（Webhook 的 `presets` / `placeholders`，**三条渠道共有**的
   *    `levels` / `daily_limit_min` / `daily_limit_max`，见 [ChannelRules]），客户端只渲染，
   *    不要手抄第二份。`placeholders` 是 `{ name, desc }` 对象数组（说明文案的真源在 core）；
   *    每个预设还带 `secret_label` / `secret_marker` / `secret_target`（`none` / `url` / `body`）
   *    ——「用户只要填这一样」的机器可读声明，替换时必须整段连尖括号一起换掉，
   *    留下裸 `<` 会让 core 判定未配置齐全、渠道永远不投递；
   * ③ 两个 test 端点**失败也是 HTTP 200** + `{ success: false, error }`；
   *    Webhook 的 `status_code` 为 null 表示请求没走完（超时 / 连不上 / DNS / TLS）；
   * ④ 本机短信的 test **真的从设备 SIM 发出一条短信**、产生费用并占用一条今日配额，
   *    调用前必须让用户确认。
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
   * 流量统计（2026-09-19 进契约）。
   *
   * `usage` 的响应形状与取数封装在 [api/traffic.ts](../api/traffic.ts) 里，那份文件原先自己留了
   * 一个路径字面量并注明"刻意不进契约表"——理由是"只有一半 traffic 路径会更糟"。
   * 现在四条一起登记，那个理由不再成立。
   *
   * 三处坑：
   * ① `realtime` 在调度器尚未预热时回的是 **HTTP 200 + 失败信封**（`code = NO_DATA_YET`），
   *    不是 4xx —— 按 HTTP 码判断成功会把"还没数据"当成有数据，读到一堆 undefined；
   * ② `history` 的 `records[]` 字段是 **camelCase**（`rxBytes` / `txSpeed` / `timestamp`），
   *    直接序列化 Room 实体的结果，与全仓 snake_case 的惯例相反，别照别的端点抄；
   * ③ `usage` 的 `range` / `anchor` **都不会回 400**：range 认不出 → `day`，anchor 解析不出 → now。
   *    翻页锚点一律原样透传响应里的 `prev_anchor` / `next_anchor`，不要在前端做日历运算
   *    （月长度、闰年、DST 都已经在 core 算对了）。
   */
  traffic: {
    realtime: '/api/traffic/realtime',
    history: '/api/traffic/history',
    summary: '/api/traffic/summary',
    usage: '/api/traffic/usage',
  },
  /**
   * 天气（2026-09-19 web 接入；core 侧 `WeatherRoutes`，上游 Open-Meteo，**无需 API key**）。
   *
   * 四处坑：
   * ① `GET /api/weather` 有**两种 200 形状**：未设城市时只回
   *    `{configured: false, enabled, message}`，其余字段一个都没有 —— 见 [WeatherNow]。
   *    直接读 `temperature` 会拿到 undefined，必须先判 `configured`；
   * ② 上游不可达是 **502**（`OPERATION_FAILED`），不是 200 + 失败信封；
   * ③ `weather_code` → 中文描述的映射在 **core** 里做好了（响应的 `description` 字段），
   *    客户端不要再抄一张 WMO 码表 —— 那张表一分叉就会和手机端说两种天气；
   * ④ `PUT /config` 是字段级 patch，但会**校验**：纬度 -90..90、经度 -180..180、
   *    `unit` 只认 `celsius` / `fahrenheit`、`city` ≤ 64 字符，越界回 400。
   *    `latitude = 0 && longitude = 0` 是"未设置"的哨兵，不是几内亚湾。
   */
  weather: {
    now: '/api/weather',
    search: '/api/weather/search',
    config: '/api/weather/config',
  },
  /**
   * 每日诗词（2026-09-19 web 接入；core 侧 `PoetryRoutes`，上游 jinrishici.com，**无需 API key**）。
   *
   * 两处坑：
   * ① `refresh=1` 只绕过 **core 本地那 10 分钟缓存**，上游自己也有约 10 分钟的缓存 ——
   *    所以"刷新"完全可能拿回同一首，这不是 bug，界面上不要承诺"换一首"；
   * ② 没有 tag 参数：选哪首由上游按设备出口 IP 的地理位置、当地天气、时辰与节气决定，
   *    命中的标签在响应的 `match_tags` 里回读。
   */
  poetry: {
    now: '/api/poetry',
    config: '/api/poetry/config',
  },
  /**
   * 国家/地区探测（2026-09-19 进契约）。诗词与天气的上游选源都依赖它。
   *
   * `country` 是 ISO 3166-1 alpha-2，**空串 = 从未探测成功**；此时 `source` 一定是 `unknown`
   * （即便刚刚真的发起过一次抓取），所以判"有没有结果"只看 `country` 是否非空。
   * `POST /detect` 在三个地理源全部不可达时回 **502**，并保留上一次的结果不清空。
   */
  geo: {
    root: '/api/geo',
    detect: '/api/geo/detect',
  },
  /**
   * 设备本机媒体库（core 侧 `MediaRoutes`）。
   * web 侧界面在 `views/media/`（视频 / 音乐 / 图片三个独立页面）与文件预览弹窗。
   *
   * 六处坑，改动前必须读：
   * ① 几乎每条都要 `type` ∈ `video` / `audio` / `image`，缺失或认不出回 400；
   *    权限不足回的是 **403 但 code 仍是 `BAD_REQUEST`**（没有专用错误码），
   *    带 `extra = {permission, type}`；
   * ② `thumbnail` / `cover` 回的是**二进制图片**（带 ETag + `Cache-Control: private, max-age=86400`），
   *    不是 JSON。浏览器里只能用 `<img>` + blob URL，因为 `<img src>` 带不上 Bearer 头与设备签名 ——
   *    与 APK 下载同一个限制（见 [Endpoints.update] 的说明）；
   * ③ `PUT /thumbnail` 的 body 是**裸 JPEG 字节**（不是 JSON、不是 multipart），
   *    上限 512 KB，且必须以 `FF D8 FF` 开头，否则 400；
   * ④ `browse` 在"配置了多个根目录且没传 path"时回的是**选根目录**的形状：
   *    `path: ''` + `parent: null` + 非空 `roots` + 空 `folders`/`items`，不是"这个目录是空的"；
   * ⑤ **播放字节流不在这一组**：走 `/media/stream?ticket=…`（挂在 `/api` 之外的免鉴权区），
   *    要先 `POST /api/files/stream-ticket` 换一张只授权那一个文件、滑动过期 10 分钟的票据；
   *    **必须用响应里的 `url`**，别自己拼；
   * ⑥ `subtitles` 的 `supported` 字段**不能直接当"我能播"** —— 见该字段的说明。
   */
  media: {
    status: '/api/media/status',
    list: '/api/media/list',
    browse: '/api/media/browse',
    thumbnail: '/api/media/thumbnail',
    cover: '/api/media/cover',
    lyrics: '/api/media/lyrics',
    tags: '/api/media/tags',
    config: '/api/media/config',
    rescan: '/api/media/rescan',
    /**
     * 某个视频可用的**外挂字幕列表**。
     *
     * 为什么必须由 core 来列：`.srt` 不是视频，**永远不会出现在 MediaStore 的视频集合里**，
     * 所以 `/list` 与 `/browse` 都发现不了它；而"哪个字幕属于哪个视频"的匹配规则
     * （同名、`movie.zh.srt`、`movie - 中文.srt`…）放到客户端就得在每个播放入口各写一遍。
     *
     * query：`path`（视频真实路径）、`scope`
     *  · `matched`（默认）：只回按文件名判定属于该视频的 → 打开就自动挂载用；
     *  · `folder`：同目录**所有**字幕 → 手动选字幕用（现实里字幕名和视频名经常对不上）。
     *
     * ⚠️ **`supported` 不等于"客户端能播"**：它的语义是「core 能转码并给出字幕 MIME」，
     * 对齐的是 **app 端 media3** 的解析能力。浏览器 `<track>` **只认 WebVTT** ——
     * srt 靠播放器内部转换、ass/ssa 要额外渲染插件、ttml 没有通用方案。
     * 所以 web 侧另有一份自己的能力映射（`composables/subtitleFormat.ts`），
     * 两份**刻意不同**，不要为了"统一"合并 —— 合了必然有一端在说谎。
     *
     * `supported: false` 的条目**照样返回**（MicroDVD `.sub`、SAMI `.smi`）：
     * 让客户端显示"格式不支持"，而不是让用户对着目录里明明存在的文件怀疑程序瞎了。
     */
    subtitles: '/api/media/subtitles',
    /**
     * 字幕文件内容，**已由 core 统一转成 UTF-8**（响应头显式带 `charset=utf-8`）。
     *
     * 为什么不直接拉字节流：字幕解析器按 UTF-8 解，而中文字幕现实中大量是 GB18030/Big5，
     * 直接喂原始字节出来就是一屏乱码，且播放器不提供"换编码重试"的入口。
     * core 把探测与转码收在服务端（BOM → **严格** UTF-8 → GB18030 → Big5 → Latin-1 兜底），
     * 客户端永远只会见到 UTF-8。**这是本端点存在的全部意义。**
     *
     * query：`path`（字幕真实路径）。后缀不认回 **415**，读取失败回 500。
     */
    subtitle: '/api/media/subtitle',
  },
  /**
   * 音频歌单（core 侧 `PlaylistRoutes`，2026-09-21）。
   * web 侧界面是音乐页里的歌单面板（`views/media/components/PlaylistPanel.vue`）。
   *
   * 四处必须知道的约定：
   * ① **曲目以真实路径为标识**，不是 MediaStore 的 id —— id 重扫会变，歌单会整份失效。
   *    所有加歌 / 移出 / 重排的 body 都是 `{ paths: string[] }`；
   * ② `GET {root}/:id/items` 回的 item 与 media.list 的 `items[]` **形状完全一致**，
   *    额外多一个 `missing` 布尔。`missing: true` 的条目 `id` 为 0（媒体库里查不到，
   *    文件被删 / 卡没插 / 还没被扫到），要画"已失效"占位并禁止播放，
   *    **不要自动帮用户移出** —— 拔一次卡就清空歌单是不可接受的；
   * ③ `items` 的顺序就是播放顺序，由用户决定。前端不要再按时间 / 名称排一次；
   * ④ 移出用 DELETE。body 与 `?path=` 都支持（部分代理会丢 DELETE 的 body），
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
   * SIM 卡（2026-08-27 web 接入）。
   * 本组只有 switch 一条：core 原先那个「SIM 信息」查询端点与 dashboard summary 的
   * device_info.identity 同源却多 15 分钟缓存、phone_type 恒为 GSM、sim_state 更粗糙，
   * 两端都不该用，T40-15 已在 core 侧删除。
   * PIN/PUK 状态查询端点同样已在 core 侧删除，web 不再展示 PIN 信息。
   */
  sim: {
    switch: '/api/sim/switch',
  },
  /**
   * 后端 APK 自更新（2026-08-27 web 接入）与前端 ZIP 更新是**两套独立通道**：
   * update 组管 core APK，web 组管 web 资源，各有自己的 status/state 机。
   *
   * 注意：本文件里描述端点时不要用反引号包 api 路径通配写法（形如 update 斜杠星号），
   * 契约校验器会把带引号的 api 路径字面量当成「客户端声明了该端点」，通配路径必然报 P0。
   *
   * 两处坑：①update 组的失败信封是真实 HTTP 码 + `{success,ok,error,message,code}`，
   * 而 web 组只回 `{error}`；②安装 core 时进程会自杀重启，轮询失败属预期，不能当错误。
   * frontendInfo 是「手机 App 安装包」信息（读清单，缓存 5 分钟）；core 另有一个带鉴权的
   * APK 代理下载端点，web 用不了（`<a download>` 带不上 Bearer 头），所以 web 直接给
   * 清单里的 `apk_url` 上游直链。
   *
   * 第三处坑（2026-09-06）：**check 不是「检查」**，它是「检查+下载+校验+安装+重启」一条龙，
   * 点下去就直接开装。要"先看看有没有新版"必须打 backendInfo（只读清单 + 版本比对，
   * 不碰 core 的更新状态机），确认有新版并让用户二次确认后才允许打 check。
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
     * 更新源决策快照（2026-09-22，只读、无入参）：
     * `{ mode, country, use_mirror, mirror_prefixes }`。
     * 给 app 用（它的 APK 自更新不走 core 代理，只取决策自己拼 URL）；web 目前不需要它，
     * 因为 web 不下载任何东西 —— 登记在这里是为了让契约完整、不被当成"野端点"。
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
   * 配置备份与恢复（2026-09-12 web 接入；Kotlin 侧见同名 Endpoints.Backup）。
   *
   * 四个端点都在鉴权块内。三个坑：
   * ① 明文导出（`encrypted: false`）**必须**带 `acknowledge_plaintext: true`，否则 400 ——
   *    包里含设备后台密码与隧道凭据，服务端不接受"悄悄导出"。调用前必须已经向用户
   *    展示过风险（本页的明文确认弹窗就是那个展示）；
   * ② preview / import 的 body 是备份包二进制，口令走 `X-Backup-Passphrase` 请求头
   *    （不要塞进 query：query 会进访问日志与浏览器历史）；
   * ③ 导出响应是二进制附件，axios 要带 `responseType: 'blob'`，错误响应体也会是 Blob，
   *    读错误文案前得先 `.text()`（见 BackupPanel 的 readBlobError）。
   */
  backup: {
    info: '/api/backup/info',
    export: '/api/backup/export',
    preview: '/api/backup/preview',
    import: '/api/backup/import',
  },
  /**
   * 终端命令历史（2026-09-12 web 接入）。
   *
   * **只读写历史、不执行命令** —— 历史的真源是设备端实际执行过什么，由 shell / AT 路由
   * 在执行后写入。import 是把升级前的本地历史一次性搬进来，由客户端保证只调一次
   * （服务端不去重，重复调只会在列表里多出重复行）。
   *
   * 分页是 keyset 游标：`limit` / `cursor_ts` / `cursor_id`，响应回
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
 * 消费端一律按完整 key 匹配（或按 `namespace:` 前缀分流）。2026-09-21 踩过：
 * core 的 `ResponseCache.invalidate` 把 `device:traffic-limit` 截成了 `device`，
 * 两端的精准刷新全部静默失效。新增取值请同步 Kotlin 侧。
 */
export const WsDataTopic = {
  TASK_LIST: 'task:list',
  TASK_RULES: 'task:rules',
  CONSOLE_AT: 'console:at',
  CONSOLE_SHELL: 'console:shell',
  DEVICE_TRAFFIC_LIMIT: 'device:traffic-limit',
  /** 音频歌单集合变了（新建 / 重命名 / 删除 / 加歌 / 移出 / 重排）。 */
  MEDIA_PLAYLISTS: 'media:playlists',
} as const;

/**
 * 网络模式（T15）：与 Kotlin `NetworkMode` 对齐。
 *
 * 两个取值域必须分清：
 * - **别名集**（客户端提交给 `POST /api/network/mode` 与定时任务 `network_mode.mode`，大小写不敏感）；
 * - **BearerPreference**（设备实际取值，大小写敏感，如 `Only_5G`）—— 只出现在
 *   `GET /api/device/settings` 的回读与 `POST /api/network/mode` 响应的 `bearer` 字段里。
 * 客户端**不要**直接提交 BearerPreference。
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
 * **查表统一走 [bearerToNetworkMode]**（先转大写），不要直接下标取值。
 */
export const BearerToNetworkMode: Record<string, string> = {
  WL_AND_5G: NetworkMode.AUTO,
  AUTO: NetworkMode.AUTO,
  // 真机默认档位（2026-09 F50）：语义等于 5G/4G/3G 自动
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
 * 设备侧同一档位有多种大小写写法（`Only_5G` / `only_5g`），原来靠在表里各列一遍，
 * 少列一种就静默落到"未知"。认不出来的取值**原样返回**，不伪装成「自动」。
 */
export function bearerToNetworkMode(raw: string): string {
  const key = String(raw ?? '').trim();
  if (!key) return '';
  return BearerToNetworkMode[key.toUpperCase()] || key;
}

/**
 * 切换制式后的「回读确认」预算，与 Kotlin `NetworkMode.SwitchProbe` 逐字对齐。
 *
 * 为什么需要：`POST /api/network/mode` 返回成功只代表**固件收下了**这条命令，设备还要重新
 * 注册网络，这期间 `GET /api/device/settings` 报的**仍是旧档位**。
 * 写完只回读一次就渲染，界面会停在切换前的档位，直到别处偶然又拉了一次设置才自己变对
 * —— 这正是 2026-09-11 真机上"切换生效了但界面还显示旧制式"。
 *
 * 上限是硬要求：每次回读都真打设备（core 写成功后会清 `device:settings` 缓存），
 * 无上限轮询会把 goform 查询许可耗在这一件事上。
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

/** 总时长上限，给文案用。与 Kotlin `SwitchProbe.TOTAL_BUDGET_MS` 同一算式。 */
export const NetworkModeSwitchBudgetMs = (() => {
  let total = NetworkModeSwitchProbe.firstDelayMs;
  for (let i = 1; i < NetworkModeSwitchProbe.maxAttempts; i += 1) total += modeProbeIntervalMs(i);
  return total;
})();

/**
 * 还要不要再回读一次。与 Kotlin `SwitchProbe.shouldKeepProbing` 同一判据。
 *
 * @param attemptNo 刚刚完成的是第几次回读（从 1 开始）
 * @param reachedTarget 这次回读到的档位是否已等于目标档位
 *
 * 两个终止条件都是硬的：读到目标就停（成功），次数到顶就停（超时）。
 * 不要再叠一层"按总时长判断"——两套上限并存时谁先到谁生效会变成偶发行为。
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
 * 取值来自 2026-09-22 真机抓包。core 侧 `WifiRoutes.kt` 的 `/wifi/config` 只是把
 * `auth_mode` / `encryp_type` 原样透传给 goform（仅对 OPEN 特判），所以**配对关系由客户端负责**。
 *
 * 为什么绑成一档而不给两个下拉：这两个参数在设备侧必须配对下发，分开选就能造出
 * 「WPA3PSK + NONE」这种设备收下了却不生效的组合 —— 又一个假开关。
 *
 * 注意字段名是 `encryp_type`（**不是** `encrypt_type`），与设备后台同名。
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
 * 这一档要不要密码框。
 *
 * 表外写法按「要密码」处理：宁可多留一个输入框，也不要把一个已加密的网络
 * 当成开放网络提交（那会把密码丢掉，所有已连设备立刻掉线）。
 */
export function wifiSecurityNeedsPassphrase(authMode: string | undefined | null): boolean {
  return findWifiSecurityPreset(authMode)?.needsPassphrase ?? true;
}

/**
 * 下拉选项 = 4 个权威档位 +（设备当前值如果是表外写法就置顶并入）。
 *
 * 并入当前值是必须的：不然 select 对一台回读 `WPAPSK` 的设备显示空白，
 * 用户随手一保存就把加密方式改成了别的档 —— 他并没有要求改这一项。
 *
 * 返回**可变数组**：naive-ui 的 `n-select :options` 形参是 `SelectMixedOption[]`，
 * 给 readonly 会在 vue-tsc 里报 TS4104。
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
 * 上限 10 来自用户对**中兴 F50** 的规格结论（2026-09-22），与 core 侧
 * `ZteGoformProfile.AP_MAX_STA_NUM_RANGE`（`validateApConfig` 用它拒 1..10 之外的值）对齐。
 * 导出是为了让两个 WiFi 表单的 `n-input-number :min/:max` 与本文件的提交守门共用同一份取值，
 * 而不是各写一个字面量。
 */
export const WifiMaxStaNumRange = { min: 1, max: 10 } as const;

/**
 * `max_sta_num` 是否可提交。
 *
 * `null` = 留空，语义是「不修改」（core 不传时压根不下发 ApMaxStationNumber）。
 * 其余必须是 [WifiMaxStaNumRange] 闭区间内的整数：越界值 core 侧 `validateApConfig`
 * 会直接 Rejected，在这里先拦下来，用户才看得到原因而不是一句「保存失败」。
 *
 * 这一层是**必须**的：`n-input-number` 的 `:max` 只在用步进器时硬夹，
 * 用户手打 `99` 仍会落进 form。
 */
export function isWifiMaxStaNumAcceptable(v: number | null | undefined): boolean {
  if (v === null || v === undefined) return true;
  return Number.isInteger(v) && v >= WifiMaxStaNumRange.min && v <= WifiMaxStaNumRange.max;
}

// ────────────────────────────────────────────────────────────
// WiFi 频段 —— POST /api/wifi/band
// ────────────────────────────────────────────────────────────

/**
 * 频段的**传输取值域**，用设备自己的词汇。
 *
 * 设备侧只有一条命令能真的换频段：
 * `goformId=switchWiFiChip&ChipEnum=chip1|chip2&GuestEnable=0`（2026-09-22 真机抓包，
 * chip1 = 2.4G、chip2 = 5G），core 把它包成 `POST /api/wifi/band` 收 `{ chip }`。
 *
 * 曾经走的 `POST /api/wifi/config` 的 `chip_index` 落到设备的 `setAccessPointInfo` →
 * `ChipIndex`，**设备不认这条路**，用户实测「改了没反应」—— 那条映射已经从
 * [buildWifiConfigPayload] 里摘掉，不要再加回来。
 *
 * 只认 `chip1` / `chip2`：**不要**另造 `'1'` / `'2'`（那是读侧展示字段 `chip_index` 的编码）
 * 或 `'2.4G'` / `'5G'`（那是界面文案）作为传输值，core 侧 profile 的 validate 会直接 400。
 */
export const WifiBands = ['chip1', 'chip2'] as const;
export type WifiBand = (typeof WifiBands)[number];

/** 读不到设备当前频段时的兜底值（设备只有单频段在跑，2.4G 是出厂档）。 */
export const WIFI_BAND_DEFAULT: WifiBand = 'chip1';

/**
 * 频段选择器的选项。文案是人话（「2.4 GHz」），value 是设备词汇（`chip1`）——
 * 界面上看到什么与线上发出去什么在这里一次对齐，免得组件里各写一份字面量。
 */
export const WifiBandOptions: ReadonlyArray<{ label: string; value: WifiBand }> = [
  { label: '2.4 GHz', value: 'chip1' },
  { label: '5 GHz', value: 'chip2' },
];

/**
 * 读侧字段 `chip_index`（`'1'` / `'2'`，由 `normalizeWifiSettings` 从 `wifi_chip` 归一出来，
 * **只用于展示**）→ 写侧取值域 [WifiBand]。
 *
 * 表单要拿「设备当前频段」和用户的选择比对才知道要不要下发，而它手上只有展示字段，
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
 * 为什么收敛到这里：OPEN 档不能带 `passphrase`、`encryp_type` 不传会被 core 硬写成 CCMP，
 * 这两条规则抄两份就一定会有一份漏掉（原来两个表单各自拼报文，`encryp_type` 也各写了一遍）。
 */
export function buildWifiConfigPayload(input: WifiConfigFormInput): Record<string, unknown> {
  const preset = findWifiSecurityPreset(input.authMode);
  const payload: Record<string, unknown> = {
    ssid: input.ssid,
    auth_mode: input.authMode,
    // 预设档用配对值；表外写法把设备回读值原样带回去。
    // 都不传时 core 会把 EncrypType 硬写成 CCMP，等于改 SSID 顺手改坏了加密方式。
    encryp_type: preset ? preset.encrypType : input.fallbackEncrypType || undefined,
    // 语义是「隐藏」：1 = 隐藏（不广播），0 = 广播。别按字面当成「广播开关」。
    broadcast_disabled: input.hidden ? 1 : 0,
  };
  // 频段**不在这里**：`chip_index` 走 setAccessPointInfo，设备不认（用户实测「改了没反应」），
  // 真正换频段要发 `POST /api/wifi/band`，取值域见 [WifiBands]。
  // 留空 / 越界都不下发：core 不传时保持设备现值，比下发一个设备会拒的值安全。
  // 判定复用 [isWifiMaxStaNumAcceptable]（它对 null 返回 true，所以还要排掉 null）——
  // 在这里另写一遍区间比较，两处迟早会漂移（原来这里写的是 `> 0`，与守门条件各说各话）。
  if (input.maxStaNum !== null && isWifiMaxStaNumAcceptable(input.maxStaNum)) {
    payload.max_sta_num = input.maxStaNum;
  }
  // 开放网络**不带** passphrase：真机 OPEN 档只发 AuthMode=OPEN + EncrypType=NONE，
  // 多带一个密码字段会让设备按「有密码」处理，结果是选了开放却连不上。
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
 * 后三类 2026-09-07 补齐：`AlertEngine` 一直在检测它们（checkTrafficLimit / recordDeviceEvent），
 * 但从未出现在任何清单与 UI 里，而 `typeEnabled` 的判据是 `perType[type] == true`（**缺键 = 关**），
 * 于是这三类在实际使用中是死的。分类开关按本数组渲染，补进来才能真正打开它们。
 * `traffic_limit` 是**按套餐用量百分比**预警，与按绝对 MB 阈值的 `traffic` 是两类，不要混用。
 */
export const AlertType = [
  'temperature',
  'battery',
  'traffic',
  'signal',
  'connectivity',
  'traffic_limit',
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
 * 所以展示命中规则一律用 `rule_pattern`，不要拿 `rule_id` 反查规则表。
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
 * 三条渠道（邮件 / Webhook / 本机短信）的 config 响应里**同构**的那一组规则字段。
 * 与 Kotlin 侧 `ChannelRules` 对应。
 *
 * 2026-09-10 core 把规则做成同构：每条渠道都有「最低级别」+「每日上限」，字段名与语义
 * 逐字一致，只有取值域刻意不同（见 [daily_limit_min] / [daily_limit_max]）。所以这里只登记
 * **一份**形状，三处配置界面共用 —— 各写一份的结果是三处各自跑偏成三种语义。
 *
 * 每条渠道各有**独立的计数器**（core 侧物理隔离在三份 prefs 里）：共享一个计数器意味着
 * 邮件发多了会吃掉本机短信的额度，而那种故障在账面上看不出任何异常。
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
   * 必须渲染成「不限」而不是 0：0 的含义是"已经用尽"，与"不限"恰好相反 ——
   * 照着数字显示会把承诺说反，用户会以为这条渠道今天已经不发了。
   */
  quota_remaining: number | null;
  /**
   * 级别取值域（下拉按它渲染）。**显示名一律取 core 下发的 `label`**，见 [NotifyLevelOption]：
   * web 不留第二份级别表 —— 手抄一份时 core 加一档不会报错，只会在界面上少一项，
   * 而措辞也必然与手机端、与 core 的 `NotifyLevel.label` 各自跑偏（那正是这次改造的起因）。
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
 * 级别取值域里的一项：wire name + **core 给的显示名**（Kotlin 侧 `NotifyLevel.name/label`）。
 *
 * 为什么显示名必须由 core 下发：级别名此前在 app 与 web 各有一张映射表，措辞已经分叉
 * （app「一般（info）」vs web「一般及以上（全部通知）」），而同一档在两端叫两个名字
 * 会让人以为那是两道不同的闸。core 的 `NotifyLevel.label` 是唯一真源，客户端只渲染。
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
 * 老 core 只回字符串时**用 wire name 当显示名**（界面上就是 `info` / `warning` / `critical`）——
 * 刻意不在 web 侧补一张兜底级别表：那张表正是这次要拆掉的东西，而它在 core 加一档时
 * 不会报错，只会静默显示错的名字。认不出的形状整项丢掉（不足以渲染成一个可选项）。
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
 * 用 0 而不是 -1：0 是"一条都不许发"在语义上唯一说不通的取值（那种诉求用渠道开关表达）。
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
   * **不要直接比这个字符串做分类**，走 [deliveryOutcomeOf] —— 那是全 web 唯一一处判定。
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
   * 文案由 core 生成，web 侧**不维护第二张原因映射表** —— 抄一份的结果是 core 加了一种原因、
   * 这边显示成空白。**也不要按这段文案做分类或高亮**：分类只认 `outcome`（见 [deliveryOutcomeOf]），
   * 按文案匹配是"core 改一句措辞就静默失效"的判定 —— 2026-09-10 把 `GATE` 拆成
   * `MASTER_OFF`（通知总开关已关闭）与 `QUIET_HOURS`（处于免打扰时段）正是这样一次改动。
   */
  error: string;
  sent_at: number;
}

/**
 * `GET /api/sms-forward/history` 的响应（keyset 游标，形态与 [SmsBlockedRecord] 那个列表一致）。
 *
 * `failed_total` / `skipped_total` 都是该渠道的**全表**计数、**互不包含也不能相加**：
 * 跳过是闸门按用户自己的配置拦下的，并进失败数会让人去排一个不存在的故障。
 * 两者也不随 `result` 筛选变化，所以摘要在任何筛选档下都是同一组数字。
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
 * 与 [MailHistoryResponse] 里那三个计数**是同一批数字的另一种切法**，多出来的是
 * `sent` 与 `last_sent_at` —— 列表响应只给 total/failed/skipped，"已发出多少条"得自己减，
 * 而 `total - failed - skipped` 在 core 尚未全量升到 DB v12 的设备上会算错
 * （那种 core 不产生 skipped 行，减出来的数没错，但它也不回这个端点 —— 见下面那句）。
 *
 * **老固件没有这条端点**：404 时不要把整张记录视图判成失败，回落到列表响应里的三个计数即可
 * （少显示 `sent` 与 `last_sent_at` 两项，其余功能不受影响）。
 *
 * `last_sent_at` 是全表最后一条记录的时间戳（ms），**不是"最后成功"的时间**；无记录时为 0。
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
 * `outcome` 缺失时回落到 `success`。这不是兼容 shim，是**外部响应的版本容错**：
 * web 资源与 core 是两套独立更新的产物（前端 ZIP / core APK 各有自己的更新通道），
 * 用户完全可能 web 已经是这一版、设备上的 core 还是 DB v11（没有 `outcome` 列）。
 * 而这是一个诊断视图 —— 把「失败」渲染成「已发出」是它最不能犯的错，用户会因此停止排查。
 * 老 core 只记发起过投递的行，那时 `success` 就是完整判据，回落不会把任何东西说错
 * （那种 core 根本不产生 skipped 行）。
 *
 * core 全量升到 v12（`outcome` 恒有值）之后，`!outcome` 那两个分支可以删掉。
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
 * /api/config 取值范围。C03 之后 core 会把越界字段回报到 `rejected_fields`（带 min/max），
 * 但客户端仍应先钳制输入框，避免用户提交注定被拒的值。
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
 * 更新下载方式（2026-09-22：决策下沉 core，见 `docs/update-source-core-plan.md`）。
 *
 * `update_source_mode` 是**唯一决策字段**，客户端只读写它；「用不用镜像 / 用哪个镜像 /
 * 失败怎么换源」全部由 core 的 `MirrorResolver` 决定。`update_url` 与 `update_mirror_base`
 * 自此是 core 的实现细节（清单地址 + 自定义前缀覆盖），**web 只读不写** ——
 * 写了就等于又多出一个决策方，而那正是这次改造要消除的东西。
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
  /** 时间戳全链路 ms，**不要再 * 1000** */
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
 * core 尚未统一（C01 会做 ok()/fail()），客户端两种键都要认。
 */
export const isFailure = (body: any): boolean =>
  body != null && typeof body === 'object' && (body.success === false || body.ok === false);

/**
 * 设备数据字段契约 —— 与 Kotlin 侧 `DeviceFields.kt` 一一对应的手抄镜像。
 *
 * 这是「不管设备（goform）侧字段怎么变，core 对外始终返回这些 key」的冻结清单。
 * 设备侧的可变性由 core 的 `:core:device-schema` / `DeviceProfile` 吸收，
 * **前端只跟 core 打交道，永远不直接请求 goform**。
 *
 * 三条约定（与 Kotlin 侧同源）：
 * 1. 既有 key 一律不改名（命名混乱是从透传时代继承的历史债，冻结即接受）
 * 2. 新增 key 必须 snake_case
 * 3. 字段缺失 = 该 key 不出现，而不是 `null`
 *
 * 每个分组的 `all` 数组会被 `scripts/verify-api-contract.mjs` 与 Kotlin 侧的
 * `ALL` 列表逐项比对，漂移会报 P0。
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
    /** @deprecated core 不再输出（已归一到 moduleSwitch），阶段 4.1 起响应里没有它 */
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
   * 设备侧的复合串（'470_1024' = 470 GB）与恒为 'MB' 的 unit 字段已被 core 吃掉（core 2.8）：
   * 读用 limit_value / limit_unit_display / limit_bytes，写用 limit_value + limit_unit。
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
   * 返回值不归一化也不脱敏 —— 只供排障，不要接到界面上。
   */
  unstableEndpoints: ['api/device/goform', 'api/device/goform/query', 'api/device/goform/set', 'api/wifi/module-info'],
} as const;

/** `GET /api/weather/config` 配置。`0, 0` 是"未设城市"的哨兵，不是几内亚湾。 */
export const WeatherUnit = { CELSIUS: 'celsius', FAHRENHEIT: 'fahrenheit' } as const;
export type WeatherUnit = (typeof WeatherUnit)[keyof typeof WeatherUnit];

/**
 * `GET /api/weather` 在**已配置**（`configured: true`）时的完整形状。
 * 未配置时只有 `{ configured: false, enabled, message }`。
 *
 * `hourly_times` / `hourly_temperatures` 是等长的 24 点数组（index 0 = 当前小时）。
 * `weather_code` 是 WMO 码；中文描述取 `description`，**不要在 web 侧再翻译一次**。
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
