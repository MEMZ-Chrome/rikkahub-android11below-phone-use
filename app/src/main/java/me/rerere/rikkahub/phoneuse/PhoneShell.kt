package me.rerere.rikkahub.phoneuse

import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** 一条 shell 命令的结果，输出按 UTF-8 解码；[code] 是 -1 表示超时被杀 */
data class ShellResult(val code: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = code == 0

    /** 失败时的简短原因，拿去拼错误消息；命令输出可能很长，只留一小段 */
    fun error(): String = stderr.trim().ifEmpty { stdout.trim() }.take(200).ifEmpty { "exit code $code" }
}

/**
 * 跑 shell 命令的通道。Shizuku 给的是 shell 权限（uid 2000），root 给的是 uid 0，
 * 能跑的命令一样，只是拿权限的方式不同。命令都在独立进程里跑，超时直接杀掉。
 */
interface PhoneShell {
    /** 现在能不能用：Shizuku 已授权，或者 su 可用 */
    val isReady: Boolean

    suspend fun exec(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ShellResult

    /** 取原始输出，截图用 */
    suspend fun execBytes(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ByteArray

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
        const val HIERARCHY_TIMEOUT_MS = 30_000L
    }
}

/** 进程的原始输出。stdout 单独留成字节，截图不能被 UTF-8 解码破坏 */
internal class ProcessOutput(val code: Int, val stdout: ByteArray, val stderr: ByteArray) {
    fun text() = ShellResult(code, String(stdout, Charsets.UTF_8), String(stderr, Charsets.UTF_8))
}

/**
 * 等进程结束并收走输出。两条流各开一个线程读，否则管道写满会互相卡死；
 * 超时就强杀，[ProcessOutput.code] 给 -1。
 *
 * 不能用 [Process.waitFor]：它内部只把 IllegalThreadStateException 当成「还没结束」，
 * 而 Shizuku 的远程进程在没结束时抛的是 IllegalArgumentException("process hasn't exited")，
 * 于是 waitFor 一进去就炸（连 waitFor() 无参版本也一样），只能自己轮询退出码。
 */
internal fun Process.awaitOutput(timeoutMs: Long): ProcessOutput {
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    val stdoutReader = drain(inputStream, stdout)
    val stderrReader = drain(errorStream, stderr)
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    var code: Int? = null
    // 短命令 10ms 一次就能立刻发现结束，长命令退到 100ms，避免一直打 binder
    var intervalMs = 10L
    while (System.nanoTime() < deadline) {
        code = exitCodeOrNull()
        if (code != null) break
        try {
            Thread.sleep(intervalMs)
        } catch (_: InterruptedException) {
            break
        }
        if (intervalMs < 100) intervalMs += 10
    }
    if (code == null) {
        // 再确认一次，别刚好卡在超时边界上把已经退出的进程杀掉
        code = exitCodeOrNull()
        if (code == null) runCatching { destroyForcibly() }
    }
    // 进程没了流就会关，读取线程很快退出；这里只是等它们把缓冲区写完
    stdoutReader.join(1_000)
    stderrReader.join(1_000)
    return ProcessOutput(code ?: -1, stdout.toByteArray(), stderr.toByteArray())
}

/** 退出码，还在跑（或通道出问题）时给 null */
private fun Process.exitCodeOrNull(): Int? = try {
    exitValue()
} catch (_: Exception) {
    null
}

private fun drain(input: InputStream, sink: ByteArrayOutputStream): Thread = Thread {
    runCatching { input.use { it.copyTo(sink) } }
}.apply {
    isDaemon = true
    start()
}

/** Shizuku 授权的 shell 权限，用户要装 Shizuku 并在里面给本应用授权 */
class ShizukuShell : PhoneShell {
    override val isReady: Boolean get() = isAuthorized()

    override suspend fun exec(command: String, timeoutMs: Long): ShellResult = withContext(Dispatchers.IO) {
        check(isReady) { "Shizuku is not running, or this app is not authorized in Shizuku." }
        newProcess(command).awaitOutput(timeoutMs).text()
    }

    override suspend fun execBytes(command: String, timeoutMs: Long): ByteArray = withContext(Dispatchers.IO) {
        check(isReady) { "Shizuku is not running, or this app is not authorized in Shizuku." }
        newProcess(command).awaitOutput(timeoutMs).stdout
    }

    @Suppress("DEPRECATION")
    private fun newProcess(command: String): Process =
        Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)

    companion object {
        /** Shizuku 服务在跑（App 装了、服务启动了）；没装时调用一律返回 false，不会崩 */
        fun isRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

        fun isAuthorized(): Boolean = runCatching {
            isRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }
}

/** root 权限，走 su。没有 su 的机器上 [isReady] 一直是 false */
class RootShell : PhoneShell {
    // su 探测会弹系统的授权框，一次会话只问一次；用户手动重试时 force 一次
    @Volatile
    private var granted: Boolean? = null

    override val isReady: Boolean get() = granted == true

    override suspend fun exec(command: String, timeoutMs: Long): ShellResult = withContext(Dispatchers.IO) {
        check(probe()) { "Root is not available: su was not granted." }
        ProcessBuilder("su", "-c", command).start().awaitOutput(timeoutMs).text()
    }

    override suspend fun execBytes(command: String, timeoutMs: Long): ByteArray = withContext(Dispatchers.IO) {
        check(probe()) { "Root is not available: su was not granted." }
        ProcessBuilder("su", "-c", command).start().awaitOutput(timeoutMs).stdout
    }

    /** 跑一条 id 看 su 给不给用；第一次会弹授权提示 */
    suspend fun probe(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!force) granted?.let { return@withContext it }
        val result = runCatching {
            ProcessBuilder("su", "-c", "id").start().awaitOutput(5_000)
        }.getOrNull()
        val ok = result != null && result.code == 0 && String(result.stdout).contains("uid=0")
        granted = ok
        ok
    }
}
