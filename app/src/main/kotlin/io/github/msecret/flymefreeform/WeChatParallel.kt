package io.github.msecret.flymefreeform

/**
 * **平行小窗：微信已在子页面时，再开一扇独立的微信小窗。**
 *
 * ## 触发条件（用户 2026-11 确认）
 *
 * 当前台应用是微信、且顶层 Activity **不是**主界面（[MAIN_ACTIVITY]）——即在子页面
 * （公众号文章 / 微信文档等）—— 这一次点击走「开第二扇」；其余情况行为不变。
 *
 * ## 平台层已验证可用
 *
 * 真机：`am start --windowingMode 100 -f 0x18000000 -n com.tencent.mm/.ui.LauncherUI`，
 * 第一扇在**子页面**时能开出一个**独立第二实例**；第一扇在**主界面**时会被 singleTask
 * 重定向回老实例、并留下一个空壳 task。所以本特性**只在子页面触发**——主界面不带
 * `MULTIPLE_TASK`，空壳自然不会产生。
 *
 * `0x18000000` = `FLAG_ACTIVITY_NEW_TASK(0x10000000) | FLAG_ACTIVITY_MULTIPLE_TASK(0x08000000)`。
 *
 * ## 为什么只走 shell（`am start`），不走 `DirectStartStrategy`
 *
 * `startActivity + MULTIPLE_TASK` 在 ColorOS 私有模式 100 下成不成没验过；而 shell 那条
 * 已经真机验证可用。所以平行这条路**只走 [ShizukuShell]**，跳过直启。
 *
 * ## 清空壳 task
 *
 * 正常流程里**不会**产生空壳（主界面根本不触发）。这里再挂一道**兜底**：起完之后等小窗
 * 落定，数一下微信的自由窗 task 数；若没比触发前多（说明判错了、其实是在主界面被重定向），
 * 就把那个非自由窗的微信 task 的 id 记进日志。
 *
 * ⚠️ 真机 `am help task` 确认：`am task` 只有 lock / lock stop / resizeable / resize，
 * **没有 per-task 的 remove**；`am stack remove` 移的是整块 stack 且拿不到 stackId，也不适用。
 * 所以这里**不做**会误伤的 `am force-stop`（那会把正在用的那扇一起杀）——只记日志。
 * 空壳是空 task，会在内存压力下被系统回收，或用户在最近任务里手动划掉。要真正做到自动
 * 移除，得走 Shizuku UserService 反射调 `ActivityTaskManager.removeTask`（见类尾 TODO）。
 *
 * ## 其他应用
 *
 * 本实现只对微信（[WECHAT] / [MAIN_ACTIVITY]）生效。同样的判据与启动方式对其他应用也
 * 适用：把包名与主界面 Activity 换成目标应用的那一对即可。本 PR 仅微信经真机验证，
 * 未做全量适配；其余应用表现（尤其 singleTask 重定向、多实例是否独立）需逐个测。
 */
object WeChatParallel {

    /** 微信包名。与 [SystemTools.PACKAGE_WECHAT] 一致，这里自带一份避免互相依赖。 */
    private const val WECHAT = "com.tencent.mm"

    /**
     * 微信主界面 Activity（`com.tencent.mm/.ui.LauncherUI`，singleTask）。
     *
     * 顶层是它 = 在主界面 → 不开第二扇。只判类名尾段，避免把 `com.tencent.mm.ui.LauncherUI`
     * 这种全名写死成带点号的格式。
     */
    private const val MAIN_ACTIVITY = "LauncherUI"

    /** 平行启动用的 flags：`NEW_TASK | MULTIPLE_TASK`。 */
    private const val PARALLEL_FLAGS = 0x18000000

