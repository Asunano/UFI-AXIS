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

/**
 * 赞助名单的**保底数据**（2026-10-14）。
 *
 * ## 为什么在构建期注入，而不是让页面运行时去 fetch 仓库里那份
 * 仓库根的 `sponsors.json` 是名单的**唯一源文件**（三端共用），但它不在 `web/public` 下，
 * 浏览器拿不到它；而"保底"的全部意义就是**0 延迟、不白屏** —— 一次网络往返之后才画出来的
 * 名单不叫保底。所以构建时读进来、用 `define` 内联成字面量：
 * 页面首帧就有数据，`GET api/sponsors` 只是"更新层"，成功且合规才替换。
 *
 * ## 为什么用 readFileSync 而不是 `import sponsors from '../sponsors.json'`
 * 与上面的 `webVersionPlugin` 读根 `version.json` 是同一手法，两个理由：
 * ① 读失败（文件被删 / JSON 坏）**可以兜住**成 `null`，页面退化成"没有保底名单但更新层照常"；
 *    静态 import 的话构建直接失败，而这份名单坏掉不该让整个前端构建挂掉；
 * ② 路径写法与那个插件一致（都是 `resolve(__dirname, '../…')`），
 *    不用去分辨 esbuild 对 `../sponsors.json` 这种跨 root 的 JSON import 怎么解析。
 *
 * ## 为什么是 define 而不是写一个生成文件到 src/
 * 生成文件要先跑一次构建才存在，`vue-tsc -b` 在干净检出上会直接报找不到模块；
 * 而 `define` 只是一次文本替换，不产生第二份需要提交的产物（单一源文件仍然只有根目录那一份）。
 */
function readBundledSponsors(): unknown {
  try {
    return JSON.parse(readFileSync(resolve(__dirname, '../sponsors.json'), 'utf-8'));
  } catch {
    // 读不到就注入 null：`sponsorData.ts` 会把它当成"内置那份不可用"，
    // 页面仍然能靠 api/sponsors 拿到名单（只是少了保底那一层）。
    return null;
  }
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
  /**
   * `__SPONSORS_BUNDLED__` 是给 `src/views/sponsors/sponsorData.ts` 用的构建期常量，
   * 类型声明在 `src/views/sponsors/bundledSponsors.d.ts`（`declare const`）。
   * 值必须**先 JSON.stringify 成字符串**：define 做的是文本替换，直接塞对象字面量
   * 会得到 `[object Object]`。
   */
  define: {
    __SPONSORS_BUNDLED__: JSON.stringify(readBundledSponsors()),
  },
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
          // 播放器内核单独成块（~50KB gzip）：只有视频页与文件预览会用到它。
          // mpegts.js / hls.js **刻意不列在这里** —— 它们在 VideoPlayer 里是
          // 动态 import()，让 Vite 自然切成独立 chunk，只在真遇到 flv/m3u8 时才下载。
          // 列进来反而会把它们合进这个必加载的块里，白增 200KB。
          player: ['artplayer'],
        },
      },
    },
  },
}));
