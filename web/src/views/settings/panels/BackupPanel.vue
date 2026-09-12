<template>
  <div class="settings-panel">
    <!-- 明文链路提示：只在「隧道来源 + http」这一种组合下出现。
         局域网 http 与隧道 https 都不打扰用户。 -->
    <n-alert v-if="insecureRemote" type="warning" :bordered="false" style="grid-column: 1 / -1">
      当前是通过内网穿透以 HTTP 访问的。备份内容与口令在链路上是明文的，建议改用 HTTPS 或在设备所在的局域网内操作。
    </n-alert>

    <!-- 两张卡都通栏（.full-width），不做两列配对：
         导出卡的高度随「是否加密」变化（关闭后多出风险提示块），恢复卡的高度随
         「是否已选文件 / 是否已预览 / 段数 / 是否替换模式 / 是否有失败明细」变化，
         幅度可达数倍。按栅格铁律「高度随运行时状态变化的卡不参与等高配对」，
         强行两列会在宽屏上留下整块空洞。 -->
    <n-card class="full-width" title="导出备份" size="small">
      <n-space vertical :size="14">
        <n-alert type="default" :bordered="false">
          备份包含服务与设备后台配置、告警与监控设置、通知渠道、定时任务与自动化规则、
          短信拦截规则、隧道通道配置，以及本浏览器的外观偏好。
          <br />
          <strong>不包含</strong>配对凭据与配对密码、历史数据（短信、告警、监控曲线）、下载任务。
          恢复到另一台设备后仍需重新配对。
        </n-alert>

        <ToggleRow
          v-model="encryptExport"
          label="加密备份包"
          description="备份里含设备后台密码、Webhook 地址与令牌等信息，建议保持开启"
        />

        <template v-if="encryptExport">
          <n-input
            v-model:value="exportPassphrase"
            type="password"
            show-password-on="click"
            :placeholder="`设置口令（至少 ${minPassphrase} 个字符）`"
          />
          <n-input v-model:value="exportPassphrase2" type="password" show-password-on="click" placeholder="确认口令" />
          <n-text depth="3" style="font-size: 12px">
            口令不会被保存在设备或备份文件中，遗忘后无法恢复备份内容。
          </n-text>
        </template>

        <n-space justify="end">
          <n-button type="primary" size="small" :loading="exporting" @click="doExport"> 导出并下载 </n-button>
        </n-space>
      </n-space>
    </n-card>

    <n-card class="full-width" title="从备份恢复" size="small">
      <n-space vertical :size="14">
        <n-space align="center" :size="10">
          <n-button size="small" :disabled="importing" @click="pickFile">选择备份文件</n-button>
          <n-text v-if="fileName" depth="2" style="font-size: 13px">{{ fileName }}</n-text>
        </n-space>
        <input ref="fileInputRef" type="file" accept=".ufibak,.zip" style="display: none" @change="onFileSelected" />

        <template v-if="preview">
          <n-descriptions :column="2" size="small" bordered label-placement="left">
            <n-descriptions-item label="来源设备">
              {{ preview.same_device ? '本设备' : preview.device_id || '未知' }}
            </n-descriptions-item>
            <n-descriptions-item label="生成时间">{{ formatTime(preview.created_at) }}</n-descriptions-item>
            <n-descriptions-item label="是否加密">{{ preview.encrypted ? '已加密' : '未加密' }}</n-descriptions-item>
            <n-descriptions-item label="包含段数">{{ preview.sections?.length ?? 0 }}</n-descriptions-item>
          </n-descriptions>

          <n-table :single-line="false" size="small">
            <thead>
              <tr>
                <th>内容</th>
                <th style="width: 96px">条目</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="s in preview.sections" :key="s.path">
                <td>
                  {{ s.label }}
                  <n-tag v-if="s.sensitive_items > 0" size="tiny" type="warning" :bordered="false"> 含敏感信息 </n-tag>
                </td>
                <td>{{ s.items }}</td>
              </tr>
            </tbody>
          </n-table>

          <n-radio-group v-model:value="importMode" size="small">
            <n-space vertical :size="6">
              <n-radio value="merge">合并（只覆盖备份里有的项，其余保持现状）</n-radio>
              <n-radio value="replace">替换（备份里没有的项回到默认值）</n-radio>
            </n-space>
          </n-radio-group>

          <n-input
            v-if="preview.encrypted"
            v-model:value="importPassphrase"
            type="password"
            show-password-on="click"
            placeholder="输入备份口令"
          />

          <n-space justify="end">
            <n-button size="small" :disabled="importing" @click="resetImport">取消</n-button>
            <n-button type="error" size="small" :loading="importing" @click="confirmImport"> 开始恢复 </n-button>
          </n-space>
        </template>

        <template v-if="result">
          <n-alert :type="result.failedCount > 0 ? 'warning' : 'success'" :bordered="false">
            已恢复 {{ result.appliedCount }} 段<span v-if="result.failedCount > 0"
              >， {{ result.failedCount }} 段未成功</span
            >。
            <template v-if="result.needsRestart">
              <br /><strong>端口或设备档位有变化，需要重启核心服务才会生效。</strong>
            </template>
          </n-alert>
          <n-table v-if="result.failed.length" :single-line="false" size="small">
            <thead>
              <tr>
                <th>未成功的项</th>
                <th>原因</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="f in result.failed" :key="f.path">
                <td>{{ f.path }}</td>
                <td>{{ f.reason }}</td>
              </tr>
            </tbody>
          </n-table>
        </template>
      </n-space>
    </n-card>
  </div>
