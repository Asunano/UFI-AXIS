/**
 * 全局致命错误状态机的单测（2026-10-30）。
 *
 * 这层是「页面崩了之后唯一还能说话的东西」，所以它自己的判据必须被测住：
 *  · 什么样的错误该被识别成「刷新就能好」（整包 OTA 后旧 chunk 404）；
 *  · 详情必须截断 —— 一段几千行的堆栈塞进 DOM 只会让浮层卡住；
 *  · 同一个页面连续报错时以**最后一次**为准（用户需要的是当前状态）。
 *
 * 渲染部分（`FatalErrorOverlay.vue`）不在这里测：它只读这个状态，逻辑为零；
 * 真要测就得拖进 jsdom + naive，收益远小于成本。
 */
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { dismissFatal, fatalError, isBenignBrowserWarning, reportFatal } from './globalError';

describe('reportFatal', () => {
  beforeEach(() => {
    // reportFatal 会无条件 console.error（浮层可能没渲染出来，日志才是排查入口）。
    // 测试里把它挡掉，否则每个用例都会往输出里刷一段堆栈。
    vi.spyOn(console, 'error').mockImplementation(() => {});
    dismissFatal();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('记录 Error 的 message 与堆栈', () => {
    reportFatal(new Error('render exploded'), 'vue:render function');

    const info = fatalError.value;
    expect(info?.message).toBe('render exploded');
    expect(info?.detail).toContain('vue:render function');
    expect(info?.detail).toContain('render exploded');
    expect(info?.reloadHint).toBe(false);
  });

  it('非 Error 的抛出物用通用标题，细节仍留在详情区', () => {
    reportFatal({ code: 500 }, 'window.error');
    expect(fatalError.value?.message).toBe('页面渲染出错');
    expect(fatalError.value?.detail).toContain('"code": 500');
  });

  it('动态 import 失败识别成「页面已过期」，提示刷新而不是报 bug', () => {
    reportFatal(
      new Error('Failed to fetch dynamically imported module: http://dev/assets/DashboardView-abc.js'),
      'vue:setup'
    );
    expect(fatalError.value?.reloadHint).toBe(true);
  });

  it('详情截断到上限，避免长堆栈拖慢浮层渲染', () => {
    reportFatal(new Error('x'.repeat(10_000)));
    expect(fatalError.value!.detail.length).toBeLessThanOrEqual(4000 + 'vue:setup\n\n'.length);
  });

  it('后一条覆盖前一条（连续出错时看最新的那条）', () => {
    reportFatal(new Error('first'));
    reportFatal(new Error('second'));
    expect(fatalError.value?.message).toBe('second');
  });

  it('dismiss 清空状态，浮层随之卸载', () => {
    reportFatal(new Error('boom'));
    dismissFatal();
    expect(fatalError.value).toBeNull();
  });

  it('浏览器自身的 ResizeObserver 告警不弹浮层', () => {
    // window.onerror 会把这类告警当普通错误派发；它是规范允许的提示，页面并没坏。
    reportFatal('ResizeObserver loop completed with undelivered notifications.', 'window.error');
    expect(fatalError.value).toBeNull();

    reportFatal(new Error('ResizeObserver loop limit exceeded'), 'window.error');
    expect(fatalError.value).toBeNull();
  });

  it('良性告警的判据只看消息文本，其它错误照旧弹浮层', () => {
    expect(isBenignBrowserWarning('ResizeObserver loop completed with undelivered notifications.')).toBe(true);
    expect(isBenignBrowserWarning('ResizeObserver loop limit exceeded')).toBe(true);
    expect(isBenignBrowserWarning('Failed to fetch dynamically imported module')).toBe(false);
    expect(isBenignBrowserWarning('')).toBe(false);

    reportFatal('ResizeObserver is not defined', 'window.error');
    expect(fatalError.value?.message).toContain('ResizeObserver is not defined');
  });
});