    /**
     * 这次启动要不要走「平行小窗」。
     *
     * 三个条件全满足才返回 true：
     * 1. 目标是微信（原体，userId == 0；分身不在本特性范围）；
     * 2. 微信有 `visible=true` 的 task，且能取到它的顶层 Activity（走 [visibleWeChatTopActivity]）；
     * 3. 那个顶层 Activity 不是主界面 [MAIN_ACTIVITY]（即在子页面）。
     *
     * Shizuku 没连上时返回 false —— 静默回退到原策略，不阻塞。
     */
    fun shouldLaunch(target: LaunchTarget): Boolean {
        if (target.packageName != WECHAT) return false
        if (target.userId != 0) return false
        val topActivity = visibleWeChatTopActivity() ?: return false
        // 顶层是主界面 → 不触发（避免 singleTask 重定向 + 空壳 task）。
        // 用 endsWith 而不是 contains：避免把名字里恰好含 LauncherUI 的子页面误判成主界面。
        return !topActivity.endsWith(MAIN_ACTIVITY)
    }

    /**
     * 当前【可见】的微信 task 的顶层 Activity 类名（全限定，如 `com.tencent.mm.ui.LauncherUI`）。
     *
     * 走 Shizuku 读 `am stack list`，取 `visible=true` 的那条微信 task 行里的
     * `topActivity=ComponentInfo{...}`——那是该 task **当前顶层** Activity，正是用户在看的那一屏。
     *
     * ## 为什么不用 `dumpsys activity activities` 的 `mResumedActivity`
     *
     * 真机（ColorOS 16）实测：`dumpsys activity activities` 里**没有** `mResumedActivity` 这个字段
     * （grep 返回空）。而 `am stack list` 的 task 行直接带 `topActivity=`，一步到位，且与项目
     * [taskSnapshot] 同一条命令、同一种解析。
     *
     * ## 为什么只要 `visible=true` 的
     *
     * 微信被切到后台时 task 是 `visible=false`——那时点轮盘里的微信应当走原逻辑（把它拉回前台），
     * 不该走平行。`visible=true` 把「微信正在屏上显示」（全屏前台 / 自由窗）这一批留下。
     */
    private fun visibleWeChatTopActivity(): String? {
        if (!ShizukuShell.hasPermission) return null
        val result =
            ShizukuShell.run(
                "am stack list 2>/dev/null | grep 'com.tencent.mm/' | grep 'visible=true'",
                timeoutMs = 4_000L,
            )
        if (!result.isSuccess) return null
        // 形如： taskId=1113: com.tencent.mm/com.tencent.mm.ui.LauncherUI ... visible=true
        //        topActivity=ComponentInfo{com.tencent.mm/com.tencent.mm.plugin...TmplWebViewMMUI}
        val line =
            result.stdout.lineSequence().firstOrNull { it.contains("topActivity=") } ?: return null
        val match = Regex("""topActivity=ComponentInfo\{com\.tencent\.mm/([^}]+)\}""").find(line) ?: return null
        return match.groupValues[1]
    }

    /**
     * 开第二扇：shell 身份 `am start --windowingMode 100 -f 0x18000000 -n <组件>`。
     *
     * 成功 = shell 退出码 0 且输出不像错误。失败返回 false，调用方继续走原策略。
     * 善后（`noteLaunched` / `watchForFreeformWindow`）由 [FreeformLauncher] 的调用方
     * （`OverlayGestureService`）在 `launch()` 返回成功后统一做，这里不重复。
     */
    private fun launchParallel(target: LaunchTarget): Boolean {
        val command =
            "am start --windowingMode ${ColorOsFreeform.WINDOWING_MODE}" +
                " -f 0x${PARALLEL_FLAGS.toString(16)}" +
                " -n ${target.flattened}"
        val result = ShizukuShell.run(command, timeoutMs = 8_000L)
        val output = (result.stdout + result.stderr).trim()
        val ok = result.isSuccess && !ShizukuAmStrategy.looksLikeError(output)
        DebugLog.info(
            "LAUNCH_PARALLEL",
            "${target.flattened} exit=${result.exitCode} ok=$ok $output",
        )
        return ok
    }

