package com.syntholoc.ota

import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser

internal data class Catalog(val strings:Map<String,String>,val plurals:Map<String,Map<String,String>> = emptyMap(),val arrays:Map<String,List<String>> = emptyMap(),val styled:Map<String,CharSequence> = emptyMap(),val languageTag:String? = null) {
    companion object {
        fun parse(format:String,bytes:ByteArray):Catalog {
            require(bytes.size<=10_000_000)
            val text=Wire.decode(bytes);require(!text.contains('\u0000'))
            if(format=="JSON_FLAT"||format=="JSON_NESTED") {
                val result=mutableMapOf<String,String>()
                fun visit(node:JSONObject,prefix:String,depth:Int) {
                    require(depth<40)
                    for(key in node.keys()) {
                        require(key !in setOf("__proto__","constructor","prototype"))
                        val path=if(prefix.isEmpty())key else "$prefix.$key"
                        when(val value=node.get(key)) {
                            is String -> require(result.put(path,value)==null)
                            is JSONObject -> {require(format=="JSON_NESTED");visit(value,path,depth+1)}
                            else -> error("OTA_INVALID_CATALOG")
                        }
                    }
                }
                visit(JSONObject(text),"",0);return Catalog(result)
            }
            require(format=="ANDROID_XML")
            require(!text.contains("<!DOCTYPE", ignoreCase = true))
            val parser=Xml.newPullParser();parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,false)
            parser.setInput(bytes.inputStream(),"UTF-8")
            val strings=mutableMapOf<String,String>();val plurals=mutableMapOf<String,Map<String,String>>();val arrays=mutableMapOf<String,List<String>>()
            val styled=mutableMapOf<String,CharSequence>()
            fun readText():Pair<String,CharSequence?> {
                val depth=parser.depth;val plain=StringBuilder();val markup=StringBuilder();var hasStyle=false
                val tags=setOf("b","i","u","tt","big","small","sup","sub","strike","s","font","a","br")
                while(true) {
                    val event=parser.next()
                    require(event!=XmlPullParser.END_DOCUMENT && event!=XmlPullParser.DOCDECL)
                    if(event==XmlPullParser.END_TAG && parser.depth==depth)break
                    when(event) {
                        XmlPullParser.TEXT -> {plain.append(parser.text);markup.append(android.text.TextUtils.htmlEncode(parser.text))}
                        XmlPullParser.START_TAG -> {
                            if(parser.name=="xliff:g")continue
                            require(parser.name in tags);hasStyle=true;markup.append('<').append(parser.name)
                            for(i in 0 until parser.attributeCount) {
                                val key=parser.getAttributeName(i)
                                require(key in setOf("color","face","size","href"))
                                val value=parser.getAttributeValue(i)
                                if(key=="href")require(value.startsWith("https://") || value.startsWith("http://"))
                                markup.append(' ').append(key).append("=\"").append(android.text.TextUtils.htmlEncode(value)).append('"')
                            }
                            markup.append('>')
                        }
                        XmlPullParser.END_TAG -> {if(parser.name!="xliff:g")markup.append("</").append(parser.name).append('>')}
                    }
                }
                if(!hasStyle)return decode(plain.toString()) to null
                val spanned=android.text.Html.fromHtml(decode(markup.toString()),android.text.Html.FROM_HTML_MODE_COMPACT)
                return spanned.toString() to android.text.SpannedString(spanned)
            }
            while(parser.next()!=XmlPullParser.END_DOCUMENT) {
                if(parser.eventType==XmlPullParser.DOCDECL)error("OTA_UNSAFE_XML")
                if(parser.eventType!=XmlPullParser.START_TAG)continue
                val name=parser.getAttributeValue(null,"name")?:continue
                when(parser.name) {
                    "string" -> {
                        val (text,spans)=readText();require(strings.put(name,text)==null)
                        if(spans!=null)styled[name]=spans
                    }
                    "plurals","string-array" -> {
                        val plural=parser.name=="plurals";val values=mutableListOf<String>();val forms=mutableMapOf<String,String>()
                        val depth=parser.depth
                        while(!(parser.next()==XmlPullParser.END_TAG && parser.depth==depth)) {
                            if(parser.eventType==XmlPullParser.START_TAG && parser.name=="item") {
                                val quantity=parser.getAttributeValue(null,"quantity");val text=readText().first
                                if(plural){require(quantity in setOf("zero","one","two","few","many","other"));require(forms.put(quantity!!,text)==null)}else values.add(text)
                            }
                        }
                        if(plural){require(forms.containsKey("other"));require(plurals.put(name,forms)==null)}else require(arrays.put(name,values)==null)
                    }
                }
            }
            return Catalog(strings,plurals,arrays,styled)
        }
        private fun decode(text:String)=text.removeSurrounding("\"").replace("\\n","\n").replace("\\t","\t").replace("\\'","'").replace("\\\"","\"").replace("\\\\","\\")
    }
}
