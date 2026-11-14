<!--
  关于页（方案 D：深色设备牌面 + App 关于页结构）。

  这里只回答「这是什么、从哪里来、还能去哪」：
  - 身份：应用图标 / 名称 / 版本 / 一句话简介 / 赞赏按钮；
  - 更新：更新设置与检查更新（跳既有 UpdatePanel）；
  - 维护：备份、诊断、资源、赞助（都是既有页面）；
  - 项目：GitHub 仓库与开源许可。

  刻意不再展示运行与存储、电池、诊断字段与字段覆盖率：
  这些在监控页 / 诊断中心已有完整入口，About 不重复。
-->
<template>
  <div class="about-panel">
    <section class="about-hero" aria-labelledby="about-app-name">
      <img class="about-icon" src="/favicon.svg" alt="" width="58" height="58" />
      <h2 id="about-app-name">UFI-AXIS</h2>
      <p class="hero-version">Version {{ version || '--' }}</p>
      <p class="hero-desc">
        为随身 WiFi / CPE 提供第三方管理端：后端服务运行在设备本身，手机端与网页面板共用同一套接口。
      </p>
      <button class="hero-donate" type="button" @click="go('donate')">
        <n-icon :size="14"><HeartOutline /></n-icon>
        赞赏
      </button>
    </section>

    <section class="about-group" aria-labelledby="about-update-title">
      <div class="group-head">
        <h2 id="about-update-title">更新</h2>
        <span>当前 {{ version || '--' }}</span>
      </div>
      <button class="about-row" type="button" @click="openSettingsTab('update')">
        <span class="row-icon"
          ><n-icon :size="17"><CloudDownloadOutline /></n-icon
        ></span>
        <span class="row-copy">
          <b>更新设置</b>
          <small>镜像源 · 启动时自动检查更新</small>
        </span>
        <n-icon :size="14" class="row-chev"><ChevronForwardOutline /></n-icon>
      </button>
      <div class="group-foot">
        <button class="check-btn" type="button" @click="openSettingsTab('update')">检查更新</button>
      </div>
    </section>

    <section class="about-group" aria-labelledby="about-maintenance-title">
      <div class="group-head">
        <h2 id="about-maintenance-title">维护</h2>
        <span>不常用，但需要时在此</span>
      </div>
      <button class="about-row" type="button" @click="openSettingsTab('backup')">
        <span class="row-icon"
          ><n-icon :size="17"><ArchiveOutline /></n-icon
        ></span>
        <span class="row-copy">
          <b>备份与恢复</b>
          <small>导出配置备份包 · 从备份文件恢复</small>
        </span>
        <n-icon :size="14" class="row-chev"><ChevronForwardOutline /></n-icon>
      </button>
      <button class="about-row" type="button" @click="go('diagnostics')">
        <span class="row-icon"
          ><n-icon :size="17"><MedkitOutline /></n-icon
        ></span>
        <span class="row-copy">
          <b>诊断中心</b>
          <small>设备权限 · 网络连通 · 后台服务 · 导出诊断包</small>
        </span>
        <n-icon :size="14" class="row-chev"><ChevronForwardOutline /></n-icon>
      </button>
      <button class="about-row" type="button" @click="go('resources')">
        <span class="row-icon"
          ><n-icon :size="17"><CubeOutline /></n-icon
        ></span>
        <span class="row-copy">
          <b>插件与组件</b>
          <small>frpc / cloudflared 等插件组件的下载、安装与卸载</small>
        </span>
        <n-icon :size="14" class="row-chev"><ChevronForwardOutline /></n-icon>
      </button>
      <button class="about-row" type="button" @click="go('sponsors')">
        <span class="row-icon"
          ><n-icon :size="17"><HeartOutline /></n-icon
        ></span>
        <span class="row-copy">
          <b>赞助与感谢</b>
          <small>感谢名单</small>
        </span>
        <n-icon :size="14" class="row-chev"><ChevronForwardOutline /></n-icon>
      </button>
    </section>

    <section class="about-group" aria-labelledby="about-project-title">
      <div class="group-head">
        <h2 id="about-project-title">项目</h2>
        <span>UFI-AXIS</span>
      </div>
      <a class="about-row" :href="REPO_URL" target="_blank" rel="noopener noreferrer">
        <span class="row-icon"
          ><n-icon :size="17"><LogoGithub /></n-icon
        ></span>
        <span class="row-copy">
          <b>UFI-AXIS</b>
          <small>github.com/Asunano/UFI-AXIS</small>
        </span>
        <n-icon :size="14" class="row-chev"><ChevronForwardOutline /></n-icon>
      </a>
      <a class="about-row" :href="THIRD_PARTY_NOTICES_URL" target="_blank" rel="noopener noreferrer">
        <span class="row-icon"
          ><n-icon :size="17"><DocumentTextOutline /></n-icon
        ></span>
        <span class="row-copy">
          <b>开源许可</b>
          <small>第三方组件与许可证声明</small>
        </span>
        <n-icon :size="14" class="row-chev"><ChevronForwardOutline /></n-icon>
      </a>
    </section>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import {
  ArchiveOutline,
  ChevronForwardOutline,
  CloudDownloadOutline,
  CubeOutline,
  DocumentTextOutline,
  HeartOutline,
  LogoGithub,
  MedkitOutline,
} from '@vicons/ionicons5';
import { getApiClient } from '@/composables/useApi';

