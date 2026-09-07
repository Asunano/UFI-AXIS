<template>
  <div class="tunnel-view">
    <!--
      2026-09-05 重排：页面默认只有「概览」+「隧道」两张通栏卡。
      原来是 2 列栅格里塞 5 张卡，概览与看护设置各占半宽、旁边空一半；
      组件管理与看护设置属于「配置一次就不看」的东西，已收进卡头的两个弹窗入口。
    -->
    <!-- 概览：一行读数条，右侧是全局操作与设置入口 -->
    <GridCard title="隧道概览">
      <template #extra>
        <n-space :size="6">
          <n-button size="tiny" quaternary :loading="loading" @click="loadAll">刷新</n-button>
          <n-button size="tiny" quaternary @click="showComponentsModal = true">组件管理</n-button>
          <n-button size="tiny" quaternary @click="showGuardModal = true">看护设置</n-button>
          <n-button size="tiny" @click="confirmClearAllLogs">清空全部日志</n-button>
          <n-button size="tiny" type="error" ghost @click="confirmStopAll">全部停止</n-button>
        </n-space>
      </template>
      <!--
        概览不再用胶囊读数条：frpc / cloudflared 是「组件装了没、在跑几条」这种带状态的东西，
        套一层胶囊底色反而把状态色和背景色搅在一起。改成「状态点 + 两行文字」的裸排版，
        唯一的动效是在跑时状态点外围有一圈呼吸光环（.ov-dot-live），它是有语义的：
        亮着且在呼吸 = 真的有进程在跑。
      -->
      <div class="overview">
        <div v-for="c in overviewComponents" :key="c.key" class="ov-item" :class="{ 'ov-item-off': !c.installed }">
          <span class="ov-dot" :class="{ 'ov-dot-live': c.running > 0 }"></span>
          <div class="ov-text">
            <div class="ov-line">
              <span class="ov-name">{{ c.name }}</span>
              <span class="ov-value">{{ c.installed ? c.version || '版本未知' : '未安装' }}</span>
            </div>
            <div class="ov-sub">
              <template v-if="!c.installed">组件缺失，对应隧道无法启动</template>
              <template v-else-if="c.running">{{ c.running }} 条在跑</template>
              <template v-else>已就绪，当前无运行</template>
            </div>
          </div>
          <n-button v-if="!c.installed" size="tiny" type="primary" @click="showComponentsModal = true">去安装</n-button>
        </div>

        <div class="ov-item">
          <span class="ov-dot ov-dot-idle"></span>
          <div class="ov-text">
            <div class="ov-line">
              <span class="ov-name">本机服务端口</span>
              <span class="ov-value">{{ status.localPort || '--' }}</span>
            </div>
            <div class="ov-sub">隧道回源指向这个端口</div>
          </div>
        </div>
      </div>
      <div class="hint">
        「全部停止」会同时清空看护期望列表（frp_desired / cf_desired），停掉的通道不会被自动重连拉起。
      </div>
    </GridCard>

    <!--
      隧道：FRP 与 Cloudflare 合并成一张卡，卡头用按钮组切换。
      两边的条目本来就是同一个 Instance 类型、六个操作一一对应，所以列表模板只写一份
      （原先是两段近乎逐字相同的模板），由 tunnelKind 决定调哪一组 handler。
      切换控件用 n-button-group 而不是 n-tabs：卡内切换在本项目统一是按钮组
      （见仪表盘 NetDetailChartCard），n-tabs 留给页面级分栏。
    -->
    <GridCard>
      <template #title>
        <n-button-group size="small">
          <n-button :type="isFrp ? 'primary' : 'default'" size="small" @click="tunnelKind = 'frp'">FRP 通道</n-button>
          <n-button :type="isFrp ? 'default' : 'primary'" size="small" @click="tunnelKind = 'cf'">
            Cloudflare 隧道
          </n-button>
        </n-button-group>
      </template>
      <template #extra>
        <n-space :size="6">
          <n-button size="tiny" type="primary" @click="editItem('')">{{ isFrp ? '新建通道' : '新建隧道' }}</n-button>
          <n-button size="tiny" type="error" ghost :disabled="!activeGroup.running.length" @click="stopAllOfKind">
            停止全部
          </n-button>
        </n-space>
      </template>

      <!-- 组件没装时先横一条提醒：配置照常可看可改，但启动一定失败，
           所以是横幅而不是把列表整个替换掉 -->
      <div v-if="!activeInstalled" class="warn-bar">
        <span class="warn-text">{{ activeBinary }} 未安装，{{ isFrp ? '通道' : '隧道' }}无法启动</span>
        <n-button size="tiny" type="primary" @click="showComponentsModal = true">去安装</n-button>
      </div>

      <div v-if="!activeGroup.items.length" class="empty">
        <n-empty :description="isFrp ? '暂无 FRP 通道配置' : '暂无 Cloudflare 隧道'">
          <template v-if="!activeInstalled" #extra>
            <n-button size="small" type="primary" @click="showComponentsModal = true">
              先安装 {{ activeBinary }}
            </n-button>
          </template>
        </n-empty>
      </div>
      <div v-else class="inst-list">
        <div v-for="it in activeGroup.items" :key="it.name" class="inst-item">
          <div class="inst-main">
            <div class="inst-title">
              <span class="inst-name">{{ it.name }}</span>
              <n-tag v-if="activeGroup.active === it.name" size="tiny" type="info" :bordered="false">选中</n-tag>
              <n-tag :type="statusTagType(it.status)" size="tiny" :bordered="false">{{ statusLabel(it.status) }}</n-tag>
              <n-tag v-if="!isFrp && !it.token_set" size="tiny" type="warning" :bordered="false">未设置 token</n-tag>
            </div>
            <!-- serverAddr / proxy 数只有 FRP 有 -->
            <div v-if="isFrp" class="inst-sub">
              {{ it.server_addr || '未配置 serverAddr'
              }}<template v-if="it.server_port">:{{ it.server_port }}</template> 　·　{{ it.proxy_count }} 个 proxy
            </div>
            <div v-if="it.last_error" class="inst-err">{{ it.last_error }}</div>
          </div>
          <n-space :size="4" class="inst-actions">
            <n-button
              v-if="!it.running"
              size="tiny"
              type="primary"
              :loading="busy === `${tunnelKind}-start-${it.name}`"
              @click="startItem(it.name)"
              >启动</n-button
            >
            <n-button v-else size="tiny" :loading="busy === `${tunnelKind}-stop-${it.name}`" @click="stopItem(it.name)"
              >停止</n-button
            >
            <n-button size="tiny" quaternary @click="editItem(it.name)">编辑</n-button>
            <n-button size="tiny" quaternary @click="showLog(it.name)">日志</n-button>
            <n-button v-if="activeGroup.active !== it.name" size="tiny" quaternary @click="activateItem(it.name)"
              >设为选中</n-button
            >
            <n-button size="tiny" quaternary type="error" @click="deleteItem(it.name)">删除</n-button>
          </n-space>
        </div>
      </div>
    </GridCard>

    <!-- 组件管理（frpc / cloudflared 不随 APK 分发，按需下载）。
         装完基本不用再看，所以从常驻卡片改成概览卡头的弹窗入口。 -->
    <n-modal v-model:show="showComponentsModal" preset="card" title="组件管理" style="max-width: 760px">
      <div v-if="comp.manifestError" class="inst-err">
        更新源不可用：{{ comp.manifestError }}（仍可用下方「本地上传」安装）
      </div>
      <div class="inst-list">
        <div v-for="c in comp.items" :key="c.id" class="inst-item">
          <div class="inst-main">
            <div class="inst-title">
              <span class="inst-name">{{ c.name }}</span>
              <n-tag v-if="c.installed" size="tiny" type="success" :bordered="false">
                已安装 {{ c.installed_version || '版本未知' }}
              </n-tag>
              <n-tag v-else size="tiny" type="warning" :bordered="false">未安装</n-tag>
              <n-tag v-if="c.update_available" size="tiny" type="info" :bordered="false">
                有新版 {{ c.latest_version }}
              </n-tag>
              <n-tag v-if="c.source === 'manual'" size="tiny" :bordered="false">本地上传</n-tag>
              <n-tag v-if="c.source === 'legacy'" size="tiny" :bordered="false">旧版迁移</n-tag>
            </div>
            <div class="inst-sub">
              {{ c.description }}
              <template v-if="c.installed">　·　占用 {{ formatBytes(c.installed_size) }}</template>
              <template v-else-if="c.download_size">　·　需下载约 {{ formatBytes(c.download_size) }}</template>
            </div>
            <div v-if="c.upstream" class="inst-sub">
              上游：{{ c.upstream }}<template v-if="c.latest_version">　·　最新 {{ c.latest_version }}</template>
            </div>
            <n-progress
              v-if="comp.progress.id === c.id && progressActive"
              :percentage="comp.progress.percent"
              :height="6"
              :show-indicator="false"
              style="margin-top: 6px"
            />
            <div v-if="comp.progress.id === c.id && comp.progress.message" class="inst-sub">
              {{ comp.progress.message }}
            </div>
          </div>
          <n-space :size="4" class="inst-actions">
            <n-button
              v-if="!c.installed || c.update_available"
              size="tiny"
              type="primary"
              :disabled="!c.available || progressActive"
              :loading="comp.progress.id === c.id && progressActive"
              @click="installComponent(c.id)"
            >
              {{ c.installed ? '更新' : '下载安装' }}
            </n-button>
            <n-button size="tiny" quaternary :disabled="progressActive" @click="pickUpload(c.id)">本地上传</n-button>
            <n-button
              v-if="c.installed"
              size="tiny"
              quaternary
              type="error"
              :disabled="progressActive"
              @click="confirmUninstall(c)"
              >卸载</n-button
            >
          </n-space>
        </div>
      </div>
      <div class="hint">
        组件从上游官方 release 直链下载，经 SHA-256 校验与 arm64 ELF 体检后安装到 filesDir/components/。
        大陆网络可在「设置 → 更新」里配置镜像前缀加速。无外网时用「本地上传」手动提供裸二进制或官方 tar.gz。
      </div>
      <input ref="uploadInput" type="file" style="display: none" @change="onUploadPicked" />
      <template #footer>
        <n-space justify="end" :size="8">
          <n-button size="small" :loading="comp.refreshing" @click="loadComponents(true)">检查更新</n-button>
          <n-button size="small" @click="showComponentsModal = false">关闭</n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- 看护设置：配一次就不再动，同样收进弹窗 -->
    <n-modal v-model:show="showGuardModal" preset="card" title="看护设置" style="max-width: 560px">
      <ToggleRow
        label="自动重连"
        description="进程意外退出时按下面的间隔重新拉起（仅对期望在跑的通道生效）"
        :model-value="settings.auto_reconnect"
        :loading="settingsSaving"
        @update:model-value="(v: boolean) => saveSettings({ auto_reconnect: v })"
      />
      <ToggleRow label="重连间隔" description="10 - 120 秒；改小会立即重排看护协程">
        <template #control>
          <n-input-number
            :value="settings.reconnect_interval_sec"
            :min="10"
            :max="120"
            size="small"
            style="width: 120px"
            @update:value="(v: number | null) => v && saveSettings({ reconnect_interval_sec: v })"
          />
        </template>
      </ToggleRow>
      <ToggleRow
        label="失败时通知"
        description="隧道由非 Error 变为 Error 时推送通知（这是隧道通知的唯一真源）"
        :model-value="settings.notify_on_failure"
        :loading="settingsSaving"
        @update:model-value="(v: boolean) => saveSettings({ notify_on_failure: v })"
      />
      <InfoRow label="最大连续失败次数" :value="String(settings.max_reconnect_attempts)" />
      <div class="desired-row">
        <span class="row-hint">看护期望（FRP）：</span>
        <n-space :size="4">
          <n-tag v-for="n in settings.frp_desired" :key="`fd-${n}`" size="tiny" :bordered="false">{{ n }}</n-tag>
          <span v-if="!settings.frp_desired.length" class="row-hint">（空）</span>
        </n-space>
      </div>
      <div class="desired-row">
        <span class="row-hint">看护期望（CF）：</span>
        <n-space :size="4">
          <n-tag v-for="n in settings.cf_desired" :key="`cd-${n}`" size="tiny" :bordered="false">{{ n }}</n-tag>
          <span v-if="!settings.cf_desired.length" class="row-hint">（空）</span>
        </n-space>
      </div>
      <template #footer>
        <n-space justify="end" :size="8">
          <n-button size="small" @click="showGuardModal = false">关闭</n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- FRP 配置编辑 -->
    <n-modal
      v-model:show="frpEditor.show"
      preset="card"
      :title="frpEditor.isNew ? '新建 FRP 通道' : `编辑 ${frpEditor.name}`"
      style="max-width: 720px"
    >
      <n-space vertical :size="10">
        <n-input
          v-if="frpEditor.isNew"
          v-model:value="frpEditor.name"
          size="small"
          placeholder='通道名（不能含 \\ / : * ? " &lt; &gt; | 逗号，最长 64）'
        />
        <n-input
          v-model:value="frpEditor.toml"
          type="textarea"
          :autosize="{ minRows: 12, maxRows: 24 }"
          placeholder="frpc TOML 配置"
        />
        <span class="row-hint">
          core 只做行级解析取 serverAddr / server_port / proxy 数；toml 为空会被拒绝（400），不会写成空文件。
        </span>
      </n-space>
      <template #footer>
        <n-space justify="end" :size="8">
          <n-button size="small" @click="frpEditor.show = false">取消</n-button>
          <n-button size="small" type="primary" :loading="frpEditor.saving" @click="saveFrpConfig">保存</n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- CF token 编辑 -->
    <n-modal
      v-model:show="cfEditor.show"
      preset="card"
      :title="cfEditor.isNew ? '新建 Cloudflare 隧道' : `编辑 ${cfEditor.name}`"
      style="max-width: 560px"
    >
      <n-space vertical :size="10">
        <n-input
          v-if="cfEditor.isNew"
          v-model:value="cfEditor.name"
          size="small"
          placeholder="隧道名（命名规则同 FRP）"
        />
        <n-input
          v-model:value="cfEditor.token"
          type="password"
          show-password-on="click"
          size="small"
          placeholder="cloudflared 隧道 token"
        />
        <span class="row-hint">
          core 的 PUT 要求 token 非空，所以编辑时会先回读现有 token 预填 —— 该端点本身就是明文返回 token 的。
        </span>
      </n-space>
      <template #footer>
        <n-space justify="end" :size="8">
          <n-button size="small" @click="cfEditor.show = false">取消</n-button>
          <n-button size="small" type="primary" :loading="cfEditor.saving" @click="saveCfTunnel">保存</n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- 日志查看 -->
    <n-modal
      v-model:show="logView.show"
      preset="card"
      :title="`${logView.kind === 'frp' ? 'FRP' : 'CF'} 日志 · ${logView.name}`"
      style="max-width: 900px"
    >
      <n-space :size="8" align="center" style="margin-bottom: 8px">
        <n-switch v-model:value="logView.full" size="small" @update:value="refreshLog" />
        <span class="row-hint"
          >读取运行期日志文件尾部（关闭则只看内存最近 200 行；停止后文件不存在会自动回落内存）</span
        >
      </n-space>
      <pre class="log-box">{{ logView.text || '（无输出）' }}</pre>
      <template #footer>
        <n-space justify="end" :size="8">
          <n-button size="small" :loading="logView.clearing" @click="clearInstanceLog">清空本实例日志</n-button>
          <n-button size="small" :loading="logView.loading" @click="refreshLog">刷新</n-button>
          <n-button size="small" @click="logView.show = false">关闭</n-button>
        </n-space>
      </template>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue';
