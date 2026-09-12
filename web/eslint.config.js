import js from '@eslint/js';
import globals from 'globals';
import pluginVue from 'eslint-plugin-vue';
import tseslint from 'typescript-eslint';
import prettier from 'eslint-config-prettier';

// web/ 此前没有任何 lint 配置，1500+ 处格式问题就是这么攒起来的。
// 规则集刻意保守：只开「能自动修 + 能防真 bug」的那些，不引入需要大改结构的规则。
export default tseslint.config(
  { ignores: ['dist/**', 'node_modules/**', '*.tsbuildinfo'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  {
    files: ['**/*.{ts,vue}'],
    languageOptions: {
      // 浏览器环境：window/document/setTimeout/fetch/URL/performance… 都是合法全局，
      // 不声明的话 no-undef 会把它们全部报成未定义（91 条噪声）。
      globals: { ...globals.browser },
      parserOptions: { parser: tseslint.parser, ecmaVersion: 'latest', sourceType: 'module' },
    },
    rules: {
      // 真 bug 防线
      eqeqeq: ['error', 'always', { null: 'ignore' }],
      curly: ['error', 'all'],
      'no-var': 'error',
      // 中文文案里的全角空格是排版需要，只在代码位置报错
      'no-irregular-whitespace': ['error', { skipStrings: true, skipComments: true, skipTemplates: true }],
      // 与 handleLogin 这类手写校验并存，未使用变量只警告不阻塞
      '@typescript-eslint/no-unused-vars': ['warn', { argsIgnorePattern: '^_' }],
      '@typescript-eslint/no-explicit-any': 'off',
      '@typescript-eslint/no-empty-object-type': 'off',
      'vue/multi-word-component-names': 'off',
      // TS 里 props 的可选性由类型表达，不强制 default
      'vue/require-default-prop': 'off',
    },
  },
  {
    // Vue 模板文本里的全角空格是中文排版分隔符，删掉会改变渲染结果；
    // skipStrings/skipTemplates 覆盖不到模板文本节点，只能在 .vue 上整体关掉此规则。
    files: ['**/*.vue'],
    rules: { 'no-irregular-whitespace': 'off' },
  },
  {
    // 构建期脚本跑在 Node 里：console / process / fs 都是合法全局，
    // 用浏览器那套 globals 会把它们全报成 no-undef。
    files: ['scripts/**/*.{js,mjs}'],
    languageOptions: { globals: { ...globals.node } },
  },
  // prettier 放最后：关掉所有与格式化冲突的规则，格式统一交给 prettier
  prettier
);
