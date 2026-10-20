package com.syntholoc.ota.compose

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.syntholoc.ota.OtaClient
import com.syntholoc.ota.OtaResources
import com.syntholoc.ota.OtaSnapshot
import java.util.concurrent.atomic.AtomicBoolean

/** Observe activation only. Downloading a candidate never changes the rendered frame. */
@Composable
fun rememberOtaSnapshot(client:OtaClient):OtaSnapshot {
    var snapshot by remember(client) {mutableStateOf(client.snapshot())}
    DisposableEffect(client) {
        val handler=Handler(Looper.getMainLooper());val disposed=AtomicBoolean(false)
        val subscription=client.onActivation {value -> handler.post {if(!disposed.get())snapshot=value}}
        onDispose {disposed.set(true);subscription.close()}
    }
    return snapshot
}

/** Resource provider backed by one immutable snapshot for this composition. */
@Composable
fun rememberOtaResources(client:OtaClient,namespace:String):OtaResources {
    val snapshot=rememberOtaSnapshot(client)
    val context=LocalContext.current;val resources=context.resources
    val locale=LocalConfiguration.current.locales[0]
    return remember(snapshot,resources,namespace,locale,context.packageName) {
        OtaResources(snapshot,resources,namespace,locale,context.packageName)
    }
}
