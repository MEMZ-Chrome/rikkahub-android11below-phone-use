package me.rerere.rikkahub.phoneuse

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Xml
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.StringReader
import kotlin.math.roundToInt

private const val MAX_ELEMENTS = 150
private const val MAX_LABEL_CHARS = 80

// 没有自己文字的可点击容器，用里面的前几段文字当名字
private const val MAX_LABEL_PARTS = 3
private const val JPEG_QUALITY = 80

// /data/local/tmp 是 shell 和 root 都能读写的地方，不用碰共享存储
private const val DUMP_PATH = "/data/local/tmp/rikkahub_ui.xml"

// 点击目标后等输入框拿到焦点，再往里送文字
private const val FOCUS_DELAY_MS = 250L
private const val CLIPBOARD_DELAY_MS = 200L
private const val DUMP_RETRY_DELAY_MS = 400L
private const val TAP_MAX_MS = 120L

// KEYCODE_PASTE：让拿到焦点的输入框自己粘贴
private const val KEYCODE_PASTE = 279

private val BOUNDS_REGEX = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")
private val SIZE_REGEX = Regex("""(\d+)x(\d+)""")
private val PACKAGE_REGEX = Regex("""([A-Za-z][\w]*(?:\.[\w]+)+)/""")

/**
 * 用 shell 命令操作手机：Shizuku（shell 权限）和 root（uid 0）共用这一份。
 * 读界面靠 `uiautomator dump`，截图靠 `screencap`，手感操作靠 `input`，
 * 比无障碍慢一些（每条命令都要起进程），但不需要用户开无障碍、能做到无障碍做不到的事。
 */
