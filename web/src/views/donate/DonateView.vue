<!--
  赞赏（2026-10-14 新建，路由 `/donate`，**侧栏没有入口**，只从「设置 → 关于 → 赞赏」进）。

  ## 用途
  两张赞赏码并排：**左支付宝、右微信**，每张下面标注渠道名；再配一段说明（赞赏自愿、不影响功能）。
  本页**不放名单** —— 名单是 `/sponsors` 的事。

  ## 为什么与「赞助与感谢」拆成两页（用户原话：「防止内容挤压」）
  拆分前 `/sponsors` 上半是赞赏码、下半是名单。两边的宽度需求是**互斥**的：
  码要够大才扫得出来（并排两张各需 200px 以上），而名单的条数由赞助人数决定、会一直长。
  挤在同一张卡里，窄屏上只能二选一 —— 要么把码缩到扫不出来，要么把名单压成一条缝。
  而且「我要付钱」与「我想看都有谁」本来就是两次不同的访问，拆开后每页只有一个主题。

  ## 两张图的比例不同，这是本页最容易写错的地方
  支付宝 1260×1890（约 1:1.5）、微信 1308×1781（约 1:1.36）—— **不是同一个比例**。
  所以并排时**绝不能给同一个固定高度**：固定高度只会把其中一张拉伸/压扁，
  而**压扁的二维码扫不出来**（模块从正方变成矩形，识别率断崖式下降）。
  两张图因此都只用 `width` + `height: auto`，高度各自由**它自己的**比例算出来；
  HTML 的 `width`/`height` 属性逐张写上真实像素尺寸，作用只是图未加载时先占住正确比例的位置
  （不写的话，两张图会在加载完成的一瞬间把下面的内容顶一下）。

  ## 否决过的替代方案
  · **继续留在 `/sponsors` 一页**：见上，宽度需求互斥，用户要的正是"别挤"。
  · **自己拼一张"两码合一"的图**：两个渠道的相对大小、间距、标注会被烧进像素，
    改文案要重新出图；而且非整数倍缩放在 retina 屏上会让二维码模块边缘发虚。
  · **两张图给同一个固定高度**：比例 1:1.5 与 1:1.36，固定高度必然压扁其中一张（见上）。
  · **按 PNG 原始像素宽度直出**（1260 / 1308px）：并排会把卡片撑爆，也没有任何收益 ——
    显示宽度给 220px，2 倍屏上仍有 440 物理像素，扫一扫足够。
-->
<template>
  <div class="donate-view">
    <GridCard title="赞赏">
      <p class="dt-intro">如果你喜欢这个软件，可以考虑请作者喝一杯柠檬水。设备端 App 的「关于」页里也有同一个入口。</p>
      <p class="dt-intro">赞赏完全自愿，不影响任何功能 —— 所有功能对所有用户都是完整开放的。</p>

      <!-- 两张码：DOM 顺序即视觉顺序（支付宝在左、微信在右）。
           窄屏下由下面的 media query 改成上下堆叠、居中。 -->
      <div class="dt-grid">
        <figure class="dt-item">
          <!-- width/height 是**这张图自己的**真实像素尺寸（比例 1260:1890），
               只用于加载前占位；CSS 用 width + height:auto 覆盖实际尺寸。 -->
          <img class="dt-qr" src="/donate-alipay.png" alt="支付宝赞赏码" width="1260" height="1890" loading="lazy" />
          <figcaption class="dt-label">支付宝</figcaption>
        </figure>
        <figure class="dt-item">
          <!-- 比例 1308:1781，与左边那张**不同** —— 所以两边的宽度可以一样，高度不能。 -->
          <img class="dt-qr" src="/donate-wechat.png" alt="微信赞赏码" width="1308" height="1781" loading="lazy" />
          <figcaption class="dt-label">微信</figcaption>
        </figure>
      </div>
    </GridCard>
  </div>
</template>

<script setup lang="ts">
import GridCard from '@/components/GridCard.vue';
</script>

<style scoped>
.donate-view {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.dt-intro {
  margin: 0 0 var(--space-2);
  font-size: var(--font-base);
  color: var(--text-secondary);
  line-height: 1.7;
}

/* 两张码：宽屏并排居中；`flex-wrap` 是兜底 —— 万一将来加宽了图，
   窄容器里也会自己折行，而不是把卡片顶出一条横向滚动条。 */
.dt-grid {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  align-items: flex-start;
  gap: var(--space-4);
  margin-top: var(--space-3);
}
.dt-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-2);
  margin: 0;
}

/*
 * 赞赏码：**只给宽度、高度 auto**，两张图共用这一条。
 *
 * 支付宝 1260×1890（1:1.5）与微信 1308×1781（1:1.36）比例不同，给固定高度必然压扁其中一张
 * —— 而**压扁的二维码扫不出来**。`height: auto` 让每张图按自己的比例算高度，
 * 所以并排时两张的**高度本来就不一样**，这是正确结果，不要去"对齐"它们。
 * `max-width: 100%` 兜住极窄视口（320px），此时宽度被压到容器宽，高度仍按比例走。
 *
 * 底色给白：PNG 在暗色主题下若边缘透明、或图还没加载出来，白底能保证二维码区域
 * 始终是高对比的（与 `QrModal.vue` / `WifiInfoCard.vue` 同一取舍）。
 */
.dt-qr {
  width: 220px;
  height: auto;
  max-width: 100%;
  border-radius: var(--radius-md);
  /* ui-baseline-allow: 二维码底色必须纯白，接令牌在暗色下会让码块与底色的对比度不足以识别 */
  background: #fff;
}
.dt-label {
  font-size: var(--font-base);
  font-weight: 600;
  color: var(--text-primary);
}

@media (max-width: 768px) {
  /* 手机端：两张码改成上下堆叠、居中。
     两个理由：① 卡内可用宽只有 ~300px，两张 220px 并排会各自被压到 140px 左右，
     那个尺寸在手机屏幕上不好扫；② 堆叠后每张能放到 280px，接近并排时的两倍宽。 */
  .dt-grid {
    flex-direction: column;
    align-items: center;
    gap: var(--space-5);
  }
  /* 单列时放大到 280px（仍 `height: auto`）。
     ⚠ 不要在这里加 `flex: 0 0 280px`：纵向排列时 flex-basis 会变成**高度**基准，
     而这两张图的高度是宽度的 1.5 / 1.36 倍，一加就会被压成一坨。 */
  .dt-qr {
    width: 280px;
  }
}
</style>
