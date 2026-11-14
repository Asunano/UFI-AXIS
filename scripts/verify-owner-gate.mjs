#!/usr/bin/env node
/**
 * 主人密码门（OwnerGate）的**端到端回归脚本**。
 *
 * ## 为什么要有它
 *
 * `OwnerGateCoverageTest` 只能证明「代码里有门、门登记在表里」—— 证明不了
 * 「打开开关之后，某个端点真的会回 401」。这类事只有对着跑起来的 core 打一遍才知道。
 * 本脚本就是那一遍：自己完成配对（ECDSA P-256 签挑战）、给每个 /api 请求签名，
 * 然后逐条断言门的三种结果（放行 / 401 缺密码 / 401 错密码）与两个条件门。
 * 静态那部分由 `verify-api-contract.mjs` 与单测覆盖，两者互补。
 *
 * ## 不能进 CI
 *
 * 它需要一台真的跑着 core 的设备（或模拟器）并且能访问到 HTTP 端口，CI 里没有。
 * 定位是「改完门禁之后的手动回归」。
 *
 * ## 会真的动设备（请知情）
 *
 * - `POST /api/config/reset {scopes:["logging"]}` —— 只恢复 logging 域
 * - `DELETE /api/debug-logs/files` —— 删掉设备上的落盘日志
 * - `POST /api/shell/exec {"command":"id"}` —— 跑一条只读命令
 * - `PUT /api/config {goform_ip}` 改到 `10.9.9.9` 再改回来
 *
 * 加 `--no-destructive` 可跳过前三类（门的行为仍会被验证，只是不真的执行）。
 *
 * ## 用法
 *
 * ```bash
 * # 模拟器：先把 core 的 8088 转发出来
 * adb -s emulator-5554 forward tcp:18088 tcp:8088
 * node scripts/verify-owner-gate.mjs --base http://127.0.0.1:18088 --password '<配对密码>'
 *
 * # 真机（同网段）
 * node scripts/verify-owner-gate.mjs --base http://192.168.0.1:8088 --password '<配对密码>'
 * ```
 *
 * | 参数 | 说明 |
 * |---|---|
 * | `--password <pw>` | **必需**。设备配对密码（配对时设的也是它，门校验的也是它） |
 * | `--base <url>` | 默认 `http://127.0.0.1:18088` |
 * | `--state <path>` | 会话（私钥 + token）落盘位置，默认 `.probe/owner-gate-session.json` |
 * | `--keep` | 跑完不解配对。默认：**只在本次脚本建立配对时**，结束时解除并删状态文件 |
 * | `--no-destructive` | 跳过真会改设备的三条 |
 *
 * 退出码：0 = 全过；1 = 有用例失败；2 = 环境不通或前置条件不满足。
 *
 * ## 安全
 *
 * 私钥只在本地 `.probe/`（已在 .gitignore 里）且权限 0600；密码只在内存里用，
 * 不打印、不落盘、不进任何请求之外的日志。
 */

import { createSign, generateKeyPairSync, randomBytes } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// ── 参数 ─────────────────────────────────────────────────────────────────
const argv = process.argv.slice(2);
const arg = (name, fallback = null) => {
  const i = argv.indexOf(name);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : fallback;
};
const flag = (name) => argv.includes(name);

const BASE = arg('--base', 'http://127.0.0.1:18088').replace(/\/$/, '');
const PASSWORD = arg('--password');
// 默认落在仓库根的 .probe/（已 gitignore），不随 CWD 漂移
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const STATE = resolve(arg('--state', `${ROOT}/.probe/owner-gate-session.json`));
const KEEP = flag('--keep');
const SKIP_DESTRUCTIVE = flag('--no-destructive');

if (!PASSWORD) {
  console.error('缺少 --password（设备配对密码）。用法见文件头。');
  process.exit(2);
}

// ── 会话状态（私钥 + token）───────────────────────────────────────────────
function loadState() {
  if (!existsSync(STATE)) return null;
  try {
    return JSON.parse(readFileSync(STATE, 'utf8'));
  } catch {
    return null;
  }
}

function saveState(state) {
  mkdirSync(dirname(STATE), { recursive: true });
  writeFileSync(STATE, JSON.stringify(state), { mode: 0o600 });
}

function newKeyPair() {
  const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  return {
    privatePem: privateKey.export({ type: 'pkcs8', format: 'pem' }),
    publicB64: publicKey.export({ type: 'spki', format: 'der' }).toString('base64'),
  };
}

function signWith(privatePem, data) {
  const signer = createSign('sha256');
  signer.update(Buffer.from(data));
  return signer.sign(privatePem).toString('base64');
}

