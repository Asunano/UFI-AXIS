import { describe, expect, it } from 'vitest';
import { compareVersions, isNewerVersion, vLabel } from './version';

describe('compareVersions', () => {
  it('按数字段比较，4.2.9 小于 4.2.10', () => {
    expect(compareVersions('4.2.9', '4.2.10')).toBe(-1);
    expect(compareVersions('4.2.10', '4.2.9')).toBe(1);
  });

  it('忽略 v 前缀和缺失的尾段', () => {
    expect(compareVersions('v4.2.9', '4.2.9')).toBe(0);
    expect(compareVersions('4.3', '4.3.0')).toBe(0);
  });

  it('预发布后缀只比较核心数字段，不制造降级', () => {
    expect(compareVersions('4.2.9-beta', '4.2.9')).toBe(0);
    expect(isNewerVersion('4.2.9-beta', '4.2.9')).toBe(false);
  });

  it('只在严格更新时提示更新', () => {
    expect(isNewerVersion('4.2.10', '4.2.9')).toBe(true);
    expect(isNewerVersion('4.2.9', '4.2.10')).toBe(false);
    expect(isNewerVersion('4.2.9', '4.2.9')).toBe(false);
  });

  it('标签统一补 v', () => {
    expect(vLabel('4.2.9')).toBe('v4.2.9');
    expect(vLabel('v4.2.9')).toBe('v4.2.9');
    expect(vLabel('')).toBe('—');
  });
});
