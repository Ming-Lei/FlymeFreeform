package io.github.msecret.flymefreeform

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import java.io.Closeable
import java.text.Collator
import java.util.Locale

/**
 * 「**工具的图标从安装的图标集里挑一张**」—— 这条路线的全部逻辑（用户 2026-10-10 提的需求）。
 *
 * ## 为什么必须「手动挑」，做不到「自动跟随」
 *
 * 图标集是**按应用**组织的（见下表），而工具是我们自己编的**伪组件**
 * （`SystemTools.PACKAGE/<toolId>`，见 [SystemTools]）—— 图标集里**根本没有这个键**。
 * 所以「工具自动用图标集里的图标」没有可查的映射，只能让用户**点一张**。
 *
 * | 来源 | 映射表 | 键 |
 * | --- | --- | --- |
 * | 第三方图标包 | `appfilter.xml`（[IconPackLoader]） | 组件（`包名/类名`） |
 * | ColorOS 系统图标集 | `/data/theme/icons` 的 `allApps.xml`（[SystemIconSet]） | **包名** |
 * | Flyme 系统图标集 | `/system/customizecenter/theme`（[FlymeIconSet]） | 包名（反扫文件名得到） |
 *
 * ## ★★ 可挑的是**全部来源**，不是「当前生效的那一套」
 *
 * 用户 2026-10-10 的原话：「我想的是可选系统的，和**任意一个自己安装的主题的**」。
 * 所以候选按 [Source] 分组 —— 一个系统图标集 + **每一个已安装的图标包各一份**，
 * 由调用方（选择器里的「来源」那一行）来切。这一点很重要：**不能只列当前生效的那套**，
 * 否则「我想让手电筒用 A 图标包的图、但全局来源是 B」就做不到。
 *
 * ## ref：存进 prefs 的那个标识
 *
 * `"<kind>:<value>"`，两种：
 *
 * - `system:<包名>` —— 系统图标集里的某一条（它就是按包名索引的）；
 * - `pack:<图标包包名>|<原属应用包名>|<drawable 名>` —— **某一个**图标包里的某一张。
 *
 * ★ 图标包那个包名**必须**写进去 —— 它决定「去哪张表取图」；原属应用那个包名只是让 ref
 * **自描述**（看得出这张图本来是哪个应用的），**不参与尺寸计算**（所有工具图标共用
 * [Session.targetFraction] 那一个值）。老格式 `pack:<drawable 名>` 仍认，退回当前生效的图标包。
 *
 * ## 候选标签：应用名 → 内置对照表 → 包名
 *
 * 图标集里那几千张图的 drawable 名是 `ic_launcher_tencent_mm` 这种，给人看没有意义。
 * 而两张映射表里**都带着包名**（图标包的组件键里、系统图标集的键本身就是），
 * 拿包名去 `PackageManager` 要一个应用名就有了 —— 于是选择器里是一格格**应用图标 + 应用名**，
 * 既能搜也能认（用户实际要挑的多半就是「微信那张图」）。
 *
 * ★★ **本机没装的那些照样列出来**（用户 2026-10-10：「为什么看不到 `com.tencent.qq` 这种包名？
 * 没装的不要删，不然意义何在」）。图标集是面向**所有**应用的表（真机 1439 条），机器上只装了
 * 一两百个 —— 但那些没装的应用的图标**照样能挑**（图就在图标集里，不依赖应用本身），
 * 包名也是**可搜**的（搜 `com.tencent.qq` 或 `qq` 都命中，见选择器里的 `matches`）。
 *
 * ★ 它们的名字走**四级**兜底，见 [displayName]：
 * **系统查到的 > 内置对照表 > 图标包自带的 name > 包名**。两块名字来源各补一段：
 *
 * - **内置对照表** [builtinNames]（人维护的 `res/raw/app_name_map.txt`）—— 逐包核对，覆盖主流应用；
 * - **图标包自带的 name** —— `res/xml/drawable.xml` 里的 `name`，见 [IconPackLoader.loadDrawableNames]，
 *   就在用户自己装的图标包里，不用联网、不会随我们发版过时。
 *
 * ⚠️ 「包名 → 应用名」系统里**根本没有这个查询**（`PackageManager` 只认已安装；详见
 * [builtinNames] 的注释），所以别指望能自动查出来。
 *
 * ⚠️ **别再插「拿 drawable 名当名字」那一级**（`wechat` / `taobao` 那种）—— 用户 2026-10-10
 * 明确否过：它是**猜**的，而包名至少是**真的、可搜的**（`matches` 连 ref 一起搜）。
 * 兜底换成 `ic_launcher_xxx` 那种**资源名**同样不行（那是用户报的「名字都是 iconxxxxxxx」）。
 */
