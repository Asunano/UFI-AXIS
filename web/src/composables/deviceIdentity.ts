/**
 * Web 端设备身份 —— 严格设备独立性（方案 C / Y）。
 *
 * ## 为什么用不可导出密钥而不是 localStorage 里的随机 UUID
 * 随机 UUID 存在 localStorage 里，任何 XSS 或手工复制都能把「设备身份」搬到另一台机器，
 * 配对上限照样失效。这里用 `crypto.subtle.generateKey(..., extractable=false)` 生成 ECDSA
 * P-256 密钥对存进 IndexedDB：`CryptoKey` 可被结构化克隆持久化，但**私钥字节 JS 永远取不到**，
 * 因此身份无法被复制到其它浏览器，只能在本浏览器内使用。
 *
 * ## 设备指纹
 * 指纹 = `base64url(SHA-256(SPKI))`，但**客户端算出的指纹仅供展示**；
 * 服务端会据上报的公钥自行重算（`DeviceAuth.fingerprintOf`），不采信客户端自报值。
 *
 * ## 签名串必须与 core 逐字节一致
 * 见 `core/common/util/DeviceAuth.kt`：
 * ```
 * METHOD \n URI \n TIMESTAMP_MS \n NONCE
 * ```
 * 请求体**不在签名内**（core 侧有 200MB 流式上传路由，鉴权拦截器无法缓冲 body 算哈希）；
 * 详细理由与残余风险见 `DeviceAuth.kt` 的类文档。
 *
 * WebCrypto 的 ECDSA 输出是 raw `r||s`（64 字节），core 会按长度归一化成 DER，
 * 所以这里**不要**自己做 DER 编码。
 *
 * ## 身份丢失
 * 用户清除站点数据 → IndexedDB 连同密钥一起消失 → 下次登录会生成新密钥、占用新的配对槽位。
 * 这是浏览器沙箱的固有限制（没有 ANDROID_ID 那样清数据后仍可复现的标识），
 * 处理方式是让用户在配对界面删除旧记录，而不是引入可伪造的浏览器指纹去"稳定"身份。
 *
 * ## http 明文访问的过渡方案（2026-08-28）
 * `crypto.subtle` **只在安全上下文可用**：`http://192.168.x.x` 下它是 `undefined`，
 * 上面整套 WebCrypto 流程会直接抛 TypeError，登录必然失败。为了让局域网 http 访问先能用，
 * 这里加了一条纯 JS 回退（`@noble/curves` 的 P-256 + `@noble/hashes` 的 SHA-256）：
 *
 * - **安全上下文（https / localhost）**：走原有 WebCrypto 路径，私钥不可导出（强保证，优先）；
 * - **明文 http**：私钥是 IndexedDB 里的 32 字节原始标量，**JS 可读** —— XSS 能把身份偷走，
 *   安全性弱于 Keystore/WebCrypto，但仍强于旧的「所有浏览器共用一个常量指纹」。
 *
 * 两条路径的对外契约完全一致（SPKI 公钥 + 同一条签名串 + base64url 签名），服务端无需区分。
 * 后续切到自签证书 HTTPS 后，浏览器自动走回 WebCrypto 分支，本回退无需删除也不会被触发；
 * 注意同一浏览器从 http 切到 https 会换成另一套密钥 = 另一台设备，需要重新配对。
 */

import { p256 } from '@noble/curves/p256';
import { sha256 } from '@noble/hashes/sha256';

const DB_NAME = 'ufi-axis-identity';
const DB_VERSION = 1;
const STORE_NAME = 'keys';
const KEY_ID = 'device-signing-key';
/** 明文 http 回退用的原始私钥（32 字节标量）。与 WebCrypto 密钥分开存，互不覆盖。 */
const KEY_ID_RAW = 'device-signing-key-raw';

const KEY_ALGORITHM: EcKeyGenParams = { name: 'ECDSA', namedCurve: 'P-256' };
const SIGN_ALGORITHM: EcdsaParams = { name: 'ECDSA', hash: 'SHA-256' };

/**
 * 当前上下文是否有可用的 WebCrypto。
 *
 * 判定用 `generateKey` 是否为函数而不是 `window.isSecureContext`：真正决定成败的是
 * `crypto.subtle` 存不存在，个别环境（旧 WebView / 代理注入）两者并不同步。
 */
const hasWebCrypto = typeof globalThis.crypto?.subtle?.generateKey === 'function';

export interface DeviceIdentity {
  /** 设备指纹（base64url(SHA-256(SPKI))，43 字符），仅供展示与日志 */
  fingerprint: string;
  /** 公钥 SPKI DER 的 base64（标准字符集），配对时上报服务端 */
  publicKeySpki: string;
}

interface StoredKeyPair {
  privateKey: CryptoKey;
  publicKey: CryptoKey;
}

