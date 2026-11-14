package com.ufi_axis.ui.screens.filemanager

import com.ufi_axis.ui.components.common.UfiButton
import com.ufi_axis.ui.components.common.UfiButtonSize
import com.ufi_axis.ui.components.common.UfiButtonVariant
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import com.ufi_axis.ui.components.common.UfiPopupMenu
import com.ufi_axis.viewmodel.state.FileViewMode
import kotlin.math.roundToInt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.navigation.NavHostController
import com.ufi_axis.data.api.FileItem
import com.ufi_axis.data.api.StorageSourceInfo
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.components.common.UfiPopupOption
import com.ufi_axis.ui.components.common.UfiPageBackgroundBox
import com.ufi_axis.ui.components.common.UfiSkeletonListItem
import com.ufi_axis.ui.components.common.UfiLoadingIndicator
import com.ufi_axis.ui.components.common.UfiToastHost
import com.ufi_axis.ui.components.common.ToastMessage
import com.ufi_axis.ui.components.common.ToastType
import com.ufi_axis.ui.components.common.UfiConfirmDialog
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.screens.filemanager.components.FileEmptyState
import com.ufi_axis.ui.screens.filemanager.components.FileErrorState
import com.ufi_axis.ui.screens.filemanager.components.FileLoadingState
import com.ufi_axis.ui.screens.filemanager.components.BatchActionBar
import com.ufi_axis.ui.screens.filemanager.components.TransferBar
import com.ufi_axis.ui.screens.filemanager.components.FileRowCard
import com.ufi_axis.ui.screens.filemanager.components.FileIcon
import com.ufi_axis.ui.screens.filemanager.components.StorageRow
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Add
import com.ufi_axis.ui.screens.filemanager.dialogs.ApkInstallDialog
import com.ufi_axis.ui.screens.filemanager.dialogs.ChecksumDialog
import com.ufi_axis.ui.screens.filemanager.dialogs.DeleteConfirmDialog
import com.ufi_axis.ui.screens.filemanager.dialogs.FileInfoDialog
import com.ufi_axis.ui.screens.filemanager.dialogs.NewFolderDialog
import com.ufi_axis.ui.screens.filemanager.dialogs.RenameDialog
import com.ufi_axis.ui.screens.filemanager.dialogs.UploadPhase
import com.ufi_axis.ui.screens.filemanager.dialogs.UploadProgressDialog
import com.ufi_axis.ui.screens.filemanager.dialogs.SortSheet
import com.ufi_axis.ui.screens.filemanager.dialogs.StoragePermissionDialog
import com.ufi_axis.ui.screens.filemanager.dialogs.SearchDialog
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.UfiMotion
import com.ufi_axis.ui.theme.UfiWeight
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.ufiStandardCard
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.ui.animation.blurEntrance
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import com.ufi_axis.viewmodel.module.FileManagerModule
import com.ufi_axis.viewmodel.module.StorageSourceModule
import java.net.URLEncoder
import com.ufi_axis.ui.navigation.ufiNavigateOnce

