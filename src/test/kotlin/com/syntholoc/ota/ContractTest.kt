package com.syntholoc.ota
import org.junit.Assert.*
import org.junit.Test
class ContractTest {
    @Test fun jsonPreservesLiteralDotsAndEmptyStrings() {
        val c=Catalog.parse("JSON_FLAT", """{"literal.dot":"Hello %1${'$'}s","empty":""}""".toByteArray())
        assertEquals("",c.strings["empty"]);assertTrue(c.strings.containsKey("literal.dot"))
    }
    @Test fun rejectsPrototypeKeys() { assertThrows(IllegalArgumentException::class.java){Catalog.parse("JSON_FLAT","""{"__proto__":"x"}""".toByteArray())} }
    @Test fun rejectsInvalidUtf8() {
        val bytes = "{\"text\":\"".toByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + "\"}".toByteArray()
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { Catalog.parse("JSON_FLAT", bytes) }
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { Catalog.parse("ANDROID_XML", bytes) }
        assertThrows(IllegalArgumentException::class.java) { Catalog.parse("JSON_FLAT", "{\"text\":\"UTF16\"}".toByteArray(Charsets.UTF_16LE)) }
    }
    @Test fun rejectsXmlDoctypeBeforeParsing() {
        assertThrows(IllegalArgumentException::class.java) {
            Catalog.parse("ANDROID_XML", """<!DOCTYPE resources [<!ENTITY value "external">]><resources/>""".toByteArray())
        }
    }
    @Test fun verifiesSha256() {
        val bytes="hello".toByteArray();OtaClient.verify(bytes,"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",5)
        assertThrows(IllegalArgumentException::class.java){OtaClient.verify(bytes,"0".repeat(64),5)}
    }
    @Test fun sharedWireFixture() {
        fun fixture(name:String)=javaClass.getResourceAsStream("/$name.json")!!.use { it.readBytes() }
        val channel=org.json.JSONObject(fixture("channel").toString(Charsets.UTF_8))
        val release=org.json.JSONObject(fixture("release").toString(Charsets.UTF_8))
        val reference=channel.getJSONObject("release")
        Wire.channel(channel);Wire.release(release)
        OtaClient.verify(fixture("release"),reference.getString("sha256"),reference.getInt("bytes"))
        val asset=release.getJSONArray("locales").getJSONObject(0).getJSONArray("assets").getJSONObject(0)
        OtaClient.verify(fixture("asset"),asset.getString("sha256"),asset.getInt("bytes"))
        assertEquals("",Catalog.parse("JSON_FLAT",fixture("asset")).strings["empty"])
    }

    @Test fun invalidWireCannotSmuggleDuplicateAssetsOrUnsafePaths() {
        val bytes=javaClass.getResourceAsStream("/release.json")!!.use {it.readBytes()}
        val original=bytes.toString(Charsets.UTF_8)
        val release=org.json.JSONObject(original)
        val asset=release.getJSONArray("locales").getJSONObject(0).getJSONArray("assets").getJSONObject(0)
        asset.put("path","../escape")
        assertThrows(IllegalArgumentException::class.java) {Wire.release(release)}
        val duplicate=org.json.JSONObject(original)
        val assets=duplicate.getJSONArray("locales").getJSONObject(0).getJSONArray("assets")
        assets.put(assets.getJSONObject(0))
        assertThrows(IllegalArgumentException::class.java) {Wire.release(duplicate)}
    }

}
