package com.nova.watchparty

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder

class NovaApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel("nova_invites", "Invites", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory()) }.build()
}