object ToolIconLibrary {

    /** 系统图标集（ColorOS / Flyme 各一套，谁开出来用谁）。 */
    const val KIND_SYSTEM = "system"

    /** 第三方图标包。 */
    const val KIND_PACK = "pack"

    private const val SEPARATOR = ':'

    /** `pack` 的 ref 里「包名」与「drawable 名」之间的分隔符。 */
    private const val INNER = '|'

    /** 渲染缓存最多留几张。48dp@3x 的一张约 80KB，64 张 ≈ 5MB。 */
    private const val MAX_RENDER_CACHE = 64

    /** 系统图标集在「来源」列表里的名字。 */
    private const val SYSTEM_LABEL = "系统图标集"

    /**
     * 一张可挑的图。
     *
     * [ref] 是存进 prefs 的标识（见类注释）；[label] 是给人看的名字 —— **应用名**，
     * 本机没装的应用退回**包名**（`com.tencent.qq`）；[named] = 这个应用**本机装了**
     * （所以 [label] 是能认出来的应用名），只用来排序，把认得出的排在前面。
     */
    data class Candidate(val ref: String, val label: String, val named: Boolean)

    /**
     * 一个可挑的**来源**：系统图标集，或**某一个**已安装的图标包。
     *
     * 候选是**按需加载**的（[candidates]），不是 [ToolIconLibrary.open] 里一次性全解出来 ——
     * 玩主题的人能装十几个图标包，每个都要解一份几百 KB 的 `appfilter.xml`，
     * 一次全解完才让用户进对话框是不可接受的。切到哪个来源才解哪个，解过就常驻。
     */
    class Source internal constructor(
        /** 给人看的名字（「系统图标集」/ 图标包那个 app 的名字）。 */
        val label: String,
        private val loader: () -> List<Candidate>,
    ) {
        @Volatile
        private var cache: List<Candidate>? = null

        /** 这个来源里的全部候选，按应用名排序。**首次调用会解映射表，放后台线程。** */
        fun candidates(): List<Candidate> {
            cache?.let { return it }
            val loaded = loader()
            cache = loaded
            return loaded
        }
    }

