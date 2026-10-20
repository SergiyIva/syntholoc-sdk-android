package com.syntholoc.ota

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import java.util.Locale

/** Resource IDs stay compiled into the host. Formatting errors use bundled text. */
class OtaResources private constructor(private val snapshot:()->OtaSnapshot,private val bundled:Resources,
    private val namespace:String,private val locale:Locale,private val resourcePackage:String?) {
    constructor(client:OtaClient,bundled:Resources,namespace:String,locale:Locale,resourcePackage:String?=null):
        this({client.snapshot()},bundled,namespace,locale,resourcePackage)
    constructor(snapshot:OtaSnapshot,bundled:Resources,namespace:String,locale:Locale,resourcePackage:String?=null):
        this({snapshot},bundled,namespace,locale,resourcePackage)
    private fun key(id:Int):String? = if(resourcePackage==null || bundled.getResourcePackageName(id)==resourcePackage)bundled.getResourceEntryName(id) else null
    fun getText(id:Int):CharSequence {
        val key=key(id)?:return bundled.getText(id)
        return snapshot().text(namespace,key) {bundled.getText(id)}
    }
    fun getString(id:Int,vararg args:Any):String {
        val text=getText(id).toString()
        return if(args.isEmpty())text else try {String.format(locale,text,*args)} catch(_:java.util.IllegalFormatException) {bundled.getString(id,*args)}
    }
    fun getQuantityString(id:Int,quantity:Int,vararg args:Any):String {
        val key=key(id)?:return bundled.getQuantityString(id,quantity,*args)
        val text=snapshot().quantity(namespace,key,quantity,locale){bundled.getQuantityString(id,quantity)}
        return if(args.isEmpty())text else try {String.format(locale,text,*args)} catch(_:java.util.IllegalFormatException) {bundled.getQuantityString(id,quantity,*args)}
    }
    fun getStringArray(id:Int):Array<String> {
        val key=key(id)?:return bundled.getStringArray(id)
        return snapshot().stringArray(namespace,key){bundled.getStringArray(id)}
    }
    @Suppress("DEPRECATION")
    fun wrap(context:Context):Context = object:ContextWrapper(context) {
        private val otaResources=object:Resources(bundled.assets,bundled.displayMetrics,bundled.configuration) {
            override fun getText(id:Int):CharSequence=this@OtaResources.getText(id)
            override fun getText(id:Int,def:CharSequence?):CharSequence=try {getText(id)} catch(_:NotFoundException) {def?:throw NotFoundException()}
            override fun getString(id:Int):String=this@OtaResources.getString(id)
            override fun getString(id:Int,vararg formatArgs:Any):String=this@OtaResources.getString(id,*formatArgs)
            override fun getQuantityString(id:Int,quantity:Int):String=this@OtaResources.getQuantityString(id,quantity)
            override fun getQuantityString(id:Int,quantity:Int,vararg formatArgs:Any):String=this@OtaResources.getQuantityString(id,quantity,*formatArgs)
            override fun getStringArray(id:Int):Array<String> = this@OtaResources.getStringArray(id)
        }
        override fun getResources():Resources=otaResources
        override fun getSystemService(name:String):Any? = if(name==Context.LAYOUT_INFLATER_SERVICE)
            LayoutInflater.from(baseContext).cloneInContext(this) else super.getSystemService(name)
    }
    /** Call from the host's existing Factory2 after creating each View, including custom Views. */
    fun localize(view:View,attrs:AttributeSet) {
        val android="http://schemas.android.com/apk/res/android"
        fun resource(name:String,apply:(Int)->Unit) {val id=attrs.getAttributeResourceValue(android,name,0);if(id!=0)apply(id)}
        resource("contentDescription") {view.contentDescription=getText(it)}
        if(view is TextView) {
            resource("text") {view.text=getText(it)}
            resource("hint") {view.hint=getText(it)}
        }
    }
}