import { useInterval } from '@/composables/useRealtime';
import { useMessage, useDialog } from 'naive-ui';
import { useCancellableApi } from '@/composables/useCancellableApi';
import { formatBytes } from '@/composables/utils';
import GridCard from '@/components/GridCard.vue';
import InfoRow from '@/components/InfoRow.vue';
import ToggleRow from '@/components/ToggleRow.vue';

const message = useMessage();
const dialog = useDialog();
const api = useCancellableApi();

/**
 * core 的 /api/tunnel 有三种失败形态，必须都认：
 * ① respondFail：真实 HTTP 码 + { success:false, ok:false, error, message, code }（400/404）
 * ② 业务失败：**HTTP 200** + { success:false, message:中文原因 }（start/stop/delete 都是这种）
 * ③ 裸异常：{ error } 单字段（body 非 JSON 对象 / 未捕获异常 500）
 * 所以每个动作都要先看 HTTP 是否抛错，再看 body.success。
 */
function errText(e: any, fallback: string): string {
  const d = e?.response?.data;
  return d?.message || d?.error || fallback;
}

interface Instance {
  name: string;
  running: boolean;
  status: string;
  last_error: string;
  server_addr?: string;
  server_port?: number;
  proxy_count?: number;
  token_set?: boolean;
}

