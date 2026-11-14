package com.ufi_axis.data.bill

/**
 * 话费账单数据源（2026-10-31 新增）。
 *
 * 目前只定义接口 + 一份**演示数据**，真实接入时：
 * 1. 实现一个 `BillRepository`（App 直连营业厅 / 走 core 代理都行）；
 * 2. 在 App 启动时把 [BillRepositoryProvider.repository] 换成真实实现；
 * 3. 删掉 [SampleBillRepository]（或保留给 Compose Preview / 无网调试）。
 *
 * 刻意不让 UI 直接依赖具体实现：账单页只认 [BillSnapshot]，
 * 数据来源换了页面不用改。
 */
interface BillRepository {
    /**
     * 读取一次账单快照；失败时抛异常，由 ViewModel 转成错误态。
     *
     * @param accountId 2026-11-05 多账号：null = 当前账号（老 core / 老调用点）。
     * @param force 绕过 core 的 5 分钟缓存（用户在页面上点刷新）。
     */
    suspend fun loadBill(accountId: String? = null, force: Boolean = false): BillSnapshot
}

/**
 * 全局数据源入口。
 *
 * 没有引入 DI 框架（本仓现有模块都是构造传参），这里用一个最小可替换的 holder；
 * 真实接入时在 `UfiAxisApplication` / `MainViewModel` 初始化处赋值即可。
 */
object BillRepositoryProvider {
    @Volatile
    var repository: BillRepository = SampleBillRepository()
}

/**
 * 演示数据（2026-10-31 查询结果），仅用于先把 UI 跑起来。
 *
 * ⚠ 接入真实数据后请替换 [BillRepositoryProvider.repository]，不要把这个实现留在生产路径上。
 */
class SampleBillRepository : BillRepository {
    override suspend fun loadBill(accountId: String?, force: Boolean): BillSnapshot = SAMPLE_BILL

    companion object {
        val SAMPLE_BILL = BillSnapshot(
            carrier = "中国移动",
            phone = "136****1614",
            planName = "8元自由选套餐（100MB流量）（其它品牌）",
            ownerName = "黄*",
            tenureMonths = 61,
            starLevel = 1.0,
            balanceYuan = 23.82,
            lastMonthBillYuan = 34.87,
            currentBillYuan = 11.05,
            currentBillPeriod = "202610",
            currentBillAsOf = "2026-10-31",
            points = 152,
            flow = BillFlow(
                generalUsedGb = 0.46,
                generalTotalGb = 2.20,
                // 1.73 / 109.05 是营业厅给的剩余量；不要用 2.20 - 0.46 = 1.74 去“修正”它
                generalRemainingGb = 1.73,
                directedUsedGb = 0.95,
                directedTotalGb = 110.00,
                directedRemainingGb = 109.05
            ),
            monthlyUsedGb = 34.04,
            packages = listOf(
                BillPackage(
                    kind = BillPackageKind.GENERAL,
                    name = "8元自由选套餐（100MB流量）",
                    remainingGb = 0.00,
                    totalGb = 0.10,
                    validFrom = "2026-10-05T00:00:00",
                    validTo = "2026-11-05T00:00:00"
                ),
                BillPackage(
                    kind = BillPackageKind.GENERAL,
                    name = "8元自由选套餐（100MB流量）",
                    remainingGb = 0.10,
                    totalGb = 0.10,
                    validFrom = "2026-10-05T00:00:00",
                    validTo = "2026-12-05T00:00:00"
                ),
                BillPackage(
                    kind = BillPackageKind.GENERAL,
                    name = "参与线上活动享1GB流量1天包",
                    remainingGb = 0.93,
                    totalGb = 1.00,
                    validFrom = "2026-10-30T14:11:00",
                    validTo = "2026-10-31T14:16:00"
                ),
                BillPackage(
                    kind = BillPackageKind.GENERAL,
                    name = "统一营销流量月包-1GB",
                    remainingGb = 0.70,
                    totalGb = 1.00,
                    validFrom = "2026-10-14T00:00:00",
                    validTo = "2026-11-01T00:00:00"
                ),
                BillPackage(
                    kind = BillPackageKind.DIRECTED,
                    name = "移动云手机0元30GB定向流量包",
                    remainingGb = 30.00,
                    totalGb = 30.00,
                    validFrom = "2026-10-01T00:00:00",
                    validTo = "2026-11-01T00:00:00"
                ),
                BillPackage(
                    kind = BillPackageKind.DIRECTED,
                    name = "中国移动咪咕快游0元50GB定向流量包（2025版）",
                    remainingGb = 49.05,
                    totalGb = 50.00,
                    validFrom = "2026-10-01T00:00:00",
                    validTo = "2026-11-01T00:00:00"
                ),
                BillPackage(
                    kind = BillPackageKind.DIRECTED,
                    name = "30GB移动云盘定向流量免费服务",
                    remainingGb = 30.00,
                    totalGb = 30.00,
                    validFrom = "2026-10-01T00:00:00",
                    validTo = "2026-11-01T00:00:00"
                )
            )
        )
    }
}
