<template>
  <div class="tab-grid">
    <!-- 设备后台密码 -->
    <GridCard title="设备后台密码">
      <div class="form-grid">
        <div class="form-item">
          <label>旧密码</label>
          <n-input
            v-model:value="passwordForm.old_password"
            type="password"
            show-password-on="mousedown"
            placeholder="输入当前密码"
            size="small"
          />
        </div>
        <div class="form-item">
          <label>新密码</label>
          <n-input
            v-model:value="passwordForm.new_password"
            type="password"
            show-password-on="mousedown"
            placeholder="输入新密码"
            size="small"
          />
        </div>
      </div>
      <div class="form-actions">
        <n-button type="primary" size="small" :loading="passwordLoading" @click="changePassword">修改密码</n-button>
      </div>
    </GridCard>

    <!-- 关机 -->
    <GridCard title="关机">
      <p class="danger-desc">此操作将立即关闭设备。请确保所有重要任务已完成。</p>
      <n-button type="error" :loading="shutdownLoading" @click="handleShutdown">关机</n-button>
    </GridCard>

    <!-- Goform 原始数据 -->
    <GridCard title="设备后台原始数据">
      <template #extra>
        <n-button size="tiny" quaternary :loading="goformLoading" @click="loadGoform">
          {{ goformData ? '刷新' : '加载' }}
        </n-button>
      </template>
      <p class="danger-desc">
        这是设备后台的完整快照，可能包含 IMSI / ICCID / MSISDN / WiFi 密码等敏感信息，截图或分享前请自行脱敏。
      </p>
      <div v-if="goformData">
        <n-button size="tiny" text style="margin-bottom: 8px" @click="goformExpanded = !goformExpanded">
          {{ goformExpanded ? '收起' : '展开' }}
        </n-button>
        <pre v-show="goformExpanded" class="goform-pre">{{ JSON.stringify(goformData, null, 2) }}</pre>
      </div>
      <n-empty v-else-if="!goformLoading" description="点击加载按钮获取设备后台数据" />
    </GridCard>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue';
import { useMessage, useDialog } from 'naive-ui';
import { useCancellableApi } from '@/composables/useCancellableApi';
import GridCard from '@/components/GridCard.vue';

const message = useMessage();
const dialog = useDialog();
const api = useCancellableApi();

// 设备后台密码
const passwordLoading = ref(false);
const passwordForm = reactive({ old_password: '', new_password: '' });

async function changePassword() {
  if (!passwordForm.old_password || !passwordForm.new_password) {
    message.warning('请填写旧密码和新密码');
    return;
  }
  passwordLoading.value = true;
  try {
    await api.post('/api/device/password', {
      old_password: passwordForm.old_password,
      new_password: passwordForm.new_password,
    });
    message.success('密码修改成功');
    passwordForm.old_password = '';
    passwordForm.new_password = '';
  } catch {
    message.error('密码修改失败');
  } finally {
    passwordLoading.value = false;
  }
}

// 关机
const shutdownLoading = ref(false);

function handleShutdown() {
  dialog.error({
    title: '确认关机',
    content: '设备将立即关闭，所有连接将断开。请确认已保存所有工作。',
    positiveText: '确认关机',
    negativeText: '取消',
    onPositiveClick: async () => {
      shutdownLoading.value = true;
      try {
        await api.post('/api/device/shutdown');
        message.success('关机指令已发送');
      } catch {
        message.error('关机失败');
      } finally {
        shutdownLoading.value = false;
      }
    },
  });
}

// Goform 原始数据
const goformLoading = ref(false);
const goformData = ref<Record<string, any> | null>(null);
const goformExpanded = ref(false);

async function loadGoform() {
  goformLoading.value = true;
  try {
    const { data } = await api.get('/api/device/goform');
    goformData.value = data;
    message.success('设备后台数据已加载');
  } catch (e: any) {
    // 403 = core 侧 goform_dump_enabled 默认关闭（不是鉴权失败，不会被拦截器踢去登录页）
    if (e?.response?.status === 403) {
      // 文案指向开关所在的分栏名，而不是配置键名：键名是内部契约，用户在界面上找不到它。
      message.warning('设备原始字段 dump 已关闭，可在「设置 › 通用」里打开');
    } else {
      message.error('加载设备后台数据失败');
    }
  } finally {
    goformLoading.value = false;
  }
}
</script>

<style scoped>
.tab-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 12px;
  margin-bottom: 12px;
}
.form-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.form-item label {
  font-size: 12px;
  color: var(--text-secondary);
  font-weight: 500;
}
.form-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 4px;
}
.danger-desc,
.action-desc {
  font-size: 13px;
  color: var(--text-secondary);
  margin: 0 0 12px;
  line-height: 1.6;
}
.goform-pre {
  font-size: 11px;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--text-primary);
  background: var(--page-bg);
  padding: 12px;
  border-radius: 8px;
  max-height: 500px;
  overflow-y: auto;
  line-height: 1.5;
}
@media (max-width: 768px) {
  .tab-grid {
    grid-template-columns: 1fr;
  }
  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
