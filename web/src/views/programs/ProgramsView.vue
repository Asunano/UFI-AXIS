<!--
  第三方程序（应用商店）页面：状态优先的网格。

  ## 布局（2026-10-31，A+D 定稿）
  - 已安装 / 未安装两段；已安装内部按 需处理 → 启动中 → 运行中 → 已停止 排。
  - 状态只靠 LED + 文字，外加失败卡片淡红底；不靠颜色单通道。
  - 一卡一主操作：打开 / 启动 / 重试 / 安装；停止在桌面常驻、手机收进 ⋯。
  - 详情走右侧抽屉（手机全屏），不再把日志、访问方式、卸载堆进卡片。
  - 口令、需要处理、安装进度放在页级横幅/任务条，避免把某一行撑高。

  ## 颜色
  全部走 `styles/main.css` 的语义令牌与 `--cat-*`；本组件不写死颜色。
  原因不只是暗色适配：`check:ui` 会按"scoped CSS 里的硬编码颜色"计数。

  ## 与 APK 的关系
  程序二进制不包含在 APK 安装包中。APK 仅包含 assets/programs/catalog.json 元数据；
  用户执行安装时，再从发布源下载（与 frpc/cloudflared 的获取方式一致）。
-->
<template>
  <div class="programs-view">
    <!-- 页头：说明 + 全局状态 + 搜索 / 刷新 -->
    <header class="page-head">
      <div class="page-title">
        <div class="title-row">
          <h1>程序</h1>
          <span class="count-pill">{{ countsPending ? '—' : programs.length }}</span>
        </div>
      </div>
      <div class="tools">
        <div class="tools-row">
          <n-input
            v-model:value="searchQuery"
            size="small"
            clearable
            placeholder="搜索名称、端口或 ID"
            aria-label="搜索程序"
            class="search-input"
          >
            <template #prefix>
              <n-icon :size="15"><SearchOutline /></n-icon>
            </template>
          </n-input>
          <n-button quaternary circle :loading="loading" aria-label="刷新列表" @click="manualRefresh">
            <template #icon
              ><n-icon :size="18"><RefreshOutline /></n-icon
            ></template>
          </n-button>
          <!-- 手机端排序入口：筛选卡里的 sort-btn 在 ≤768 隐藏，但排序能力不能跟着消失 -->
          <n-button
            size="small"
            quaternary
            class="mobile-sort-btn"
            :aria-label="`排序方式：${sortMode === 'status' ? '按状态' : '按名称'}，点击切换`"
            @click="toggleSort"
          >
            <template #icon
              ><n-icon :size="13"><SwapHorizontalOutline /></n-icon
            ></template>
            {{ sortMode === 'status' ? '状态' : '名称' }}
          </n-button>
        </div>
      </div>
    </header>

    <!-- 本机 SELinux 探测：只提示，不做安装门禁（避免误判把整个商店锁死） -->
    <div v-if="!nativeExecOk" class="banner banner-warn" role="status">
      <n-icon class="banner-icon" :size="15"><AlertCircleOutline /></n-icon>
      <div class="banner-copy">
        <strong>当前设备可能不支持运行第三方程序</strong>
        <span
          >SELinux 拒绝了 UFI-AXIS Core 的执行检测。程序仍可安装，但启动时可能返回 EACCES；请先确认设备安全策略。</span
        >
      </div>
    </div>

    <!-- 需要处理的程序：熔断 / 外部存储不可用 -->
    <div v-if="attentionPrograms.length" class="banner banner-attn" role="status">
      <n-icon class="banner-icon" :size="15"><AlertCircleOutline /></n-icon>
      <div class="banner-copy">
        <strong>{{ attentionPrograms.length }} 个程序需要处理</strong>
        <span>{{ attentionSummary }}</span>
      </div>
      <n-button size="small" @click="focusAttention">定位</n-button>
    </div>

    <!-- 管理员凭据：列表只给 has_secret；dufs 可再次查看，OpenList 消费后不可再读 -->
    <div v-if="secretProgram" class="banner banner-secret">
      <n-icon class="banner-icon" :size="15"><KeyOutline /></n-icon>
      <div class="banner-copy">
        <strong>{{ secretProgram.name }} 管理员凭据</strong>
        <span>{{ secretBannerHint }}</span>
      </div>
      <n-button size="small" :loading="secretLoading" @click="revealSecret(secretProgram)">
        <template #icon
          ><n-icon><KeyOutline /></n-icon
        ></template>
        查看凭据
      </n-button>
    </div>

    <!-- 全局下载/安装任务：同一时刻只有一个 -->
    <div v-if="activeTask" class="task">
      <div class="task-head">
        <span class="task-title">{{ taskTitle }}</span>
        <span class="task-pct mono">{{ percentOf(activeTask) }}%</span>
      </div>
      <n-progress type="line" :percentage="percentOf(activeTask)" :show-indicator="false" :height="4" processing />
      <div class="task-foot">
        <span>{{ fmtSize(activeTask.downloaded_bytes) }} / {{ fmtSize(activeTask.total_bytes) }}</span>
        <span>
          {{ activeTask.message || '从发布源下载' }}
          <template v-if="activeTask.candidate_index > 0"> · 第 {{ activeTask.candidate_index }} 个下载源</template>
        </span>
      </div>
    </div>

    <!--
      筛选条：与终端 / 短信页同一套 —— 白色卡面承载 Naive segment tabs。
      2026-11-11 用户指出原来的 pill 不是公共组件；`animated` 才有胶囊滑动过渡，
      纯 n-tabs 直接铺在页面底色上时浅灰轨道与背景几乎同色，切换看不出动效。
    -->
    <div class="filter-card">
      <!--
        2026-11-11：从 n-tabs 换成 n-radio-group。
        原因：segment tabs 渲染出来的四个标签是**没有 role/tabindex 的 div**（实测 AX 树里
        role=tab 为 0 个，Tab 键从排序直接跳到第一张卡），键盘和读屏用户根本无法切换筛选。
        单选组渲染真实 `<input type=radio>`，方向键与 Tab 都能操作，外观仍是分段按钮。
      -->
      <div ref="filterWrap" class="filter-wrap" role="radiogroup" aria-label="筛选程序">
        <n-radio-group
          v-model:value="filter"
          size="medium"
          class="filter-radio"
          :style="{ '--filter-count': filters.length }"
        >
          <span
            class="filter-pill"
            :style="{ transform: `translateX(${filterIndex * 100}%)` }"
            aria-hidden="true"
          ></span>
          <!-- name 必须逐项传：n-radio-button 只在自身 name 有值时才写进 input，
               组级 name 在这个版本没有下传（实测四个 input 的 name 都是空串），
               于是四个单选各自成组、变成四个 Tab 停靠点。 -->
          <n-radio-button
            v-for="f in filters"
            :key="f.key"
            :value="f.key"
            name="program-filter"
            :title="f.key === 'running' ? '运行中 + 启动中' : undefined"
          >
            {{ f.label }} {{ f.count }}
          </n-radio-button>
        </n-radio-group>
      </div>
      <n-button
        size="small"
        quaternary
        class="sort-btn"
        :aria-label="`排序方式：${sortMode === 'status' ? '按状态' : '按名称'}，点击切换`"
        @click="toggleSort"
      >
        <template #icon
          ><n-icon :size="13"><SwapHorizontalOutline /></n-icon
        ></template>
        {{ sortMode === 'status' ? '按状态' : '按名称' }}
      </n-button>
    </div>

    <!-- 首次读取：没有数据时给骨架，而不是让页头下面一片空白 -->
    <div
      v-if="loading && !programs.length"
      class="tile-grid skeleton-grid"
      aria-busy="true"
      aria-label="正在读取程序列表"
    >
      <div v-for="n in 4" :key="n" class="pg-skeleton">
        <div class="pg-skeleton-top">
          <n-skeleton circle :width="42" :height="42" />
          <div class="pg-skeleton-lines">
            <n-skeleton text width="72%" />
            <n-skeleton text width="38%" />
          </div>
        </div>
        <n-skeleton text :repeat="2" />
        <div class="pg-skeleton-metrics">
          <n-skeleton text width="44%" />
          <n-skeleton text width="44%" />
        </div>
      </div>
    </div>

    <!-- 首次读取失败：页内说明 + 重试，不依赖一闪而过的 toast -->
    <div v-else-if="loadFailed && !programs.length" class="empty load-error" role="alert">
      <n-icon :size="22"><AlertCircleOutline /></n-icon>
      <h3>读取程序列表失败</h3>
      <p>检查设备连接后重试；已有的安装和运行状态不会因此改变。</p>
      <n-button size="small" :loading="loading" @click="load()">重试</n-button>
    </div>

    <template v-else>
      <!-- 已安装 -->
      <section v-if="installedPrograms.length" class="section" aria-labelledby="programs-installed">
        <div class="section-head">
          <h2 id="programs-installed">
            已安装 <span class="count">{{ installedPrograms.length }}</span>
          </h2>
        </div>
        <div class="tile-grid">
          <article
            v-for="p in installedPrograms"
            :key="p.id"
            class="pg-tile"
            :class="`tone-${tileState(p)}`"
            :data-id="p.id"
          >
            <button class="pg-open" type="button" :aria-label="`查看 ${p.name} 的详情`" @click="openDetail(p)">
              <span class="pg-top">
                <span class="glyph">{{ initials(p.name) }}<i class="led" :class="dotClass(p)"></i></span>
                <span class="pg-ident">
                  <span class="name">{{ p.name }}</span>
                  <span class="ver">
                    {{ versionLabel(p) }}
                    <span v-if="updateAvailable(p)" class="chip chip-update">可更新</span>
                    <span v-if="p.requires_root" class="chip" title="需要 root 权限">需 root</span>
                  </span>
                </span>
              </span>
              <span class="pg-status">
                <span class="status-text">{{ statusLine(p) }}</span>
                <span v-if="statusNote(p)" class="pg-note" :class="{ error: noteIsError(p) }" :title="statusNote(p)">{{
                  statusNote(p)
                }}</span>
              </span>
              <span class="pg-summary">{{ p.summary || '暂无简介' }}</span>
              <span class="pg-metrics">
                <span v-for="m in tileMetrics(p)" :key="m.k" class="pg-metric">
                  <span class="k">{{ m.k }}</span>
                  <span class="v" :class="{ mono: m.mono }">{{ m.v }}</span>
                </span>
              </span>
            </button>
            <div class="pg-actions">
              <template v-if="isStorageIssue(p)">
                <n-button class="pg-action-main" size="small" @click="openDetail(p)">查看原因</n-button>
              </template>
              <template v-else-if="isLive(p)">
                <template v-if="p.phase === 'running'">
                  <n-button class="pg-action-main" size="small" type="primary" @click="open(p)">
                    <template #icon
                      ><n-icon><OpenOutline /></n-icon></template
                    >打开
                  </n-button>
                  <n-button class="btn-stop" size="small" quaternary @click="stop(p)">
                    <template #icon
                      ><n-icon><StopOutline /></n-icon></template
                    >停止
                  </n-button>
                </template>
                <template v-else-if="p.phase === 'starting'">
                  <n-button class="pg-action-main" size="small" disabled>
                    <template #icon
                      ><n-icon><PlayOutline /></n-icon></template
                    >启动中
                  </n-button>
                  <n-button class="btn-stop" size="small" quaternary @click="stop(p)">
                    <template #icon
                      ><n-icon><StopOutline /></n-icon></template
                    >停止
                  </n-button>
                </template>
                <n-button v-else class="pg-action-main" size="small" disabled>停止中…</n-button>
              </template>
              <n-button
                v-else
                class="pg-action-main"
                size="small"
                type="primary"
                :disabled="!p.storage_ready || p.content_ready === false || isActive(p) || !!activeTask"
                :title="activeTask ? '安装任务进行中' : isActive(p) ? '该程序正在运行' : undefined"
                @click="start(p)"
              >
                <template #icon>
                  <n-icon>
                    <RefreshOutline v-if="p.phase === 'failed' || p.circuit_open" />
                    <PlayOutline v-else />
                  </n-icon>
                </template>
                {{ startLabel(p) }}
              </n-button>
              <span class="pg-spacer" />
              <n-dropdown :options="menuOf(p)" trigger="click" size="small" @select="(key: string) => onMenu(key, p)">
                <n-button size="small" quaternary class="more-btn" aria-label="更多操作">⋯</n-button>
              </n-dropdown>
            </div>
          </article>
        </div>
      </section>

      <!-- 未安装 -->
      <section v-if="newPrograms.length" class="section" aria-labelledby="programs-new">
        <div class="section-head">
          <h2 id="programs-new">
            未安装 <span class="count">{{ newPrograms.length }}</span>
          </h2>
        </div>
        <div class="tile-grid">
          <article
            v-for="p in newPrograms"
            :key="p.id"
            class="pg-tile is-new"
            :class="`tone-${tileState(p)}`"
            :data-id="p.id"
          >
            <button class="pg-open" type="button" :aria-label="`查看 ${p.name} 的详情`" @click="openDetail(p)">
              <span class="pg-top">
                <span class="glyph">{{ initials(p.name) }}<i class="led" :class="dotClass(p)"></i></span>
                <span class="pg-ident">
                  <span class="name">{{ p.name }}</span>
                  <span class="ver">
                    {{ versionLabel(p) }}
                    <span v-if="p.requires_root" class="chip" title="需要 root 权限">需 root</span>
                  </span>
                </span>
              </span>
              <span class="pg-status"
                ><span class="status-text">{{ statusLine(p) }}</span></span
              >
              <span class="pg-summary">{{ p.summary || '暂无简介' }}</span>
              <span class="pg-metrics">
                <span v-for="m in tileMetrics(p)" :key="m.k" class="pg-metric">
                  <span class="k">{{ m.k }}</span>
                  <span class="v" :class="{ mono: m.mono }">{{ m.v }}</span>
                </span>
              </span>
            </button>
            <div class="pg-actions">
              <n-button
                class="pg-action-main"
                size="small"
                :type="p.available && online && !activeTask && !isActive(p) ? 'primary' : 'default'"
                :disabled="!p.available || !online || !!activeTask || isActive(p)"
                :title="
                  !online
                    ? '安装需要联网下载'
                    : activeTask
                      ? '已有安装任务在进行'
                      : isActive(p)
                        ? '该程序正在运行，先停止它'
                        : undefined
                "
                @click="openInstall(p, 'install')"
              >
                <template #icon
                  ><n-icon><DownloadOutline /></n-icon
                ></template>
                {{
                  !p.available
                    ? '暂无版本'
                    : !online
                      ? '需要联网'
                      : activeTask
                        ? '安装中'
                        : isActive(p)
                          ? '先停止程序'
                          : '安装'
                }}
              </n-button>
              <span class="pg-spacer" />
              <n-dropdown :options="menuOf(p)" trigger="click" size="small" @select="(key: string) => onMenu(key, p)">
                <n-button size="small" quaternary class="more-btn" aria-label="更多操作">⋯</n-button>
              </n-dropdown>
            </div>
          </article>
        </div>
      </section>

      <!-- 空状态：区分搜索无结果 / 筛选为空 -->
      <div v-if="!loading && !installedPrograms.length && !newPrograms.length" class="empty">
        <n-icon :size="22">
          <SearchOutline v-if="searchQuery" />
          <AlertCircleOutline v-else />
        </n-icon>
        <h3>{{ emptyTitle }}</h3>
        <p>{{ emptyHint }}</p>
        <n-button v-if="searchQuery || filter !== 'all'" size="small" @click="resetFilters">清除筛选</n-button>
      </div>
    </template>

    <!-- 安装 / 更新配置 -->
    <n-modal v-model:show="installOpen" preset="card" style="max-width: 520px" :title="installTitle">
      <div v-if="installTarget" class="modal-body">
        <div class="mrow">
          <span class="mk">程序</span>
          <span class="mv">{{ installTarget.name }} · {{ versionLabel(installTarget) }}</span>
        </div>
        <div class="mrow">
          <span class="mk">下载</span>
          <span class="mv">{{ fmtSize(installTarget.download_size) }}（由发布源直接下载，不包含在 APK 中）</span>
        </div>

        <template v-if="installIsUpdate">
          <div class="mrow">
            <span class="mk">更新</span>
            <span class="mv">{{ versionLabel(installTarget) }} → {{ vLabel(installTarget.latest_version) }}</span>
          </div>
          <div class="mhint">将替换程序文件并保留数据目录；访问方式保持当前设置。</div>
        </template>

        <template v-else>
          <template v-if="installTarget?.content_enabled">
            <div class="mrow">
              <span class="mk">内容位置</span>
              <n-select
                v-model:value="installContentRoot"
                size="small"
                class="content-select"
                :options="volumeOptions"
                :render-label="renderVolumeLabel"
                placeholder="选择内容目录"
              />
            </div>
            <div class="mhint">
              此处用于存放上传文件、媒体库等大文件；数据库与密钥仍保存在内部存储。如已插入 SD 卡，建议选择该位置。
            </div>
            <div v-if="contentRootIssue" class="mhint warn" role="status">{{ contentRootIssue }}</div>
            <div class="mhint mono">实际位置：{{ contentTargetHint }}</div>
          </template>
          <div class="mrow">
            <span class="mk">访问方式</span>
            <n-radio-group v-model:value="installMode" size="small">
              <n-radio-button value="direct">直连端口</n-radio-button>
              <n-radio-button value="gateway">经 Core 转发</n-radio-button>
            </n-radio-group>
          </div>
          <div class="mhint">{{ modeHint }}</div>
          <div v-if="installTarget && isActive(installTarget)" class="mhint warn">
            该程序正在运行：安装会替换它的二进制，先停止它再继续。
          </div>
        </template>

        <div class="mhint warn">
          <strong>安全提示：</strong>程序将以 UFI-AXIS Core
          的系统服务账号运行，可访问该账号的私有数据（包括已保存的运营商账号信息和设备令牌）。 请仅安装来源可信的程序。
        </div>
        <n-checkbox v-model:checked="riskAck" class="risk-check"
          >我已了解共用系统账号的风险，仅安装来源可信的程序。</n-checkbox
        >
        <div v-if="!online" class="mhint warn">当前设备离线，需要联网下载安装包。</div>
        <div v-else-if="activeTask" class="mhint warn">已有安装任务在进行。</div>
      </div>
      <template #footer>
        <div class="modal-foot">
          <n-button size="small" @click="installOpen = false">取消</n-button>
          <n-button
            size="small"
            type="primary"
            :disabled="
              !riskAck ||
              !online ||
              !!activeTask ||
              (installTarget ? isActive(installTarget) : false) ||
              contentRootBlocking
            "
            @click="confirmInstall"
          >
            {{ installIsUpdate ? '开始更新' : '开始下载' }}
          </n-button>
        </div>
      </template>
    </n-modal>

    <!-- 管理员凭据：安装完成后自动弹出；内容是可发放给使用者的正式说明 -->
    <n-modal v-model:show="secretOpen" preset="card" style="max-width: 540px" title="管理员凭据">
      <div class="modal-body secret-modal">
        <div class="secret-head">
          <span class="secret-program">{{ secretName }}</span>
          <n-tag v-if="secretAuto" size="small" type="success" :bordered="false">安装完成</n-tag>
        </div>

        <p class="secret-intro">
          这是 <strong>{{ secretName }}</strong> 首次初始化时生成的管理员凭据，用于登录程序自身的管理界面， 不是
          UFI-AXIS 或设备后台的登录密码。
        </p>

        <div class="secret-fields">
          <div v-if="secretAccount" class="secret-field">
            <span class="secret-label">用户名</span>
            <code class="secret-code">{{ secretAccount }}</code>
            <n-button size="tiny" quaternary @click="copyText(secretAccount, '用户名已复制')">复制</n-button>
          </div>
          <div class="secret-field">
            <span class="secret-label">密码</span>
            <code class="secret-code secret-password">{{ secretPassword }}</code>
            <n-button size="tiny" quaternary @click="copyText(secretPassword, '密码已复制')">复制</n-button>
          </div>
        </div>

        <div class="secret-note">
          <strong>保存与找回</strong>
          <p>{{ secretNote }}</p>
          <p>需要自定义口令时，停止程序后点击下方「忘记 / 自定义」即可重新设置，旧口令会立即失效。</p>
        </div>
      </div>
      <template #footer>
        <div class="modal-foot secret-foot">
          <n-button size="small" @click="secretTarget && openResetSecret(secretTarget)">忘记 / 自定义</n-button>
          <span class="secret-foot-spacer"></span>
          <n-button size="small" type="primary" @click="secretOpen = false">我已保存</n-button>
        </div>
      </template>
    </n-modal>

    <!-- 重置 / 自定义管理员凭据 -->
    <n-modal v-model:show="resetOpen" preset="card" style="max-width: 480px" title="重置管理员凭据">
      <div v-if="resetTarget" class="modal-body">
        <div class="mrow">
          <span class="mk">程序</span><span class="mv">{{ resetTarget.name }}</span>
        </div>
        <p class="secret-intro">将重新设置管理员凭据，旧口令立即失效。请先停止程序；数据不会被删除。</p>
        <label class="field">
          <span class="field-label">新密码（可选）</span>
          <n-input
            v-model:value="resetPassword"
            type="password"
            show-password-on="click"
            placeholder="留空则生成随机口令"
            :maxlength="64"
          />
        </label>
        <div class="mhint">自定义口令需为 8–64 位字母、数字或 . _ -；留空由设备生成随机口令。</div>
      </div>
      <template #footer>
        <div class="modal-foot">
          <n-button size="small" @click="resetOpen = false">取消</n-button>
          <n-button size="small" type="primary" :loading="resetBusy" @click="submitResetSecret">
            {{ resetPassword.trim() ? '设置并查看' : '生成并查看' }}
          </n-button>
        </div>
      </template>
    </n-modal>

    <!-- 完整日志 -->
    <n-modal v-model:show="logOpen" preset="card" style="max-width: 720px" :title="`日志 · ${logTarget?.name ?? ''}`">
      <pre class="log-body">{{ logText || '加载中…' }}</pre>
    </n-modal>

    <!-- 程序设置（2026-11-10）：访问方式 / 端口 / 开机自启；数据位置安装后不可迁移 -->
    <n-modal
      v-model:show="settingsOpen"
      preset="card"
      style="max-width: 500px"
      :title="`设置 · ${settingsTarget?.name ?? ''}`"
    >
      <div v-if="settingsTarget" class="modal-body">
        <div class="mrow lock-area">
          <span class="mk">访问方式</span>
          <n-radio-group v-model:value="settingsForm.accessMode" size="small" :disabled="settingsRunning">
            <n-radio-button value="direct">直连端口</n-radio-button>
            <n-radio-button value="gateway">经 Core 转发</n-radio-button>
          </n-radio-group>
          <!-- 运行中控件被禁用，点击本身不会触发事件；用透明层接住点击并提示"先停止" -->
          <div v-if="settingsRunning" class="lock-hit" @click="warnSettingsRunning" />
        </div>
        <div class="mhint">{{ settingsModeHint }}</div>

        <div class="mrow lock-area">
          <span class="mk">内部端口</span>
          <n-input-number
            v-model:value="settingsForm.internalPort"
            size="small"
            :min="1024"
            :max="65535"
            :disabled="settingsRunning"
            class="port-input"
          />
          <div v-if="settingsRunning" class="lock-hit" @click="warnSettingsRunning" />
        </div>
        <div class="mhint">程序实际监听的端口（直连模式下即浏览器访问端口），取值范围 1024–65535；下次启动生效。</div>

        <template v-if="settingsForm.accessMode === 'gateway'">
          <div class="mrow lock-area">
            <span class="mk">外部端口</span>
            <n-input-number
              v-model:value="settingsForm.externalPort"
              size="small"
              :min="1024"
              :max="65535"
              :disabled="settingsRunning"
              class="port-input"
            />
            <div v-if="settingsRunning" class="lock-hit" @click="warnSettingsRunning" />
          </div>
          <div class="mhint">经 Core 转发时浏览器访问的端口；端口被占用时将自动顺延。下次启动生效。</div>
        </template>

        <ToggleRow
          v-model="settingsForm.autostart"
          label="开机自启"
          description="UFI-AXIS Core 启动后自动启动该程序；此设置与手动“启动/停止”相互独立。"
          :disabled="settingsSaving"
        />

        <div class="mrow">
          <span class="mk">数据位置</span>
          <span class="mv">{{ settingsTarget.storage === 'external' ? '外部存储' : '内部存储' }}</span>
        </div>
        <div class="mhint">
          数据位置在安装时确定，当前不支持在线迁移。如需更换，请卸载后重新安装；原有数据不会自动迁移。
        </div>

        <div v-if="settingsRunning" class="mhint warn">
          程序正在运行：访问方式与端口需要先停止程序才能修改；开机自启不受影响。
        </div>
      </div>
      <template #footer>
        <div class="modal-foot">
          <n-button v-if="settingsRunning" size="small" @click="settingsTarget && stop(settingsTarget)">
            停止程序
          </n-button>
          <n-button size="small" @click="settingsOpen = false">取消</n-button>
          <n-button
            size="small"
            type="primary"
            :loading="settingsSaving"
            :disabled="!settingsTarget"
            @click="saveSettings"
          >
            保存
          </n-button>
        </div>
      </template>
    </n-modal>

    <!--
      详情抽屉：桌面右侧，手机全屏。
      z-index 1500 < n-modal / n-dialog 的 2000：从抽屉里再打开日志、更新、
      卸载确认时，弹窗不会被抽屉盖住。
    -->
    <n-drawer
      v-model:show="detailOpen"
      class="programs-drawer"
      :width="isMobile ? '100%' : 460"
      :z-index="1500"
      placement="right"
      :trap-focus="true"
    >
      <n-drawer-content :body-style="{ padding: 0 }" :closable="false">
        <div v-if="detailProgram" class="detail-sheet">
          <header class="detail-head" :class="`tone-${tileState(detailProgram)}`">
            <span class="glyph">
              {{ initials(detailProgram.name) }}<i class="led" :class="dotClass(detailProgram)"></i>
            </span>
            <div class="detail-title">
              <h2>{{ detailProgram.name }}</h2>
              <div class="detail-sub">
                {{ versionLabel(detailProgram) }} · {{ detailProgram.license || '—' }}
                <span v-if="updateAvailable(detailProgram)" class="chip chip-update">可更新</span>
                <span v-if="detailProgram.requires_root" class="chip" title="需要 root 权限">需 root</span>
              </div>
            </div>
            <n-button quaternary circle aria-label="关闭详情" @click="detailOpen = false">
              <template #icon
                ><n-icon :size="18"><CloseOutline /></n-icon
              ></template>
            </n-button>
          </header>

          <div class="detail-body">
            <div class="detail-status" :class="`tone-${tileState(detailProgram)}`">
              <span class="status-text">{{ statusLine(detailProgram) }}</span>
              <span v-if="statusNote(detailProgram)" class="detail-note" :class="{ error: noteIsError(detailProgram) }">
                {{ statusNote(detailProgram) }}
              </span>
            </div>

            <div class="detail-main-actions">
              <template v-if="!detailProgram.installed">
                <n-button
                  class="pg-action-main"
                  :type="detailProgram.available ? 'primary' : 'default'"
                  :disabled="!detailProgram.available"
                  @click="openInstall(detailProgram, 'install')"
                >
                  <template #icon
                    ><n-icon><DownloadOutline /></n-icon
                  ></template>
                  {{ detailProgram.available ? '安装' : '暂无版本' }}
                </n-button>
              </template>
              <template v-else-if="isStorageIssue(detailProgram)">
                <n-button class="pg-action-main" disabled>等待存储恢复</n-button>
              </template>
              <template v-else-if="detailProgram.phase === 'running'">
                <n-button class="pg-action-main" type="primary" @click="open(detailProgram)">
                  <template #icon
                    ><n-icon><OpenOutline /></n-icon></template
                  >打开
                </n-button>
                <n-button @click="stop(detailProgram)">
                  <template #icon
                    ><n-icon><StopOutline /></n-icon></template
                  >停止
                </n-button>
              </template>
              <template v-else-if="detailProgram.phase === 'starting'">
                <n-button class="pg-action-main" disabled>
                  <template #icon
                    ><n-icon><PlayOutline /></n-icon></template
                  >启动中
                </n-button>
                <n-button @click="stop(detailProgram)">
                  <template #icon
                    ><n-icon><StopOutline /></n-icon></template
                  >停止
                </n-button>
              </template>
              <template v-else-if="detailProgram.phase === 'stopping'">
                <n-button class="pg-action-main" disabled>停止中…</n-button>
              </template>
              <n-button
                v-else
                class="pg-action-main"
                type="primary"
                :disabled="
                  !detailProgram.storage_ready ||
                  detailProgram.content_ready === false ||
                  isActive(detailProgram) ||
                  !!activeTask
                "
                :title="activeTask ? '安装任务进行中' : isActive(detailProgram) ? '该程序正在运行' : undefined"
                @click="start(detailProgram)"
              >
                <template #icon>
                  <n-icon>
                    <RefreshOutline v-if="detailProgram.phase === 'failed' || detailProgram.circuit_open" />
                    <PlayOutline v-else />
                  </n-icon>
                </template>
                {{ startLabel(detailProgram) }}
              </n-button>
              <n-button v-if="detailProgram.installed" @click="loadLogs(detailProgram)">
                <template #icon
                  ><n-icon><TerminalOutline /></n-icon></template
                >日志
              </n-button>
            </div>

            <div v-if="detailMetrics.length" class="detail-metrics">
              <div v-for="m in detailMetrics" :key="m.k" class="detail-metric">
                <span class="k">{{ m.k }}</span>
                <span class="v" :class="{ mono: m.mono }">{{ m.v }}</span>
              </div>
            </div>

            <p class="detail-summary">{{ detailProgram.summary || '暂无简介' }}</p>

            <dl class="detail-meta">
              <div>
                <dt>版本</dt>
                <dd class="mono">{{ versionLabel(detailProgram) }}</dd>
              </div>
              <div>
                <dt>来源</dt>
                <dd>{{ sourceLabel(detailProgram) }}</dd>
              </div>
              <div>
                <dt>安装位置</dt>
                <dd>{{ detailProgram.installed ? storageLabel(detailProgram) : '安装时选择' }}</dd>
              </div>
              <div v-if="detailProgram.content_enabled">
                <dt>内容位置</dt>
                <dd class="mono">
                  {{
                    detailProgram.installed
                      ? `${detailProgram.content_dir}${
                          detailProgram.content_bytes ? ` · ${fmtSize(detailProgram.content_bytes)}` : ''
                        }`
                      : '安装时选择（可放 SD 卡）'
                  }}
                </dd>
              </div>
              <div v-if="detailProgram.installed">
                <dt>占用</dt>
                <dd>
                  <template v-if="detailProgram.used_bytes > 0">
                    {{ sizeText(detailProgram.used_bytes) }}（含二进制）
                  </template>
                  <template v-else>—</template>
                  <template v-if="detailProgram.log_bytes">· 日志 {{ fmtSize(detailProgram.log_bytes) }}</template>
                  <template v-if="detailProgram.cache_bytes">· 缓存 {{ fmtSize(detailProgram.cache_bytes) }}</template>
                </dd>
              </div>
              <div>
                <dt>访问方式</dt>
                <dd>{{ detailProgram.installed ? accessLabel(detailProgram) : '安装时选择' }}</dd>
              </div>
              <div>
                <dt>许可证</dt>
                <dd>{{ detailProgram.license || '—' }}</dd>
              </div>
              <div>
                <dt>安装大小</dt>
                <dd>{{ fmtSize(detailProgram.installed_size || detailProgram.download_size) }}</dd>
              </div>
            </dl>

            <div v-if="isStorageIssue(detailProgram)" class="note note-warn">
              <n-icon :size="13"><AlertCircleOutline /></n-icon>
              <span>
                {{ storageIssueText(detailProgram) }}。插回后程序会自动恢复；如果长期不用外部存储，
                可以卸载后改装到内部存储。
              </span>
            </div>
            <div v-if="detailProgram.requires_root" class="note note-warn">
              <n-icon :size="13"><AlertCircleOutline /></n-icon>
              <span>该程序需要 root 权限：安装与启动时将调用设备的 su。</span>
            </div>
            <div
              v-if="updateAvailable(detailProgram) && !isStorageIssue(detailProgram)"
              class="note"
              :class="isActive(detailProgram) ? 'note-warn' : 'note-info'"
            >
              <n-icon :size="13"><DownloadOutline /></n-icon>
              <span>
                可更新到 {{ vLabel(detailProgram.latest_version) }}。
                {{ isActive(detailProgram) ? '更新前需要先停止该程序。' : '更新将替换程序文件并保留数据目录。' }}
              </span>
            </div>

            <div v-if="detailProgram.installed" class="detail-logs">
              <div class="detail-logs-head">
                <span>最近日志</span>
                <n-button text size="tiny" @click="loadLogs(detailProgram)">查看完整日志</n-button>
              </div>
              <pre>{{ detailLogLoading ? '加载中…' : detailLog || '（暂无日志）' }}</pre>
            </div>
            <div v-else-if="detailProgram.available" class="note note-info">
              <n-icon :size="13"><AlertCircleOutline /></n-icon>
              <span>安装包由发布源直接下载，不包含在 APK 中；首次安装需要联网。</span>
            </div>
            <div v-else class="note note-warn">
              <n-icon :size="13"><AlertCircleOutline /></n-icon>
              <span>当前程序源中暂无可安装版本。请检查网络连接或更新源后重试。</span>
            </div>

            <!-- 触屏没有 hover：禁用原因不能只放 title，否则灰掉的按钮看起来像坏了 -->
            <p v-if="detailProgram.installed && isActive(detailProgram)" class="detail-actions-hint">
              运行中：更新、重置口令与卸载需要先停止程序。
            </p>
            <div v-if="detailProgram.installed" class="detail-actions">
              <n-button size="small" @click="openSettings(detailProgram)">
                <template #icon
                  ><n-icon><SettingsOutline /></n-icon></template
                >设置
              </n-button>
              <n-button
                v-if="!isLive(detailProgram)"
                size="small"
                @click="setAccessMode(detailProgram, detailProgram.access_mode === 'direct' ? 'gateway' : 'direct')"
              >
                <template #icon
                  ><n-icon><SwapHorizontalOutline /></n-icon></template
                >切换访问方式
              </n-button>
              <n-button
                v-if="updateAvailable(detailProgram) && !isStorageIssue(detailProgram)"
                size="small"
                :disabled="isActive(detailProgram) || !!activeTask || !online"
                :title="
                  !online
                    ? '更新需要联网下载'
                    : isActive(detailProgram)
                      ? '该程序正在运行，先停止它'
                      : activeTask
                        ? '已有安装任务在进行'
                        : undefined
                "
                @click="openInstall(detailProgram, 'update')"
              >
                <template #icon
                  ><n-icon><DownloadOutline /></n-icon></template
                >更新
              </n-button>
              <n-button
                size="small"
                quaternary
                :disabled="isActive(detailProgram) || !!activeTask"
                :title="
                  isActive(detailProgram)
                    ? '该程序正在运行，先停止它'
                    : activeTask
                      ? '安装任务进行中'
                      : '会重置程序管理员口令'
                "
                @click="openResetSecret(detailProgram)"
              >
                <template #icon
                  ><n-icon><KeyOutline /></n-icon></template
                >重置口令
              </n-button>
              <n-button
                size="small"
                quaternary
                type="error"
                :disabled="isActive(detailProgram) || !!activeTask"
                :title="
                  isActive(detailProgram) ? '该程序正在运行，先停止它' : activeTask ? '安装任务进行中' : undefined
                "
                @click="uninstall(detailProgram)"
              >
                <template #icon
                  ><n-icon><TrashOutline /></n-icon></template
                >卸载
              </n-button>
            </div>
          </div>
        </div>
      </n-drawer-content>
    </n-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, h, nextTick, onMounted, onUnmounted, reactive, ref, watch, type VNodeChild } from 'vue';
