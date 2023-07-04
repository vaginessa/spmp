package com.toasterofbread.spmp

import SpMp
import android.content.ComponentCallbacks2
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.StrictMode
import android.os.StrictMode.VmPolicy
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.toasterofbread.spmp.platform.PlatformContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        PlatformContext.main_activity = MainActivity::class.java

        Thread.setDefaultUncaughtExceptionHandler { _: Thread, error: Throwable ->
            if (
                error is java.nio.channels.UnresolvedAddressException // Thrown by Kizzy
            ) {
                SpMp.Log.warning("Skipping error: ${error.stackTraceToString()}")
                return@setDefaultUncaughtExceptionHandler
            }

            error.printStackTrace()

            startActivity(Intent(this@MainActivity, ErrorReportActivity::class.java).apply {
                putExtra("message", error.message)
                putExtra("stack_trace", error.stackTraceToString())
            })
        }

        StrictMode.setVmPolicy(VmPolicy.Builder()
            .detectLeakedClosableObjects()
            .penaltyLog()
            .build()
        )

        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)

        PlatformContext(this) {
            SpMp.init(it)
        }

        val open_uri: Uri? =
            if (intent.action == Intent.ACTION_VIEW) intent.data
            else null

        setContent {
            SpMp.App(open_uri?.toString())
        }
    }

    override fun onDestroy() {
        SpMp.release()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        SpMp.onStart()
    }

    override fun onStop() {
        super.onStop()
        SpMp.onStop()
    }

    // TODO
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
            }

            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                SpMp.onLowMemory()
            }

            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
            }

            else -> {
            }
        }
    }
}