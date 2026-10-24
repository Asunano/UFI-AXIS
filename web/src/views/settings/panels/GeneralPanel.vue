<!--
  连接与端口（原「通用设置」）：**这一栏只放 core 自身的连接参数** ——
  设备后台 IP / 端口 / 密码、服务监听端口，外加一个「恢复默认配置」入口。
  一句话定位：这里回答的是「web 怎么连上 core 与设备后台」。

  2026-10-14 搬迁：日志与排障开关已移到「日志」面板（LogsPanel.vue），
  卡片里那段占位注释写清了搬走了什么、为什么。本栏此后不再是「什么设置都往里塞」的筐。

  2026-10-14 收口：原先保留的另外三块（本浏览器系统通知 / 短信验证码 / 更新配置）
  也已各自回到功能分栏 —— NotifyPanel.vue / 短信页的「验证码设置」弹窗 /
  UpdatePanel.vue 的「更新源」卡。那三块当时没动是因为目标分栏还在别人手里改，
  留着说明比删出空档稳；现在它们都落地了，所以在这里一次性删掉字段、读映射与差量键。
  **本栏现在只持有 goform_ip / goform_port / goform_password / port 四个键**，
  不再持有任何别的域的键 —— 否则差量保存会把另一栏刚写的值用旧快照覆盖回去。
-->
<template>
  <div class="settings-panel">
    <!-- Card: 连接与端口 -->
    <GridCard title="连接与端口">
      <template #extra>
        <n-button size="tiny" type="error" quaternary @click="resetConfig">恢复默认</n-button>
      </template>
      <div class="config-section">
        <div class="section-subtitle">设备后台连接</div>
        <div class="config-grid">
          <div class="config-item">
            <span class="config-label">IP</span>
            <n-input v-model:value="generalForm.goformIp" placeholder="192.168.0.1" size="small" />
          </div>
          <div class="config-item">
            <span class="config-label">设备后台端口</span>
            <n-input-number
              v-model:value="generalForm.goformPort"
              :min="ConfigLimits.goformPort[0]"
              :max="ConfigLimits.goformPort[1]"
              size="small"
            />
          </div>
          <div class="config-item">
            <span class="config-label">密码</span>
            <n-input
              v-model:value="generalForm.goformPassword"
              type="password"
              show-password-on="click"
              placeholder="留空则不修改"
              size="small"
            />
          </div>
          <div class="config-item">
            <span class="config-label">服务监听端口</span>
            <n-input-number
              v-model:value="generalForm.port"
              :min="ConfigLimits.port[0]"
              :max="ConfigLimits.port[1]"
              size="small"
            />
          </div>
        </div>
      </div>
      <!--
        2026-10-14 搬迁：日志与排障开关已移到「日志」面板（LogsPanel.vue）——
        log_enabled / core_log_enabled / app_log_enabled / debug_mode 四个日志开关，
        goform_dump_enabled / goform_command_enabled / field_normalization_enabled 三个排障开关
        （外加那个暂时隐藏的 ttyd 开关，它与排障开关共用同一套即时保存机制）。
        理由：它们本来就是日志域的东西，此前被拆在「通用」与「日志」两栏（要开详细日志得先想到
        来「通用」找），而四个日志开关互相有依赖、需要一个「改完一起提交」的时机 ——
        跟「日志」栏的查看/清空放在一起才是一件事。**要找它们去 LogsPanel.vue，别在这里加回来。**

        这一段里原先还夹着两条别的域的记录，一并留在原处免得后来的人找不到：
        · 「开机自启已移到『服务控制』卡片（立即生效 + 有状态回读）。此处原来有第二个开关
          写同一个后端字段 auto_start_on_boot，两者互不刷新。」
        · 「2026-10-07：预热开关与手动预热已迁至视频页『封面设置』（视频域开关放视频页）。」
      -->
      <!--
        2026-10-14 搬迁：本栏原有三块属于别的域的内容，已各自回到它们的功能分栏。
        **要找它们去下面这些地方，别在这里加回来：**
        · 「本浏览器系统通知」→ NotifyPanel.vue（通知面板顶部独立一张卡）。
          它是**浏览器本地偏好**（localStorage + Notification 权限），不写设备配置，
          放在"通知渠道"那一栏才对得上；
        · 「短信验证码」（启用提取 / 清理周期）→ 短信页 `#/sms` 的「验证码设置」弹窗
          （`views/sms/components/modals/SmsCodeSettingsModal.vue`）。它只影响短信页的
          验证码提取，住在"通用"里没有任何人找得到；
        · 「更新配置」（下载方式 / 设备出口地区）→ UpdatePanel.vue 顶部的「更新源」卡。
          它决定"包从哪来"，与"怎么更新"必须在一起。

        删掉它们之后本栏才真的只剩「连接 + 恢复默认」—— 卡片标题是「连接与端口」，
        内容与标题对得上。（此前 W1 搬迁时保留了这三块并留了说明，因为那时 notify / sms /
        update 三个目标分栏还在别的 agent 手里改；现在它们都落地了，所以在这里收口。）
      -->
      <!-- 未读到配置时的告警：本栏的四个连接输入与「保存」按钮都受 generalLoaded 控制 -->
      <div v-if="!generalLoaded" class="section-subtitle">配置未从设备读取，本栏开关已禁用（避免把默认值写回设备）</div>
      <div class="card-actions">
        <n-button type="primary" size="small" :loading="savingGeneral" :disabled="!generalLoaded" @click="saveGeneral"
          >保存</n-button
        >
      </div>
    </GridCard>
    <!-- 恢复默认配置：需配对密码，core 侧 /api/config/reset 会校验。
         尺寸走 main.css 的 `.modal-fit`（2026-10-14）：只传设计宽，视口约束与内容滚动由那个类统一管，
         不再各写一份 `width + max-width` —— 抄漏一处就会在窄屏贴死屏幕。 -->
    <n-modal
      v-model:show="resetModalOpen"
      preset="card"
      title="恢复默认配置"
      class="modal-fit"
      style="--modal-w: 460px"
      :mask-closable="!resetting"
    >
      <n-space vertical :size="12">
        <n-alert type="warning" :bordered="false">
          将把端口、设备后台连接、日志、QoS、更新源等配置全部恢复默认，<strong>需重启服务生效</strong>。
          配对信息与配对密码不受影响。
        </n-alert>
        <n-input
          v-model:value="resetPassword"
          type="password"
          show-password-on="click"
          placeholder="请输入配对密码"
          :disabled="resetting"
          @keyup.enter="submitReset"
        />
      </n-space>
      <template #footer>
        <n-space justify="end">
          <n-button size="small" :disabled="resetting" @click="resetModalOpen = false">取消</n-button>
          <n-button size="small" type="error" :loading="resetting" @click="submitReset">恢复默认</n-button>
        </n-space>
      </template>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, onUnmounted } from 'vue';
