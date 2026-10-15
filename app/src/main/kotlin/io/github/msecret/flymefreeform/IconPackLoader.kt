package io.github.msecret.flymefreeform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Xml
import org.xmlpull.v1.XmlPullParser

/**
 * 第三方图标包加载器。
 *
 * 图标包（Nova / ADW 系支持的独立 app）遵循一套约定：
 * - 声明 `org.adw.launcher.THEMES`（ADW 系）或 `com.novalauncher.THEME`（Nova 系）中的**一个**；
 * - 把「组件」映射到「图标 drawable 名」的表放在 **`res/xml/appfilter.xml`**，
 *   有些图标包只放 **`assets/appfilter.xml`**（两种都得试）；
 * - 图标本身在 `res/drawable*` 或 `res/mipmap*` 里。
 *
 * 这里读图标包 app 的资源，按映射给应用换图标。
 *
 * ## ★ 判据是「**声明了那两条 action**」，不是「有没有 appfilter 资源」
 *
 * 就是 `["org.adw.launcher.THEMES", "com.novalauncher.THEME"]` 这两条 action 拿去
 * `queryIntentActivities`，再按包名去重。**别再看资源名**——真机踩过：
 * `com.oplus.safecenter`（OPPO 安全中心）**恰好也有一个叫 `appfilter` 的 xml 资源**
 * （`0x7f140000 xml/appfilter`，跟图标毫无关系），按资源名判就会把它列成图标包；
 * 用户选了它 → 映射解析为空 → **「选了图标包但图标一个都没变」**。
 *
 * 至于 ColorOS 主题的**系统图标集**，是另一套东西，走 [SystemIconSet]（那个能读，不用 root）。
 */
object IconPackLoader {

    private const val ICON_SIZE_DP = 48f

    /**
     * 归一化时**兜底**用的目标占比 —— 自适应图标前景的 **72/108 = 0.667**。
     *
     * 正常路径上调用方会传**实测值**（拿这个应用自己的默认图标量出来的占比，见
     * [contentFraction]），那才是用户要的「拉到和真正图标一样大」。只有在量不出来
     * （默认图标读不到 / 全透明）时才退回这个理论值。
     *
     * 顺带它也在正圆裁剪的安全范围内（内切圆边长有 70.7%）。详见 [scaleTo]。
     */
    internal const val VISUAL_FRACTION = 2f / 3f

    /**
     * 量内容边界时的 alpha 阈值。
     *
     * 取 32 而不是 1：图标普遍带一点投影 / 光晕（alpha 个位数到十几），
     * 按 1 算会把那圈虚影也算成内容 —— 边界凭空大一圈，图标反而被缩得更小。
     */
    private const val ALPHA_MIN = 32

    /** 兜底合成里三类素材的槽位号，只用来给变体挑选用（见 [art]），本身无含义。 */
    private const val SLOT_BACK = 0
    private const val SLOT_UPON = 1
    private const val SLOT_MASK = 2

    /**
     * 兜底合成时原图标内容占比的**下限**。
     *
     * 算出来比它还小就说明素材离谱了（底板内容框极小 / `scale` 写得极小），
     * 那时原图标会缩成一个点 —— 不如给个地板值让它还能看。
     */
    private const val MIN_FALLBACK_FRACTION = 0.2f

    /**
     * 图标包 App 声明自己身份用的 action。
     *
     * ADW / Nova 系的老约定：ADW 系、Nova 系各一条，图标包至少声明其中之一。
     */
    private val PACK_ACTIONS = listOf("org.adw.launcher.THEMES", "com.novalauncher.THEME")

    /** assets 下的映射文件名（有些图标包只放这里，见 [loadMapping]）。 */
    private const val ASSET_FILTER = "appfilter.xml"

    /** 名字表的文件名（`res/xml/drawable.xml`，有些包只放 assets，见 [loadDrawableNames]）。 */
    private const val DRAWABLE_FILE = "drawable.xml"

    /**
     * 扫描所有已安装应用，找出**能用的图标包**：声明了 [PACK_ACTIONS] 之一，且真的带得出映射表。
     *
     * 两个条件都要：只声明 action 却拿不出映射的（例如空模板包）列出来也没用；反过来只看资源名会误报。
     */
    fun findIconPacks(context: Context): List<String> {
        val pm = context.packageManager
        return PACK_ACTIONS
            .flatMap { action ->
                runCatching { pm.queryIntentActivities(Intent(action), 0) }.getOrDefault(emptyList())
            }
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()
            .filter { hasMapping(pm, it) }
            .sorted()
    }