</template>

<script setup lang="ts">
/**
 * 备份与恢复分栏。
 *
 * 恢复分两步：先 `POST /api/backup/preview` 确认将要变更的内容，再 `POST /api/backup/import`。
 * 不做一步到位是刻意的 —— 用户点击「恢复」时应当已经知道备份来自哪台设备、何时导出、
 * 涉及几段配置，否则等同于在不知情的前提下覆盖现有配置。
 *
 * 导出走 axios（`responseType: 'blob'`）而不是裸 fetch：axios 拦截器会自动补设备签名头，
 * 裸 fetch 得自己拼（见 `views/files/filesShared.ts` 的 authHeaders）。
 * 上传走 `application/octet-stream` 原始 body 而不是 multipart：备份包不大，但 multipart
 * 要先在内存里攒一份，低端设备上没必要。
 */
import { ref, computed, onMounted } from 'vue';
import { useMessage, useDialog } from 'naive-ui';
import { getApiClient } from '@/composables/useApi';
import { useAppStore } from '@/stores/app';
import ToggleRow from '@/components/ToggleRow.vue';

interface PreviewSection {
  path: string;
  label: string;
  items: number;
  sensitive_items: number;
}
interface PreviewInfo {
  created_at: number;
  device_id: string;
  encrypted: boolean;
  same_device: boolean;
  sections: PreviewSection[];
}

const api = getApiClient();
const message = useMessage();
const dialog = useDialog();
const appStore = useAppStore();

const PASSPHRASE_HEADER = 'X-Backup-Passphrase';

const minPassphrase = ref(12);
const origin = ref<'lan' | 'tunnel'>('lan');
/** 隧道来源且当前是 http：内容与口令在链路上是明文的 */
const insecureRemote = computed(() => origin.value === 'tunnel' && window.location.protocol === 'http:');

const encryptExport = ref(true);
const exportPassphrase = ref('');
const exportPassphrase2 = ref('');
const exporting = ref(false);

const fileInputRef = ref<HTMLInputElement | null>(null);
const fileName = ref('');
const fileBuffer = ref<ArrayBuffer | null>(null);
const preview = ref<PreviewInfo | null>(null);
const importMode = ref<'merge' | 'replace'>('merge');
const importPassphrase = ref('');
const importing = ref(false);
const result = ref<{
  appliedCount: number;
  failedCount: number;
  failed: { path: string; reason: string }[];
  needsRestart: boolean;
} | null>(null);

onMounted(async () => {
  try {
    const { data } = await api.get('/api/backup/info');
    origin.value = data?.origin === 'tunnel' ? 'tunnel' : 'lan';
    if (typeof data?.min_passphrase_length === 'number') {
      minPassphrase.value = data.min_passphrase_length;
    }
  } catch {
    /* 拿不到就按默认提示，不阻塞面板 */
  }
});

function formatTime(ms: number): string {
  if (!ms) return '未知';
  return new Date(ms).toLocaleString('zh-CN', { hour12: false });
}

// ── 导出 ──

async function doExport() {
  if (encryptExport.value) {
    if (exportPassphrase.value.length < minPassphrase.value) {
      message.warning(`口令至少需要 ${minPassphrase.value} 个字符`);
      return;
    }
    if (exportPassphrase.value !== exportPassphrase2.value) {
      message.warning('两次输入的口令不一致');
      return;
    }
  } else {
    const ok = await confirmPlainExport();
    if (!ok) return;
  }

  exporting.value = true;
  try {
    const { data } = await api.post(
      '/api/backup/export',
      {
        encrypted: encryptExport.value,
        passphrase: encryptExport.value ? exportPassphrase.value : '',
        // core 对明文导出要求显式确认（缺这个标记直接 400）。走不到这里就不会有明文包：
        // 上方 confirmPlainExport() 已经把这句风险摆给用户看过，用户点「仍然不加密」才算确认，
        // 这个标记就是把那次点击翻译给服务端。
        acknowledge_plaintext: !encryptExport.value,
        // 将本浏览器的外观偏好一并写入同一个备份包，恢复时由本页落地
        client: { web: { darkMode: appStore.darkMode, themeId: appStore.themeId } },
      },
      { responseType: 'blob' }
    );
    saveBlob(data as Blob, `ufi-axis-backup-${Date.now()}.ufibak`);
    exportPassphrase.value = '';
    exportPassphrase2.value = '';
    message.success('备份已导出');
  } catch (e: any) {
    message.error(await readBlobError(e));
  } finally {
    exporting.value = false;
  }
}

