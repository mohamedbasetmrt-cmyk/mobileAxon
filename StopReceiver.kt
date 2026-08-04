package com.example.app_abdelbaset

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class StopReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "StopReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Stop action received from notification")
        context.stopService(Intent(context, MicForegroundService::class.java))
    }
}