import type { SelectOption } from 'naive-ui';
import { NCheckbox, useDialog, useMessage } from 'naive-ui';
import type { DropdownOption } from 'naive-ui';
import {
  AlertCircleOutline,
  CloseOutline,
  DownloadOutline,
  KeyOutline,
  OpenOutline,
  PlayOutline,
  RefreshOutline,
  SearchOutline,
  SettingsOutline,
  StopOutline,
  SwapHorizontalOutline,
  TerminalOutline,
  TrashOutline,
} from '@vicons/ionicons5';
import { getApiClient } from '@/composables/useApi';
import { useIsMobile } from '@/composables/useIsMobile';
import ToggleRow from '@/components/ToggleRow.vue';
import { Endpoints } from '@/api/contract';
import { isNewerVersion, vLabel } from './version';

interface ProgramItem {
  id: string;
  name: string;
  summary: string;
  homepage: string;
  license: string;
  available: boolean;
  latest_version: string;
  download_size: number;
  installed: boolean;
  installed_version: string;
  installed_size: number;
  source: string;
  phase: string;
  pid: number;
  rss_mb: number;
  uptime_ms: number;
  last_error: string;
  last_exit_code: number | null;
  desired: boolean;
  circuit_open: boolean;
  consecutive_failures: number;
  external_port: number;
  internal_port: number;
  requires_root: boolean;
  used_bytes: number;
  /** 日志占用（在 /sdcard，与数据不在同一个配额口径里） */
  log_bytes: number;
  /** {CACHE} 占用（程序的 TMPDIR，系统可回收） */
  cache_bytes: number;
  /** 数据目录含凭据 → 禁止选外部存储 */
  private_data: boolean;
  /** 该程序有独立的内容目录（大文件），可与数据目录分开存放 */
  content_enabled: boolean;
  /** 内容目录所在卷当前是否可用；未配置内容目录时恒 true */
  content_ready?: boolean;
  /** 内容目录实际路径 */
  content_dir: string;
  /** 内容目录占用字节 */
  content_bytes: number;
  has_secret: boolean;
  storage: string;
  storage_ready: boolean;
  access_mode: string;
  /** Core 启动后自动拉起（与当前是否在跑无关） */
  autostart: boolean;
  ui_path: string;
}

