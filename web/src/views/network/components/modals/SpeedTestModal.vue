<template>
  <n-modal
    v-model:show="show"
    preset="card"
    title="网速测试"
    style="width: 420px; max-width: calc(100vw - 32px)"
  >
    <div class="speedtest-area">
      <div class="speedtest-label">下行</div>
      <div v-if="speedTestResult !== null" class="speedtest-result">
        <span class="speedtest-value">{{ speedTestResult }}</span>
        <span class="speedtest-unit">MB/s</span>
      </div>
      <div v-else-if="speedTesting" class="speedtest-result">
        <span class="speedtest-value">{{ speedProgress }}</span>
        <span class="speedtest-unit">MB/s</span>
      </div>
      <div v-else class="speedtest-placeholder">
        <span style="color: var(--text-muted)">点击下方按钮测试下载速度</span>
      </div>
      <n-progress
        v-if="speedTesting"
        :percentage="speedPercent"
        :show-indicator="false"
        :height="4"
        style="margin: 8px 0"
      />

      <n-divider style="margin: 12px 0" />

      <div class="speedtest-label">上行</div>
      <div v-if="uploadResult !== null" class="speedtest-result">
        <span class="speedtest-value">{{ uploadResult }}</span>
        <span class="speedtest-unit">MB/s</span>
      </div>
      <div v-else-if="uploadTesting" class="speedtest-result">
        <span class="speedtest-value">{{ uploadProgress }}</span>
        <span class="speedtest-unit">MB/s</span>
      </div>
      <div v-else class="speedtest-placeholder">
        <span style="color: var(--text-muted)">上行会向设备发送等量随机数据，core 收完即丢弃</span>
      </div>
      <n-progress
        v-if="uploadTesting"
        :percentage="uploadPercent"
        :show-indicator="false"
        :height="4"
        style="margin: 8px 0"
      />

      <div class="speedtest-actions">
        <n-select
          v-model:value="speedTestSize"
          :options="speedSizeOptions"
          size="small"
          style="width: 120px"
          :disabled="speedTesting || uploadTesting"
        />
        <n-button
          size="small"
          type="primary"
          :loading="speedTesting"
          :disabled="uploadTesting"
          @click="runSpeedTest"
          >{{ speedTesting ? '测试中...' : '下行测速' }}</n-button
        >
        <n-button
          size="small"
          :loading="uploadTesting"
          :disabled="speedTesting"
          @click="runUploadSpeedTest"
          >{{ uploadTesting ? '测试中...' : '上行测速' }}</n-button
        >
      </div>
    </div>
  </n-modal>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue';
import { useMessage } from 'naive-ui';
import { useRouter } from 'vue-router';
import { useAppStore } from '@/stores/app';
import { loadDeviceIdentity } from '@/composables/deviceIdentityLazy';

const props = defineProps<{ show: boolean }>();
const emit = defineEmits<{ 'update:show': [boolean] }>();

const show = computed({
  get: () => props.show,
  set: (v) => emit('update:show', v),
});

const message = useMessage();
const router = useRouter();
const appStore = useAppStore();

const speedTesting = ref(false);
const speedTestResult = ref<number | null>(null);
const speedProgress = ref(0);
const speedPercent = ref(0);
const speedTestSize = ref(10);
const speedSizeOptions = [
  { label: '10 MB', value: 10 },
  { label: '25 MB', value: 25 },
  { label: '50 MB', value: 50 },
  { label: '100 MB', value: 100 },
];

/**
 * 裸 fetch / XHR 的鉴权头：Bearer + 设备签名。
 *
 * 测速两处刻意绕过 axios（下行要流式读、上行要 XHR 进度事件），因此拿不到 useApi 里的
 * 签名拦截器；而 core 的 AuthMiddleware 对**所有** /api 请求强制验签，少这三个头就是 444。
 * 签名的 URI 必须是服务端看到的 path+query，所以这里传相对路径而不是拼好的绝对 URL。
 */
async function speedTestHeaders(method: string, uri: string): Promise<Record<string, string>> {
  const signed = await (await loadDeviceIdentity()).signRequest(method, uri);
  return appStore.token ? { Authorization: `Bearer ${appStore.token}`, ...signed } : { ...signed };
}

async function runSpeedTest() {
  speedTesting.value = true;
  speedTestResult.value = null;
  speedProgress.value = 0;
  speedPercent.value = 0;
  try {
    const base = appStore.baseUrl || '';
    const uri = `/api/speedtest?ckSize=${speedTestSize.value}`;
    const url = `${base}${uri}`;
    const resp = await fetch(url, { headers: await speedTestHeaders('GET', uri) });
    if (!resp.ok) {
      // 手写 fetch 不经过 axios 拦截器，鉴权失败需要自己兜住，否则只会显示 "HTTP 444"
      if (resp.status === 401 || resp.status === 444) {
        appStore.clearAuth();
        router.push('/login');
        return;
      }
      throw new Error(`HTTP ${resp.status}`);
    }
    const totalBytes = speedTestSize.value * 1024 * 1024;
    const reader = resp.body!.getReader();
    let received = 0;
    const startTime = performance.now();
    let lastUpdate = startTime;
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      received += value.byteLength;
      const now = performance.now();
      if (now - lastUpdate > 200) {
        const elapsed = (now - startTime) / 1000;
        const speed = received / elapsed / 1_048_576;
        speedProgress.value = Math.round(speed * 10) / 10;
        speedPercent.value = Math.min(99, Math.round((received / totalBytes) * 100));
        lastUpdate = now;
      }
    }
    const totalTime = (performance.now() - startTime) / 1000;
    speedTestResult.value = Math.round((received / totalTime / 1_048_576) * 10) / 10;
    speedPercent.value = 100;
  } catch (e: any) {
    message.error(`测速失败: ${e.message || '未知错误'}`);
  } finally {
    speedTesting.value = false;
  }
}

