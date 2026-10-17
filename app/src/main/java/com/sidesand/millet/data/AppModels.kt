package com.sidesand.millet.data

import android.graphics.drawable.Drawable

enum class FreezeType(val label: String) {
    V2("V2"),
    V1("V1"),
    KILL("Kill"),
    RUNNING("运行中")
}

data class ProcessDetail(
    val pid: Int,
    val processName: String,
    val rssKb: Long,
    val wchan: String,
    val state: String,
    val isFrozen: Boolean,
    val freezeType: FreezeType
)

data class RunningAppItem(
    val uid: Int,
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val isSystemApp: Boolean,
    val totalRssMb: Float,
    val processes: List<ProcessDetail>
) {
    val frozenCount: Int get() = processes.count { it.isFrozen }
    val totalCount: Int get() = processes.size
    val primaryFreezeType: FreezeType
        get() = when {
            processes.any { it.freezeType == FreezeType.V2 } -> FreezeType.V2
            processes.any { it.freezeType == FreezeType.V1 } -> FreezeType.V1
            processes.any { it.freezeType == FreezeType.KILL } -> FreezeType.KILL
            else -> FreezeType.RUNNING
        }
}

data class InstalledAppItem(
    val uid: Int,
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val isSystemApp: Boolean,
    val isBatteryOptIgnored: Boolean
)
