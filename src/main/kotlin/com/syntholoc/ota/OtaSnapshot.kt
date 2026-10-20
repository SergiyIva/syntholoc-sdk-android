package com.syntholoc.ota

/** Immutable view of one activated set. Keep one snapshot for an entire rendered frame. */
class OtaSnapshot internal constructor(
    val releaseId:String?, private val catalogs:Map<String,Catalog>, private val locales:List<String>
) {
    fun string(namespace:String,key:String,fallback:()->String):String {
        for(locale in locales)catalogs["$locale/$namespace"]?.strings?.get(key)?.let {return it}
        return fallback()
    }
    fun text(namespace:String,key:String,fallback:()->CharSequence):CharSequence {
        for(locale in locales)catalogs["$locale/$namespace"]?.let {catalog ->
            (catalog.styled[key]?:catalog.strings[key])?.let {return it}
        }
        return fallback()
    }
    fun quantity(namespace:String,key:String,category:String,fallback:()->String):String {
        for(locale in locales)catalogs["$locale/$namespace"]?.plurals?.get(key)?.let {forms ->
            (forms[category]?:forms["other"])?.let {return it}
        }
        return fallback()
    }
    fun quantity(namespace:String,key:String,quantity:Int,locale:java.util.Locale,fallback:()->String):String {
        for(scopeLocale in locales)catalogs["$scopeLocale/$namespace"]?.let {catalog ->
            val forms=catalog.plurals[key]?:return@let
            val language=catalog.languageTag?.let {java.util.Locale.forLanguageTag(it)}?:locale
            val category=android.icu.text.PluralRules.forLocale(language).select(quantity.toDouble())
            (forms[category]?:forms["other"])?.let {return it}
        }
        return fallback()
    }
    fun stringArray(namespace:String,key:String,fallback:()->Array<String>):Array<String> {
        for(locale in locales)catalogs["$locale/$namespace"]?.arrays?.get(key)?.let {return it.toTypedArray()}
        return fallback()
    }
    companion object {val BUNDLED=OtaSnapshot(null,emptyMap(),emptyList())}
}
