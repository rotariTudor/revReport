package com.thrust.revreport

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

fun readPhoneTempC(context: Context): Double? {
    val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
    val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
    return if (t == Int.MIN_VALUE) null else t / 10.0   // sistemul o dă în zecimi de grad
}