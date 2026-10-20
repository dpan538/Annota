package io.github.dpan538.annota

import android.app.ActivityManager
import android.content.Context

/** Rebuildable caches share a budget derived from the normal app heap, never largeHeap. */
data class RenderMemory(val pages: Int, val images: Int, val covers: Int, val words: Int) {
    companion object {
        private const val MIB = 1024 * 1024

        fun forHeap(megabytes: Int, lowRam: Boolean = false): RenderMemory {
            val heap = megabytes.coerceAtLeast(32)
            val divisor = if (lowRam) 2 else 1
            return RenderMemory(
                (heap / 8 / divisor).coerceIn(4, 32) * MIB,
                (heap / 24 / divisor).coerceIn(2, 12) * MIB,
                (heap / 32 / divisor).coerceIn(1, 8) * MIB,
                (heap / 64 / divisor).coerceIn(1, 4) * MIB,
            )
        }

        fun from(context: Context): RenderMemory {
            val manager = context.getSystemService(ActivityManager::class.java)
            return forHeap(manager.memoryClass, manager.isLowRamDevice)
        }
    }
}
