#!/usr/bin/env node
/**
 * 跨端诊断项**归属**自检（2026-10-14）。
 *
 * 用法（仓库根目录）：
 *   node scripts/check-diagnostics-scope.mjs          # 校验（不一致退出码 1）
 *   node scripts/check-diagnostics-scope.mjs --list   # 顺带打印跨端对照表
 *
 * ## 它解决什么问题
 * 两端的诊断中心各有一份清单：
 *   web  web/src/views/diagnostics/diagnosticRegistry.ts
 *   app  app/feature-settings/src/main/java/com/ufi_axis/ui/diagnose/DiagnosticRegistry.kt
 * 其中"查的是设备 / core 自己的东西"的那些（两端都标 `core`）**必须两端都有** ——
 * 少一端就意味着同一台设备在两个客户端上看到的结论不一样。而这件事此前只存在于人脑里：
 * 加一项 core 类检查时，**没有任何东西会提醒你"另一端也要加"**。本脚本就是那条提醒。
 *
 * ## 判据：`scope` + 下面这张 [CORE_PAIRS] 映射表
 *  · 注册表里标 `core` 的项**必须**在 [CORE_PAIRS] 里出现一次，且映射到另一端
 *    **存在、也标 core** 的项；
 *  · 标 `client` 的项**必须不**出现在 [CORE_PAIRS] 里（它只在本端才有意义）；
 *  · [CORE_PAIRS] 里任何一端不存在的 id = 陈旧登记（改名 / 删项之后没同步）。
 * 于是"core 项两端齐全"从一句口头约定变成一条可执行的断言，缺哪一项、缺在哪一端都报出来。
 *
 * ## 为什么两端 id 不同名也能对上
 * id 是**本端**的稳定键（结果缓存、输入框状态都用它），历史上两端各自起名
 * （web 的 `storage` = app 的 `env.monitorstorage`）。所以映射只在这张表里登记一次，
 * 而不是要求两端改名 —— 改名会打断两端的缓存键与用户习惯，收益不抵改动。
 *
 * ## 为什么用正则抽取，而不是解析 TS / Kotlin
 * 完整解析要引依赖（本仓不引入新依赖），而且会被两端的语法演进持续打脸。
 * 这里只要两样东西：每个 `id`，以及同一个 item 里的 `scope`。抽取口径是
 * "以 `id` 为锚，取它到**下一个 id** 之间的文本当这一项的身体，在其中找 `scope`"，
 * 所以字段顺序、换行、缩进、注释都不影响结果。
 * ⚠ 抽不到 `scope`（或一个 id 都抽不到）一律报**解析失败**并退出 1 ——
 * **绝不静默跳过**：静默跳过会让这个门禁在格式变化之后变成一句假的"通过"。
 */
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const LIST = process.argv.includes('--list')

const WEB_FILE = 'web/src/views/diagnostics/diagnosticRegistry.ts'
const APP_FILE =
  'app/feature-settings/src/main/java/com/ufi_axis/ui/diagnose/DiagnosticRegistry.kt'

/** 抽取规则：`id` 的写法两端不同（TS 的 `id: 'x'` / Kotlin 的 `id = "x"`）。 */
const WEB_RULES = { id: /\bid:\s*'([^']+)'/g, scope: /\bscope:\s*'(core|client)'/ }
const APP_RULES = { id: /\bid\s*=\s*"([^"]+)"/g, scope: /\bscope\s*=\s*DiagnosticScope\.(CORE|CLIENT)\b/ }

/**
 * 跨端对照表：`[web id, app id, 中文名]`。**19 对 core 类检查**（2026-10-14 补齐后 1:1）。
 *
 * 中文名只用于报错文案（"缺的是哪一项"要比 id 好读）。新增一项 core 类检查时，
 * 两端都要加，**并且在这里加一行** —— 忘了加，本脚本会以"未登记跨端映射"报错。
 */
const CORE_PAIRS = [
  ['capabilities', 'env.capabilities', '设备能力集'],
  ['root-channel', 'env.shell', 'root / shell 通道'],
  ['selinux', 'env.selinux', 'SELinux'],
  ['magisk', 'env.magisk', '特权 shell 探测'],
  ['storage', 'env.monitorstorage', '监控数据库占用'],
  ['disk-usage', 'env.storage', '数据分区占用'],
  ['media-permission', 'env.media', '媒体库权限'],
  ['core-permission', 'env.corepermission', 'core 自身的权限'],
  ['cpu-probe', 'env.cpuprobe', 'CPU 探针'],
  ['net-ping', 'network.ping', 'ping'],
  ['net-dns', 'network.dns', 'DNS 解析'],
  ['net-tcp', 'network.tcp', 'TCP 探测'],
  ['net-traceroute', 'network.traceroute', 'traceroute'],
  ['service-status', 'service.status', '服务状态与崩溃'],
  ['qos-status', 'service.qos', 'QoS 状态'],
  ['cache-health', 'service.cachehealth', '响应缓存健康'],
  ['notify-channels', 'service.notifychannels', '通知渠道配置'],
  ['full-snapshot', 'service.snapshot', '全量诊断快照'],
  ['debug-logs', 'service.debuglog', '调试日志'],
]

