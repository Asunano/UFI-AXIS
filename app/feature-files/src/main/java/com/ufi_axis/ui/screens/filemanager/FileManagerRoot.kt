package com.ufi_axis.ui.screens.filemanager

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
import androidx.compose.material.icons.filled.Refresh
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

/**
 * 文件管理器主屏（ticket T5 — 主屏布局骨架）。
 *
 * 将 T1 的占位占位 [androidx.compose.material3.Text] 占位替换为真实的、可编译的
 * 屏幕骨架：从 [FileManagerModule.state] 收集状态，并行触发初始数据拉取，
 * 并组合 [BreadcrumbBar] + [FileToolbar] + 主体列表（存储卷列表 / 文件列表）。
 *
 * 设计系统约束：
 *  - 颜色统一走 [com.ufi_axis.ui.theme.LocalResolvedPalette.current]，禁止 `colorScheme.tertiary`。
 *  - 滚动容器使用 [com.ufi_axis.ui.components.common.UfiPageBackgroundBox]（非滚动 Box，
 *    因为列表自身由 [LazyColumn] 滚动）。
 *  - [UfiScreenScaffold] 的 content 是 `(PaddingValues) -> Unit`，**不是 BoxScope**，
 *    因此此处禁止 `Modifier.align()`，布局一律用 [Column] 组织。
 *
 * 注意：[navController] 继续透传给 [UfiScreenScaffold] 以保留返回手势，但子屏路由
 * 暂未接通（TODO(T7)）。
 *
 * @param viewModel 文件管理器 ViewModel（直接持有 [FileManagerModule]）
 * @param sourceModule 外部存储源模块。首屏的存储列表兼任原「外部存储」配置页的职责
 *   （长按一行 → 编辑 / 测试连接 / 删除），所以这里需要它的 CRUD 与试连能力。
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

    // T12 — 手机端（本应用）存储权限设置跳转启动器：仅用于打开**本机**设置页（合法同设备）。
    // 设备端（Core）授权绝不跨设备 startActivity，只靠「重试」重新查询状态。
    val context = LocalContext.current
    val storageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.checkStorageAccess()
    }

    // T7 — 上传文件选择器：**支持多选**（GetMultipleContents 回 List<Uri>）。
    // 2026-09-19 从 GetContent() 换过来：那个契约只回单个 Uri，系统选择器里
    // 连多选按钮都不给，所以"多文件上传"在入口处就被掐死了。
    // 目标目录取回调时最新的 currentPath。
    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> -> if (uris.isNotEmpty()) viewModel.uploadFilesToServer(uris, state.currentPath) }

    // T8b — 排序 / 操作面板 / 各弹窗的 holder（目标文件用 FileItem? 持有，确保关闭时仍可安全回调）。
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

    // 远端推送任务面板（core 暂存 → 外部存储源）。由传输条的「⋯」或顶栏菜单打开。
    var showPushPanel by remember { mutableStateOf(false) }

    // 上传进度弹窗默认打开（一开始上传就弹出来）；点「后台进行」后改为 false，
    // 上传继续跑、UI 降级成底部横条。下一次上传开始时重置为 true。
    var uploadDialogVisible by remember { mutableStateOf(false) }
    LaunchedEffect(state.isUploading) { if (state.isUploading) uploadDialogVisible = true }

    // 待删除的外部存储源（长按行 → 删除 → 二次确认）。删源是不可逆的配置变更，
    // 不给确认步的话一次误触就要把地址、账号、密码重新填一遍。
    var sourceToDelete by remember { mutableStateOf<StorageSourceInfo?>(null) }

    // 刚点了「选存储」那一层的哪一行（还在等 core 把目录列回来）。
    // 只用来给那一行单独显示转圈：远端源列目录要 0.5~2s，没有行级回执用户会反复点。
    // isLoading 落下即清空 —— 成功会切页（这个值随之失效），失败也要把箭头还回去。
    var pendingStorageKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.isLoading) { if (!state.isLoading) pendingStorageKey = null }

    // 存储源的操作结果（测试连接 / 删除）走本页 toast。
    // 这一份 state 与 FileManagerState 是两个模块，所以要单独收。
    val sourceOpState by sourceModule.state.collectAsState()

    // 一次性 toast 提示（复制/剪切/粘贴/删除等操作的反馈），替代常驻 PasteBanner。
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

    // 预览悬浮窗目标：图片/视频/音频点开时不再 navigate 进独立页，而是把目标文件存这里，
    // 由 FilePreviewOverlay 在文件管理器之上叠加显示。文本类仍走 navigate 进编辑器。
    var previewTarget by remember { mutableStateOf<FileItem?>(null) }

    // 视频全屏状态由 FilePreviewOverlay 回传；全屏时后台文件列表的 RenderEffect 模糊归零，
    // 避免与 SurfaceView 视频解码抢 GPU 带宽导致卡顿/掉帧。
    var videoFullscreen by remember { mutableStateOf(false) }

    // 预览悬浮窗打开时，把文件管理器主内容做模糊，营造磨砂背景（替代纯黑遮罩）。
    // minSdk=31，RenderEffect 模糊在 31+ 可用；0.dp 时等于无操作。
    // 视频全屏时强制关闭模糊，把 GPU 资源全让给视频层。
    // 打开时平滑模糊（磨砂观感），关闭时立即归零（snap）：关闭路径上若仍走 250ms tween，
    // 每帧都要重算 RenderEffect，与视频退出动画/解码抢 GPU，是「关闭掉帧」的次因；
    // 关闭瞬间浮层仍在淡出、背景基本被遮挡，snap 到 0 在视觉上无感。
    val previewBgBlurred = previewTarget != null && !videoFullscreen
    val bgBlur by animateDpAsState(
        targetValue = if (previewBgBlurred) 14.dp else 0.dp,
        animationSpec = if (previewBgBlurred) tween(UfiMotion.Duration.Fluid) else snap(),
        label = "fileManagerBgBlur"
    )

    // 列表/网格视图现在以 state.viewMode 为单一数据源（由 FileManagerModule 持有并持久化）。

    // T8b — 文件操作分派：行点击直接打开；三点按钮弹出 UfiPopupMenu 操作菜单（见 FileRowCard）。
    // 点击分发。判据来自 FileKind 这份唯一真源 —— 与列表图标、菜单文案共用同一张表，
    // 所以不会再出现「显示了视频图标却点不开」这种矛盾。没有内置预览的类别落到详情弹窗。
    fun resolveOpenRoute(f: FileItem) {
        if (f.isDirectory) {
            viewModel.navigateToDir(f.path)
            return
        }
        val encoded = URLEncoder.encode(f.path, "UTF-8")
        when (fileKindOf(f.name)) {
            // 图片/视频/音频：不再进独立导航页，改为在文件管理器之上叠加预览悬浮窗。
            FileKind.IMAGE -> previewTarget = f
            FileKind.VIDEO -> previewTarget = f
            FileKind.AUDIO -> previewTarget = f
            FileKind.TEXT -> navController.navigate("file/editor?path=" + encoded)
            // 压缩包（含可解压的 zip/tar.gz）与 apk 都落这里：它们的动作会改设备文件系统，
            // 单击这种一碰就触发、又没有确认的手势不该直接执行，交给详情弹窗的主操作按钮。
            else -> { infoTarget = f; showInfo = true }
        }
    }

    fun handleAction(action: String, f: FileItem) {
        when (action) {
            "open" -> resolveOpenRoute(f)
            "edit" -> navController.navigate("file/editor?path=" + URLEncoder.encode(f.path, "UTF-8"))
            "download" -> viewModel.downloadFileToPhone(f.path, f.name)
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
    // ① 虚拟根多一段「外部存储」入口；② 面包屑第一段要显示源标签而不是 remote:<id>；
    // ③ 能力清单里没有的动作必须隐藏（解压 / 压缩 / 校验和 / 搜索）。
    // 三者都只读 state.remoteSources —— 那份快照由 checkStorageAccess() 顺带拉。

    /**
     * 某条路径属于哪个存储源。本地一律 `"local"`，远端是 sourceId。
     * 用来判断"剪贴板里的东西和目标目录是不是同一个源" —— 跨源的复制/移动 core 不支持
     * （它要在两个 provider 之间搬字节，那是另一条没实现的链路），必须在点之前就拦住。
     */
    fun sourceKeyOf(path: String): String =
        if (viewModel.isRemotePath(path)) viewModel.remoteSourceIdOf(path) ?: "" else "local"

    /** 当前目录所在源的显示名（面包屑用）。本地返回 null，由面包屑走原来的卷标签逻辑。 */
    val remoteSourceLabel: String? = run {
        val id = viewModel.remoteSourceIdOf(state.currentPath) ?: return@run null
        state.remoteSources.firstOrNull { it.id == id }?.label?.ifBlank { null } ?: id
    }

    /**
     * 粘贴：跨源直接拦下。
     *
     * 判据是"剪贴板里第一项的源"与"目标目录的源"是否相同 —— 剪贴板里的多项必然来自
     * 同一个目录（复制/剪切都是在某一个目录里做的），所以看第一项就够。
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

    // 当前目录支持哪些动作。判据是「core 那条路由对这个源真的能走通吗」，分两层：
    //  ① 远端 provider 的能力清单（`GET /api/storage/sources` 的 capabilities）：
    //     FTP 没有 MOVE/COPY，S3 没有 MOVE，任何远端都没有 EXTRACT/COMPRESS/CHECKSUM/SEARCH；
    //  ② core 路由自身有没有 `remote:` 分支 —— `/extract`、`/compress`、`/checksum`、
    //     `/upload`、`/touch` 这几条完全没有远端分支，带 `remote:` 前缀的路径会在
    //     `safeResolve` → `isUserStoragePath` 处直接回 **400 Invalid path**。
    //     所以即便某个源的 capabilities 里写了 UPLOAD（core 的 capabilitiesFor 确实写了），
    //     远端也一律按"不支持"处理：能力位声明了、路由没实现，以实现为准。
    // 不支持时**从菜单里去掉**，而不是灰着 —— 灰着的项用户会以为是权限问题反复戳。
    val isRemoteHere = viewModel.isRemotePath(state.currentPath)
    val canExtractHere = !isRemoteHere && viewModel.supportsCapability(state.currentPath, "EXTRACT")
    val canCompressHere = !isRemoteHere && viewModel.supportsCapability(state.currentPath, "COMPRESS")
    val canChecksumHere = !isRemoteHere && viewModel.supportsCapability(state.currentPath, "CHECKSUM")
    /**
     * 上传是否可用。
     *
     * 本地目录恒可用；远端目录要同时满足两条：
     * ① 这个源的能力清单里有 `UPLOAD`；
     * ② **设备端固件支持两段式远端上传**（`/api/files/status` 的 `supports_remote_upload`）。
     *
     * 第 ② 条是必须的：老固件的 `/upload*` 没有 `remote:` 分支，路径带前缀只会回 400。
     * 能力清单里写着 UPLOAD 只代表 provider 会写，不代表路由实现了那条链路。
     */
    val canUploadHere = !isRemoteHere ||
        (viewModel.supportsCapability(state.currentPath, "UPLOAD") && state.remotePushSupported)

    // ── 远端推送（第二阶段：core 暂存 → 外部存储源）在本页的派生值 ──
    // 传输条只展示"当前那一个 + 还有几个排队 + 失败几个"，完整列表在 RemotePushDialog。
    // 注意失败数**不随在途数归零**：没有在途作业但有失败记录时，传输条仍要留一行，
    // 否则一次失败就彻底沉默（用户以为传上去了，远端却什么都没有）。
    val pushJobs = state.remotePushJobs
    val pushCurrent = pushJobs.firstOrNull { it.state == FileManagerModule.PUSH_STATE_PUSHING }
        ?: pushJobs.firstOrNull { it.state == FileManagerModule.PUSH_STATE_QUEUED }
    val pushActiveCount = pushJobs.count {
        it.state == FileManagerModule.PUSH_STATE_PUSHING || it.state == FileManagerModule.PUSH_STATE_QUEUED
    }
    val pushFailedCount = pushJobs.count { it.state == FileManagerModule.PUSH_STATE_FAILED }

    /**
     * 这台设备有没有「选存储」那一层（虚拟根）。
     *
     * 判据：至少有一个本地卷，且**确实有得选** —— 多卷，或者配了外部存储源。
     * 只有单卷且没有远端源时才没有这一层（那种设备上 `storageRoot` 就是唯一的地板）。
     *
     * 两处必须用同一个判据，否则会出现"路径置空了但页面不认"的白屏：
     * ① 列表分支的 `isVirtualRoot`（渲染卷列表）；
     * ② `canBack`（能不能从 `storageRoot` 退回上一层）。
     * ViewModel 侧的 `resetToLanding()` 用的也是这同一条，三处口径一致。
     */
    val hasVirtualRoot = state.storageVolumes.isNotEmpty() &&
        (state.storageVolumes.size > 1 || state.remoteSources.isNotEmpty())

    /**
     * 现在就停在「选存储」那一层。
     *
     * 判据只有 `currentPath.isEmpty()`：`storageRoot` 是导航地板，不是"未选择存储"的标记。
     * （曾经把 `currentPath == storageRoot` 也算进来，结果单卷 + 有远端源的设备点「内部存储」
     * 后又被判回这一层，表现为"点进去没反应"。）
     *
     * 这一层**不渲染面包屑与工具栏**：还没选存储，就没有"当前路径"可言，
     * 返回 / 视图切换 / 排序 / 搜索 / 粘贴 / 上传 也全都没有作用对象 ——
     * 摆一排点了没反应的图标只会让人怀疑是不是坏了。配置外部存储的入口
     * 改为列表末尾的「管理外部存储」一行（工具栏里那个「更多」菜单在这一层不出现）。
     */
    val isVirtualRoot = hasVirtualRoot &&
        state.currentPath.isEmpty() &&
        state.searchResults == null

    // 回到这一层时重拉一次源列表：用户刚从配置页加完 / 停用了某个源，
    // 回来就该看到变化。一次本地 HTTP，代价可忽略。
    LaunchedEffect(isVirtualRoot) {
        if (isVirtualRoot) viewModel.loadRemoteSources()
    }

    // 进「选存储」这一层时，把每个启用的源都试连一遍，结果以标签挂在各自那一行上。
    // 这样"哪个源现在能用"是看一眼就知道的事，而不是点进去等超时才发现。
    //
    // 代价是真实的：每次测连都是 core 向别人的服务器建一次连接（FTP 握手能到秒级）。
    // 所以 ① 串行、② 60s 节流（见 StorageSourceModule.testAllSources）——
    // 进出文件管理器很频繁，不节流就等于把对方服务器当压测目标。
    // key 带上源列表本身：首帧时列表还是空的，拉回来之后要补测一次。
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
     * 长按菜单的选项清单 —— **网格与列表共用这一份**。     *
     * 此前网格分支与列表分支各抄了一份逐字相同的 25 行 `buildList`，加减菜单项要同步改两处，
     * 漏一处就出现「列表视图有这项、网格视图没有」。
     *
     * 每一项的出现条件都是"点下去真的能做成事"：
     *  - 下载走 `/api/files/stream`，core 对目录回 404 → 目录不给这一项
     *    （旧实现无条件给，而 `downloadFileToPhone` 又不查状态码，结果是把错误 JSON
     *     存成一个以目录名命名的文件还提示"下载完成"）；
     *  - 安装 APK 要 core 把文件 `cp` 到 `/data/local/tmp` 再 `pm install`，只吃本地真实路径 →
     *    远端不给；判据用 [fileKindOf] 的类别而非 `endsWith(".apk")`，后者会把名字以 .apk
     *    结尾的**目录**也算进来；
     *  - 复制 / 剪切分别要 `COPY` / `MOVE` 能力（FTP 两个都没有，S3 没有 MOVE）——
     *    菜单里放着、到粘贴那一步才失败是最差的顺序。
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
            // canExtract 只看文件名，所以名字以 .zip 结尾的**目录**也会命中；目录不可解压，必须显式排除。
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

    // 操作结果一次性 toast：operationMessage 变化时弹出，随后清空以免重复触发。
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
            // 浮层（FilePreviewOverlay）是本 Box 的兄弟节点、不在此 blur 作用域内，故保持清晰。
            Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .blur(bgBlur)
        ) {
            // 自绘标题栏（替代 UfiScreenScaffold 内置 UfiHeader，使其与文件列表处于同一模糊层）：
            // 预览打开时「文件管理器」标题与「退出」随内容一起磨砂，作为悬浮窗背景。
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

                // ── 存储切换条（2026-09-21）──
                // 此前换存储只有一条路：一路返回到虚拟根，再点另一张卡。目录深一点就要连按五六次，
                // 而"我现在在哪个存储、还能去哪"这件事在界面上完全看不到。
                // 这一行把所有存储平铺出来：当前那个高亮，点另一个直接跳过去（本地跳卷根、
                // 远端跳源根），最左边「全部」回虚拟根。只在**确实有多个存储**且已经进到某个存储
                // 里面时出现 —— 虚拟根那一层本来就在列卡片，再加一行是重复。
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
                // 两者在「选存储」那一层都不出现（见 isVirtualRoot 的 KDoc）。
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

                // 还能不能往上退。
                // 有虚拟根（能选存储）的设备上，`storageRoot` 也能退 —— 退回去就是那一层选择页；
                // 没有虚拟根时 `storageRoot` 才是地板（再往上是系统目录，不给进）。
                val canBack = state.currentPath.isNotEmpty() &&
                    (hasVirtualRoot || state.currentPath != state.storageRoot)
                // 系统返回键（手势 / 物理键）：还能向上时返回上一级目录（如 /Android/media → /Android）；
                // 到根目录才不拦截，交给导航栈 popBackStack 退出文件管理器。
                // 预览悬浮窗打开时本返回键让位给浮层的关闭逻辑（见下方 BackHandler），避免冲突。
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
                    // 远端源不一定支持服务端搜索（FTP / WebDAV 都没有"递归查找"这种原语）——
                    // 不支持时整颗按钮撤掉，而不是点了回一个空结果让人以为真没这个文件。
                    showSearch = viewModel.supportsCapability(state.currentPath, "SEARCH"),
                    // 推送任务入口只在外部存储源里出现，且**只带当前这个源的 id** ——
                    // 人是在"某个源里"关心"我传给这个源的文件到了没有"，
                    // 混着所有源的全局列表等于把挑拣的工作推回给用户。
                    onPushTasks = if (isRemoteHere) {
                        {
                            val sid = viewModel.remoteSourceIdOf(state.currentPath).orEmpty()
                            navController.navigate("detail/remote-push?source=$sid")
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
                    // 「更多」菜单每项都带图标：UfiPopupMenu 在 icon == null 时留一个 18dp 空位，
                    // 于是"有图标的项"和"没图标的项"文字仍然对齐但视觉上缺一块，
                    // 今天新加的「刷新」正是这种情况。图标沿用长按菜单同一套 Icons.Filled.*。
                    moreMenuOptions = buildList {
                        add(UfiPopupOption("multiselect", "多选", icon = Icons.Filled.Checklist, onClick = { viewModel.toggleMultiSelectMode() }))
                        add(UfiPopupOption("newfolder", "新建文件夹", icon = Icons.Filled.CreateNewFolder, onClick = { showNewFolder = true }))
                        // 上传只对本地目录成立：core 的 `/api/files/upload`（含分片 `/upload/session`）
                        // 没有 `remote:` 分支，路径带前缀会在 safeResolve 处回 400 Invalid path。
                        // 远端源的 capabilities 里虽然写着 UPLOAD，但那是声明、不是实现。
                        if (canUploadHere) {
                            add(UfiPopupOption(
                                "upload",
                                // 远端目录下点明"经设备中转"：这条链路比本地上传多一段（先到设备、
                                // 再由设备推送），事先说清比事后解释为什么还没出现在远端要好。
                                if (isRemoteHere) "上传（经设备中转）" else "上传",
                                icon = Icons.Filled.FileUpload,
                                onClick = { uploadLauncher.launch("*/*") }
                            ))
                        }
                        add(UfiPopupOption("refresh", "刷新", icon = Icons.Default.Refresh, onClick = { viewModel.refreshFileList() }))
                        add(UfiPopupOption("storage", "存储权限", icon = Icons.Filled.Storage, onClick = { viewModel.checkStorageAccess() }))
                    }
                )
                }

                // T14 — 搜索结果横幅：searchResults 非空时挂载，提供「清除」回到普通列表。
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

                // 加载 / 错误 / 空态与列表主体（由 T6 的 FileStates 组件统一承载）。
                //
                // 2026-09-04 去掉下拉刷新：这里原来是 `PullToRefreshBox(isRefreshing = state.isLoading)`，
                // 现在换成普通 Box。两个理由：
                // ① 下拉手势和列表自身的纵向滚动抢同一个手势通道，长目录里往回滚一下就误触一次
                //    全量重载；② `isRefreshing` 直接接了 isLoading，任何一次进目录都会在顶部
                //    甩出一个转圈，与下面的骨架屏叠成两层加载态。
                // 手动刷新入口没有丢：顶栏「更多」菜单里的「刷新」项（见上方 moreMenuOptions）
                // 走的是同一个 loadFileList，删掉手势不影响用户主动刷新。
                // ── 加载指示（2026-09-21）──
                //
                // 远端源的一次 `/api/files/list` 要 0.5~2s（core 拿到请求后还要再走一趟
                // FTP / WebDAV / SMB），而下面那条"已有内容时不换骨架"的策略在这种场景下
                // 等于**完全没有反馈**：列表继续列着上一个目录，用户以为点击没生效、于是反复点，
                // 每点一次又发一个请求。下拉刷新的转圈 2026-09-04 已因手势冲突删掉，
                // 于是这一层的加载彻底变成了静默。
                //
                // 现在给两个都不改变布局高度、也不会闪的信号：
                //  ① 列表区顶部居中叠一个 [UfiLoadingIndicator]（全仓统一那颗呼吸弧线转圈，
                //     与按钮 loading、各页首屏、更新流程用的是同一个组件；**不用** M3 的
                //     CircularProgressIndicator / LinearProgressIndicator —— 它们的颜色
                //     不走 LocalResolvedPalette，换配色时会留下不跟着变的控件）；
                //  ② 列表内容淡到 0.55，160ms tween 过渡 —— "内容正在被替换"最直观的表达。
                //     淡化只作用在内容上，转圈本身保持不透明，否则最该看见的东西反而最淡。
                val listBusy = state.isLoading
                val listAlpha by animateFloatAsState(
                    targetValue = if (listBusy) 0.55f else 1f,
                    animationSpec = tween(durationMillis = 160),
                    label = "fileListLoadingAlpha"
                )

                Box(modifier = Modifier.weight(1f)) {
                    Column(Modifier.fillMaxSize().graphicsLayer { alpha = listAlpha }) {
                when {
                    // 首屏骨架：仅「正在加载且当前一条数据都没有」时出现。
                    // 已有列表时的刷新**不**换骨架 —— 否则每次刷新都把已渲染内容整块替换成灰块，
                    // 观感就是闪一下；数据原地更新反而更稳。
                    state.isLoading && state.files.isEmpty() && state.searchResults == null -> {
                        FileLoadingState(modifier = Modifier.weight(1f))
                    }
                    errorMessage != null -> {
                        FileErrorState(
                            message = errorMessage,
                            onRetry = { viewModel.loadFileList(state.currentPath) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    else -> {
                        // 虚拟根（多卷 landing）：展示存储卷列表。
                        // 单卷时 storageRoot == mountPath 且直接进入文件列表，因此额外要求 size > 1，
                        // 否则单卷进入后 currentPath 永远等于 storageRoot，会卡在卷列表无法进入文件。
                        // T14 — 列表派生：搜索结果优先；否则普通文件列表，再按 filterType 分类筛选。
                        val baseList = state.searchResults ?: state.files

                        // ── 懒加载 = 渐进渲染（不是真分页）────────────────────────────────
                        // 依据：core 的 `GET /api/files/list` 只读一个 `path` 查询参数
                        // （FileRoutes.kt `get("/list")`），没有 offset/limit；app 侧 Retrofit
                        // `listFiles(@Query("path"))` 同样只透传 path。要做真分页得改服务端契约，
                        // 本次不动 core，所以在客户端分批**渲染**同一份已到手的数据：
                        // 先出前 RENDER_PAGE 条，滚到接近底部再追加一批。
                        // 收益全在首帧 —— 一个装了几百个文件的目录，item 的组合/测量被摊到多帧，
                        // 不再全压在"进目录"那一帧上（那是最容易被感知为卡顿的时刻）。
                        // 语义上没有任何数据丢失：baseList 一直是完整的，只是没全部挂到布局树上。
                        // 分页进度：按路径分槽持久化（rememberSaveable）。打开文本编辑页（独立 NavHost
                        // 目的地）再返回会让 FileManagerRoot 整体重组，普通 remember 会归零；用 saveable
                        // 后停留在原位置，不会异常弹回列表顶部。
                        var renderLimit by rememberSaveable(state.currentPath, state.searchResults, state.viewMode) {
                            mutableIntStateOf(FILE_RENDER_PAGE)
                        }
                        val displayFiles = baseList.take(renderLimit)
                        val hasMoreToRender = renderLimit < baseList.size

                        // 列表态与网格态各自一份滚动状态：判定"接近底部"要读各自的 layoutInfo。
                        // 两份都在这里无条件创建（而不是各自放进下面的 if 分支里）：
                        // 放进分支的话切换视图模式会让 remember 槽位随分支一起销毁，
                        // 切回来时滚动位置归零 —— 用户切个视图就被弹回列表顶部。
                        // 关键修复：滚动位置用 rememberSaveable 按路径分槽保存，进入文本编辑页返回后
                        // 重组不再丢失滚动位置（修复「退出编辑页异常跳回列表顶部」）。
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

                        // 进入新文件夹/搜索结果变化后自动回到顶部（路径不变时此处不触发，保留原位置）。
                        LaunchedEffect(state.currentPath, state.searchResults) {
                            fileListState.scrollToItem(0)
                            fileGridState.scrollToItem(0)
                        }

                        // 触发条件：最后一个可见 item 距列表尾部不足 RENDER_LOAD_AHEAD 条。
                        // 用 snapshotFlow 而不是在组合里直接读 layoutInfo：后者每帧滚动都会
                        // 让整个分支重组，白白抖掉一堆帧。
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

                        // 「选存储」那一层（isVirtualRoot，判据见它的 KDoc）：**一行一个**，
                        // 本地卷与外部存储源用同一种行式（[StorageRow]），不再分段、不再两种卡混排。
                        if (isVirtualRoot) {
                            LazyColumn(
                                modifier = Modifier.weight(1f).blurEntrance(triggerKey = state.currentPath),
                                contentPadding = PaddingValues(
                                    horizontal = Spacing.CardHorizontalMargin,
                                    vertical = 8.dp
                                ),
                                verticalArrangement = Arrangement.spacedBy(Spacing.CardBottomMargin)
                            ) {
                                items(state.storageVolumes, key = { it.mountPath }) { vol ->
                                    StorageRow(
                                        // SD 卡与内部存储给不同图标：同一个盘符图标重复三次
                                        // 等于没有图标。判据用挂载路径，`/storage/emulated/0` 之外的都是外接卷。
                                        icon = if (vol.mountPath.startsWith("/storage/emulated")) {
                                            Icons.Filled.Storage
                                        } else {
                                            Icons.Filled.SdStorage
                                        },
                                        title = vol.label.ifBlank { "内部存储" },
                                        // 容量从进度条降级成一行文字：信息量一样，但行高与远端源一致。
                                        subtitle = "已用 ${vol.usedSize} / 共 ${vol.totalSize}" +
                                            vol.usePercent.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                                        loading = pendingStorageKey == vol.mountPath,
                                        onClick = {
                                            pendingStorageKey = vol.mountPath
                                            viewModel.navigateToDir(vol.mountPath)
                                        }
                                    )
                                }
                                // 外部存储源：用 **allRemoteSources**（含已停用的）。
                                // 独立的「外部存储」列表页已删除 —— 它列的东西和这一层完全重复 ——
                                // 管理职责搬到这里：长按一行给「编辑 / 测试连接 / 删除」。
                                // 停用的源如果不显示就再也找不回来（既不能重新启用也不能删），
                                // 所以照样列出，只是置灰并在副标题里写明。
                                items(state.allRemoteSources, key = { it.id }) { src ->
                                    // 首屏**不显示主机 / 端口 / bucket / endpoint**。
                                    // 这一层是随手打开就会看到的页面（还可能被截图、投屏、递给别人看），
                                    // 而 `pan.moe:443`、`s3://company-backup` 这类地址本身就是敏感信息：
                                    // 它暴露内网拓扑或服务商账号线索，而对"我要点哪个存储"毫无帮助 ——
                                    // 用户是靠自己起的名字认它的。完整地址留在编辑页。
                                    val protocolName = when (src.protocol) {
                                        "webdav" -> "WebDAV"
                                        "ftp" -> "FTP"
                                        "smb" -> "SMB 共享"
                                        "s3" -> "S3 对象存储"
                                        else -> src.protocol.uppercase()
                                    }
                                    val editRoute = "detail/storage-source-edit?id=${src.id}"
                                    // 连通性标签。判成功失败在这里做、配色也在这里给 ——
                                    // StorageRow 只负责画，不该知道"什么算连通"。
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
                                        // 没起名字时用协议名兜底，**不能**退回 host —— 那等于把
                                        // 想隐藏的东西又摆回标题上。
                                        title = src.label.ifBlank { protocolName },
                                        subtitle = if (src.enabled) protocolName else "$protocolName · 已停用",
                                        dimmed = !src.enabled,
                                        loading = pendingStorageKey == src.id,
                                        statusText = if (src.enabled) statusText else null,
                                        statusColor = statusColor,
                                        onEdit = { navController.navigate(editRoute) },
                                        // 停用的源点进去必然连不上，所以单击直接去编辑页
                                        // （那里有启用开关），而不是让它报一次连接失败。
                                        onClick = {
                                            if (src.enabled) {
                                                pendingStorageKey = src.id
                                                viewModel.navigateToDir(viewModel.remoteRootOf(src.id))
                                            } else {
                                                navController.navigate(editRoute)
                                            }
                                        },
                                        moreOptions = listOf(
                                            UfiPopupOption("edit", "编辑", icon = Icons.Filled.Edit) {
                                                navController.navigate(editRoute)
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
                                // 新增入口收在列表末尾：这一层没有工具栏，配置项必须在列表里有位置。
                                item(key = "add-source") {
                                    StorageRow(
                                        icon = Icons.Filled.Add,
                                        title = "添加外部存储",
                                        subtitle = "FTP / WebDAV / SMB / S3",
                                        onClick = { navController.navigate("detail/storage-source-edit?id=") }
                                    )
                                }
                            }
                        } else if (displayFiles.isEmpty()) {
                            FileEmptyState(modifier = Modifier.weight(1f))
                        } else if (state.viewMode == FileViewMode.GRID) {
                            // T17: 固定 4 列，卡片固定高度，避免 Adaptive 列宽/文字换行导致大小参差。
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
                                // key 用 path（目录内唯一且稳定）而不是下标：排序切换 / 删除单项后
                                // 下标会整体错位，带下标 key 的 item 会被判定为"全换了一遍"。
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
                                // 尾部渲染占位：只在"还有没渲染的条目"时挂一行骨架，整行跨列
                                // （maxLineSpan），不然会被塞进 4 列网格的一格里。
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

                // 加载中的转圈：叠在列表区顶部居中，**不在淡化层里** —— 见上方 listAlpha 的说明，
                // 最该看见的东西不能跟着内容一起变淡。
                // 首屏骨架那条分支自带加载态，但骨架只在"一条数据都没有"时出现；
                // 这颗转圈覆盖的是**已有内容时的切目录 / 刷新**，也就是远端最常见的那种等待。
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

            // T9 / T11 — 批量操作条与传输任务横幅改为底部悬浮面板，见下方 UfiPageBackgroundBox 的 Box 作用域。
            }

            // 操作结果 toast：一次性提示，顶部居中，不遮挡列表。
            UfiToastHost(
                toastMessage = toastMessage,
                modifier = Modifier.align(Alignment.TopCenter),
                onDismiss = { toastMessage = null }
            )

            // 批量操作条：多选模式下底部悬浮（不再推挤列表）。
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
                    // 与长按菜单同一套判据：当前源不支持的动作整颗按钮撤掉，而不是点了回一句失败。
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

            // 传输任务横幅：上传/下载进行中或下载暂停时底部悬浮。
            // 2026-09-21 起还有第三种情况：**远端推送在跑或失败**（第二阶段）——
            // 那一段发生在"上传完成"之后，不挂出来用户就看不到它。
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

            // 预览悬浮窗：图片/视频/音频直接在文件管理器之上叠加，不进新界面。
            // 作为嵌套 Box 的最后一个子节点，z 序最高、覆盖全屏（含顶部退出栏与底部浮条）。
            FilePreviewOverlay(
                target = previewTarget,
                onDismiss = {
                    previewTarget = null
                    // 关闭预览时强制复位全屏状态：全屏下直接关闭时 FilePreviewOverlay 的
                    // videoFullscreen 仍停在 true（仅被 AnimatedVisibility 隐藏），不会回调
                    // onFullscreenChange(false)，否则此处状态卡死导致 bgBlur 恒为 0。
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
                        // 改建到第一个卷的 mountPath，避免建到文件系统根 "/"。
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

            // T8b — 排序面板（ChoiceSheet，内部自带 if(visible) 守卫，故 visible 透传 true）。
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

            // 删除外部存储源。删掉的是**配置**（地址 + 账号 + 密码），远端文件一个都不动 ——
            // 复用文件删除那个确认弹窗，但文案主体给源名，避免被读成"要删远端文件"。
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
                            // 列表这一层读的是 FileManagerState 的快照，删完必须重拉，
                            // 不然那一行还留在屏幕上、点进去才发现源已经没了。
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
                    // 长按菜单点「删除」和详情弹窗点「删除」必须是同一段代码，否则两处会各自漂移。
                    // 关弹窗的动作由 FileInfoDialog 自己在回调前完成（见其 KDoc），这里不用管顺序。
                    onAction = { action -> handleAction(action, f) },
                    // 弹窗那一个主操作的可用性判据**复用长按菜单的清单**：菜单里没有这项，
                    // 说明当前源做不成它（远端的解压、远端 / 目录的安装 APK），弹窗也不该给按钮。
                    // 抄一份并行的条件判断必然会和菜单漂移。
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

            // 远端推送任务：入口改到各外部存储源的工具栏右上角（见 FileToolbar 的 onPushTasks），
            // 传输条的「⋯」也跳同一个页面。这里只保留跳转，页面本体在 RemotePushScreen。
            if (showPushPanel) {
                LaunchedEffect(Unit) {
                    showPushPanel = false
                    val sid = viewModel.remoteSourceIdOf(state.currentPath)
                        ?: pushCurrent?.source_id
                        ?: pushJobs.firstOrNull()?.source_id
                        ?: ""
                    navController.navigate("detail/remote-push?source=$sid")
                }
            }

            // 上传进度：默认弹窗 + 圆环，**跨两段连续显示**（手机→设备，设备→远端）。
            // 点「后台进行」后降级成底部横条（见下方 TransferBar），传输本身不受影响。
            //
            // 可见条件里带上 pushCurrent：第一段结束后 isUploading 立刻变 false，
            // 如果只看它，弹窗会在"进度 100%"的瞬间消失 —— 那正好是最容易被读成
            // "上传完成"的时刻，而实际上文件才刚到设备。
            val uploadPhase = if (state.isUploading) UploadPhase.UPLOADING else UploadPhase.PUSHING
            UploadProgressDialog(
                visible = uploadDialogVisible && (state.isUploading || pushCurrent != null),
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
                    // 按阶段分派：两段取消掉的东西不一样（分片 vs 暂存文件）
                    if (state.isUploading) {
                        viewModel.cancelUpload()
                    } else {
                        pushCurrent?.let { viewModel.cancelRemotePush(it.id) }
                    }
                },
                onBackground = { uploadDialogVisible = false }
            )

            // T14 — 搜索弹窗：关键词 + 搜索范围（1/2/3 层），确认后触发 viewModel.searchFiles 并关闭。
            SearchDialog(
                visible = showSearch,
                currentDepth = state.searchDepth,
                onSearch = { query, depth ->
                    viewModel.searchFiles(query, depth)
                    showSearch = false
                },
                onDismiss = { showSearch = false }
            )

            // T12 — 双域存储权限引导：showStoragePermissionDialog 由 checkStorageAccess 置位时挂载。
            // 设备端未授权只能「重试」；手机端未授权可「前往手机设置」打开本机设置页。
            StoragePermissionDialog(
                visible = state.showStoragePermissionDialog,
                coreGranted = state.coreStorageGranted,
                phoneGranted = state.phoneStorageGranted,
                onRetry = { viewModel.checkStorageAccess() },
                onOpenPhoneSettings = {
                    // 修复：MANAGE_ALL_FILES_ACCESS_PERMISSION 是系统级设置页，不应带 package URI；
                    // 部分 ROM 下 Dialog context.packageName 为空，改用 applicationContext 并加兜底。
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
 * 存储切换条里的一颗芯片。
 *
 * 刻意不用 Material3 的 `FilterChip`：那套自带的容器色与描边不走
 * [com.ufi_axis.ui.theme.LocalResolvedPalette]，换配色时会留下一片不跟着变的按钮
 * （本仓库的红线之一）。这里用 palette 自绘，选中态填 accent 淡底 + accent 描边。
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
 * 30 是"够铺满两屏还有余量"的量级 —— 再小的话用户一滚就撞到追加边界（追加本身要重组一次），
 * 再大就失去了摊帧的意义。目录里文件不足 30 个时这套机制等于不存在，零额外成本。
 */
private const val FILE_RENDER_PAGE = 30

/** 距列表尾部还剩几条时就追加下一批：留 5 条缓冲，让追加发生在用户看到底部**之前**。 */
private const val FILE_RENDER_LOAD_AHEAD = 5

/**
 * 列表尾部的渐进渲染占位（只在还有未渲染条目时挂载）。
 *
 * 放一条骨架行（而不是转圈）：与首屏骨架同一套观感，且它占的高度恰好预示"下面还会出现一行"，
 * 用户往下滚不会停在一个突兀的硬边界上。
 *
 * 2026-09-04：删掉了原先"渲染完则收成一行「没有更多（共 N 项）」"的尾部说明 ——
 * 滚到底自然停住本身就是最清楚的到底信号，多一行灰字既占位置又容易被当成故障提示。
 */
@Composable
private fun FileRenderFooter() {
    UfiSkeletonListItem(modifier = Modifier.padding(top = Spacing.CardBottomMargin))
}

// canExtract 已搬到 FileKind.kt（同包，无需 import）：详情弹窗在另一个包里也要按
// 「能否解压」决定按钮，两处各留一份后缀表迟早对不上。

// ===== 网格视图项（列表/网格切换） =====

/**
 * 网格视图单个文件/目录项：图标容器 48dp 居中，文件名两行截断居中，副信息居中。
 * 点击打开；长按整项呼出 UfiPopupMenu 提供与列表行一致的操作。
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
                        // combinedClickable 不暴露长按 offset，退而用卡片几何中心作为长按点
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
 * 2026-08-31：私有 formatSize 已删除 —— 全库 4 份逐字节相同的拷贝
 *（本文件 / FileRowCard / FileInfoDialog / TextEditorScreen）统一收敛到
 * [com.ufi_axis.util.FormatUtils.formatSize]。
 */