import { useMessage } from 'naive-ui';
import { getApiClient } from '@/composables/useApi';
import GridCard from '@/components/GridCard.vue';
import { ConfigLimits } from '@/api/contract';
import { buildChangedPayload, commitConfigSave, findUnbaselinedKeys } from '@/views/settings/settingsShared';

const message = useMessage();
const api = getApiClient();

// ── 连接与端口 ──

const savingGeneral = ref(false);
/** 是否已经从 core 读到过配置。false 时表单值只是硬编码初值，禁止编辑与保存。 */
const generalLoaded = ref(false);
const generalOriginal = reactive<Record<string, any>>({});

const generalForm = reactive({
  goformIp: '',
  goformPort: 8080,
  goformPassword: '',
  port: 8080,
  // autoStart 已移除：开机自启由「服务控制」卡片经 /api/service/autostart 单独管理，
  // 留在这里会形成第二份写同一后端字段的表单值。
  // 这些初值只在「还没读到设备配置」时短暂出现，且此时保存是禁用的（见 generalLoaded）。
  //
  // 2026-10-14：日志四开关（logEnabled / coreLogEnabled / appLogEnabled / debugMode）与排障三开关
  // （goformDumpEnabled / ttydEnabled / goformCommandEnabled / fieldNormalizationEnabled）
  // 连同它们的即时保存机制一起移到了 LogsPanel.vue —— 那些键现在由「日志」面板读写，这里不再持有。
  //
  // 2026-10-14：封面预热（thumbPrewarmEnabled / thumbPrewarmWifiOnly）的字段与读映射一并删除。
  // 它们的开关 2026-10-07 就迁到了视频页「封面设置」，视频页自己读写这两个键
  // （MediaVideoView.saveThumbKey），留在这里的两个表单字段与读映射早已没有消费者，
  // 而它们引用的 fieldNormalizationEnabled 初值注释这次也搬走了 —— 再留着就是指向空气的注释。
  //
  // 2026-10-14 收口：短信验证码（smsCodeEnabled / smsCodeCleanupHours）与更新源
  // （updateSourceMode）也删了 —— 它们分别搬进短信页的「验证码设置」弹窗与
  // UpdatePanel 的「更新源」卡。**本栏不再持有任何别的域的键**，
  // 这正是「同一件事不能有两个入口」那条纪律的落地：留着字段与读映射，
  // 差量保存就会把另一栏刚写的值用旧快照覆盖回去。
});

