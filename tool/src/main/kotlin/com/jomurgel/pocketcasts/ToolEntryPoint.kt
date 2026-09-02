package com.jomurgel.pocketcasts

import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.shared.LightServerData
import kotlinx.coroutines.flow.StateFlow

@EntryPoint
object ToolEntryPoint : LightEntryPoint {
    override suspend fun onToolCreate(
        serverData: StateFlow<LightServerData?>,
    ) {
        // No push registration needed yet; sync is pull-based in v1.
    }

    override suspend fun onPushNotification(
        data: ByteArray,
    ) {
        // Not used in v1.
    }
}