/**
 * 文件管理器主屏。
 *
 * 从 [FileManagerModule.state] 收集状态，并行触发初始数据拉取，并组合 [BreadcrumbBar] +
 * [FileToolbar] + 主体列表（存储卷列表 / 文件列表）。
 *
 * 设计系统约束：
 *  - 颜色统一走 [com.ufi_axis.ui.theme.LocalResolvedPalette.current]，不使用 `colorScheme.tertiary`。
 *  - 滚动容器使用 [com.ufi_axis.ui.components.common.UfiPageBackgroundBox]（非滚动 Box，
 *    列表自身由 [LazyColumn] 滚动）。
 *  - [UfiScreenScaffold] 的 content 是 `(PaddingValues) -> Unit`，不是 BoxScope，
 *    因此此处不使用 `Modifier.align()`，布局一律用 [Column] 组织。
 *
 * [navController] 透传给 [UfiScreenScaffold] 以保留返回手势；子屏路由由各自入口直接
 * 触发导航。
 *
 * @param viewModel 文件管理器 ViewModel（直接持有 [FileManagerModule]）
 * @param sourceModule 外部存储源模块。首屏的存储列表兼任原「外部存储」配置页的职责
 *   （长按一行 → 编辑 / 测试连接 / 删除），因此需要其 CRUD 与试连能力。
 * @param navController 导航控制器
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerRoot(
    viewModel: FileManagerModule,
    sourceModule: StorageSourceModule,
    navController: NavHostController
) {
    val state by viewModel.state.collectAsState()
    val palette = LocalResolvedPalette.current
    val errorMessage = state.errorMessage
    var showNewFolder by remember { mutableStateOf(false) }

    // 手机端（本应用）存储权限设置跳转启动器：仅用于打开本机设置页（同设备合法）。
    // 设备端（Core）授权不得跨设备 startActivity，只能通过「重试」重新查询状态。
    val context = LocalContext.current
    val storageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.checkStorageAccess()
    }

    // 上传文件选择器：支持多选（GetMultipleContents 返回 List<Uri>）。
    // GetContent() 只返回单个 Uri，系统选择器不提供多选入口，多文件上传无法发起。
    // 目标目录取回调时的 currentPath。
    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> -> if (uris.isNotEmpty()) viewModel.uploadFilesToServer(uris, state.currentPath) }

    // 排序 / 操作面板 / 各弹窗的 holder（目标文件用 FileItem? 持有，关闭后回调仍可安全执行）。
    var showSort by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileItem?>(null) }
    var showDelete by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<FileItem?>(null) }
    var showInfo by remember { mutableStateOf(false) }
    var infoTarget by remember { mutableStateOf<FileItem?>(null) }
    var showInstall by remember { mutableStateOf(false) }
    var installTarget by remember { mutableStateOf<FileItem?>(null) }

    var showSearch by remember { mutableStateOf(false) }

    // 远端推送任务面板（core 暂存 → 外部存储源），由传输条的「⋯」或顶栏菜单打开。
    var showPushPanel by remember { mutableStateOf(false) }

    // 上传进度弹窗默认打开（开始上传即弹出）；点击「后台进行」后置为 false，
    // 上传继续执行、UI 降级为底部横条。下一次上传开始时重置为 true。
    var uploadDialogVisible by remember { mutableStateOf(false) }
    LaunchedEffect(state.isUploading) { if (state.isUploading) uploadDialogVisible = true }

    // 待删除的外部存储源（长按行 → 删除 → 二次确认）。删源是不可逆的配置变更，
    // 缺少确认步骤时一次误触即需重新填写地址、账号、密码。
    var sourceToDelete by remember { mutableStateOf<StorageSourceInfo?>(null) }

    // 刚点击「选存储」这一层中哪一行（等待 core 返回目录列表）。
    // 仅用于给该行单独显示加载指示：远端源列目录需 0.5~2s，缺少行级反馈时用户会重复点击。
    // isLoading 落下即清空 —— 成功会切换页面（该值失效），失败也需要恢复箭头。
    var pendingStorageKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.isLoading) { if (!state.isLoading) pendingStorageKey = null }

    // 存储源的操作结果（测试连接 / 删除）走本页 toast。
    // 该 state 与 FileManagerState 属于两个模块，需单独收集。
    val sourceOpState by sourceModule.state.collectAsState()

    // 一次性 toast 提示（复制 / 剪切 / 粘贴 / 删除等操作反馈），替代常驻 PasteBanner。
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }

    LaunchedEffect(sourceOpState.testResult) {
        sourceOpState.testResult?.let { r ->
            toastMessage = ToastMessage(
                if (r.success) "连接成功（${r.latencyMs}ms）" else "连接失败：${r.message}",
                if (r.success) ToastType.SUCCESS else ToastType.ERROR
            )
            sourceModule.clearTestResult()
        }
    }

    // 预览悬浮窗目标：图片 / 视频 / 音频点开时不导航至独立页面，而是将目标文件存于此，
    // 由 FilePreviewOverlay 在文件管理器之上叠加显示。文本类仍导航至编辑器。
    var previewTarget by remember { mutableStateOf<FileItem?>(null) }

    // 视频全屏状态由 FilePreviewOverlay 回传；全屏时后台文件列表的 RenderEffect 模糊归零，
    // 避免与 SurfaceView 视频解码争用 GPU 带宽导致卡顿或掉帧。
    var videoFullscreen by remember { mutableStateOf(false) }

    // 预览悬浮窗打开时对文件管理器主内容做模糊，作为悬浮窗的磨砂背景（替代纯黑遮罩）。
    // minSdk=31，RenderEffect 模糊在 31+ 可用；0.dp 等价于无操作。
    // 视频全屏时强制关闭模糊，将 GPU 资源让给视频层。
    // 打开时使用 tween 过渡（磨砂观感），关闭时立即归零（snap）：关闭路径若仍使用 250ms
    // tween，每帧都要重算 RenderEffect，与视频退出动画及解码争用 GPU；关闭瞬间浮层仍在
    // 淡出、背景基本被遮挡，snap 到 0 在视觉上无差别。
    val previewBgBlurred = previewTarget != null && !videoFullscreen
    val bgBlur by animateDpAsState(
        targetValue = if (previewBgBlurred) 14.dp else 0.dp,
        animationSpec = if (previewBgBlurred) tween(UfiMotion.Duration.Fluid) else snap(),
        label = "fileManagerBgBlur"
    )

    // 列表 / 网格视图以 state.viewMode 为单一数据源（由 FileManagerModule 持有并持久化）。

    // 文件操作分派：行点击直接打开；三点按钮弹出 UfiPopupMenu 操作菜单（见 FileRowCard）。
    // 点击分发的判据来自 FileKind（唯一真源），与列表图标、菜单文案共用同一张表，
    // 因此不会出现「显示了视频图标却无法打开」的矛盾。无内置预览的类别进入详情弹窗。
    fun resolveOpenRoute(f: FileItem) {
        if (f.isDirectory) {
            viewModel.navigateToDir(f.path)
            return
        }
        val encoded = URLEncoder.encode(f.path, "UTF-8")
        when (fileKindOf(f.name)) {
            // 图片 / 视频 / 音频：不进入独立导航页，改为在文件管理器之上叠加预览悬浮窗。
            FileKind.IMAGE -> previewTarget = f
            FileKind.VIDEO -> previewTarget = f
            FileKind.AUDIO -> previewTarget = f
            FileKind.TEXT -> navController.ufiNavigateOnce("file/editor?path=" + encoded)
            // 压缩包（含可解压的 zip/tar.gz）与 apk 均落此处：它们的动作会修改设备文件
            // 系统，单击这类无确认的手势不应直接执行，交由详情弹窗的主操作按钮。
            else -> { infoTarget = f; showInfo = true }
        }
    }

    fun handleAction(action: String, f: FileItem) {
        when (action) {
            "open" -> resolveOpenRoute(f)
            "edit" -> navController.ufiNavigateOnce("file/editor?path=" + URLEncoder.encode(f.path, "UTF-8"))
            "download" -> viewModel.downloadFileToPhone(f.path, f.name, source = "file")
            "info" -> { infoTarget = f; showInfo = true }
            "copy" -> { viewModel.copyToClipboard(f.path, isCut = false) }
            "cut" -> { viewModel.copyToClipboard(f.path, isCut = true) }
            "rename" -> { renameTarget = f; showRename = true }
            "delete" -> { deleteTarget = f; showDelete = true }
            "install" -> { installTarget = f; showInstall = true }
            "extract" -> viewModel.extractArchive(f.path, f.name)
            "compress" -> viewModel.compressFiles(listOf(f.path))
            "checksum" -> viewModel.checksumFile(f.path)
            "copy-path" -> viewModel.copyPathToClipboard(f.path)
        }
    }

    // 并行预拉取：只触发、不 await。下列方法均存在于 FileManagerModule。
    LaunchedEffect(Unit) {
        viewModel.loadDiskUsage()
        viewModel.checkStorageAccess()
    }

    // ── 远端存储源（FTP / WebDAV）在本页的三处影响 ────────────────────────────────
    // ① 虚拟根增加「外部存储」入口；② 面包屑第一段显示源标签而非 remote:<id>；
    // ③ 能力清单中缺失的动作必须隐藏（解压 / 压缩 / 校验和 / 搜索）。
    // 三者均只读 state.remoteSources —— 该快照由 checkStorageAccess() 一并拉取。

    /**
     * 某条路径属于哪个存储源。本地恒为 `"local"`，远端为 sourceId。
     * 用于判断「剪贴板内容与目标目录是否同一源」：core 不支持跨源复制 / 移动
     *（需要在两个 provider 之间搬运字节，该链路未实现），必须在操作前拦截。
     */
    fun sourceKeyOf(path: String): String =
        if (viewModel.isRemotePath(path)) viewModel.remoteSourceIdOf(path) ?: "" else "local"

    /** 当前目录所在源的显示名（面包屑用）。本地返回 null，由面包屑走既有的卷标签逻辑。 */
    val remoteSourceLabel: String? = run {
        val id = viewModel.remoteSourceIdOf(state.currentPath) ?: return@run null
        state.remoteSources.firstOrNull { it.id == id }?.label?.ifBlank { null } ?: id
    }

    /**
     * 粘贴：跨源直接拦截。
     *
     * 判据是「剪贴板中第一项的源」与「目标目录的源」是否相同：剪贴板中的多项必然来自
     * 同一目录（复制 / 剪切均在某一目录内完成），因此检查第一项即可。
     */
    fun handlePaste() {
        val clip = state.clipboard
        if (clip == null) return
        val from = sourceKeyOf(clip.sourcePaths.first())
        val to = sourceKeyOf(state.currentPath)
        if (from != to) {
            toastMessage = ToastMessage(
                "跨存储源操作暂不支持，请先下载到设备再上传",
                ToastType.WARNING,
                durationMs = 4000L
            )
            return
        }
        viewModel.pasteFromClipboard(state.currentPath)
    }

    // 当前目录支持哪些动作。判据是「core 对应路由对该源是否可用」，分两层：
    //  ① 远端 provider 的能力清单（`GET /api/storage/sources` 的 capabilities）：
    //     FTP 没有 MOVE/COPY，S3 没有 MOVE，任何远端都没有 EXTRACT/COMPRESS/CHECKSUM/SEARCH；
    //  ② core 路由自身是否有 `remote:` 分支 —— `/extract`、`/compress`、`/checksum`、
    //     `/upload`、`/touch` 均无远端分支，带 `remote:` 前缀的路径会在 `safeResolve` →
    //     `isUserStoragePath` 处直接返回 400 Invalid path。
    //     因此即使某源的 capabilities 声明了 UPLOAD（core 的 capabilitiesFor 确实声明了），
    //     远端仍按不支持处理：能力位声明不代表路由已实现，以实现为准。
    // 不支持时从菜单中移除，而不是置灰 —— 置灰项会被理解为权限问题而反复尝试。
    val isRemoteHere = viewModel.isRemotePath(state.currentPath)
    val canExtractHere = !isRemoteHere && viewModel.supportsCapability(state.currentPath, "EXTRACT")
    val canCompressHere = !isRemoteHere && viewModel.supportsCapability(state.currentPath, "COMPRESS")
    val canChecksumHere = !isRemoteHere && viewModel.supportsCapability(state.currentPath, "CHECKSUM")
    /**
     * 上传是否可用。
     *
     * 本地目录恒可用；远端目录需同时满足两条：
     * ① 该源的能力清单包含 `UPLOAD`；
     * ② 设备端固件支持两段式远端上传（`/api/files/status` 的 `supports_remote_upload`）。
     *
     * 第 ② 条必须满足：旧固件的 `/upload*` 没有 `remote:` 分支，路径带前缀只会返回 400。
     * 能力清单声明 UPLOAD 只表示 provider 可写，不表示路由实现了该链路。
     */
    val canUploadHere = !isRemoteHere ||
        (viewModel.supportsCapability(state.currentPath, "UPLOAD") && state.remotePushSupported)

    // ── 远端推送（第二阶段：core 暂存 → 外部存储源）在本页的派生值 ──
    // 传输条只展示「当前作业 + 排队数 + 失败数」，完整列表在 RemotePushDialog。
    // 失败数不随在途数归零：不存在在途作业但有失败记录时，传输条仍需保留一行，
    // 否则失败不会被展示（用户会认为已传输成功，而远端并无文件）。
    val pushJobs = state.remotePushJobs
    val pushCurrent = pushJobs.firstOrNull { it.state == FileManagerModule.PUSH_STATE_PUSHING }
        ?: pushJobs.firstOrNull { it.state == FileManagerModule.PUSH_STATE_QUEUED }

    // 本批上传的目标是否为远端存储源。
    //
    // core 的 RemotePushManager 中 jobs 是常驻内存表（ConcurrentHashMap，未落盘），已推送
    // 作业保留至被清理。因此「传到内部存储 / SD 卡」的本地上传，只要已推送过任何远端
    // 作业，`pushCurrent` 即非空 —— 弹窗可见条件 `isUploading || pushCurrent != null` 会在
    // 本地上传结束后继续成立，第一段刚结束就被判定为 `UploadPhase.PUSHING`，弹出
    //「第 2 步：设备推送到 外部存储」。
    //
    // 判据必须取发起本批上传时的目标路径，不能读当前的 currentPath（选择器在后台打开期间
    // 用户可能切换目录，会导致判据与目标不一致），因此在菜单点击那一刻记录。
    var uploadTargetIsRemote by remember { mutableStateOf(false) }
    val pushActiveCount = pushJobs.count {
        it.state == FileManagerModule.PUSH_STATE_PUSHING || it.state == FileManagerModule.PUSH_STATE_QUEUED
    }
    val pushFailedCount = pushJobs.count { it.state == FileManagerModule.PUSH_STATE_FAILED }

    /**
     * 本设备是否存在「选存储」这一层（虚拟根）。
     *
     * 判据：至少有一个本地卷，且确实有可选项 —— 多卷，或配置了外部存储源。
     * 仅单卷且无远端源时不存在这一层（此类设备上 `storageRoot` 即导航地板）。
     *
     * 三处必须使用同一判据，否则会出现「路径已置空但页面未切换」的白屏：
     * ① 列表分支的 `isVirtualRoot`（渲染卷列表）；
     * ② `canBack`（能否从 `storageRoot` 退回上一层）；
     * ③ ViewModel 侧的 `resetToLanding()`。
     */
    val hasVirtualRoot = state.storageVolumes.isNotEmpty() &&
        (state.storageVolumes.size > 1 || state.remoteSources.isNotEmpty())

    /**
     * 当前位于「选存储」这一层。
     *
     * 判据只有 `currentPath.isEmpty()`：`storageRoot` 是导航地板，不是「未选择存储」的标记。
     *（若将 `currentPath == storageRoot` 也计入，单卷 + 有远端源的设备在点击「内部存储」
     * 后会被判回这一层，表现为点击无响应。）
     *
     * 该层不渲染面包屑与工具栏：尚未选择存储，不存在「当前路径」，返回 / 视图切换 / 排序 /
     * 搜索 / 粘贴 / 上传均无作用对象，展示一排无响应的图标会被理解为故障。配置外部存储的
     * 入口为列表末尾的「添加外部存储」一行（工具栏「更多」菜单在该层不出现）。
     */
    val isVirtualRoot = hasVirtualRoot &&
        state.currentPath.isEmpty() &&
        state.searchResults == null

    // 回到该层时重新拉取源列表：用户可能刚从配置页新增 / 停用了某个源。一次本地 HTTP
    // 请求，代价可忽略。
    LaunchedEffect(isVirtualRoot) {
        if (isVirtualRoot) viewModel.loadRemoteSources()
    }

    // 进入「选存储」层时对每个启用的源试连一次，结果以标签显示在对应行上，
    // 使「哪个源当前可用」无需点入等待超时即可判断。
    //
    // 代价是真实的：每次测连都由 core 向对端服务器建立连接（FTP 握手可达秒级）。
    // 因此 ① 串行执行、② 60s 节流（见 StorageSourceModule.testAllSources）——
    // 进出文件管理器频繁，不节流会对对端服务器造成持续压力。
    // key 包含源列表本身：首帧时列表为空，拉回后需要补测一次。
    LaunchedEffect(isVirtualRoot, state.remoteSources.map { it.id }) {
        if (isVirtualRoot && state.remoteSources.isNotEmpty()) {
            sourceModule.testAllSources(state.remoteSources.map { it.id })
        }
    }
    val canDownloadHere = viewModel.supportsCapability(state.currentPath, "DOWNLOAD")
    val canRenameHere = viewModel.supportsCapability(state.currentPath, "RENAME")
    val canDeleteHere = viewModel.supportsCapability(state.currentPath, "DELETE")
    val canCopyHere = viewModel.supportsCapability(state.currentPath, "COPY")
    val canMoveHere = viewModel.supportsCapability(state.currentPath, "MOVE")

    /**
     * 长按菜单的选项清单 —— 网格与列表共用这一份。
     *
     * 网格分支与列表分支若各自维护一份相同的清单，增删菜单项需同步修改两处，遗漏一处会
     * 出现「列表视图有该项、网格视图没有」。
     *
     * 每一项的出现条件均为「执行它确实能成功」：
     *  - 下载走 `/api/files/stream`，core 对目录返回 404，因此目录不提供该项
     *   （无条件下发时，`downloadFileToPhone` 不检查状态码，会把错误 JSON 存成以目录名
     *     命名的文件并提示「下载完成」）；
     *  - 安装 APK 需 core 将文件 `cp` 到 `/data/local/tmp` 再 `pm install`，只接受本地真实
     *     路径，因此远端不提供；判据使用 [fileKindOf] 的类别而非 `endsWith(".apk")`，
     *     后者会把以 .apk 结尾的目录计入；
     *  - 复制 / 剪切分别需要 `COPY` / `MOVE` 能力（FTP 两者皆无，S3 无 MOVE）——
     *     菜单中提供、到粘贴时才失败是最差的交互顺序。
     */
    fun fileMenuFor(item: FileItem): List<UfiPopupOption> {
        val kind = fileKindOf(item.name, item.isDirectory)
        return buildList {
            add(UfiPopupOption("open", kind.openActionLabel, icon = Icons.AutoMirrored.Filled.Launch) { handleAction("open", item) })
            if (!item.isDirectory && canDownloadHere) {
                add(UfiPopupOption("download", "下载", icon = Icons.Filled.Download) { handleAction("download", item) })
            }
            add(UfiPopupOption("info", "信息", icon = Icons.Filled.Info) { handleAction("info", item) })
            if (canCopyHere) {
                add(UfiPopupOption("copy", "复制", icon = Icons.Filled.ContentCopy) { handleAction("copy", item) })
            }
            if (canMoveHere) {
                add(UfiPopupOption("cut", "剪切", icon = Icons.Filled.ContentCut) { handleAction("cut", item) })
            }
            if (canRenameHere) {
                add(UfiPopupOption("rename", "重命名", icon = Icons.Filled.DriveFileRenameOutline) { handleAction("rename", item) })
            }
            if (canDeleteHere) {
                add(UfiPopupOption("delete", "删除", icon = Icons.Filled.Delete, isDestructive = true) { handleAction("delete", item) })
            }
            if (kind == FileKind.APK && !isRemoteHere) {
                add(UfiPopupOption("install", "安装APK", icon = Icons.Filled.InstallMobile) { handleAction("install", item) })
            }
            // canExtract 只看文件名，因此以 .zip 结尾的目录也会命中；目录不可解压，必须显式排除。
            if (!item.isDirectory && canExtract(item.name) && canExtractHere) {
                add(UfiPopupOption("extract", "解压", icon = Icons.Filled.FolderZip) { handleAction("extract", item) })
            }
            if (!item.isDirectory && canChecksumHere) {
                add(UfiPopupOption("checksum", "校验和", icon = Icons.Filled.Fingerprint) { handleAction("checksum", item) })
            }
            if (canCompressHere) {
                add(UfiPopupOption("compress", "压缩", icon = Icons.Filled.Archive) { handleAction("compress", item) })
            }
            add(UfiPopupOption("copy-path", "复制路径", icon = Icons.Filled.ContentCopy) { handleAction("copy-path", item) })
        }
    }

    // 操作结果一次性 toast：operationMessage 变化时弹出，随后清空避免重复触发。
    LaunchedEffect(state.operationMessage) {
        state.operationMessage?.let {
            toastMessage = ToastMessage(it, ToastType.SUCCESS)
            viewModel.clearFileOperationMessage()
        }
    }

    UfiScreenScaffold(
        title = "文件管理器",
        navController = navController,
        showHeader = false
    ) { padding ->
        UfiPageBackgroundBox(modifier = Modifier.fillMaxSize()) {
            // 嵌套 Box 提供 BoxScope，使底部悬浮条 / 顶部 Toast 的 Modifier.align() 合法。
            // 预览打开时只对「文件管理器内容」做模糊（bgBlur），作为悬浮窗的磨砂背景；
            // 浮层（FilePreviewOverlay）是本 Box 的兄弟节点，不在此 blur 作用域内，保持清晰。
            Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .blur(bgBlur)
        ) {
            // 自绘标题栏（替代 UfiScreenScaffold 内置 UfiHeader，使其与文件列表处于同一模糊层）：
            // 预览打开时「文件管理器」标题与「退出」随内容一起模糊，作为悬浮窗背景。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 8.dp)
                    .padding(horizontal = Spacing.HeaderPaddingH)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .heightIn(min = 48.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = LocalIndication.current
                        ) { navController.popBackStack() }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "退出",
                        color = palette.accent,
                        style = UfiTextStyles.bodyLead.copy(fontWeight = UfiWeight.Medium)
                    )
                }
                Text(
                    text = "文件管理器",
                    style = UfiTextStyles.headerTitle,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

                // ── 存储切换条 ──
                // 无此条时切换存储只有一条路径：逐级返回到虚拟根后再点击另一张卡，目录较深时
                // 需要多次返回，且「当前在哪个存储、还能去哪些存储」在界面上不可见。
                // 该行平铺所有存储：当前项高亮，点击其他项直接跳转（本地跳卷根、远端跳源根），
                // 最左侧「全部」返回虚拟根。仅在确实存在多个存储且已进入某个存储时显示 ——
                // 虚拟根层本身即列出卡片，再加一行属于重复。
                if (hasVirtualRoot && state.currentPath.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = Spacing.CardHorizontalMargin, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StorageChip(
                            label = "全部",
                            selected = false,
                            onClick = { viewModel.navigateToRoot() }
                        )
                        state.storageVolumes.forEach { vol ->
                            StorageChip(
                                label = vol.label.ifBlank { "内部存储" },
                                selected = !isRemoteHere && state.currentPath.startsWith(vol.mountPath),
                                onClick = { viewModel.navigateToDir(vol.mountPath) }
                            )
                        }
                        state.remoteSources.forEach { src ->
                            StorageChip(
                                label = src.label.ifBlank { src.protocol.uppercase() },
                                selected = viewModel.remoteSourceIdOf(state.currentPath) == src.id,
                                onClick = { viewModel.navigateToDir(viewModel.remoteRootOf(src.id)) }
                            )
                        }
                    }
                }

                // 地址栏与工具栏分离：BreadcrumbBar 单独一行展示可点击路径，
                // FileToolbar 只负责核心操作图标，避免多重路径导致布局异常。
                // 两者在「选存储」层均不出现（见 isVirtualRoot 的 KDoc）。
                if (!isVirtualRoot) {
                BreadcrumbBar(
                    currentPath = state.currentPath,
                    volumes = state.storageVolumes,
                    onNavigate = { viewModel.navigateToDir(it) },
                    onRoot = { viewModel.navigateToRoot() },
                    remoteSourceLabel = remoteSourceLabel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.CardHorizontalMargin, vertical = 2.dp)
                )
                }

                // 是否还能向上返回。
                // 存在虚拟根（可选存储）的设备上，`storageRoot` 也可返回 —— 返回即该选择页；
                // 无虚拟根时 `storageRoot` 是地板（再向上为系统目录，不提供进入）。
                val canBack = state.currentPath.isNotEmpty() &&
                    (hasVirtualRoot || state.currentPath != state.storageRoot)
                // 系统返回键（手势 / 物理键）：可向上时返回上一级目录（如 /Android/media →
                // /Android）；到达根目录时不拦截，交由导航栈 popBackStack 退出文件管理器。
                // 预览悬浮窗打开时该返回键让位给浮层的关闭逻辑（见下方 BackHandler）。
                BackHandler(enabled = canBack && previewTarget == null) { viewModel.navigateToParent() }
                // 预览悬浮窗打开时：系统返回键优先关闭浮层。
                BackHandler(enabled = previewTarget != null) { previewTarget = null }
                if (!isVirtualRoot) {
                FileToolbar(
                    viewMode = state.viewMode,
                    canBack = canBack,
                    onBack = { viewModel.navigateToParent() },
                    onToggleViewMode = {
                        viewModel.setViewMode(
                            if (state.viewMode == FileViewMode.GRID) FileViewMode.LIST else FileViewMode.GRID
                        )
                    },
                    onSort = { showSort = true },
                    onSearch = { showSearch = true },
                    // 远端源不一定支持服务端搜索（FTP / WebDAV 均无「递归查找」原语）——
                    // 不支持时移除整颗按钮，而非点击后返回空结果（用户会认为文件不存在）。
                    showSearch = viewModel.supportsCapability(state.currentPath, "SEARCH"),
                    // 推送任务入口仅在外部存储源中出现，且只带当前源的 id ——
                    // 用户关心的是「传给这个源的文件是否到达」，混合所有源的全局列表
                    // 会把筛选工作转嫁给用户。
                    onPushTasks = if (isRemoteHere) {
                        {
                            val sid = viewModel.remoteSourceIdOf(state.currentPath).orEmpty()
                            navController.ufiNavigateOnce("detail/remote-push?source=$sid")
                        }
                    } else {
                        null
                    },
                    pushTaskBadge = if (isRemoteHere) {
                        val sid = viewModel.remoteSourceIdOf(state.currentPath)
                        pushJobs.count {
                            it.source_id == sid && (
                                it.state == FileManagerModule.PUSH_STATE_QUEUED ||
                                    it.state == FileManagerModule.PUSH_STATE_PUSHING ||
                                    it.state == FileManagerModule.PUSH_STATE_FAILED
                                )
                        }
                    } else {
                        0
                    },
                    canPaste = state.clipboard != null,
                    onPaste = { handlePaste() },
                    // 「更多」菜单每项均带图标：UfiPopupMenu 在 icon == null 时保留 18dp 空位，
                    // 使有图标与无图标的项文字仍对齐但视觉上缺一块。图标沿用长按菜单的
                    // 同一套 Icons.Filled.*。
                    moreMenuOptions = buildList {
                        add(UfiPopupOption("multiselect", "多选", icon = Icons.Filled.Checklist, onClick = { viewModel.toggleMultiSelectMode() }))
                        add(UfiPopupOption("newfolder", "新建文件夹", icon = Icons.Filled.CreateNewFolder, onClick = { showNewFolder = true }))
                        // 上传只对本地目录成立：core 的 `/api/files/upload`（含分片
                        // `/upload/session`）没有 `remote:` 分支，路径带前缀会在 safeResolve
                        // 处返回 400 Invalid path。远端源的 capabilities 中的 UPLOAD 是声明，
                        // 不是实现。
                        if (canUploadHere) {
                            add(UfiPopupOption(
                                "upload",
                                // 远端目录下点明「经设备中转」：该链路比本地上传多一段（先到设备、
                                // 再由设备推送），事先说明优于事后解释文件为何尚未出现在远端。
                                if (isRemoteHere) "上传（经设备中转）" else "上传",
                                icon = Icons.Filled.FileUpload,
                                onClick = {
                                    // 在打开选择器这一刻记录目标为本地还是远端：
                                    // core 的推送作业表常驻内存，本批目标为本地时不能被历史远端
                                    // 作业带出「第 2 步 推送」阶段（见 uploadTargetIsRemote）。
                                    uploadTargetIsRemote = isRemoteHere
                                    uploadLauncher.launch("*/*")
                                }
                            ))
                        }
                        /*
                         * 不提供「刷新」项：该层不存在手动刷新语义 —— 进入目录、返回上一级、
                         * 上传 / 删除后均会自行重载；该项还因未传 force 而命中 TTL 缓存，
                         * 点击无效。真正的重新拉取由错误横幅上的「重试」承担，那里语义明确
                         *（仅在失败后需要）。
                         */
                        /*
                         * 不提供「存储权限」项：它只是「再查一次」的入口，而无权限时的引导框
                         * 本就由 `checkStorageAccess()` 自动挂载（进入页面 / 回到前台各一次）；
                         * 置于「更多」中既非常用操作，又使三个高频动作挤在同一列。
                         * 权限状态展示归属于设置页。
                         */
                    }
                )
                }

                // 搜索结果横幅：searchResults 非空时挂载，提供「清除」返回普通列表。
                val results = state.searchResults
                if (results != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.CardHorizontalMargin, vertical = 8.dp)
                            .ufiStandardCard()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "搜索结果（${results.size}）",
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.textPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { viewModel.clearSearchResults() }) {
                                Text("清除", color = palette.accent)
                            }
                        }
                    }
                }

                // 加载 / 错误 / 空态与列表主体（分别由 FileLoadingState / FileErrorState / FileEmptyState 承载）。
                //
                // 不使用下拉刷新：`PullToRefreshBox(isRefreshing = state.isLoading)` 形态下，
                // ① 下拉手势与列表自身的纵向滚动竞争同一手势通道，长目录中回滚即误触一次全量
                //    重载；② `isRefreshing` 直接接 isLoading，任何一次进入目录都会在顶部显示
                //    转圈，与下方骨架屏叠加成两层加载态。
                // 手动重新拉取由错误横幅的「重试」承担（见 isVirtualRoot 分支中的入口）。
                //
                // ── 加载指示 ──
                //
                // 远端源的一次 `/api/files/list` 需要 0.5~2s（core 收到请求后还需访问
                // FTP / WebDAV / SMB），而「已有内容时不更换骨架」的策略在这种场景下完全
                // 没有反馈：列表继续显示上一个目录，用户会认为点击未生效并重复点击，
                // 每次点击又发起一次请求。
                //
                // 因此提供两个不改变布局高度、不产生闪烁的信号：
                //  ① 列表区顶部居中叠加 [UfiLoadingIndicator]（全仓统一的呼吸弧线指示器，
                //     与按钮 loading、各页首屏、更新流程使用同一组件；不使用 M3 的
                //     CircularProgressIndicator / LinearProgressIndicator —— 它们不读
                //     LocalResolvedPalette，换配色时会留下不随主题变化的控件）；
                //  ② 列表内容透明度降至 0.55，160ms tween 过渡 —— 「内容正在被替换」最直接的
                //     表达。淡化只作用于内容，指示器保持不透明，否则最需要看见的元素反而最淡。
                val listBusy = state.isLoading
                val listAlpha by animateFloatAsState(
                    targetValue = if (listBusy) 0.55f else 1f,
                    animationSpec = tween(durationMillis = 160),
                    label = "fileListLoadingAlpha"
                )

                Box(modifier = Modifier.weight(1f)) {
                    Column(Modifier.fillMaxSize().graphicsLayer { alpha = listAlpha }) {
                when {
                    // 首屏骨架：仅在「正在加载且当前无任何数据」时出现。
                    // 已有列表时的刷新不更换骨架 —— 否则每次刷新都会把已渲染内容整体替换为
                    // 灰块，产生闪烁；数据原地更新更稳定。
                    state.isLoading && state.files.isEmpty() && state.searchResults == null -> {
                        FileLoadingState(modifier = Modifier.weight(1f))
                    }
                    /*
                     * 错误页不能覆盖「选存储」这一层。
                     *
                     * 点击一个无法连接的远端源时，`currentPath` 不变（仅在成功时更新），
                     * 因此 `isVirtualRoot` 仍为 true；而错误分支若排在虚拟根分支之前，会把整张
                     * 源列表替换为错误页 —— 该层既不渲染工具栏、也不拦截返回键（`canBack`
                     * 要求 currentPath 非空），用户既看不到源列表也无法返回，只能退出文件
                     * 管理器。因此条件包含 `&& !isVirtualRoot`：该层照常列出源，错误以顶部
                     * 提示呈现（见下面 LazyColumn 的 landing-error item）。
                     */
                    errorMessage != null && !isVirtualRoot -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            FileErrorState(
                                message = errorMessage,
                                onRetry = { viewModel.refreshFileList() },
                                modifier = Modifier.weight(1f)
                            )
                            if (hasVirtualRoot) {
                                // 连不上某个源之后，"回到存储列表"永远得有个出口
                                UfiButton(
                                    text = "返回存储列表",
                                    onClick = { viewModel.navigateToRoot() },
                                    variant = UfiButtonVariant.Secondary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            horizontal = Spacing.CardHorizontalMargin,
                                            vertical = 8.dp
                                        )
                                )
                            }
                        }
                    }
                    else -> {
                        // 虚拟根（多卷 landing）：展示存储卷列表。
                        // 单卷时 storageRoot == mountPath 且直接进入文件列表，因此额外要求
                        // size > 1，否则单卷进入后 currentPath 恒等于 storageRoot，会停留在
                        // 卷列表无法进入文件。
                        // 列表派生：搜索结果优先；否则普通文件列表，再按 filterType 分类筛选。
                        val baseList = state.searchResults ?: state.files

                        // ── 懒加载 = 渐进渲染（不是分页）────────────────────────────────
                        // 依据：core 的 `GET /api/files/list` 只接受 `path` 查询参数
                        //（FileRoutes.kt `get("/list")`），没有 offset/limit；app 侧 Retrofit
                        // `listFiles(@Query("path"))` 同样只透传 path。实现真分页需修改服务端
                        // 契约，因此客户端对已取得的同一份数据分批渲染：
                        // 先渲染前 RENDER_PAGE 条，滚动接近底部时追加一批。
                        // 收益集中在首帧 —— 包含数百个文件的目录，其 item 组合与测量被分摊到
                        // 多帧，不再集中在进入目录的那一帧。
                        // 数据不受影响：baseList 始终完整，只是未全部挂载到布局树。
                        // 分页进度按路径分槽持久化（rememberSaveable）：打开文本编辑页（独立
                        // NavHost 目的地）再返回会使 FileManagerRoot 整体重组，普通 remember
                        // 会归零；使用 saveable 后可停留在原位置，不会回弹至列表顶部。
                        var renderLimit by rememberSaveable(state.currentPath, state.searchResults, state.viewMode) {
                            mutableIntStateOf(FILE_RENDER_PAGE)
                        }
                        val displayFiles = baseList.take(renderLimit)
                        val hasMoreToRender = renderLimit < baseList.size

                        // 列表态与网格态各持一份滚动状态：判定「接近底部」需读各自的 layoutInfo。
                        // 两份均在此处无条件创建（而非放入下方 if 分支）：放入分支时切换视图模式
                        // 会使 remember 槽位随分支销毁，切回时滚动位置归零。
                        // 滚动位置使用 rememberSaveable 按路径分槽保存，从文本编辑页返回后
                        // 重组不再丢失（表现为退出编辑页后跳回列表顶部）。
                        val savedListIndex = rememberSaveable(state.currentPath, state.searchResults) { mutableIntStateOf(0) }
                        val savedListOffset = rememberSaveable(state.currentPath, state.searchResults) { mutableIntStateOf(0) }
                        val savedGridIndex = rememberSaveable(state.currentPath, state.searchResults) { mutableIntStateOf(0) }
                        val savedGridOffset = rememberSaveable(state.currentPath, state.searchResults) { mutableIntStateOf(0) }
                        val fileListState = rememberLazyListState(
                            initialFirstVisibleItemIndex = savedListIndex.intValue,
                            initialFirstVisibleItemScrollOffset = savedListOffset.intValue
                        )
                        val fileGridState = rememberLazyGridState(
                            initialFirstVisibleItemIndex = savedGridIndex.intValue,
                            initialFirstVisibleItemScrollOffset = savedGridOffset.intValue
                        )
                        // 实时把滚动位置写回持久化槽，供返回时恢复。
                        LaunchedEffect(fileListState) {
                            snapshotFlow {
                                fileListState.firstVisibleItemIndex to fileListState.firstVisibleItemScrollOffset
                            }.collect { (i, o) -> savedListIndex.intValue = i; savedListOffset.intValue = o }
                        }
                        LaunchedEffect(fileGridState) {
                            snapshotFlow {
                                fileGridState.firstVisibleItemIndex to fileGridState.firstVisibleItemScrollOffset
                            }.collect { (i, o) -> savedGridIndex.intValue = i; savedGridOffset.intValue = o }
                        }

                        // 进入新目录或搜索结果变化后回到顶部（路径不变时不触发，保留原位置）。
                        LaunchedEffect(state.currentPath, state.searchResults) {
                            fileListState.scrollToItem(0)
                            fileGridState.scrollToItem(0)
                        }

                        // 触发条件：最后一个可见 item 距列表尾部不足 RENDER_LOAD_AHEAD 条。
                        // 使用 snapshotFlow 而非在组合中直接读 layoutInfo：后者会在每次滚动时
                        // 使整个分支重组，产生额外帧开销。
                        LaunchedEffect(fileListState, fileGridState, baseList.size, state.viewMode) {
                            snapshotFlow {
                                val info = if (state.viewMode == FileViewMode.GRID) {
                                    fileGridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                                } else {
                                    fileListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                                }
                                info ?: -1
                            }.collect { lastVisible ->
                                if (lastVisible >= 0 &&
                                    lastVisible >= renderLimit - FILE_RENDER_LOAD_AHEAD &&
                                    renderLimit < baseList.size
                                ) {
                                    renderLimit = (renderLimit + FILE_RENDER_PAGE)
                                        .coerceAtMost(baseList.size)
                                }
                            }
                        }

                        // 「选存储」层（isVirtualRoot，判据见其 KDoc）：一行一个，本地卷与
                        // 外部存储源使用同一种行式（[StorageRow]），不再分段，也不混用两种卡。
                        if (isVirtualRoot) {
                            LazyColumn(
                                modifier = Modifier.weight(1f).blurEntrance(triggerKey = state.currentPath),
                                contentPadding = PaddingValues(
                                    horizontal = Spacing.CardHorizontalMargin,
                                    vertical = 8.dp
                                ),
                                verticalArrangement = Arrangement.spacedBy(Spacing.CardBottomMargin)
                            ) {
                                /*
                                 * 顶部错误条：刚刚点击的源无法连接时，列表照常在下方展示，
                                 * 此处说明哪个请求失败并提供重试。重试走 `refreshFileList()`，
                                 * 它会优先重试 `lastFailedPath`（该次失败的路径），而非当前目录。
                                 */
                                if (errorMessage != null) {
                                    item(key = "landing-error") {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .ufiStandardCard()
                                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                        ) {
                                            Text(
                                                text = errorMessage,
                                                style = UfiTextStyles.note,
                                                color = palette.error
                                            )
                                            Spacer(Modifier.height(8.dp))
                                            UfiButton(
                                                text = "重试",
                                                onClick = { viewModel.refreshFileList() },
                                                variant = UfiButtonVariant.Subtle,
                                                size = UfiButtonSize.Small
                                            )
                                        }
                                    }
                                }

                                items(state.storageVolumes, key = { it.mountPath }) { vol ->
                                    StorageRow(
                                        // SD 卡与内部存储使用不同图标：重复使用同一个盘符图标
                                        // 会使图标失去区分作用。判据为挂载路径：`/storage/emulated/0`
                                        // 之外的都是外接卷。
                                        icon = if (vol.mountPath.startsWith("/storage/emulated")) {
                                            Icons.Filled.Storage
                                        } else {
                                            Icons.Filled.SdStorage
                                        },
                                        title = vol.label.ifBlank { "内部存储" },
                                        // 容量由进度条降级为一行文字：信息量不变，行高与远端源行一致。
                                        subtitle = "已用 ${vol.usedSize} / 共 ${vol.totalSize}" +
                                            vol.usePercent.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                                        loading = pendingStorageKey == vol.mountPath,
                                        onClick = {
                                            pendingStorageKey = vol.mountPath
                                            viewModel.navigateToDir(vol.mountPath)
                                        }
                                    )
                                }
                                // 外部存储源使用 `allRemoteSources`（含已停用的）。
                                // 外部存储源不设独立列表页（内容与本层重复），管理入口在本层：
                                // 长按一行提供「编辑 / 测试连接 / 删除」。
                                // 停用的源若不显示则无法恢复（既不能重新启用也不能删除），
                                // 因此照常列出，仅置灰并在副标题中标注。
                                items(state.allRemoteSources, key = { it.id }) { src ->
                                    // 首屏不显示主机 / 端口 / bucket / endpoint。
                                    // 该层为常用页面（可能被截图、投屏或他人查看），
                                    // `host:443`、`s3://bucket-name` 一类地址本身即敏感信息：
                                    // 会暴露内网拓扑或服务商账号线索，而对「选择哪个存储」没有
                                    // 帮助 —— 用户通过自定义名称识别它。完整地址保留在编辑页。
                                    val protocolName = when (src.protocol) {
                                        "webdav" -> "WebDAV"
                                        "ftp" -> "FTP"
                                        "smb" -> "SMB 共享"
                                        "s3" -> "S3 对象存储"
                                        else -> src.protocol.uppercase()
                                    }
                                    val editRoute = "detail/storage-source-edit?id=${src.id}"
                                    // 连通性标签：判定成功 / 失败与配色均在此处完成 ——
                                    // StorageRow 只负责绘制，不应知道「什么算连通」。
                                    val testing = src.id in sourceOpState.testingIds
                                    val testResult = sourceOpState.testResults[src.id]
                                    val statusText = when {
                                        testing -> "测试中"
                                        testResult == null -> null
                                        testResult.success -> "${testResult.latencyMs}ms"
                                        else -> "连接失败"
                                    }
                                    val statusColor = when {
                                        testing -> palette.textSecondary
                                        testResult == null -> null
                                        testResult.success -> palette.success
                                        else -> palette.warning
                                    }
                                    StorageRow(
                                        icon = when (src.protocol) {
                                            "smb" -> Icons.Filled.Dns
                                            "s3" -> Icons.Filled.CloudQueue
                                            else -> Icons.Filled.Cloud
                                        },
                                        // 未命名时使用协议名兜底，不能退回 host —— 那等于把需要
                                        // 隐藏的信息重新放到标题上。
                                        title = src.label.ifBlank { protocolName },
                                        subtitle = if (src.enabled) protocolName else "$protocolName · 已停用",
                                        dimmed = !src.enabled,
                                        loading = pendingStorageKey == src.id,
                                        statusText = if (src.enabled) statusText else null,
                                        statusColor = statusColor,
                                        onEdit = { navController.ufiNavigateOnce(editRoute) },
                                        // 停用的源点击必然连接失败，因此单击直接进入编辑页
                                        //（那里有启用开关），而不是触发一次连接失败。
                                        onClick = {
                                            if (src.enabled) {
                                                pendingStorageKey = src.id
                                                viewModel.navigateToDir(viewModel.remoteRootOf(src.id))
                                            } else {
                                                navController.ufiNavigateOnce(editRoute)
                                            }
                                        },
                                        moreOptions = listOf(
                                            UfiPopupOption("edit", "编辑", icon = Icons.Filled.Edit) {
                                                navController.ufiNavigateOnce(editRoute)
                                            },
                                            UfiPopupOption("test", "测试连接", icon = Icons.Filled.NetworkCheck) {
                                                sourceModule.testSource(src.id)
                                            },
                                            UfiPopupOption("remove", "删除", icon = Icons.Filled.Delete, isDestructive = true) {
                                                sourceToDelete = src
                                            }
                                        )
                                    )
                                }
                                // 新增入口位于列表末尾：该层没有工具栏，配置项必须在列表中有位置。
                                item(key = "add-source") {
                                    StorageRow(
                                        icon = Icons.Filled.Add,
                                        title = "添加外部存储",
                                        subtitle = "FTP / WebDAV / SMB / S3",
                                        onClick = { navController.ufiNavigateOnce("detail/storage-source-edit?id=") }
                                    )
                                }
                            }
                        } else if (displayFiles.isEmpty()) {
                            FileEmptyState(modifier = Modifier.weight(1f))
                        } else if (state.viewMode == FileViewMode.GRID) {
                            // 固定 4 列，卡片固定高度，避免 Adaptive 列宽与文字换行导致大小参差。
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(4),
                                state = fileGridState,
                                modifier = Modifier.weight(1f).blurEntrance(triggerKey = state.currentPath),
                                contentPadding = PaddingValues(
                                    horizontal = Spacing.CardHorizontalMargin,
                                    vertical = 8.dp
                                ),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // key 使用 path（目录内唯一且稳定）而非下标：排序切换或删除单项后
                                // 下标整体错位，带下标的 item 会被判定为「全部更换」。
                                items(displayFiles, key = { it.path }) { item ->
                                    val fileOptions = fileMenuFor(item)
                                    FileGridItem(
                                        item = item,
                                        isSelected = state.selectedPaths.contains(item.path),
                                        showCheckbox = state.multiSelectMode,
                                        onOpen = { handleAction("open", item) },
                                        onToggleSelect = { viewModel.toggleFileSelection(item.path) },
                                        moreOptions = fileOptions
                                    )
                                }
                                // 尾部渲染占位：仅在仍有未渲染条目时挂载一行骨架，整行跨列
                                //（maxLineSpan），否则会被放入 4 列网格的一格中。
                                if (hasMoreToRender) {
                                    item(span = { GridItemSpan(maxLineSpan) }) { FileRenderFooter() }
                                }
                            }
                        } else {
                            LazyColumn(
                                state = fileListState,
                                modifier = Modifier.weight(1f).blurEntrance(triggerKey = state.currentPath),
                                contentPadding = PaddingValues(
                                    horizontal = Spacing.CardHorizontalMargin,
                                    vertical = 8.dp
                                ),
                                verticalArrangement = Arrangement.spacedBy(Spacing.CardBottomMargin)
                            ) {
                                items(displayFiles, key = { it.path }) { item ->
                                    val fileOptions = fileMenuFor(item)
                                    FileRowCard(
                                        item = item,
                                        isSelected = state.selectedPaths.contains(item.path),
                                        showCheckbox = state.multiSelectMode,
                                        onOpen = { handleAction("open", item) },
                                        onToggleSelect = { viewModel.toggleFileSelection(item.path) },
                                        moreOptions = fileOptions
                                    )
                                }
                                if (hasMoreToRender) {
                                    item(key = "render-footer") { FileRenderFooter() }
                                }
                            }
                        }
                    }
                }
                    }

                // 加载指示器：叠加在列表区顶部居中，不置于淡化层内 —— 见上方 listAlpha 的说明。
                // 首屏骨架分支自带加载态，但骨架仅在「无任何数据」时出现；
                // 该指示器覆盖的是已有内容时的切目录 / 刷新，即远端最常见的等待场景。
                if (listBusy) {
                    UfiLoadingIndicator(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 20.dp)
                            .size(32.dp),
                        strokeWidth = 3f
                    )
                }
                }

            // 批量操作条与传输任务横幅为底部悬浮面板，见下方 UfiPageBackgroundBox 的 Box 作用域。
            }

            // 操作结果 toast：一次性提示，顶部居中，不遮挡列表。
            UfiToastHost(
                toastMessage = toastMessage,
                modifier = Modifier.align(Alignment.TopCenter),
                onDismiss = { toastMessage = null }
            )

            // 批量操作条：多选模式下底部悬浮（不推挤列表）。
            if (state.multiSelectMode) {
                var showBatchDeleteConfirm by remember { mutableStateOf(false) }
                BatchActionBar(
                    selectedCount = state.selectedPaths.size,
                    onSelectAll = { viewModel.selectAllFiles() },
                    onCopy = { viewModel.batchCopySelected() },
                    onCut = { viewModel.batchCutSelected() },
                    onCompress = { viewModel.compressFiles(state.selectedPaths.toList()) },
                    onDelete = { showBatchDeleteConfirm = true },
                    onCancel = { viewModel.toggleMultiSelectMode() },
                    // 与长按菜单同一套判据：当前源不支持的动作移除整颗按钮，而不是点击后返回失败。
                    showCopy = canCopyHere,
                    showCut = canMoveHere,
                    showCompress = canCompressHere,
                    showDelete = canDeleteHere,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                )
                if (showBatchDeleteConfirm) {
                    UfiConfirmDialog(
                        title = "删除选中文件",
                        text = "将删除选中的 ${state.selectedPaths.size} 个文件/目录，此操作不可恢复。",
                        confirmText = "全部删除",
                        destructive = true,
                        onDismiss = { showBatchDeleteConfirm = false },
                        onConfirm = { showBatchDeleteConfirm = false; viewModel.batchDeleteSelected() }
                    )
                }
            }

            // 传输任务横幅：上传 / 下载进行中或下载暂停时底部悬浮。
            // 另包含远端推送在运行或失败（第二阶段）—— 该阶段发生在上传完成之后，
            // 不展示则用户无从得知。
            val transferActive = state.isUploading || state.uploadProgress >= 0f ||
                state.isDownloading || state.downloadStatus == "paused" || state.downloadProgress >= 0f ||
                pushCurrent != null || pushFailedCount > 0
            if (transferActive) {
                TransferBar(
                    isUploading = state.isUploading,
                    uploadProgress = state.uploadProgress,
                    uploadFileName = state.uploadFileName,
                    isDownloading = state.isDownloading,
                    downloadProgress = state.downloadProgress,
                    downloadFileName = state.downloadFileName,
                    downloadStatus = state.downloadStatus,
                    onCancelDownload = { viewModel.cancelDownload() },
                    onResumeDownload = { viewModel.resumeDownload(state.downloadPath, state.downloadFileName) },
                    onCancelUpload = { viewModel.cancelUpload() },
                    uploadTotalCount = state.uploadTotalCount,
                    uploadDoneCount = state.uploadDoneCount,
                    pushFileName = pushCurrent?.file_name ?: "",
                    pushSourceLabel = pushCurrent?.source_label ?: "",
                    pushProgress = pushCurrent?.progress ?: -1f,
                    pushQueuedCount = (pushActiveCount - 1).coerceAtLeast(0),
                    pushFailedCount = pushFailedCount,
                    onCancelPush = { pushCurrent?.let { viewModel.cancelRemotePush(it.id) } },
                    onOpenPushPanel = { viewModel.loadRemotePushJobs(); showPushPanel = true },
                    modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (state.multiSelectMode) 84.dp else 16.dp)
                )
            }

            // 预览悬浮窗：图片 / 视频 / 音频直接在文件管理器之上叠加，不进入新页面。
            // 作为嵌套 Box 的最后一个子节点，z 序最高且覆盖全屏（含顶部退出栏与底部浮条）。
            FilePreviewOverlay(
                target = previewTarget,
                onDismiss = {
                    previewTarget = null
                    // 关闭预览时强制复位全屏状态：全屏下直接关闭时 FilePreviewOverlay 的
                    // videoFullscreen 仍为 true（仅被 AnimatedVisibility 隐藏），不会回调
                    // onFullscreenChange(false)，状态残留会使 bgBlur 恒为 0。
                    videoFullscreen = false
                },
                onFullscreenChange = { videoFullscreen = it }
            )

            }

            NewFolderDialog(
                visible = showNewFolder,
                onConfirm = { name ->
                    val cleanName = name.trim('/')
                    val parent = state.currentPath.trimEnd('/')
                    val target = if (parent.isEmpty()) {
                        // 虚拟根（多卷 landing）：currentPath 为空，无当前目录，
                        // 改为建在第一个卷的 mountPath 下，避免建到文件系统根 "/"。
                        val volRoot = state.storageVolumes.firstOrNull()?.mountPath
                        if (volRoot.isNullOrEmpty()) null else "$volRoot/$cleanName"
                    } else {
                        "$parent/$cleanName"
                    }
                    target?.let { viewModel.createDirectory(it) }
                    showNewFolder = false
                },
                onDismiss = { showNewFolder = false }
            )

            // 排序面板（ChoiceSheet 内部自带 if (visible) 守卫，visible 直接透传）。
            SortSheet(
                visible = showSort,
                currentSort = state.sortBy,
                onSelect = { viewModel.setSortBy(it); showSort = false },
                onDismiss = { showSort = false }
            )

            RenameDialog(
                visible = showRename,
                initialName = renameTarget?.name ?: "",
                onConfirm = { newName ->
                    renameTarget?.let { viewModel.renameFile(it.path, newName) }
                    showRename = false
                    renameTarget = null
                },
                onDismiss = { showRename = false; renameTarget = null }
            )

            DeleteConfirmDialog(
                visible = showDelete,
                targetLabel = deleteTarget?.name ?: "",
                onConfirm = {
                    deleteTarget?.let { viewModel.deleteFileOrDir(it.path) }
                    showDelete = false
                    deleteTarget = null
                },
                onDismiss = { showDelete = false; deleteTarget = null }
            )

            // 删除外部存储源。删除的是配置（地址 + 账号 + 密码），远端文件不受影响 ——
            // 复用文件删除的确认弹窗，但主体文案给出源名，避免被理解为「删除远端文件」。
            sourceToDelete?.let { src ->
                DeleteConfirmDialog(
                    visible = true,
                    targetLabel = "存储源「${src.label.ifBlank { src.protocol.uppercase() }}」的配置",
                    onConfirm = {
                        val id = src.id
                        sourceToDelete = null
                        sourceModule.deleteSource(id) { ok, msg ->
                            toastMessage = ToastMessage(
                                if (ok) "已删除存储源" else msg.ifBlank { "删除失败" },
                                if (ok) ToastType.SUCCESS else ToastType.ERROR
                            )
                            // 该层读取 FileManagerState 的快照，删除后必须重拉，
                            // 否则该行仍留在屏幕上，点入后才发现源已不存在。
                            if (ok) viewModel.loadRemoteSources()
                        }
                    },
                    onDismiss = { sourceToDelete = null }
                )
            }

            infoTarget?.let { f ->
                FileInfoDialog(
                    visible = true,
                    file = f,
                    onDismiss = { showInfo = false; infoTarget = null },
                    // 弹窗内的操作按钮走同一个 handleAction，不另开分发路径：
                    // 长按菜单与详情弹窗的「删除」必须是同一段代码，否则两处会各自漂移。
                    // 关闭弹窗由 FileInfoDialog 在回调前完成（见其 KDoc），此处不处理顺序。
                    onAction = { action -> handleAction(action, f) },
                    // 弹窗主操作的可用性判据复用长按菜单的清单：菜单中没有该项，说明当前源
                    // 无法执行（远端解压、远端 / 目录的安装 APK），弹窗也不提供按钮。
                    // 并行的条件判断会与菜单产生偏差。
                    actionAvailable = { action -> fileMenuFor(f).any { it.id == action } }
                )
            }

            ApkInstallDialog(
                visible = showInstall,
                fileName = installTarget?.name ?: "",
                onConfirm = {
                    installTarget?.let { viewModel.installApk(it.path) }
                    showInstall = false
                    installTarget = null
                },
                onDismiss = { showInstall = false; installTarget = null }
            )

            // 校验和弹窗：checksumFile 成功后由 state.checksumResult 驱动挂载。
            state.checksumResult?.let { cs ->
                ChecksumDialog(
                    visible = true,
                    result = cs,
                    onDismiss = { viewModel.clearChecksumResult() }
                )
            }

            // 远端推送任务：入口在各外部存储源工具栏右上角（见 FileToolbar 的 onPushTasks），
            // 传输条的「⋯」跳转同一页面。此处只保留跳转，页面本体在 RemotePushScreen。
            if (showPushPanel) {
                LaunchedEffect(Unit) {
                    showPushPanel = false
                    val sid = viewModel.remoteSourceIdOf(state.currentPath)
                        ?: pushCurrent?.source_id
                        ?: pushJobs.firstOrNull()?.source_id
                        ?: ""
                    navController.ufiNavigateOnce("detail/remote-push?source=$sid")
                }
            }

            // 上传进度：默认弹窗 + 圆环，跨两段连续显示（手机→设备，设备→远端）。
            // 点击「后台进行」后降级为底部横条（见下方 TransferBar），传输本身不受影响。
            //
            // 可见条件包含 pushCurrent：第一段结束后 isUploading 立即为 false，
            // 若只看它，弹窗会在「进度 100%」的瞬间消失 —— 该时刻最容易被理解为
            //「上传完成」，而文件实际才刚到达设备。
            val uploadPhase = if (state.isUploading) UploadPhase.UPLOADING else UploadPhase.PUSHING
            UploadProgressDialog(
                // 第二阶段（推送）仅在本批目标确为远端时可能出现。
                // 可见条件若为无条件的 `|| pushCurrent != null`，则会命中 core 推送作业表
                // 常驻内存的副作用：任何一次远端上传的残留作业都会让本地上传结束后弹出
                //「第 2 步：设备推送到…」。
                visible = uploadDialogVisible &&
                    (state.isUploading || (uploadTargetIsRemote && pushCurrent != null)),
                phase = uploadPhase,
                fileName = if (state.isUploading) state.uploadFileName else pushCurrent?.file_name.orEmpty(),
                progress = if (state.isUploading) state.uploadProgress else (pushCurrent?.progress ?: -1f),
                doneCount = state.uploadDoneCount,
                totalCount = state.uploadTotalCount,
                targetLabel = if (state.isUploading) {
                    if (isRemoteHere) {
                        remoteSourceLabel ?: "外部存储"
                    } else {
                        state.currentPath.trimEnd('/').substringAfterLast('/').ifBlank { "内部存储" }
                    }
                } else {
                    pushCurrent?.source_label?.ifBlank { "外部存储" } ?: "外部存储"
                },
                queuedCount = (pushActiveCount - 1).coerceAtLeast(0),
                onCancel = {
                    // 按阶段分派：两段取消的对象不同（分片上传 / 暂存文件）
                    if (state.isUploading) {
                        viewModel.cancelUpload()
                    } else {
                        pushCurrent?.let { viewModel.cancelRemotePush(it.id) }
                    }
                },
                onBackground = { uploadDialogVisible = false }
            )

            // 搜索弹窗：关键词 + 搜索范围（1/2/3 层），确认后触发 viewModel.searchFiles 并关闭。
            SearchDialog(
                visible = showSearch,
                currentDepth = state.searchDepth,
                onSearch = { query, depth ->
                    viewModel.searchFiles(query, depth)
                    showSearch = false
                },
                onDismiss = { showSearch = false }
            )

            // 双域存储权限引导：showStoragePermissionDialog 由 checkStorageAccess 置位时挂载。
            // 设备端未授权只能「重试」；手机端未授权可「前往手机设置」打开本机设置页。
            StoragePermissionDialog(
                visible = state.showStoragePermissionDialog,
                coreGranted = state.coreStorageGranted,
                phoneGranted = state.phoneStorageGranted,
                onRetry = { viewModel.checkStorageAccess() },
                onOpenPhoneSettings = {
                    // MANAGE_ALL_FILES_ACCESS_PERMISSION 是系统级设置页，不应带 package URI；
                    // 部分 ROM 下 Dialog 的 context.packageName 为空，改用 applicationContext
                    // 并加兜底。
                    val packageName = context.applicationContext.packageName
                    val intent = try {
                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    } catch (_: Exception) {
                        null
                    }
                    val fallback = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null)
                    )
                    try {
                        if (intent != null) {
                            storageLauncher.launch(intent)
                        } else {
                            storageLauncher.launch(fallback)
                        }
                    } catch (_: ActivityNotFoundException) {
                        try {
                            storageLauncher.launch(fallback)
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(context, "无法打开系统设置页", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onDismiss = { viewModel.dismissStoragePermissionDialog() }
            )
        }
    }
}