    /**
     * 起完之后的「开出来没有 + 没开就清空壳」。
     *
     * 记下触发前的微信自由窗 task 数，起完等 1.5 秒再数一次：没变多 = 判错了（其实在主界面，
     * 被重定向）→ 尝试清空壳。整段在后台线程，不挡主线程。
     */
    fun launchAndVerify(target: LaunchTarget): Boolean {
        val before = freeformWeChatTaskCount()
        if (!launchParallel(target)) return false
        Thread(
            {
                Thread.sleep(1_500L)
                val after = freeformWeChatTaskCount()
                DebugLog.info("PARALLEL_VERIFY", "微信自由窗 before=$before after=$after")
                if (after <= before) {
                    DebugLog.warn(
                        "PARALLEL_MISFIRE",
                        "第二扇没开出来（可能其实在主界面），尝试清空壳 task",
                    )
                    cleanupPhantomTasks()
                }
            },
            "wechat-parallel-verify",
        ).start()
        return true
    }

    /**
     * 微信的自由窗 task 数（`mWindowingMode=freeform` 或 ColorOS 的 100）。
     *
     * `am stack list` 里 `mWindowingMode` 在 task 行的**上一行**（项目 [taskSnapshot] 同款
     * `-B1` 解析），所以先 `-B1 com.tencent.mm/` 把「RootTask 行 + task 行」成对取出，
     * 再在其中数 `mWindowingMode=freeform`。
     *
     * 读不到 / 解析不出时返回 0（只影响兜底判断，不影响启动本身）。
     */
    private fun freeformWeChatTaskCount(): Int {
        if (!ShizukuShell.hasPermission) return 0
        // 一条 shell 同时数 freeform 和 100（ColorOS 显示成哪个没验过，两个都算）。
        val result =
            ShizukuShell.run(
                "am stack list 2>/dev/null | grep -B1 'com.tencent.mm/' | grep -cE 'mWindowingMode=(freeform|100)'",
                timeoutMs = 4_000L,
            )
        if (!result.isSuccess) return 0
        return result.stdout.trim().toIntOrNull() ?: 0
    }

    /**
     * 清微信的空壳 task：找出**非自由窗**的微信 task，记进日志。
     *
     * ⚠️ 见类注释：`am help task` 真机确认 `am task` 没有 remove 子命令，所以这里**只检测+记日志**，
     * 不做任何会误伤正在用的那扇的动作（绝不 `am force-stop`）。要自动移除见类尾 TODO。
     */
    private fun cleanupPhantomTasks() {
        if (!ShizukuShell.hasPermission) return
        val result =
            ShizukuShell.run(
                "am stack list 2>/dev/null | grep -B1 'com.tencent.mm/'",
                timeoutMs = 4_000L,
            )
        if (!result.isSuccess) return
        // 把成对的「RootTask 行 / task 行」扫一遍：task 行取 taskId，RootTask 行取 windowingMode。
        val phantomIds = ArrayList<Int>()
        val lines = result.stdout.lineSequence().toList()
        for (i in lines.indices) {
            val line = lines[i]
            if (!line.contains("com.tencent.mm/")) continue
            val taskId = Regex("""taskId=(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val prev = if (i > 0) lines[i - 1] else ""
            val mode = Regex("""mWindowingMode=(\w+)""").find(prev)?.groupValues?.get(1) ?: ""
            // 自由窗（freeform / 100）保留；其余视为空壳候选。
            if (mode != "freeform" && mode != "100") phantomIds += taskId
        }
        if (phantomIds.isEmpty()) {
            DebugLog.info("PARALLEL_CLEANUP", "没有可清的空壳 task")
            return
        }
        // am 无 per-task remove：只记 id，供诊断。空壳是空 task，系统会在内存压力下回收。
        DebugLog.warn(
            "PARALLEL_CLEANUP",
            "检测到空壳 task（am 无 remove 命令，仅记录）：ids=$phantomIds。" +
                "如需自动移除，见 WeChatParallel 类尾 TODO（Shizuku UserService 调 removeTask）。",
        )
    }

    // TODO(parallel-cleanup): 若要真正做到自动移除空壳 task，走 Shizuku UserService
    // (bindUserService) 以 shell 身份反射调 ActivityTaskManager#removeTask(taskId)。
    // `am` 命令行没有这个能力（见上）。当前靠「检测正确 → 主界面不触发」预防，空壳不会产生。
}