// ── HTTP ────────────────────────────────────────────────────────────────
async function raw(method, path, { body, headers, binary } = {}) {
  const res = await fetch(BASE + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(headers || {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (binary) return { status: res.status, bytes: (await res.arrayBuffer()).byteLength };
  const text = await res.text();
  let parsed;
  try {
    parsed = text ? JSON.parse(text) : {};
  } catch {
    parsed = { _raw: text.slice(0, 120) };
  }
  return { status: res.status, body: parsed };
}

/**
 * 带 Bearer + 设备签名的 /api 请求。
 *
 * canonical 与 core 的 `DeviceAuth.canonicalString` 逐字一致：`METHOD\nURI\nTS\nNONCE`，
 * ECDSA-SHA256、DER、base64。
 *
 * @param withBodyPassword 把配对密码塞进 body 的 `password` 字段
 * @param withHeaderPassword 走 `X-Owner-Password` 头
 */
async function api(method, path, { body, withBodyPassword, withHeaderPassword, binary } = {}) {
  const state = loadState();
  if (!state?.token || !state?.privatePem) throw new Error('没有可用会话（先重新配对）');
  const ts = String(Date.now());
  const nonce = randomBytes(16).toString('hex');
  const signature = signWith(state.privatePem, `${method.toUpperCase()}\n${path}\n${ts}\n${nonce}`);

  const headers = {
    Authorization: `Bearer ${state.token}`,
    'X-Timestamp': ts,
    'X-Nonce': nonce,
    'X-Signature': signature,
  };
  if (withHeaderPassword) headers['X-Owner-Password'] = PASSWORD;

  const payload = withBodyPassword ? { ...(body ?? {}), password: PASSWORD } : body;
  return raw(method, path, { body: payload, headers, binary });
}

// ── 配对 ────────────────────────────────────────────────────────────────
async function pair() {
  const info = await raw('GET', '/pairing/info');
  if (info.status !== 200) {
    throw new Error(`/pairing/info 失败（${info.status}）：端口不通，或设备拒绝了请求`);
  }
  // 配对码只在「设备尚未初始化」时下发；设过配对密码之后，新客户端凭**密码**即可配对
  // （core 的 `loginByPassword` 分支），此时 `pairing_code` 是空串。
  const pairingCode = info.body?.pairing_code || '';
  const passwordInitialized = info.body?.has_default_password === false;
  if (!pairingCode && !passwordInitialized) {
    throw new Error(
      `既没有配对码（设备还没初始化完），配对密码也还是默认值 —— 这种情况只能先在设备上完成首次初始化。`
    );
  }
  const challengeRes = await raw('POST', '/pairing/challenge');
  if (challengeRes.status !== 200 || !challengeRes.body?.challenge) {
    throw new Error(`/pairing/challenge 失败（${challengeRes.status}）：${JSON.stringify(challengeRes.body)}`);
  }
  const challenge = challengeRes.body.challenge;
  const keys = newKeyPair();

  const confirm = await raw('POST', '/pairing/confirm', {
    body: {
      pairing_code: pairingCode,
      device_pubkey: keys.publicB64,
      challenge,
      signature: signWith(keys.privatePem, challenge),
      password: PASSWORD,
      device_name: 'owner-gate-e2e',
    },
  });
  if (confirm.status !== 200 || !confirm.body?.token) {
    throw new Error(`/pairing/confirm 失败（${confirm.status}）：${JSON.stringify(confirm.body)}`);
  }
  saveState({ ...keys, token: confirm.body.token, createdPairing: true });
  console.log(`已配对：${BASE}（设备名 owner-gate-e2e）`);
}

// ── 断言 ────────────────────────────────────────────────────────────────
const results = [];
function expect(name, want, res, note) {
  const ok = Array.isArray(want) ? want.includes(res.status) : res.status === want;
  results.push({ ok, name, want, got: res.status, note: note ?? '' });
}
function expectCode(name, code, res) {
  const ok = res.status === 401 && res.body?.code === code;
  results.push({ ok, name, want: 401, got: `${res.status}/${res.body?.code ?? '-'}`, note: '' });
}

// ── 主流程 ──────────────────────────────────────────────────────────────
async function run() {
  const health = await raw('GET', '/health');
  if (health.status !== 200) {
    console.error(
      `连不上 ${BASE}（/health → ${health.status}）。\n` +
        `  模拟器：adb -s <serial> forward tcp:18088 tcp:8088\n` +
        `  真机：确认与设备同网段、端口是 8088`
    );
    process.exit(2);
  }

  if (!loadState()?.token) await pair();

  const status0 = await api('GET', '/api/pairing/status');
  if (status0.status === 444 || status0.status === 401) {
    console.error(`现有 token 已失效（${status0.status}）。删掉 ${STATE} 后重跑以重新配对。`);
    process.exit(2);
  }
  const wasEnabled = status0.body?.owner_gate_enabled === true;

  // 1) 打开开关不需要密码
  expect('打开总开关（不带密码）', 200,
    await api('PUT', '/api/pairing/config', { body: { owner_gate_enabled: true } }));

  // 2) 门内端点：缺密码 / 错密码 / 对密码（body 与头两种取法）
  if (!SKIP_DESTRUCTIVE) {
    const missing = await api('POST', '/api/config/reset', { body: { scopes: ['logging'] } });
    expect('config/reset 缺密码', 401, missing);
    expectCode('缺密码 code = MISSING_PASSWORD', 'MISSING_PASSWORD', missing);
    expectCode('错密码 code = INVALID_PASSWORD', 'INVALID_PASSWORD',
      await api('POST', '/api/config/reset', { body: { scopes: ['logging'], password: 'definitely-wrong' } }));
    expect('config/reset 对密码（body）', 200,
      await api('POST', '/api/config/reset', { body: { scopes: ['logging'] }, withBodyPassword: true }));
    expect('config/reset 对密码（请求头）', 200,
      await api('POST', '/api/config/reset', { body: { scopes: ['logging'] }, withHeaderPassword: true }));
    expect('debug-logs/files 删除缺密码', 401, await api('DELETE', '/api/debug-logs/files'));
    expect('debug-logs/files 删除带密码', 200,
      await api('DELETE', '/api/debug-logs/files', { withHeaderPassword: true }));
  }

  expect('apps/uninstall 缺密码', 401,
    await api('POST', '/api/apps/uninstall', { body: { packageName: 'com.ufi_axis.nonexistent' } }));
  expect('backup/export 缺密码', 401, await api('POST', '/api/backup/export', { body: { encrypted: true } }));
  expect('backup/export 带密码 → 200', 200, await api('POST', '/api/backup/export', {
    body: { encrypted: true, passphrase: 'abcdef123456' }, withHeaderPassword: true, binary: true,
  }));
  expect('programs/x/secret 缺密码', 401, await api('POST', '/api/programs/nonexistent/secret'));
  expect('programs/x/secret 门后 → 404', 404,
    await api('POST', '/api/programs/nonexistent/secret', { withHeaderPassword: true }));
  expect('blacklist/clear 缺密码', 401, await api('POST', '/api/pairing/blacklist/clear', { body: {} }));
  expect('shell/exec 缺密码', 401, await api('POST', '/api/shell/exec', { body: { command: 'id' } }));
  expect('shell/exec 对密码 → 200', 200,
    await api('POST', '/api/shell/exec', { body: { command: 'id' }, withBodyPassword: true }));
  expect('tasks 建 custom_shell 缺密码', 401,
    await api('POST', '/api/tasks', { body: { actionType: 'custom_shell', command: 'id', hour: 3, minute: 0 } }));

  // 3) 不在门内：门开着也不需要密码
  expect('PUT pairing/config 改配额（非关开关）', 200,
    await api('PUT', '/api/pairing/config', { body: { pairing_max_devices: 3 } }));

  // 4) 条件门：重指向
  const cfg = await api('GET', '/api/config');
  const goformIp = cfg.body?.goform_ip || '192.168.0.1';
  expect('PUT config 回写未变的 goform_ip', 200, await api('PUT', '/api/config', { body: { goform_ip: goformIp } }));
  if (!SKIP_DESTRUCTIVE) {
    expect('PUT config 改 goform_ip 缺密码', 401, await api('PUT', '/api/config', { body: { goform_ip: '10.9.9.9' } }));
    expect('PUT config 改 goform_ip 带密码', 200,
      await api('PUT', '/api/config', { body: { goform_ip: '10.9.9.9' }, withBodyPassword: true }));
    expect('PUT config 改回 goform_ip', 200,
      await api('PUT', '/api/config', { body: { goform_ip: goformIp }, withBodyPassword: true }));
  }

  // 5) 条件门：关掉总开关
  expect('关掉总开关 缺密码', 401, await api('PUT', '/api/pairing/config', { body: { owner_gate_enabled: false } }));
  expect('关掉总开关 带密码', 200,
    await api('PUT', '/api/pairing/config', { body: { owner_gate_enabled: false }, withBodyPassword: true }));

  // 6) 关掉之后不再拦
  const off = await api('POST', '/api/apps/uninstall', { body: { packageName: 'com.ufi_axis.nonexistent' } });
  results.push({ ok: off.status !== 401, name: '关掉后 apps/uninstall 不再 401', want: '≠401', got: off.status, note: '' });

  // 复原成跑之前的样子
  if (wasEnabled) {
    await api('PUT', '/api/pairing/config', { body: { owner_gate_enabled: true }, withBodyPassword: true });
  }
}

// ── 收尾 ────────────────────────────────────────────────────────────────
let exitCode = 0;
try {
  await run();
} catch (e) {
  console.error('执行中断：', e?.message || e);
  exitCode = 2;
}

for (const r of results) {
  const mark = r.ok ? '✔' : '✘';
  console.log(`${mark} ${r.name.padEnd(38)} 期望 ${r.want} 实际 ${r.got}${r.ok ? '' : '  ' + r.note}`);
}
const failed = results.filter((r) => !r.ok).length;
if (results.length) console.log(`\n${results.length - failed}/${results.length} 通过`);

// 只解除「本次脚本建立的配对」，别把机主自己的设备踢掉
const final = loadState();
if (final?.createdPairing && !KEEP && !exitCode) {
  const res = await api('POST', '/api/pairing/unpair', { withBodyPassword: true }).catch(() => null);
  if (res?.status === 200) console.log('已解除本次测试建立的配对');
  try {
    rmSync(STATE);
  } catch {
    /* 忽略 */
  }
}

process.exit(exitCode || (failed ? 1 : 0));