interface DownloadTask {
  id: string;
  phase: string;
  downloaded_bytes: number;
  total_bytes: number;
  candidate_index: number;
  message: string;
}

type FilterKey = 'all' | 'running' | 'installed' | 'new';
type SortKey = 'status' | 'name';

interface FilterItem {
  key: FilterKey;
  label: string;
  /** 数字 = 已知数量；`—` = 首次读取尚未完成（不拿 0 冒充）。 */
  count: number | string;
}

interface DetailMetric {
  k: string;
  v: string;
  mono?: boolean;
}

type TileMetric = DetailMetric;

const message = useMessage();
const dialog = useDialog();
/** 卸载弹窗里「同时删除日志（彻底卸载）」的勾选状态。 */
const purgeLogs = ref(false);
const client = getApiClient();
const isMobile = useIsMobile();

const programs = ref<ProgramItem[]>([]);
const loading = ref(false);
/** 筛选组容器：用来把未选中的单选设成 tabindex=-1（roving tabindex） */
const filterWrap = ref<HTMLElement | null>(null);
/** 首次/手动读取失败；有旧数据时不清空列表，但无数据时要给页内重试。 */
const loadFailed = ref(false);
const activeTask = ref<DownloadTask | null>(null);
/** 刚提交、但 /status 可能还没进入 BUSY 的安装任务；用于把“1 秒内就失败”也提示出来。 */
const pendingInstallId = ref<string | null>(null);
/** 安装完成后待自动展示凭据的程序 id（只在本页发起安装时设置） */
const autoRevealId = ref<string | null>(null);
const searchQuery = ref('');
const filter = ref<FilterKey>('all');
const sortMode = ref<SortKey>('status');
const nativeExecOk = ref(true);
const online = ref(typeof navigator === 'undefined' ? true : navigator.onLine);

/** 同 uid 风险确认：每次打开安装/更新弹窗都重置，必须重新勾选。 */
const riskAck = ref(false);

/** 初始口令：列表只给 has_secret，值必须调 secret 端点消费。 */
const secretOpen = ref(false);
const secretLoading = ref(false);
const secretName = ref('');
const secretId = ref('');
/** 弹窗打开后列表可能因为 OpenList 的一次性消费而不再包含该程序，重置需要独立持有目标。 */
const secretTarget = ref<ProgramItem | null>(null);
const secretAccount = ref('');
const secretPassword = ref('');
/** 是否为安装完成后自动弹出的凭据（文案用） */
const secretAuto = ref(false);

/** 自定义/重置口令弹窗 */
const resetOpen = ref(false);
const resetTarget = ref<ProgramItem | null>(null);
const resetPassword = ref('');
const resetBusy = ref(false);

/** 安装 / 更新配置弹窗 */
const installOpen = ref(false);
const installTarget = ref<ProgramItem | null>(null);
/** 内容目录的父路径；'' 表示内部默认（programs/<id>/content） */
const installContentRoot = ref('');
const volumes = ref<
  Array<{
    id: string;
    label: string;
    path: string | null;
    removable: boolean;
    /** false = 卷当前未挂载/不可写；旧 core 不返回该字段，按已挂载处理 */
    mounted?: boolean;
    total_bytes: number;
    used_bytes: number;
    /** -1 = 未知（卷不可用）；0 也当未知处理 */
    free_bytes: number;
  }>
>([]);
const installMode = ref('direct');
const installIsUpdate = ref(false);

/** 完整日志弹窗 */
const logOpen = ref(false);
const logTarget = ref<ProgramItem | null>(null);
const logText = ref('');

/** 详情抽屉 */
const detailOpen = ref(false);
const detailId = ref<string | null>(null);
const detailLog = ref('');
const detailLogLoading = ref(false);

/** 程序设置弹窗（2026-11-10）：访问方式 / 端口 / 开机自启 */
const settingsOpen = ref(false);
const settingsId = ref<string | null>(null);
const settingsSaving = ref(false);
const settingsForm = reactive<{
  accessMode: 'direct' | 'gateway';
  internalPort: number | null;
  externalPort: number | null;
  autostart: boolean;
}>({
  accessMode: 'direct',
  internalPort: null,
  externalPort: null,
  autostart: false,
});
const settingsTarget = computed(() => programs.value.find((p) => p.id === settingsId.value) ?? null);
const settingsRunning = computed(() => isActive(settingsTarget.value));
const settingsModeHint = computed(() =>
  settingsForm.accessMode === 'gateway'
    ? '程序仅监听本机地址，由 UFI-AXIS Core 使用独立端口转发；局域网无法绕过控制台直接访问。'
    : '通过“设备 IP:内部端口”直接访问；程序端口会暴露在局域网，安全性由程序自身的登录验证保障。'
);

const BUSY_PHASES = new Set(['downloading', 'verifying', 'extracting', 'installing', 'initializing']);

const installedCount = computed(() => programs.value.filter((p) => p.installed).length);
const runningCount = computed(() => programs.value.filter((p) => p.installed && p.phase === 'running').length);
const startingCount = computed(() => programs.value.filter((p) => p.installed && p.phase === 'starting').length);

/** 任何程序在跑时，core 的 install / uninstall 路由会直接拒绝（全局限制，不是单程序限制）。 */
/** 该程序自己是否处于运行/过渡态（多实例后，别的程序在跑不再阻塞它）。 */
function isActive(p: ProgramItem | null | undefined): boolean {
  if (!p) return false;
  return p.installed && (p.phase === 'running' || p.phase === 'starting' || p.phase === 'stopping');
}

/**
 * 首次读取期间不给计数：这时 `programs` 还是空数组，照实渲染就是「全部 0」，
 * 而真实数量未知 —— 与「读失败」一样属于假状态。用 `—` 表示「还没读到」。
 */