const loading = ref(false);
const busy = ref<string | null>(null);

const status = reactive({
  frpVersion: '',
  frpRunning: 0,
  frpInstalled: true,
  cfVersion: '',
  cfRunning: 0,
  cfInstalled: true,
  localPort: 0,
});

const settings = reactive({
  auto_reconnect: true,
  reconnect_interval_sec: 30,
  notify_on_failure: true,
  max_reconnect_attempts: 3,
  frp_desired: [] as string[],
  cf_desired: [] as string[],
});
const settingsSaving = ref(false);

const frp = reactive({ active: '', running: [] as string[], items: [] as Instance[] });
const cf = reactive({ active: '', running: [] as string[], items: [] as Instance[] });

// ── 两类隧道合并成一张卡（2026-09-05）──
// frp.items 与 cf.items 本来就是同一个 Instance[]，六个操作也一一对应，
// 所以列表模板只写一份，由 tunnelKind 决定调哪一组 handler。
// 下面这几个 wrapper 是模板与 frp*/cf* 两组函数之间的唯一桥梁 ——
// 新增一类隧道时只要在这里各加一个分支，模板不用动。
const tunnelKind = ref<'frp' | 'cf'>('frp');
const isFrp = computed(() => tunnelKind.value === 'frp');
const activeGroup = computed(() => (isFrp.value ? frp : cf));
const startItem = (name: string) => (isFrp.value ? startFrp(name) : startCf(name));
const stopItem = (name: string) => (isFrp.value ? stopFrp(name) : stopCf(name));
const activateItem = (name: string) => (isFrp.value ? activateFrp(name) : activateCf(name));
const editItem = (name: string) => (isFrp.value ? openFrpEditor(name) : openCfEditor(name));
const deleteItem = (name: string) => (isFrp.value ? confirmDeleteFrp(name) : confirmDeleteCf(name));
const stopAllOfKind = () => (isFrp.value ? stopAllFrp() : stopAllCf());
const showLog = (name: string) => openLog(tunnelKind.value, name);