class ShellPhoneController(
    private val context: Context,
    private val shell: PhoneShell,
) : PhoneController {
    override val canScreenshot: Boolean = true

    /** 通道现在通不通，给设置页判断 */
    val isReady: Boolean get() = shell.isReady

    /** 重新检测通道；root 会再问一次 su，用户刚在 root 管理里点了允许时用得上 */
    suspend fun recheck(): Boolean {
        val current = shell
        return if (current is RootShell) current.probe(force = true) else current.isReady
    }

    // 和最近一次 captureScreen 返回的元素一一对应，setText 靠它找到要点的位置
    @Volatile
    private var elements: List<PhoneElement> = emptyList()

    // ---- 读取屏幕 ----

    override suspend fun visiblePackages(): Set<String> = readDeviceInfo().packages.toSet()

    override suspend fun captureScreen(): PhoneScreen = withContext(Dispatchers.IO) {
        val info = readDeviceInfo()
        val roots = parseHierarchy(dumpHierarchy())
        val collector = Collector(info.width, info.height).apply { collect(roots) }
        elements = collector.elements
        val packages = (info.packages.toSet() + collector.packages) - context.packageName
        val top = info.packages.firstOrNull() ?: collector.packages.firstOrNull()
        PhoneScreen(
            packageName = top,
            appLabel = top?.let(::appLabel),
            packages = packages,
            width = info.width,
            height = info.height,
            keyboardVisible = info.keyboardVisible,
            elements = collector.elements,
            truncated = collector.truncated,
        )
    }

    /** 尺寸、输入法、焦点应用一条命令问完，少起一次进程 */
    private suspend fun readDeviceInfo(): DeviceInfo {
        val result = shell.exec(
            "wm size; echo @@; dumpsys input_method | grep -E 'mInputShown|mWindowVisible'; echo @@; " +
                "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"
        )
        val parts = result.stdout.split("@@")
        val size = SIZE_REGEX.findAll(parts.getOrNull(0).orEmpty()).lastOrNull()
        val width = size?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val height = size?.groupValues?.get(2)?.toIntOrNull() ?: 0
        return DeviceInfo(
            // 拿不到大小时按 1080x2400 兜底，总比除零崩掉好
            width = width.takeIf { it > 0 } ?: 1080,
            height = height.takeIf { it > 0 } ?: 2400,
            keyboardVisible = isKeyboardVisible(parts.getOrNull(1).orEmpty()),
            packages = PACKAGE_REGEX.findAll(parts.getOrNull(2).orEmpty())
                .map { it.groupValues[1] }
                .filterNot { it == context.packageName }
                .distinct()
                .toList(),
        )
    }

    /** 输入法是否在显示：新系统给 mInputShown，老系统只有 mImeWindowVis（最低位是「正在显示」） */
    private fun isKeyboardVisible(dump: String): Boolean {
        if (dump.contains("mInputShown=true")) return true
        val visibility = Regex("""mImeWindowVis=([0-9A-Fa-f]+)""").find(dump) ?: return false
        return visibility.groupValues[1].toIntOrNull(16)?.and(0x1) == 0x1
    }

    private class DeviceInfo(
        val width: Int,
        val height: Int,
        val keyboardVisible: Boolean,
        val packages: List<String>,
    )

    /** `uiautomator dump` 偶尔会因为界面还在动失败，等一下就重试一次 */
    private suspend fun dumpHierarchy(): String {
        repeat(2) { attempt ->
            if (attempt > 0) delay(DUMP_RETRY_DELAY_MS)
            val result = shell.exec(
                "uiautomator dump $DUMP_PATH >/dev/null 2>&1; cat $DUMP_PATH 2>/dev/null",
                timeoutMs = PhoneShell.HIERARCHY_TIMEOUT_MS,
            )
            val start = result.stdout.indexOf("<?xml")
            if (start >= 0) return result.stdout.substring(start)
        }
        return ""
    }

    private fun parseHierarchy(xml: String): List<UiNode> {
        if (xml.isBlank()) return emptyList()
        return runCatching {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml))
            val roots = mutableListOf<UiNode>()
            val stack = ArrayDeque<UiNode>()
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> if (parser.name == "node") {
                        val node = parser.readNode()
                        if (stack.isEmpty()) roots += node else stack.last().children += node
                        stack.addLast(node)
                    }

                    XmlPullParser.END_TAG -> if (parser.name == "node") {
                        stack.removeLastOrNull()
                    }
                }
                event = parser.next()
            }
            roots
        }.getOrDefault(emptyList())
    }

    private fun XmlPullParser.readNode(): UiNode {
        fun attr(name: String) = getAttributeValue(null, name)
        fun flag(name: String) = attr(name) == "true"
        val bounds = BOUNDS_REGEX.find(attr("bounds").orEmpty())?.groupValues
        return UiNode(
            packageName = attr("package"),
            className = attr("class").orEmpty(),
            text = attr("text").orEmpty(),
            description = attr("content-desc").orEmpty(),
            resourceId = attr("resource-id").orEmpty().substringAfterLast('/').takeIf { it.isNotBlank() },
            clickable = flag("clickable"),
            longClickable = flag("long-clickable"),
            scrollable = flag("scrollable"),
            checkable = flag("checkable"),
            checked = flag("checked"),
            focused = flag("focused"),
            left = bounds?.get(1)?.toIntOrNull() ?: 0,
            top = bounds?.get(2)?.toIntOrNull() ?: 0,
            right = bounds?.get(3)?.toIntOrNull() ?: 0,
            bottom = bounds?.get(4)?.toIntOrNull() ?: 0,
        )
    }

    private class UiNode(
        val packageName: String?,
        val className: String,
        val text: String,
        val description: String,
        val resourceId: String?,
        val clickable: Boolean,
        val longClickable: Boolean,
        val scrollable: Boolean,
        val checkable: Boolean,
        val checked: Boolean,
        val focused: Boolean,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val children: MutableList<UiNode> = mutableListOf(),
    ) {
        val editable: Boolean get() = className.contains("EditText")

        fun ownLabel(): String = text.takeIf { it.isNotBlank() }
            ?: description.takeIf { it.isNotBlank() }
            ?: ""

        fun intersects(width: Int, height: Int): Boolean =
            left < width && top < height && right > 0 && bottom > 0 && right > left && bottom > top

        fun descendantLabels(): List<String> {
            val labels = mutableListOf<String>()
            fun collect(node: UiNode) {
                for (child in node.children) {
                    if (labels.size >= MAX_LABEL_PARTS) return
                    // 可点击的子节点自己会单独列出
                    if (child.clickable) continue
                    child.ownLabel().takeIf { it.isNotEmpty() }?.let(labels::add)
                    collect(child)
                }
            }
            collect(this)
            return labels
        }
    }

    private class Collector(
        private val width: Int,
        private val height: Int,
    ) {
        val elements = mutableListOf<PhoneElement>()
        val packages = mutableSetOf<String>()
        var truncated = false

        fun collect(roots: List<UiNode>) {
            roots.forEach { visit(it, absorbed = false) }
        }

        private fun visit(node: UiNode, absorbed: Boolean) {
            if (elements.size >= MAX_ELEMENTS) {
                truncated = true
                return
            }
            node.packageName?.let(packages::add)
            var childrenAbsorbed = absorbed
            val interactive = node.clickable || node.longClickable || node.scrollable || node.checkable
            var label = node.ownLabel()
            if (label.isEmpty() && node.clickable) {
                label = node.descendantLabels().joinToString(" ")
                // 文字已经算进这个容器了，子节点里的纯文字不再单独列一遍
                childrenAbsorbed = label.isNotEmpty()
            }
            val listed = interactive || (label.isNotEmpty() && !absorbed)
            if (listed && node.intersects(width, height)) {
                elements += PhoneElement(
                    index = elements.size,
                    className = node.className.substringAfterLast('.').ifEmpty { "View" },
                    label = label.clean(),
                    resourceId = node.resourceId,
                    left = node.left,
                    top = node.top,
                    right = node.right,
                    bottom = node.bottom,
                    clickable = node.clickable,
                    editable = node.editable,
                    scrollable = node.scrollable,
                    checked = if (node.checkable) node.checked else null,
                    focused = node.editable && node.focused,
                )
            }
            node.children.forEach { visit(it, childrenAbsorbed) }
        }
    }

    // ---- 截图 ----

    override suspend fun screenshot(maxSide: Int): ByteArray {
        val png = shell.execBytes("screencap -p")
        return withContext(Dispatchers.Default) {
            val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)
                ?: error("Screenshot failed: screencap returned no image.")
            // 一张全屏位图十几 MB，连续截图时等 GC 回收来不及，用完立刻释放
            try {
                val ratio = maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)
                val scaled = if (ratio < 1f) {
                    bitmap.scale((bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt())
                } else {
                    bitmap
                }
                try {
                    ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                        .toByteArray()
                } finally {
                    if (scaled !== bitmap) scaled.recycle()
                }
            } finally {
                bitmap.recycle()
            }
        }
    }

    // ---- 操作 ----

    override suspend fun gesture(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long): Boolean {
        val x1 = fromX.roundToInt().coerceAtLeast(0)
        val y1 = fromY.roundToInt().coerceAtLeast(0)
        val x2 = toX.roundToInt().coerceAtLeast(0)
        val y2 = toY.roundToInt().coerceAtLeast(0)
        // input 命令会一直等到手势做完才返回，不用自己再等
        val command = when {
            x1 != x2 || y1 != y2 -> "input swipe $x1 $y1 $x2 $y2 ${durationMs.toInt()}"
            durationMs <= TAP_MAX_MS -> "input tap $x1 $y1"
            // 长按是同点的滑动，时长交给系统
            else -> "input swipe $x1 $y1 $x2 $y2 ${durationMs.toInt()}"
        }
        return shell.exec(command).ok
    }

    override suspend fun setText(elementIndex: Int?, text: String): Boolean {
        // 先点一下目标，让输入框拿到焦点；不点的话文字会填到上一个输入框里
        val element = elementIndex?.let { elements.getOrNull(it) }
        if (element != null) {
            gesture(
                (element.left + element.right) / 2f,
                (element.top + element.bottom) / 2f,
                (element.left + element.right) / 2f,
                (element.top + element.bottom) / 2f,
                TAP_MAX_MS,
            )
            delay(FOCUS_DELAY_MS)
        }
        // input text 只认 ASCII，中文得走剪贴板粘贴
        return if (text.all { it.code in 32..126 }) {
            shell.exec("input text '${text.replace(" ", "%s").replace("'", "'\\''")}'").ok
        } else {
            paste(text)
        }
    }

    private suspend fun paste(text: String): Boolean {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
            ?: error("Clipboard is not available on this phone.")
        val written = runCatching {
            withContext(Dispatchers.Main) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Phone Use", text))
            }
        }
        check(written.isSuccess) {
            "This phone does not let the app write the clipboard in the background, " +
                "so non-English text cannot be typed this way. " +
                "Switch Phone Use to the accessibility backend for that."
        }
        delay(CLIPBOARD_DELAY_MS)
        return shell.exec("input keyevent $KEYCODE_PASTE").ok
    }

    override suspend fun pressKey(key: PhoneKey): Boolean = when (key) {
        PhoneKey.BACK -> shell.exec("input keyevent 4").ok
        PhoneKey.HOME -> shell.exec("input keyevent 3").ok
        PhoneKey.RECENTS -> shell.exec("input keyevent 187").ok
        // 老系统没有 cmd statusbar，退回直接调服务
        PhoneKey.NOTIFICATIONS -> shell.exec("cmd statusbar expand-notifications").ok ||
            shell.exec("service call statusbar 1").ok

        PhoneKey.ENTER -> shell.exec("input keyevent 66").ok
    }

    // ---- 应用 ----

    override fun listApps(): List<PhoneApp> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(launcher, 0)
            .map { PhoneApp(it.activityInfo.packageName, it.loadLabel(context.packageManager).toString()) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    override suspend fun openApp(packageName: String): Boolean {
        // monkey 会自己找启动图标，不用知道 Activity 名
        val result = shell.exec("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
        return result.ok && !result.stderr.contains("aborted", ignoreCase = true) &&
            !result.stdout.contains("No activities found", ignoreCase = true)
    }

    private fun appLabel(packageName: String): String? = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0))
            .toString()
    }.getOrNull()
}

/** 元素文字里可能有换行，压成一行并截断，免得把整段列表挤爆 */
private fun String.clean() = replace('\n', ' ').trim().let {
    if (it.length > MAX_LABEL_CHARS) it.take(MAX_LABEL_CHARS) + "…" else it
}