const countsPending = computed(() => (loading.value || loadFailed.value) && !programs.value.length);
const filters = computed<FilterItem[]>(() => [
  { key: 'all', label: '全部', count: countsPending.value ? '—' : programs.value.length },
  { key: 'running', label: '运行中', count: countsPending.value ? '—' : runningCount.value + startingCount.value },
  { key: 'installed', label: '已安装', count: countsPending.value ? '—' : installedCount.value },
  { key: 'new', label: '未安装', count: countsPending.value ? '—' : programs.value.length - installedCount.value },
]);

/** 白色胶囊的轨道位置；四段等宽，用 transform 做终端页同款滑动。 */
const filterIndex = computed(() =>
  Math.max(
    0,
    filters.value.findIndex((f) => f.key === filter.value)
  )
);

const installedPrograms = computed(() => {
  if (filter.value === 'new') return [];
  const q = searchQuery.value.trim().toLowerCase();
  const list = programs.value.filter((p) => p.installed && matchesQuery(p, q));
  const visible =
    filter.value === 'running' ? list.filter((p) => p.phase === 'running' || p.phase === 'starting') : list;
  return visible.sort(sortMode.value === 'status' ? sortByStatus : sortByName);
});

const newPrograms = computed(() => {
  if (filter.value === 'installed' || filter.value === 'running') return [];
  const q = searchQuery.value.trim().toLowerCase();
  return programs.value.filter((p) => !p.installed && matchesQuery(p, q)).sort(sortNew);
});

const attentionPrograms = computed(() => programs.value.filter((p) => p.installed && isAttention(p)));
const attentionSummary = computed(() => {
  const list = attentionPrograms.value;
  const names = list.slice(0, 2).map(attentionLabel).join(' · ');
  return list.length > 2 ? `${names} 等 ${list.length} 个` : names;
});

const secretProgram = computed(() => programs.value.find((p) => p.installed && p.has_secret) ?? null);

/** 不同适配器的凭据语义不同：dufs 可以再次查看（YAML 持久化），OpenList 只在本次 Core 内存里。 */
const secretIsPersistent = computed(() => secretProgram.value?.id === 'dufs' || secretId.value === 'dufs');
const secretBannerHint = computed(() =>
  secretProgram.value?.id === 'dufs'
    ? '首次初始化生成了管理员凭据；明文可再次查看，重置后旧口令立即失效。'
    : '首次初始化生成了管理员凭据，仅可查看一次；忘记后请停止程序再重置。'
);
const secretNote = computed(() =>
  secretIsPersistent.value
    ? 'dufs 的凭据保存在设备配置中，可再次查看；忘记或想换口令，可在停止程序后重置。'
    : '该口令只保存在本次 Core 运行的内存中；关闭后无法再次查看，忘记时只能重置。'
);
const taskProgram = computed(() => {
  const t = activeTask.value;
  return t ? (programs.value.find((p) => p.id === t.id) ?? null) : null;
});

const taskTitle = computed(() => {
  const t = activeTask.value;
  if (!t) return '';
  const name = taskProgram.value?.name ?? t.id;
  if (t.phase === 'downloading') return `正在下载 ${name}`;
  if (t.phase === 'initializing') return `正在初始化 ${name}`;
  return `正在安装 ${name}`;
});

const detailProgram = computed(() => programs.value.find((p) => p.id === detailId.value) ?? null);

const detailMetrics = computed<DetailMetric[]>(() => {
  const p = detailProgram.value;
  if (!p) return [];
  if (!p.installed) {
    return [
      { k: '下载大小', v: sizeText(p.download_size) },
      { k: '最新版本', v: vLabel(p.latest_version) },
      { k: '许可证', v: p.license || '—' },
      { k: 'root 权限', v: p.requires_root ? '需要' : '不需要' },
    ];
  }
  const third = isStorageIssue(p)
    ? { k: '存储', v: '外部不可用' }
    : p.circuit_open
      ? { k: '连续失败', v: `${p.consecutive_failures} 次` }
      : p.phase === 'failed'
        ? { k: '连续失败', v: `${p.consecutive_failures} 次` }
        : p.phase === 'running'
          ? { k: '运行时长', v: fmtDuration(p.uptime_ms) || '—' }
          : p.phase === 'starting'
            ? { k: '状态', v: '启动中' }
            : p.phase === 'stopping'
              ? { k: '状态', v: '停止中' }
              : { k: '上次退出码', v: p.last_exit_code != null ? String(p.last_exit_code) : '—', mono: true };
  // 只展示有读数的指标。停止中的程序通常只有端口有值，四格全摆出来会有三块
  // 大号「—」，在抽屉里既占高度又让人误以为采集失败。
  return [
    { k: '内存', v: memoryLabel(p) },
    { k: '端口', v: p.internal_port ? String(p.internal_port) : '—', mono: true },
    third,
    { k: '占用', v: sizeText(p.used_bytes) },
  ].filter((m) => m.v && m.v !== '—');
});

/**
 * 卡片指标（2026-11-11 修正）。
 *
 * 两条原则：
 *  ① **不摆占位**：未安装卡原来有「位置 可选 / 访问 可选」两格，它们不是数据，
 *     却占了四格里的两格；现在只给下载 / 许可 / 来源 三项真实信息。
 *  ② **未知就是未知**：`fmtSize(0)` 返回 `0B`，在运行中的程序上读起来像"真的一点没占"，
 *     实际是 core 没给这个值。这里改走 `sizeText()`，没有读数就显示 `—`。
 */
function sizeText(bytes: number | null | undefined): string {
  return typeof bytes === 'number' && bytes > 0 ? fmtSize(bytes) : '—';
}

function tileMetrics(p: ProgramItem): TileMetric[] {
  if (!p.installed) {
    return [
      { k: '下载', v: sizeText(p.download_size) },
      { k: '许可', v: p.license || '—' },
      { k: '来源', v: '发布源下载' },
    ];
  }
  if (isStorageIssue(p)) {
    return [
      { k: '存储', v: '外部不可用' },
      { k: '端口', v: p.internal_port ? String(p.internal_port) : '—', mono: true },
      { k: '占用', v: sizeText(p.used_bytes) },
      { k: '访问', v: p.access_mode === 'gateway' ? '转发' : '直连' },
    ];
  }
  if (p.phase === 'failed' || p.circuit_open) {
    return [
      { k: '端口', v: p.internal_port ? String(p.internal_port) : '—', mono: true },
      { k: '上次退出码', v: p.last_exit_code != null ? String(p.last_exit_code) : '—', mono: true },
      { k: '占用', v: sizeText(p.used_bytes) },
      { k: '存储', v: p.storage === 'external' ? '外部' : '内部' },
    ];
  }
  if (p.phase === 'running' || p.phase === 'starting') {
    return [
      p.rss_mb > 0 ? { k: '内存', v: `${p.rss_mb} MB` } : { k: '存储', v: p.storage === 'external' ? '外部' : '内部' },
      { k: '端口', v: p.internal_port ? String(p.internal_port) : '—', mono: true },
      { k: '占用', v: sizeText(p.used_bytes) },
      { k: '访问', v: p.access_mode === 'gateway' ? '转发' : '直连' },
    ];
  }
  return [
    { k: '端口', v: p.internal_port ? String(p.internal_port) : '—', mono: true },
    { k: '占用', v: sizeText(p.used_bytes) },
    { k: '存储', v: p.storage === 'external' ? '外部' : '内部' },
    { k: '访问', v: p.access_mode === 'gateway' ? '转发' : '直连' },
  ];
}

const installTitle = computed(() => (installIsUpdate.value ? '更新程序' : '安装程序'));

/** 内容目录至少要留这么多余量才允许选：与 core 的 CONTENT_MIN_FREE_BYTES 一致。 */
const CONTENT_MIN_FREE_BYTES = 128 * 1024 * 1024;

/**
 * 一个卷的容量文案。
 *
 * 三种「没有数字」的情况必须分开说，不能都格式化成 0B：
 *  · mounted=false / free_bytes<0 → 未挂载（不可选）
 *  · free_bytes=0 → 容量未知（不可选）
 *  · total_bytes=0 → 只报可用量，不编一个 0B 总量
 */
function volumeCapacity(v: { mounted?: boolean; free_bytes: number; total_bytes: number }): string {
  if (v.mounted === false || v.free_bytes < 0) return ' · 未挂载';
  if (!v.free_bytes) return ' · 容量未知';
  const free = `${fmtSize(v.free_bytes)} 可用`;
  return v.total_bytes > 0 ? ` · ${free} / ${fmtSize(v.total_bytes)}` : ` · ${free}`;
}

/** 内容目录候选：内部（空串）+ 公共存储 + SD 卡。由 core 用 StorageManager 枚举，前端不猜路径。 */
const volumeOptions = computed(() =>
  volumes.value.map((v) => {
    const lowSpace = v.mounted !== false && v.free_bytes >= 0 && v.free_bytes < CONTENT_MIN_FREE_BYTES;
    return {
      // 关闭态显示完整一行；展开后由 #render-label 拆成「卷名 / 容量」两行，
      // 避免 320px 宽把「1004GB」截成「1004…」——总容量正是选卷依据。
      label: `${v.label}${volumeCapacity(v)}${v.removable ? ' · 可移除' : ''}`,
      value: v.path ?? '',
      disabled: v.mounted === false || v.free_bytes <= 0 || lowSpace,
      lowSpace,
      volumeName: v.label,
      volumeMeta: `${volumeCapacity(v).replace(/^ · /, '')}${v.removable ? ' · 可移除' : ''}`,
      volumeBadge: v.mounted === false ? '未挂载' : v.free_bytes <= 0 ? '容量未知' : lowSpace ? '空间不足' : '',
    };
  })
);

/**
 * 下拉选项的两行渲染（Naive 这个版本通过 `render-label` 属性传渲染函数，没有同名插槽）。
 * 第一行卷名（带「空间不足 / 未挂载 / 容量未知」徽标），第二行容量 —— 320px 宽时
 * 单行会把「1004GB」截成「1004…」，而总容量正是选卷依据。
 */
/**
 * 下拉选项内容。
 *
 * 用 `render-label` 而不是 `render-option`：后者会替换掉 Naive 整个 option 节点，
 * 连同它的 click/keyboard/role 包装一起丢掉 —— 表现就是“菜单能展开，但点任何一项都没反应”。
 * `render-label` 只替换 option 内部的文字区，外壳仍由 Naive 提供。
 * selected=true 时是**已选值**（控件里那 28px 高的一行），只给一行；
 * 其余情况是下拉菜单项，给两行。
 */
function renderVolumeLabel(option: SelectOption, selected: boolean): VNodeChild {
  const o = option as SelectOption & {
    volumeName?: string;
    volumeMeta?: string;
    volumeBadge?: string;
    label?: string;
  };
  // 选中值只显示卷名：`卷名 + 容量 + 可移除` 在 392px 的控件里必然顶到边框，
  // 容量在下拉项里已有两行完整展示；选中态保持一个可读的短标签。
  if (selected) return o.volumeName ?? o.label ?? '';
  return h('span', { class: 'vol-opt' }, [
    h('span', { class: 'vol-name' }, [
      o.volumeName ?? '',
      o.volumeBadge ? h('span', { class: 'vol-badge' }, o.volumeBadge) : null,
    ]),
    h('span', { class: 'vol-meta' }, o.volumeMeta ?? ''),
  ]);
}

/** 当前选中的卷（内部 = path 为空的项）。 */
const selectedVolume = computed(() => volumes.value.find((v) => (v.path ?? '') === installContentRoot.value) ?? null);

/** 真正阻止安装的问题（未挂载 / 容量未知 / 空间不足）。 */
const contentRootBlocking = computed(() => {
  const v = selectedVolume.value;
  if (!v) return false;
  if (v.mounted === false || v.free_bytes < 0) return true;
  if (!v.free_bytes) return true;
  return v.free_bytes < CONTENT_MIN_FREE_BYTES;
});

/**
 * 选中卷的提示文案。
 *
 * 注意「可移除」只是提醒，**不**阻止安装；阻止安装的只有 [contentRootBlocking]。
 * 之前把两者合成一个非空字符串，结果选了可移除卷后「开始下载」被永久禁用。
 */
const contentRootIssue = computed(() => {
  const v = selectedVolume.value;
  if (!v) return '';
  if (v.mounted === false || v.free_bytes < 0) return '该存储卷当前未挂载，无法作为内容目录。';
  if (!v.free_bytes) return '无法确认该卷的剩余空间，请换一个位置。';
  if (v.free_bytes < CONTENT_MIN_FREE_BYTES) {
    return `该卷只剩 ${fmtSize(v.free_bytes)}，少于 ${fmtSize(CONTENT_MIN_FREE_BYTES)}，不建议继续。`;
  }
  if (v.removable) return '这是可移除存储：拔出后程序读不到已有内容，插回后恢复。';
  return '';
});

/** 选中卷之后内容实际落在哪：core 会再拼一层程序 id。 */
const contentTargetHint = computed(() => {
  const p = installTarget.value;
  if (!p) return '';
  const base = installContentRoot.value;
  return base ? `${base}/${p.id}` : `内部：programs/${p.id}/content`;
});

/**
 * 单选组只保留**一个** Tab 停靠点：选中项 tabindex=0，其余 -1。
 * 原生单选组的标准键盘模型（Tab 进组、方向键换项），但 Naive 给每个 input 都写了
 * tabindex=0，不处理就会变成四个停靠点、把键盘用户卡在筛选条里。
 */
function syncRovingTabindex(): void {
  const inputs = filterWrap.value?.querySelectorAll<HTMLInputElement>('input[type="radio"]');
  inputs?.forEach((el) => {
    el.tabIndex = el.checked ? 0 : -1;
  });
}

async function loadVolumes(): Promise<void> {
  try {
    const { data } = await client.get(Endpoints.programs.volumes);
    volumes.value = (data.volumes ?? []) as typeof volumes.value;
  } catch {
    // 拿不到卷列表时退化成"只有内部默认"：选择器会显示空，安装仍然可用
    volumes.value = [];
  }
}

const modeHint = computed(() =>
  installMode.value === 'gateway'
    ? '程序仅监听本机地址 127.0.0.1，访问请求由 UFI-AXIS Core 转发并校验会话，相比直连模式增加一层访问控制。'
    : '程序监听 0.0.0.0，可通过“设备 IP:端口”直接访问；程序自身的登录验证仍是主要安全防护。'
);

const emptyTitle = computed(() => {
  if (searchQuery.value.trim()) return `没有匹配「${searchQuery.value.trim()}」的程序`;
  if (filter.value === 'running') return '暂无正在运行的程序';
  if (filter.value === 'installed') return '暂无已安装程序';
  if (filter.value === 'new') return '暂无可安装程序';
  return '暂无程序';
});

