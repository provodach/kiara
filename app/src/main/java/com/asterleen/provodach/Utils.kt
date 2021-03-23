package com.asterleen.provodach

import android.os.Handler
import android.os.Looper
import  java.util.Random


object Utils {
    /* Очень содержательно. */
    private var handler: Handler? = null
    fun runOnUiThread(r: Runnable?) {
        handler!!.post(r!!)
    }

    // We try to keep the compatible version as low as we can
    fun nextInt(rng: Random, n: Int): Int {
        // error checking and 2^x checking removed for simplicity.
        var bits: Int
        var `val`: Int
        do {
            bits = rng.nextInt() shl 1 ushr 1
            `val` = bits % n
        } while (bits - `val` + (n - 1) < 0L)
        return `val`
    }

    fun randomString(length: Int = 32) : String {
        val source = "qwertyuiopasdfghjklzxcvbnm"
        val rng = Random()
        var toReturn : String = ""
        for (i: Int in 1..length) {
            toReturn += source[nextInt(rng, source.length)]
        }

        return toReturn
    }

    init {
        handler = Handler(Looper.getMainLooper())
    }
}