<template>
  <n-modal
    v-model:show="show"
    preset="card"
    title="远端存储源"
    class="modal-fit"
    style="--modal-w: 760px; --modal-h: 720px"
  >
    <div class="src-body">
      <!-- 列表 -->
      <template v-if="!form">
        <div class="src-head">
          <span class="src-hint">
            接入 FTP / WebDAV / SMB / S3。保存后在文件管理的「存储」弹层里会多出对应入口，
            可直接浏览、上传、下载与增删改。
          </span>
          <n-button size="small" type="primary" @click="openNew">新增存储源</n-button>
        </div>

        <n-spin :show="loading">
          <div v-if="sources.length" class="src-list">
            <div v-for="s in sources" :key="s.id" class="src-item">
              <div class="src-main">
                <div class="src-title">
                  <span class="src-label">{{ s.label || s.id }}</span>
                  <span class="src-proto">{{ protoLabel(s.protocol) }}</span>
                  <span v-if="!s.enabled" class="src-off">已停用</span>
                </div>
                <div class="src-addr">{{ addrOf(s) }}</div>
                <div class="src-caps">{{ capsTextOf(s) }}</div>
              </div>
              <div class="src-acts">
                <n-button size="tiny" :loading="testingId === s.id" @click="testSaved(s.id)">测试</n-button>
                <n-button size="tiny" @click="openEdit(s)">编辑</n-button>
                <n-button size="tiny" type="error" ghost @click="remove(s)">删除</n-button>
              </div>
            </div>
          </div>
          <n-empty v-else-if="!loading" description="还没有配置任何远端存储源" />
        </n-spin>

        <n-alert v-if="testMsg" :type="testOk ? 'success' : 'error'" :show-icon="false" class="src-test">
          {{ testMsg }}
        </n-alert>
      </template>

      <!-- 新增 / 编辑表单：版式走全局 `.config-grid / .config-item / .config-label`
           （原先本文件自写一份 `.src-form / .form-item / .form-item label`，
           与设置域四份逐字相同 —— 那些已收进 main.css，这里跟着换过来） -->
      <template v-else>
        <div class="config-grid">
          <div class="config-item">
            <span class="config-label">显示名称</span>
            <n-input v-model:value="form.label" size="small" placeholder="例如：家里的 NAS" />
          </div>
          <div class="config-item">
            <span class="config-label">协议</span>
            <n-select v-model:value="form.protocol" size="small" :options="protoOptions" :disabled="isEditing" />
          </div>

          <!-- FTP / WebDAV / SMB 共用 host + port；S3 走 endpoint / region -->
          <template v-if="form.protocol !== 's3'">
            <div class="config-item">
              <span class="config-label">主机</span>
              <n-input v-model:value="form.host" size="small" placeholder="192.168.1.10 或域名" />
            </div>
            <div class="config-item">
              <span class="config-label">端口</span>
              <n-input-number
                v-model:value="form.port"
                size="small"
                :min="1"
                :max="65535"
                :placeholder="String(defaultPort)"
              />
            </div>
          </template>

          <!-- SMB 特有：域 + 共享名 -->
          <template v-if="form.protocol === 'smb'">
            <div class="config-item">
              <span class="config-label">域（可选）</span>
              <n-input v-model:value="form.domain" size="small" placeholder="WORKGROUP" />
            </div>
            <div class="config-item">
              <span class="config-label">共享名</span>
              <n-input v-model:value="form.share" size="small" placeholder="必填，例如 public" />
            </div>
          </template>

          <!-- S3 特有 -->
          <template v-if="form.protocol === 's3'">
            <div class="config-item">
              <span class="config-label">Endpoint（留空则按 region 推导）</span>
              <n-input v-model:value="form.endpoint" size="small" placeholder="https://s3.example.com" />
            </div>
            <div class="config-item">
              <span class="config-label">Region</span>
              <n-input v-model:value="form.region" size="small" placeholder="us-east-1" />
            </div>
            <div class="config-item">
              <span class="config-label">Bucket</span>
              <n-input v-model:value="form.bucket" size="small" placeholder="必填" />
            </div>
          </template>

          <div class="config-item">
            <span class="config-label">{{ form.protocol === 's3' ? 'Access Key' : '用户名' }}</span>
            <n-input v-model:value="form.username" size="small" autocomplete="off" />
          </div>
          <div class="config-item">
            <span class="config-label">{{ form.protocol === 's3' ? 'Secret Key' : '密码' }}</span>
            <n-input
              v-model:value="form.password"
              size="small"
              type="password"
              show-password-on="mousedown"
              :placeholder="isEditing ? '留空或保持掩码 = 不修改' : ''"
            />
          </div>

          <!-- 路径 / 协议开关 -->
          <template v-if="form.protocol !== 'smb'">
            <div class="config-item">
              <span class="config-label">起始路径</span>
              <n-input v-model:value="form.basePath" size="small" placeholder="/" />
            </div>
          </template>
          <!--
            行内开关一律 `.config-item.switch-item`（标签左、控件右）—— 全站三种开关版式里的
            第二种。裸 `n-switch` 在这里是刻意的：这些开关属于「与输入框并排塞进两列栅格」，
            套 ToggleRow 会把一行表单撑成两行。
          -->
          <template v-if="form.protocol === 'ftp' || form.protocol === 'webdav'">
            <div class="config-item switch-item">
              <span class="config-label">使用 TLS（FTPS / HTTPS）</span>
              <n-switch v-model:value="form.useTls" />
            </div>
          </template>
          <template v-if="form.protocol === 'ftp'">
            <div class="config-item switch-item">
              <span class="config-label">被动模式</span>
              <n-switch v-model:value="form.passive" />
            </div>
          </template>
          <template v-if="form.protocol === 'webdav'">
            <div class="config-item switch-item">
              <span class="config-label">跳过证书校验（仍校验主机名）</span>
              <n-switch v-model:value="form.trustAllCerts" />
            </div>
          </template>
          <template v-if="form.protocol === 's3'">
            <div class="config-item switch-item">
              <span class="config-label">Path-Style 寻址</span>
              <n-switch v-model:value="form.pathStyle" />
            </div>
          </template>
          <div class="config-item switch-item">
            <span class="config-label">启用（停用后不在文件管理里出现）</span>
            <n-switch v-model:value="form.enabled" />
          </div>
        </div>

        <n-alert v-if="testMsg" :type="testOk ? 'success' : 'error'" :show-icon="false" class="src-test">
          {{ testMsg }}
        </n-alert>
      </template>
    </div>

    <template #footer>
      <div class="modal-footer">
        <template v-if="form">
          <n-button size="small" @click="cancelForm">返回列表</n-button>
          <n-button size="small" :loading="testing" @click="testInline">测试连接</n-button>
          <n-button size="small" type="primary" :loading="saving" @click="save">保存</n-button>
        </template>
        <template v-else>
          <n-button size="small" @click="show = false">关闭</n-button>
          <n-button size="small" :loading="loading" @click="load">刷新</n-button>
        </template>
      </div>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