/** 当前这类隧道依赖的二进制装了没 —— 没装时列表上方横一条提醒并给安装入口 */
const activeInstalled = computed(() => (isFrp.value ? status.frpInstalled : status.cfInstalled));
const activeBinary = computed(() => (isFrp.value ? 'frpc' : 'cloudflared'));

/** 概览里的两个组件行；「本机服务端口」不属于组件，单独写在模板里 */
const overviewComponents = computed(() => [
  {
    key: 'frp',
    name: 'frpc',
    installed: status.frpInstalled,
    version: status.frpVersion,
    running: status.frpRunning,
  },
  {
    key: 'cf',
    name: 'cloudflared',
    installed: status.cfInstalled,
    version: status.cfVersion,
    running: status.cfRunning,
  },
]);

// 组件管理与看护设置从常驻卡片改成弹窗，入口在概览卡头
const showComponentsModal = ref(false);
const showGuardModal = ref(false);

// ── 可选组件（frpc / cloudflared 按需下载）──

interface ComponentItem {
  id: string;
  name: string;
  description: string;
  installed: boolean;
  installed_version: string;
  installed_size: number;
  source: string;
  latest_version: string;
  download_size: number;
  archive: string;
  upstream: string;
  available: boolean;
  update_available: boolean;
}

const comp = reactive({
  items: [] as ComponentItem[],
  progress: { id: '', state: 'idle', percent: 0, message: '' },
  manifestError: '',
  refreshing: false,
});