const api = getApiClient();
const router = useRouter();
const version = ref('--');

const REPO_URL = 'https://github.com/Asunano/UFI-AXIS';
const THIRD_PARTY_NOTICES_URL =
  'https://github.com/Asunano/UFI-AXIS/blob/main/core/src/main/assets/shell/THIRD-PARTY-NOTICES.md';

function go(name: 'donate' | 'diagnostics' | 'resources' | 'sponsors') {
  void router.push({ name });
}

function openSettingsTab(tab: 'update' | 'backup') {
  // from=about 让 SettingsView 的返回键回到 About，而不是回设置首页。
  void router.push({ name: 'settings', query: { tab, from: 'about' } });
}

async function loadVersion() {
  try {
    const { data } = await api.get('/api/config/version');
    version.value = data.version || '--';
  } catch {
    /* 版本读取失败不阻塞页面；保持 -- 而不是编一个假版本 */
  }
}

onMounted(loadVersion);
</script>

<style scoped>
.about-panel {
  width: 100%;
  max-width: none;
  margin: 0 auto;
}

.about-hero {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 22px 20px 18px;
  border-radius: var(--radius-md);
  background: #1a1f22; /* ui-baseline-allow: 设备身份牌面固定深色，不随主题反相 */
  color: var(--card-bg);
  text-align: center;
}

.about-icon {
  width: 58px;
  height: 58px;
  border-radius: 13px;
  background: var(--card-bg);
}

.about-hero h2 {
  margin: 10px 0 0;
  color: var(--card-bg);
  font-size: 20px;
  line-height: 1.1;
  letter-spacing: -0.03em;
  font-weight: 760;
}

.hero-version {
  margin: 5px 0 0;
  color: var(--card-bg);
  opacity: 0.66;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
}

.hero-desc {
  max-width: 560px;
  margin: 12px 0 0;
  color: var(--card-bg);
  opacity: 0.66;
  font-size: 12px;
  line-height: 1.65;
  text-wrap: balance;
}

.hero-donate {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 30px;
  margin-top: 14px;
  padding: 0 12px;
  border: 1px solid color-mix(in srgb, var(--card-bg) 24%, transparent);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--card-bg);
  font-size: 12px;
}

.hero-donate:hover {
  background: color-mix(in srgb, var(--card-bg) 8%, transparent);
}

.about-group {
  margin-top: 12px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  background: var(--card-bg);
  overflow: hidden;
}

.group-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  padding: 12px 16px 9px;
  border-bottom: 1px solid var(--border-subtle);
}

.group-head h2 {
  margin: 0;
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 700;
}

.group-head span {
  color: var(--text-muted);
  font-size: 12px;
}

.about-row {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-height: 55px;
  padding: 9px 16px;
  border: 0;
  border-bottom: 1px solid var(--border-subtle);
  background: transparent;
  color: var(--text-primary);
  text-align: left;
  text-decoration: none;
}

.about-row:last-child {
  border-bottom: 0;
}

.about-row:hover {
  background: var(--surface-hover);
}

.about-row:focus-visible,
.hero-donate:focus-visible,
.check-btn:focus-visible {
  outline: 2px solid var(--accent-color);
  outline-offset: 2px;
}

.row-icon {
  display: grid;
  place-items: center;
  flex: none;
  width: 28px;
  height: 28px;
  border-radius: 7px;
  background: var(--accent-color-light);
  color: var(--accent-color);
}

.row-copy {
  flex: 1;
  min-width: 0;
}

.row-copy b,
.row-copy small {
  display: block;
}

.row-copy b {
  color: var(--text-primary);
  font-size: 12px;
  font-weight: 650;
}

.row-copy small {
  margin-top: 2px;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.4;
}

.row-chev {
  flex: none;
  color: var(--text-muted);
}

.group-foot {
  display: flex;
  justify-content: flex-end;
  padding: 10px 16px;
  border-top: 1px solid var(--border-subtle);
}

.check-btn {
  min-height: 29px;
  padding: 0 9px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
  background: var(--card-bg);
  color: var(--text-primary);
  font-size: 12px;
}

.check-btn:hover {
  border-color: var(--accent-color);
  color: var(--accent-color);
}

@media (max-width: 640px) {
  .about-hero {
    padding: 20px 16px 16px;
  }

  .about-hero h2 {
    font-size: 18px;
  }

  .group-head,
  .about-row,
  .group-foot {
    padding-left: 14px;
    padding-right: 14px;
  }
}
</style>
