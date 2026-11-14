/**
 * `api/contract.ts` 的单测（2026-10-30）。
 *
 * 这个文件是 core 契约的**手抄镜像**（2178 行），与 Kotlin 侧逐字对应。端点是否存在由
 * `scripts/verify-api-contract.mjs` 守门，但**取值语义**（同一个档位在两侧怎么折算）
 * 没有任何机器在核对 —— 这里补的就是那部分：
 *
 *  · `bearerToNetworkMode`：漏一种设备写法，切换制式后的回读就永远对不上目标档位；
 *  · `modeProbeIntervalMs` / `shouldKeepProbingMode`：必须与 Kotlin `SwitchProbe` 同判据，
 *    否则一侧认为「还在切」、另一侧已经判超时；
 *  · `buildWifiConfigPayload`：OPEN 档多带 `passphrase` 会让设备按有密码处理，
 *    结果是「选了开放却连不上」；`max_sta_num` 的哨兵 0 也绝不该出现在报文里。
 */
import { describe, expect, it } from 'vitest';
import {
  Endpoints,
  NetworkMode,
  WsChannel,
  NetworkModeSwitchProbe,
  WS_UI_TOPICS,
  WifiBands,
  bearerToNetworkMode,
  buildWifiConfigPayload,
  clampAlertLimit,
  findWifiSecurityPreset,
  isWifiMaxStaNumAcceptable,
  modeProbeIntervalMs,
  shouldKeepProbingMode,
  wifiBandFromChipIndex,
  wifiMaxStaNumForPayload,
  wifiSecurityNeedsPassphrase,
} from './contract';

describe('Endpoints', () => {
  it('路径带前导斜杠，且与 axios 调用现状一致', () => {
    expect(Endpoints.config.root).toBe('/api/config');
    expect(Endpoints.alerts.list).toBe('/api/alerts/list');
  });

  it('程序端点覆盖 #/programs 的读写路径', () => {
    expect(Endpoints.programs.list).toBe('/api/programs');
    expect(Endpoints.programs.status).toBe('/api/programs/status');
    expect(Endpoints.programs.volumes).toBe('/api/programs/volumes');
    expect(Endpoints.programs.start('a/b')).toBe('/api/programs/a%2Fb/start');
    expect(Endpoints.programs.logs('openlist')).toBe('/api/programs/openlist/logs');
    expect(Endpoints.programs.secret('openlist')).toBe('/api/programs/openlist/secret');
    expect(Endpoints.programs.resetSecret('openlist')).toBe('/api/programs/openlist/reset-secret');
  });
});

describe('bearerToNetworkMode', () => {
  it('真机各种写法都归一到别名', () => {
    expect(bearerToNetworkMode('only_5g')).toBe(NetworkMode.ONLY_5G);
    expect(bearerToNetworkMode('NR5G_ONLY')).toBe(NetworkMode.ONLY_5G);
    expect(bearerToNetworkMode('  lte_only ')).toBe(NetworkMode.ONLY_LTE);
    expect(bearerToNetworkMode('WCDMA_AND_LTE_AND_5G')).toBe(NetworkMode.AUTO);
  });

  it('认不出来就原样返回，不伪装成「自动」', () => {
    expect(bearerToNetworkMode('SOME_NEW_MODE')).toBe('SOME_NEW_MODE');
    expect(bearerToNetworkMode('')).toBe('');
  });
});

describe('制式切换回读预算（与 Kotlin SwitchProbe 同判据）', () => {
  it('前 5 次用快档 1.5s，之后降到 3s', () => {
    expect(modeProbeIntervalMs(1)).toBe(NetworkModeSwitchProbe.intervalMs);
    expect(modeProbeIntervalMs(4)).toBe(NetworkModeSwitchProbe.intervalMs);
    expect(modeProbeIntervalMs(5)).toBe(NetworkModeSwitchProbe.slowIntervalMs);
    expect(modeProbeIntervalMs(12)).toBe(NetworkModeSwitchProbe.slowIntervalMs);
  });

  it('读到目标就停；次数到顶也停', () => {
    expect(shouldKeepProbingMode(1, false)).toBe(true);
    expect(shouldKeepProbingMode(1, true)).toBe(false);
    expect(shouldKeepProbingMode(12, false)).toBe(true);
    expect(shouldKeepProbingMode(13, false)).toBe(false);
  });
});