/** 解析失败（格式变了 / 文件不在）时立刻退出：此时"通过"是假的。 */
function bail(message) {
  console.error(`✗ ${message}`)
  console.error('  本脚本按"id → 下一个 id 之间找 scope"抽取（见文件头）。若注册表换了写法，')
  console.error('  请同步更新本文件的 WEB_RULES / APP_RULES —— 不要让它静默通过。')
  process.exit(1)
}

/** 从一个注册表源码里抽出 `id → scope`。抽不到就抛（不返回半份结果）。 */
function extract(relPath, rules) {
  const abs = join(ROOT, relPath)
  if (!existsSync(abs)) {
    bail(`${relPath} 不存在 —— 这个自检要同时看 web/ 与 app/，必须在完整仓库里跑`)
  }
  const text = readFileSync(abs, 'utf8')
  const ids = [...text.matchAll(rules.id)]
  if (ids.length === 0) bail(`${relPath} 里一个 id 都没抽到（注册表格式变了？）`)

  const items = new Map()
  for (let i = 0; i < ids.length; i++) {
    const id = ids[i][1]
    const body = text.slice(ids[i].index, i + 1 < ids.length ? ids[i + 1].index : text.length)
    const scope = rules.scope.exec(body)
    if (!scope) {
      bail(`${relPath} 的 "${id}" 里抽不到 scope —— 每一项检查都必须显式声明归属（core / client）`)
    }
    if (items.has(id)) bail(`${relPath} 里 id "${id}" 出现了两次 —— id 是结果缓存与输入框状态的键，必须唯一`)
    items.set(id, scope[1].toLowerCase())
  }
  return items
}

const web = extract(WEB_FILE, WEB_RULES)
const app = extract(APP_FILE, APP_RULES)

const problems = []
const pairsByWeb = new Map()
const pairsByApp = new Map()
for (const [webId, appId, name] of CORE_PAIRS) {
  if (pairsByWeb.has(webId)) problems.push(`CORE_PAIRS 里 web id "${webId}" 登记了两次`)
  if (pairsByApp.has(appId)) problems.push(`CORE_PAIRS 里 app id "${appId}" 登记了两次`)
  pairsByWeb.set(webId, [webId, appId, name])
  pairsByApp.set(appId, [webId, appId, name])
}

/** 注册表 → 映射表方向：core 必须登记，client 必须不登记。 */
function checkRegistry(side, items, byId) {
  for (const [id, scope] of items) {
    if (scope === 'core' && !byId.has(id)) {
      problems.push(
        `${side} 的 core 项 "${id}" 没有登记跨端映射 —— ` +
          `要么去另一端补上对应项并在本脚本的 CORE_PAIRS 里加一行，要么把它改成 client`
      )
    }
    if (scope === 'client' && byId.has(id)) {
      problems.push(`${side} 的 "${id}" 标的是 client，却出现在 CORE_PAIRS 里 —— 两者必须一致`)
    }
  }
}

checkRegistry('web', web, pairsByWeb)
checkRegistry('app', app, pairsByApp)

/** 映射表 → 注册表方向：两端都必须真实存在，且都标 core。 */
for (const [webId, appId, name] of CORE_PAIRS) {
  if (!web.has(webId)) {
    problems.push(`CORE_PAIRS 里的 web id "${webId}"（${name}）在 web 注册表里不存在 —— 改名 / 删项后忘了同步映射表`)
  } else if (web.get(webId) !== 'core') {
    problems.push(`CORE_PAIRS 把 web 的 "${webId}"（${name}）当 core 项，但注册表标的是 ${web.get(webId)}`)
  }
  if (!app.has(appId)) {
    problems.push(`CORE_PAIRS 里的 app id "${appId}"（${name}）在 app 注册表里不存在 —— 改名 / 删项后忘了同步映射表`)
  } else if (app.get(appId) !== 'core') {
    problems.push(`CORE_PAIRS 把 app 的 "${appId}"（${name}）当 core 项，但注册表标的是 ${app.get(appId)}`)
  }
}

const count = (items, scope) => [...items.values()].filter((s) => s === scope).length

if (problems.length > 0) {
  console.error(`✗ 跨端归属不一致（${problems.length} 处）：`)
  for (const p of problems) console.error(`  · ${p}`)
  console.error('')
  console.error('  口径：scope=core 的项两端都必须有（本脚本的 CORE_PAIRS 是唯一映射真源）；')
  console.error('        scope=client 的项只在本端有意义，不该出现在映射表里。')
  process.exit(1)
}

console.log(`✓ web 注册表：${web.size} 项（core ${count(web, 'core')} / client ${count(web, 'client')}）`)
console.log(`✓ app 注册表：${app.size} 项（core ${count(app, 'core')} / client ${count(app, 'client')}）`)
console.log(`✓ core 类检查两端 1:1 对齐：${CORE_PAIRS.length} 对（映射表 = ${CORE_PAIRS.length} 行，无未登记项、无陈旧项）`)

if (LIST) {
  console.log('')
  console.log('跨端对照表（web id → app id）：')
  for (const [webId, appId, name] of CORE_PAIRS) {
    console.log(`  ${webId.padEnd(18)} → ${appId.padEnd(22)} ${name}`)
  }
}
