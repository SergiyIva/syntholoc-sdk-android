package com.syntholoc.ota

import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min

/** Immutable scope; create a new client when the host selects another language or channel. */
data class OtaConfig(
    val baseUrl: String, val distributionId: String, val channel: String = "production",
    val catalogVersion: String, val localeKey: String, val bundles: Set<String>,
    val fallbackLocaleKeys: List<String> = emptyList(), val checkIntervalSeconds: Long = 900,
    val credential: (() -> String)? = null
)
data class OtaUpdate(val status: String, val releaseId: String? = null, val error: String? = null)
class OtaException(val code: String, val status: Int = 0, val retryAfterSeconds: Long = 0) : Exception(code)

/** All downloaded catalogs are data. No source evaluation, method swizzling or resource-id creation. */
class OtaClient(configuration: OtaConfig, cacheDirectory: File) : Closeable {
    private val config=configuration.copy(bundles=configuration.bundles.toSet(),fallbackLocaleKeys=configuration.fallbackLocaleKeys.toList())
    private val prefix = "/ota/v1/distributions/${config.distributionId}/channels/${config.channel}/"
    private val origin = URI(config.baseUrl).let { URI(it.scheme, null, it.host, it.port, "/", null, null) }
    private val scope = listOf(origin.toString(), config.distributionId, config.channel, config.catalogVersion, config.localeKey,
        config.bundles.sorted().joinToString(","), config.fallbackLocaleKeys.joinToString(",")).joinToString("|")
    private val directory = File(cacheDirectory, sha(scope.toByteArray()))
    private val lock = locks.computeIfAbsent(directory.absolutePath) { ReentrantLock() }
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var activeSnapshot=OtaSnapshot.BUNDLED
    private val listeners=java.util.concurrent.CopyOnWriteArraySet<(OtaSnapshot)->Unit>()
    private var refresh: CompletableFuture<OtaUpdate>? = null
    @Volatile private var nextAttemptNanos:Long?=null
    @Volatile private var nextCheckNanos:Long?=null
    private val locales = listOf(config.localeKey) + config.fallbackLocaleKeys
    init {
        require(origin.scheme == "https" || (origin.scheme == "http" && origin.host in setOf("localhost", "127.0.0.1")))
        require(URI(config.baseUrl).rawUserInfo == null && URI(config.baseUrl).rawQuery == null && URI(config.baseUrl).rawFragment == null)
        require(Wire.name(config.distributionId) && Wire.name(config.channel))
        require(!origin.host.isNullOrEmpty())
        require(config.catalogVersion.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,79}")))
        require(config.bundles.size in 1..50 && config.bundles.all { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,79}")) })
        require(locales.size in 1..100 && locales.distinct().size == locales.size && locales.all { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9@._-]{0,79}")) })
        directory.mkdirs()
    }
    private fun <T> locked(block: () -> T): T = lock.withLock {
        File(directory, "lock").outputStream().channel.use { channel -> channel.lock().use { block() } }
    }
    private fun state(): JSONObject = try { JSONObject(Wire.decode(readBounded(File(directory,"state.json"),16*1024*1024))) } catch (_: Exception) { JSONObject() }
    private fun readBounded(file:File,limit:Int):ByteArray {
        require(file.length()<=limit)
        return file.inputStream().use { stream ->
            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(16384)
            while(true) {val n=stream.read(buffer);if(n<0)break;require(out.size()+n<=limit);out.write(buffer,0,n)}
            out.toByteArray()
        }
    }
    private fun update(epoch:Long,block:(JSONObject)->Unit) = locked {
        val current=state();if(current.optLong("cacheEpoch")!=epoch)throw OtaException("OTA_CANCELLED")
        block(current);save(current)
    }
    private fun <T> checking(block:()->T):T? {
        val checkLock=refreshLocks.computeIfAbsent(directory.absolutePath) { ReentrantLock() }
        if(!checkLock.tryLock())return null
        try {
            java.io.RandomAccessFile(File(directory,"refresh.lock"),"rw").use { file ->
                val acquired=try {file.channel.tryLock()} catch(_:java.nio.channels.OverlappingFileLockException) {null} ?: return null
                acquired.use {return block()}
            }
        } finally {checkLock.unlock()}
    }
    private fun save(state: JSONObject) = atomic(File(directory,"state.json"),state.put("lastUsedAt",System.currentTimeMillis()).toString().toByteArray())
    private fun atomic(file: File, bytes: ByteArray) {
        val temporary = File.createTempFile(file.name,".tmp",file.parentFile)
        temporary.outputStream().use { stream -> stream.write(bytes); stream.fd.sync() }
        // Both files share the app-private directory. Android's rename replaces the
        // destination atomically and is available on API 24 (java.nio.file needs 26).
        if (!temporary.renameTo(file)) {temporary.delete();throw java.io.IOException("OTA_CACHE_WRITE_FAILED")}
    }
    private fun safeUrl(path: String, base: URI = origin): URI {
        require(!path.contains('%') && !path.contains('\\') && !path.contains('?') && !path.contains('#') && path.split('/').none { it == ".." || it == "." })
        val url = base.resolve(path)
        require(url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port && url.rawUserInfo == null && url.path.startsWith(prefix))
        return url
    }
    private data class Reply(val bytes: ByteArray?, val etag: String?)
    private fun request(uri: URI, maxBytes: Int, etag: String? = null): Reply {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val conn = uri.toURL().openConnection() as HttpURLConnection
        connection = conn
        try {
            conn.instanceFollowRedirects = false; conn.connectTimeout = 15000; conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent","SyntholocOTA/0.1 (Android)")
            etag?.let { conn.setRequestProperty("If-None-Match",it) }
            config.credential?.let { conn.setRequestProperty("Authorization","Bearer ${it()}") }
            if (conn.responseCode == 304) return Reply(null,conn.getHeaderField("ETag"))
            if (conn.responseCode != 200) {
                val code = try { JSONObject(conn.errorStream.bufferedReader().use { reader -> val chars=CharArray(4096);val n=reader.read(chars);String(chars,0,maxOf(0,n)) }).optString("code").takeIf { it.matches(Regex("OTA_[A-Z0-9_]{1,100}")) } ?: "OTA_HTTP_${conn.responseCode}" } catch (_: Exception) { "OTA_HTTP_${conn.responseCode}" }
                throw OtaException(code,conn.responseCode,retryAfter(conn.getHeaderField("Retry-After")))
            }
            if (conn.contentLengthLong > maxBytes) throw OtaException("OTA_LIMIT_EXCEEDED")
            val out = java.io.ByteArrayOutputStream()
            conn.inputStream.use { stream ->
                val buffer = ByteArray(16384)
                while (true) { if (Thread.currentThread().isInterrupted) throw InterruptedException(); val n=stream.read(buffer); if(n<0) break; if(out.size()+n>maxBytes) throw OtaException("OTA_LIMIT_EXCEEDED");out.write(buffer,0,n) }
            }
            return Reply(out.toByteArray(),conn.getHeaderField("ETag"))
        } finally { conn.disconnect(); connection=null }
    }
    // State is atomically renamed and blobs are immutable. Startup reads never
    // take the writer lock, which may be held by a background network check.
    fun loadCached(): Boolean {
        val s=state()
        for (name in listOf("active","previous")) {
            val set=s.optJSONObject(name) ?: continue
            try { activeSnapshot=OtaSnapshot(set.getString("releaseId"),parseSet(set),locales);File(directory,"state.json").setLastModified(System.currentTimeMillis());return true } catch (_: Exception) { }
        }
        activeSnapshot=OtaSnapshot.BUNDLED;return false
    }
    @Synchronized fun refreshAsync(): CompletableFuture<OtaUpdate> {
        refresh?.let { if(!it.isDone) return it }
        return CompletableFuture.supplyAsync({ checkForUpdates() },executor).also { refresh=it }
    }
    fun checkForUpdates(forceRefresh: Boolean = false): OtaUpdate = checking check@{
        val s=state();val epoch=s.optLong("cacheEpoch");val now=System.currentTimeMillis()
        if(s.optString("stopped") == "retired")return@check OtaUpdate("retired")
        if(s.optString("stopped") == "auth")return@check OtaUpdate("skipped")
        fun future(key:String): Boolean {
            val monotonic=if(key=="nextAttempt")nextAttemptNanos else nextCheckNanos
            if(monotonic!=null)return System.nanoTime()<monotonic
            val remaining=s.optLong(key)-now;return remaining in 1..95040000
        }
        if(future("nextAttempt") || (!forceRefresh && future("nextCheck")))return@check OtaUpdate("skipped")
        try {
            try {collect();pruneScopes()} catch(_:Exception) {}
            val channelUrl=safeUrl("${prefix}manifest.json")
            var reply=request(channelUrl,2_000_000,s.optString("etag").takeIf { it.isNotEmpty() })
            if(reply.bytes==null && !s.has("channel"))reply=request(channelUrl,2_000_000)
            val c=reply.bytes?.let { JSONObject(Wire.decode(it)) } ?: s.getJSONObject("channel");Wire.channel(c)
            require(c.getInt("protocolVersion")==1 && c.getString("distributionId")==config.distributionId && c.getString("channel")==config.channel && c.getString("catalogVersion")==config.catalogVersion)
            val generation=c.getLong("generation");require(generation>=s.optLong("highestGeneration"))
            val release=c.getJSONObject("release");val id=release.getString("id")
            val manifestPath="${prefix}releases/$id/manifest.json";require(release.getString("manifestPath")==manifestPath)
            update(epoch) { current ->
                require(generation>=current.optLong("highestGeneration"))
                current.put("channel",c).put("highestGeneration",generation);reply.etag?.let {current.put("etag",it)}
            }
            val active=s.optJSONObject("active")
            if(active?.getString("releaseId")==id) {
                try { val parsed=parseSet(active);success(epoch,c);update(epoch) {activeSnapshot=OtaSnapshot(id,parsed,locales)};return@check OtaUpdate("unchanged",id) } catch (_: Exception) { }
            }
            val manifestUrl=safeUrl(manifestPath)
            val body=request(manifestUrl,2_000_000).bytes ?: throw OtaException("OTA_MANIFEST_MISSING")
            verify(body,release.getString("sha256"),release.getInt("bytes"))
            val m=JSONObject(Wire.decode(body));Wire.release(m)
            require(m.getInt("protocolVersion")==1 && m.getString("distributionId")==config.distributionId && m.getString("releaseId")==id && m.getString("catalogVersion")==config.catalogVersion)
            val required=m.getJSONArray("requiredBundles").strings();require(config.bundles.containsAll(required))
            val mappings=m.getJSONArray("locales");require(mappings.length() in 1..100)
            val byLocale=mutableMapOf<String,JSONObject>()
            for(i in 0 until mappings.length()) {val l=mappings.getJSONObject(i);require(byLocale.put(l.getString("localeKey"),l)==null)}
            val assets=JSONArray()
            for(locale in locales) {
                val entries=byLocale[locale]?.getJSONArray("assets") ?: throw OtaException("OTA_LOCALE_UNKNOWN")
                require(entries.length()<=50);val found=mutableSetOf<String>()
                for(i in 0 until entries.length()) {
                    val a=entries.getJSONObject(i);val bundle=a.getString("bundle");require(found.add(bundle))
                    if(bundle !in config.bundles) continue
                    val hash=a.getString("sha256");require(hash.matches(Regex("[a-f0-9]{64}")))
                    val path="assets/${a.getString("id")}/$hash";require(a.getString("path")==path)
                    val blob=File(directory,hash)
                    var bytes=try {readBounded(blob,10_000_000)} catch(_:Exception) {byteArrayOf()}
                    if(bytes.size!=a.getInt("bytes") || sha(bytes)!=hash) {
                        bytes=request(safeUrl(path,manifestUrl),10_000_000).bytes ?: throw OtaException("OTA_ASSET_MISSING")
                        verify(bytes,hash,a.getInt("bytes"));Catalog.parse(a.getString("format"),bytes);atomic(blob,bytes)
                    }
                    assets.put(JSONObject().put("localeKey",locale).put("languageTag",byLocale.getValue(locale).getString("languageTag")).put("asset",a))
                }
                require(found.containsAll(config.bundles))
            }
            val candidate=JSONObject().put("releaseId",id).put("generation",generation).put("assets",assets)
            parseSet(candidate);success(epoch,c,candidate);OtaUpdate("available",id)
        } catch(e:Exception) {
            if(state().optLong("cacheEpoch")!=epoch)return@check OtaUpdate("error",error="OTA_CANCELLED")
            val wire=e as? OtaException;val failures=min(1000,s.optInt("failures")+1)
            val stopped=if(wire?.status==410 && wire.code=="OTA_DISTRIBUTION_RETIRED")"retired" else if(wire?.status==401||wire?.status==403)"auth" else null

            val delay=if(wire?.status==404)3600L else min(3600L,15L shl min(failures,8))
            val delayMillis=maxOf((wire?.retryAfterSeconds?:0)*1000,(delay*1000*(0.8+Math.random()*0.4)).toLong())
            val deadline=now+delayMillis;nextAttemptNanos=System.nanoTime()+delayMillis*1_000_000
            try {update(epoch) {current ->
                stopped?.let {current.put("stopped",it)}
                current.put("failures",failures).put("nextAttempt",deadline)
            }} catch(_:Exception) {return@check OtaUpdate("error",error="OTA_CANCELLED")}
            OtaUpdate(if(stopped=="retired")"retired" else "error",error=wire?.code?:"OTA_INVALID_UPDATE")
        }
    } ?: OtaUpdate("skipped")
    private fun success(epoch:Long,c:JSONObject,candidate:JSONObject?=null) = update(epoch) { s ->
        require(c.getLong("generation")>=s.optLong("highestGeneration"))
        candidate?.let {s.put("candidate",it)}
        s.put("lastChecked",System.currentTimeMillis()).put("failures",0).put("nextAttempt",0)
        val interval=maxOf(config.checkIntervalSeconds.coerceIn(60,86400),c.optLong("recommendedCheckIntervalSeconds",900).coerceIn(60,86400))
        val delay=(interval*1000*(0.9+Math.random()*0.2)).toLong()
        s.put("nextCheck",System.currentTimeMillis()+delay);nextCheckNanos=System.nanoTime()+delay*1_000_000;nextAttemptNanos=null
    }
    fun activate(id:String) { locked {
        val s=state();val set=s.getJSONObject("candidate")
        require(set.getString("releaseId")==id && set.getLong("generation")>=s.optLong("highestGeneration"))
        val parsed=parseSet(set);s.optJSONObject("active")?.let { s.put("previous",it) };s.put("active",set);s.remove("candidate");save(s)
        activeSnapshot=OtaSnapshot(id,parsed,locales)
    };collect();notifyActivation() }
    private fun collect() = locked {
        val file=File(directory,"state.json")
        val current=state()
        if(file.exists() && current.length()==0)return@locked
        val keep=mutableSetOf<String>()
        for(name in listOf("active","previous","candidate")) {
            val entries=current.optJSONObject(name)?.optJSONArray("assets")?:continue
            for(i in 0 until entries.length())keep.add(entries.getJSONObject(i).getJSONObject("asset").getString("sha256"))
        }
        val cutoff=System.currentTimeMillis()-86400000
        directory.listFiles()?.forEach {blob -> if(Wire.hash(blob.name) && blob.name !in keep && blob.lastModified()<cutoff)blob.delete()}
    }
    private fun pruneScopes() {
        val others=directory.parentFile?.listFiles()?.filter {it.isDirectory && Wire.hash(it.name) && it!=directory}
            ?.sortedByDescending {File(it,"state.json").lastModified()}?:return
        for(old in others.drop(7)) {
            val refreshLock=refreshLocks.computeIfAbsent(old.absolutePath) {ReentrantLock()}
            if(!refreshLock.tryLock())continue
            try {
                java.io.RandomAccessFile(File(old,"refresh.lock"),"rw").use {refreshFile ->
                    val network=try {refreshFile.channel.tryLock()} catch(_:java.nio.channels.OverlappingFileLockException) {null}?:return@use
                    network.use {
                        locks.computeIfAbsent(old.absolutePath) {ReentrantLock()}.withLock {
                            java.io.RandomAccessFile(File(old,"lock"),"rw").use {metadata -> metadata.channel.lock().use {
                                val file=File(old,"state.json")
                                val value=try {JSONObject(Wire.decode(readBounded(file,16*1024*1024)))} catch(_:Exception) {return@withLock}
                                val refs=listOf("active","previous","candidate","channel")
                                if(refs.any {value.has(it)}) {
                                    refs.forEach {value.remove(it)};value.remove("etag")
                                    value.put("cacheEpoch",value.optLong("cacheEpoch")+1)
                                    atomic(file,value.toString().toByteArray())
                                }
                                val cutoff=System.currentTimeMillis()-86400000
                                old.listFiles()?.forEach {blob -> if(Wire.hash(blob.name) && blob.lastModified()<cutoff)blob.delete()}
                            }}
                        }
                    }
                }
            } finally {refreshLock.unlock()}
        }
    }
    private fun parseSet(set:JSONObject):Map<String,Catalog> {
        val result=mutableMapOf<String,Catalog>();val entries=set.getJSONArray("assets");require(entries.length()<=5000)
        for(i in 0 until entries.length()) {
            val entry=entries.getJSONObject(i);val a=entry.getJSONObject("asset");Wire.asset(a);val hash=a.getString("sha256");require(locales.contains(entry.getString("localeKey")) && config.bundles.contains(a.getString("bundle")))
            val bytes=readBounded(File(directory,hash),10_000_000);verify(bytes,hash,a.getInt("bytes"));File(directory,hash).setLastModified(System.currentTimeMillis())
            val key="${entry.getString("localeKey")}/${a.getString("namespace")}";require(result.put(key,Catalog.parse(a.getString("format"),bytes).copy(languageTag=entry.optString("languageTag").takeIf {Wire.language(it)}))==null)
        }
        require(locales.all {locale -> config.bundles.all {bundle -> (0 until entries.length()).count {entries.getJSONObject(it).getString("localeKey")==locale && entries.getJSONObject(it).getJSONObject("asset").getString("bundle")==bundle}==1}})
        return result
    }
    /** Empty strings are values; missing entries fall through to bundled resources. */
    fun snapshot():OtaSnapshot=activeSnapshot
    fun onActivation(listener:(OtaSnapshot)->Unit):Closeable {
        listeners.add(listener);listener(activeSnapshot);return Closeable {listeners.remove(listener)}
    }
    private fun notifyActivation() {val snapshot=activeSnapshot;listeners.forEach {listener -> try {listener(snapshot)} catch(_:Exception) {}}}
    fun string(namespace:String,key:String,fallback:()->String)=activeSnapshot.string(namespace,key,fallback)
    fun quantity(namespace:String,key:String,category:String,fallback:()->String)=activeSnapshot.quantity(namespace,key,category,fallback)
    fun stringArray(namespace:String,key:String,fallback:()->Array<String>)=activeSnapshot.stringArray(namespace,key,fallback)
    fun resetFailureState() = locked {nextAttemptNanos=null;nextCheckNanos=null;val s=state();s.put("cacheEpoch",s.optLong("cacheEpoch")+1);s.remove("stopped");s.remove("nextAttempt");s.remove("nextCheck");save(s)}
    fun clearCache() {locked {nextAttemptNanos=null;nextCheckNanos=null;val epoch=state().optLong("cacheEpoch")+1;save(JSONObject().put("cacheEpoch",epoch));activeSnapshot=OtaSnapshot.BUNDLED};notifyActivation()}
    override fun close() { connection?.disconnect();executor.shutdownNow() }
    companion object {
        private fun retryAfter(header:String?):Long {
            header?:return 0
            header.toLongOrNull()?.let {return it.coerceIn(0,86400)}
            return try {val format=java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z",java.util.Locale.US)
                ((format.parse(header)!!.time-System.currentTimeMillis())/1000).coerceIn(0,86400)
            } catch(_:Exception) {0}
        }
        private val locks=ConcurrentHashMap<String,ReentrantLock>()
        private val refreshLocks=ConcurrentHashMap<String,ReentrantLock>()
        internal fun sha(bytes:ByteArray):String=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        internal fun verify(bytes:ByteArray,hash:String,size:Int){require(size in 0..10_000_000 && bytes.size==size && sha(bytes)==hash)}
        private fun JSONArray.strings()=(0 until length()).map { getString(it) }
    }
}
