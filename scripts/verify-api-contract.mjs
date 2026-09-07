#!/usr/bin/env node
/**
 * API 契约漂移校验（T02）。
 *
 * 用法：
 *   node scripts/verify-api-contract.mjs            # 人类可读报告
 *   node scripts/verify-api-contract.mjs --json     # 机器可读
 *
 * 目的：在写任何契约代码之前，先有一个能**自证有效**的漂移探测器。
 * 它从四个来源各自抽取端点集合，然后交叉比对：
 *   - core ：core/** 里的 Ktor 路由声明（route/get/post/put/delete/patch/webSocket）
 *   - app  ：app/** 里的 Retrofit 注解（@GET/@POST/...）
 *   - web  ：web/src/** 里的 axios 调用与 /api 字面量
 *   - docs ：docs/UFI-AXIS-Core-API-Reference.md 里写明的端点
 *
 * 判定口径：比较**路径后缀**而不是完整路径。原因是 core 的路由分散在多个
 * `Route.xxxRoutes()` 扩展函数里，挂载前缀由 HttpServer.kt 决定，静态还原完整路径
 * 会引入大量假报警；而"客户端声明的 api/alerts/list 在 core 里能否找到以
 * alerts/list 结尾的路由"这一判定既稳又足以抓出真实漂移。
 *
 * 退出码：存在 P0（客户端/文档有、core 没有）时为 1，否则 0。
 * 首次运行**必须**报出 /api/adb/* 属于 P0 —— 这是本脚本的自证条件。
 */

import { readdirSync, readFileSync, statSync, existsSync } from 'node:fs'
import { join, extname, relative } from 'node:path'
import { fileURLToPath } from 'node:url'
import { dirname } from 'node:path'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const JSON_OUT = process.argv.includes('--json')

// ───────────────────────── 工具 ─────────────────────────

function walk(dir, exts, out = []) {
  if (!existsSync(dir)) return out
  for (const name of readdirSync(dir)) {
    if (name === 'node_modules' || name === 'build' || name === '.git' || name === 'dist') continue
    const p = join(dir, name)
    const st = statSync(p)
    if (st.isDirectory()) walk(p, exts, out)
    else if (exts.includes(extname(p))) out.push(p)
  }
  return out
}

/**
 * 归一化路径：去首尾斜杠、折叠重复斜杠、路径参数统一为 `:p`、去查询串。
 *
 * **尾通配单独标记成 `:p*`**：Ktor 的 `{name...}` 吃「任意多段」，
 * `route("/files") { get("/{name...}") }` 同时服务 `/files/2026-08-28/app.log`。
 * 若跟单段参数一样折成 `:p`，文档里写的两段路径就会被误报成 core 缺失。
 */
function norm(path) {
  if (!path) return ''
  let p = String(path).split('?')[0].split('#')[0]
  p = p.replace(/\\/g, '/').replace(/\/{2,}/g, '/')
  p = p.replace(/^\/+|\/+$/g, '')
  p = p
    .split('/')
    .map((seg) => {
      if (/^\{.*\.\.\.\}$/.test(seg)) return ':p*' // Ktor tailcard，匹配任意多段
      // Ktor {id} / Retrofit {path} / JS ${x} / 冒号参数 → 统一占位
      if (/^\{.*\}$/.test(seg) || /^\$\{.*\}$/.test(seg) || /^:/.test(seg)) return ':p'
      if (/\$\{/.test(seg)) return ':p'
      return seg
    })
    .join('/')
  return p
}

/**
 * 只关心服务端契约相关的前缀，过滤掉外部 URL / 静态资源等噪声。
 *
 * 含 `*` 的一律不算声明：那是散文写法（注释里的 `/api/sms-forward/*`
 * 指「这一组接口」，不是某个真实端点），当成声明会造出永远修不掉的 P0。
 */
function isContractPath(p) {
  if (p.includes('*')) return false
  return /^(api|ws|pairing|pair|health)(\/|$)/.test(p)
}

/**
 * 已知「core 有但抓不到客户端引用」的白名单。
 * 不是漂移，只是本脚本的静态扫描覆盖不到该调用方式。
 */
const KNOWN_UNREFERENCED = new Set([
  'ws/realtime', // WebSocket 端点，两端都用 OkHttp WebSocket / 浏览器 WebSocket 连接，不走 axios/Retrofit
  ':p*', // HttpServer 的 SPA 兜底路由 `/{path...}`，由浏览器直接访问，不是被调用的 API
])


// ───────────────────────── core：Ktor 路由 ─────────────────────────

const ROUTE_OPEN = /\broute\s*\(\s*"([^"]*)"\s*\)/
/**
 * Ktor 动词调用。
 *
 * `(?<![.\w])` 是必须的：`goformData?.get("imei")` / `map.get("cr_version")` 这类
 * **成员调用**也长得像 `get("x")`，不排除的话会在 `route("/sim")` 块里造出
 * `/api/sim/imei` `/api/sim/imsi` `/api/sim/iccid` 这些根本不存在的端点
 * （同理 DeviceRoutes 里会造出 `/api/device/cr_version` 等），既虚增 core 端点数，
 * 又让「core 存在但无任何端引用」列表里全是查不到的幽灵。
 */