    /**
     * 一次「打开图标集」的会话：**来源清单 + 按 ref 渲染**。
     *
     * 之所以要成会话：读系统图标集要解 `allApps.xml`（真机 1439 条），渲染时还要它的 `ZipFile`
     * 才取得到图 —— 每张现开一次等于把那份表解几十遍。会话把这些攥住，渲染一张只是解码 + 归一化。
     *
     * ⚠️ 用完要 [close]；常驻的那一份由 [shared] 管，别自己开一个又忘了关。
     */
    class Session internal constructor(
        private val context: Context,
        private val colorOs: SystemIconSet.Session?,
        private val flyme: FlymeIconSet.Session?,
        /** 老 ref（`pack:` 后面没有 `|` 的那种）退回哪个图标包，见 [render]。 */
        private val legacyPack: String,
        /** 可挑的来源；有系统图标集时它排在最前。 */
        val sources: List<Source>,
        /** 默认停在哪个来源（**当前生效**的那一套）—— 选择器一开就落在这里。 */
        val defaultSourceIndex: Int,
    ) : Closeable {

        /**
         * 按 [ref] 渲染一张图标；取不到（换了主题 / 卸了图标包 / ref 不合法）返回 **null**。
         *
         * [targetPx] 是目标边长。
         *
         * ## ★★ 大小：**所有工具图标共用同一个占比**（[targetFraction]）
         *
         * 用户 2026-10-10：「和别的图标一样，根据主动呼出轮盘里面的那个大小」，紧接着又报
         * 「把识屏的换成了它里面的一个图标，识屏明显小一圈」。轮盘里所有图标都被画进**同一个
         * 方块**，所以看上去的差别全在「图里的内容占多少」—— 这个数**必须和线稿那一支一致**
         * （`SystemTools.drawIcon` 用的就是同一个 [AppCatalog.typicalIconFraction]）。
         *
         * ⚠️ 曾经按「这张图原来所属的那个应用」精确对齐（`defaultIconFraction(appPkg)`）——
         * **已废弃**：每个应用自己的占比差得很远，于是挑过的工具和没挑过的线稿一大一小。
         *
         * 但**归一化方式仍要照抄 `AppCatalog.resolveIcon` 的分支**，别自作主张统一处理：
         *
         * | 来源 | 怎么做 | 为什么 |
         * | --- | --- | --- |
         * | 图标包 | 归一化到 [targetFraction] | **自适应图标那一支加载器故意不归一化**，这里补一刀 |
         * | ColorOS 图标集 | 归一化到 [targetFraction] | `SystemIconSet.loadIcon` 内部已经做了 |
         * | **Flyme 图标集** | **原样、不归一化** | 它是底板+前景+遮罩的**满幅**合成，那正是桌面观感；再归一化会比同一个应用在轮盘里的图标小一圈 |
         */
        fun render(ref: String, targetPx: Int): Bitmap? {
            if (targetPx <= 0) return null
            val value = valueOf(ref)
            if (value.isBlank()) return null
            return runCatching { renderInner(ref, value, targetPx) }.getOrNull()
        }

        private fun renderInner(ref: String, value: String, targetPx: Int): Bitmap? =
            when (kindOf(ref)) {
                KIND_PACK -> renderPack(value, targetPx)

                KIND_SYSTEM -> {
                    val fraction = targetFraction(targetPx)
                    if (colorOs != null) {
                        // 这一支内部已经 `squareScale(…, fraction)` 归一化好了。
                        colorOs.loadIcon(value, targetPx, fraction)
                    } else {
                        // ★★ Flyme 那一支**不能**再归一化，理由见 [render] 的表格。
                        flyme?.loadIcon(value, targetPx, fraction) { null }
                    }
                }

                else -> null
            }

        /**
         * `pack:<图标包包名>|<原属应用包名>|<drawable 名>`。
         *
         * ⚠️ 第 2 段（原属应用包名）**不参与尺寸计算** —— 所有工具图标共用同一个
         * [targetFraction]（见那里的说明）。留着它是为了让 ref 自描述：看得出这张图本来是
         * 哪个应用的（排查时有用）。
         *
         * ⚠️ 老格式 `pack:<drawable 名>`（只有一段）仍认：图标包退回**当前生效**的那个。
         */
        private fun renderPack(value: String, targetPx: Int): Bitmap? {
            val parts = value.split(INNER)
            val packPkg: String
            val drawable: String
            when (parts.size) {
                3 -> {
                    packPkg = parts[0]
                    drawable = parts[2]
                }
                // 老格式：只有 drawable 名。
                1 -> {
                    packPkg = legacyPack
                    drawable = parts[0]
                }
                else -> return null
            }
            if (packPkg.isBlank() || drawable.isBlank()) return null
            val fraction = targetFraction(targetPx)
            val icon = IconPackLoader.loadIcon(context, packPkg, drawable, targetPx, fraction) ?: return null
            // 加载器对**自适应图标**是故意不归一化的（见 `IconPackLoader.scaleTo`），
            // 这里补一刀 —— 否则同一个图标包里「自适应的那种」会比「PNG 那种」大一圈。
            return runCatching { IconPackLoader.squareScale(icon, targetPx, fraction) }.getOrDefault(icon)
        }

        /**
         * 目标占比 = **本机典型的应用图标占比**（[AppCatalog.typicalIconFraction]）。
         *
         * ★★ **所有工具图标共用这一个数**（挑来的图 / 没挑过的线稿都一样）。两处必须同源，
         * 否则「挑过的那个工具」会比其余几个大一圈或小一圈 —— 用户 2026-10-10 报的正是这个：
         * 「把识屏的换成了它里面的一个图标，识屏明显小一圈」。当时挑来的图用的是**那个应用
         * 自己**的占比、而线稿用的是本机典型值，两个数不一样。
         *
         * ⚠️ **别改回「按原属应用精确对齐」**：那样八个工具各是一个尺寸、看上去参差不齐，
         * 而用户要的是**所有工具图标一样大**（「和别的图标一样」）。线稿那一支在
         * `SystemTools.drawIcon` 里用的就是这个函数同一个值。
         */
        private fun targetFraction(targetPx: Int): Float =
            AppCatalog.typicalIconFraction(context, targetPx)

        override fun close() {
            runCatching { colorOs?.close() }
            runCatching { flyme?.close() }
        }
    }

