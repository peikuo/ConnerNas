package com.peik.cornernas.util

import android.util.Log

object AppLog {
    fun i(tag: String, message: String) {
        Log.i(tag, message)
        LogStore.add("I", tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.w(tag, message, throwable)
            LogStore.add("W", tag, "$message (${throwable.javaClass.simpleName}: ${throwable.message})")
        } else {
            Log.w(tag, message)
            LogStore.add("W", tag, message)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
            LogStore.add("E", tag, "$message (${throwable.javaClass.simpleName}: ${throwable.message})")
        } else {
            Log.e(tag, message)
            LogStore.add("E", tag, message)
        }
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        LogStore.add("D", tag, message)
    }
}