const emptyHint = computed(() => {
  if (searchQuery.value.trim()) return '请尝试输入名称、端口或 ID 的一部分。';
  if (filter.value === 'running') return '启动一个程序后，它会出现在这里。';
  if (filter.value === 'installed') return '请从下方未安装列表中选择程序进行安装。';
  if (filter.value === 'new') return '当前程序源中的程序均已安装。';
  return '请检查设备连接与更新源，或刷新列表。';
});

function matchesQuery(p: ProgramItem, q: string): boolean {
  if (!q) return true;
  return [p.name, p.id, p.summary, String(p.internal_port), String(p.external_port)]
    .join(' ')
    .toLowerCase()
    .includes(q);
}

function rankOf(p: ProgramItem): number {
  if (isStorageIssue(p) || p.phase === 'failed' || p.circuit_open) return 0;
  if (p.phase === 'starting' || p.phase === 'stopping') return 1;
  if (p.phase === 'running') return 2;
  return 3;
}

function sortByStatus(a: ProgramItem, b: ProgramItem): number {
  const d = rankOf(a) - rankOf(b);
  return d !== 0 ? d : a.name.localeCompare(b.name, 'zh');
}

function sortByName(a: ProgramItem, b: ProgramItem): number {
  return a.name.localeCompare(b.name, 'zh');
}

function sortNew(a: ProgramItem, b: ProgramItem): number {
  if (a.available !== b.available) return a.available ? -1 : 1;
  return a.name.localeCompare(b.name, 'zh');
}

/**
 * 「存储不可用」有两种，必须分开说：
 *  · 数据卷（data/conf）掉了 → storage_ready=false；
 *  · 内容卷（SD/U 盘上的 content/）掉了 → content_ready=false，数据卷还可能好好的。
 */
function isStorageIssue(p: ProgramItem): boolean {
  return p.installed && ((p.storage === 'external' && !p.storage_ready) || p.content_ready === false);
}

/** 卡片/抽屉上的说明：指出掉的是哪一块存储。 */
function storageIssueText(p: ProgramItem): string {
  if (p.storage === 'external' && !p.storage_ready) return '数据所在存储不可用（已拔出或未挂载）';
  if (p.content_ready === false) return '内容目录所在存储不可用（已拔出或未挂载）';
  return '存储不可用';
}

function isAttention(p: ProgramItem): boolean {
  return isStorageIssue(p) || p.phase === 'failed' || p.circuit_open;
}

function isLive(p: ProgramItem): boolean {
  return p.phase === 'running' || p.phase === 'starting' || p.phase === 'stopping';
}

function tileState(p: ProgramItem): string {
  if (!p.installed) return 'new';
  if (isAttention(p)) return 'err';
  if (p.phase === 'starting' || p.phase === 'stopping') return 'boot';
  if (p.phase === 'running') return 'run';
  return 'stop';
}

function dotClass(p: ProgramItem): string {
  const s = tileState(p);
  if (s === 'run') return 'run';
  if (s === 'boot') return 'boot';
  if (s === 'err') return 'err';
  return '';
}

function statusLine(p: ProgramItem): string {
  if (!p.installed) return p.available ? '未安装' : '暂无可用版本';
  if (isStorageIssue(p)) return storageIssueText(p);
  // circuit_open 与 phase 是两套状态：健康检查失败 stop 后 phase 是 installed，
  // 但 circuit 已打开。必须优先看 circuit，否则卡片只显示“已停止 · 上次异常”。
  if (p.circuit_open) return `已暂停自动重试（连续失败 ${p.consecutive_failures} 次）`;
  switch (p.phase) {
    case 'running': {
      const up = fmtDuration(p.uptime_ms);
      return up ? `运行中 · ${up}` : '运行中';
    }
    case 'starting': {
      const sec = Math.floor((p.uptime_ms || 0) / 1000);
      return sec > 0 ? `启动中 ${sec}s` : '启动中';
    }
    case 'stopping':
      return '停止中…';
    case 'failed':
      return '启动失败';
    default:
      return p.last_exit_code != null && p.last_exit_code !== 0 ? '已停止 · 上次异常' : '已停止';
  }
}

function statusNote(p: ProgramItem): string {
  if (!p.installed) return '';
  if (isStorageIssue(p)) return '重新插入存储设备后将自动恢复';
  if (p.circuit_open) return p.last_error || '连续失败后已暂停自动重试，请查看日志后手动重试。';
  if (p.phase === 'starting') return '首次启动需要初始化数据库，可能需要 1–2 分钟。';
  if (p.phase === 'failed') {
    return p.last_error || '请查看日志以确定原因。';
  }
  if (p.phase !== 'running' && p.last_exit_code != null && p.last_exit_code !== 0) {
    return p.last_error || `上次退出码 ${p.last_exit_code}`;
  }
  return '';
}

function noteIsError(p: ProgramItem): boolean {
  return p.installed && !isStorageIssue(p) && (p.phase === 'failed' || p.circuit_open);
}

function updateAvailable(p: ProgramItem): boolean {
  return (
    p.installed && !!p.installed_version && !!p.latest_version && isNewerVersion(p.latest_version, p.installed_version)
  );
}

function startLabel(p: ProgramItem): string {
  return p.circuit_open || p.phase === 'failed' ? '重试' : '启动';
}

function versionLabel(p: ProgramItem): string {
  return vLabel(p.installed ? p.installed_version : p.latest_version);
}

function memoryLabel(p: ProgramItem): string {
  if (p.phase !== 'running' && p.phase !== 'starting') return '—';
  return p.rss_mb > 0 ? `${p.rss_mb} MB` : '—';
}

function sourceLabel(p: ProgramItem): string {
  if (!p.installed) return '程序源';
  if (p.source === 'upload') return '本地上传';
  if (p.source === 'remote') return '发布源下载';
  return p.source || '—';
}

function storageLabel(p: ProgramItem): string {
  return p.storage === 'external' ? '外部存储' : '内部存储';
}

function accessLabel(p: ProgramItem): string {
  return p.access_mode === 'gateway' ? '经 Core 转发' : '直连端口';
}

function attentionLabel(p: ProgramItem): string {
  if (isStorageIssue(p)) return `${p.name} ${storageIssueText(p)}`;
  if (p.circuit_open) return `${p.name} 已暂停自动重试`;
  return `${p.name} 启动失败`;
}

function initials(name: string): string {
  const clean = name.replace(/\s+/g, '');
  if (!clean) return '??';
  if (/[\u4e00-\u9fa5]/.test(clean)) return clean.slice(0, 2);
  const words = name.split(/\s+/).filter(Boolean);
  if (words.length >= 2) return ((words[0]?.[0] ?? '') + (words[1]?.[0] ?? '')).toUpperCase();
  return clean.slice(0, 2).toUpperCase();
}

function fmtSize(bytes: number): string {
  if (!bytes || bytes <= 0) return '0B';
  if (bytes >= 1024 ** 3) return `${(bytes / 1024 ** 3).toFixed(1)}GB`;
  if (bytes >= 1024 ** 2) return `${(bytes / 1024 ** 2).toFixed(1)}MB`;
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(0)}KB`;
  return `${bytes}B`;
}

function fmtDuration(ms: number): string {
  if (!ms || ms <= 0) return '';
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s}s`;
  if (s < 3600) return `${Math.floor(s / 60)} 分钟`;
  if (s < 86400) return `${Math.floor(s / 3600)} 小时`;
  return `${Math.floor(s / 86400)} 天`;
}

function percentOf(t: DownloadTask): number {
  if (!t.total_bytes || t.total_bytes <= 0) return 0;
  return Math.min(100, Math.round((t.downloaded_bytes / t.total_bytes) * 100));
}

function toggleSort(): void {
  sortMode.value = sortMode.value === 'status' ? 'name' : 'status';
}

function resetFilters(): void {
  searchQuery.value = '';
  filter.value = 'all';
}

function applySecret(programId: string, raw: string): void {
  secretId.value = programId;
  // dufs 的契约是 user:pass；OpenList 只返回 password，账号固定 admin。
  // 把两者拆开显示，避免用户把 "ufi:..." 整串粘进密码框。
  if (programId === 'dufs' && raw.includes(':')) {
    const at = raw.indexOf(':');
    secretAccount.value = raw.slice(0, at);
    secretPassword.value = raw.slice(at + 1);
  } else if (programId === 'openlist') {
    secretAccount.value = 'admin';
    secretPassword.value = raw;
  } else {
    secretAccount.value = '';
    secretPassword.value = raw;
  }
}

async function revealSecret(p: ProgramItem, auto = false): Promise<void> {
  if (!p.has_secret || secretLoading.value) return;
  secretLoading.value = true;
  try {
    // 列表只给 has_secret；值由 secret 端点消费，取走后 Core 端不再保留（OpenList）。
    const { data } = await client.post(Endpoints.programs.secret(p.id));
    secretName.value = p.name;
    secretTarget.value = p;
    secretAuto.value = auto;
    applySecret(p.id, String(data.secret ?? ''));
    secretOpen.value = true;
    await load(true);
  } catch (e) {
    message.error(errText(e, '读取初始口令失败'));
  } finally {
    secretLoading.value = false;
  }
}

/** 列表轮询完成后调用：只在本次安装完成的那个程序上自动弹一次。 */
async function maybeAutoRevealSecret(): Promise<void> {
  const id = autoRevealId.value;
  if (!id) return;
  const p = programs.value.find((x) => x.id === id);
  if (!p) return;
  if (p.has_secret) {
    autoRevealId.value = null;
    await revealSecret(p, true);
  } else if (p.installed) {
    autoRevealId.value = null;
  }
}

function openResetSecret(p: ProgramItem): void {
  // 从凭据弹窗进入时先收起它，避免两个 dialog 的 scrim 叠在一起。
  secretOpen.value = false;
  resetTarget.value = p;
  resetPassword.value = '';
  resetOpen.value = true;
}

async function submitResetSecret(): Promise<void> {
  const p = resetTarget.value;
  if (!p || resetBusy.value) return;
  const custom = resetPassword.value.trim();
  if (custom && !/^[A-Za-z0-9._-]{8,64}$/.test(custom)) {
    message.warning('自定义口令需为 8–64 位字母、数字或 . _ -');
    return;
  }
  resetBusy.value = true;
  try {
    const { data } = await client.post(Endpoints.programs.resetSecret(p.id), custom ? { password: custom } : null);
    resetOpen.value = false;
    secretName.value = p.name;
    secretTarget.value = p;
    secretAuto.value = false;
    applySecret(p.id, String(data.secret ?? ''));
    secretOpen.value = true;
    await load(true);
  } catch (e) {
    message.error(errText(e, '重置初始口令失败'));
  } finally {
    resetBusy.value = false;
  }
}

async function copyText(text: string, success = '已复制'): Promise<void> {
  if (!text) return;
  try {
    await navigator.clipboard.writeText(text);
    message.success(success);
    return;
  } catch {
    // HTTP 直连时 Clipboard API 不可用，退回 execCommand
  }
  const ta = document.createElement('textarea');
  ta.value = text;
  ta.style.position = 'fixed';
  ta.style.opacity = '0';
  document.body.appendChild(ta);
  ta.select();
  let ok = false;
  try {
    ok = document.execCommand('copy');
  } catch {
    ok = false;
  }
  ta.remove();
  if (ok) message.success(success);
  else message.warning('复制失败，请手动选择内容');
}

function focusAttention(): void {
  const first = attentionPrograms.value[0];
  if (!first) return;
  filter.value = 'all';
  searchQuery.value = '';
  openDetail(first);
  void nextTick(() => {
    document.querySelector(`.pg-tile[data-id="${first.id}"]`)?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  });
}

function openDetail(p: ProgramItem): void {
  detailId.value = p.id;
  detailOpen.value = true;
  detailLog.value = '';
  if (p.installed) {
    // 列表接口刻意不算目录大小（10s 轮询不能让几万文件每次都 stat 一遍）。
    // 抽屉打开时再拉一次 detail=true，拿到真实的 used_bytes / log_bytes / cache_bytes。
    void loadDetailData(p.id);
    void loadDetailLogs(p);
  }
}

async function loadDetailData(id: string): Promise<void> {
  try {
    const { data } = await client.get(Endpoints.programs.detail(id));
    if (detailId.value !== id) return;
    const target = programs.value.find((p) => p.id === id);
    if (target && data) Object.assign(target, data);
  } catch {
    // 详情统计失败保留列表里的值；不要影响抽屉和日志。
  }
}

async function loadDetailLogs(p: ProgramItem): Promise<void> {
  const targetId = p.id;
  detailLogLoading.value = true;
  try {
    const { data } = await client.get(Endpoints.programs.logs(targetId), {
      params: { tail: 12 },
    });
    if (detailId.value !== targetId) return;
    const parts = [data?.stderr, data?.stdout].filter(Boolean);
    detailLog.value = parts.length ? parts.join('\n\n') : '（暂无日志）';
  } catch (e) {
    if (detailId.value === targetId) detailLog.value = errText(e, '读取日志失败');
  } finally {
    if (detailId.value === targetId) detailLogLoading.value = false;
  }
}

function menuOf(p: ProgramItem): DropdownOption[] {
  const items: DropdownOption[] = [{ label: '查看详情', key: 'detail' }];
  if (!p.installed) {
    if (p.available) {
      items.push({
        label: !online.value
          ? '安装（需要联网）'
          : isActive(p)
            ? '安装（先停止该程序）'
            : `安装 ${fmtSize(p.download_size)}`,
        key: 'install',
        disabled: !online.value || !!activeTask.value || isActive(p),
      });
    }
    return items;
  }
  items.push({ label: '设置', key: 'settings' });
  items.push({ label: '查看日志', key: 'logs' });
  if (!isLive(p)) {
    items.push({
      label: '访问方式',
      key: 'mode',
      children: [
        { label: `直连端口${p.access_mode === 'direct' ? ' ✓' : ''}`, key: 'mode:direct' },
        { label: `经 Core 转发${p.access_mode === 'gateway' ? ' ✓' : ''}`, key: 'mode:gateway' },
      ],
    });
    items.push({
      label: '重启',
      key: 'restart',
      disabled: !!activeTask.value,
    });
  } else if (p.phase !== 'stopping') {
    items.push({ label: '停止', key: 'stop' });
  }
  if (updateAvailable(p) && !isStorageIssue(p)) {
    items.push({
      label: isActive(p) ? '更新（先停止该程序）' : `更新到 ${vLabel(p.latest_version)}`,
      key: 'update',
      disabled: isActive(p) || !!activeTask.value || !online.value,
    });
  }
  items.push({ type: 'divider', key: 'd1' });
  items.push({
    label: isActive(p) ? '卸载（先停止该程序）' : '卸载',
    key: 'uninstall',
    disabled: isActive(p) || !!activeTask.value,
  });
  return items;
}