    fun systemRef(pkg: String): String = "$KIND_SYSTEM$SEPARATOR$pkg"

    /**
     * `pack:<图标包包名>|<原属应用包名>|<drawable 名>`。
     *
     * ★ 为什么要带**原属应用包名**：挑来的图标要**和轮盘里那个应用一样大**，而那个尺寸是按
     * 「该应用自己的默认图标实测占比」算的（见 [Session.render]）。不记包名就只能用一个
     * 平均值，对不上具体应用 —— 而「和别的图标一样大」正是用户 2026-10-10 要的。
     */
    fun packRef(packPkg: String, appPkg: String, drawable: String): String =
        "$KIND_PACK$SEPARATOR$packPkg$INNER$appPkg$INNER$drawable"

    fun kindOf(ref: String): String = ref.substringBefore(SEPARATOR)

    fun valueOf(ref: String): String = ref.substringAfter(SEPARATOR, "")

    /**
     * 把一个 ref 说成一句人话（「系统图标集」/「OS 图标包」），给设置页那一行的副标题用。
     *
     * 取不到（图标包已卸载）时退回泛称 —— 那一行本来也只是个提示，不值得为它报错。
     */
    fun describe(context: Context, ref: String): String =
        if (kindOf(ref) == KIND_PACK) {
            val pkg = valueOf(ref).substringBefore(INNER)
            if (pkg.isBlank()) "图标包" else packLabel(context, pkg)
        } else {
            SYSTEM_LABEL
        }

    /**
     * 开一份**新的**会话。
     *
     * 这里只做两件相对便宜的事：开系统图标集（一份 zip + 解它的映射）、列出已安装的图标包名字。
     * **各图标包的映射表不在这里解**（那是 [Source.candidates] 的事，见类注释）。
     *
     * 一个来源都凑不出来时返回 null —— 调用方据此说一句「这台机器上没有可读的图标集」，
     * 而不是弹一个空的选择器。
     */
    fun open(context: Context): Session? {
        val app = context.applicationContext
        // 两套系统图标集是互斥的（各家的路径只有自己才有），谁先开出来用谁 —— 与
        // `AppCatalog.ThemedIcons.open` 同一套判据，别在这里另立一个 ROM 判断。
        val colorOs = runCatching { SystemIconSet.open() }.getOrNull()
        val flyme = if (colorOs == null) runCatching { FlymeIconSet.open(app) }.getOrNull() else null
        val activePack = SettingsStore(app).iconPackPackage

        val sources = mutableListOf<Source>()
        // 1) 系统图标集：映射**已经**在 [SystemIconSet.open] 里解好了，候选直接建，不用再解一遍。
        val system = systemCandidates(app, colorOs, flyme)
        if (system.isNotEmpty()) sources += Source(SYSTEM_LABEL) { system }
        // 2) 每个已安装的图标包一个来源。★ 顺序就是 `findIconPacks` 的顺序（按包名排序，稳定）。
        var defaultIndex = 0
        IconPackLoader.findIconPacks(app).forEach { pkg ->
            if (pkg == activePack) defaultIndex = sources.size
            sources += Source(packLabel(app, pkg)) { packCandidates(app, pkg) }
        }
        if (sources.isEmpty()) {
            runCatching { colorOs?.close() }
            runCatching { flyme?.close() }
            return null
        }
        // 当前生效的图标包没被列出来（比如已卸载）⇒ 退回第一个来源（通常就是系统图标集）。
        if (defaultIndex >= sources.size) defaultIndex = 0
        return Session(app, colorOs, flyme, activePack, sources, defaultIndex)
    }

    // ---- 常驻会话（给渲染路径用） ----

    private val lock = Any()
    private var cachedKey: String? = null
    private var cachedSession: Session? = null

    /** 渲染过的位图（key = `ref@边长`），FIFO 淘汰。 */
    private val renderCache = LinkedHashMap<String, Bitmap>()