describe('WiFi 安全档位', () => {
  it('auth_mode 是大小写敏感的精确匹配', () => {
    expect(findWifiSecurityPreset('OPEN')?.encrypType).toBe('NONE');
    expect(findWifiSecurityPreset('wpa2psk')).toBeUndefined();
    expect(findWifiSecurityPreset(null)).toBeUndefined();
  });

  it('表外写法按「要密码」处理（宁可多留一个输入框，也不要把加密网当开放网提交）', () => {
    expect(wifiSecurityNeedsPassphrase('OPEN')).toBe(false);
    expect(wifiSecurityNeedsPassphrase('WPA2PSK')).toBe(true);
    expect(wifiSecurityNeedsPassphrase('UNKNOWN_MODE')).toBe(true);
    expect(wifiSecurityNeedsPassphrase(undefined)).toBe(true);
  });
});

describe('buildWifiConfigPayload', () => {
  const base = { ssid: 'ufi-home', passphrase: 'secret123', maxStaNum: null, hidden: false };

  it('OPEN 档不带 passphrase，并显式下发 encryp_type=NONE', () => {
    const payload = buildWifiConfigPayload({ ...base, authMode: 'OPEN' });
    expect(payload).toEqual({
      ssid: 'ufi-home',
      auth_mode: 'OPEN',
      encryp_type: 'NONE',
      broadcast_disabled: 0,
    });
    expect('passphrase' in payload).toBe(false);
  });

  it('加密档带密码，隐藏开关写进 broadcast_disabled', () => {
    const payload = buildWifiConfigPayload({ ...base, authMode: 'WPA2PSK', maxStaNum: 5, hidden: true });
    expect(payload.passphrase).toBe('secret123');
    expect(payload.encryp_type).toBe('CCMP');
    expect(payload.max_sta_num).toBe(5);
    expect(payload.broadcast_disabled).toBe(1);
  });

  it('max_sta_num 的哨兵 0 与越界值都不下发（core 会直接 Rejected）', () => {
    expect(buildWifiConfigPayload({ ...base, authMode: 'WPA2PSK', maxStaNum: 0 })).not.toHaveProperty('max_sta_num');
    expect(buildWifiConfigPayload({ ...base, authMode: 'WPA2PSK', maxStaNum: 99 })).not.toHaveProperty('max_sta_num');
    expect(buildWifiConfigPayload({ ...base, authMode: 'WPA2PSK', maxStaNum: 10 }).max_sta_num).toBe(10);
  });

  it('表外档位把设备回读的 encryp_type 原样带回', () => {
    const payload = buildWifiConfigPayload({
      ...base,
      authMode: 'WPA2PSKWPA3PSK_V2',
      fallbackEncrypType: 'TKIP',
    });
    expect(payload.encryp_type).toBe('TKIP');
    expect(payload.passphrase).toBe('secret123');
  });
});

describe('WiFi 频段', () => {
  it('传输取值只认 chip1 / chip2', () => {
    expect(WifiBands).toEqual(['chip1', 'chip2']);
  });

  it('读侧 chip_index 用数字编码，翻译只有这一处', () => {
    expect(wifiBandFromChipIndex('2')).toBe('chip2');
    expect(wifiBandFromChipIndex('1')).toBe('chip1');
    expect(wifiBandFromChipIndex(null)).toBe('chip1');
    expect(wifiBandFromChipIndex('')).toBe('chip1');
  });
});

