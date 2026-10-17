package com.sidesand.millet.ipc

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel

interface SystemInterface : IInterface {
    fun isSystemHookActive(): Boolean
    fun isPowerKeeperActive(): Boolean
    fun registerCallback(callback: ISystemCallback?, clientName: String)
    fun unregisterCallback(callback: ISystemCallback?, clientName: String)
    fun notifyConfigChanged(key: String)
    fun pushConfigJson(jsonText: String)
    fun pullConfigJson(): String
    fun checkUnfreezeReason(packageName: String)
    fun reportUnfreezeReason(packageName: String, reason: String)
    fun syncNoRestrictApps(rawList: String?)
    fun getRunningProcessesJson(includeSystem: Boolean): String
    fun getLogsText(): String

    abstract class Stub : Binder(), SystemInterface {
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
                TRANSACTION_IS_SYSTEM_ACTIVE -> {
                    data.enforceInterface(DESCRIPTOR)
                    val res = isSystemHookActive()
                    reply?.writeNoException()
                    reply?.writeInt(if (res) 1 else 0)
                    true
                }
                TRANSACTION_IS_PK_ACTIVE -> {
                    data.enforceInterface(DESCRIPTOR)
                    val res = isPowerKeeperActive()
                    reply?.writeNoException()
                    reply?.writeInt(if (res) 1 else 0)
                    true
                }
                TRANSACTION_REGISTER_CALLBACK -> {
                    data.enforceInterface(DESCRIPTOR)
                    val cb = ISystemCallback.Stub.asInterface(data.readStrongBinder())
                    val name = data.readString().orEmpty()
                    registerCallback(cb, name)
                    reply?.writeNoException()
                    true
                }
                TRANSACTION_UNREGISTER_CALLBACK -> {
                    data.enforceInterface(DESCRIPTOR)
                    val cb = ISystemCallback.Stub.asInterface(data.readStrongBinder())
                    val name = data.readString().orEmpty()
                    unregisterCallback(cb, name)
                    reply?.writeNoException()
                    true
                }
                TRANSACTION_NOTIFY_CONFIG -> {
                    data.enforceInterface(DESCRIPTOR)
                    notifyConfigChanged(data.readString().orEmpty())
                    reply?.writeNoException()
                    true
                }
                TRANSACTION_PUSH_CONFIG_JSON -> {
                    data.enforceInterface(DESCRIPTOR)
                    pushConfigJson(data.readString().orEmpty())
                    reply?.writeNoException()
                    true
                }
                TRANSACTION_PULL_CONFIG_JSON -> {
                    data.enforceInterface(DESCRIPTOR)
                    val json = pullConfigJson()
                    reply?.writeNoException()
                    reply?.writeString(json)
                    true
                }
                TRANSACTION_CHECK_REASON -> {
                    data.enforceInterface(DESCRIPTOR)
                    checkUnfreezeReason(data.readString().orEmpty())
                    reply?.writeNoException()
                    true
                }
                TRANSACTION_REPORT_REASON -> {
                    data.enforceInterface(DESCRIPTOR)
                    val pkg = data.readString().orEmpty()
                    val reason = data.readString().orEmpty()
                    reportUnfreezeReason(pkg, reason)
                    reply?.writeNoException()
                    true
                }
                TRANSACTION_SYNC_NO_RESTRICT -> {
                    data.enforceInterface(DESCRIPTOR)
                    syncNoRestrictApps(data.readString())
                    reply?.writeNoException()
                    true
                }
                TRANSACTION_GET_RUNNING_PROCS -> {
                    data.enforceInterface(DESCRIPTOR)
                    val includeSys = data.readInt() != 0
                    val json = getRunningProcessesJson(includeSys)
                    reply?.writeNoException()
                    reply?.writeString(json)
                    true
                }
                TRANSACTION_GET_LOGS_TEXT -> {
                    data.enforceInterface(DESCRIPTOR)
                    val logs = getLogsText()
                    reply?.writeNoException()
                    reply?.writeString(logs)
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        }

        private class Proxy(private val remote: IBinder) : SystemInterface {
            override fun asBinder(): IBinder = remote

            override fun isSystemHookActive(): Boolean {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                return try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    remote.transact(TRANSACTION_IS_SYSTEM_ACTIVE, data, reply, 0)
                    reply.readException()
                    reply.readInt() != 0
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }

