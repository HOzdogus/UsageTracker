package com.screentime.tracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.screentime.tracker.ui.ScreenTimeApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = UsageRepository(applicationContext)
        val storageRepo = StorageRepository(applicationContext)
        setContent { ScreenTimeApp(repo, storageRepo) }
    }
}