/**
 * 远端存储源管理（2026-10-14 新建，web 侧此前**完全没有**这块支持）。
 *
 * core 早就有一套完整实现：`StorageSourceRoutes`（7 个端点）+
 * `FileProviderRegistry`（远端路径方案 `remote:<sourceId>/<相对路径>`）。
 * 文件 API（list / upload / download / rename / delete / mkdir / search…）
 * 全都接受 `remote:` 开头的路径 —— 所以本弹窗只管**配置**，
 * 浏览与读写直接复用文件管理现有那套 UI，不需要另写一套远端浏览器。
 *
 * 密码：core 的响应里是掩码（`manager.isPasswordMasked`），PUT 时传掩码 = 保留旧密码。
 * 所以编辑态下密码框留空或保持掩码都不会把已存的密码清掉（core 侧兜底）。
 * 用户名**没有**掩码保护（core 2026-09-21 修过漏字段导致回填清空 username 的 bug），
 * 所以这里必须把 username 完整回填。
 */
import { ref, reactive, computed, watch } from 'vue';
import { useMessage, useDialog } from 'naive-ui';
import { useCancellableApi } from '@/composables/useCancellableApi';
import { capsTextOf } from '../../filesShared';

const props = defineProps<{ show: boolean }>();
const emit = defineEmits<{ 'update:show': [boolean] }>();

const show = computed({
  get: () => props.show,
  set: (v) => emit('update:show', v),
});

const message = useMessage();
const dialog = useDialog();
const api = useCancellableApi();

interface StorageSource {
  id: string;
  label: string;
  protocol: string;
  host: string;
  port: number;
  username: string;
  basePath: string;
  useTls: boolean;
  passive: boolean;
  encoding: string;
  trustAllCerts: boolean;
  domain: string;
  share: string;
  bucket: string;
  region: string;
  endpoint: string;
  pathStyle: boolean;
  timeoutSec: number;
  enabled: boolean;
  capabilities?: string[];
}

const sources = ref<StorageSource[]>([]);
const loading = ref(false);
const saving = ref(false);
const testing = ref(false);
const testingId = ref('');
const testMsg = ref('');
const testOk = ref(false);

const protoOptions = [
  { label: 'FTP / FTPS', value: 'ftp' },
  { label: 'WebDAV', value: 'webdav' },
  { label: 'SMB / CIFS', value: 'smb' },
  { label: 'S3 兼容对象存储', value: 's3' },
];

function protoLabel(p: string): string {
  return protoOptions.find((o) => o.value === p)?.label || p;
}

