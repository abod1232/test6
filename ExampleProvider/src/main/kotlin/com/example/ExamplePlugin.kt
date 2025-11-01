package com.example

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ExamplePlugin: Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner
        registerMainAPI(ExampleProvider())

        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            val frag = BlankFragment(this)
            frag.show(activity.supportFragmentManager, "Frag")
        }
    }
}