async function loadGeneralConfig() {
  try {
    const { data } = await api.get('/api/config');
    const mapping: Record<string, string> = {
      goform_ip: 'goformIp',
      goform_port: 'goformPort',
      goform_password: 'goformPassword',
      port: 'port',
      // 2026-10-14 搬迁：日志 / 排障 / ttyd / 封面预热的键不在这里读了 —— 前三个（组）
      // 由 LogsPanel.vue 自己读（开关要显示设备真实状态，就得由持有开关的面板持有基线），
      // 封面预热由视频页读（见 generalForm 里的说明）。
      // 2026-10-14 收口：短信验证码与更新源的键同样不在这里读了，各自由
      // SmsCodeSettingsModal.vue 与 UpdatePanel.vue 持有基线。
    };
    for (const [apiKey, formKey] of Object.entries(mapping)) {
      if (data[apiKey] !== undefined) {
        (generalForm as any)[formKey] = data[apiKey];
        generalOriginal[apiKey] = data[apiKey];
      }
    }
    // core 对 goform_password 做脱敏返回（如 12****78），且会拒绝含 *** 的回写。
    // 直接把脱敏串填进密码框会让用户误以为那是真实密码，改为留空 = 不修改。
    generalForm.goformPassword = '';
    generalOriginal['goform_password'] = '';
    generalLoaded.value = true;
  } catch {
    // 2026-09-04：这里原来是 silent catch，表单于是停在硬编码初值上（当时这一栏还带着日志开关，
    // 它们的初值全是 true）却看起来像设备的真实状态 —— 用户在手机端刚关掉的开关，在 web 上
    // 显示为开启，一点保存就把它又打开了。现在明确标记「未加载」：开关与保存按钮全部禁用。
    // （日志开关本身已于 2026-10-14 移到「日志」面板，同一条纪律在 LogsPanel.vue 里继续生效。）
    generalLoaded.value = false;
    message.warning('设备配置读取失败，本栏配置暂不可编辑');
  }
}

/**
 * 表单字段 ↔ core 配置键。提到函数外是因为「有没有未保存改动」的判断也要用它
 * （见 [hasUnsavedChanges]），放在 saveGeneral 里就得抄第二份。
 *
 * 2026-10-14：四个日志开关与三个排障开关的键随开关一起搬到了 LogsPanel.vue，
 * 这张表里不再有它们。那边同样把排障三键排除在差量表外 —— 理由（避免与即时保存双写、
 * 避免 hasUnsavedChanges 把刚生效的开关算成未保存改动）写在 LogsPanel 的同名常量上。
 */
const GENERAL_FORM_KEYS: Record<string, string> = {
  goformIp: 'goform_ip',
  goformPort: 'goform_port',
  goformPassword: 'goform_password',
  port: 'port',
};

function hasUnsavedChanges(): boolean {
  return Object.keys(buildChangedPayload(GENERAL_FORM_KEYS, generalForm, generalOriginal)).length > 0;
}

// ── 2026-10-14 搬迁：更新源（下载方式 + 设备出口地区）整体移到了「更新」面板 ──
//
// 这里原来放着 UPDATE_MODE_OPTIONS / updateMode / onUpdateModeChange / geoCountry /
// detectingGeo / geoText / detectGeo（含 2026-09-22 那条「唯一真源是 core 的
// update_source_mode，web 只读写它，update_url / update_mirror_base 是实现细节」的决策记录）。
// 搬迁理由：它决定"包从哪来"，与"怎么更新"必须在一起 —— 此前「通用」里有下载方式、
// 「服务更新」里有 Web 前端与后端 core，同一个域被拆成两处。
// **要改更新源去 UpdatePanel.vue 的「更新源」卡，别在这里加回来。**