// ── IndexedDB 最小封装（不引依赖，只需一个 object store） ──

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(STORE_NAME)) db.createObjectStore(STORE_NAME);
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error ?? new Error('IndexedDB 打开失败'));
  });
}

function idbGet<T>(db: IDBDatabase, key: string): Promise<T | undefined> {
  return new Promise((resolve, reject) => {
    const req = db.transaction(STORE_NAME, 'readonly').objectStore(STORE_NAME).get(key);
    req.onsuccess = () => resolve(req.result as T | undefined);
    req.onerror = () => reject(req.error ?? new Error('IndexedDB 读取失败'));
  });
}

function idbPut(db: IDBDatabase, key: string, value: unknown): Promise<void> {
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_NAME, 'readwrite');
    tx.objectStore(STORE_NAME).put(value, key);
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error ?? new Error('IndexedDB 写入失败'));
  });
}

function idbDelete(db: IDBDatabase, key: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_NAME, 'readwrite');
    tx.objectStore(STORE_NAME).delete(key);
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error ?? new Error('IndexedDB 删除失败'));
  });
}

// ── 编码工具 ──

function toBase64(bytes: ArrayBuffer | Uint8Array): string {
  const view = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  let binary = '';
  // 分块避免超长参数导致 String.fromCharCode 栈溢出（SPKI 很短，但签名拼接场景复用同一函数）
  const CHUNK = 0x8000;
  for (let i = 0; i < view.length; i += CHUNK) {
    binary += String.fromCharCode(...view.subarray(i, i + CHUNK));
  }
  return btoa(binary);
}

