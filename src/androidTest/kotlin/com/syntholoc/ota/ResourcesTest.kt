package com.syntholoc.ota

import android.content.Context
import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.syntholoc.ota.test.R
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResourcesTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().context
    private fun provider():OtaResources {
        val xml="""<resources>
            <string name="ota_title">Hello <b>bold</b> &amp; <i>italic</i></string>
            <string name="ota_hint"></string><string name="ota_format">%1${'$'}d</string>
            <plurals name="ota_count"><item quantity="one">%d один</item><item quantity="few">%d несколько</item><item quantity="many">%d много</item><item quantity="other">%d другое</item></plurals>
            <string-array name="ota_array"><item>Первый</item><item>Второй</item></string-array>
        </resources>"""
        val catalog=Catalog.parse("ANDROID_XML",xml.toByteArray())
        val snapshot=OtaSnapshot("release1",mapOf("ru/app" to catalog),listOf("ru"))
        return OtaResources(snapshot,context.resources,"app",Locale.forLanguageTag("ru"),context.packageName)
    }
    @Test fun styledStringsPluralsArraysAndBundledFallback() {
        val provider=provider();val text=provider.getText(R.string.ota_title)
        assertEquals("Hello bold & italic",text.toString());assertTrue(text is Spanned)
        val spans=(text as Spanned).getSpans(0,text.length,StyleSpan::class.java)
        assertTrue(spans.any {it.style==Typeface.BOLD});assertTrue(spans.any {it.style==Typeface.ITALIC})
        assertEquals("",provider.getString(R.string.ota_hint))
        assertEquals("Bundled removed",provider.getString(R.string.ota_removed))
        assertEquals("Bundled name",provider.getString(R.string.ota_format,"name"))
        assertEquals("1 один",provider.getQuantityString(R.plurals.ota_count,1,1))
        assertEquals("2 несколько",provider.getQuantityString(R.plurals.ota_count,2,2))
        assertEquals("5 много",provider.getQuantityString(R.plurals.ota_count,5,5))
        assertArrayEquals(arrayOf("Первый","Второй"),provider.getStringArray(R.array.ota_array))
        assertEquals(context.getString(android.R.string.ok),provider.getString(android.R.string.ok))
    }
    @Test fun contextAndXmlInflationUseTheExplicitFactoryAdapter() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val provider=provider();val wrapped=provider.wrap(context)
            assertEquals("Hello bold & italic",wrapped.getString(R.string.ota_title))
            val inflater=LayoutInflater.from(wrapped).cloneInContext(wrapped)
            inflater.factory2=object:LayoutInflater.Factory2 {
                override fun onCreateView(parent:View?,name:String,context:Context,attrs:AttributeSet):View? =
                    if(name=="TextView")TextView(context,attrs).also {provider.localize(it,attrs)} else null
                override fun onCreateView(name:String,context:Context,attrs:AttributeSet):View?=onCreateView(null,name,context,attrs)
            }
            val view=inflater.inflate(R.layout.ota_sample,null) as TextView
            assertEquals("Hello bold & italic",view.text.toString());assertTrue(view.text is Spanned)
            assertEquals("",view.hint.toString());assertEquals("Bundled removed",view.contentDescription)
        }
    }
    @Test fun unsupportedMarkupAndUnsafeXmlFailAtomically() {
        for(xml in listOf("<resources><string name=\"x\"><script>bad</script></string></resources>",
            "<!DOCTYPE resources [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><resources/>",
            "<resources><plurals name=\"x\"><item quantity=\"bad\">bad</item></plurals></resources>")) {
            try {Catalog.parse("ANDROID_XML",xml.toByteArray());fail("unsafe XML accepted")} catch(_:IllegalArgumentException) {}
        }
    }
}