/** 安装任务是否在进行中：进行中要禁掉所有安装/卸载按钮（core 侧同一时刻只允许一个任务） */
const progressActive = computed(() =>
  ['downloading', 'verifying', 'extracting', 'installing'].includes(comp.progress.state)
);

const uploadInput = ref<HTMLInputElement | null>(null);
const uploadTargetId = ref('');

function applyComponentStatus(data: any) {
  comp.progress = {
    id: data?.id || '',
    state: data?.state || 'idle',
    percent: data?.percent ?? 0,
    message: data?.message || '',
  };
  comp.manifestError = data?.manifest_error || '';
}

/** [refresh] 为 true 时 core 会重新拉一次 version.json（否则用进程内缓存，避免轮询打网络） */
async function loadComponents(refresh = false) {
  if (refresh) comp.refreshing = true;
  try {
    const { data } = await api.get('/api/components', { params: refresh ? { refresh: 'true' } : {} });
    comp.items = data?.components || [];
    applyComponentStatus(data);
    if (refresh && comp.manifestError) message.warning(comp.manifestError);
  } catch (e: any) {
    if (refresh) message.error(errText(e, '读取组件列表失败'));
  } finally {
    comp.refreshing = false;
  }
}

async function installComponent(id: string) {
  try {
    const { data } = await api.post(`/api/components/${encodeURIComponent(id)}/install`);
    applyComponentStatus(data);
    message.info('已开始下载，可离开本页，进度会继续');
  } catch (e: any) {
    message.error(errText(e, '触发安装失败'));
  }
}

function confirmUninstall(c: ComponentItem) {
  dialog.warning({
    title: `卸载 ${c.name}`,
    content: `会删除 ${formatBytes(c.installed_size)} 的二进制与元数据。相关隧道配置不受影响，但在重新安装前无法启动。`,
    positiveText: '卸载',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await api.post(`/api/components/${encodeURIComponent(c.id)}/uninstall`);
        message.success(`${c.name} 已卸载`);
      } catch (e: any) {
        // 409：还有实例在跑
        message.error(errText(e, '卸载失败'));
      } finally {
        await loadComponents();
        await loadStatus();
      }
    },
  });
}

function pickUpload(id: string) {
  uploadTargetId.value = id;
  if (uploadInput.value) {
    uploadInput.value.value = '';
    uploadInput.value.click();
  }
}

/**
 * 本地上传走 raw body（application/octet-stream），不用 multipart：
 * cloudflared 裸二进制约 36MB，multipart 那套要先在内存里攒一份，低端设备会 OOM。
 * core 按文件头自动识别裸 ELF 与 tar.gz，安装后跑 --version 回填版本号。
 */
async function onUploadPicked(ev: Event) {
  const file = (ev.target as HTMLInputElement).files?.[0];
  const id = uploadTargetId.value;
  if (!file || !id) return;
  try {
    await api.post(`/api/components/${encodeURIComponent(id)}/upload`, file, {
      headers: { 'Content-Type': 'application/octet-stream' },
    });
    message.success(`${id} 已安装`);
  } catch (e: any) {
    message.error(errText(e, '上传安装失败'));
  } finally {
    uploadTargetId.value = '';
    await loadComponents();
    await loadStatus();
  }
}

function statusLabel(s: string): string {
  const map: Record<string, string> = { Running: '运行中', Stopped: '已停止', Error: '异常' };
  return map[s] || s;
}
function statusTagType(s: string): 'default' | 'success' | 'error' {
  if (s === 'Running') return 'success';
  if (s === 'Error') return 'error';
  return 'default';
}

async function loadStatus() {
  try {
    // 该端点会 fork 进程探测版本号（最长 5s），因此轮询间隔不宜太短
    const { data } = await api.get('/api/tunnel/status');
    status.frpVersion = data?.frp?.version || '';
    status.frpRunning = data?.frp?.running_count ?? 0;
    status.frpInstalled = data?.frp?.installed !== false;
    status.cfVersion = data?.cf_tunnel?.version || '';
    status.cfRunning = data?.cf_tunnel?.running_count ?? 0;
    status.cfInstalled = data?.cf_tunnel?.installed !== false;
    status.localPort = data?.local_port ?? 0;
  } catch {
    /* 静默 */
  }
}

async function loadSettings() {
  try {
    // GET 返回裸设置对象；PUT 的成功响应则包了一层 settings，两处形态不同
    const { data } = await api.get('/api/tunnel/settings');
    Object.assign(settings, {
      auto_reconnect: data?.auto_reconnect ?? true,
      reconnect_interval_sec: data?.reconnect_interval_sec ?? 30,
      notify_on_failure: data?.notify_on_failure ?? true,
      max_reconnect_attempts: data?.max_reconnect_attempts ?? 3,
      frp_desired: data?.frp_desired ?? [],
      cf_desired: data?.cf_desired ?? [],
    });
  } catch {
    /* 静默 */
  }
}

// PUT 是严格字段级更新（未传的字段绝不覆盖），所以只发变化的那一项
async function saveSettings(patch: Record<string, any>) {
  settingsSaving.value = true;
  try {
    const { data } = await api.put('/api/tunnel/settings', patch);
    if (data?.settings) Object.assign(settings, data.settings);
  } catch (e: any) {
    message.error(errText(e, '保存看护设置失败'));
    await loadSettings();
  } finally {
    settingsSaving.value = false;
  }
}