    /**
     * 这个包名现在还算不算「能用的图标包」。
     *
     * 用在两处：设置页检测、以及**存量错值的自愈**——老版本可能把 `com.oplus.safecenter`
     * 这种假阳性存进了 `iconPackPackage`，不清掉就会一直压着图标集不生效。
     */
    fun isUsable(context: Context, iconPackPkg: String): Boolean =
        iconPackPkg.isNotBlank() && hasMapping(context.packageManager, iconPackPkg)

    /** 有没有映射表：资源 `xml/appfilter` **或** `assets/appfilter.xml`。 */
    private fun hasMapping(pm: PackageManager, packageName: String): Boolean =
        runCatching {
            val resources = pm.getResourcesForApplication(packageName)
            resources.getIdentifier("appfilter", "xml", packageName) != 0 ||
                resources.assets.open(ASSET_FILTER).use { true }
        }.getOrDefault(false)

    /**
     * 读图标包的 appfilter，返回「组件 key → drawable 名」的映射。
     *
     * 组件 key 归一化成 `包名/类名`（见 [normalizeComponent]），`resolveIcon` 会拿三种形态去试
     * （完整类名 / 短类名 / **只写包名**——图标包里只写包名的条目很常见）。
     *
     * 来源两条，**先资源后 assets**：真机那份 `com.happyrich.os` 两条都有
     * （`res/xml/appfilter.xml` 531KB + `assets/appfilter.xml` 407KB），而有些图标包只放 assets。
     */
    fun loadMapping(context: Context, iconPackPkg: String): Map<String, String> =
        loadPackFile(context, iconPackPkg).mapping

    /**
     * 一份 `appfilter.xml` 解析出来的全部内容。
     *
     * 映射表与兜底声明**同一次解析**读出来 —— 真机那份 `com.happyrich.os` 的 appfilter 有 531KB，
     * 为了拿兜底声明再解一遍是白花的时间。
     */
    internal class PackFile(
        val mapping: Map<String, String>,
        /** 这个包对「它没画的应用」的交代；`null` = 它有声明但一条素材都没读到。 */
        val fallback: Fallback?,
    )

    /**
     * 解析并缓存一份 appfilter（映射表 + 兜底声明）。
     *
     * 缓存键带 **`lastUpdateTime`**：图标包一升级，映射表和素材都可能整体换掉。
     * 只按包名缓存的话，用户换完包得重启应用才生效。
     */
    internal fun loadPackFile(context: Context, iconPackPkg: String): PackFile {
        val pm = context.packageManager
        val stamp =
            runCatching { pm.getPackageInfo(iconPackPkg, 0).lastUpdateTime }.getOrDefault(0L)
        val cacheKey = "$iconPackPkg@$stamp"
        synchronized(packFileCacheLock) { packFileCache[cacheKey] }?.let { return it }

        val mapping = LinkedHashMap<String, String>()
        val sink = FallbackSink()
        runCatching {
            val resources = pm.getResourcesForApplication(iconPackPkg)
            val id = resources.getIdentifier("appfilter", "xml", iconPackPkg)
            if (id != 0) {
                val parser = resources.getXml(id)
                try {
                    parse(parser, mapping, sink)
                } finally {
                    runCatching { parser.close() }
                }
            }
        }.onFailure { DebugLog.warn("ICON_PACK_PARSE_FAILED", "$iconPackPkg(res)", it) }
        if (mapping.isEmpty()) {
            runCatching {
                val resources = pm.getResourcesForApplication(iconPackPkg)
                resources.assets.open(ASSET_FILTER).use { stream ->
                    val parser = Xml.newPullParser()
                    parser.setInput(stream, null)
                    parse(parser, mapping, sink)
                }
            }.onFailure { DebugLog.warn("ICON_PACK_PARSE_FAILED", "$iconPackPkg(assets)", it) }
        }
        val file = PackFile(mapping, sink.snapshot())
        synchronized(packFileCacheLock) { packFileCache[cacheKey] = file }
        return file
    }

    /** [PackFile] 的缓存。装几个图标包就有几个条目，不用做淘汰。 */
    private val packFileCache = HashMap<String, PackFile>()
    private val packFileCacheLock = Any()