async function onMenu(key: string, p: ProgramItem): Promise<void> {
  if (key === 'detail') {
    openDetail(p);
    return;
  }
  if (key === 'settings') {
    openSettings(p);
    return;
  }
  if (key === 'logs') {
    await loadLogs(p);
    return;
  }
  if (key === 'restart') {
    await restart(p);
    return;
  }
  if (key === 'stop') {
    await stop(p);
    return;
  }
  if (key === 'install') {
    openInstall(p, 'install');
    return;
  }
  if (key === 'update') {
    openInstall(p, 'update');
    return;
  }
  if (key === 'uninstall') {
    uninstall(p);
    return;
  }
  if (key.startsWith('mode:')) {
    await setAccessMode(p, key.slice(5));
  }
}

/** 运行中点击被锁定的配置区域：明确告诉用户要先停止程序（2026-11-10）。 */
function warnSettingsRunning(): void {
  message.warning('程序正在运行，请先停止程序再修改访问方式与端口');
}

function openSettings(p: ProgramItem): void {
  settingsId.value = p.id;
  settingsForm.accessMode = p.access_mode === 'gateway' ? 'gateway' : 'direct';
  settingsForm.internalPort = p.internal_port > 0 ? p.internal_port : null;
  settingsForm.externalPort = p.external_port > 0 ? p.external_port : null;
  settingsForm.autostart = !!p.autostart;
  settingsOpen.value = true;
}

async function saveSettings(): Promise<void> {
  const p = settingsTarget.value;
  if (!p || settingsSaving.value) return;
  if (settingsForm.internalPort == null) {
    message.warning('请填写内部端口');
    return;
  }
  if (settingsForm.accessMode === 'gateway' && settingsForm.externalPort == null) {
    message.warning('经 Core 转发需要填写外部端口');
    return;
  }
  settingsSaving.value = true;
  try {
    await client.patch(Endpoints.programs.settings(p.id), {
      access_mode: settingsForm.accessMode,
      internal_port: settingsForm.internalPort,
      external_port: settingsForm.externalPort ?? 0,
      autostart: settingsForm.autostart,
    });
    settingsOpen.value = false;
    message.success('设置已保存，端口与访问方式下次启动生效');
    await load();
  } catch (e) {
    message.error(errText(e, '保存设置失败'));
  } finally {
    settingsSaving.value = false;
  }
}

function openInstall(p: ProgramItem, mode: 'install' | 'update' = 'install'): void {
  if (!online.value) {
    message.warning('安装或更新需要联网下载安装包');
    return;
  }
  if (activeTask.value) {
    message.warning('已有安装任务正在进行，请等待当前任务完成');
    return;
  }
  if (isActive(p)) {
    message.warning('该程序正在运行，请先停止后再安装或更新');
    return;
  }
  if (mode === 'update') {
    if (!updateAvailable(p) || isStorageIssue(p) || isActive(p)) return;
    installIsUpdate.value = true;
  } else {
    installIsUpdate.value = false;
  }
  installTarget.value = p;
  // 含凭据的程序只允许内部存储：选项已置灰，这里再兜一次（用户可能先选了外部再换目标）
  installContentRoot.value = '';
  if (p.content_enabled) void loadVolumes();
  installMode.value = p.access_mode || 'direct';
  riskAck.value = false;
  installOpen.value = true;
}

async function confirmInstall(): Promise<void> {
  const p = installTarget.value;
  if (!p) return;
  if (!riskAck.value) {
    message.warning('请先确认共用系统账号的风险');
    return;
  }
  if (!online.value) {
    message.warning('安装或更新需要联网下载安装包');
    return;
  }
  if (activeTask.value) {
    message.warning('已有安装任务在进行');
    return;
  }
  const updating = installIsUpdate.value;
  installOpen.value = false;
  try {
    // 数据位置固定内部存储（两端都已去掉该设置），这里不再传 storage
    const access = updating ? p.access_mode : installMode.value;
    await client.post(Endpoints.programs.install(p.id), null, {
      params: {
        access_mode: access,
        // 只有声明了内容目录的程序才带这个参数；空串 = 内部默认
        ...(p.content_enabled ? { content_root: installContentRoot.value } : {}),
      },
    });
    message.info(
      updating
        ? '已开始下载新版本，完成后会替换二进制并保留数据目录'
        : `已开始下载 ${fmtSize(p.download_size)}，进度见上方进度条`
    );
    pendingInstallId.value = p.id;
    if (!updating) autoRevealId.value = p.id;
    startPolling();
  } catch (e) {
    message.error(errText(e, updating ? '更新失败' : '安装失败'));
  }
}

async function start(p: ProgramItem): Promise<void> {
  if (activeTask.value) {
    message.warning('安装任务正在进行，当前无法启动');
    return;
  }
  if (isActive(p)) {
    message.warning('该程序正在运行，请先停止它');
    return;
  }
  try {
    // 首次初始化已前移到安装阶段；这里正常只需秒级。给足 120s 是为了旧安装/异常配置
    // 仍能走完 preflight，而不是在 15s 时误报“启动失败”。
    await client.post(Endpoints.programs.start(p.id), null, { timeout: 120_000 });
    message.info('正在启动。首次启动需要初始化数据库，可能需要 1–2 分钟。');
    startStartupPolling();
  } catch (e) {
    message.error(errText(e, '启动失败'));
  }
}

let startupTimer: number | null = null;

function startStartupPolling(): void {
  if (startupTimer !== null) return;
  const deadline = Date.now() + 6 * 60 * 1000;
  startupTimer = window.setInterval(async () => {
    await load();
    const pending = programs.value.some((x) => x.phase === 'starting' || x.phase === 'stopping');
    if (!pending || Date.now() > deadline) {
      window.clearInterval(startupTimer!);
      startupTimer = null;
    }
  }, 2000);
}

async function stop(p: ProgramItem): Promise<void> {
  try {
    await client.post(Endpoints.programs.stop(p.id), null, { timeout: 30_000 });
    message.success('已停止');
    await load();
  } catch (e) {
    message.error(errText(e, '停止失败'));
  }
}

async function restart(p: ProgramItem): Promise<void> {
  if (activeTask.value) {
    message.warning('安装任务正在进行，当前无法重启');
    return;
  }
  if (isActive(p)) {
    message.warning('该程序正在运行，请先停止它');
    return;
  }
  try {
    await client.post(Endpoints.programs.restart(p.id), null, { timeout: 120_000 });
    message.info('正在重启…');
    startStartupPolling();
  } catch (e) {
    message.error(errText(e, '重启失败'));
  }
}

async function open(p: ProgramItem): Promise<void> {
  // 必须在 await 之前同步开窗：任何网络往返之后 window.open 都会失去用户手势，
  // Chrome/Safari 都会拦截。先开空白页，拿到 URL 后再导航。
  const win = window.open('', '_blank');
  if (win) {
    try {
      win.opener = null;
    } catch {
      /* 跨域后不可写，忽略 */
    }
  }
  try {
    const { data } = await client.post(Endpoints.programs.open(p.id));
    const scheme = String(data.scheme || 'http');
    const url = `${scheme}://${location.hostname}:${data.port}${data.path || '/'}`;
    if (!win) {
      message.warning('浏览器拦截了新窗口，请允许弹窗后重试');
      return;
    }
    win.location.href = url;
  } catch (e) {
    win?.close();
    message.error(errText(e, '打开失败'));
  }
}

async function setAccessMode(p: ProgramItem, mode: string): Promise<void> {
  if (mode === p.access_mode) return;
  try {
    await client.post(Endpoints.programs.accessMode(p.id), null, {
      params: { mode },
    });
    message.success(mode === 'gateway' ? '已切换为经 Core 转发，下次启动生效' : '已切换为直连端口，下次启动生效');
    await load();
  } catch (e) {
    message.error(errText(e, '切换失败'));
  }
}

function uninstall(p: ProgramItem): void {
  if (activeTask.value) {
    message.warning('安装任务正在进行，当前无法卸载');
    return;
  }
  purgeLogs.value = false;
  dialog.warning({
    title: `卸载 ${p.name}`,
    content: () =>
      h('div', [
        h('p', { style: 'margin:0 0 10px' }, '将删除程序及其数据目录。重新安装时需要重新下载。'),
        h(
          NCheckbox,
          {
            checked: purgeLogs.value,
            'onUpdate:checked': (v: boolean) => {
              purgeLogs.value = v;
            },
          },
          { default: () => '同时删除日志（彻底卸载）' }
        ),
        h(
          'p',
          { style: 'margin:6px 0 0;font-size: var(--font-sm);opacity:.65' },
          '日志用于卸载后排障；删除后无法恢复。'
        ),
      ]),
    positiveText: '卸载',
    negativeText: '取消',
    onPositiveClick: async () => {
      const deleteLogs = purgeLogs.value;
      try {
        const { data } = await client.post(Endpoints.programs.uninstall(p.id), null, {
          params: { delete_logs: deleteLogs },
          timeout: 30_000,
        });
        if (data.residual?.length) {
          message.warning(`程序已删除，但有 ${data.residual.length} 个路径未能清理`);
        } else if (deleteLogs) {
          message.success('已彻底卸载（含日志）');
        } else {
          message.success('已卸载（日志保留）');
        }
        if (detailId.value === p.id) detailOpen.value = false;
        await load();
      } catch (e) {
        message.error(errText(e, '卸载失败'));
      }
    },
  });
}

async function loadLogs(p: ProgramItem): Promise<void> {
  logTarget.value = p;
  logText.value = '';
  logOpen.value = true;
  try {
    const { data } = await client.get(Endpoints.programs.logs(p.id), {
      params: { tail: 200 },
    });
    const parts = [
      data.stderr ? `--- stderr ---\n${data.stderr}` : '',
      data.stdout ? `--- stdout ---\n${data.stdout}` : '',
    ].filter(Boolean);
    logText.value = parts.join('\n\n') || '（暂无日志）';
  } catch (e) {
    logText.value = errText(e, '读取日志失败');
  }
}

let pollTimer: number | null = null;
let listTimer: number | null = null;
let loadSeq = 0;

function startPolling(): void {
  stopPolling();
  pollTimer = window.setInterval(async () => {
    await loadStatus();
    if (!activeTask.value) {
      stopPolling();
      await load();
      await maybeAutoRevealSecret();
    }
  }, 1000);
}

function stopPolling(): void {
  if (pollTimer !== null) {
    window.clearInterval(pollTimer);
    pollTimer = null;
  }
}

async function load(silent = false): Promise<boolean> {
  const seq = ++loadSeq;
  if (!silent) loading.value = true;
  try {
    const { data } = await client.get(Endpoints.programs.list);
    if (seq !== loadSeq) return false;
    const next = (data.programs ?? []) as ProgramItem[];
    const prev = new Map(programs.value.map((p) => [p.id, p]));
    const merged: ProgramItem[] = [];
    for (const n of next) {
      const old = prev.get(n.id);
      if (!old) {
        merged.push(n);
        continue;
      }
      // 就地合并，保留对象引用：卡片不重建，轮询期间不会反复卸载/挂载条件元素
      for (const k of Object.keys(n) as Array<keyof ProgramItem>) {
        if (old[k] !== n[k]) {
          (old as Record<string, unknown>)[k as string] = n[k];
        }
      }
      merged.push(old);
    }
    programs.value = merged;
    nativeExecOk.value = data.native_exec !== false;
    loadFailed.value = false;
    return true;
  } catch {
    if (!silent) {
      // 页内已经有 role=alert 的失败块与重试按钮：再弹一个 toast 只会盖住页头，
      // 而且 axios 的英文 "Network Error" 与整页中文不一致。
      loadFailed.value = true;
    }
    return false;
  } finally {
    if (!silent) loading.value = false;
  }
}

/** 手动刷新：成功时用 toast 反馈，不再把「刚刚更新」写进页头布局。 */
async function manualRefresh(): Promise<void> {
  if (await load()) message.success('列表已更新');
}

async function loadStatus(): Promise<void> {
  try {
    const { data } = await client.get(Endpoints.programs.status);
    const t = data as DownloadTask;
    const prev = activeTask.value;
    const pendingId = pendingInstallId.value;
    if (BUSY_PHASES.has(t.phase)) {
      activeTask.value = t;
      return;
    }
    // 安装失败以前会直接消失：这里把最后一条 failed/done 转成用户能看到的反馈。
    // pendingId 覆盖“提交后 1 秒内就失败、activeTask 还没建立”的情况。
    const targetId = prev?.id || pendingId;
    if (targetId && targetId === t.id) {
      const name = taskProgram.value?.name ?? programs.value.find((p) => p.id === t.id)?.name ?? t.id;
      if (t.phase === 'failed') {
        if (autoRevealId.value === t.id) autoRevealId.value = null;
        message.error(`安装失败（${name}）：${t.message || '未知原因'}`);
      } else if (t.phase === 'done') {
        message.success(`${name} 安装完成`);
      }
    }
    if (pendingId && (t.phase === 'failed' || t.phase === 'done' || t.phase === 'idle')) {
      pendingInstallId.value = null;
    }
    activeTask.value = null;
  } catch {
    // 状态接口失败不该打断页面：列表本身仍可用
  }
}

function errText(e: unknown, fallback: string): string {
  const err = e as {
    response?: { status?: number; data?: { error?: unknown; message?: unknown } | string };
    message?: string;
  };
  const res = err.response;
  if (!res) {
    // 没有 response = 请求没到设备（断网 / 超时 / 被中断）。axios 原文是英文 "Network Error"，
    // 直接上屏会与整页中文打架，也说不清下一步该做什么。
    const raw = err.message ?? '';
    if (/network\s*error/i.test(raw) || /timeout/i.test(raw)) {
      return '设备未响应（网络错误），请检查连接后重试';
    }
    return raw || fallback;
  }
  const body = res.data;
  let detail: string;
  if (typeof body === 'string') detail = body.slice(0, 300);
  else if (body && typeof body === 'object') {
    const m = body.message ?? body.error;
    detail = typeof m === 'string' ? m : JSON.stringify(body).slice(0, 300);
  } else detail = '';
  return `[HTTP ${res.status ?? '?'}] ${detail || fallback}`;
}

function onOnline(): void {
  online.value = true;
  void load(true);
}

function onOffline(): void {
  online.value = false;
}

function onVisibilityChange(): void {
  if (!document.hidden) void load(true);
}

onMounted(async () => {
  // 程序状态没有 WS 推送：低频静默刷新，崩溃/看护重启/外部客户端操作才能反映出来。
  listTimer = window.setInterval(() => {
    if (!document.hidden) void load(true);
  }, 10_000);
  online.value = navigator.onLine;
  window.addEventListener('online', onOnline);
  window.addEventListener('offline', onOffline);
  document.addEventListener('visibilitychange', onVisibilityChange);
  await loadStatus();
  await load();
  if (activeTask.value) startPolling();
  // 刷新页面时可能正有程序在 starting/stopping，必须继续轮询，否则会永远停在那一帧。
  if (programs.value.some((p) => p.phase === 'starting' || p.phase === 'stopping')) {
    startStartupPolling();
  }
  await nextTick();
  syncRovingTabindex();
});