/**
 * 存储切换条中的一颗芯片。
 *
 * 不使用 Material3 的 `FilterChip`：其容器色与描边不走
 * [com.ufi_axis.ui.theme.LocalResolvedPalette]，换配色时会留下一片不随主题变化的按钮。
 * 此处用 palette 自绘，选中态为 accent 淡底 + accent 描边。
 */
@Composable
private fun StorageChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) palette.accent.copy(alpha = 0.16f) else palette.cardBg)
            .border(
                width = 1.dp,
                color = if (selected) palette.accent else palette.divider,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) palette.accent else palette.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 渐进渲染的批大小：首屏先挂 30 条。
 *
 * 30 约为「铺满两屏有余量」的量级：更小时用户滚动即触及追加边界（追加需要一次重组），
 * 更大则失去摊帧的意义。目录内文件不足 30 个时该机制不生效，无额外成本。
 */
private const val FILE_RENDER_PAGE = 30

/** 距列表尾部还剩几条时追加下一批：保留 5 条缓冲，使追加发生在用户看到底部之前。 */
private const val FILE_RENDER_LOAD_AHEAD = 5

/**
 * 列表尾部的渐进渲染占位（仅在仍有未渲染条目时挂载）。
 *
 * 放置一行骨架而非转圈：与首屏骨架同一套观感，其高度恰好预示「下方还会出现一行」，
 * 用户滚动时不会停在一个突兀的边界上。
 *
 * 不提供「渲染完成，共 N 项」的尾部说明：滚动到底自然停住本身即最明确的结束信号，
 * 额外一行灰字占用空间且容易被理解为异常提示。
 */
