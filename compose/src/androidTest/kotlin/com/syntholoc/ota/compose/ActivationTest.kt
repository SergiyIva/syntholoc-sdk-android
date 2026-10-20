package com.syntholoc.ota.compose

import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.syntholoc.ota.OtaClient
import com.syntholoc.ota.OtaConfig
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ActivationTest {
    @get:Rule val compose=createComposeRule()
    @Test fun candidateDoesNotRenderUntilActivationAndClearReturnsToBundled() {
        val server=ServerSocket(0,16,InetAddress.getByName("127.0.0.1"))
        val executor=Executors.newSingleThreadExecutor();val files=ConcurrentHashMap<String,ByteArray>()
        executor.execute {
            while(!server.isClosed)try {
                server.accept().use {socket ->
                    val reader=socket.getInputStream().bufferedReader();val path=reader.readLine().split(' ')[1]
                    while(!reader.readLine().isNullOrEmpty()){}
                    val bytes=files[path]?:byteArrayOf()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());write(bytes);flush()
                    }
                }
            } catch(_:java.net.SocketException) {if(!server.isClosed)throw IllegalStateException("origin closed unexpectedly")}
        }
        val directory=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"ota-compose-${System.nanoTime()}")
        val client=OtaClient(OtaConfig("http://127.0.0.1:${server.localPort}","dist",catalogVersion="v1",localeKey="en",bundles=setOf("app")),directory)
        try {
            val prefix="/ota/v1/distributions/dist/channels/production/"
            val asset="""{"hello":"Downloaded"}""".toByteArray()
            fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
            val hash=sha(asset);val manifest=JSONObject().put("protocolVersion",1).put("distributionId","dist").put("releaseId","r1")
                .put("catalogVersion","v1").put("createdAt","2026-10-19T00:00:00Z").put("sourceDigest","a".repeat(64))
                .put("requiredBundles",JSONArray(listOf("app"))).put("locales",JSONArray().put(JSONObject().put("localeKey","en").put("languageTag","en")
                    .put("assets",JSONArray().put(JSONObject().put("id","asset").put("bundle","app").put("namespace","app").put("format","JSON_FLAT")
                        .put("contentType","application/json").put("path","assets/asset/$hash").put("sha256",hash).put("bytes",asset.size)))))
                .toString().toByteArray()
            files[prefix+"releases/r1/manifest.json"]=manifest;files[prefix+"releases/r1/assets/asset/$hash"]=asset
            files[prefix+"manifest.json"]=JSONObject().put("protocolVersion",1).put("distributionId","dist").put("channel","production").put("catalogVersion","v1")
                .put("generation",1).put("recommendedCheckIntervalSeconds",60).put("release",JSONObject().put("id","r1").put("manifestPath",prefix+"releases/r1/manifest.json")
                    .put("sha256",sha(manifest)).put("bytes",manifest.size)).toString().toByteArray()
            compose.setContent {val snapshot=rememberOtaSnapshot(client);BasicText(snapshot.string("app","hello") {"Bundled"})}
            compose.onNodeWithText("Bundled").assertIsDisplayed()
            assertEquals("available",client.checkForUpdates().status)
            compose.onNodeWithText("Bundled").assertIsDisplayed()
            client.activate("r1");compose.waitForIdle();compose.onNodeWithText("Downloaded").assertIsDisplayed()
            client.clearCache();compose.waitForIdle();compose.onNodeWithText("Bundled").assertIsDisplayed()
        } finally {client.close();server.close();executor.shutdownNow();directory.deleteRecursively()}
    }
}