/** 列表里那行地址：S3 显示 endpoint/bucket，其余显示 host:port。 */
function addrOf(s: StorageSource): string {
  if (s.protocol === 's3') return `${s.endpoint || `s3.${s.region}.amazonaws.com`} / ${s.bucket}`;
  const port = s.port || defaultPortOf(s.protocol);
  return `${s.host}:${port}${s.share ? ` / ${s.share}` : ''}${s.basePath && s.basePath !== '/' ? ` / ${s.basePath}` : ''}`;
}

function defaultPortOf(p: string): number {
  return p === 'ftp' ? 21 : p === 'webdav' ? 80 : p === 'smb' ? 445 : 443;
}
const defaultPort = computed(() => defaultPortOf(form.value?.protocol || 'ftp'));

// ── 表单 ──
type FormState = {
  id: string;
  label: string;
  protocol: string;
  host: string;
  port: number | null;
  username: string;
  password: string;
  basePath: string;
  useTls: boolean;
  passive: boolean;
  encoding: string;
  trustAllCerts: boolean;
  domain: string;
  share: string;
  bucket: string;
  region: string;
  endpoint: string;
  pathStyle: boolean;
  timeoutSec: number;
  enabled: boolean;
};

const form = ref<FormState | null>(null);
const isEditing = computed(() => !!form.value?.id);

function blankForm(protocol = 'ftp'): FormState {
  return reactive({
    id: '',
    label: '',
    protocol,
    host: '',
    port: null,
    username: '',
    password: '',
    basePath: '/',
    useTls: false,
    passive: true,
    encoding: 'UTF-8',
    trustAllCerts: false,
    domain: '',
    share: '',
    bucket: '',
    region: 'us-east-1',
    endpoint: '',
    pathStyle: true,
    timeoutSec: 15,
    enabled: true,
  }) as FormState;
}

function openNew() {
  testMsg.value = '';
  form.value = blankForm();
}

function openEdit(s: StorageSource) {
  testMsg.value = '';
  form.value = reactive({
    id: s.id,
    label: s.label || '',
    protocol: s.protocol || 'ftp',
    host: s.host || '',
    port: s.port || null,
    // username 必须回填：core 的响应里带它，漏了就会在保存时把用户名清空
    username: s.username || '',
    // 密码留空：core 的响应是掩码，原样提交也会被识别为"保留旧值"，
    // 但留空更直观（用户不填就是不改）
    password: '',
    basePath: s.basePath || '/',
    useTls: !!s.useTls,
    passive: s.passive !== false,
    encoding: s.encoding || 'UTF-8',
    trustAllCerts: !!s.trustAllCerts,
    domain: s.domain || '',
    share: s.share || '',
    bucket: s.bucket || '',
    region: s.region || 'us-east-1',
    endpoint: s.endpoint || '',
    pathStyle: s.pathStyle !== false,
    timeoutSec: s.timeoutSec || 15,
    enabled: s.enabled !== false,
  }) as FormState;
}

function cancelForm() {
  form.value = null;
  testMsg.value = '';
}

/** 提交给 core 的载荷：port 为 null 时用协议默认端口，避免 core 拿到 0。 */
function payload() {
  const f = form.value!;
  return {
    id: f.id,
    label: f.label,
    protocol: f.protocol,
    host: f.host,
    port: f.port || defaultPortOf(f.protocol),
    username: f.username,
    // ⚠ 编辑态下密码留空 = 不修改，此时必须发**掩码**而不是空串：
    // core 的 `isPasswordMasked` 只认字面量 "********"（StorageSourceManager.PASSWORD_MASK），
    // 发空串会被当成"新密码是空"从而把已存密码清掉（源直接连不上）。
    password: f.password || (isEditing.value ? '********' : ''),
    basePath: f.basePath || '/',
    useTls: f.useTls,
    passive: f.passive,
    encoding: f.encoding || 'UTF-8',
    trustAllCerts: f.trustAllCerts,
    domain: f.domain,
    share: f.share,
    bucket: f.bucket,
    region: f.region || 'us-east-1',
    endpoint: f.endpoint,
    pathStyle: f.pathStyle,
    timeoutSec: f.timeoutSec || 15,
    enabled: f.enabled,
  };
}

async function load() {
  loading.value = true;
  try {
    const { data } = await api.get('/api/storage/sources');
    sources.value = data?.sources || [];
  } catch {
    message.error('读取存储源列表失败');
  } finally {
    loading.value = false;
  }
}

