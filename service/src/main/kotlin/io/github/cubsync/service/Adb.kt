package io.github.cubsync.service

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 传输通道's USB leg, through the adb that is on the PATH.
 *
 * The channel was drawn for four releases and said 已留位 when tapped. It is not a special
 * protocol: `adb forward` opens a TCP port on this machine that is answered on the device,
 * so a USB cable becomes the same socket the Wi-Fi path uses and nothing above this file
 * has to know the difference. That is also why the phone needs nothing: it is already
 * listening on its share port, and the cable only decides how the host reaches it.
 *
 * Everything here shells out to the platform tool rather than reimplementing its protocol,
 * and every failure is returned as a sentence rather than thrown, because "no adb" is the
 * normal state of a machine that has never installed it.
 */
object Adb {

    /** One device as `adb devices -l` describes it. */
    data class Device(val serial: String, val model: String, val state: String) {
        val ready: Boolean get() = state == "device"
        val label: String get() = when {
            model.isNotBlank() -> "$model（$serial）"
            else -> serial
        }
    }

    /** Whether an adb that answers is on the PATH. */
    fun available(): Boolean = run("version").first == 0

    /** Every device the tool can see, including the ones that are unauthorized. */
    fun devices(): List<Device> {
        val (code, output) = run("devices", "-l")
        if (code != 0) return emptyList()
        return output.lineSequence()
            .drop(1)
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (parts.size < 2) return@mapNotNull null
                val serial = parts[0]
                val state = parts[1]
                val model = parts.firstOrNull { it.startsWith("model:") }
                    ?.removePrefix("model:")
                    ?.replace('_', ' ')
                    .orEmpty()
                Device(serial, model, state)
            }
            .toList()
    }

    /**
     * Makes `127.0.0.1:localPort` on this machine reach `remotePort` on the device.
     *
     * Returns null when it worked, or the reason it did not. `-s` picks the device when
     * more than one is plugged in; without it adb refuses, which is the correct answer.
     */
    fun forward(localPort: Int, remotePort: Int = localPort, serial: String? = null): String? {
        val args = buildList {
            if (!serial.isNullOrBlank()) {
                add("-s")
                add(serial)
            }
            add("forward")
            add("tcp:$localPort")
            add("tcp:$remotePort")
        }
        val (code, output) = run(*args.toTypedArray())
        return if (code == 0) null else output.trim().ifEmpty { "adb forward 失败（退出码 $code）" }
    }

    fun removeForward(localPort: Int) {
        run("forward", "--remove", "tcp:$localPort")
    }

    private fun run(vararg args: String): Pair<Int, String> = runCatching {
        val process = ProcessBuilder(listOf("adb") + args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(8, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching -1 to "adb 没有在 8 秒内回答"
        }
        process.exitValue() to output
    }.getOrElse { failure ->
        -1 to (failure.message ?: "找不到 adb")
    }

    /** Where the tool lives, for the screen that says whether it was found. */
    fun location(): String? = runCatching {
        System.getenv("PATH").orEmpty()
            .split(File.pathSeparator)
            .map { File(it, if (System.getProperty("os.name").startsWith("Windows")) "adb.exe" else "adb") }
            .firstOrNull { it.isFile }
            ?.absolutePath
    }.getOrNull()
}