describe('max_sta_num 守门', () => {
  it('null / undefined 视为「不修改」，其余必须是 1..10 的整数', () => {
    expect(isWifiMaxStaNumAcceptable(null)).toBe(true);
    expect(isWifiMaxStaNumAcceptable(undefined)).toBe(true);
    expect(isWifiMaxStaNumAcceptable(1)).toBe(true);
    expect(isWifiMaxStaNumAcceptable(10)).toBe(true);
    expect(isWifiMaxStaNumAcceptable(0)).toBe(false);
    expect(isWifiMaxStaNumAcceptable(11)).toBe(false);
    expect(isWifiMaxStaNumAcceptable(3.5)).toBe(false);
  });

  it('表单哨兵折算成 null', () => {
    expect(wifiMaxStaNumForPayload(0)).toBeNull();
    expect(wifiMaxStaNumForPayload(7)).toBe(7);
  });
});

describe('告警列表上限', () => {
  it('夹在 1..200 并取整', () => {
    expect(clampAlertLimit(0)).toBe(1);
    expect(clampAlertLimit(201)).toBe(200);
    expect(clampAlertLimit(50.9)).toBe(50);
  });
});

describe('WS 订阅集合', () => {
  it('去掉 notification：core 对同一条告警会 notification + alert 双发', () => {
    expect(WS_UI_TOPICS).not.toContain('notification');
    expect(WS_UI_TOPICS).toContain('alert');
  });
});

/**
 * 端点表的**形状**守卫（2026-10-30）。
 *
 * `Endpoints` 是 144 个手抄字面量（与本文件同源的 Kotlin `Endpoints.kt` 对应）。
 * `verify-api-contract.mjs` 校验的是"某个端点在 core 里存不存在"，但**撞车**和
 * **前缀写错**它看不出来：两条路径写成同一个值，脚本仍然两边都能匹配上，
 * 而线上表现是"点 A 按钮把 B 的数据改了"。下面三条约束就是补这个盲区。
 */
function flattenEndpoints(node: unknown, prefix = ''): Array<{ key: string; value: string }> {
  if (typeof node === 'string') return [{ key: prefix, value: node }];
  if (!node || typeof node !== 'object') return [];
  return Object.entries(node as Record<string, unknown>).flatMap(([k, v]) =>
    flattenEndpoints(v, prefix ? `${prefix}.${k}` : k)
  );
}

/**
 * 同一个路径配两个键的**白名单**。
 *
 * 目前只有 `/api/device/plan`：GET 读套餐、PUT 写套餐，core 侧本来就是同一个路由的两方法。
 * 再加同路径条目时必须先加到这张表里 —— 逼着写清楚"它是另一个方法"还是"抄错了"。
 */
const SHARED_PATH_ALLOW = new Set(['/api/device/plan']);

/** 刻意不在 `/api` 下的两个：WS 升级地址，以及免鉴权的存活探测（core 自更新后用它判断就绪）。 */
const NON_API_ALLOW = new Set(['/ws/realtime', '/health']);

describe('Endpoints 表结构', () => {
  const entries = flattenEndpoints(Endpoints);

  it('每个叶子都是字符串，且规模与手抄表相当（防止误把整块删成空对象）', () => {
    expect(entries.length).toBeGreaterThan(120);
    expect(entries.every((e) => e.value.startsWith('/'))).toBe(true);
  });

  it('全部挂在 /api/ 下（除白名单），且没有尾斜杠', () => {
    const bad = entries.filter((e) => !e.value.startsWith('/api/') && !NON_API_ALLOW.has(e.value));
    expect(bad).toEqual([]);
    expect(entries.filter((e) => e.value.endsWith('/'))).toEqual([]);
  });

  it('没有两个键指向同一条路径（撞车会让一个按钮改到另一处）', () => {
    const byValue = new Map<string, string[]>();
    for (const { key, value } of entries) {
      byValue.set(value, [...(byValue.get(value) ?? []), key]);
    }
    const dupes = [...byValue.entries()].filter(([value, keys]) => keys.length > 1 && !SHARED_PATH_ALLOW.has(value));
    expect(dupes).toEqual([]);
  });
});

describe('WS 频道表', () => {
  it('取值唯一、全部小写下划线（core 按这个字面量分发）', () => {
    const values = Object.values(WsChannel);
    expect(new Set(values).size).toBe(values.length);
    expect(values.every((v) => /^[a-z][a-z0-9_]*$/.test(v))).toBe(true);
  });
});
