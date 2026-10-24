<template>
  <!--
    登录页左侧品牌栏（2026-10-22 从 LoginView 拆出）。

    为什么单独成组件：LoginView 原本 1161 行，再塞进一整列品牌叙事会把它推过 1500 行，
    而这一栏与登录逻辑零耦合（只有页面地址这一个动态值时）。拆出来后：
      · 桌面端是整列深绿 hero（slogan + 一句说明 + 版本/地址）；
      · ≤960px（手机 / 竖屏平板）收成页面底色上的一行轻抬头（logo + 名称 + 副标题 + 版本/地址），
        不再是原先那块 150px 高、只放 logo 的绿色横条。
    960 是 mockup 02-split-brand 的单列阈值：只有它以下的窄屏才用移动端抬头，
    769–960 也按移动端排（不再像之前 768 那样在平板上提前切桌面两栏）。
  -->
  <aside class="brand">
    <!-- 大面积留白里的淡品牌水印：只做质感，不抢文字注意力 -->
    <svg class="brand-watermark" viewBox="0 0 24 24" aria-hidden="true">
      <path d="M10 3.2a9 9 0 1 0 10.8 10.8a1 1 0 0 0 -1 -1h-3.8a4.1 4.1 0 1 1 -5 -5v-4a0.9 0.9 0 0 0 -1 -0.8" />
      <path d="M15 3.5a9 9 0 0 1 5.5 5.5h-4.5a9 9 0 0 0 -1 -1v-4.5" />
    </svg>

    <div class="brand-top">
      <div class="brand-logo">
        <!-- 与 :app 启动图标同款（白底 + chart-donut 字形），属于品牌图形，不随主题换色 -->
        <svg
          width="23"
          height="23"
          viewBox="0 0 24 24"
          fill="none"
          stroke="#0f3d2a"
          stroke-width="1.3"
          stroke-linecap="round"
          stroke-linejoin="round"
          aria-hidden="true"
        >
          <path d="M10 3.2a9 9 0 1 0 10.8 10.8a1 1 0 0 0 -1 -1h-3.8a4.1 4.1 0 1 1 -5 -5v-4a0.9 0.9 0 0 0 -1 -0.8" />
          <path d="M15 3.5a9 9 0 0 1 5.5 5.5h-4.5a9 9 0 0 0 -1 -1v-4.5" />
        </svg>
      </div>
      <div class="brand-text">
        <div class="brand-name">UFI-AXIS</div>
        <div class="brand-tag">随身 WiFi 管理平台</div>
      </div>
    </div>

    <div class="brand-mid">
      <h2>随身 WiFi，<br />也值得一个像样的后台。</h2>
      <p>登录后查看设备状态与流量，管理文件、媒体与内网穿透 —— 数据都存在设备本机。</p>
    </div>

    <div class="brand-foot">
      <span>本机</span><span aria-hidden="true">·</span><span class="brand-host">{{ host }}</span>
    </div>
  </aside>
</template>

<script setup lang="ts">
/**
 * 页面地址：同源部署时就是设备的地址（如 192.168.0.1:8088），
 * 开发环境是 vite 的 127.0.0.1:5173 —— 两种情况都如实显示，不做"设备地址"的假设。
 */
const host = window.location.host;
</script>