async function save() {
  const f = form.value!;
  if (!f.label.trim()) {
    message.warning('请填写显示名称');
    return;
  }
  saving.value = true;
  try {
    if (isEditing.value) {
      await api.put(`/api/storage/sources/${encodeURIComponent(f.id)}`, payload());
      message.success('已保存');
    } else {
      await api.post('/api/storage/sources', payload());
      message.success('已新增');
    }
    cancelForm();
    await load();
  } catch (e: any) {
    // core 的校验失败是 400 + error 字段（host/share/bucket 缺失等），原样透出比笼统报错有用
    message.error(e?.response?.data?.error || '保存失败');
  } finally {
    saving.value = false;
  }
}

async function testInline() {
  testing.value = true;
  testMsg.value = '';
  try {
    const { data } = await api.post('/api/storage/sources/test', payload());
    testOk.value = !!data?.success;
    testMsg.value = `${data?.message || (testOk.value ? '连接成功' : '连接失败')}${
      data?.latency_ms != null ? `（${data.latency_ms} ms）` : ''
    }`;
  } catch (e: any) {
    testOk.value = false;
    testMsg.value = e?.response?.data?.error || '测试失败';
  } finally {
    testing.value = false;
  }
}

async function testSaved(id: string) {
  testingId.value = id;
  testMsg.value = '';
  try {
    const { data } = await api.post(`/api/storage/sources/${encodeURIComponent(id)}/test`);
    testOk.value = !!data?.success;
    testMsg.value = `${data?.message || (testOk.value ? '连接成功' : '连接失败')}${
      data?.latency_ms != null ? `（${data.latency_ms} ms）` : ''
    }`;
  } catch (e: any) {
    testOk.value = false;
    testMsg.value = e?.response?.data?.error || '测试失败';
  } finally {
    testingId.value = '';
  }
}

function remove(s: StorageSource) {
  dialog.warning({
    title: '删除存储源',
    content: `确定删除「${s.label || s.id}」？已上传到该源的文件不受影响，但本设备不再能访问它。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await api.delete(`/api/storage/sources/${encodeURIComponent(s.id)}`);
        message.success('已删除');
        await load();
      } catch {
        message.error('删除失败');
      }
    },
  });
}

// 打开时拉一次列表（弹窗由父组件控制显隐，这里只在打开时取数）
watch(
  () => props.show,
  (v) => {
    if (v) {
      cancelForm();
      load();
    }
  },
  { immediate: true }
);
</script>

<style scoped>
/* 宽高与滚动由全局 `.modal-fit` 统一约束（main.css）：头尾固定、内容区自己滚。
   原先这里手写 `max-height: clamp(160px, calc(100vh - 240px), 560px)`，
   与弹窗自身的高度上限叠成两层约束，矮屏上内容区被压成一条缝。 */
.src-body {
  min-width: 0;
}
.src-head {
  display: flex;
  align-items: flex-start;
  gap: var(--space-3);
  margin-bottom: var(--space-3);
}
.src-hint {
  flex: 1;
  font-size: var(--font-sm);
  color: var(--text-muted);
  line-height: 1.6;
}
.src-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.src-item {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
}
.src-main {
  flex: 1;
  min-width: 0;
}
.src-title {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  /* 源名可能很长（用户自己填的），必须允许它收缩 + 省略，
     否则 360px 下会把协议 chip 和「已停用」挤出卡片 */
  min-width: 0;
}
.src-label {
  font-size: var(--font-md);
  font-weight: 600;
  color: var(--text-primary);
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.src-proto {
  font-size: var(--font-xs);
  padding: 0 6px;
  border-radius: var(--radius-pill);
  color: var(--accent-color);
  background: var(--accent-color-light);
  flex-shrink: 0;
}
.src-off {
  font-size: var(--font-xs);
  color: var(--text-muted);
  flex-shrink: 0;
}
.src-addr {
  font-size: var(--font-sm);
  color: var(--text-secondary);
  margin-top: 2px;
  word-break: break-all;
}
.src-caps {
  font-size: var(--font-xs);
  color: var(--text-muted);
  margin-top: 2px;
}
.src-acts {
  display: flex;
  gap: var(--space-1);
  flex-shrink: 0;
}
.src-test {
  margin-top: var(--space-3);
}
/* `.modal-footer` 的 display / justify-content / gap 走全局（main.css，计划 §8.2 C2）；
   这里只剩窄屏折行这一条本地规则（见下面 768 档）。 */

/* 断点 2026-10-21 由 480 并回 768（全站只留 768/1024 两档） */
@media (max-width: 768px) {
  /* 说明文字很长，窄屏上把它和「新增」按钮并排会挤成一条细柱：
     改成上下两段，按钮靠右，说明整行可读 */
  .src-head {
    flex-direction: column;
    align-items: stretch;
    gap: var(--space-2);
  }
  .src-head .n-button {
    align-self: flex-end;
  }
  .src-item {
    flex-direction: column;
    align-items: stretch;
  }
  .src-acts {
    justify-content: flex-end;
    flex-wrap: wrap;
  }
  .modal-footer {
    flex-wrap: wrap;
  }
}
</style>