async function loadFrp() {
  try {
    const { data } = await api.get('/api/tunnel/frp/configs');
    frp.active = data?.active || '';
    frp.running = data?.running || [];
    frp.items = data?.items || [];
  } catch {
    /* 静默 */
  }
}

async function loadCf() {
  try {
    const { data } = await api.get('/api/tunnel/cf/tunnels');
    cf.active = data?.active || '';
    cf.running = data?.running || [];
    cf.items = data?.items || [];
  } catch {
    /* 静默 */
  }
}

async function loadAll() {
  loading.value = true;
  try {
    await Promise.all([loadStatus(), loadSettings(), loadFrp(), loadCf(), loadComponents()]);
  } finally {
    loading.value = false;
  }
}

/** 统一处理形态②：HTTP 200 但 success=false。 */
async function act(key: string, fn: () => Promise<any>, okMsg: string, failMsg: string) {
  busy.value = key;
  try {
    const { data } = await fn();
    if (data?.success === false) {
      message.error(data?.message || failMsg);
    } else {
      message.success(okMsg);
    }
  } catch (e: any) {
    message.error(errText(e, failMsg));
  } finally {
    busy.value = null;
    await loadAll();
  }
}

// ── FRP 动作 ──
// start 是**同步**的：core 内部启进程后等 1.5s 存活探测，秒退即判失败并把原因写进 message，
// 所以返回时状态已确定，不需要额外轮询。
const startFrp = (name: string) =>
  act(
    `frp-start-${name}`,
    () => api.post(`/api/tunnel/frp/config/${encodeURIComponent(name)}/start`),
    `${name} 已启动`,
    `${name} 启动失败`
  );
const stopFrp = (name: string) =>
  act(
    `frp-stop-${name}`,
    () => api.post(`/api/tunnel/frp/config/${encodeURIComponent(name)}/stop`),
    `${name} 已停止`,
    `${name} 停止失败`
  );
const activateFrp = (name: string) =>
  act(
    `frp-act-${name}`,
    () => api.post(`/api/tunnel/frp/config/${encodeURIComponent(name)}/activate`),
    `已选中 ${name}`,
    '设置选中失败'
  );
const stopAllFrp = () =>
  act('frp-stop-all', () => api.post('/api/tunnel/frp/stop'), '已停止全部 FRP 通道', '部分 frpc 停止失败');

function confirmDeleteFrp(name: string) {
  dialog.warning({
    title: '删除 FRP 通道',
    content: `确定删除「${name}」？配置文件会被删除；若通道在运行会先尝试停止，停不掉则不会删除。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: () =>
      act(
        `frp-del-${name}`,
        () => api.delete(`/api/tunnel/frp/config/${encodeURIComponent(name)}`),
        `${name} 已删除`,
        `${name} 删除失败`
      ),
  });
}

const frpEditor = reactive({ show: false, isNew: true, name: '', toml: '', saving: false });

async function openFrpEditor(name: string) {
  frpEditor.isNew = !name;
  frpEditor.name = name;
  frpEditor.toml = '';
  frpEditor.saving = false;
  frpEditor.show = true;
  if (!name) return;
  try {
    const { data } = await api.get(`/api/tunnel/frp/config/${encodeURIComponent(name)}`);
    frpEditor.toml = data?.toml || '';
  } catch (e: any) {
    message.error(errText(e, '读取配置失败'));
  }
}

async function saveFrpConfig() {
  const name = frpEditor.name.trim();
  if (!name) {
    message.error('请填写通道名');
    return;
  }
  // core 对空 toml 会 400（T40-2 之前会静默写成空文件），这里先拦一道给出更直接的提示
  if (!frpEditor.toml.trim()) {
    message.error('TOML 配置不能为空');
    return;
  }
  frpEditor.saving = true;
  try {
    await api.put(`/api/tunnel/frp/config/${encodeURIComponent(name)}`, { toml: frpEditor.toml });
    message.success('配置已保存');
    frpEditor.show = false;
    await loadAll();
  } catch (e: any) {
    // 400 常见原因：名字含非法字符（\ / : * ? " < > | 逗号 .. 或超 64 字符）
    message.error(errText(e, '保存失败'));
  } finally {
    frpEditor.saving = false;
  }
}

// ── CF 动作 ──
const startCf = (name: string) =>
  act(
    `cf-start-${name}`,
    () => api.post(`/api/tunnel/cf/tunnel/${encodeURIComponent(name)}/start`),
    `${name} 已启动`,
    `${name} 启动失败`
  );
const stopCf = (name: string) =>
  act(
    `cf-stop-${name}`,
    () => api.post(`/api/tunnel/cf/tunnel/${encodeURIComponent(name)}/stop`),
    `${name} 已停止`,
    `${name} 停止失败`
  );
const activateCf = (name: string) =>
  act(
    `cf-act-${name}`,
    () => api.post(`/api/tunnel/cf/tunnel/${encodeURIComponent(name)}/activate`),
    `已选中 ${name}`,
    '设置选中失败'
  );
const stopAllCf = () =>
  act('cf-stop-all', () => api.post('/api/tunnel/cf/stop'), '已停止全部 CF 隧道', '部分 cloudflared 停止失败');

function confirmDeleteCf(name: string) {
  dialog.warning({
    title: '删除 Cloudflare 隧道',
    content: `确定删除「${name}」？token 会一并删除；若隧道在运行会先尝试停止，停不掉则不会删除。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: () =>
      act(
        `cf-del-${name}`,
        () => api.delete(`/api/tunnel/cf/tunnel/${encodeURIComponent(name)}`),
        `${name} 已删除`,
        `${name} 删除失败`
      ),
  });
}

