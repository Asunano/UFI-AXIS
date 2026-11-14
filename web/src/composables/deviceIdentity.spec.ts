/**
 * 请求签名用的 URI 归一化单测（2026-10-30）。
 *
 * 为什么只测这一个函数：`toSignableUri` 的输出会直接进入 ECDSA 签名，而 core 的
 * AuthMiddleware 是**按服务端看到的 `request.uri` 逐字节验签**的。多一个斜杠、少一个
 * 百分号编码、把 query 丢掉，表现都是同一个 401 `STALE_TIMESTAMP`/签名不匹配 ——
 * 而错误信息完全指不到这里（历史上就踩过：签名只用了 `config.url`，所有带 params 的
 * 请求 100% 验签失败）。
 *
 * 其余签名逻辑（generateKey / sign）依赖 WebCrypto + IndexedDB，属于集成测试范畴，
 * 这里不伪造环境去测它 —— 假环境只会让用例通过，不会让真机通过。
 */
import { describe, expect, it } from 'vitest';
import { toSignableUri } from './deviceIdentity';

describe('toSignableUri', () => {
  // 注：所有用例都必须给 baseUrl。函数内部用 `baseUrl || window.location.origin` 兜底，
  // 而 node 环境没有 window —— 传空串会直接走 catch 分支（返回原样），
  // 测出来的是测试环境的形状，不是浏览器里的行为。
  it('绝对 URL 只取 path + query，丢掉 origin', () => {
    expect(toSignableUri('http://192.168.0.1:8088/api/config', 'http://192.168.0.1:8088')).toBe('/api/config');
    expect(toSignableUri('http://192.168.0.1:8088/api/files/list?path=%2F', 'http://192.168.0.1:8088')).toBe(
      '/api/files/list?path=%2F'
    );
  });

  it('相对路径按 baseUrl 解析，结果与浏览器实际请求一致', () => {
    expect(toSignableUri('/api/config', 'http://192.168.0.1:8088')).toBe('/api/config');
    expect(toSignableUri('api/config', 'http://192.168.0.1:8088/')).toBe('/api/config');
    expect(toSignableUri('/api/files/list?path=%2Froot', 'http://192.168.0.1:8088')).toBe(
      '/api/files/list?path=%2Froot'
    );
  });

  it('query 原样保留（含百分号编码），hash 不进签名', () => {
    expect(toSignableUri('http://h:1/api/x?a=1&b=2#frag', 'http://h:1')).toBe('/api/x?a=1&b=2');
    expect(toSignableUri('http://h:1/api/x?q=a%20b', 'http://h:1')).toBe('/api/x?q=a%20b');
  });

  it('解析不了时原样返回，不抛错', () => {
    // 非法 URL 会抛 TypeError → catch 分支原样返回。这条兜底存在的意义是：
    // 宁可让 core 用 401 明确拒绝，也不要在这里抛一个和签名毫无关系的异常。
    expect(toSignableUri('http://[bad', 'http://h:1')).toBe('http://[bad');
  });
});
