package com.syntholoc.ota

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.Locale

internal object Wire {
    fun string(value:JSONObject,key:String):String {val text=value.get(key);require(text is String);return text}
    fun name(value:String)=value.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,79}"))
    fun hash(value:String)=value.matches(Regex("[a-f0-9]{64}"))
    fun text(value:String)=value.isNotEmpty() && value.length<=512 && value.toByteArray().size<=2048
    fun language(value:String)=text(value) && value.matches(Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*"))
    fun decode(bytes:ByteArray)=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    fun integer(value:JSONObject,key:String,minimum:Long,maximum:Long):Long {
        val number=value.get(key);require(number is Number && number.toDouble().isFinite() && number.toLong().toDouble()==number.toDouble())
        return number.toLong().also { require(it in minimum..maximum) }
    }
    fun asset(a:JSONObject) {
        for(key in listOf("id","bundle","namespace"))require(name(string(a,key)))
        require(hash(string(a,"sha256")));integer(a,"bytes",0,10_000_000)
        val types=mapOf("JSON_FLAT" to "application/json","JSON_NESTED" to "application/json","ANDROID_XML" to "application/xml",
            "IOS_STRINGS" to "text/plain","IOS_STRINGSDICT" to "application/xml","IOS_XCSTRINGS" to "application/json")
        require(types[string(a,"format")]==string(a,"contentType"))
        require(string(a,"path")=="assets/${string(a,"id")}/${string(a,"sha256")}")
    }
    fun channel(c:JSONObject) {
        require(integer(c,"protocolVersion",1,1)==1L)
        require(name(string(c,"distributionId")) && name(string(c,"channel")) && text(string(c,"catalogVersion")))
        integer(c,"generation",0,9_007_199_254_740_991);integer(c,"recommendedCheckIntervalSeconds",0,86400)
        val r=c.getJSONObject("release");require(name(string(r,"id")) && hash(string(r,"sha256")))
        integer(r,"bytes",0,2_000_000)
        require(string(r,"manifestPath")=="/ota/v1/distributions/${string(c,"distributionId")}/channels/${string(c,"channel")}/releases/${string(r,"id")}/manifest.json")
    }
    fun release(m:JSONObject) {
        integer(m,"protocolVersion",1,1)
        require(name(string(m,"distributionId")) && name(string(m,"releaseId")) && text(string(m,"catalogVersion")))
        require(hash(string(m,"sourceDigest")))
        val date=string(m,"createdAt")
        require(listOf("yyyy-MM-dd'T'HH:mm:ss.SSSX","yyyy-MM-dd'T'HH:mm:ssX").any { pattern ->
            val parser=SimpleDateFormat(pattern,Locale.US);parser.isLenient=false
            val position=java.text.ParsePosition(0);parser.parse(date,position)!=null && position.index==date.length
        })
        val required=m.getJSONArray("requiredBundles");require(required.length()<=50)
        val requiredNames=(0 until required.length()).map { val value=required.get(it);require(value is String && name(value));value }
        require(requiredNames.distinct().size==requiredNames.size)
        val locales=m.getJSONArray("locales");require(locales.length() in 1..100)
        val localeKeys=mutableSetOf<String>();val ids=mutableSetOf<String>();var total=0L
        for(i in 0 until locales.length()) {
            val l=locales.getJSONObject(i);require(text(string(l,"localeKey")) && language(string(l,"languageTag")))
            require(localeKeys.add(string(l,"localeKey")))
            val assets=l.getJSONArray("assets");require(assets.length()<=50);val bundles=mutableSetOf<String>()
            for(j in 0 until assets.length()) {
                val a=assets.getJSONObject(j);asset(a);require(ids.add(string(a,"id")) && bundles.add(string(a,"bundle")))
                total+=a.getLong("bytes");require(total<=100*1024*1024)
            }
            require(bundles.containsAll(requiredNames))
        }
    }
}