const cfEditor = reactive({ show: false, isNew: true, name: '', token: '', saving: false });

async function openCfEditor(name: string) {
  cfEditor.isNew = !name;
  cfEditor.name = name;
  cfEditor.token = '';
  cfEditor.saving = false;
  cfEditor.show = true;
  if (!name) return;
  try {
    // core 的 PUT 强制 token 非空，无法「留空表示不修改」，所以必须回读预填
    const { data } = await api.get(`/api/tunnel/cf/tunnel/${encodeURIComponent(name)}`);
    cfEditor.token = data?.token || '';
  } catch (e: any) {
    message.error(errText(e, '读取隧道失败'));
  }
}

async function saveCfTunnel() {
  const name = cfEditor.name.trim();
  if (!name) {
    message.error('请填写隧道名');
    return;
  }
  if (!cfEditor.token.trim()) {
    message.error('token 不能为空');
    return;
  }
  cfEditor.saving = true;
  try {
    await api.put(`/api/tunnel/cf/tunnel/${encodeURIComponent(name)}`, { token: cfEditor.token });
    message.success('隧道已保存');
    cfEditor.show = false;
    await loadAll();
  } catch (e: any) {
    message.error(errText(e, '保存失败'));
  } finally {
    cfEditor.saving = false;
  }
}

// ── 日志 ──
const logView = reactive({
  show: false,
  kind: 'frp' as 'frp' | 'cf',
  name: '',
  text: '',
  full: false,
  loading: false,
  clearing: false,
});

function logPath(): string {
  const n = encodeURIComponent(logView.name);
  return logView.kind === 'frp' ? `/api/tunnel/frp/config/${n}/log` : `/api/tunnel/cf/tunnel/${n}/log`;
}

async function openLog(kind: 'frp' | 'cf', name: string) {
  logView.kind = kind;
  logView.name = name;
  logView.text = '';
  logView.full = false;
  logView.show = true;
  await refreshLog();
}

async function refreshLog() {
  logView.loading = true;
  try {
    const { data } = await api.get(logPath(), { params: logView.full ? { full: 1 } : {} });
    logView.text = data?.log || '';
  } catch (e: any) {
    message.error(errText(e, '读取日志失败'));
  } finally {
    logView.loading = false;
  }
}

/**
 * 单实例清日志：FRP 走 /frp/config/{name}/log/clear，CF 走 /cf/tunnel/{name}/log/clear
 * （路径与 logPath() 同一套 kind 分支，name 已在 logPath 里 encodeURIComponent）。
 *
 * core **不校验 name 是否存在**，未知名字也恒回 { success: true } / HTTP 200，
 * 所以 name 为空必须前端自己拦住 —— 否则会打到形如 .../config//log/clear 的路径却「成功」。
 * 与顶部「清空全部日志」（/api/tunnel/logs/clear，两个引擎所有实例）是两个不同的动作。
 */
async function clearInstanceLog() {
  if (!logView.name.trim()) {
    message.error('实例名为空，未发送请求');
    return;
  }
  logView.clearing = true;
  try {
    await api.post(`${logPath()}/clear`);
    message.success('已清空本实例日志');
    // 重新拉一次而不是就地把 text 置空：清空结果由服务端确认（正常会变成「（无输出）」）
    await refreshLog();
  } catch (e: any) {
    message.error(errText(e, '清空失败'));
  } finally {
    logView.clearing = false;
  }
}

function confirmClearAllLogs() {
  dialog.warning({
    title: '清空全部日志',
    content: '会清空两个引擎所有实例的内存缓冲，并截断运行期日志文件。',
    positiveText: '清空',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await api.post('/api/tunnel/logs/clear');
        message.success('已清空全部日志');
      } catch (e: any) {
        message.error(errText(e, '清空失败'));
      }
    },
  });
}

function confirmStopAll() {
  dialog.warning({
    title: '停止全部隧道',
    content: '会停止所有 FRP 通道与 CF 隧道，并清空看护期望列表（之后不会被自动重连拉起）。',
    positiveText: '全部停止',
    negativeText: '取消',
    onPositiveClick: () => act('stop-all', () => api.post('/api/tunnel/stop'), '已全部停止', '部分进程停止失败'),
  });
}

onMounted(() => {
  loadAll();
});

// status 端点会 fork 进程探版本，5s 已是比较克制的间隔
useInterval(() => {
  loadStatus();
  loadFrp();
  loadCf();
}, 5000);

/**
 * 组件安装进度单独用 1s 快轮询，且只在有任务时打网络。
 * 任务从"进行中"落到终态时补拉一次列表——安装完成后 installed/版本号才会变。
 */