@Composable
private fun FileRenderFooter() {
    UfiSkeletonListItem(modifier = Modifier.padding(top = Spacing.CardBottomMargin))
}

// canExtract 定义在 FileKind.kt（同包，无需 import）：详情弹窗在另一个包中也需要按
//「能否解压」决定按钮，两处各保留一份后缀表会产生偏差。

// ===== 网格视图项（列表 / 网格切换） =====

/**
 * 网格视图单个文件 / 目录项：图标容器 48dp 居中，文件名单行截断居中，副信息居中。
 * 点击打开；长按整项呼出 UfiPopupMenu，提供与列表行一致的操作。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileGridItem(
    item: FileItem,
    isSelected: Boolean,
    showCheckbox: Boolean = false,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
    moreOptions: List<UfiPopupOption>,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    var expanded by remember { mutableStateOf(false) }
    var moreAnchorBounds by remember { mutableStateOf(IntRect.Zero) }
    var longPressPoint by remember { mutableStateOf(IntOffset.Zero) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(112.dp)
            .clip(UfiCardDefaults.shape)
            .background(if (isSelected) palette.accent.copy(alpha = 0.08f) else palette.cardBg)
            .then(if (isSelected) Modifier.border(1.dp, palette.accent.copy(alpha = 0.5f)) else Modifier)
            .onGloballyPositioned { coords ->
                val r = coords.boundsInWindow()
                moreAnchorBounds = IntRect(
                    r.left.roundToInt(),
                    r.top.roundToInt(),
                    r.right.roundToInt(),
                    r.bottom.roundToInt()
                )
            }
            .combinedClickable(
                onClick = onOpen,
                onLongClick = if (showCheckbox) null else {
                    {
                        // combinedClickable 不暴露长按 offset，因此使用卡片几何中心作为长按点
                        longPressPoint = IntOffset(
                            (moreAnchorBounds.left + moreAnchorBounds.right) / 2,
                            (moreAnchorBounds.top + moreAnchorBounds.bottom) / 2
                        )
                        expanded = true
                    }
                }
            )
            .padding(10.dp)
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(UfiCardDefaults.shape)
                    .background(
                        if (item.isDirectory) palette.accent.copy(alpha = 0.08f)
                        else palette.accentSecondary.copy(alpha = 0.12f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                FileIcon(
                    name = item.name,
                    isDirectory = item.isDirectory,
                    modifier = Modifier.size(28.dp)
                )
            }
            Text(
                text = item.name,
                style = UfiTextStyles.note.copy(fontWeight = UfiWeight.Strong),
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Text(
                text = if (item.isDirectory) "文件夹" else FormatUtils.formatSize(item.size),
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
        if (showCheckbox) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelect() },
                modifier = Modifier.align(Alignment.TopEnd),
                colors = CheckboxDefaults.colors(
                    checkedColor = palette.accent,
                    checkmarkColor = palette.onAccent
                )
            )
        }

        // 长按呼出的操作菜单（跟随长按点位置出现）。
        if (!showCheckbox) {
            UfiPopupMenu(
                visible = expanded,
                onDismiss = { expanded = false },
                anchorBounds = moreAnchorBounds,
                anchorPoint = longPressPoint,
                options = moreOptions
            )
        }
    }
}

/**
 * 尺寸格式化统一使用 [com.ufi_axis.util.FormatUtils.formatSize]。
 * 本文件、FileRowCard、FileInfoDialog 与 TextEditorScreen 不得各自实现一份：四处逐字节
 * 相同的拷贝会随局部修改产生偏差。
 */

