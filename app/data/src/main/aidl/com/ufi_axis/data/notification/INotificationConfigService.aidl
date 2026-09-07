// INotificationConfigService.aidl
//
// 前端 App 内部跨进程配置同步接口（独立进程架构 v2 Phase 2）。
//
// 进程边界：
//   - UI 进程（:app）：BackgroundGuardScreen / AlertSettingsScreen 经 NotificationConfigClient
//     绑定 :ufi_notify 进程的 NotifyService 调用本接口；
//   - 通知进程（:ufi_notify）：NotifyService.onBind 返回 INotificationConfigService.Stub 实现，
//     方法内写 ufi_axis_prefs + 触发 GuardScheduler 重调度。
//
// 注意：本文件放在 :app:data 模块（app/data/src/main/aidl/），包路径与实际类包
// com.ufi_axis.data.notification 一致 —— 这是为了让 AIDL 生成类同时被
//   - NotifyService（:app 模块，经 implementation(project(":app:data")) 引用）与
//   - NotificationConfigClient（:app:data 模块内部）
// 可见，避免 :app:data 反向依赖 :app 造成循环依赖。
package com.ufi_axis.data.notification;

interface INotificationConfigService {
    /** 开/关后台轮询总开关（写 guard_enabled + 重调度 WorkManager 周期任务）。 */
    void setGuardEnabled(boolean enabled);

    /** 修改轮询间隔（分钟，15/30/60；写 guard_interval_minutes + 重调度）。 */
    void setGuardIntervalMinutes(int minutes);

    /** 开/关免打扰时段（写 dnd_enabled）。 */
    void setDndEnabled(boolean enabled);

    /** 修改免打扰时段（0..23 的起止小时；start > end 表示跨零点）。 */
    void setDndWindow(int startHour, int endHour);

    /**
     * 开/关前台服务保活（写 guard_foreground_keepalive_enabled 的进程内镜像 +
     * 闸门为关时当场 stopForeground(STOP_FOREGROUND_REMOVE) + stopSelf）。
     *
     * 必须走 AIDL 显式传值：通知进程读开关时优先读自己那份 mirror_ 副本，主进程改共享文件
     * 它看不到；且「后台守护」页在页内一直 BIND_AUTO_CREATE 绑着服务，单靠 stopService
     * 不会销毁服务，那条常驻通知会挂到用户离开页面才消失。
     */
    void setForegroundKeepAlive(boolean enabled);

    /** 从 ufi_axis_prefs 重新加载配置并按需重调度（进程重启/外部写入后同步）。 */
    void reloadConfig();
}
