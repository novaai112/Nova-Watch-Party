package com.nova.watchparty.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nova.watchparty.AppViewModel
import com.nova.watchparty.Screen

@Composable
fun NovaNav(vm: AppViewModel, screen: Screen) {
    if (screen is Screen.Splash) {
        Box(Modifier.fillMaxSize().background(Color.Black), Alignment.Center) { NovaLogo(56.dp) }
        return
    }
    NovaBackground(dim = if (screen is Screen.Room) 0.45f else 0f) {
        AnimatedContent(
            targetState = screen,
            transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) },
            label = "screen_transition"
        ) { targetScreen ->
            when (targetScreen) {
                Screen.Splash -> {}
                Screen.Onboarding -> OnboardingScreen(vm)
                Screen.SignIn -> SignInScreen(vm)
                Screen.EmailEntry -> EmailEntryScreen(vm)
                is Screen.EmailCode -> EmailCodeScreen(vm, targetScreen)
                Screen.Terms -> TermsScreen(vm)
                Screen.Setup -> SetupScreen(vm)
                Screen.Home -> HomeScreen(vm)
                is Screen.Sources -> SourcesScreen(vm, targetScreen.mode)
                Screen.Room -> RoomScreen(vm)
                Screen.Profile -> ProfileScreen(vm)
                Screen.Settings -> SettingsScreen(vm)
                Screen.Friends -> FriendsScreen(vm)
            }
        }
    }
}
