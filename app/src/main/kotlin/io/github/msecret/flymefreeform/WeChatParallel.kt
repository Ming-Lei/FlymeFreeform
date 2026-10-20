package io.github.msecret.flymefreeform

import android.content.Context

/**
 * **平行小窗：微信已在子页面时，再开一扇独立的微信小窗。**
 *
 * ## 触发条件（用户 2026-11 确认）
 *
 * 当前台应用是微信、且顶层 Activity **不是**主界面（[MAIN_ACTIVITY]）——即在子页面
 * （公众号文章 / 微信文档等）—— 这一次点击走「开第二扇」；其余情况行为不变。
 *
 * ## 「第二实例」其实是「第二个 task」
 *
 * 开出来的是**同一个进程**里的第二个 task（`MULTIPLE_TASK` 让 `ActivityStarter` 跳过
 * 「复用已有任务」那一步），**不是**独立进程、更不是微信自己的「应用分身」：账号、数据库、
 * 图片缓存、网络连接**全都是同一份**。所以别指望它俩互不影响 —— 排查「两扇窗互相带着跳」
 * 这类现象时先想到这一条。
 *
 * ## 平台层已验证可用（**仅 ColorOS**）
 *
 * 真机（ColorOS 17）：`am start --windowingMode 100 -f <NEW_TASK|MULTIPLE_TASK>`，
 * 第一扇在**子页面**时能开出一个独立第二扇；第一扇在**主界面**时会被 singleTask 重定向回
 * 老实例、并留下一个空壳 task。所以本特性**只在子页面触发**——主界面不带
 * `MULTIPLE_TASK`，空壳自然不会产生。
 *
 * `0x18000000` = `FLAG_ACTIVITY_NEW_TASK(0x10000000) | FLAG_ACTIVITY_MULTIPLE_TASK(0x08000000)`。
 *
 * ★★ **只在 [SystemSupport.FreeformState.OURS] 上启用**（见 [shouldLaunch] 第一条）。
 * 这里**故意**不去取当前形态的 windowingMode：AOSP 形态（vivo / 小米）下 `MULTIPLE_TASK`
 * 叠上各家私有的小窗扩展（小米还有 `mFreeformScale`、`XSpace` 重解析）**一次都没验过**，
 * 盲开的风险是「第二扇以**全屏**糊上来」—— 比没有这个功能糟得多。要放开：先按
 * [FreeformProtocol.windowMode] 取参数，再在真机上把「第二扇到底是不是小窗」记下来。
 *
 * ## 为什么只走 shell（`am start`），不走 `DirectStartStrategy`
 *
 * `startActivity + MULTIPLE_TASK` 在 ColorOS 私有模式 100 下成不成没验过；而 shell 那条
 * 已经真机验证可用。所以平行这条路**只走 [ShizukuShell]**，跳过直启。
 *
 * ## 判据只要一次 shell
 *
 * 「顶层 Activity + 现在屏上有几扇自由窗」是**同一条** `am stack list` 出来的
 * （[snapshot]），不拆成两次跑：这条路每次都发生在窗口出现**之前**，多跑一趟就是用户多等
 * 几百毫秒。⚠️ 别为了「顺手再查一次」把这里拆开 —— 这个功能全程压在「点下去 → 窗口出现」
 * 这条最敏感的时间线上。
 *
 * ## 空壳 task
 *
 * 正常流程里**不会**产生空壳（主界面根本不触发）。真要清理也只能记日志：
 * 真机 `am help task` 确认 `am task` 只有 lock / lock stop / resizeable / resize，
 * **没有 per-task 的 remove**；`am stack remove` 移的是整块 stack 且拿不到 stackId。
 * 所以这里**不做**会误伤的 `am force-stop`（那会把正在用的那扇一起杀）。
 * 要真正做到自动移除，得走 Shizuku UserService 反射调
 * `ActivityTaskManager#removeTask`（见类尾 TODO）。
 */
object WeChatParallel {

    /** 微信包名。★ 引用 [SystemTools.PACKAGE_WECHAT]，别在这里另抄一份。 */
    private const val PACKAGE = SystemTools.PACKAGE_WECHAT

    /**
     * 微信主界面 Activity 的类名尾段（`com.tencent.mm.ui.LauncherUI`，singleTask）。
     *
     * 顶层是它 = 在主界面 → 不开第二扇。判尾段而不是全名，是为了兼容 `am stack list`
     * 偶尔打出的相对写法（`.ui.LauncherUI`）。
     */
    private const val MAIN_ACTIVITY = ".ui.LauncherUI"

    /** `FLAG_ACTIVITY_NEW_TASK(0x10000000) | FLAG_ACTIVITY_MULTIPLE_TASK(0x08000000)`。 */
    private const val PARALLEL_FLAGS = 0x18000000