// ── 上行测速 ──
// core POST /api/speedtest/upload 把请求体读完即丢弃，返回 {success, bytes}；
// 12 个并发许可用尽时返回 429 纯文本，60s 后强制结束。
const uploadTesting = ref(false);
const uploadResult = ref<number | null>(null);
const uploadProgress = ref(0);
const uploadPercent = ref(0);

// crypto.getRandomValues 单次上限 65536 字节，先生成一个 64KB 随机块再重复拼装。
// 重复内容对速率没影响：octet-stream 不走压缩。
function buildRandomBlob(size: number): Blob {
  const CHUNK = 65536;
  const chunk = new Uint8Array(CHUNK);
  crypto.getRandomValues(chunk);
  const parts: Uint8Array[] = [];
  let remaining = size;
  while (remaining > 0) {
    parts.push(remaining >= CHUNK ? chunk : chunk.subarray(0, remaining));
    remaining -= CHUNK;
  }
  return new Blob(parts, { type: 'application/octet-stream' });
}

async function runUploadSpeedTest() {
  uploadTesting.value = true;
  uploadResult.value = null;
  uploadProgress.value = 0;
  uploadPercent.value = 0;
  const totalBytes = speedTestSize.value * 1024 * 1024;
  try {
    const uri = '/api/speedtest/upload';
    const headers = await speedTestHeaders('POST', uri);
    await new Promise<void>((resolve, reject) => {
      // 这里用 XHR 而不是 fetch/axios：只有 XHR 能拿到上传方向的进度事件
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${appStore.baseUrl || ''}${uri}`);
      Object.entries(headers).forEach(([k, v]) => xhr.setRequestHeader(k, v));
      xhr.setRequestHeader('Content-Type', 'application/octet-stream');
      const startTime = performance.now();
      let lastUpdate = startTime;
      xhr.upload.onprogress = (ev) => {
        const now = performance.now();
        if (now - lastUpdate < 200) return;
        lastUpdate = now;
        const elapsed = (now - startTime) / 1000;
        if (elapsed <= 0) return;
        uploadProgress.value = Math.round((ev.loaded / elapsed / 1_048_576) * 10) / 10;
        uploadPercent.value = Math.min(99, Math.round((ev.loaded / totalBytes) * 100));
      };
      xhr.onload = () => {
        // 手写 XHR 不经过 axios 拦截器，鉴权失败需要自己兜住
        if (xhr.status === 401 || xhr.status === 444) {
          appStore.clearAuth();
          router.push('/login');
          resolve();
          return;
        }
        if (xhr.status === 429) {
          reject(new Error('请求频率过多，请稍后再试'));
          return;
        }
        if (xhr.status < 200 || xhr.status >= 300) {
          reject(new Error(`HTTP ${xhr.status}`));
          return;
        }
        // 以 core 回报的实际接收字节数算速率，客户端中途被截断时更准
        let bytes = totalBytes;
        try {
          const parsed = JSON.parse(xhr.responseText);
          if (typeof parsed?.bytes === 'number' && parsed.bytes > 0) bytes = parsed.bytes;
        } catch {
          /* 响应不是 JSON（如 429 纯文本）时保留估算值 */
        }
        const totalTime = (performance.now() - startTime) / 1000;
        uploadResult.value = Math.round((bytes / totalTime / 1_048_576) * 10) / 10;
        uploadPercent.value = 100;
        resolve();
      };
      xhr.onerror = () => reject(new Error('网络错误'));
      xhr.ontimeout = () => reject(new Error('超时'));
      xhr.send(buildRandomBlob(totalBytes));
    });
  } catch (e: any) {
    message.error(`上行测速失败: ${e.message || '未知错误'}`);
  } finally {
    uploadTesting.value = false;
  }
}
</script>

<style scoped>
.speedtest-area {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 12px 0;
}
.speedtest-label {
  font-size: 12px;
  color: var(--text-muted);
  margin-bottom: 2px;
}
.speedtest-result {
  display: flex;
  align-items: baseline;
  gap: 6px;
}
.speedtest-value {
  font-size: 36px;
  font-weight: 700;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}
.speedtest-unit {
  font-size: 14px;
  color: var(--text-secondary);
}
.speedtest-placeholder {
  padding: 16px 0;
}
.speedtest-actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
  flex-wrap: wrap;
  justify-content: center;
}
</style>
