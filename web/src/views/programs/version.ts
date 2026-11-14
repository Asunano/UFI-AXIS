/**
 * 程序版本比较。
 *
 * 只做数字段比较：4.2.9 < 4.2.10，4.3 = 4.3.0。预发布后缀（-beta）按核心数字段比较，
 * 不因为字符串不同就允许降级 —— 旧版 catalog 不应该把用户已经装好的新版本“更新”回去。
 */
export function normalizeVersion(version: string): string {
  return (version || '').trim().replace(/^v/i, '');
}

export function compareVersions(a: string, b: string): number {
  const parse = (v: string): number[] => {
    const core = normalizeVersion(v).split('-')[0] ?? '';
    return core.split('.').map((part) => {
      const n = Number.parseInt(part, 10);
      return Number.isFinite(n) ? n : 0;
    });
  };
  const left = parse(a);
  const right = parse(b);
  const len = Math.max(left.length, right.length);
  for (let i = 0; i < len; i += 1) {
    const l = left[i] ?? 0;
    const r = right[i] ?? 0;
    if (l !== r) return l > r ? 1 : -1;
  }
  return 0;
}

/** catalog 的 latest 是否严格新于已安装版本。 */
export function isNewerVersion(candidate: string, current: string): boolean {
  return compareVersions(candidate, current) > 0;
}

export function vLabel(version: string): string {
  const v = (version || '').trim();
  if (!v) return '—';
  return /^v/i.test(v) ? v : `v${v}`;
}