    /**
     * 常驻会话：**同一个签名下只开一次**。
     *
     * 为什么必须常驻：[SystemTools.load] 一次要给十来个工具出图标，而设置页那排样式预览
     * 一次要画「8 种样式 × 全部工具」。每张都现开一次会话等于把系统图标集那份表解几十遍。
     *
     * 签名里带上两个图标集文件的**修改时间**（[SystemIconSet.modifiedAt] /
     * [FlymeIconSet.modifiedAt]）与当前图标包 —— 用户换主题 / 换图标包时自动作废重开，
     * 不会攥着旧 inode 不放（理由见那两个 `modifiedAt` 的注释）。
     */
    fun shared(context: Context): Session? = synchronized(lock) {
        val app = context.applicationContext
        val key = signature(app)
        if (cachedSession == null || key != cachedKey) {
            runCatching { cachedSession?.close() }
            cachedSession = open(app)
            cachedKey = key
            renderCache.clear()
        }
        cachedSession
    }

    /**
     * 按 [ref] 渲染一张，带缓存 —— **[SystemTools.drawIcon] 唯一该走的入口**。
     *
     * 失败返回 null（调用方据此静默退回线稿）。
     */
    fun render(context: Context, ref: String, targetPx: Int): Bitmap? {
        val key = "$ref@$targetPx"
        synchronized(lock) { renderCache[key]?.let { return it } }
        val bitmap = shared(context)?.render(ref, targetPx) ?: return null
        synchronized(lock) {
            renderCache[key] = bitmap
            while (renderCache.size > MAX_RENDER_CACHE) {
                val oldest = renderCache.keys.firstOrNull() ?: break
                renderCache.remove(oldest)
            }
        }
        return bitmap
    }

    /** 缓存签名：当前图标包 + 两份图标集文件的修改时间。 */
    private fun signature(context: Context): String {
        val pack = SettingsStore(context).iconPackPackage
        return "$pack|${SystemIconSet.modifiedAt()}|${FlymeIconSet.modifiedAt()}"
    }

    // ---- 候选枚举 ----

    /**
     * 系统图标集里的候选（ColorOS 的键是包名；Flyme 没有映射表，反扫 zip 文件名）。
     *
     * ★★ **没装的应用也要列出来，名字用「包名」**（用户 2026-10-10：「为什么看不到
     * `com.tencent.qq` 这种包名？没装的不要删，不然意义何在」）。
     *
     * 图标集是面向**所有**应用的表（ColorOS 真机 1439 条），机器上只装了一两百个 ——
     * 但那些**没装的应用的图标照样能挑**（图标集里就有那张图，不依赖应用本身），
     * 而且包名本身是**可搜**的（`com.tencent.qq`、`qq` 都搜得到，见选择器里的 `matches`）。
     * 删掉它们等于把图标集的一大半直接扔掉。
     *
     * ⚠️ 兜底名字**必须用包名，不能用 drawable 名**（`ic_launcher_tencent_qq` 这种）：
     * 包名是这张表**自己用的键**、看得出是谁、也搜得到；drawable 名是作者随手起的，
     * 既认不出也搜不到 —— 那才是用户报的「名字似乎不对」。
     */
    private fun systemCandidates(
        context: Context,
        colorOs: SystemIconSet.Session?,
        flyme: FlymeIconSet.Session?,
    ): List<Candidate> {
        val pm = context.packageManager
        val out = LinkedHashMap<String, Candidate>()
        colorOs?.mapping?.keys?.forEach { pkg ->
            val (label, named) = displayName(context, pm, pkg)
            out[systemRef(pkg)] = Candidate(systemRef(pkg), label, named)
        }
        flyme?.packages()?.forEach { pkg ->
            val (label, named) = displayName(context, pm, pkg)
            out[systemRef(pkg)] = Candidate(systemRef(pkg), label, named)
        }
        return sorted(out.values)
    }

