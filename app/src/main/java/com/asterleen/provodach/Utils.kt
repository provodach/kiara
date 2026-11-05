package com.asterleen.provodach

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.os.Handler
import android.os.Looper
import  java.util.Random


object Utils {
    fun registerPrivateReceiver(context: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    /** Keep the existing View UI clear of bars, cutouts and the IME under enforced edge-to-edge. */
    fun applySystemInsets(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val root = activity.findViewById<View>(androidx.appcompat.R.id.decor_content_parent) ?: return
        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom
        root.setOnApplyWindowInsetsListener { view, insets ->
            val safeInsets = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
            )
            view.setPadding(
                left + safeInsets.left, top + safeInsets.top,
                right + safeInsets.right, bottom + safeInsets.bottom
            )
            WindowInsets.CONSUMED
        }
        root.requestApplyInsets()
    }

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