function confirmPlainExport(): Promise<boolean> {
  return new Promise((resolve) => {
    dialog.warning({
      title: '不加密导出',
      content:
        '备份包会以明文保存设备后台密码、Webhook 地址与令牌等信息。' +
        '获得该文件的任何人都可直接读取，并可借此接管通知渠道。是否仍要不加密导出？',
      positiveText: '仍然不加密',
      negativeText: '返回设置口令',
      onPositiveClick: () => resolve(true),
      onNegativeClick: () => resolve(false),
      onClose: () => resolve(false),
      onMaskClick: () => resolve(false),
    });
  });
}

function saveBlob(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  // 必须先挂进文档，Firefox 才会响应 click()
  document.body.appendChild(a);
  a.click();
  a.remove();
  // 同步 revoke 会取消下载，必须挪到下一个 tick
  setTimeout(() => URL.revokeObjectURL(url), 0);
}

/** responseType 为 blob 时，错误响应体也是 Blob，得读出来才能拿到 error 文案。 */
async function readBlobError(e: any): Promise<string> {
  const data = e?.response?.data;
  if (data instanceof Blob) {
    try {
      const parsed = JSON.parse(await data.text());
      return parsed?.error || parsed?.message || '导出失败';
    } catch {
      return '导出失败';
    }
  }
  return data?.error || data?.message || '导出失败';
}

// ── 恢复 ──

function pickFile() {
  fileInputRef.value?.click();
}

async function onFileSelected(e: Event) {
  const input = e.target as HTMLInputElement;
  const file = input.files?.[0];
  // 立刻清空，否则同一个文件选第二次不会触发 change
  input.value = '';
  if (!file) return;

  fileName.value = file.name;
  fileBuffer.value = await file.arrayBuffer();
  result.value = null;
  importPassphrase.value = '';
  await loadPreview();
}

async function loadPreview() {
  if (!fileBuffer.value) return;
  try {
    const { data } = await api.post('/api/backup/preview', fileBuffer.value, {
      headers: { 'Content-Type': 'application/octet-stream' },
    });
    preview.value = data as PreviewInfo;
  } catch (e: any) {
    preview.value = null;
    message.error(e?.response?.data?.error || '无法读取该备份文件');
  }
}

function resetImport() {
  preview.value = null;
  fileBuffer.value = null;
  fileName.value = '';
  importPassphrase.value = '';
}

function confirmImport() {
  const p = preview.value;
  if (!p || !fileBuffer.value) return;
  if (p.encrypted && !importPassphrase.value) {
    message.warning('这是加密备份，请输入口令');
    return;
  }
  const modeText =
    importMode.value === 'replace'
      ? '「替换」模式会把备份里没有的配置项恢复为默认值'
      : '「合并」模式只覆盖备份里存在的配置项';
  dialog.warning({
    title: '确认恢复配置',
    content:
      `将用${p.same_device ? '本设备' : '其他设备'}于 ${formatTime(p.created_at)} 导出的备份` +
      `覆盖当前配置，共 ${p.sections?.length ?? 0} 段。${modeText}。\n\n` +
      '配对信息与配对密码不受影响；端口等项需要重启核心服务才生效。此操作不可撤销。',
    positiveText: '开始恢复',
    negativeText: '取消',
    onPositiveClick: doImport,
  });
}

async function doImport() {
  if (!fileBuffer.value) return;
  importing.value = true;
  try {
    const headers: Record<string, string> = { 'Content-Type': 'application/octet-stream' };
    if (importPassphrase.value) headers[PASSPHRASE_HEADER] = importPassphrase.value;

    const { data } = await api.post(`/api/backup/import?mode=${importMode.value}`, fileBuffer.value, { headers });

    const failedMap = (data?.failed ?? {}) as Record<string, string>;
    result.value = {
      appliedCount: Array.isArray(data?.applied) ? data.applied.length : 0,
      failedCount: Object.keys(failedMap).length,
      failed: Object.entries(failedMap).map(([path, reason]) => ({ path, reason })),
      needsRestart: data?.needs_restart === true,
    };
    applyWebSection(data?.client_web);
    resetImport();
    message.success('恢复完成');
  } catch (e: any) {
    const status = e?.response?.status;
    if (status === 401) {
      message.error(e?.response?.data?.error || '口令错误或备份文件已损坏');
    } else {
      message.error(e?.response?.data?.error || '恢复失败');
    }
  } finally {
    importing.value = false;
  }
}

/** 落地包里的 web 段。core 只负责原样带回来，语义在这里解释。 */
function applyWebSection(raw: unknown) {
  if (!raw) return;
  try {
    const section = typeof raw === 'string' ? JSON.parse(raw) : raw;
    if (typeof section?.themeId === 'string') appStore.setThemeId(section.themeId);
    if (typeof section?.darkMode === 'boolean' && section.darkMode !== appStore.darkMode) {
      appStore.toggleDarkMode();
    }
  } catch {
    /* 客户端段坏了不影响设备端配置已经恢复成功这件事 */
  }
}
</script>
