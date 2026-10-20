package com.syntholoc.ota

import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real HTTP and disk: the Android-independent delivery engine uses no mocked transport. */
class ClientTest {
    private lateinit var server: ServerSocket
    private val executor = Executors.newSingleThreadExecutor()
    private val files = ConcurrentHashMap<String, ByteArray>()
    private val requests = ConcurrentHashMap<String, AtomicInteger>()
    private val directory = Files.createTempDirectory("ota-android-").toFile()
    private val clients = mutableListOf<OtaClient>()
    private val prefix = "/ota/v1/distributions/dst/channels/production/"
    @Volatile private var generation = 0
    @Volatile private var denied = false
    @Volatile private var requestStarted: CountDownLatch? = null
    @Volatile private var resumeRequest: CountDownLatch? = null

    @Before fun start() {
        server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        executor.execute {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val path = input.readLine().split(' ')[1]
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            val separator = line.indexOf(':')
                            if (separator > 0) headers[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
                        }
                        requests.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
                        val bytes = files[path]
                        requestStarted?.countDown()
                        resumeRequest?.await(5, TimeUnit.SECONDS)
                        val etag = "\"generation-$generation\""
                        val status = when {
                            denied -> 403
                            bytes == null -> 404
                            path == prefix + "manifest.json" && headers["if-none-match"] == etag -> 304
                            else -> 200
                        }
                        val body = when (status) {
                            403 -> """{"code":"OTA_KEY_INVALID"}""".toByteArray()
                            404 -> """{"code":"OTA_NOT_FOUND"}""".toByteArray()
                            304 -> byteArrayOf()
                            else -> bytes!!
                        }
                        val output = socket.getOutputStream()
                        output.write("HTTP/1.1 $status Test\r\nContent-Length: ${body.size}\r\nETag: $etag\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        output.write(body)
                        output.flush()
                    }
                } catch (error: java.net.SocketException) {
                    if (!server.isClosed) throw error
                }
            }
        }
    }

    @After fun stop() {
        clients.forEach { it.close() }
        server.close()
        executor.shutdownNow()
        directory.deleteRecursively()
    }

    private fun client() = OtaClient(OtaConfig(
        baseUrl = "http://127.0.0.1:${server.localPort}", distributionId = "dst",
        catalogVersion = "app-v1", localeKey = "en", bundles = setOf("app")
    ), directory).also { clients.add(it) }

    private fun release(id: String, text: String): String {
        val body = JSONObject().put("hello", text).put("empty", "").toString().toByteArray()
        val hash = OtaClient.sha(body)
        val path = "assets/asset/$hash"
        val manifest = JSONObject().put("protocolVersion", 1).put("distributionId", "dst")
            .put("releaseId", id).put("catalogVersion", "app-v1")
            .put("createdAt", "2026-10-19T00:00:00Z").put("sourceDigest", "a".repeat(64))
            .put("requiredBundles", JSONArray(listOf("app")))
            .put("locales", JSONArray().put(JSONObject().put("localeKey", "en").put("languageTag", "en")
                .put("assets", JSONArray().put(JSONObject().put("id", "asset").put("bundle", "app")
                    .put("namespace", "app").put("format", "JSON_FLAT").put("path", path)
                    .put("sha256", hash).put("bytes", body.size).put("contentType", "application/json")))))
            .toString().toByteArray()
        files[prefix + "releases/$id/manifest.json"] = manifest
        files[prefix + "releases/$id/" + path] = body
        return prefix + "releases/$id/" + path
    }

    private fun publish(id: String) {
        generation++
        val path = prefix + "releases/$id/manifest.json"
        val body = files[path]!!
        files[prefix + "manifest.json"] = JSONObject().put("protocolVersion", 1)
            .put("distributionId", "dst").put("channel", "production").put("catalogVersion", "app-v1")
            .put("generation", generation).put("recommendedCheckIntervalSeconds", 900)
            .put("release", JSONObject().put("id", id).put("manifestPath", path)
                .put("sha256", OtaClient.sha(body)).put("bytes", body.size)).toString().toByteArray()
    }

    @Test fun updateActivationOfflineCacheAndRollback() {
        release("r1", "First"); publish("r1")
        val client = client()
        assertFalse(client.loadCached())
        assertEquals("Bundled", client.string("app", "hello") { "Bundled" })
        assertEquals("available", client.checkForUpdates().status)
        assertEquals("Bundled", client.string("app", "hello") { "Bundled" })
        client.activate("r1")
        assertEquals("First", client.string("app", "hello") { "Bundled" })
        assertEquals("", client.string("app", "empty") { "Bundled" })
        assertEquals("skipped", client.checkForUpdates().status)
        val restored = client()
        assertTrue(restored.loadCached())
        assertEquals("First", restored.string("app", "hello") { "Bundled" })
        val before = requests.values.sumOf { it.get() }
        assertEquals("unchanged", client.checkForUpdates(true).status)
        assertEquals(before + 1, requests.values.sumOf { it.get() })
        release("r2", "Second"); publish("r2")
        assertEquals("available", client.checkForUpdates(true).status)
        client.activate("r2")
        assertEquals("Second", client.string("app", "hello") { "Bundled" })
        publish("r1")
        assertEquals("available", client.checkForUpdates(true).status)
        client.activate("r1")
        assertEquals("First", client.string("app", "hello") { "Bundled" })
    }

    @Test fun corruptDownloadKeepsActiveAndForceCannotBypassBackoff() {
        release("r1", "First"); publish("r1")
        val client = client(); client.checkForUpdates(); client.activate("r1")
        val path = release("r2", "Second"); publish("r2")
        files[path] = "corrupt".toByteArray()
        assertEquals("error", client.checkForUpdates(true).status)
        assertEquals("First", client.string("app", "hello") { "Bundled" })
        val before = requests.values.sumOf { it.get() }
        assertEquals("skipped", client.checkForUpdates(true).status)
        assertEquals(before, requests.values.sumOf { it.get() })
        release("r2", "Second"); client.resetFailureState()
        assertEquals("available", client.checkForUpdates(true).status)
    }

    @Test fun revokedKeyIsCheckedBeforeConditionalAndPreservesOfflineContent() {
        release("r1", "First"); publish("r1")
        val client = client(); client.checkForUpdates(); client.activate("r1")
        denied = true
        assertEquals("OTA_KEY_INVALID", client.checkForUpdates(true).error)
        assertEquals("skipped", client.checkForUpdates(true).status)
        assertEquals("First", client.string("app", "hello") { "Bundled" })
        denied = false; client.resetFailureState()
        assertEquals("unchanged", client.checkForUpdates().status)
    }

    @Test fun cachedLaunchDoesNotWaitForAnotherClientsNetworkCheck() {
        release("r1", "First"); publish("r1")
        val client = client(); client.checkForUpdates(); client.activate("r1")
        client.resetFailureState()
        val started = CountDownLatch(1)
        val resume = CountDownLatch(1)
        requestStarted = started; resumeRequest = resume
        val refresh = client.refreshAsync()
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val restored = client()
            assertTrue(CompletableFuture.supplyAsync { restored.loadCached() }.get(2, TimeUnit.SECONDS))
            assertEquals("First", restored.string("app", "hello") { "Bundled" })
            assertFalse(refresh.isDone)
        } finally {
            resume.countDown()
        }
        assertEquals("unchanged", refresh.get(2, TimeUnit.SECONDS).status)
    }

    @Test fun clearCacheFencesInflightDownloadsAndDoesNotWaitForNetwork() {
        release("r1", "First");publish("r1")
        val client=client();val clearingClient=client()
        val started=CountDownLatch(1);val resume=CountDownLatch(1)
        requestStarted=started;resumeRequest=resume
        val refresh=client.refreshAsync()
        try {
            assertTrue(started.await(2,TimeUnit.SECONDS))
            assertEquals("skipped",clearingClient.checkForUpdates().status)
            CompletableFuture.runAsync {clearingClient.clearCache()}.get(1,TimeUnit.SECONDS)
        } finally {resume.countDown()}
        assertEquals("OTA_CANCELLED",refresh.get(2,TimeUnit.SECONDS).error)
        assertFalse(clearingClient.loadCached())
        requestStarted=null;resumeRequest=null
        assertEquals("available",clearingClient.checkForUpdates(true).status)
    }
}
