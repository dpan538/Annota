package io.github.dpan538.annota

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage

/**
 * finish() is asynchronous. Never unlink the test DB while an old reader can still save into it.
 */
fun finishReadersBeforeDatabaseReset() {
    check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
    val inst = InstrumentationRegistry.getInstrumentation()
    val activities = mutableSetOf<MainActivity>()
    inst.runOnMainSync {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        Stage.values()
            .filter { it != Stage.DESTROYED }
            .forEach { stage ->
                activities.addAll(
                    monitor.getActivitiesInStage(stage).filterIsInstance<MainActivity>()
                )
            }
        activities.forEach { if (!it.isFinishing) it.finish() }
    }
    val end = SystemClock.uptimeMillis() + 15000
    while (activities.any { !it.isDestroyed }) {
        check(SystemClock.uptimeMillis() < end) {
            "Previous reader Activity has not finished before test database reset"
        }
        SystemClock.sleep(20)
    }
    inst.waitForIdleSync()
}
