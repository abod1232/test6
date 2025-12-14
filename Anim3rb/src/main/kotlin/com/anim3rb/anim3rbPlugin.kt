package com.anime3rb

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import java.lang.ref.WeakReference
import androidx.appcompat.app.AppCompatActivity
@CloudstreamPlugin
class Anime3rbPlugin: Plugin() {
    override fun load(context: Context) {
        // نلتقط الـ Context ونحفظه في متغير ثابت (Static) في البروفايدر
        // نستخدم WeakReference لتجنب تسريب الذاكرة (Memory Leaks)
        Anime3rb.activityContext = WeakReference(context)
        registerMainAPI(Anime3rb())


        // تعريف زر الإعدادات
        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            Anime3rbSettingsDialog.show(activity.supportFragmentManager, sharedPref)
        }
    }
}
