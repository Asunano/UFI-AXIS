import { defineConfig, type Plugin } from 'vite';
import vue from '@vitejs/plugin-vue';
import Components from 'unplugin-vue-components/vite';
import { NaiveUiResolver } from 'unplugin-vue-components/resolvers';
import { resolve } from 'path';
import { readFileSync, writeFileSync } from 'fs';

/** 构建结束时自动生成 dist/version.json，供 APK 内置版本追踪和独立更新校验。
 *  版本号以仓库根 version.json 的 web.version 为准（单一更新配置源），package.json 仅兜底 */
function webVersionPlugin(): Plugin {
  return {
    name: 'web-version',
    enforce: 'post',
    closeBundle() {
      const pkg = JSON.parse(readFileSync('package.json', 'utf-8'));
      let webVersion = pkg.version;
      try {
        const rootVersion = JSON.parse(readFileSync(resolve(__dirname, '../version.json'), 'utf-8'));
        if (rootVersion?.web?.version) webVersion = rootVersion.web.version;
      } catch {
        // 根 version.json 读取失败 → 回退 package.json
      }
      const versionJson = {
        version: webVersion,
        buildTime: new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'),
      };
      writeFileSync('dist/version.json', JSON.stringify(versionJson, null, 2) + '\n');
    },
  };
}

export default defineConfig(() => ({
  plugins: [
    vue(),
    // Naive UI 按需引入：模板中的 <n-xxx> 由插件按使用自动注入 import，
    // 配合移除 main.ts 的 app.use(naive)，首屏不再整库加载。
    Components({
      resolvers: [NaiveUiResolver()],
      dts: 'src/components.d.ts',
    }),
    webVersionPlugin(),
  ],
  base: '/',
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://192.168.0.1:8088',
        changeOrigin: true,
      },
      '/ws': {
        target: 'ws://192.168.0.1:8088',
        ws: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    target: 'es2020',
    // 路由级 code-splitting 由 vue-router 自动处理
    rollupOptions: {
      output: {
        manualChunks: {
          // 不再强制 naive-ui 单独成块：移除 app.use(naive) 后，各组件会随所用路由
          // 分片按需加载，首屏仅含首屏实际用到的子集。
          echarts: ['echarts', 'vue-echarts'],
          vendor: ['vue', 'vue-router', 'pinia', 'axios'],
        },
      },
    },
  },
}));