<style scoped>
/*
  品牌栏尺寸同样由登录页的 `--u` 派生（定义在 LoginView 的 .login-shell 上，向下继承）。
  这里不写第二套比例常量：两块区域的缩放必须来自同一个数，否则一边放大一边没动。
*/
.brand {
  position: relative;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: calc(64 * var(--u)) calc(64 * var(--u)) calc(44 * var(--u));
  color: var(--brand-ink);
  /* 品牌区固定深绿（语义见 main.css 的 --brand-* 注释），不跟换肤走 */
  background:
    radial-gradient(1100px 560px at -10% -10%, var(--brand-glow), transparent 60%),
    linear-gradient(160deg, var(--brand-panel-1), var(--brand-panel-2));
}
.brand::after {
  content: '';
  position: absolute;
  inset: auto calc(-120 * var(--u)) calc(-160 * var(--u)) auto;
  width: calc(460 * var(--u));
  height: calc(460 * var(--u));
  border-radius: 50%;
  background: radial-gradient(circle at 30% 30%, var(--brand-glow-soft), transparent 62%);
  pointer-events: none;
}
/* 水印是装饰层：只用白色透明度，不参与主题 */
.brand-watermark {
  position: absolute;
  right: calc(-110 * var(--u));
  bottom: calc(-90 * var(--u));
  width: calc(520 * var(--u));
  height: calc(520 * var(--u));
  opacity: 0.055;
  pointer-events: none;
}
.brand-watermark path {
  fill: none;
  stroke: var(--brand-logo-bg);
  stroke-width: 0.8;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.brand > *:not(.brand-watermark) {
  position: relative;
  z-index: 1;
}

.brand-top {
  display: flex;
  align-items: center;
  gap: calc(12 * var(--u));
  animation: brand-fall 0.55s cubic-bezier(0.2, 0.7, 0.3, 1) both;
}
.brand-logo {
  width: calc(44 * var(--u));
  height: calc(44 * var(--u));
  border-radius: calc(12 * var(--u));
  background: var(--brand-logo-bg);
  border: 1px solid var(--border-subtle);
  display: grid;
  place-items: center;
  flex: none;
  box-shadow: 0 6px 20px var(--shadow-color-strong);
}
.brand-logo svg {
  width: calc(23 * var(--u));
  height: calc(23 * var(--u));
}
.brand-name {
  font-size: calc(17 * var(--u));
  font-weight: 700;
  letter-spacing: 0.02em;
}
.brand-tag {
  font-size: calc(12 * var(--u));
  opacity: 0.66;
  margin-top: calc(2 * var(--u));
}

.brand-mid {
  animation: brand-rise 0.62s 0.08s cubic-bezier(0.2, 0.7, 0.3, 1) both;
}
.brand-mid h2 {
  font-size: calc(36 * var(--u));
  line-height: 1.35;
  font-weight: 700;
  letter-spacing: -0.02em;
  max-width: 11em;
}
.brand-mid p {
  margin-top: calc(18 * var(--u));
  font-size: calc(15 * var(--u));
  line-height: 1.75;
  opacity: 0.72;
  max-width: 23em;
}

.brand-foot {
  display: flex;
  gap: calc(10 * var(--u));
  align-items: center;
  font-size: calc(13 * var(--u));
  opacity: 0.55;
  animation: brand-rise 0.62s 0.16s cubic-bezier(0.2, 0.7, 0.3, 1) both;
}
.brand-host {
  font-variant-numeric: tabular-nums;
}

@keyframes brand-fall {
  from {
    opacity: 0;
    transform: translateY(-8px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}
@keyframes brand-rise {
  from {
    opacity: 0;
    transform: translateY(8px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

/* ── 平板（≤1024）：仍是桌面两栏，只把品牌区收紧一档 ──
   与 mockup 02-split-brand 的同名断点逐条对齐：padding 40/36/32、slogan 30。
   （961 起回到桌面两栏，所以这条只影响 961–1024，不会碰到手机抬头。） */
@media (max-width: 1024px) {
  .brand {
    padding: calc(40 * var(--u)) calc(36 * var(--u)) calc(32 * var(--u));
  }
  .brand-mid h2 {
    font-size: calc(30 * var(--u));
  }
}

/* ── 手机 / 竖屏平板（≤960）：整列深绿收成页面底色上的一行轻抬头 ──
   只改变排列与配色，尺寸继续按 --u 等比缩放。 */
@media (max-width: 960px) {
  .brand {
    flex-direction: row;
    align-items: center;
    justify-content: space-between;
    gap: calc(12 * var(--u));
    padding: calc(12 * var(--u)) calc(16 * var(--u)) calc(10 * var(--u));
    min-height: 0;
    align-self: start;
    color: var(--text-primary);
    background: none;
  }
  .brand::after,
  .brand-watermark,
  .brand-mid {
    display: none;
  }
  .brand-top {
    gap: calc(12 * var(--u));
  }
  .brand-logo {
    width: calc(40 * var(--u));
    height: calc(40 * var(--u));
    border-radius: calc(11 * var(--u));
    box-shadow: none;
  }
  .brand-logo svg {
    width: calc(23 * var(--u));
    height: calc(23 * var(--u));
  }
  .brand-name {
    font-size: calc(17 * var(--u));
    letter-spacing: 0.01em;
    color: var(--text-primary);
  }
  .brand-tag {
    display: block;
    font-size: calc(12 * var(--u));
    margin-top: calc(2 * var(--u));
    color: var(--text-secondary);
    opacity: 0.85;
  }
  .brand-foot {
    display: flex;
    font-size: calc(12 * var(--u));
    opacity: 0.7;
    color: var(--text-secondary);
  }
}

@media (max-width: 380px) {
  /* 极窄屏抬头左右两段会互相挤到换行，右侧元信息先让位 */
  .brand-foot {
    display: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .brand-top,
  .brand-mid,
  .brand-foot {
    animation: none;
  }
}
</style>
