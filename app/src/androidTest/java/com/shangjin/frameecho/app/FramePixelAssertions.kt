package com.shangjin.frameecho.app

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertTrue
import kotlin.math.abs

internal fun assertSourceLabelPixels(index: Int, reference: Bitmap, actual: Bitmap) {
    val differences = IntArray(reference.width * reference.height)
    fun gray(pixel: Int): Int = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114 + 500) / 1000
    for (y in 0 until reference.height) {
        for (x in 0 until reference.width) {
            differences[y * reference.width + x] = abs(gray(reference.getPixel(x, y)) - gray(actual.getPixel(x + 20, y + 18)))
        }
    }
    differences.sort()
    val outliers = differences.count { it > 64 }
    assertTrue("Frame $index contains ghosted or neighbouring-frame pixels ($outliers outliers)", outliers <= differences.size / 200)
    assertTrue("Frame $index label differs from its independently decoded source", differences[differences.size * 99 / 100] <= 32)
}
