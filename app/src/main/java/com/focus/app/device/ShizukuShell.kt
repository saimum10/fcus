package com.focus.app.device

import android.content.pm.PackageManager
import com.focus.app.domain.ShizukuState
import java.io.IOException
import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

/**
 * The only class that talks to Shizuku. It runs the system's own `pm suspend` / `pm unsuspend`
 * commands with shell rights, which is the same thing `adb shell pm suspend` does.
 *
 * Nothing here is assumed: every call re-checks that Shizuku is running and allowed, and a
 * suspension counts only if the command itself reported the new state.
 *
 * All methods except [state] and [requestPermission] block on a child process: call them
 * off the main thread. They throw [IOException] or [IllegalStateException] when Shizuku is
 * not usable; the restriction engine treats that as "the system refused".
 */
internal class ShizukuShell {
    /** Packages this process has confirmed suspended, used when `dumpsys` output cannot be read. */
    private val confirmedSuspended = HashSet<String>()

    fun state(): ShizukuState = try {
        when {
            !Shizuku.pingBinder() || Shizuku.isPreV11() -> ShizukuState.NotRunning
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuState.Granted
            else -> ShizukuState.NeedsPermission
        }
    } catch (_: Exception) {
        ShizukuState.NotRunning
    }

    /** Shows Shizuku's permission dialog. True if the request was sent. */
    fun requestPermission(): Boolean = try {
        if (!Shizuku.pingBinder() || Shizuku.isPreV11()) {
            false
        } else {
            Shizuku.requestPermission(REQUEST_CODE)
            true
        }
    } catch (_: Exception) {
        false
    }

    /** Suspends or un-suspends [packages]. Returns those the system did not confirm in the wanted state. */
    fun setSuspended(packages: Set<String>, suspended: Boolean): Set<String> {
        val valid = packages.filter { PACKAGE_NAME.matches(it) }
        val invalid = packages - valid.toSet()
        if (valid.isEmpty()) return invalid
        val verb = if (suspended) "suspend" else "unsuspend"
        val script = "for p in \"\$@\"; do pm $verb --user 0 \"\$p\" 2>&1; done"
        val output = run(script, valid)
        val wanted = "new suspended state: $suspended"
        val confirmed = HashSet<String>()
        for (line in output.lineSequence()) {
            val text = line.trim()
            if (!text.startsWith("Package ") || !text.endsWith(wanted)) continue
            confirmed += text.removePrefix("Package ").substringBefore(' ')
        }
        synchronized(confirmedSuspended) {
            if (suspended) confirmedSuspended += confirmed else confirmedSuspended -= valid.toSet()
        }
        return invalid + valid.filterNot { it in confirmed }
    }

    /** Force-stops [packages] for the main user with `am force-stop`. Best effort: nothing is reported back. */
    fun forceStop(packages: Set<String>) {
        val valid = packages.filter { PACKAGE_NAME.matches(it) }
        if (valid.isEmpty()) return
        run("for p in \"\$@\"; do am force-stop --user 0 \"\$p\" >/dev/null 2>&1; done", valid)
    }

    /** The subset of [packages] that the system reports as suspended for the main user. */
    fun suspendedAmong(packages: Set<String>): Set<String> {
        val valid = packages.filter { PACKAGE_NAME.matches(it) }
        if (valid.isEmpty()) return emptySet()
        val script = "for p in \"\$@\"; do " +
            "o=\$(dumpsys package \"\$p\" 2>/dev/null | grep -o 'suspended=[a-z]*' | head -n 1); " +
            "echo \"\$p \${o:-unknown}\"; done"
        val output = run(script, valid)
        val reported = HashMap<String, Boolean?>()
        for (line in output.lineSequence()) {
            val parts = line.trim().split(' ')
            if (parts.size != 2) continue
            reported[parts[0]] = when (parts[1]) {
                "suspended=true" -> true
                "suspended=false" -> false
                else -> null
            }
        }
        return synchronized(confirmedSuspended) {
            valid.filterTo(HashSet()) { pkg ->
                when (reported[pkg]) {
                    true -> true.also { confirmedSuspended += pkg }
                    false -> false.also { confirmedSuspended -= pkg }
                    // Could not be read (output format differs, or the package is gone): fall back to what this process confirmed.
                    null -> pkg in confirmedSuspended
                }
            }
        }
    }

    /**
     * Sets the `no_uninstall_apps` user restriction (and, on Android 11+, `no_control_apps`, which also
     * stops force stop and clearing fOCUS's data from Settings) with `pm set-user-restriction`.
     * Blocking counts only if the command succeeded and `dumpsys user` lists the restriction.
     * Un-blocking counts if the command succeeded or the restriction is not listed (nothing to undo).
     */
    fun setUninstallBlocked(blocked: Boolean): Boolean {
        val value = if (blocked) "1" else "0"
        val script = "pm set-user-restriction --user 0 no_uninstall_apps $value >/dev/null 2>&1; echo \"exit=\$?\"; " +
            "pm set-user-restriction --user 0 no_control_apps $value >/dev/null 2>&1; " +
            "if dumpsys user 2>/dev/null | grep -q no_uninstall_apps; then echo present; else echo absent; fi"
        val lines = run(script, emptyList()).lines().map { it.trim() }
        val succeeded = "exit=0" in lines
        val listed = "present" in lines
        return if (blocked) succeeded && listed else succeeded || !listed
    }

    private fun run(script: String, args: List<String>): String {
        check(state() == ShizukuState.Granted) { "Shizuku is not running or not allowed" }
        val command = arrayOf("sh", "-c", script, "sh") + args.toTypedArray()
        val process = newProcess(command)
        try {
            val text = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw IOException("Shizuku command timed out")
            }
            return text
        } finally {
            process.destroy()
        }
    }

    /**
     * Shizuku 13 made `Shizuku.newProcess` private (it prefers a bound user service, which needs an
     * AIDL service and much more code). The method still exists and is what most apps call by reflection.
     */
    private fun newProcess(command: Array<String>): Process {
        try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            method.isAccessible = true
            return method.invoke(null, command, null, null) as Process
        } catch (e: ReflectiveOperationException) {
            throw IOException("Shizuku cannot start a command", e)
        }
    }

    private companion object {
        const val REQUEST_CODE = 4711
        const val PROCESS_TIMEOUT_SECONDS = 60L
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    }
}