// Tab 不是每次都会触发重渲染（方向键只改 checked），所以两条都要跟：
// 筛选值变化后、以及键盘移动焦点后，把 tabindex 重新对齐到当前选中项。
watch(filter, () => void nextTick(syncRovingTabindex));

onUnmounted(() => {
  stopPolling();
  if (startupTimer !== null) {
    window.clearInterval(startupTimer);
    startupTimer = null;
  }
  if (listTimer !== null) {
    window.clearInterval(listTimer);
    listTimer = null;
  }
  window.removeEventListener('online', onOnline);
  window.removeEventListener('offline', onOffline);
  document.removeEventListener('visibilitychange', onVisibilityChange);
});

watch(detailOpen, (open) => {
  if (!open) detailId.value = null;
});

watch(detailProgram, (p) => {
  if (!p && detailOpen.value) detailOpen.value = false;
});
</script>

<style scoped>
.programs-view {
  /* ui-baseline-allow: 程序页主/警示/危险色按 4.5:1 单独压深，全站主题令牌不动 */
  --pg-warn-ink: #8a5a00;
  --pg-danger-ink: #b4233f;
  /* 主按钮底色：公共 --accent-color(#18a058) + 白字只有 3.38:1，14px 正文不达 4.5:1。
     这里只压深本页的主按钮（#0f7a43 = 5.41:1），全站主题不动。 */
  --pg-primary: #0f7a43;
  --pg-primary-hover: #12804a;
  --pg-primary-active: #0d6b3b;
  /* 文字用的主色（选中筛选标签等）：浅底压深绿，深底要换亮绿 */
  --pg-accent-ink: #0f7a43;
  display: flex;
  flex-direction: column;
  container-type: inline-size;
}
.dark .programs-view {
  /* ui-baseline-allow: 暗色档同一组色的对应取值，白/黑字对比按 4.9:1 校准 */
  --pg-warn-ink: #f0a020;
  --pg-danger-ink: #ff8a9a;
  /* 深色档主按钮保持白字：底色仍要用深绿（#12804a 上白字 4.99:1），
     不能换成亮绿 —— Naive 暗色主按钮的 ink 是白色，亮绿+白字只有 1.9:1。 */
  --pg-primary: #12804a;
  --pg-primary-hover: #159257;
  --pg-primary-active: #0d6b3b;
  --pg-accent-ink: #63e2b7;
}
/* n-drawer 会 teleport 到 body，页面上的局部变量传不过去：
   错误/警告色在抽屉里会退化成正文色（实测 var(--pg-danger-ink) 解析为 rgb(51,54,57)）。
   这里把同一组值挂到抽屉根节点上，抽屉内的状态文字、note、主按钮才拿得到。 */
:global(.programs-drawer) {
  /* ui-baseline-allow: 抽屉 teleport 到 body，页面局部变量传不过去，必须复制同一组值 */
  --pg-warn-ink: #8a5a00;
  --pg-danger-ink: #b4233f;
  --pg-primary: #0f7a43;
  --pg-primary-hover: #12804a;
  --pg-primary-active: #0d6b3b;
}
:global(.dark .programs-drawer) {
  /* ui-baseline-allow: 暗色档抽屉同上，配合 Naive 暗色主按钮的黑字 */
  --pg-warn-ink: #f0a020;
  --pg-danger-ink: #ff8a9a;
  /* 深色档主色：Naive 在暗色下把按钮文字设为黑色，压深绿会变成黑压深绿（3.89:1）。
     换成亮一档的绿，黑字对比反而更高。 */
  --pg-primary: #12804a;
  --pg-primary-hover: #159257;
  --pg-primary-active: #0d6b3b;
  --pg-accent-ink: #63e2b7;
}
/* 抽屉是 teleport 出去的，`.programs-view :deep(...)` 到不了；主按钮需要单独一份 */
:global(.programs-drawer .n-button--primary-type:not(.n-button--text-type)) {
  background-color: var(--pg-primary);
  border-color: var(--pg-primary);
}
:global(.programs-drawer .n-button--primary-type:not(.n-button--text-type):hover) {
  background-color: var(--pg-primary-hover);
  border-color: var(--pg-primary-hover);
}
:global(.programs-drawer .n-button--primary-type:not(.n-button--text-type):active) {
  background-color: var(--pg-primary-active);
  border-color: var(--pg-primary-active);
}

/* ── 页头 ── */
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-6);
  margin-bottom: var(--space-3);
}
.title-row {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
}
.page-title h1 {
  margin: 0;
  font-size: var(--font-xl);
  font-weight: 600;
  letter-spacing: -0.02em;
  line-height: 1.2;
}
.count-pill {
  display: inline-flex;
  align-items: center;
  height: 18px;
  padding: 0 7px;
  border-radius: var(--radius-pill);
  background: var(--cat-neutral-bg);
  color: var(--cat-neutral-fg);
  font-size: var(--font-xs);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.tools {
  display: flex;
  align-items: center;
  flex: none;
}
.tools-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
.search-input {
  width: 248px;
  max-width: 360px;
  flex: none;
}
.mobile-sort-btn {
  display: none;
  flex: none;
}
/* Naive 把 --n-color 写成元素行内变量，变量覆盖赢不过行内样式，只能直接压背景/边框。
   :not(.n-button--text-type) 排除文字按钮，避免把「查看完整日志」这类也染成实心。 */
.programs-view :deep(.n-button--primary-type:not(.n-button--text-type)) {
  background-color: var(--pg-primary);
  border-color: var(--pg-primary);
}
.programs-view :deep(.n-button--primary-type:not(.n-button--text-type):hover) {
  background-color: var(--pg-primary-hover);
  border-color: var(--pg-primary-hover);
}
.programs-view :deep(.n-button--primary-type:not(.n-button--text-type):active) {
  background-color: var(--pg-primary-active);
  border-color: var(--pg-primary-active);
}
/* 搜索占位符：Naive 默认 #c2c2c2 在白底只有 1.78:1，占位文字也是要读的 */
.search-input :deep(.n-input__placeholder) {
  color: var(--text-secondary);
}

/* ── 横幅 ── */
.banner {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
  margin-bottom: var(--space-2);
  padding: 10px 12px;
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  font-size: var(--font-sm);
  line-height: 1.45;
}
.banner-icon {
  margin-top: 1px;
  flex: none;
}
.banner-copy {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  gap: 1px;
}
.banner-copy strong {
  font-weight: 600;
}
.banner-copy span {
  color: var(--text-secondary);
}
.banner-copy code {
  padding: 1px 5px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
  background: var(--code-bg);
  color: var(--text-primary);
  font-family: var(--mono);
  font-size: var(--font-xs);
  letter-spacing: 0.4px;
}
.banner-attn {
  border-color: var(--error-light);
  background: var(--error-light);
  color: var(--pg-danger-ink);
}
.banner-attn .banner-copy strong {
  color: var(--pg-danger-ink);
}
.banner-secret {
  border-color: var(--success-light);
  background: var(--success-light);
  color: var(--cat-green-fg);
}
.banner-secret .banner-copy strong {
  color: var(--cat-green-fg);
}
.banner-warn {
  border-color: var(--warning-light);
  background: var(--warning-light);
  color: var(--pg-warn-ink);
}
.banner-warn .banner-copy strong {
  color: var(--pg-warn-ink);
}
.banner-warn .banner-copy span {
  color: var(--text-secondary);
}
.slot-note {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin: 0 0 var(--space-2);
  padding: 8px 10px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  background: var(--card-bg);
  color: var(--text-secondary);
  font-size: var(--font-sm);
  line-height: 1.5;
}
.slot-note strong {
  color: var(--text-primary);
}
.banner .n-button {
  flex: none;
  align-self: center;
}

/* ── 下载任务 ── */
.task {
  display: flex;
  flex-direction: column;
  gap: 7px;
  margin-bottom: var(--space-2);
  padding: 10px 12px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  background: var(--card-bg);
}
.task-head {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
}
.task-title {
  font-size: var(--font-sm);
  font-weight: 600;
}
.task-pct {
  margin-left: auto;
  color: var(--text-secondary);
  font-size: var(--font-sm);
}
.task-foot {
  display: flex;
  gap: var(--space-2);
  color: var(--text-secondary);
  font-size: var(--font-sm);
  font-variant-numeric: tabular-nums;
}
.task-foot span:last-child {
  margin-left: auto;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ── 筛选 ── */
.filter-card {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin: var(--space-2) 0 var(--space-3);
  padding: 10px 16px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  background: var(--card-bg);
}
.filter-wrap {
  display: flex;
  flex: 1 1 auto;
  min-width: 0;
}
.filter-radio {
  position: relative;
  display: flex;
  flex: 1 1 auto;
  min-width: 0;
  flex-wrap: nowrap;
  gap: 0;
  padding: 2px;
  border-radius: var(--radius-sm);
  background: var(--page-bg);
}

/* 白色活动胶囊独立成层，位置由 filterIndex 的 translateX 驱动。
   这样既能保留 n-radio-group 的原生键盘语义，又有终端 segment 的滑动动画。 */
.filter-pill {
  position: absolute;
  z-index: 0;
  top: 2px;
  bottom: 2px;
  left: 2px;
  width: calc((100% - 4px) / var(--filter-count, 4));
  border-radius: calc(var(--radius-sm) - 2px);
  background: var(--card-bg);
  box-shadow: 0 1px 2px var(--shadow-color-soft);
  pointer-events: none;
  transition: transform 220ms cubic-bezier(0.4, 0, 0.2, 1);
  will-change: transform;
}

.filter-radio :deep(.n-radio-button) {
  position: relative;
  z-index: 1;
  display: flex;
  flex: 1 1 0;
  align-items: center;
  justify-content: center;
  border-radius: calc(var(--radius-sm) - 2px);
  background: transparent !important;
  color: var(--text-primary);
  transition: color 160ms ease;
}

.filter-radio :deep(.n-radio__label) {
  width: 100%;
  text-align: center;
}

/* 终端页的 segment tab 是「浅灰轨道 + 白色活动胶囊」；单选组把每个 label
   的边框/分隔线去掉，选中态由 filter-pill 表达，两端才长得一样。 */
.filter-radio :deep(.n-radio-button),
.filter-radio :deep(.n-radio-input) {
  border: 0 !important;
  outline: 0 !important;
}

.filter-radio :deep(.n-radio-button__state-border),
.filter-radio :deep(.n-radio-group__splitor) {
  display: none !important;
}

.filter-radio :deep(.n-radio-button--checked) {
  background: transparent !important;
  color: var(--text-primary);
  box-shadow: none !important;
}

/* 上面把 Naive 的边框/焦点描边一起清掉了；键盘焦点要单独补回来，
   否则看起来和未选中完全一样。 */
.filter-radio :deep(.n-radio-button:has(.n-radio-input:focus-visible)) {
  box-shadow:
    0 0 0 2px var(--card-bg),
    0 0 0 4px var(--accent-color);
}

.filter-radio :deep(.n-radio-button--checked .n-radio__label) {
  color: var(--text-primary);
  font-weight: 500;
}

/* 桌面端对齐终端 segment 的几何：轨道 39px、活动胶囊 33px。
   移动端仍沿用 pointer: coarse 的 44px 触控高度。 */
@media (min-width: 769px) {
  .filter-radio {
    height: 39px;
    padding: 3px;
  }

  .filter-radio .filter-pill {
    top: 3px;
    bottom: 3px;
    left: 3px;
    width: calc((100% - 6px) / var(--filter-count, 4));
  }

  .filter-radio :deep(.n-radio-button) {
    height: 33px;
  }

  .filter-radio :deep(.n-radio__label) {
    height: 33px;
    line-height: 33px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .filter-pill {
    transition: none;
  }
}

.sort-btn {
  flex: none;
}

/* ── 分组与网格 ── */
.section + .section {
  margin-top: var(--space-5);
}
.section-head {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
  margin-bottom: var(--space-2);
  padding: 0 2px;
}
.section-head h2 {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  /* 分组标题要比卡名（14px）大或至少同级，之前 13px 让「已安装」看起来像卡片副标题 */
  font-size: var(--font-md);
  font-weight: 600;
}
.section-head .count {
  display: inline-flex;
  align-items: center;
  height: 18px;
  padding: 0 7px;
  border-radius: var(--radius-pill);
  background: var(--cat-neutral-bg);
  color: var(--cat-neutral-fg);
  font-size: var(--font-xs);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.tile-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--space-3);
  align-items: stretch;
}
@container (max-width: 1000px) {
  .tile-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
@container (max-width: 720px) {
  .tile-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
  }
}
@container (max-width: 480px) {
  .tile-grid {
    grid-template-columns: 1fr;
  }
}

.skeleton-grid {
  align-items: stretch;
}
.pg-skeleton {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-height: 210px;
  padding: 13px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  background: var(--card-bg);
}
.pg-skeleton-top {
  display: flex;
  align-items: center;
  gap: 10px;
}
.pg-skeleton-lines {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  gap: 6px;
}
.pg-skeleton-metrics {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  margin-top: auto;
}
.load-error p {
  margin: 0;
  max-width: 42ch;
}
.pg-tile {
  position: relative;
  display: flex;
  flex-direction: column;
  min-height: 210px;
  padding: 13px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  background: var(--card-bg);
  transition:
    border-color 0.15s ease,
    background 0.15s ease,
    box-shadow 0.15s ease;
}
.pg-tile:hover {
  box-shadow: 0 10px 28px -24px var(--shadow-color-strong);
}
.pg-tile.tone-err {
  background: var(--error-light);
}
.pg-tile.is-new .glyph {
  border-style: dashed;
  background: transparent;
  color: var(--text-secondary);
}
.pg-tile.flash {
  animation: flash 1.2s ease;
}
@keyframes flash {
  0%,
  100% {
    box-shadow: 0 0 0 0 transparent;
  }
  25% {
    box-shadow: 0 0 0 3px var(--accent-color-light);
  }
}
.pg-open {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
  min-width: 0;
  padding: 0;
  border: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  color: inherit;
  text-align: left;
  font: inherit;
  cursor: pointer;
}
.pg-open:focus-visible {
  outline: 2px solid var(--accent-color);
  outline-offset: 2px;
}
.pg-open:hover .name {
  text-decoration: underline;
  text-decoration-color: var(--border-subtle);
  text-underline-offset: 3px;
}
.pg-top {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  min-width: 0;
}
.glyph {
  position: relative;
  display: grid;
  place-items: center;
  flex: none;
  width: 42px;
  height: 42px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  background: var(--surface-elevated);
  color: var(--text-secondary);
  font-family: var(--mono);
  font-size: var(--font-xs);
  font-weight: 600;
  letter-spacing: -0.02em;
}
.led {
  position: absolute;
  right: -3px;
  bottom: -3px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--text-muted);
  box-shadow: 0 0 0 2px var(--card-bg);
}
.led.run,
.tone-run .led {
  background: var(--success);
}
.led.boot,
.tone-boot .led {
  background: var(--warning);
  animation: blink 1.3s ease-in-out infinite;
}
.led.err,
.tone-err .led {
  background: var(--error);
}
@keyframes blink {
  50% {
    opacity: 0.35;
  }
}
.pg-ident {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}
.pg-ident .name {
  overflow: hidden;
  font-size: var(--font-md);
  font-weight: 600;
  letter-spacing: -0.01em;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.pg-ident .ver {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
  color: var(--text-secondary);
  font-family: var(--mono);
  /* 11px 的等宽版本号在 390 视口下已经难读，提到 12px；chip 仍是 11px（短标签） */
  font-size: var(--font-sm);
}
.chip {
  display: inline-flex;
  align-items: center;
  height: 17px;
  padding: 0 6px;
  border-radius: 4px;
  background: var(--cat-neutral-bg);
  color: var(--cat-neutral-fg);
  font-size: var(--font-xs);
  font-weight: 600;
  letter-spacing: 0.01em;
  white-space: nowrap;
}
.chip-update {
  background: var(--cat-green-bg);
  color: var(--cat-green-fg);
}
.pg-status {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}
.status-text {
  font-weight: 600;
}
.tone-run .status-text {
  color: var(--cat-green-fg);
}
.tone-err .status-text {
  color: var(--pg-danger-ink);
}
/* 「启动中」是被安装/启动流程短暂阻塞的状态，不能和「已停止」同一个灰；
   「停止中」不是告警，仍用正文灰。 */
.tone-boot .status-text {
  color: var(--pg-warn-ink);
}
.tone-stop .status-text {
  color: var(--text-secondary);
}
.tone-stop .status-text,
.tone-new .status-text {
  color: var(--text-secondary);
}
.pg-status .status-text {
  font-size: var(--font-sm);
}
.pg-note {
  overflow: hidden;
  color: var(--text-secondary);
  font-size: var(--font-sm);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.pg-note.error {
  color: var(--pg-danger-ink);
}
.pg-summary {
  display: -webkit-box;
  overflow: hidden;
  color: var(--text-secondary);
  font-size: var(--font-sm);
  line-height: 1.5;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.pg-metrics {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px 12px;
  margin-top: 4px;
}
.pg-metric {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 1px;
}
.pg-metric .k {
  color: var(--text-secondary);
  font-size: var(--font-sm);
}
.pg-metric .v {
  overflow: hidden;
  color: var(--text-primary);
  font-size: var(--font-base);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.pg-metric .v.mono {
  font-family: var(--mono);
}
.pg-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: auto;
  padding-top: 10px;
  border-top: 1px solid var(--border-subtle);
}
.pg-actions :deep(.n-button) {
  height: 32px;
}
.pg-actions :deep(.pg-action-main) {
  flex: 0 0 auto;
  min-width: 84px;
}
.pg-actions .pg-spacer {
  flex: 1;
}
.pg-actions :deep(.more-btn) {
  width: 28px;
  min-width: 28px;
  height: 28px;
  padding: 0;
}
.mono {
  font-family: var(--mono);
  font-variant-numeric: tabular-nums;
}

/* ── 详情抽屉 ── */
.detail-sheet {
  display: flex;
  flex-direction: column;
  min-height: 100%;
}
.detail-head {
  position: sticky;
  top: 0;
  z-index: 1;
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 16px 18px;
  border-bottom: 1px solid var(--border-subtle);
  background: var(--card-bg);
}
.detail-head .glyph {
  width: 44px;
  height: 44px;
  font-size: var(--font-sm);
}
.detail-title {
  flex: 1;
  min-width: 0;
}
.detail-title h2 {
  margin: 0;
  font-size: var(--font-lg);
  font-weight: 600;
  letter-spacing: -0.02em;
  line-height: 1.25;
}
.detail-sub {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px 6px;
  margin-top: 2px;
  color: var(--text-secondary);
  font-size: var(--font-sm);
}
.detail-body {
  padding: 16px 18px 24px;
}
.detail-status {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-bottom: 12px;
}
.detail-status .status-text {
  font-size: var(--font-base);
}
.detail-note {
  color: var(--text-secondary);
  font-size: var(--font-sm);
  line-height: 1.5;
}
.detail-note.error {
  color: var(--pg-danger-ink);
}
.detail-main-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 14px;
}
.detail-main-actions :deep(.n-button) {
  height: 32px;
}
.detail-main-actions :deep(.pg-action-main) {
  flex: 1;
}
.detail-metrics {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(130px, 1fr));
  gap: 1px;
  overflow: hidden;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
  background: var(--border-subtle);
}
.detail-metric {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
  padding: 9px 11px;
  background: var(--card-bg);
}
.detail-metric .k {
  color: var(--text-secondary);
  font-size: var(--font-sm);
}
.detail-metric .v {
  overflow: hidden;
  color: var(--text-primary);
  font-size: var(--font-base);
  font-weight: 600;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.detail-summary {
  max-width: 70ch;
  margin-top: 14px;
  color: var(--text-secondary);
  font-size: var(--font-sm);
  line-height: 1.6;
}
.detail-meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px 18px;
  margin-top: 12px;
}
.detail-meta > div {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 1px;
}
.detail-meta dt {
  color: var(--text-secondary);
  font-size: var(--font-sm);
}
.detail-meta dd {
  overflow: hidden;
  color: var(--text-secondary);
  font-size: var(--font-sm);
  text-overflow: ellipsis;
}
.note {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  margin-top: 9px;
  padding: 8px 9px;
  border-radius: var(--radius-sm);
  font-size: var(--font-sm);
  line-height: 1.5;
}
.note .n-icon {
  margin-top: 1px;
  flex: none;
}
.note-error {
  background: var(--error-light);
  color: var(--pg-danger-ink);
}
.note-warn {
  background: var(--warning-light);
  color: var(--text-secondary);
}
.note-warn .n-icon {
  color: var(--pg-warn-ink);
}
.note-info {
  background: var(--accent-color-light);
  color: var(--text-secondary);
}
.note-info .n-icon {
  color: var(--accent-color);
}
.detail-logs {
  margin-top: 14px;
  overflow: hidden;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
}
.detail-logs-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 10px;
  border-bottom: 1px solid var(--border-subtle);
  color: var(--text-secondary);
  font-size: var(--font-xs);
}
.detail-logs pre {
  max-height: 170px;
  margin: 0;
  overflow: auto;
  padding: 10px;
  background: var(--code-bg);
  color: var(--text-secondary);
  font-family: var(--mono);
  font-size: var(--font-xs);
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}
.detail-actions-hint {
  margin: 14px 0 -6px;
  color: var(--text-secondary);
  font-size: var(--font-sm);
  line-height: 1.5;
}
.detail-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 14px;
}
.detail-actions :deep(.n-button) {
  height: 30px;
}

