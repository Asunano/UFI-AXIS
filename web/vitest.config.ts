import { defineConfig } from 'vitest/config';
import { fileURLToPath } from 'node:url';

/**
 * 单测配置（2026-10-30）。
 *
 * 刻意**不复用** `vite.config.ts`：那份配置挂着 `unplugin-vue-components`（会写
 * `src/components.d.ts`）与 `webVersionPlugin`（构建结束时读 version.json），
 * 跑一次 `vitest` 就顺手改一次生成文件、换个 cwd 还可能读不到根 version.json。
 * 单测只需要两样东西：`@` 别名，和 `src/**` 的收集范围。
 *
 * 环境固定 `node` 而不是 jsdom：当前被测模块（utils / contract / 签名 URI 归一化）
 * 都是纯函数。等要测组件时再给个别文件加 `// @vitest-environment jsdom`，
 * 不要为了将来可能的需求把整套环境换成 jsdom —— 那会让每个用例都拖上一个假 DOM。
 */
export default defineConfig({
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.spec.ts'],
  },
});
