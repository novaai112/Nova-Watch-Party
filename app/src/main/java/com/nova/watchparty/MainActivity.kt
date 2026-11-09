package com.nova.watchparty

import android.app.PictureInPictureParams
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.nova.watchparty.ui.*

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) deepLink(intent)
        setContent {
            NovaTheme {
                val ctx = LocalContext.current
                LaunchedEffect(Unit) { vm.toasts.collect { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() } }
                val screen = vm.screen
                BackHandler(enabled = screen !is Screen.Splash && screen !is Screen.Home) { vm.back() }
                NovaNav(vm, screen)
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); deepLink(intent) }

    private fun deepLink(i: Intent?) {
        i?.getStringExtra("code")?.let { vm.handleDeepLink(it); return }
        val d = i?.data ?: return
        if (d.scheme == "novaparty" && d.host == "room") vm.handleDeepLink(d.pathSegments.firstOrNull())
    }

    override fun onUserLeaveHint() {
        val f = vm.user?.flags?.floatingPlayer == true
        if (f && vm.room.value != null && Build.VERSION.SDK_INT >= 26) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        vm.inPip = isInPictureInPictureMode
    }
}