    private fun parse(
        parser: XmlPullParser,
        into: MutableMap<String, String>,
        fallback: FallbackSink,
    ) {
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item" -> {
                        val component = parser.getAttributeValue(null, "component")
                        val drawable = parser.getAttributeValue(null, "drawable")
                        if (!component.isNullOrBlank() && !drawable.isNullOrBlank()) {
                            into[normalizeComponent(component)] = drawable
                        }
                    }
                    // 底下这三条和 `<item>` **混在同一个文件里**（都在 `<resources>` 下），
                    // 所以顺手一起读，见 [Fallback]。
                    "iconback" -> fallback.backs += imgAttributes(parser)
                    "iconupon" -> fallback.upons += imgAttributes(parser)
                    "iconmask" -> fallback.masks += imgAttributes(parser)
                    "scale" ->
                        parser.getAttributeValue(null, "factor")?.toFloatOrNull()?.let { factor ->
                            fallback.scale = factor
                        }
                }
            }
            event = parser.next()
        }
    }

    /**
     * 取一条声明上的全部素材名 —— 规范里变体写作 `img1` / `img2` / …（launcher 随机挑一张）。
     *
     * 判据用「属性名以 `img` 开头」而不是写死 `img1`：有的包只写一个 `img`，
     * 也有人从 `img0` 开始编号。
     */
    private fun imgAttributes(parser: XmlPullParser): List<String> =
        (0 until parser.attributeCount)
            .filter { parser.getAttributeName(it).startsWith("img") }
            .mapNotNull { parser.getAttributeValue(it)?.trim() }
            .filter { it.isNotEmpty() }

    /** 解析途中攒兜底声明的四个可变槽，解析完固化成 [Fallback]。 */
    private class FallbackSink {
        val backs = mutableListOf<String>()
        val upons = mutableListOf<String>()
        val masks = mutableListOf<String>()
        var scale = 1f

        fun snapshot(): Fallback? =
            Fallback(backs, upons, masks, scale).takeIf { it.usable }
    }

    /**
     * 读图标包的 `drawable.xml`，返回「drawable 名 → **应用名**」。
     *
     * ## 这是什么
     *
     * 糖果（CandyBar）系图标包的**标准文件**，和 `appfilter.xml` 分工明确：
     *
     * - `appfilter.xml`：「组件 → drawable」；
     * - `drawable.xml`：「drawable → **名字** + 分类」，形如
     *   `<item drawable="ic_camera" name="相机" />`。
     *
     * 名字那一栏在规范里是**可选**的，但主流包都填（2026-10-10 抽查
     * `sshuijian/colorlineiconpack`：283 条里 282 条有 `name`）。
     *
     * ## 为什么需要它
     *
     * 图标集里**绝大多数条目是本机没装的应用**，而「包名 → 应用名」系统里根本查不到
     * （`PackageManager` 只认已安装；详见 `ToolIconLibrary.builtinNames` 的注释）。
     * 公开数据集要么只有 Google 自家应用、要么许可证不适合打包 —— **而这份名字就在用户
     * 自己装的图标包里**，不用联网、不会随我们发版过时，还能覆盖那个包支持的全部应用。
     *
     * ⚠️ 名字是**按 drawable** 给的，同一个 drawable 常被一批应用共用（「相机」「浏览器」
     * 这种通用图标）—— 用的时候要判一下，别把 30 个相机应用都标成「相机」，
     * 见 `ToolIconLibrary.packCandidates` 里的 `GENERIC_DRAWABLE_MIN`。
     */
    fun loadDrawableNames(context: Context, iconPackPkg: String): Map<String, String> {
        val pm = context.packageManager
        val result = LinkedHashMap<String, String>()
        runCatching {
            val resources = pm.getResourcesForApplication(iconPackPkg)
            val id = resources.getIdentifier("drawable", "xml", iconPackPkg)
            if (id != 0) {
                val parser = resources.getXml(id)
                try {
                    parseNames(parser, result)
                } finally {
                    runCatching { parser.close() }
                }
            }
        }.onFailure { DebugLog.warn("ICON_PACK_PARSE_FAILED", "$iconPackPkg(drawable.xml)", it) }
        if (result.isEmpty()) {
            runCatching {
                val resources = pm.getResourcesForApplication(iconPackPkg)
                resources.assets.open(DRAWABLE_FILE).use { stream ->
                    val parser = Xml.newPullParser()
                    parser.setInput(stream, null)
                    parseNames(parser, result)
                }
            }.onFailure {
                DebugLog.warn("ICON_PACK_PARSE_FAILED", "$iconPackPkg(drawable.xml/assets)", it)
            }
        }
        return result
    }

    /** 只管 `<item drawable="x" name="y"/>`；`<category>` 和别的属性都不看。 */
    private fun parseNames(parser: XmlPullParser, into: MutableMap<String, String>) {
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "item") {
                val drawable = parser.getAttributeValue(null, "drawable")
                val name = parser.getAttributeValue(null, "name")
                if (!drawable.isNullOrBlank() && !name.isNullOrBlank()) {
                    into[drawable.trim()] = name.trim()
                }
            }
            event = parser.next()
        }
    }

    /**
     * 把 appfilter 里的 `ComponentInfo{包名/类名}` 或 `包名/类名` 归一化成 `包名/类名`。
     *
     * 只写包名（没有 `/`）的条目原样保留——它本身就是「这个包的所有入口都用这张图」的意思。
     */
    private fun normalizeComponent(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("ComponentInfo{")) {
            return trimmed.removePrefix("ComponentInfo{").removeSuffix("}")
        }
        return trimmed
    }

    /**
     * 按 drawable 名加载图标包里的图标，转成 Bitmap。
     *
     * [targetPx] 是位图的目标边长，由调用方按「所有绘制处的最大需求」算
     * （见 `AppCatalog.iconTargetPx`）。**一定要传对**：这里早先写死 48dp（≈126px），
     * 而面板网格要 192px —— 那个位图会被**放大 1.5 倍**画出来，看着比别的图标糊。
     * 传 0 时退回 48dp，只是兜底，正常路径都该显式传。
     *
     * [targetFraction] 是**要缩放到多大**（内容占边长的比例）。调用方传「这个应用的默认图标
     * 实测出来的占比」就能把图标包图标拉到和真正图标一样大，见 [contentFraction]。
     */
    fun loadIcon(
        context: Context,
        iconPackPkg: String,
        drawableName: String,
        targetPx: Int = 0,
        targetFraction: Float = VISUAL_FRACTION,
    ): Bitmap? {
        val pm = context.packageManager
        return runCatching {
            val resources = pm.getResourcesForApplication(iconPackPkg)
            val id = resources.getIdentifier(drawableName, "drawable", iconPackPkg)
                .let { if (it != 0) it else resources.getIdentifier(drawableName, "mipmap", iconPackPkg) }
            if (id == 0) return null
            val drawable = resources.getDrawable(id, null)
            scaleTo(drawable, resolveTarget(context, targetPx), targetFraction = targetFraction)
        }.getOrNull()
    }

    /**
     * 图标包对「**它自己没画过的应用**」的默认处理素材 —— `appfilter.xml` 顶部那几条声明。
     *
     * ## 这是什么
     *
     * 图标包不会给世界上每个应用都画一张图。对它没画的应用，规范允许它声明一套**兜底素材**，
     * 由 launcher 拿**应用自己的图标**和这些素材现合成一张出来（规范里叫
     * autogenerated fallback icon）：
     *
     * ```xml
     * <resources>
     *   <iconback img1="iconback1" img2="iconback2" />  <!-- 垫在原图标**下面**（底板） -->
     *   <iconmask img1="iconmask" />                    <!-- 用来**裁**原图标（定形状） -->
     *   <iconupon  img1="iconupon" />                   <!-- 盖在原图标**上面**（光泽等） -->
     *   <scale factor="1.0" />                          <!-- 原图标相对底板内容框的缩放 -->
     * </resources>
     * ```
     *
     * 每条可以有多个变体（`img1` / `img2` / …），规范说 launcher **任意挑一张**；三样都是可选的。
     * 一个都不声明的包（**西方包基本都是**：Arcticons 五万多条 `<item>` 里一条都没有）说明它
     * 不打算兜底 —— 那时调用方照旧往下退（系统图标集 → 应用原图）。
     *
     * ★★ 一句话：**这是图标包作者对「我没画的应用」的交代**。用户 2026-10-10 问的
     * 「图标包有自己默认的处理读不到的图标的方式，我们对没有的能按每个图标包默认方式吗」
     * 说的就是它。
     *
     * ⚠️ 别把这张表和 `ToolIconLibrary` 混起来：那边是**手动挑单张图**（人选的），
     * 这里是对**整批没命中的应用**自动兜底（包选的）。
     */
    internal class Fallback(
        val backs: List<String>,
        val upons: List<String>,
        val masks: List<String>,
        val scale: Float,
    ) {
        /** 一条素材都没有 ⇒ 这个包不提供兜底，调用方别插这一级。 */
        val usable: Boolean get() = backs.isNotEmpty() || upons.isNotEmpty() || masks.isNotEmpty()

        /** `factor` 缺省 / 写 0（真机上有包这么写，意思是"别缩"）都按 1 算。 */
        val effectiveScale: Float get() = if (scale > 0f) scale else 1f
    }

    /**
     * 把 [base]（应用自带的原图标）与图标包声明的兜底素材合成出一张图标。
     *
     * 合成顺序就是规范那一套：**底板 → 原图标 → 覆盖层**，遮罩用来裁原图标。
     *
     * ## 原图标占多大（这里唯一需要判断的地方）
     *
     * `<scale factor>` 的 1.0 是「铺满底板」，但底板图**通常自带留白 / 阴影**（中间才是内容区）
     * —— 原图标照整张画布铺上去会把底板的边全盖掉。所以先量底板的**内容框**（[alphaBounds]），
     * 原图标就缩到「内容框 × scale」，居中。
     *
     * 量不出内容框（底板整张全透明 / 没底板）时退回整张画布 —— 那时 `scale` 就是唯一的调节。
     *
     * ## 变体怎么挑
     *
     * 规范说「随机挑一张」，但真随机会让**同一个应用在轮盘和面板里长得不一样**（两边各合成一次）。
     * 所以这里按 [seed]（传包名）做**稳定哈希**：同一个应用永远挑到同一套变体。
     *
     * @param seed 稳定挑变体用的种子，调用方传**应用包名**。
     * @return 合成结果；素材一条都取不到时 `null`（调用方照旧往下退）。
     */
    internal fun fallbackIcon(
        context: Context,
        iconPackPkg: String,
        fallback: Fallback,
        base: Drawable,
        targetPx: Int,
        seed: String,
    ): Bitmap? {
        if (targetPx <= 0 || !fallback.usable) return null
        return runCatching {
            val back = art(context, iconPackPkg, fallback.backs, targetPx, seed, SLOT_BACK)
            val upon = art(context, iconPackPkg, fallback.upons, targetPx, seed, SLOT_UPON)
            val mask = art(context, iconPackPkg, fallback.masks, targetPx, seed, SLOT_MASK)
            if (back == null && upon == null && mask == null) return null

            val frame = back?.content
            val frameSize =
                (frame?.let { maxOf(it.width(), it.height()) } ?: targetPx).coerceAtLeast(1)
            val fraction =
                (frameSize * fallback.effectiveScale / targetPx)
                    .coerceIn(MIN_FALLBACK_FRACTION, 1f)

            // 原图标先归一化到「底板内容框 × scale」：各应用原图留白差别很大，
            // 不归一化的话同一套底板下会一大一小（和 `AppCatalog.resolveIcon` 同一个口径）。
            var glyph = normalizeVisual(renderTo(base, targetPx), targetPx, fraction)
            if (mask != null) glyph = applyMask(glyph, mask)

            val out = Bitmap.createBitmap(targetPx, targetPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val full = RectF(0f, 0f, targetPx.toFloat(), targetPx.toFloat())
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            back?.let { canvas.drawBitmap(it.bitmap, null, full, paint) }
            canvas.drawBitmap(glyph, 0f, 0f, paint)
            upon?.let { canvas.drawBitmap(it.bitmap, null, full, paint) }
            out
        }.getOrNull()
    }

    /**
     * 一张渲染好的兜底素材。
     *
     * [content] 是它的内容框（用来算原图标该占多大），[keepsCenter] 是**遮罩极性**
     * （只对 `iconmask` 有意义，见 [applyMask]）。
     */
    private class Art(val bitmap: Bitmap, val content: Rect?, val keepsCenter: Boolean)

    private val artCache = HashMap<String, Art?>()
    private val artCacheLock = Any()

    /**
     * 取某一类兜底素材里的一张（按 [seed] 稳定挑变体）并渲染成 `targetPx`。
     *
     * ★ 必须缓存：一次目录加载里有几十上百个应用没被图标包命中，每个都要用同一批底板 ——
     * 每次现解 [name] 对应的资源再缩放，光这一步就是几百毫秒。
     * `null`（没声明这一类 / 名字取不到）**也缓存**，免得每个应用都去 `getIdentifier` 撞一次空。
     */
    private fun art(
        context: Context,
        iconPackPkg: String,
        names: List<String>,
        targetPx: Int,
        seed: String,
        slot: Int,
    ): Art? {
        if (names.isEmpty()) return null
        // 负数取模在 Kotlin 里会留负数，先加一次 size 再取模。
        val index = (((seed.hashCode() * 31 + slot) % names.size) + names.size) % names.size
        val key = "$iconPackPkg|$slot|$index|$targetPx"
        synchronized(artCacheLock) {
            if (artCache.containsKey(key)) return artCache[key]
        }
        val art =
            runCatching {
                val resources = context.packageManager.getResourcesForApplication(iconPackPkg)
                val name = names[index]
                val id =
                    resources.getIdentifier(name, "drawable", iconPackPkg).let {
                        if (it != 0) it else resources.getIdentifier(name, "mipmap", iconPackPkg)
                    }
                if (id == 0) return@runCatching null
                val bitmap = renderFit(resources.getDrawable(id, null), targetPx)
                Art(bitmap, alphaBounds(bitmap), maskKeepsCenter(bitmap))
            }.getOrNull()
        synchronized(artCacheLock) { artCache[key] = art }
        return art
    }

    /**
     * 按**保持宽高比**铺进 `size`×`size` 居中的位图。
     *
     * 不能直接用 [renderTo]（它 `setBounds` 铺满、非正方形会被拉变形）—— 底板素材未必是方的，
     * 拉变形就露馅了。
     */
    private fun renderFit(drawable: Drawable, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val intrinsicW = drawable.intrinsicWidth.takeIf { it > 0 } ?: size
        val intrinsicH = drawable.intrinsicHeight.takeIf { it > 0 } ?: size
        val scale = minOf(size.toFloat() / intrinsicW, size.toFloat() / intrinsicH)
        val width = (intrinsicW * scale).toInt().coerceAtLeast(1)
        val height = (intrinsicH * scale).toInt().coerceAtLeast(1)
        val left = (size - width) / 2
        val top = (size - height) / 2
        drawable.setBounds(left, top, left + width, top + height)
        drawable.draw(canvas)
        return out
    }

    /**
     * 用 [mask] 裁 [glyph]。
     *
     * ## ★★ 极性必须**看图判断**，不能写死（2026-10-10 用户报「圆都是空白的」）
     *
     * 规范把 iconmask 描述成「mask 黑色处不显示、透明处显示」，但**落到 PNG 上有两种写法**，
     * 而且真机图标包里两种都存在：
     *
     * - **形状遮罩**（常见）：中间是**不透明**的形状（圆 / 方），四周透明
     *   ⇒ 要**保留中间** ⇒ [`PorterDuff.Mode.DST_IN`]（结果 alpha = 两者之积，mask 透明处被去掉）；
     * - **反相遮罩**：中间**透明**、四周是不透明背景（作者把「要挖掉的部分」画成了背景）
     *   ⇒ 要**擦掉不透明那圈** ⇒ [`PorterDuff.Mode.DST_OUT`]（src 的不透明度把 dst 擦掉）。
     *
     * 写死其中一个，另一种就会**正好反**：本该裁成圆的应用图标会变成「中间被挖了个圆洞」，
     * 看上去就是从**应用图标**里**包着一个空白的圆**（用户原话）—— 圆是 iconback 露出来的，
     * 而圆里那块本该放图标的地方被我们挖掉了。
     *
     * 判据取**中心区 vs 角落区**的平均 alpha（见 [maskKeepsCenter]）：图标形状一定**居中**，
     * 所以「更不透明的那一侧 = 要保留的那一侧」。这个判据与包无关、与写法无关。
     */
    private fun applyMask(glyph: Bitmap, mask: Art): Bitmap {
        val out = runCatching { glyph.copy(Bitmap.Config.ARGB_8888, true) }.getOrNull() ?: return glyph
        val canvas = Canvas(out)
        val mode = if (mask.keepsCenter) PorterDuff.Mode.DST_IN else PorterDuff.Mode.DST_OUT
        val paint =
            Paint(Paint.FILTER_BITMAP_FLAG).apply {
                xfermode = PorterDuffXfermode(mode)
            }
        canvas.drawBitmap(
            mask.bitmap,
            null,
            RectF(0f, 0f, out.width.toFloat(), out.height.toFloat()),
            paint,
        )
        return out
    }

    /**
     * mask 的**中心**是不是比角落更不透明（⇒ 中间是要保留的形状）。
     *
     * 取中心 1/4 方块和左上一小块的平均 alpha 比大小。尺寸太小（< 8）没得比，
     * 按「形状遮罩」算（最常见的那种）。
     */
    private fun maskKeepsCenter(mask: Bitmap): Boolean =
        runCatching {
            val width = mask.width
            val height = mask.height
            if (width < 8 || height < 8) return true

            fun averageAlpha(x0: Int, y0: Int, x1: Int, y1: Int): Int {
                val span = x1 - x0
                if (span <= 0 || y1 <= y0) return 0
                val row = IntArray(span)
                var sum = 0L
                var count = 0
                for (y in y0 until y1) {
                    mask.getPixels(row, 0, span, x0, y, span, 1)
                    for (pixel in row) {
                        sum += pixel ushr 24
                        count++
                    }
                }
                return if (count == 0) 0 else (sum / count).toInt()
            }

            val center =
                averageAlpha(width * 3 / 8, height * 3 / 8, width * 5 / 8, height * 5 / 8)
            // 角落取**最外那一小块**（1/32 ~ 1/8）：判据要的是「画面四角」，取太靠里的话
            // 遇到「形状几乎铺满画布」的遮罩（圆直径≈画布）会误采到形状内部，判反。
            val corner = averageAlpha(width / 32, height / 32, width / 8, height / 8)
            center >= corner
        }.getOrDefault(true)

    /** 调用方没给目标尺寸时的兜底：48dp。 */
    private fun resolveTarget(context: Context, targetPx: Int): Int =
        if (targetPx > 0) {
            targetPx
        } else {
            (ICON_SIZE_DP * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        }

    /**
     * 把任意 Drawable 画成 `target`×`target` 的位图。
     *
     * ## 视觉大小怎么对齐（用户 2026-10-08：「有些图标会很大，有些图标包会很小」）
     *
     * 图标的**视觉大小**和它的**绘制边界**不是一回事，各来源差得很远：
     * 自适应图标是 108 的网格、前景只画在中间的 72（视觉 66.7%）；而图标包 / 系统图标集里的
     * PNG 铺满自己的画布，留多少边距全看作者。一律铺满，出来就是「有的撑满、有的缩在中间」。
     *
     * ## ★ 判据是**来源**，不是 Drawable 的类型
     *
     * [alignVisual] 由**调用方按来源**决定：
     *
     * - **应用原图标（「默认」来源）→ `false`**：它跟着系统 / 桌面走，是**基准**，一律不动。
     *   这不是"偷懒"，而是**类型判不出来**：真机上 ColorOS 的
     *   `PackageManager.getApplicationIcon()` 返回的**并不是** `AdaptiveIconDrawable`，
     *   所以 `drawable is AdaptiveIconDrawable` 那种判据永远为 false —— 结果是「默认」图标
     *   也被拉去按整图边界归一化了。而各图标的整图内容占比本来就不一样（有的自带不透明背景、
     *   有的留边），归一化的倍率自然各不相同 ⇒ 同类之间反而被缩得**参差不齐**
     *   （用户当场报的「默认的图标都变得不一样大」，前两版都没修好就是因为这个）。
     * - **图标包 / 系统图标集 → `true`**：它们是用户主动换的一套，要和基准**对齐**。
     *
     * ## 对齐到多少：[targetFraction]
     *
     * 默认 [VISUAL_FRACTION]（2/3，即自适应图标前景的 72/108）。但**更好的做法是传实测值**
     * —— 见 [contentFraction]：`AppCatalog` 会拿**这个应用自己的默认图标**量出一个占比传进来，
     * 那就是用户说的「**把图标包的大小强制拉到和真正图标一样大**」。
     *
     * ⚠️ 画的时候**必须带 `FILTER_BITMAP_FLAG`**：不带的话缩放走最近邻采样，图标会发糊。
     * 真机上「图标很糊」的真因一直是采样方式，跟位图尺寸无关。
     */
    internal fun scaleTo(
        drawable: Drawable,
        target: Int,
        alignVisual: Boolean = true,
        targetFraction: Float = VISUAL_FRACTION,
    ): Bitmap {
        val raw =
            when {
                // BitmapDrawable 走 createScaledBitmap：它内部带过滤，比走一遍 draw 快。
                drawable is BitmapDrawable && drawable.bitmap != null ->
                    Bitmap.createScaledBitmap(drawable.bitmap, target, target, true)
                else -> renderTo(drawable, target)
            }
        if (!alignVisual) return raw
        // 自适应图标自带标准视觉（108 网格 / 前景 72），本来就是基准，不用也对不齐。
        if (drawable is AdaptiveIconDrawable) return raw
        return normalizeVisual(raw, target, targetFraction)
    }

    /**
     * 把 drawable 铺满 `size`×`size` 的位图，**不做任何归一化**。
     *
     * `internal` 而不是 `private`：`AppCatalog` 要拿它把**默认图标**渲染出来量尺寸
     * （见 [contentFraction]），好让图标包图标对齐到它。
     */
    internal fun renderTo(drawable: Drawable, size: Int): Bitmap {
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return result
    }

    /**
     * 量出 [bitmap] 里「有内容的那块」占整张的比例（较长边 ÷ 边长）。
     *
     * 量不出来时返回 null：整张全透明、尺寸非法、或者拿不到像素（`Config.HARDWARE`）。
     *
     * ★ 这是「强制拉到和真正图标一样大」的量尺：`AppCatalog` 先拿**这个应用自己的默认图标**
     * 量一个占比，再把图标包 / 系统图标集的图标归一化到同一个占比。
     */
    internal fun contentFraction(bitmap: Bitmap): Float? {
        val bounds = alphaBounds(bitmap) ?: return null
        val size = maxOf(bitmap.width, bitmap.height)
        if (size <= 0) return null
        return maxOf(bounds.width(), bounds.height()).toFloat() / size
    }

    /**
     * 视觉归一化：把 [raw] 里「有内容的那块」缩到 `target × fraction` 并居中。
     *
     * 只处理**非自适应**的图标（legacy 应用图标、图标包 / 系统图标集的 PNG）——
     * 自适应图标是基准、不走这里，理由见 [scaleTo]。
     *
     * 量不出内容（整张全透明）时**原样返回**——那种图标本来就该是空的，
     * 不该被放大成一片噪声。
     */
    private fun normalizeVisual(raw: Bitmap, target: Int, fraction: Float): Bitmap {
        val content = alphaBounds(raw) ?: return raw
        val contentSize = maxOf(content.width(), content.height())
        if (contentSize <= 0) return raw
        val scale = target * fraction / contentSize
        val output = Bitmap.createBitmap(target, target, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val width = raw.width * scale
        val height = raw.height * scale
        // 让**内容的中心**落在画布中心，而不是 raw 的中心——内容本来就可能偏在一边
        // （图标作者画歪、或前景层自带不对称边距）。
        val left = target / 2f - content.exactCenterX() * scale
        val top = target / 2f - content.exactCenterY() * scale
        canvas.drawBitmap(raw, null, RectF(left, top, left + width, top + height), paint)
        return output
    }

    /**
     * 扫出「有像素」的包围盒（alpha ≥ [ALPHA_MIN]）；整张全透明时返回 null。
     *
     * 阈值取 32 而不是 1：图标普遍带一点**投影 / 光晕**（alpha 个位数到十几），
     * 按 1 算会把那圈虚影也算进内容里 —— 边界凭空大一圈，图标反而被缩得更小。
     *
     * 整段包在 `runCatching` 里：`getPixels` 遇到 `Config.HARDWARE` 的位图会抛
     * （它拿不到像素）。那种情况就当「量不出内容」处理 —— 退化成不归一化，
     * 总好过为一个图标崩掉整个目录加载。
     */
    private fun alphaBounds(bitmap: Bitmap): Rect? =
        runCatching {
            val width = bitmap.width
            val height = bitmap.height
            if (width <= 0 || height <= 0) return null
            val row = IntArray(width)
            var left = width
            var top = height
            var right = -1
            var bottom = -1
            for (y in 0 until height) {
                bitmap.getPixels(row, 0, width, 0, y, width, 1)
                for (x in 0 until width) {
                    if (row[x] ushr 24 >= ALPHA_MIN) {
                        if (x < left) left = x
                        if (x > right) right = x
                        if (y < top) top = y
                        if (y > bottom) bottom = y
                    }
                }
            }
            if (right < left || bottom < top) return null
            Rect(left, top, right + 1, bottom + 1)
        }.getOrNull()

    /**
     * 解码出来可能不是正方形（系统图标集里就有），先居中裁方，再缩放并归一化。
     *
     * [targetFraction] 的含义见 [scaleTo] / [contentFraction]：传「这个应用的默认图标实测占比」
     * 就能把系统图标集里那枚图标拉到和真正图标一样大。
     */
    internal fun squareScale(
        bitmap: Bitmap,
        target: Int,
        targetFraction: Float = VISUAL_FRACTION,
    ): Bitmap {
        val size = minOf(bitmap.width, bitmap.height)
        if (size <= 0) return bitmap
        val square =
            if (bitmap.width == bitmap.height) bitmap
            else runCatching {
                Bitmap.createBitmap(bitmap, (bitmap.width - size) / 2, (bitmap.height - size) / 2, size, size)
            }.getOrDefault(bitmap)
        if (target <= 0) return square
        val scaled =
            if (square.width == target) {
                square
            } else {
                runCatching { Bitmap.createScaledBitmap(square, target, target, true) }.getOrDefault(square)
            }
        return normalizeVisual(scaled, target, targetFraction)
    }
}