// ── 2026-10-14 搬迁：排障开关的即时保存机制整体移到了「日志」面板 ──
//
// 这里原来放着 saveProbeSwitch / probeDescription / probeSwitchDisabled / PROBE_FORM_KEY /
// PROBE_NEEDS_RESTART / probeSaving 与四条说明文案（dump / 裸命令 / 字段归一化 / 隐藏的 ttyd）。
// 三个排障开关本身搬去了 LogsPanel.vue，机制跟着开关走 —— 留一份在这里就等于有了两个
// 「谁在写同一个 /api/config 键」的实现，那正是本次搬迁要消除的东西。要改排障开关去 LogsPanel.vue。

async function saveGeneral() {
  if (!generalLoaded.value) {
    message.warning('配置尚未从设备读取，保存已阻止');
    return;
  }
  // 无条件提示（不看用户改没改过）：缺基线的控件显示的是前端默认值而不是设备真实状态，
  // 哪怕没动过，「界面上有这个开关、设备却不认它」本身就得说出来。
  const unbaselined = findUnbaselinedKeys(GENERAL_FORM_KEYS, generalOriginal);
  if (unbaselined.length > 0) {
    message.warning(`以下配置项当前 core 版本不支持，已忽略：${unbaselined.join('、')}`);
  }
  const payload = buildChangedPayload(GENERAL_FORM_KEYS, generalForm, generalOriginal);
  if (Object.keys(payload).length === 0) {
    message.info('没有更改');
    return;
  }
  savingGeneral.value = true;
  try {
    const { data } = await api.put('/api/config', payload);
    commitConfigSave(payload, data, generalOriginal, '连接与端口', message, loadGeneralConfig);
  } catch (e: any) {
    message.error(e?.response?.data?.error || '保存失败');
  } finally {
    savingGeneral.value = false;
  }
}

/**
 * 恢复默认配置。
 *
 * core 侧 `/api/config/reset` 从 2026-09-10 起要求配对密码：此前它只要求「已配对身份」，
 * 而它会清掉配置里的一切 —— 一台借出去用过、配对记录还没删的旧手机就能远程触发。
 * 所以这里不能再用 dialog.warning 一键确认，必须收一次密码。
 */
const resetModalOpen = ref(false);
const resetPassword = ref('');
const resetting = ref(false);

function resetConfig() {
  resetPassword.value = '';
  resetModalOpen.value = true;
}

async function submitReset() {
  if (!resetPassword.value) {
    message.warning('请输入配对密码');
    return;
  }
  resetting.value = true;
  try {
    const { data } = await api.post('/api/config/reset', { password: resetPassword.value });
    message.warning(data.message || '配置已恢复默认，需重启服务生效');
    resetModalOpen.value = false;
    resetPassword.value = '';
    // 只刷新本分栏：QoS / 版本信息等其余分栏此刻并未挂载，
    // 下次打开它们时会各自 onMounted 重新拉取，无需在这里代劳。
    loadGeneralConfig();
  } catch (e: any) {
    message.error(e?.response?.data?.error || '恢复失败');
  } finally {
    resetting.value = false;
  }
}

/**
 * 页面重新可见时重新拉一次配置。
 *
 * 为什么需要：这些字段的唯一真源在 core，而**手机端 App 随时可以改它们**。
 * 原来本面板只在 onMounted 拉一次，于是「用户在手机上改了配置 → 切回 web 这个标签页」
 * 看到的仍是几十分钟前的快照，表现为「手机端已改、web 端还显示旧值」。
 * （日志开关 2026-10-14 搬去「日志」面板时把同一段逻辑带了过去，见 LogsPanel.onVisible ——
 * 那边不能没有它，否则同一个毛病会以「日志面板显示旧快照」的形式复发。）
 *
 * 有未保存改动时跳过：否则用户填了一半的表单会被后台状态冲掉。
 */
function onVisible() {
  if (document.visibilityState !== 'visible') return;
  if (hasUnsavedChanges()) return;
  loadGeneralConfig();
}

onMounted(() => {
  loadGeneralConfig();
  document.addEventListener('visibilitychange', onVisible);
});

onUnmounted(() => {
  document.removeEventListener('visibilitychange', onVisible);
});
</script>

<style scoped>
/* .settings-panel 栅格与断点、.config-grid/.config-item/.config-label/.switch-item 版式、
   .section-subtitle、.card-actions 均已统一到 src/styles/main.css（全局各一份） */

/* ── Config layout ── */
.config-section {
  margin-bottom: 4px;
}
.config-section:last-child {
  margin-bottom: 0;
}
</style>
