package io.github.kreza6173pixel.cyberappmanager.exec

import rikka.shizuku.Shizuku
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * Fallback executor used when the Shizuku user service never connects (issue #4).
 *
 * It runs each command as a Shizuku remote process. `Shizuku.newProcess` became private in the
 * 13.x client library, so it is reached by reflection; the server side is unchanged and the
 * child runs with the same uid as the user service would (2000 shell or 0 root).
 *
 * Same guarantees as [ShizukuExecService]: one command at a time, both streams drained
 * concurrently and capped, the child destroyed on timeout, and [cancel] destroys the running
 * child. Waiting is done with a plain blocking `waitFor()` on a pooled thread and a bounded
 * `Future.get`, because the timed `Process.waitFor(long, TimeUnit)` relies on `exitValue()`
 * throwing `IllegalThreadStateException`, which does not survive the binder hop.
 */
class ShizukuProcessRunner {

    private val commandLock = Any()
    private val pool: ExecutorService = Executors.newCachedThreadPool()
    private val running = AtomicReference<Process?>(null)
    private val cancelled = AtomicReference<Process?>(null)

    /** False when this client library build does not expose the method at all. */
    val available: Boolean get() = newProcessMethod != null

    fun run(command: String, timeoutMs: Int): ExecResult {
        if (command.isBlank()) return ExecResult(EXIT_BAD_COMMAND, "", "empty command", false)
        return synchronized(commandLock) { runOnce(command, timeoutMs) }
    }

    fun cancel() {
        running.getAndSet(null)?.let { process ->
            cancelled.set(process)
            runCatching { process.destroy() }
        }
    }

    private fun runOnce(command: String, timeoutMs: Int): ExecResult {
        val method = newProcessMethod
            ?: return ExecResult(EXIT_INTERNAL, "", "Shizuku remote process API not available", false)
        val timeout = timeoutMs.toLong().coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        val out = OutputCollector(MAX_OUTPUT_CHARS)
        val err = OutputCollector(MAX_OUTPUT_CHARS)

        val process: Process = try {
            val nullEnv: Array<String>? = null
            val nullDir: String? = null
            method.invoke(null, arrayOf(SHELL_PATH, "-c", command), nullEnv, nullDir) as Process
        } catch (e: InvocationTargetException) {
            val cause = e.targetException ?: e
            return ExecResult(
                EXIT_INTERNAL,
                "",
                "cannot start remote process: ${cause.javaClass.simpleName}: ${cause.message}",
                false,
            )
        } catch (e: Exception) {
            return ExecResult(
                EXIT_INTERNAL,
                "",
                "cannot start remote process: ${e.javaClass.simpleName}: ${e.message}",
                false,
            )
        }
        runCatching { process.outputStream.close() }
        running.set(process)

        val readers: List<Future<*>> = listOf(
            pool.submit { drain(process.inputStream, out) },
            pool.submit { drain(process.errorStream, err) },
        )
        val waiter: Future<Int> = pool.submit(Callable { process.waitFor() })

        var exitCode = EXIT_INTERNAL
        var timedOut = false
        try {
            exitCode = waiter.get(timeout, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            timedOut = true
            runCatching { process.destroy() }
            waiter.cancel(true)
        } catch (e: ExecutionException) {
            err.appendLine("wait failed: ${e.cause?.javaClass?.simpleName}: ${e.cause?.message}")
        } catch (_: InterruptedException) {
            runCatching { process.destroy() }
            Thread.currentThread().interrupt()
        } finally {
            running.compareAndSet(process, null)
        }

        for (f in readers) runCatching { f.get(JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS) }

        val wasCancelled = cancelled.compareAndSet(process, null)
        if (timedOut) {
            out.markTruncated()
            err.markTruncated()
        }
        if (wasCancelled) err.appendLine(CANCELLED_MARKER)

        return ExecResult(
            exitCode = if (timedOut || wasCancelled) CANCELLED_EXIT_CODE else exitCode,
            stdout = out.text,
            stderr = err.text,
            truncated = out.truncated || err.truncated || timedOut,
        )
    }

    private fun drain(stream: InputStream, sink: OutputCollector) {
        try {
            stream.bufferedReader().use { reader ->
                val chunk = CharArray(READ_CHUNK)
                while (true) {
                    val n = reader.read(chunk)
                    if (n < 0) break
                    if (!sink.appendAll(String(chunk, 0, n))) break
                }
            }
        } catch (_: Exception) {
            // Stream closed by destroy(); whatever was collected stands.
        }
    }

    private companion object {
        const val SHELL_PATH = "/system/bin/sh"
        const val MAX_OUTPUT_CHARS = 64 * 1024
        const val READ_CHUNK = 4096
        const val MIN_TIMEOUT_MS = 250L
        const val MAX_TIMEOUT_MS = 120_000L
        const val JOIN_TIMEOUT_MS = 2_000L
        const val CANCELLED_MARKER = "cancelled by user"
        const val CANCELLED_EXIT_CODE = 124
        const val EXIT_BAD_COMMAND = 2
        const val EXIT_INTERNAL = 127

        /** `Shizuku.newProcess(String[] cmd, String[] env, String dir)`, private since 13.x. */
        val newProcessMethod: Method? by lazy {
            runCatching {
                Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java,
                ).apply { isAccessible = true }
            }.getOrNull()
        }
    }
}