function toBase64Url(bytes: ArrayBuffer | Uint8Array): string {
  return toBase64(bytes).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

// ── 密钥生命周期 ──

let cachedPair: StoredKeyPair | null = null;
let cachedIdentity: DeviceIdentity | null = null;
/** 并发去重：多个请求同时触发初始化时只生成一次密钥，否则会互相覆盖导致指纹漂移 */
let loading: Promise<StoredKeyPair> | null = null;

async function loadOrCreateKeyPair(): Promise<StoredKeyPair> {
  if (cachedPair) return cachedPair;
  if (loading) return loading;

  loading = (async () => {
    const db = await openDb();
    const existing = await idbGet<StoredKeyPair>(db, KEY_ID);
    if (existing?.privateKey && existing?.publicKey) {
      cachedPair = existing;
      return existing;
    }
    // extractable=false：私钥字节永远无法导出，身份不可被复制到其它浏览器
    const generated = await crypto.subtle.generateKey(KEY_ALGORITHM, false, ['sign', 'verify']);
    const pair: StoredKeyPair = {
      privateKey: generated.privateKey,
      publicKey: generated.publicKey,
    };
    await idbPut(db, KEY_ID, pair);
    cachedPair = pair;
    return pair;
  })();

  try {
    return await loading;
  } finally {
    loading = null;
  }
}

// ── 明文 http 回退：纯 JS P-256（仅当 crypto.subtle 缺失时启用） ──

/**
 * P-256 SPKI DER 的固定前缀（26 字节）：
 * `SEQUENCE { SEQUENCE { OID ecPublicKey, OID prime256v1 }, BIT STRING { 04||X||Y } }`
 * 后面紧跟 65 字节未压缩公钥点，总长 91 字节 —— 与 WebCrypto `exportKey('spki')` 的输出等价，
 * 服务端 `DeviceAuth.fingerprintOf` / `X509EncodedKeySpec` 都按同一份字节解析。
 */
const P256_SPKI_PREFIX = new Uint8Array([
  0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01, 0x06, 0x08, 0x2a, 0x86, 0x48, 0xce,
  0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00,
]);

let cachedRawKey: Uint8Array | null = null;
/** 并发去重：同 [loading]，避免两个请求各生成一把私钥互相覆盖 */
let loadingRaw: Promise<Uint8Array> | null = null;

async function loadOrCreateRawKey(): Promise<Uint8Array> {
  if (cachedRawKey) return cachedRawKey;
  if (loadingRaw) return loadingRaw;

  loadingRaw = (async () => {
    const db = await openDb();
    const existing = await idbGet<Uint8Array>(db, KEY_ID_RAW);
    if (existing && existing.length === 32) {
      cachedRawKey = existing;
      return existing;
    }
    // crypto.getRandomValues 在非安全上下文同样可用（只有 crypto.subtle 受限）
    const priv = p256.utils.randomPrivateKey();
    await idbPut(db, KEY_ID_RAW, priv);
    cachedRawKey = priv;
    return priv;
  })();

  try {
    return await loadingRaw;
  } finally {
    loadingRaw = null;
  }
}

/** 回退路径的公钥 SPKI DER（91 字节）。 */
async function rawPublicKeySpki(): Promise<Uint8Array> {
  const priv = await loadOrCreateRawKey();
  const point = p256.getPublicKey(priv, false); // 未压缩：04||X||Y，65 字节
  const spki = new Uint8Array(P256_SPKI_PREFIX.length + point.length);
  spki.set(P256_SPKI_PREFIX, 0);
  spki.set(point, P256_SPKI_PREFIX.length);
  return spki;
}

/**
 * 回退路径签名：SHA-256 后做 ECDSA，输出 raw `r||s`（64 字节）——
 * 与 WebCrypto 输出格式一致，core 按长度归一化成 DER，两端无需分支。
 */
async function rawSign(data: Uint8Array): Promise<Uint8Array> {
  const priv = await loadOrCreateRawKey();
  return p256.sign(sha256(data), priv).toCompactRawBytes();
}

/** 取得（必要时生成）本浏览器的设备身份。 */
export async function getDeviceIdentity(): Promise<DeviceIdentity> {
  if (cachedIdentity) return cachedIdentity;
  if (!hasWebCrypto) {
    const spki = await rawPublicKeySpki();
    cachedIdentity = {
      fingerprint: toBase64Url(sha256(spki)),
      publicKeySpki: toBase64(spki),
    };
    return cachedIdentity;
  }
  const pair = await loadOrCreateKeyPair();
  const spki = await crypto.subtle.exportKey('spki', pair.publicKey);
  const digest = await crypto.subtle.digest('SHA-256', spki);
  cachedIdentity = {
    fingerprint: toBase64Url(digest),
    publicKeySpki: toBase64(spki),
  };
  return cachedIdentity;
}

/**
 * 丢弃当前设备身份（仅用于「本浏览器解除配对」这类显式操作）。
 * 注意：丢弃后再次登录会占用一个新的配对槽位。
 */
export async function resetDeviceIdentity(): Promise<void> {
  cachedPair = null;
  cachedIdentity = null;
  cachedRawKey = null;
  const db = await openDb();
  // 两条路径的密钥都删掉：用户的意图是"换掉本浏览器的身份"，不该因为
  // 当前恰好走哪条分支而留下另一条的旧密钥。
  await idbDelete(db, KEY_ID);
  await idbDelete(db, KEY_ID_RAW);
}

// ── 请求签名 ──

export interface SignedHeaders {
  'X-Timestamp': string;
  'X-Nonce': string;
  'X-Signature': string;
}

function randomNonce(): string {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  return toBase64Url(bytes);
}

/**
 * 为一次请求生成签名头。
 *
 * @param method HTTP 方法
 * @param uri **服务端可见的 path + query**（含原始百分号编码）。传完整 URL 会导致验签失败，
 *   因为 core 拿到的是 `call.request.uri`（不含 scheme/host）。
 */
export async function signRequest(method: string, uri: string): Promise<SignedHeaders> {
  const timestamp = String(Date.now());
  const nonce = randomNonce();
  const canonical = [method.toUpperCase(), uri, timestamp, nonce].join('\n');
  const payload = new TextEncoder().encode(canonical);
  const signature = hasWebCrypto
    ? await crypto.subtle.sign(SIGN_ALGORITHM, (await loadOrCreateKeyPair()).privateKey, payload)
    : await rawSign(payload);
  return {
    'X-Timestamp': timestamp,
    'X-Nonce': nonce,
    // 两条路径都输出 raw r||s（64 字节），core 会归一化成 DER —— 此处不要自行 DER 编码
    'X-Signature': toBase64Url(signature),
  };
}

/**
 * 对配对挑战签名（`POST /pairing/challenge` 拿到的 nonce 原文，不做任何额外包装）。
 * 服务端用上报的公钥验这个签名，以此证明"本浏览器持有对应私钥"。
 */
export async function signChallenge(challenge: string): Promise<string> {
  const payload = new TextEncoder().encode(challenge);
  const signature = hasWebCrypto
    ? await crypto.subtle.sign(SIGN_ALGORITHM, (await loadOrCreateKeyPair()).privateKey, payload)
    : await rawSign(payload);
  return toBase64Url(signature);
}

/**
 * 为 WebSocket 握手生成 query 参数（浏览器 WebSocket 无法自定义 Header）。
 * 签名的 URI 必须与服务端看到的一致：**只含 path，不含 query**，
 * 否则会陷入「签名覆盖包含签名自身」的循环。
 */
export async function signWsQuery(token: string, path: string): Promise<string> {
  const headers = await signRequest('GET', path);
  const params = new URLSearchParams({
    token,
    ts: headers['X-Timestamp'],
    nonce: headers['X-Nonce'],
    sig: headers['X-Signature'],
  });
  return params.toString();
}

/** 从完整 URL 或相对路径中取出服务端可见的 path + query。 */
export function toSignableUri(url: string, baseUrl: string): string {
  try {
    const resolved = new URL(url, baseUrl || window.location.origin);
    return resolved.pathname + resolved.search;
  } catch {
    // 已经是相对路径的情况（axios 常见），原样返回
    return url;
  }
}