            override fun isPowerKeeperActive(): Boolean {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                return try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    remote.transact(TRANSACTION_IS_PK_ACTIVE, data, reply, 0)
                    reply.readException()
                    reply.readInt() != 0
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }

            override fun registerCallback(callback: ISystemCallback?, clientName: String) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeStrongBinder(callback?.asBinder())
                    data.writeString(clientName)
                    remote.transact(TRANSACTION_REGISTER_CALLBACK, data, reply, 0)
                    reply.readException()
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }

            override fun unregisterCallback(callback: ISystemCallback?, clientName: String) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeStrongBinder(callback?.asBinder())
                    data.writeString(clientName)
                    remote.transact(TRANSACTION_UNREGISTER_CALLBACK, data, reply, 0)
                    reply.readException()
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }

            override fun notifyConfigChanged(key: String) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeString(key)
                    remote.transact(TRANSACTION_NOTIFY_CONFIG, data, reply, 0)
                    reply.readException()
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }

            override fun pushConfigJson(jsonText: String) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeString(jsonText)
                    remote.transact(TRANSACTION_PUSH_CONFIG_JSON, data, reply, 0)
                    reply.readException()
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }

            override fun pullConfigJson(): String {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                return try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    remote.transact(TRANSACTION_PULL_CONFIG_JSON, data, reply, 0)
                    reply.readException()
                    reply.readString().orEmpty()
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }

            override fun checkUnfreezeReason(packageName: String) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeString(packageName)
                    remote.transact(TRANSACTION_CHECK_REASON, data, reply, 0)
                    reply.readException()
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }

            override fun reportUnfreezeReason(packageName: String, reason: String) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeString(packageName)
                    data.writeString(reason)
                    remote.transact(TRANSACTION_REPORT_REASON, data, reply, 0)
                    reply.readException()
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }

            override fun syncNoRestrictApps(rawList: String?) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeString(rawList)
                    remote.transact(TRANSACTION_SYNC_NO_RESTRICT, data, reply, 0)
                    reply.readException()
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }

            override fun getRunningProcessesJson(includeSystem: Boolean): String {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                return try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeInt(if (includeSystem) 1 else 0)
                    remote.transact(TRANSACTION_GET_RUNNING_PROCS, data, reply, 0)
                    reply.readException()
                    reply.readString().orEmpty()
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }

            override fun getLogsText(): String {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                return try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    remote.transact(TRANSACTION_GET_LOGS_TEXT, data, reply, 0)
                    reply.readException()
                    reply.readString().orEmpty()
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }
        }

        companion object {
            const val DESCRIPTOR = "com.sidesand.millet.SystemInterface"
            const val TRANSACTION_IS_SYSTEM_ACTIVE = FIRST_CALL_TRANSACTION + 0
            const val TRANSACTION_IS_PK_ACTIVE = FIRST_CALL_TRANSACTION + 1
            const val TRANSACTION_REGISTER_CALLBACK = FIRST_CALL_TRANSACTION + 2
            const val TRANSACTION_UNREGISTER_CALLBACK = FIRST_CALL_TRANSACTION + 3
            const val TRANSACTION_NOTIFY_CONFIG = FIRST_CALL_TRANSACTION + 4
            const val TRANSACTION_CHECK_REASON = FIRST_CALL_TRANSACTION + 5
            const val TRANSACTION_REPORT_REASON = FIRST_CALL_TRANSACTION + 6
            const val TRANSACTION_SYNC_NO_RESTRICT = FIRST_CALL_TRANSACTION + 7
            const val TRANSACTION_GET_RUNNING_PROCS = FIRST_CALL_TRANSACTION + 8
            const val TRANSACTION_PUSH_CONFIG_JSON = FIRST_CALL_TRANSACTION + 9
            const val TRANSACTION_PULL_CONFIG_JSON = FIRST_CALL_TRANSACTION + 10
            const val TRANSACTION_GET_LOGS_TEXT = FIRST_CALL_TRANSACTION + 11

            fun asInterface(binder: IBinder?): SystemInterface? {
                if (binder == null) return null
                val iin = binder.queryLocalInterface(DESCRIPTOR)
                return if (iin is SystemInterface) iin else Proxy(binder)
            }
        }
    }
}