    /**
     * `am start` **被重定向到已有实例**时打的那几句。
     *
     * ★★ 这种情况**退出码是 0**、也不含 [ShizukuAmStrategy.looksLikeError] 认的那些词，
     * 光看「成功 / 失败」分不出来 —— 而它恰恰就是「主界面被 singleTask 吃掉」那一幕。
     * 认出来就当**没开成**返回 false，让调用方继续走原策略（把微信提回前台）；
     * 否则用户看到的是「点了微信，什么都没发生」。
     */
    private val REDIRECT_MARKERS =
        listOf(
            "Warning: Activity not started",
            "delivered to currently running",
            // singleTask 被重定向的另一种说法：系统把**已有那个 task** 提到前面，没有新窗口。
            "has been brought to the front",
        )

    /**
     * 这次启动要不要走「平行小窗」。
     *
     * 四个条件全满足才返回 true：
     * 1. 这台机器的小窗是 [SystemSupport.FreeformState.OURS]（见类注释：AOSP 形态没验过）；
     * 2. 目标是微信**原体**（分身住在另一个 userId 里，不在本特性范围）；
     * 3. 能读到微信 `visible=true` 且属于**这个用户**的 task，并取到它的顶层 Activity；
     * 4. 那个顶层 Activity 不是主界面，且**屏幕上还没有**微信小窗（去重，见 [snapshot]）。
     *
     * Shizuku 没连上 / 读不到 → 返回 false，静默回退原策略，不阻塞。
     */
    fun shouldLaunch(context: Context, target: LaunchTarget): Boolean {
        if (SystemSupport.freeformState(context) != SystemSupport.FreeformState.OURS) return false
        if (target.packageName != PACKAGE) return false
        if (target.userId != 0) return false
        val snapshot = snapshot(context, target.userId) ?: return false
        // 屏上已经有一扇微信小窗 → 不开第三扇（原策略会把它提起来，那才是用户想要的）。
        if (snapshot.windowVisible > 0) {
            DebugLog.info("LAUNCH_PARALLEL_SKIP", "已有 ${snapshot.windowVisible} 扇微信小窗，不再叠")
            return false
        }
        val topActivity = snapshot.visibleTopActivity ?: return false
        // 顶层是主界面 → 不触发（避免 singleTask 重定向 + 空壳 task）。
        return !topActivity.endsWith(MAIN_ACTIVITY)
    }

    /**
     * 开第二扇：shell 身份 `am start --windowingMode <当前形态> -f <NEW_TASK|MULTIPLE_TASK> -n <组件>`。
     *
     * 命令由 [ShizukuAmStrategy.buildCommand] 拼 —— **别在这里手搓**：`--user` 那一段
     * （小米上原体也要带 `--user 0`，否则会弹「选原生还是分身」）以及 flags 的口径都在那边，
     * 各写一份迟早会走样。
     *
     * 成功 = shell 退出码 0、输出不像错误、**且不是「重定向到已有实例」**。
     * 失败返回 false，调用方继续走原策略。
     * 善后（`noteLaunched` / `watchForFreeformWindow`）由 [FreeformLauncher] 的调用方
     * （`OverlayGestureService`）在 `launch()` 返回成功后统一做，这里不重复。
     */
    fun launchAndVerify(context: Context, target: LaunchTarget): Boolean {
        val command =
            ShizukuAmStrategy.buildCommand(
                target,
                FreeformProtocol.windowMode(context),
                FreeformProtocol.launchFlags(context) or PARALLEL_FLAGS,
            )
        val result = ShizukuShell.run(command, timeoutMs = 8_000L)
        val output = (result.stdout + result.stderr).trim()
        val redirected = REDIRECT_MARKERS.any { output.contains(it, ignoreCase = true) }
        val ok = result.isSuccess && !redirected && !ShizukuAmStrategy.looksLikeError(output)
        DebugLog.info(
            "LAUNCH_PARALLEL",
            "${target.flattened} exit=${result.exitCode} ok=$ok redirected=$redirected $output",
        )
        if (!ok) return false
        // 起完之后在后台复核一次（只写日志，不占「点下去 → 窗口出现」那条时间线）。
        Thread(
            {
                Thread.sleep(1_200L)
                val after = snapshot(context, target.userId)?.windowVisible ?: -1
                if (after >= 1) {
                    DebugLog.info("PARALLEL_VERIFY", "第二扇已开出，屏上微信小窗=$after")
                } else {
                    DebugLog.warn(
                        "PARALLEL_MISFIRE",
                        "第二扇没开出来（屏上小窗=$after），去 am stack list 看微信的 task",
                    )
                }
            },
            "wechat-parallel-verify",
        ).start()
        return true
    }