const VERB = /(?<![.\w])(get|post|put|delete|patch|head|options|webSocket)\s*\(\s*"([^"]*)"\s*\)/g
const VERB_NOPATH = /(?<![.\w])(get|post|put|delete|patch|head|options|webSocket)\s*\{/g
const ROUTE_EXT_DEF = /\b(?:fun\s+Route\s*\.\s*(\w+)\s*\(|class\s+(\w+Routes)\b)/
// 两种挂载写法：扩展函数 `alertRoutes(...)`；或类实例 `pairingRoutes.register(this)`
const ROUTE_MOUNT = /\b(\w*[Rr]outes)\s*(?:\?)?\s*(?:\.\s*register\s*\(|\()/g


/**
 * 解析挂载前缀：`HttpServer.kt` 里形如
 *   route("/api") { route("/alerts") { alertRoutes(...) } }
 * 决定了 `AlertRoutes.kt` 中 `get("/list")` 的真实路径是 `/api/alerts/list`。
 * 不解析这层映射就无法还原完整路径，会把 core 已有的端点误报为缺失。
 */
function parseMountPrefixes() {
  const prefixes = new Map() // 扩展函数名 -> 挂载前缀
  const files = walk(join(ROOT, 'core'), ['.kt']).filter((f) =>
    /HttpServer\.kt$/.test(f)
  )
  for (const file of files) {
    const lines = readFileSync(file, 'utf8').split(/\r?\n/)
    const stack = []
    let depth = 0
    for (const rawLine of lines) {
      const line = rawLine.replace(/\/\/.*$/, '')
      const before = depth
      const rm = line.match(ROUTE_OPEN)
      if (rm) stack.push({ prefix: rm[1], depth: before })
      const prefix = norm(stack.map((s) => s.prefix).join('/'))
      let m
      ROUTE_MOUNT.lastIndex = 0
      while ((m = ROUTE_MOUNT.exec(line)) !== null) {
        const key = m[1].toLowerCase()
        if (!prefixes.has(key)) prefixes.set(key, prefix)
      }

      for (const ch of line) {
        if (ch === '{') depth++
        else if (ch === '}') depth--
      }
      while (stack.length > 0 && depth <= stack[stack.length - 1].depth) stack.pop()
    }
  }
  return prefixes
}

function parseCore() {
  const mounts = parseMountPrefixes()
  const all = walk(join(ROOT, 'core'), ['.kt'])
  const found = new Map() // normalizedPath -> Set(file)
  for (const file of all) {
    // 只解析真正声明 Ktor 路由的文件，排除测试。
    // 否则 goform 字符串、AT 命令、Map 取值里的 get("x") 会被误当成路由（大量假阳性）。
    if (file.replace(/\\/g, '/').includes('/src/test/')) continue
    const text = readFileSync(file, 'utf8')
    if (!/io\.ktor\.server\.routing|io\.ktor\.server\.websocket/.test(text)) continue
    const lines = text.split(/\r?\n/)


    /** 每个元素 = { prefix, depth }，depth 为该 route 块开始时的花括号深度 */
    const stack = []
    let depth = 0
    let mountPrefix = ''
    for (const rawLine of lines) {
      const line = rawLine.replace(/\/\/.*$/, '')
      const before = depth

      const fnDef = line.match(ROUTE_EXT_DEF)
      if (fnDef) {
        const name = (fnDef[1] || fnDef[2] || '').toLowerCase()
        if (mounts.has(name)) mountPrefix = mounts.get(name)
      }


      const routeMatch = line.match(ROUTE_OPEN)
      if (routeMatch) {
        stack.push({ prefix: routeMatch[1], depth: before })
      }

      const prefix = [mountPrefix, ...stack.map((s) => s.prefix)].join('/')


      let m
      VERB.lastIndex = 0
      while ((m = VERB.exec(line)) !== null) {
        const p = norm(prefix + '/' + m[2])
        if (!p) continue
        if (!found.has(p)) found.set(p, new Set())
        found.get(p).add(relative(ROOT, file))
      }
      VERB_NOPATH.lastIndex = 0
      while ((m = VERB_NOPATH.exec(line)) !== null) {
        const p = norm(prefix)
        if (!p) continue
        if (!found.has(p)) found.set(p, new Set())
        found.get(p).add(relative(ROOT, file))
      }

      // 更新花括号深度并弹出已闭合的 route 块
      for (const ch of line) {
        if (ch === '{') depth++
        else if (ch === '}') depth--
      }
      while (stack.length > 0 && depth <= stack[stack.length - 1].depth) stack.pop()
    }
  }
  return found
}

// ───────────────────────── app：Retrofit 注解 ─────────────────────────

const RETROFIT = /@(GET|POST|PUT|DELETE|PATCH|HEAD)\s*\(\s*"([^"]*)"\s*\)/g

function parseApp() {
  const files = walk(join(ROOT, 'app'), ['.kt'])
  const found = new Map()
  for (const file of files) {
    const text = readFileSync(file, 'utf8')
    let m
    RETROFIT.lastIndex = 0
    while ((m = RETROFIT.exec(text)) !== null) {
      const p = norm(m[2])
      if (!p || !isContractPath(p)) continue
      if (!found.has(p)) found.set(p, new Set())
      found.get(p).add(relative(ROOT, file))
    }
  }
  return found
}

// ───────────────────────── web：axios / 字面量 ─────────────────────────

const WEB_CALL = /\b(?:api|axios|http|request)\s*\.\s*(?:get|post|put|delete|patch)\s*(?:<[^>]*>)?\s*\(\s*([`'"])([^`'"]*)\1/g
const WEB_LITERAL = /([`'"])(\/api\/[^`'"\s]*)\1/g

function parseWeb() {
  const files = walk(join(ROOT, 'web', 'src'), ['.ts', '.js', '.vue', '.tsx'])
  const found = new Map()
  const add = (p, file) => {
    if (!p || !isContractPath(p)) return
    if (!found.has(p)) found.set(p, new Set())
    found.get(p).add(relative(ROOT, file))
  }
  for (const file of files) {
    const text = readFileSync(file, 'utf8')
    let m
    WEB_CALL.lastIndex = 0
    while ((m = WEB_CALL.exec(text)) !== null) add(norm(m[2]), file)
    WEB_LITERAL.lastIndex = 0
    while ((m = WEB_LITERAL.exec(text)) !== null) add(norm(m[2]), file)
  }
  return found
}

// ───────────────────────── docs：参考手册 ─────────────────────────

const DOC_ENDPOINT = /\b(GET|POST|PUT|DELETE|PATCH)\s+(\/[A-Za-z0-9_\-{}/.:$]*)/g
/**
 * 端点级小标题只用 4 级（`#### \`GET /api/x\``）。
 * 2/3 级标题是模块索引（`## 设备信息 /api/device`），那不是端点，
 * 收进来会变成一堆「core 没有 /api/device」的假 P0。
 */
const DOC_HEADING = /^#{4}\s+.*?(\/api\/[A-Za-z0-9_\-{}/.:$]*)/gm

/**
 * 参考手册末尾的「文档幻影端点」小节列出的是**故意不存在**的端点，
 * 不能当成文档声明来比对，否则清理完仍会被自己列出的负面清单重新报 P0。
 * 这里以该标题为界把正文截断，截断之后的内容单独解析成负面清单。
 */
const DOC_NEGATIVE_HEADING = '## 文档幻影端点'

function parseDocs() {
  const file = join(ROOT, 'docs', 'UFI-AXIS-Core-API-Reference.md')
  const found = new Map()
  const negative = new Map()
  if (!existsSync(file)) return { found, negative }
  const raw = readFileSync(file, 'utf8')
  const cut = raw.indexOf(DOC_NEGATIVE_HEADING)
  const text = cut === -1 ? raw : raw.slice(0, cut)
  const negText = cut === -1 ? '' : raw.slice(cut)
  const add = (p) => {
    if (!p || !isContractPath(p)) return
    if (!found.has(p)) found.set(p, new Set())
    found.get(p).add(relative(ROOT, file))
  }
  let m
  DOC_ENDPOINT.lastIndex = 0
  while ((m = DOC_ENDPOINT.exec(text)) !== null) add(norm(m[2]))
  DOC_HEADING.lastIndex = 0
  while ((m = DOC_HEADING.exec(text)) !== null) add(norm(m[1]))
  DOC_ENDPOINT.lastIndex = 0
  while ((m = DOC_ENDPOINT.exec(negText)) !== null) {
    const p = norm(m[2])
    if (p && isContractPath(p)) negative.set(p, relative(ROOT, file))
  }
  return { found, negative }
}


// ───────────────────────── WS 频道 ─────────────────────────

/** core 侧真正会广播的频道：`broadcast("x", …)` 与 `broadcaster("x", …)` 的字面量首参。 */
const CORE_BROADCAST = /\b(?:broadcast|broadcaster)\s*\(\s*"([A-Za-z0-9_]+)"/g
/** app 侧订阅清单：`XXX_TOPICS = listOf(` 或 `XXX_TOPICS = WsChannel.UI_TOPICS`。 */
const APP_TOPICS_HEAD = /\b\w*TOPICS\b\s*(?::[^=]*)?=\s*(?:listOf\s*\()?/g
/** web 侧订阅清单：`subscribe: [` 的起点。 */
const WEB_TOPICS_HEAD = /subscribe\s*:\s*\[/g

/**
 * 去掉行注释与块注释。
 * 必须做：清单旁边的 `// TODO(T11) …` 注释里既有括号也有频道名，
 * 不剥离的话既会截断实参扫描，又会把注释里提到的频道当成真订阅。
 */
function stripComments(text) {
  return text.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/(^|[^:])\/\/[^\n]*/g, '$1')
}

/** 从 `open` 的下一个字符起按括号配平取出实参片段。 */
function sliceCall(text, openIdx, open = '(', close = ')') {
  let depth = 1
  for (let i = openIdx + 1; i < text.length; i++) {
    const ch = text[i]
    if (ch === open) depth++
    else if (ch === close) {
      depth--
      if (depth === 0) return text.slice(openIdx + 1, i)
    }
  }
  return ''
}

/**
 * 契约常量表：`object WsChannel { const val CPU = "cpu" … }` → { CPU: 'cpu' }，
 * 以及清单常量 `val UI_TOPICS: List<String> = …` → { UI_TOPICS: ['cpu', …] }。
 *
 * T03/T11 之后两端订阅清单写的是 `WsChannel.UI_TOPICS` 这类符号而不是裸字符串，
 * 只认字面量的话 WS 差集会全部漏报，所以这里必须做符号解析。
 */
function parseWsChannelConstants() {
  const file = join(ROOT, 'core', 'contract', 'src', 'main', 'kotlin', 'com', 'ufi_axis_core', 'contract', 'Enums.kt')
  const consts = new Map()
  const lists = new Map()
  if (!existsSync(file)) return { consts, lists }
  const text = readFileSync(file, 'utf8')
  const start = text.indexOf('object WsChannel')
  if (start === -1) return { consts, lists }
  const rest = text.slice(start)
  const end = rest.indexOf('\n}')
  const body = stripComments(end === -1 ? rest : rest.slice(0, end))
  for (const m of body.matchAll(/const\s+val\s+([A-Z0-9_]+)\s*=\s*"([A-Za-z0-9_]+)"/g)) {
    consts.set(m[1], m[2])
  }
  // 清单常量：`= listOf(A, B, "c")` 或 `= ALL.filter { it != X }`
  for (const m of body.matchAll(/val\s+([A-Z0-9_]+)\s*:\s*List<String>\s*=\s*/g)) {
    const name = m[1]
    const tailIdx = m.index + m[0].length
    const tail = body.slice(tailIdx)
    const listOfIdx = tail.indexOf('listOf(')
    const filterMatch = tail.match(/^([A-Z0-9_]+)\s*\.\s*filter\s*\{\s*it\s*!=\s*([A-Z0-9_]+)\s*\}/)
    if (listOfIdx === 0) {
      const chunk = sliceCall(tail, tail.indexOf('('))
      const items = []
      for (const t of chunk.split(',')) {
        const s = t.trim()
        if (!s) continue
        const lit = s.match(/^["']([A-Za-z0-9_]+)["']$/)
        if (lit) items.push(lit[1])
        else if (consts.has(s)) items.push(consts.get(s))
        else items.push(`${name}:${s}(未解析)`)
      }
      lists.set(name, items)
    } else if (filterMatch) {
      const base = lists.get(filterMatch[1]) ?? []
      const excluded = consts.get(filterMatch[2])
      lists.set(name, base.filter((c) => c !== excluded))
    }
  }
  return { consts, lists }
}

/**
 * 从订阅清单片段里取出频道名：字面量 + `WsChannel.XXX` + web 侧的 `WS_XXX` 清单常量。
 */
function channelsOf(chunk, { consts, lists }) {
  const out = []
  for (const m of chunk.matchAll(/["']([A-Za-z0-9_]+)["']/g)) out.push(m[1])
  for (const m of chunk.matchAll(/WsChannel\s*\.\s*([A-Za-z0-9_]+)/g)) {
    const name = m[1]
    if (consts.has(name)) out.push(consts.get(name))
    else if (lists.has(name)) out.push(...lists.get(name))
    else out.push(`WsChannel.${name}(未解析)`)
  }
  // web 镜像：WS_CHANNELS_ALL → ALL，WS_UI_TOPICS → UI_TOPICS，以此类推
  for (const m of chunk.matchAll(/\bWS_([A-Z0-9_]+)\b/g)) {
    const key = m[1] === 'CHANNELS_ALL' ? 'ALL' : m[1]
    if (lists.has(key)) out.push(...lists.get(key))
    else out.push(`WS_${m[1]}(未解析)`)
  }
  return out
}


function parseWsChannels() {
  const constants = parseWsChannelConstants()
  const broadcast = new Set()
  const contractDir = join(ROOT, 'core', 'contract')
  for (const f of walk(join(ROOT, 'core'), ['.kt'])) {
    if (f.includes(`${join('src', 'test')}`)) continue
    // 契约模块本身不广播；它的 KDoc 里有 `broadcast("x")` 这类示例，扫进来会造出假频道
    if (f.startsWith(contractDir)) continue
    const text = readFileSync(f, 'utf8')
    CORE_BROADCAST.lastIndex = 0
    let m
    while ((m = CORE_BROADCAST.exec(text)) !== null) broadcast.add(m[1])
  }

  const subscribed = new Map() // channel → 声明位置
  const addSub = (ch, where) => {
    if (!subscribed.has(ch)) subscribed.set(ch, new Set())
    subscribed.get(ch).add(where)
  }
  /**
   * 订阅清单可能是 `listOf(...)` / `[...]`，也可能直接是一个清单常量
   * （T11 后 app 侧就是 `val DEFAULT_TOPICS = WsChannel.UI_TOPICS`）。
   * 所以：括号形式按配平取实参，否则取声明所在行的剩余部分。
   */
  const scan = (file, headRe, open, close) => {
    if (!existsSync(file)) return
    const text = stripComments(readFileSync(file, 'utf8'))
    headRe.lastIndex = 0
    let m
    while ((m = headRe.exec(text)) !== null) {
      const after = m.index + m[0].length
      const chunk = m[0].endsWith(open)
        ? sliceCall(text, after - 1, open, close)
        : text.slice(after, text.indexOf('\n', after) === -1 ? undefined : text.indexOf('\n', after))
      for (const ch of channelsOf(chunk, constants)) addSub(ch, relative(ROOT, file))
    }
  }
  scan(
    join(ROOT, 'app', 'data', 'src', 'main', 'java', 'com', 'ufi_axis', 'data', 'repository', 'WebSocketRepository.kt'),
    APP_TOPICS_HEAD, '(', ')'
  )
  scan(join(ROOT, 'web', 'src', 'stores', 'websocket.ts'), WEB_TOPICS_HEAD, '[', ']')


  return { broadcast, subscribed }
}

// ───────────────────────── 比对 ─────────────────────────


/**
 * core 是否存在与 clientPath 对应的路由。
 * 按后缀匹配：clientPath 去掉领头的 `api/` 后，看是否有 core 路由以它结尾。
 */
function coreHas(corePaths, clientPath) {
  if (corePaths.has(clientPath)) return true
  const tail = clientPath.replace(/^api\//, '')
  if (!tail) return false
  for (const cp of corePaths.keys()) {
    if (cp === tail || cp.endsWith('/' + tail)) return true
  }
  // 尾通配（Ktor `{name...}`）：core 一条路由服务它前缀下的任意多段。
  // 例：core `api/debug-logs/files/:p*` 覆盖文档写的 `files/{date}/{file}`。
  // 前缀为空的 `:p*`（HttpServer 的 SPA 兜底 `/{path...}`）必须跳过，
  // 否则它会吞掉所有路径，P0 永远为空 —— 校验器就失效了。
  for (const cp of corePaths.keys()) {
    if (!cp.endsWith(':p*')) continue
    const prefix = cp.slice(0, -3).replace(/\/$/, '')
    if (!prefix) continue
    const bare = prefix.replace(/^api\//, '')
    for (const [p, pre] of [[clientPath, prefix], [tail, bare]]) {
      if (pre && (p === pre || p.startsWith(pre + '/'))) return true
    }
  }

  // 路径参数位置差异（如 core 用 {id}、客户端拼具体值）：按段数 + 非参数段比对。
  // 注意：必须要求至少有一个**字面量段**对上，否则任意单段路径都会被
  // 「以参数结尾的 core 路由」（如 device/{key}）吞掉，造成漏报。
  const tailSegs = tail.split('/')
  for (const cp of corePaths.keys()) {
    const segs = cp.split('/')
    if (segs.length < tailSegs.length) continue
    const window = segs.slice(segs.length - tailSegs.length)
    let ok = true
    let literalMatches = 0
    for (let i = 0; i < tailSegs.length; i++) {
      if (window[i] === tailSegs[i]) {
        if (tailSegs[i] !== ':p') literalMatches++
        continue
      }
      if (window[i] === ':p' || tailSegs[i] === ':p') continue
      ok = false
      break
    }
    if (ok && literalMatches > 0) return true
  }
  return false
}

/**
 * DeviceFields 镜像校验：`core/contract/DeviceFields.kt` ↔ `web/src/api/contract.ts`。
 *
 * 为什么需要：core 对外的设备字段名是冻结契约（设备侧 goform 字段怎么改都不影响它），
 * 而 TS 侧那份是**手抄**镜像。端点差集校验管不到字段名，手抄一旦漂移就静默失效——
 * 于是"手抄的代价由校验器兜住"这句话在字段这件事上是空的。这里把它补上。
 *
 * Kotlin 侧形如：
 *   object DeviceSettings { const val FOO = "foo"; … val ALL = listOf(FOO, …) }
 * TS 侧形如：
 *   deviceSettings: { foo: 'foo', …, all: ['foo', …] }
 * 分组名按首字母大小写对应（DeviceSettings ↔ deviceSettings）。
 */
function parseDeviceFields() {
  const ktFile = join(ROOT, 'core', 'contract', 'src', 'main', 'kotlin', 'com', 'ufi_axis_core', 'contract', 'DeviceFields.kt')
  const tsFile = join(ROOT, 'web', 'src', 'api', 'contract.ts')
  const kt = new Map() // 'deviceSettings.all' -> string[]
  const ts = new Map()
  if (!existsSync(ktFile) || !existsSync(tsFile)) return { kt, ts, present: false }

  // ── Kotlin ──
  const ktText = stripComments(readFileSync(ktFile, 'utf8'))
  const objRe = /\bobject\s+(\w+)\s*\{/g
  let m
  while ((m = objRe.exec(ktText))) {
    const name = m[1]
    if (name === 'DeviceFields') continue // 顶层对象，只看内部分组
    const body = sliceCall(ktText, m.index + m[0].length - 1, '{', '}')
    const consts = new Map()
    for (const c of body.matchAll(/\bconst\s+val\s+(\w+)\s*(?::\s*\w+)?\s*=\s*"([^"]*)"/g)) {
      consts.set(c[1], c[2])
    }
    for (const l of body.matchAll(/\bval\s+(ALL|ITEM_ALL)\s*=\s*listOf\s*\(/g)) {
      const args = sliceCall(body, l.index + l[0].length - 1, '(', ')')
      const values = args
        .split(',')
        .map((s) => s.trim())
        .filter(Boolean)
        .map((s) => {
          const lit = s.match(/^"([^"]*)"$/)
          if (lit) return lit[1]
          return consts.has(s) ? consts.get(s) : `<未解析:${s}>`
        })
      const group = name[0].toLowerCase() + name.slice(1)
      kt.set(`${group}.${l[1] === 'ALL' ? 'all' : 'itemAll'}`, values)
    }
  }

  // ── TS ──
  const tsText = readFileSync(tsFile, 'utf8')
  const dfIdx = tsText.indexOf('export const DeviceFields')
  if (dfIdx >= 0) {
    const braceIdx = tsText.indexOf('{', dfIdx)
    const dfBody = sliceCall(stripComments(tsText.slice(braceIdx)), 0, '{', '}')
    const groupRe = /(\w+)\s*:\s*\{/g
    while ((m = groupRe.exec(dfBody))) {
      const group = m[1]
      const body = sliceCall(dfBody, m.index + m[0].length - 1, '{', '}')
      for (const l of body.matchAll(/\b(all|itemAll)\s*:\s*\[/g)) {
        const args = sliceCall(body, l.index + l[0].length - 1, '[', ']')
        const values = [...args.matchAll(/['"`]([^'"`]*)['"`]/g)].map((x) => x[1])
        ts.set(`${group}.${l[1]}`, values)
      }
    }
  }
  return { kt, ts, present: true }
}

/** 两侧字段集合求差，返回差异描述列表。 */
function diffDeviceFields({ kt, ts, present }) {
  if (!present) return []
  const out = []
  const keys = new Set([...kt.keys(), ...ts.keys()])
  for (const key of [...keys].sort()) {
    const a = kt.get(key)
    const b = ts.get(key)
    if (!a) { out.push({ key, issue: 'Kotlin 侧缺少该分组' }); continue }
    if (!b) { out.push({ key, issue: 'TS 侧缺少该分组' }); continue }
    const onlyKt = a.filter((x) => !b.includes(x))
    const onlyTs = b.filter((x) => !a.includes(x))
    const unresolved = a.filter((x) => x.startsWith('<未解析:'))
    if (onlyKt.length || onlyTs.length || unresolved.length) {
      out.push({
        key,
        issue: [
          onlyKt.length ? `只在 Kotlin: ${onlyKt.join(', ')}` : '',
          onlyTs.length ? `只在 TS: ${onlyTs.join(', ')}` : '',
          unresolved.length ? `常量未解析: ${unresolved.join(', ')}` : '',
        ].filter(Boolean).join(' | '),
      })
    }
  }
  return out
}

/**
 * 设备侧字段名泄漏检查：core 的 route 源码里不应出现设备（goform）原始字段名字面量。
 * 读取的字段名要么来自 DeviceFields 常量，要么发生在 device-schema 的 profile 里。
 *
 * **P0（阶段 4.4 起阻断）**：阶段 1/2 的存量已清零，新出现一个就是新的泄漏。
 * 例外见 [REQUEST_PARAM_ALLOW]。
 */
const GOFORM_FIELD_HINT = /"((?:wifi_chip\d|Lte_|lte_|Nr_|nr_|Z5g_|data_volume_|dhcp[A-Z]|sleep_sys|wan_auto_clear)[A-Za-z0-9_]*)"/g
const LEAK_WHITELIST = [/DeviceFields\.kt$/, /device-schema/, /Profile\.kt$/]

/**
 * 长得像设备字段、实际是**我们自己 API 的请求体参数名**，不是 goform 响应字段。
 * 请求参数的归一化由 device-schema 的 `WriteSpec` 负责（值域收敛在 profile 里），
 * route 读自己的入参名是正常的，不算泄漏。
 */
const REQUEST_PARAM_ALLOW = new Set(['lte_bands', 'nr_bands'])

function scanFieldLeaks() {
  const dir = join(ROOT, 'core', 'api', 'src', 'main')
  if (!existsSync(dir)) return []
  const hits = []
  for (const f of walk(dir, ['.kt'])) {
    if (LEAK_WHITELIST.some((re) => re.test(f))) continue
    const text = stripComments(readFileSync(f, 'utf8'))
    const names = new Set(
      [...text.matchAll(GOFORM_FIELD_HINT)].map((x) => x[1]).filter((n) => !REQUEST_PARAM_ALLOW.has(n))
    )
    if (names.size) hits.push({ file: f.replace(ROOT, '').replace(/\\/g, '/'), fields: [...names].sort() })
  }
  return hits.sort((a, b) => a.file.localeCompare(b.file))
}

function main() {

  const core = parseCore()
  const app = parseApp()
  const web = parseWeb()
  const { found: docs, negative: docsNegative } = parseDocs()


  const missingInCore = [] // P0：客户端/文档有，core 没有
  const seen = new Set()
  for (const [src, map] of [['app', app], ['web', web], ['docs', docs]]) {
    for (const [p, files] of map) {
      if (coreHas(core, p)) continue
      const key = p
      const existing = missingInCore.find((x) => x.path === key)
      if (existing) {
        existing.sources.push({ src, files: [...files] })
      } else {
        missingInCore.push({ path: key, sources: [{ src, files: [...files] }] })
      }
      seen.add(key)
    }
  }

  // core 有但三端都没引用（仅提示，可能是内部/预留端点）
  const unusedInCore = []
  const clientPaths = new Set([...app.keys(), ...web.keys(), ...docs.keys()])
  for (const cp of core.keys()) {
    if (KNOWN_UNREFERENCED.has(cp)) continue
    let used = false

    for (const c of clientPaths) {
      if (coreHas(new Map([[cp, true]]), c)) { used = true; break }
    }
    if (!used) unusedInCore.push({ path: cp, files: [...core.get(cp)] })
  }

  // 负面清单里的端点如果 core 真的实现了，说明清单过期，应把它写回正文。
  // 这里**必须**用精确比对而不是 coreHas()：coreHas 的段窗口匹配是为「客户端把
  // {name} 填成具体值」设计的，它会让 `/api/adb/start` 命中 core 的
  // `api/tunnel/frp/config/{name}/start`（参数段通配 + 尾段 start 字面量相同），
  // 于是负面清单里那些确实不存在的端点会被误报成「core 已实现」。
  // 负面清单写的是字面路径，不存在参数代入，精确比对才是正确语义。
  const staleNegative = []
  for (const p of docsNegative.keys()) {
    if (core.has(p)) staleNegative.push(p)
  }

  // WS：客户端订阅的频道必须 ⊆ core 实际广播的频道
  const { broadcast: wsBroadcast, subscribed: wsSubscribed } = parseWsChannels()
  const wsUnbroadcast = []
  for (const [ch, wheres] of wsSubscribed) {
    if (!wsBroadcast.has(ch)) wsUnbroadcast.push({ channel: ch, files: [...wheres] })
  }
  const wsUnsubscribed = [...wsBroadcast].filter((ch) => !wsSubscribed.has(ch)).sort()

  // DeviceFields：Kotlin ↔ TS 手抄镜像必须一致（字段名漂移会静默打断前端读取）
  const deviceFieldDiff = diffDeviceFields(parseDeviceFields())
  // core route 里的设备侧字段名字面量（阶段 4.4 起 P0 阻断）
  const fieldLeaks = scanFieldLeaks()

  const result = {
    counts: { core: core.size, app: app.size, web: web.size, docs: docs.size },
    p0_missing_in_core: missingInCore.sort((a, b) => a.path.localeCompare(b.path)),
    p0_ws_unbroadcast: wsUnbroadcast.sort((a, b) => a.channel.localeCompare(b.channel)),
    p0_device_fields_drift: deviceFieldDiff,
    p0_goform_field_leaks: fieldLeaks,
    p1_stale_negative: staleNegative.sort((a, b) => a.localeCompare(b)),
    info_ws_unsubscribed: wsUnsubscribed,
    info_unused_in_core: unusedInCore.sort((a, b) => a.path.localeCompare(b.path)),
  }



  if (JSON_OUT) {
    console.log(JSON.stringify(result, null, 2))
  } else {
    console.log('=== API 契约校验 ===')
    console.log(
      `端点数量：core=${result.counts.core} app=${result.counts.app} web=${result.counts.web} docs=${result.counts.docs}`
    )
    console.log('')
    console.log(`【P0】客户端/文档声明但 core 不存在（${result.p0_missing_in_core.length}）`)
    if (result.p0_missing_in_core.length === 0) {
      console.log('  （无）')
    } else {
      for (const it of result.p0_missing_in_core) {
        const srcs = it.sources.map((s) => s.src).join(',')
        console.log(`  - /${it.path}   [${srcs}]`)
        for (const s of it.sources) {
          for (const f of s.files.slice(0, 3)) console.log(`      ${s.src}: ${f}`)
        }
      }
    }
    console.log('')
    console.log(`【P0】客户端订阅了 core 从不广播的 WS 频道（${result.p0_ws_unbroadcast.length}）`)
    if (result.p0_ws_unbroadcast.length === 0) {
      console.log('  （无）')
    } else {
      for (const it of result.p0_ws_unbroadcast) {
        console.log(`  - ${it.channel}   (${it.files.join(', ')})`)
      }
    }
    console.log('')
    console.log(`【提示】core 广播但两端都没订阅（${result.info_ws_unsubscribed.length}）`)
    console.log(`  ${result.info_ws_unsubscribed.join(', ') || '（无）'}`)
    console.log('')
    console.log(`【P0】DeviceFields 手抄镜像漂移（Kotlin ↔ TS，${result.p0_device_fields_drift.length}）`)
    if (result.p0_device_fields_drift.length === 0) {
      console.log('  （无）')
    } else {
      for (const it of result.p0_device_fields_drift) {
        console.log(`  - ${it.key}: ${it.issue}`)
      }
    }
    console.log('')
    console.log(`【P0】core route 里的设备侧字段名字面量（${result.p0_goform_field_leaks.length} 个文件）`)
    if (result.p0_goform_field_leaks.length === 0) {
      console.log('  （无）')
    } else {
      for (const it of result.p0_goform_field_leaks) {
        console.log(`  - ${it.file}`)
        console.log(`      ${it.fields.slice(0, 8).join(', ')}${it.fields.length > 8 ? ` … 共 ${it.fields.length} 个` : ''}`)
      }
    }
    console.log('')
    console.log(`【P1】负面清单已过期（core 已实现，${result.p1_stale_negative.length}）`)

    if (result.p1_stale_negative.length === 0) {
      console.log('  （无）')
    } else {
      for (const p of result.p1_stale_negative) console.log(`  - /${p}`)
    }
    console.log('')
    console.log(`【提示】core 存在但无任何端引用（${result.info_unused_in_core.length}）`)
    for (const it of result.info_unused_in_core.slice(0, 40)) {
      console.log(`  - /${it.path}   (${it.files[0]})`)
    }
    if (result.info_unused_in_core.length > 40) {
      console.log(`  ... 其余 ${result.info_unused_in_core.length - 40} 条省略（用 --json 查看全部）`)
    }
  }

  process.exit(
    result.p0_missing_in_core.length +
      result.p0_ws_unbroadcast.length +
      result.p0_device_fields_drift.length +
      result.p0_goform_field_leaks.length >
      0
      ? 1
      : 0
  )

}

main()
