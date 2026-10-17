package com.sidesand.millet.ipc

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.sidesand.millet.data.ConfigKeys

/**
 * 还原原模块 Landroid/support/v4/ળ; 的匿名 Binder 桥接器：
 * 向 system_server 发起 registerReceiver(IntentFilter("SMillet-Binder"))，
 * 被 Hook 后的 ActivityManagerService#registerReceiverWithFeature 会直接返回带有 "system" IBinder 的 Intent。
 */
object BinderBridge {

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun fetchSystemInterface(context: Context): SystemInterface? {
        return try {
            val filter = IntentFilter(ConfigKeys.BINDER_ACTION)
            val dummyReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {}
            }
            val stickyIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(dummyReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(dummyReceiver, filter)
            }
            runCatching { context.unregisterReceiver(dummyReceiver) }
            val binder = stickyIntent?.extras?.getBinder(ConfigKeys.BINDER_KEY_SYSTEM)
            SystemInterface.Stub.asInterface(binder)
        } catch (_: Throwable) {
            null
        }
    }
}