    /**
     * 某个图标包里的候选：映射表的 value 就是它的图标全集。
     *
     * 按 **ref**（= 包名 + 原属应用 + drawable）去重；标签取**组件所属应用**的名字，
     * 拿不到就用**包名**（理由同 [systemCandidates]：包名可认可搜，drawable 名不行）。
     *
     * ★ 不去重成「一张图一条」：同一个 drawable 被两个应用共用时（很少但确实有），两条的
     * **目标尺寸**不一样（各自对齐到自己那个应用），所以它们本来就是两个可选项。
     */
    private fun packCandidates(context: Context, packPkg: String): List<Candidate> {
        val pm = context.packageManager
        val mapping =
            runCatching { IconPackLoader.loadMapping(context, packPkg) }.getOrDefault(emptyMap())
        // 图标包**自己带的**名字表（`res/xml/drawable.xml` 的 `name`），见
        // [IconPackLoader.loadDrawableNames] —— 它就在用户机器上，不用联网也不会过时。
        val packNames =
            runCatching { IconPackLoader.loadDrawableNames(context, packPkg) }.getOrDefault(emptyMap())
        // 每张图被多少个**不同应用**用到 —— 用来识别「通用图标」（见 [GENERIC_DRAWABLE_MIN]）。
        val users = HashMap<String, MutableSet<String>>()
        mapping.forEach { (component, drawable) ->
            users.getOrPut(drawable) { HashSet() }.add(component.substringBefore('/'))
        }
        val out = LinkedHashMap<String, Candidate>()
        mapping.forEach { (component, drawable) ->
            val appPkg = component.substringBefore('/')
            val ref = packRef(packPkg, appPkg, drawable)
            if (out.containsKey(ref)) return@forEach
            // ⚠️ 通用图标的名字是**分类名**（「相机」「浏览器」）不是应用名 —— 跳过它，
            //    否则几十个相机应用会全被标成「相机」，列表反而更难认。
            val generic = (users[drawable]?.size ?: 0) >= GENERIC_DRAWABLE_MIN
            val packName =
                packNames[drawable]?.takeIf { !generic && isMeaningfulName(it) }
            val (label, named) = displayName(context, pm, appPkg, packName)
            out[ref] = Candidate(ref, label, named)
        }
        return sorted(out.values)
    }

    /**
     * 一张图被**多少个不同应用**用到，就算「通用图标」（不采信它的名字）。
     *
     * 5 是折中：真·应用专属的图标基本 1:1（一个包一张图），而「相机 / 浏览器 / 邮件 / 音乐」
     * 这类通用图标会被几十个包共用 —— 中间留一段余量。
     * 判据与处理见 [packCandidates]。
     */
    private const val GENERIC_DRAWABLE_MIN = 5

    /**
     * 图标包 `drawable.xml` 里那个 `name` 是不是**有意义的名字**。
     *
     * ## 为什么要判
     *
     * 图标包制作工具批量生成时会在 `name` 里留下**占位序号**。真机 `com.happyrich.os` 的
     * `drawable.xml`：1837 条里 **163 条是纯数字**，其中 `name="1"` 一条就占了 **112 个不同的
     * drawable** —— 而且它们**全部被 `appfilter.xml` 引用**，所以会整片出现在选择器里
     * （用户 2026-10-10：「为什么 os 图标包有那么多名字是 1 的」）。
     *
     * ## 判据：纯数字按**位数**切
     *
     * 不能「纯数字就丢」——**同一张表里 `115`（115网盘）/ `360` / `10086`（中国移动）
     * 都是真正的应用名**，丢了反而损失信息。占位序号的形态是「1、2、3…16」这种小整数，
     * 所以：**1~2 位纯数字不采信**（退回包名 —— 真的、可搜的），**3 位以上保留**。
     *
     * ⚠️ 这**不是**「从 drawable 名猜应用名」那种凭空造的做法（用户 2026-10-10 明确否过）——
     * 这里只是**拒绝明显没信息量的数据**，退回的是真包名，不会编造。
     */
    private fun isMeaningfulName(name: String): Boolean {
        val value = name.trim()
        if (value.isEmpty()) return false
        if (value.all { it.isDigit() }) return value.length >= 3
        // 既没字母也没汉字（「-」「...」这类）同样没有信息量。
        return value.any { it.isLetter() || it in '\u4e00'..'\u9fff' }
    }

    /**
     * 排序：**本机装了应用的排前面**（那些名字是能认出来的应用名），没装的（名字是包名）排后面，
     * 各自按名字排。
     *
     * ★ 为什么不能只按名字排：包名是 ASCII、应用名多是中文，而 `Collator` 在中文环境下
     * **ASCII 排在 CJK 前面** ⇒ 不分组的话第一页六十条全是 `com.*`，一眼看过去像没装应用一样
     *（图标集里本机没装的条目占绝大多数）。
     */
    private fun sorted(list: Collection<Candidate>): List<Candidate> {
        val collator = Collator.getInstance(Locale.getDefault())
        return list.sortedWith { a, b ->
            if (a.named != b.named) {
                if (a.named) -1 else 1
            } else {
                collator.compare(a.label, b.label)
            }
        }
    }

