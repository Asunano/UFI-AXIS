<!--
  赞助与感谢（2026-10-14 新建，路由 `/sponsors`，**侧栏没有入口**，只从「设置 → 关于 → 赞助与感谢」进）。

  ## 用途
  只放**赞助者名单**。赞赏码（两张：支付宝 / 微信）2026-10-14 拆去了 `/donate`（`DonateView.vue`）——
  两边的宽度需求互斥（码要 200px 以上才扫得出来，名单条数由赞助人数决定、会一直长），
  挤在同一张卡里窄屏上只能二选一。本页因此不再有任何图片。

  ## 数据是两层，页面只读一份状态
  保底那份在**构建期**就内联进了 bundle（见 `vite.config.ts`），所以首帧直接有名单、
  不会白屏；`GET api/sponsors` 是更新层，成功且**校验通过**才替换。
  两层都可能坏，坏的那份整份丢弃、保留上一层 —— 规则与理由见 `sponsorData.ts` 的文件头。
  （数据层一行都没动：拆页只动了这一页的模板与样式。）

  ## 三处刻意的"不显示"
  ① **不显示日期**：`at` 只用于排序。名单的顺序是公开的，谁什么时候赞助的不是 ——
     所以界面上一个日期都不出现（包括"2026-10"这种月份）。
  ② **不显示"暂无赞助者"**：名单为空时整块不渲染，整页只剩卡片标题。
     一个空名单配一句"暂无赞助者"会让这一页看起来像坏掉了。
  ③ **匿名条目不逐条列**：它们只汇成尾部一行「另有 N 位匿名赞助者」，
     而且**不参与时间排序**（那一行不是某个人的位置，是计数）。
     逐条列出来等于把"匿名"这个选择作废了。
-->
<template>
  <div class="sponsors-view">
    <GridCard title="赞助与感谢">
      <template #extra>
        <span v-if="sponsorsState.loading" class="sp-sync">正在从设备同步…</span>
      </template>

      <!--
        名单整块只在"确实有人"时渲染：`named.length > 0`（有具名的人）或匿名计数 > 0。
        只有匿名条目时**不显示「感谢名单」这个小标题**（标题下面没有人名会很怪），
        只留尾部那一行计数 —— 那一行本身就是完整的信息。
      -->
      <section v-if="hasAnySponsor">
        <template v-if="namedSponsors.length > 0">
          <div class="sp-list-head section-subtitle">感谢名单</div>
          <!--
            key 带上下标：`at` + `name` 不是唯一的 —— 同名（甚至同名的重名赞助）
            在同一时刻入账时 `at` 也相同，只用 `${at}/${name}` 会撞键（Vue 会警告，
            而且复用节点会让列表在更新时串行）。排序是确定的（at 倒序 → name 升序），
            所以下标在同一份数据下稳定。
          -->
          <ul class="sp-list">
            <li v-for="(s, i) in namedSponsors" :key="`${s.at}/${s.name}/${i}`" class="sp-item">{{ s.name }}</li>
          </ul>
        </template>
        <!-- 匿名计数：只在 N > 0 时出现，且**不参与时间排序**（它不是一个位置，是一个数）。 -->
        <div v-if="anonymousCount > 0" class="sp-anon">
          <span class="sp-anon-rule"></span>
          <span class="sp-anon-text">另有 {{ anonymousCount }} 位匿名赞助者</span>
        </div>
      </section>
    </GridCard>

    <!-- 更新层没接上时的说明（core 不可达 / 那份数据不合规）。
         名单没更新时必须让人知道为什么，否则用户会以为名单本来就是这样。 -->
    <p v-if="sponsorsState.note" class="sp-note">{{ sponsorsState.note }}</p>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue';
import GridCard from '@/components/GridCard.vue';
import { anonymousCount, loadSponsors, namedSponsors, sponsorsState } from './sponsorData';

/** 有没有任何赞助者。名单为空时整块不渲染（见文件头"三处刻意的不要显示"）。 */
const hasAnySponsor = computed(() => namedSponsors.value.length > 0 || anonymousCount.value > 0);

onMounted(() => {
  // 保底那份已经在 `sponsorsState` 的初值里了，这里只补一次更新层的拉取。
  // 失败不抛（见 `loadSponsors`）：core 不可达就停在保底那份。
  void loadSponsors();
});
</script>

<style scoped>
.sponsors-view {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.sp-sync {
  font-size: var(--font-sm);
  color: var(--text-muted);
}

/* ── 名单 ──
   本页现在只有这一块（赞赏码整块已拆去 `DonateView.vue`）。
   原来包着它的 `.sp-list-block`（margin-top + padding-top + border-top）随之删掉：
   那条分隔线是给"同一张卡里的两件事"分段的，上面已经没有别的东西了 ——
   留着它会在卡片内容的最上沿画出一道没有下文的横线。 */
.sp-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}
.sp-item {
  font-size: var(--font-md);
  color: var(--text-primary);
  line-height: 1.7;
  word-break: break-all;
}
.sp-anon {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-top: var(--space-3);
}
/* 计数前面那条短横线：视觉上把它与上面的名单分开，读起来是"另计"而不是"下一个人"。 */
.sp-anon-rule {
  width: 40px;
  height: 1px;
  background: var(--border-subtle);
}
.sp-anon-text {
  font-size: var(--font-base);
  color: var(--text-muted);
}

.sp-note {
  margin: 0;
  font-size: var(--font-sm);
  color: var(--text-muted);
  line-height: 1.6;
}

@media (max-width: 768px) {
  /* 768 档只做两处微调（名字靠 `break-all` 兜住，不需要额外排版）：
     ① 名单项行高从 1.7 收到 1.6 —— 手机上一屏能多看得见一两个人名；
     ② 匿名计数那一行允许折行并缩短前面的短横线：卡内可用宽约 300px，
        「另有 N 位匿名赞助者」+ 40px 横线在 320px 视口下会把文字挤成两行并末字掉行。 */
  .sp-item {
    line-height: 1.6;
  }
  .sp-anon {
    flex-wrap: wrap;
  }
  .sp-anon-rule {
    width: var(--space-4);
    flex: none;
  }
}
</style>