    /**
     * 微信 task 的现状：**一次** `am stack list` 全拿。
     *
     * - `visibleTopActivity`：`visible=true` 那条微信 task 的顶层 Activity（全限定类名）；
     * - `windowVisible`：屏幕上**看得见**、且**不是整屏**的微信 task 数（小窗，去重用）；
     * - `taskCount`：属于该用户的微信 task 总数（只进日志）。
     *
     * ## 读法
     *
     * `am stack list` 每个 task 占一行，`bounds=[l,t][r,b]` 就在这一行上，
     * `mWindowingMode` 在它**前面那行**的 `configuration={…}` 里（[modeNear] 取来只做日志）。
     *
     * ## ★★ 为什么「是不是小窗」判 bounds 而**不是** `mWindowingMode`（2026-10-20 真机）
     *
     * ColorOS 17 真机：一扇**明明是悬浮小窗**的微信（bounds=[126,319][1398,2580]，
     * 屏是 1272×2772），`am stack list` 里照样写 **`mWindowingMode=fullscreen`** ——
     * 私有模式 100 在这个字段上根本看不出来。照它判会得出「屏上没有小窗」，
     * 于是去重永远不生效、异步复核永远报 MISFIRE。
     * ⇒ 判据改成 **task 的 bounds 是不是整屏**（`[0,0][屏宽,屏高]`，横屏两个数对调）。
     *
     * ★★ **必须按 `userId=` 过滤**（项目硬规矩）：分身与原体包名一样，同一个包在两个用户
     * 里的 task 会并排出现，只按包名取会拿到**另一个用户**的那扇。
     *
     * ## 为什么只要 `visible=true`
     *
     * 微信被切到后台时 task 是 `visible=false` —— 那时点轮盘里的微信应当走原逻辑（把它拉回
     * 前台），不该走平行。`visible=true` 把「微信正在屏上显示」（全屏前台 / 自由窗）留下。
     */
    private fun snapshot(context: Context, userId: Int): Snapshot? {
        if (!ShizukuShell.hasPermission) return null
        val result =
            ShizukuShell.run(
                "am stack list 2>/dev/null | grep -B2 '$PACKAGE/'",
                timeoutMs = 4_000L,
            )
        if (!result.isSuccess) return null
        val metrics = context.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val lines = result.stdout.lineSequence().toList()
        var windowVisible = 0
        var taskCount = 0
        var visibleTop: String? = null
        for (index in lines.indices) {
            val line = lines[index]
            if (!line.contains("taskId=") || !line.contains("$PACKAGE/")) continue
            val owner = Regex("""userId=(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull()
            if (owner != userId) continue
            taskCount++
            val visible = line.contains("visible=true")
            if (visible && !isFullScreen(boundsOf(line), screenW, screenH)) windowVisible++
            if (visible && visibleTop == null) {
                visibleTop =
                    Regex("""topActivity=ComponentInfo\{$PACKAGE/([^}]+)\}""")
                        .find(line)?.groupValues?.get(1)
            }
        }
        DebugLog.info(
            "PARALLEL_SNAPSHOT",
            "tasks=$taskCount 小窗=$windowVisible top=$visibleTop",
        )
        return Snapshot(visibleTop, windowVisible, taskCount)
    }

    /** task 行上的 `bounds=[l,t][r,b]` → `(宽, 高)`；读不到返回 null（按整屏处理，不去重）。 */
    private fun boundsOf(line: String): Pair<Int, Int>? {
        val match = Regex("""bounds=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]""").find(line) ?: return null
        val (l, t, r, b) = match.destructured.toList().map { it.toIntOrNull() ?: return null }
        return (r - l) to (b - t)
    }

    /**
     * 这对宽高是不是「整屏」（横屏时宽高互换）。
     *
     * 读不到 bounds 时**当作整屏**：宁可少去重一次，也不要因为解析不出就把用户挡在门外。
     */
    private fun isFullScreen(size: Pair<Int, Int>?, screenW: Int, screenH: Int): Boolean {
        if (size == null) return true
        val (w, h) = size
        return (w == screenW && h == screenH) || (w == screenH && h == screenW)
    }

    private class Snapshot(
        val visibleTopActivity: String?,
        val windowVisible: Int,
        @Suppress("unused") val taskCount: Int,
    )

    // TODO(parallel-cleanup): 若要自动移除可能残留的空壳 task，走 Shizuku UserService
    // (bindUserService) 以 shell 身份反射调 ActivityTaskManager#removeTask(taskId)。
    // `am` 命令行没有这个能力（见类注释）。当前靠「检测正确 → 主界面不触发」预防，空壳不会产生。
}