    /**
     * 内置的「包名 → 应用名」对照表（`res/raw/app_name_map.txt`），**读一次就常驻**。
     *
     * ## 为什么需要它
     *
     * 图标集是面向**所有**应用的表（真机 1439 条），里面绝大多数条目本机**没装** —— 而 Android
     * 的 `PackageManager` **只认已安装的应用**：没装就 `getApplicationInfo` 抛异常，何况名字本来
     * 就要从 APK 的资源里读，APK 都没了。图标集自己**也不带名字**（ColorOS 的 `allApps.xml`
     * 只有「文件名 + 包名」、图标包的 `appfilter.xml` 只有「组件 + drawable 名」）。
     * 所以「包名 → 应用名」系统里**没有任何现成答案**，只能自带一张表（用户 2026-10-10 定）。
     *
     * ⚠️ 它是**兜底**，不是主路：已安装的一律走 [appLabel]（永远最新、也最准）。
     * ⚠️ 表里**只该有有把握的条目** —— 写错会把某个包标成不相干的 app，比不写更糟。
     * 维护约定写在文件抬头。
     */
    @Volatile
    private var builtinNameCache: Map<String, String>? = null

    private fun builtinNames(context: Context): Map<String, String> {
        builtinNameCache?.let { return it }
        val loaded =
            runCatching {
                context.resources.openRawResource(R.raw.app_name_map)
                    .bufferedReader(Charsets.UTF_8)
                    .useLines { lines ->
                        lines.mapNotNull { raw ->
                            val line = raw.trim()
                            if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
                            // 分隔符是**第一个** `=`；包名里不会有它，应用名里也可能有。
                            val at = line.indexOf('=')
                            if (at <= 0 || at == line.length - 1) {
                                null
                            } else {
                                line.substring(0, at).trim() to line.substring(at + 1).trim()
                            }
                        }.toMap()
                    }
            }.getOrDefault(emptyMap())
        builtinNameCache = loaded
        return loaded
    }

    /**
     * 界面上的名字 + 「这个名字是不是真的认得出」。优先级：
     * **系统查到的（已安装） > [builtinNames] 内置表 > 图标包自带的名字 > 包名本身**。
     *
     * ★ 内置表**排在图标包名字前面**：那张表是**逐包**核对过的（精确），而图标包
     * `drawable.xml` 里那个 `name` 是**逐 drawable** 的 —— 同一个图标常被一批应用共用
     * （「相机」这种通用图标），精度低一档。两者都没有才退回包名。
     *
     * ⚠️ **别再插「拿 drawable 名当名字」那一级**（2026-10-10 用户明确否掉）：看起来
     * `wechat` 比 `com.tencent.mm` 好认，但它是**猜**的 —— 名字表里没写 `name` 的包
     * （Arcticons / Frosted 这类西方包）本来就没有可用的名字，包名至少是**真的、可搜的**
     * （`matches` 连 ref 一起搜）。宁可显示真包名，不要显示猜出来的 slug。
     *
     * 返回的 `Pair.second` 就是 [Candidate.named]（排序用：有真名字的排前面）。
     */
    private fun displayName(
        context: Context,
        pm: PackageManager,
        pkg: String,
        packName: String? = null,
    ): Pair<String, Boolean> {
        appLabel(pm, pkg)?.let { return it to true }
        builtinNames(context)[pkg]?.let { return it to true }
        packName?.let { return it to true }
        return pkg to false
    }

    /** 图标包那个 app 自己的名字（「来源」列表里显示的就是它）。 */
    private fun packLabel(context: Context, packPkg: String): String =
        appLabel(context.packageManager, packPkg) ?: packPkg

    /**
     * 包名 → 应用名；**没装 / 查不到就返回 null**，由调用方退回**包名**（不是 drawable 名）。
     *
     * ⚠️ 退回包名这一条别改：图标集里的条目大多数是**本机没装的应用**（面向所有应用的表），
     * 包名是那张表自己的键、能认也能搜；drawable 名（`ic_launcher_tencent_qq`）认不出也搜不到，
     * 正是用户 2026-10-10 报的「名字似乎不对，都是 iconxxxxxxx」。
     */
    private fun appLabel(pm: PackageManager, pkg: String): String? =
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
}
