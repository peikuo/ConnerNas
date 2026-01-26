package com.peik.cornernas.util

import android.app.Activity
import android.content.Context
import android.view.WindowManager
import com.peik.cornernas.MainActivity

fun applyKeepScreenOnIfEnabled(activity: Activity) {
    val prefs = activity.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
    val enabled = prefs.getBoolean(MainActivity.KEY_KEEP_SCREEN_ON, false)
    if (enabled) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

fun clearKeepScreenOn(activity: Activity) {
    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}
