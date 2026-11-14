/**
 * `composables/utils.ts` 的单测（2026-10-30）。
 *
 * 选它的理由：这是全站被引用最多的纯逻辑模块（格式化、信号分档、错误提取），
 * 却一直没有一行测试。下面每个用例都对应一个**已经在真机上出过问题**的判据：
 *
 *  · `signalLevel` 的四档边界：`--signal-*` 只有四档，而语义色只有三档，
 *    分档错一档，用户就分不出「弱但能用」和「快断了」；
 *  · `networkTypeWithBand`：频段前缀由 core 决定（`N78` / `B3`），web 只做拼接，
 *    曾经自己拼 `N`/`B` 导致与 core 漂移；
 *  · `get()` 的 `!== undefined` 语义：`0` / `''` / `false` 是**有效值**，
 *    写成 `o[k] || fallback` 会把「0 个客户端」显示成兜底值；
 *  · `toApiErrorMessage`：core 用 200 + `{error}` 表示失败的那批端点，
 *    文案全靠它从 axios 信封里挖出来。
 */
import { describe, expect, it } from 'vitest';
import {
  batteryStatusText,
  evaluatePasswordStrength,
  formatBytes,
  formatSpeed,
  formatUptimeSec,
  get,
  networkTypeLabel,
  networkTypeWithBand,
  signalColor,
  signalLevel,
  toApiErrorMessage,
} from './utils';
import { formatUptimeMs } from '@/views/settings/settingsShared';

describe('formatBytes', () => {
  it('按 1024 进制分档，不到 1KB 直接给原始字节', () => {
    expect(formatBytes(0)).toBe('0 B');
    expect(formatBytes(1023)).toBe('1023 B');
    expect(formatBytes(1024)).toBe('1.0 KB');
    expect(formatBytes(1536)).toBe('1.5 KB');
    expect(formatBytes(1024 ** 2)).toBe('1.0 MB');
    // GB 档刻意保留两位：流量总量常见到 0.01GB 量级，一位小数会把 10.04 显示成 10.0
    expect(formatBytes(1024 ** 3)).toBe('1.00 GB');
  });
});

describe('formatSpeed', () => {
  it('不到 1KB/s 不加小数', () => {
    expect(formatSpeed(0)).toBe('0 B/s');
    expect(formatSpeed(1023)).toBe('1023 B/s');
    expect(formatSpeed(1024)).toBe('1.0 KB/s');
    expect(formatSpeed(1024 ** 2)).toBe('1.0 MB/s');
  });
});

describe('formatUptimeSec（秒）', () => {
  it('只保留最大的两级，不足一分钟显示 0分钟', () => {
    expect(formatUptimeSec(0)).toBe('0分钟');
    expect(formatUptimeSec(59)).toBe('0分钟');
    expect(formatUptimeSec(60)).toBe('1分钟');
    expect(formatUptimeSec(3599)).toBe('59分钟');
    expect(formatUptimeSec(3600)).toBe('1小时 0分');
    expect(formatUptimeSec(86399)).toBe('23小时 59分');
    expect(formatUptimeSec(86400)).toBe('1天 0小时 0分');
    expect(formatUptimeSec(86400 + 3600 + 60)).toBe('1天 1小时 1分');
  });

  it('与 settingsShared.formatUptimeMs 是两个函数：单位不同、排版也不同', () => {
    // 2026-10-30 之前两者同名 `formatUptime`，传错单位不会报错、只会静默显示错的时长
    // （86400 秒 = 1 天，当成毫秒算就只有 86 秒）。现在名字里带单位，这里把两边的
    // 输出形态一起钉住 —— 谁再合并它们，这条用例会先红。
    expect(formatUptimeSec(86400)).toBe('1天 0小时 0分');
    expect(formatUptimeMs(86_400_000)).toBe('1 天 0 小时 0 分');
    expect(formatUptimeSec(0)).toBe('0分钟');
    expect(formatUptimeMs(0)).toBe('--');
  });
});

describe('signalLevel / signalColor', () => {
  it('四档边界与 core 的 RSRP 阈值一致', () => {
    expect(signalLevel(-80)).toBe('excellent');
    expect(signalLevel(-81)).toBe('good');
    expect(signalLevel(-90)).toBe('good');
    expect(signalLevel(-91)).toBe('fair');
    expect(signalLevel(-105)).toBe('fair');
    expect(signalLevel(-106)).toBe('poor');
  });

  it('返回的是 CSS 变量而不是色值（暗色/换肤才跟得上）', () => {
    expect(signalColor(-70)).toBe('var(--signal-good)');
    expect(signalColor(-85)).toBe('var(--signal-fair)');
    expect(signalColor(-100)).toBe('var(--signal-weak)');
    expect(signalColor(-120)).toBe('var(--signal-poor)');
  });
});