/* ── 空状态 ── */
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 48px 20px;
  border: 1px dashed var(--border-subtle);
  border-radius: var(--radius-md);
  color: var(--text-secondary);
  text-align: center;
}
.empty h3 {
  color: var(--text-primary);
  font-size: var(--font-base);
  font-weight: 600;
}
.empty p {
  max-width: 40ch;
  font-size: var(--font-sm);
}

/* ── 安装 / 日志弹窗 ── */
.port-input {
  width: 160px;
}
/* 运行中锁定的配置行：透明层接住点击，弹出"先停止程序"的 toast。
   禁用控件本身在多数浏览器里不派发 click，所以不能只给 .mrow 绑 @click。 */
.lock-area {
  position: relative;
}
.lock-hit {
  position: absolute;
  inset: 0;
  z-index: 1;
  cursor: not-allowed;
}
.modal-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.mrow {
  display: flex;
  align-items: center;
  gap: 12px;
}
.mk {
  width: 68px;
  flex: none;
  color: var(--text-secondary);
  font-size: var(--font-sm);
}
.mv {
  font-size: var(--font-base);
}
/* 320px 会把「1004GB」截成「1004…」，而总容量正是选卷依据 */
.content-select {
  width: 100%;
  max-width: min(440px, 100%);
  min-width: 0;
}
/* 选中值不能顶出控件边框：Naive 的 selection label/input 需要自己裁一次 */
.content-select :deep(.n-base-selection-label),
.content-select :deep(.n-base-selection-input) {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 下拉菜单 teleport 到 body，scoped 的 .vol-* 到不了，必须用 :global */
:global(.vol-opt) {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
  padding: 2px 0;
}
:global(.vol-name) {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--font-base);
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
:global(.vol-meta) {
  font-size: var(--font-sm);
  color: var(--text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
:global(.vol-badge) {
  flex: none;
  padding: 0 6px;
  border-radius: 4px;
  background: var(--cat-neutral-bg);
  color: var(--cat-neutral-fg);
  font-size: var(--font-xs);
  font-weight: 600;
}
.mhint {
  margin: -6px 0 0 80px;
  color: var(--text-secondary);
  font-size: var(--font-sm);
  line-height: 1.5;
}
.mhint.warn {
  margin-left: 0;
  padding: 7px 9px;
  border-radius: var(--radius-sm);
  background: var(--warning-light);
  color: var(--text-secondary);
}
.risk-check {
  margin-top: -2px;
}
.secret-modal {
  gap: 10px;
}
.secret-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.secret-program {
  font-size: var(--font-base);
  font-weight: 650;
  color: var(--text-primary);
}
.secret-intro {
  margin: 0;
  color: var(--text-secondary);
  font-size: var(--font-sm);
  line-height: 1.6;
}
.secret-fields {
  display: flex;
  flex-direction: column;
  gap: 7px;
}
.secret-field {
  display: flex;
  align-items: center;
  gap: 9px;
  min-height: 38px;
  padding: 4px 8px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
  background: var(--code-bg);
}
.secret-label {
  flex: none;
  width: 42px;
  color: var(--text-muted);
  font-size: var(--font-xs);
}
.secret-code {
  flex: 1;
  min-width: 0;
  color: var(--text-primary);
  font-family: var(--mono);
  font-size: var(--font-md);
  letter-spacing: 0.04em;
  overflow-wrap: anywhere;
}
.secret-password {
  letter-spacing: 0.02em;
}
.secret-note {
  padding: 9px 10px;
  border-radius: var(--radius-sm);
  background: var(--warning-light);
  color: var(--text-secondary);
  font-size: var(--font-sm);
  line-height: 1.55;
}
.secret-note strong {
  display: block;
  margin-bottom: 3px;
  color: var(--text-primary);
  font-size: var(--font-sm);
}
.secret-note p {
  margin: 2px 0 0;
}
.secret-foot {
  align-items: center;
}
.secret-foot-spacer {
  flex: 1;
}
.modal-foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.log-body {
  max-height: 420px;
  margin: 0;
  overflow: auto;
  padding: 10px;
  border-radius: var(--radius-sm);
  background: var(--code-bg);
  font-family: var(--mono);
  font-size: var(--font-xs);
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}

/* ── 响应式 ──
   只使用正式档位（480 / 768 / 1024 / 1180），与 scripts/check-breakpoints.mjs 一致：
   原来私设的 860 / 640 两档已归并到 1024 与 768/480。 */
@media (max-width: 1024px) {
  .page-head {
    flex-direction: column;
    align-items: flex-start;
    gap: var(--space-3);
  }
  .tools {
    width: 100%;
    align-items: stretch;
  }
  .tools-row {
    width: 100%;
  }
  .search-input {
    flex: 1;
    width: auto;
    /* 1024 下原来会拉成 708px：搜索框是筛选工具，不该占掉半行 */
    max-width: 360px;
  }
}
@media (max-width: 768px) {
  .filter-card {
    padding: 8px 12px;
  }
  .sort-btn {
    display: none;
  }
  .mobile-sort-btn {
    display: inline-flex;
  }
  .pg-tile {
    min-height: 200px;
    padding: 11px;
  }
  .pg-tile .glyph {
    width: 38px;
    height: 38px;
    font-size: var(--font-xs);
  }
  .pg-actions :deep(.pg-action-main) {
    flex: 1;
    min-width: 0;
  }
  .pg-actions .pg-spacer {
    display: none;
  }
  .banner {
    flex-wrap: wrap;
  }
  .banner .n-button {
    margin-left: 23px;
  }
}
@media (pointer: coarse) {
  /* 页头工具也是可点目标：刷新是 34×34、手机排序 72×28，触屏上都要补到 44 */
  .tools-row :deep(.n-button:not(.mobile-sort-btn)) {
    width: 44px;
    min-width: 44px;
    height: 44px;
  }
  .mobile-sort-btn {
    width: auto;
    min-width: 44px;
    min-height: 44px;
    padding: 0 var(--space-3);
  }
  /* 筛选、搜索、重试、清除筛选：这些是"还没摸到卡片"就要点的控件 */
  /* 只把按钮撑到 44 不够：n-radio-group 自己还是 28px（--n-height），四个 44px 的段会
     溢出白卡并与下方的分组标题重叠。组容器与卡片一起长高。 */
  .filter-radio {
    --n-height: 44px;
    height: auto;
    align-content: start;
  }
  .filter-radio :deep(.n-radio-button) {
    min-height: 44px;
  }
  .filter-card {
    align-items: stretch;
    padding-top: var(--space-2);
    padding-bottom: var(--space-2);
  }
  /* 搜索框本身就是 .n-input 根节点（class 合并到同一元素），没有内层 .n-input 可穿透 */
  .search-input {
    min-height: 44px;
  }
  .load-error :deep(.n-button),
  .empty :deep(.n-button) {
    min-height: 44px;
  }
  .pg-actions :deep(.n-button) {
    min-height: 44px;
    height: 44px;
  }
  .pg-actions :deep(.more-btn) {
    width: 44px;
    min-width: 44px;
    height: 44px;
  }
  .detail-head :deep(.n-button) {
    width: 44px;
    height: 44px;
  }
  .detail-main-actions :deep(.n-button),
  .detail-actions :deep(.n-button) {
    min-height: 44px;
    height: 44px;
  }
  .detail-logs-head :deep(.n-button) {
    min-height: 44px;
    padding: 0 var(--space-2);
  }
}
@media (max-width: 480px) {
  .detail-body {
    padding: 14px 16px 24px;
  }
  .detail-meta {
    grid-template-columns: 1fr 1fr;
  }
  .mhint {
    margin-left: 0;
  }
  .mrow {
    align-items: flex-start;
    flex-wrap: wrap;
  }
}
</style>
