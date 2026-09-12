/**
 * 可选组件（frpc / cloudflared）的安装/卸载/上传与进度轮询。
 *
 * 为什么是 composable 而不是塞进弹窗组件：**进度轮询必须比弹窗活得久**。
 * `installComponent` 明确告诉用户「可离开本页，进度会继续」，而 naive 的 `n-modal`
 * 默认 `display-directive="if"` —— 关闭时子组件会被销毁。若把这套状态放进
 * `ComponentsModal.vue`，关掉弹窗就等于掐掉 1s 轮询和终态提示（安装成功/失败的那条 message）。
 * 所以状态与轮询挂在**页面**的 setup 上，弹窗只做展示与动作透传。
 *
 * 也不属于隧道页：`/api/components` 是通用端点，以后设置页要显示「有组件可更新」
 * 直接调这个 composable 就行，不必再抄一份。
 *
 * 调用方需要做的唯一一件事：传 `onChanged` —— 安装/卸载/上传完成后组件的 installed
 * 与版本号变了，但**隧道自己的状态**（`/api/tunnel/status` 里的 frpInstalled 等）
 * 得由调用方自己重拉，这个 composable 不知道也不该知道那个端点。
 */
import { computed, reactive } from 'vue';
import { useDialog, useMessage } from 'naive-ui';
import { useCancellableApi } from '@/composables/useCancellableApi';
import { useInterval } from '@/composables/useRealtime';
import { formatBytes } from '@/composables/utils';
import { errText } from '@/views/tunnel/tunnelShared';

export interface ComponentItem {
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

export interface UseComponentInstallerOptions {
  /** 安装/卸载/上传落地后调用，用于让调用方重拉自己那边受影响的状态 */
  onChanged?: () => void | Promise<void>;
}

export function useComponentInstaller(options: UseComponentInstallerOptions = {}) {
  const message = useMessage();
  const dialog = useDialog();
  const api = useCancellableApi();

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

  function applyComponentStatus(data: any) {
    comp.progress = {
      id: data?.id || '',
      state: data?.state || 'idle',
      percent: data?.percent ?? 0,
      message: data?.message || '',
    };
    comp.manifestError = data?.manifest_error || '';
  }

  async function notifyChanged() {
    await options.onChanged?.();
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
          await notifyChanged();
        }
      },
    });
  }

  /**
   * 本地上传走 raw body（application/octet-stream），不用 multipart：
   * cloudflared 裸二进制约 36MB，multipart 那套要先在内存里攒一份，低端设备会 OOM。
   * core 按文件头自动识别裸 ELF 与 tar.gz，安装后跑 --version 回填版本号。
   */
  async function uploadComponent(id: string, file: File) {
    if (!id || !file) return;
    try {
      await api.post(`/api/components/${encodeURIComponent(id)}/upload`, file, {
        headers: { 'Content-Type': 'application/octet-stream' },
      });
      message.success(`${id} 已安装`);
    } catch (e: any) {
      message.error(errText(e, '上传安装失败'));
    } finally {
      await loadComponents();
      await notifyChanged();
    }
  }

  /**
   * 组件安装进度单独用 1s 快轮询，且只在有任务时打网络。
   * 任务从「进行中」落到终态时补拉一次列表 —— 安装完成后 installed/版本号才会变。
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
      await notifyChanged();
    }
    lastProgressActive = progressActive.value;
  }, 1000);

  return {
    comp,
    progressActive,
    loadComponents,
    installComponent,
    confirmUninstall,
    uploadComponent,
  };
}