describe('networkTypeLabel', () => {
  it('大小写不敏感，认不出就原样返回，空值给「无信号」', () => {
    expect(networkTypeLabel('nr')).toBe('5G');
    expect(networkTypeLabel('LTE')).toBe('4G');
    expect(networkTypeLabel('hspa')).toBe('3G+');
    expect(networkTypeLabel('XYZ')).toBe('XYZ');
    expect(networkTypeLabel('')).toBe('无信号');
  });
});

describe('networkTypeWithBand', () => {
  it('频段直接用 core 拼好的 band_label，不自己补前缀', () => {
    expect(networkTypeWithBand('NR', { band_label: 'N78' })).toBe('5G N78');
    expect(networkTypeWithBand('LTE', { band_label: 'B3' })).toBe('4G B3');
  });

  it('没有频段就只显示制式，不编造', () => {
    expect(networkTypeWithBand('LTE', null)).toBe('4G');
    expect(networkTypeWithBand('LTE', {})).toBe('4G');
    expect(networkTypeWithBand('LTE', { band_label: '   ' })).toBe('4G');
  });
});

describe('batteryStatusText', () => {
  it('已知状态翻译，未知状态原样透出（设备可能报新值）', () => {
    expect(batteryStatusText('CHARGING')).toBe('充电中');
    expect(batteryStatusText('FULL')).toBe('已充满');
    expect(batteryStatusText('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});

describe('get（安全取值）', () => {
  it('只有 undefined 才回落，0 / 空串 / false 都是有效值', () => {
    expect(get({ a: { b: 1 } }, 'a.b')).toBe(1);
    expect(get({ a: { b: 0 } }, 'a.b', 99)).toBe(0);
    expect(get({ a: { b: '' } }, 'a.b', 'x')).toBe('');
    expect(get({ a: { b: false } }, 'a.b', true)).toBe(false);
  });

  it('中间层缺失或为 null 时返回 fallback', () => {
    expect(get({}, 'a.b.c')).toBeNull();
    expect(get({ a: null }, 'a.b.c', 'x')).toBe('x');
    expect(get(null, 'a', 'x')).toBe('x');
  });
});

describe('toApiErrorMessage', () => {
  it('优先取 axios 信封里的 error 字段（core 用 200 + error 表示失败）', () => {
    expect(toApiErrorMessage({ response: { data: { error: '端口被占用' } } })).toBe('端口被占用');
  });

  it('普通 Error 取 message，其余回落到调用方给的文案', () => {
    expect(toApiErrorMessage(new Error('断网了'))).toBe('断网了');
    expect(toApiErrorMessage(null)).toBe('操作失败');
    expect(toApiErrorMessage('字符串异常', '保存失败')).toBe('保存失败');
    expect(toApiErrorMessage({ response: { data: { error: 123 } } }, '保存失败')).toBe('保存失败');
  });
});

describe('evaluatePasswordStrength', () => {
  it('四项判据各记一分：长度 / 大小写 / 数字 / 符号', () => {
    expect(evaluatePasswordStrength('')).toMatchObject({ score: 0, label: '空' });
    expect(evaluatePasswordStrength('abcdefgh')).toMatchObject({ score: 1, label: '弱' });
    expect(evaluatePasswordStrength('Abcdefgh')).toMatchObject({ score: 2, label: '中' });
    expect(evaluatePasswordStrength('Abcdefg1')).toMatchObject({ score: 3, label: '强' });
    expect(evaluatePasswordStrength('Abcdefg1!')).toMatchObject({ score: 4, label: '很强' });
  });

  it('颜色返回令牌而不是色值（暗色 / 换肤才跟得上）', () => {
    expect(evaluatePasswordStrength('')).toMatchObject({ color: 'var(--error)' });
    // 0–1 分弱、2 分中、3–4 分强，与 LoginView / PairingPanel 的分档一致
    expect(evaluatePasswordStrength('abcdefgh').color).toBe('var(--error)');
    expect(evaluatePasswordStrength('Abcdefgh').color).toBe('var(--warning)');
    expect(evaluatePasswordStrength('Abcdefgh1').color).toBe('var(--success)');
    expect(evaluatePasswordStrength('Abcdefg1!').color).toBe('var(--success)');
  });

  it('四类字符各计一分，与长度无关（4 位也能拿 3 分）', () => {
    // 记录现状而不是期望：长度只值 1 分，所以 'Ab1!' 与 'Abcdefg1' 同为 3 分。
    // 真要改这个权重，先确认 LoginView / PairingPanel 两处提示文案跟得上。
    expect(evaluatePasswordStrength('Ab1!')).toMatchObject({ score: 3, label: '强' });
  });
});