let lastProgressActive = false;
useInterval(async () => {
  if (!progressActive.value && !lastProgressActive) return;
  try {
    const { data } = await api.get('/api/components/status');
    applyComponentStatus(data);
  } catch {
    /* 静默 */
  }
  if (lastProgressActive && !progressActive.value) {
    if (comp.progress.state === 'failed') message.error(comp.progress.message || '组件安装失败');
    else if (comp.progress.state === 'done') message.success(comp.progress.message || '组件安装完成');
    await loadComponents();
    await loadStatus();
  }
  lastProgressActive = progressActive.value;
}, 1000);
</script>

<style scoped>
.tunnel-view {
  /* 只有「概览」「隧道」两张通栏卡，竖排即可。
     原来是 2 列栅格 + .full-width 混排，概览与看护设置各占半宽、旁边空一半 —— 那两张卡已收进弹窗。 */
  display: flex;
  flex-direction: column;
  gap: 16px;
}
/* ── 概览：状态点 + 两行文字，没有胶囊底色 ──
   胶囊底色的问题是它自带一层背景，跟「运行中/未安装」的状态色抢同一个通道；
   这里把状态交给左侧那颗点，文字保持裸排版，卡片里就只有一种视觉语言。 */
.overview {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 4px 24px;
}
.ov-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 0;
}
.ov-text {
  flex: 1;
  min-width: 0;
}
.ov-line {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}
.ov-name {
  font-size: 13px;
  color: var(--text-secondary);
}
.ov-value {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.01em;
}
.ov-sub {
  margin-top: 2px;
  font-size: 12px;
  color: var(--text-muted);
}
/* 未安装：整行压暗，让「缺组件」这件事一眼可见，但不至于像报错那样刺眼 */
.ov-item-off .ov-value {
  color: var(--text-muted);
  font-weight: 500;
}

/* ── 状态点与呼吸光环 ──
   这是这张卡唯一的动效，而且是有语义的：只有真的有进程在跑才会呼吸。
   静态点用 currentColor 撑起 box-shadow 的内环，避免再引一个变量。 */
.ov-dot {
  flex: none;
  width: 8px;
  height: 8px;
  margin-top: 6px;
  border-radius: var(--radius-pill);
  background: var(--text-muted);
  position: relative;
}
.ov-dot-idle {
  background: var(--border-subtle);
}
.ov-item-off .ov-dot {
  background: var(--warning-color, #f0a020);
}
.ov-dot-live {
  background: var(--success-color, #18a058);
}
/* 光环是独立的伪元素：从点的大小扩散到 3 倍并淡出，
   scale 与 opacity 都走合成层，不触发重排 */
.ov-dot-live::after {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: inherit;
  background: inherit;
  animation: ov-pulse 1.8s ease-out infinite;
}
@keyframes ov-pulse {
  0% {
    transform: scale(1);
    opacity: 0.55;
  }
  70% {
    transform: scale(3);
    opacity: 0;
  }
  100% {
    transform: scale(3);
    opacity: 0;
  }
}
/* 尊重系统的「减少动态效果」：关掉呼吸，只留常亮的点 */
@media (prefers-reduced-motion: reduce) {
  .ov-dot-live::after {
    animation: none;
  }
}

/* 组件缺失横幅：配置还能看能改，只是启动会失败，所以是提醒而非拦截 */
.warn-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 10px;
  padding: 8px 12px;
  border: 1px solid var(--warning-color, #f0a020);
  border-radius: var(--radius-sm);
  background: rgba(240, 160, 32, 0.08);
}
.warn-text {
  font-size: 12px;
  color: var(--text-primary);
}
/* .row / .row-text / .row-label 已退役：三条看护设置行改用公共 ToggleRow
   （数字框那条走它的 #control 槽）。.row-hint 仍被期望标签、弹窗说明文字用着，保留。 */
.row-hint {
  font-size: 12px;
  color: var(--text-muted);
}
.hint {
  margin-top: 8px;
  font-size: 12px;
  color: var(--text-muted);
}
.desired-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-top: 8px;
  flex-wrap: wrap;
}
.empty {
  padding: 24px 0;
}
.inst-list {
  display: flex;
  flex-direction: column;
}
.inst-item {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid var(--border-subtle);
}
.inst-item:last-child {
  border-bottom: none;
}
.inst-main {
  min-width: 0;
  flex: 1;
}
.inst-title {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.inst-name {
  font-size: 13px;
  color: var(--text-primary);
  font-weight: 600;
}
.inst-sub {
  margin-top: 3px;
  font-size: 12px;
  color: var(--text-secondary);
  word-break: break-all;
}
.inst-err {
  margin-top: 3px;
  font-size: 12px;
  color: #d03050;
  word-break: break-all;
}
.inst-actions {
  flex-shrink: 0;
}
.log-box {
  max-height: 50vh;
  overflow: auto;
  margin: 0;
  padding: 10px;
  font-size: 12px;
  line-height: 1.5;
  background: var(--code-bg, rgba(128, 128, 128, 0.08));
  border-radius: 4px;
  white-space: pre-wrap;
  word-break: break-all;
}

@media (max-width: 768px) {
  .inst-item {
    flex-wrap: wrap;
  }
  .inst-actions {
    width: 100%;
  }
}
</style>
