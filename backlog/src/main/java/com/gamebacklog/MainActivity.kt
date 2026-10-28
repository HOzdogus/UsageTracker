package com.gamebacklog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.gamebacklog.ui.BacklogApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = GameStore(applicationContext)
        setContent { BacklogApp(store) }
    }
}
