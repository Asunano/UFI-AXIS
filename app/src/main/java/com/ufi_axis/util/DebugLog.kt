package com.ufi_axis.util

import android.util.Log

object DebugLog {
    private const val GLOBAL_TAG = "UFI-AXIS"
    var enabled = true

    fun d(tag: String, msg: String) {
        if (enabled) Log.d("$GLOBAL_TAG/$tag", msg)
    }

    fun i(tag: String, msg: String) {
        if (enabled) Log.i("$GLOBAL_TAG/$tag", msg)
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        Log.w("$GLOBAL_TAG/$tag", msg, tr)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        Log.e("$GLOBAL_TAG/$tag", msg, tr)
    }

    fun json(tag: String, url: String, json: String) {
        if (!enabled) return
        val truncated = if (json.length > 2000) json.take(2000) + "..." else json
        Log.d("$GLOBAL_TAG/JSON/$tag", "$url\n$truncated")
    }

    fun parseError(tag: String, url: String, responseBody: String, error: Throwable) {
        Log.e("$GLOBAL_TAG/PARSE/$tag", "URL: $url\nBody: ${responseBody.take(3000)}", error)
    }
}