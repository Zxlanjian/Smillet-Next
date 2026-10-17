package com.sidesand.millet.ipc

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel

/**
 * 对应原模块 Landroid/support/v4/ζ; (com.sidesand.millet.ISystemCallback)
 * 用于 system_server、PowerKeeper 与模块 UI 之间的双向通知。
 */
interface ISystemCallback : IInterface {
    /** 通知某项配置 Key 已变更，需要重新加载 XSharedPreferences */
    fun onConfigChanged(key: String)

    /** 请求 PowerKeeper 诊断指定包名未冻结的原因 */
    fun requestCheckFreezeReason(packageName: String)

    /** PowerKeeper 诊断完成后回调给 UI 显示原因 */
    fun onFreezeReasonResult(packageName: String, reason: String)

    abstract class Stub : Binder(), ISystemCallback {
        init {
            attachInterface(this, DESCRIPTOR)
        }

        override fun asBinder(): IBinder = this

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return when (code) {
                INTERFACE_TRANSACTION -> {
                    reply?.writeString(DESCRIPTOR)
                    true
                }
                TRANSACTION_ON_CONFIG_CHANGED -> {
                    data.enforceInterface(DESCRIPTOR)
                    onConfigChanged(data.readString().orEmpty())
                    reply?.writeNoException()
                    true
                }
                TRANSACTION_REQUEST_CHECK_FREEZE -> {
                    data.enforceInterface(DESCRIPTOR)
                    requestCheckFreezeReason(data.readString().orEmpty())
                    reply?.writeNoException()
                    true
                }
                TRANSACTION_ON_FREEZE_REASON_RESULT -> {
                    data.enforceInterface(DESCRIPTOR)
                    val pkg = data.readString().orEmpty()
                    val reason = data.readString().orEmpty()
                    onFreezeReasonResult(pkg, reason)
                    reply?.writeNoException()
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        }

        private class Proxy(private val remote: IBinder) : ISystemCallback {
            override fun asBinder(): IBinder = remote

            override fun onConfigChanged(key: String) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeString(key)
                    remote.transact(TRANSACTION_ON_CONFIG_CHANGED, data, reply, 0)
                    reply.readException()
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }

            override fun requestCheckFreezeReason(packageName: String) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeString(packageName)
                    remote.transact(TRANSACTION_REQUEST_CHECK_FREEZE, data, reply, 0)
                    reply.readException()
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }

            override fun onFreezeReasonResult(packageName: String, reason: String) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeString(packageName)
                    data.writeString(reason)
                    remote.transact(TRANSACTION_ON_FREEZE_REASON_RESULT, data, reply, 0)
                    reply.readException()
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }
        }

        companion object {
            const val DESCRIPTOR = "com.sidesand.millet.ISystemCallback"
            const val TRANSACTION_ON_CONFIG_CHANGED = FIRST_CALL_TRANSACTION + 0
            const val TRANSACTION_REQUEST_CHECK_FREEZE = FIRST_CALL_TRANSACTION + 1
            const val TRANSACTION_ON_FREEZE_REASON_RESULT = FIRST_CALL_TRANSACTION + 2

            fun asInterface(binder: IBinder?): ISystemCallback? {
                if (binder == null) return null
                val iin = binder.queryLocalInterface(DESCRIPTOR)
                return if (iin is ISystemCallback) iin else Proxy(binder)
            }
        }
    }
}
