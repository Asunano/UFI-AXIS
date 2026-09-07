/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{vue,js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        // 语义化颜色 — 与 Naive UI 主题变量对齐
        'page-bg': 'var(--page-bg)',
        'card-bg': 'var(--card-bg)',
        'text-primary': 'var(--text-primary)',
        'text-secondary': 'var(--text-secondary)',
        'text-muted': 'var(--text-muted)',
        'border-subtle': 'var(--border-subtle)',
        // 强调色（与 --accent-color 对齐）—— 供 bg-primary / text-primary 等使用
        primary: 'var(--accent-color)',
      },
      spacing: {
        // 数字档接到 CSS 变量上。Tailwind 默认 1..6 = 4/8/12/16/20/24px，与
        // --space-1..6 逐一相等，所以这次替换**零视觉变化**，只是把旋钮收进 main.css。
        1: 'var(--space-1)',
        2: 'var(--space-2)',
        3: 'var(--space-3)',
        4: 'var(--space-4)',
        5: 'var(--space-5)',
        6: 'var(--space-6)',
        // 页面级内边距（DefaultLayout 的 px-layout 在用）
        layout: 'var(--space-6)',
      },
      borderRadius: {
        card: 'var(--radius-md)',
        pill: 'var(--radius-pill)',
      },
      // 故意不覆盖 Tailwind 默认的 fontSize / borderRadius.sm|md|lg：
      // DefaultLayout.vue 用了 text-base / text-xs / text-sm / rounded-lg / rounded-md，
      // 它们依赖 Tailwind 默认值（16/12/14px、8/6px）。字号令牌 --font-* 比默认值小一档，
      // 覆盖同名 key 会静默把顶栏标题从 16px 缩到 13px。
      // 字号统一走 scoped CSS 的 var(--font-*)，不从 Tailwind 侧动。
    },
  },
  plugins: [],
};
