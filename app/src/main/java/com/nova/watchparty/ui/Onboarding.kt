@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.nova.watchparty.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.nova.watchparty.AppViewModel
import com.nova.watchparty.BuildConfig
import com.nova.watchparty.Screen
import com.nova.watchparty.net.Backend
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- Onboarding carousel
private val PAGES = listOf(
    "Watch" to "View videos with your friends in real-time",
    "Chat" to "Message while you watch",
    "React" to "Send emoji reactions and GIFs as it happens",
    "Sync" to "Perfectly synchronized videos for everyone in the room",
    "Vote" to "Everyone adds videos and votes on what plays next",
    "Anyone. Anywhere." to "Share the experience with friends from around the world",
)

@Composable
private fun PhoneFrame(modifier: Modifier = Modifier, dark: Boolean = true, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(16.dp)).background(if (dark) Color(0xFF16161D) else Color(0xFFEDEDF2))
            .border(2.dp, Color(0xFF3B3B48), RoundedCornerShape(16.dp)).padding(5.dp)
            .clip(RoundedCornerShape(11.dp)).background(Color(0xFF0D0D12)), content = content,
    )
}

@Composable
private fun VideoStub(modifier: Modifier = Modifier) = Box(modifier.background(Brush.linearGradient(listOf(Color(0xFF5B6B8C), Color(0xFFC97B63)))))

@Composable
private fun MemberRow(color: Color) = Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(22.dp).clip(CircleShape).background(color))
    Spacer(Modifier.width(8.dp))
    Box(Modifier.height(6.dp).weight(1f).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.35f)))
}

@Composable
private fun PhoneArt(page: Int) {
    val cols = listOf(NovaCoral, NovaTeal, NovaGold, NovaViolet)
    when (page) {
        0 -> PhoneFrame(Modifier.size(170.dp, 320.dp)) {
            Column { VideoStub(Modifier.fillMaxWidth().height(110.dp)); Spacer(Modifier.height(14.dp)); cols.take(3).forEach { MemberRow(it) } }
        }
        1, 2 -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(2) {
                PhoneFrame(Modifier.size(150.dp, 300.dp)) {
                    Column {
                        VideoStub(Modifier.fillMaxWidth().height(90.dp))
                        Spacer(Modifier.height(40.dp))
                        Box(Modifier.padding(horizontal = 10.dp).fillMaxWidth().height(26.dp).clip(RoundedCornerShape(13.dp)).background(Color.White.copy(0.2f)),
                            Alignment.CenterStart) { Text(if (page == 1) "That's hilarious!" else "🔥 😂 ❤️", Modifier.padding(start = 10.dp), color = Color.White, fontSize = 11.sp) }
                        Spacer(Modifier.weight(1f))
                        Icon(if (page == 1) Icons.Default.Keyboard else Icons.Default.EmojiEmotions, null, tint = Color.White.copy(0.7f),
                            modifier = Modifier.align(Alignment.CenterHorizontally).size(56.dp).padding(bottom = 14.dp))
                    }
                }
            }
        }
        3 -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PhoneFrame(Modifier.size(100.dp, 220.dp)) { Column { VideoStub(Modifier.fillMaxWidth().height(60.dp)); MemberRow(NovaTeal); MemberRow(NovaGold) } }
            PhoneFrame(Modifier.size(100.dp, 250.dp), dark = false) { Column { VideoStub(Modifier.fillMaxWidth().height(60.dp)); MemberRow(NovaCoral); MemberRow(NovaViolet) } }
            PhoneFrame(Modifier.size(100.dp, 220.dp)) { Column { VideoStub(Modifier.fillMaxWidth().height(60.dp)); MemberRow(NovaGold); MemberRow(NovaTeal) } }
        }
        4 -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            PhoneFrame(Modifier.size(120.dp, 240.dp).rotate(-8f)) { Column { VideoStub(Modifier.fillMaxWidth().height(70.dp)); Spacer(Modifier.height(10.dp)); Text("🥇 Song A  +5", Modifier.padding(8.dp), color = Color.White, fontSize = 11.sp); Text("Song B  +2", Modifier.padding(8.dp), color = Color.White.copy(0.7f), fontSize = 11.sp) } }
            PhoneFrame(Modifier.size(120.dp, 240.dp).rotate(6f)) { Column { VideoStub(Modifier.fillMaxWidth().height(70.dp)); Spacer(Modifier.height(10.dp)); Icon(Icons.Default.ThumbUp, null, tint = NovaTeal, modifier = Modifier.padding(8.dp)) } }
        }
        else -> Box(Modifier.fillMaxSize().padding(horizontal = 8.dp).rotate(-6f), Alignment.Center) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(5) { r ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(start = if (r % 2 == 0) 0.dp else 30.dp)) {
                        repeat(6) { c -> PhoneFrame(Modifier.size(52.dp, 96.dp), dark = (r + c) % 3 != 0) { Column { VideoStub(Modifier.fillMaxWidth().height(24.dp)); MemberRow(cols[(r + c) % 4]) } } }
                    }
                }
            }
        }
    }
}

@Composable
fun OnboardingScreen(vm: AppViewModel) {
    val pager = rememberPagerState { PAGES.size }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            val last = page == PAGES.lastIndex
            Box(Modifier.fillMaxSize()) {
                if (last) Box(Modifier.fillMaxSize().background(Color.Black.copy(0.35f)))
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(bottom = 90.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!last) {
                        Spacer(Modifier.height(28.dp))
                        Text(PAGES[page].first, fontSize = 22.sp, fontWeight = FontWeight.Light, color = Color.White)
                        Spacer(Modifier.weight(1f)); PhoneArt(page); Spacer(Modifier.weight(1f))
                        Text(PAGES[page].second, fontSize = 15.sp, color = Color.White.copy(0.9f), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
                    } else {
                        Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) { PhoneArt(page) }
                        Text(PAGES[page].first, fontSize = 26.sp, fontWeight = FontWeight.Light, color = Color.White,
                            modifier = Modifier.fillMaxWidth().background(Color.Black.copy(0.35f)).padding(vertical = 8.dp), textAlign = TextAlign.Center)
                        Spacer(Modifier.height(10.dp))
                        Text(PAGES[page].second, fontSize = 15.sp, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
                    }
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(PAGES.size) { Box(Modifier.size(if (it == pager.currentPage) 9.dp else 6.dp).clip(CircleShape).background(Color.White.copy(if (it == pager.currentPage) 1f else 0.4f))) }
            }
            Spacer(Modifier.height(14.dp))
            val last = pager.currentPage == PAGES.lastIndex
            PillButton(if (last) "Get started" else "Next", { if (last) vm.finishOnboarding() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } })
        }
        if (pager.currentPage != PAGES.lastIndex) {
            Text("Skip", color = Color.White.copy(0.8f), modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp).clickable { vm.finishOnboarding() })
        }
    }
}

// ---------------------------------------------------------------- Sign in
@Composable
private fun RoundSocial(onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) =
    Box(Modifier.size(48.dp).clip(CircleShape).background(Color.White).clickable(onClick = onClick), Alignment.Center, content = content)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SignInScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var serverDialog by remember { mutableStateOf(false) }
    var guestDialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        NovaLogo(72.dp, Modifier.combinedClickable(onClick = {}, onLongClick = { serverDialog = true }))
        Spacer(Modifier.weight(1.1f))
        Text("sign in to watch videos with friends", color = Color.White, fontSize = 14.sp)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()) RoundSocial({
                scope.launch {
                    try {
                        val opt = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()
                        val res = CredentialManager.create(ctx).getCredential(ctx as Activity, GetCredentialRequest.Builder().addCredentialOption(opt).build())
                        val c = res.credential
                        if (c is CustomCredential && c.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
                            vm.googleSignIn(GoogleIdTokenCredential.createFrom(c.data).idToken)
                    } catch (e: Exception) { vm.toasts.tryEmit("Google sign-in was cancelled or failed") }
                }
            }) { Text("G", color = Color(0xFF4285F4), fontWeight = FontWeight.Black, fontSize = 22.sp) }
            RoundSocial({ vm.go(Screen.EmailEntry) }) { Icon(Icons.Default.Email, "Email", tint = Color.Black) }
            RoundSocial({ guestDialog = true }) { Icon(Icons.Default.Person, "Guest", tint = Color.Black) }
        }
        Spacer(Modifier.height(16.dp))
        Text("or sign in with email", color = Color.White, fontSize = 13.sp, textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable { vm.go(Screen.EmailEntry) })
        Spacer(Modifier.height(36.dp))
        Text("By continuing, you are agreeing to our Terms of Service and Privacy Policy", color = Color.White.copy(0.8f), fontSize = 10.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
    }
    if (guestDialog) {
        var gname by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { guestDialog = false }, containerColor = Color(0xFF1C1838),
            title = { Text("Continue as guest", color = Color.White) },
            text = { OutlinedTextField(gname, { gname = it.take(24) }, label = { Text("Your name") }, singleLine = true) },
            confirmButton = { TextButton({ guestDialog = false; vm.guestSignIn(gname) }) { Text("Continue") } },
            dismissButton = { TextButton({ guestDialog = false }) { Text("Cancel") } })
    }
    if (serverDialog) {
        var s by remember { mutableStateOf(vm.server) }
        AlertDialog(onDismissRequest = { serverDialog = false }, containerColor = Color(0xFF1C1838),
            title = { Text("Server address", color = Color.White) },
            text = { OutlinedTextField(s, { s = it }, singleLine = true, label = { Text("ws:// or wss://") }) },
            confirmButton = { TextButton({ vm.server = s; serverDialog = false }) { Text("Save") } },
            dismissButton = { TextButton({ serverDialog = false }) { Text("Cancel") } })
    }
}

// ---------------------------------------------------------------- Email
@Composable
fun EmailEntryScreen(vm: AppViewModel) {
    var email by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f)); NovaLogo(72.dp); Spacer(Modifier.weight(1f))
        BasicTextField(email, { email = it.trim() }, singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 17.sp, textAlign = TextAlign.Center),
            cursorBrush = Brush.linearGradient(listOf(Color.White, Color.White)),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), Alignment.Center) {
                    if (email.isEmpty()) Text("email@email.com", color = Color.White.copy(0.55f), fontSize = 17.sp)
                    inner()
                }
            })
        HorizontalDivider(color = Color.White.copy(0.35f))
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Back", { vm.back() })
            PillButton(if (vm.busy) "Sending…" else "Continue", { vm.requestCode(email) }, enabled = email.contains("@") && !vm.busy)
        }
        Spacer(Modifier.weight(1.4f))
    }
}

@Composable
fun EmailCodeScreen(vm: AppViewModel, s: Screen.EmailCode) {
    var code by remember { mutableStateOf("") }
    var left by remember { mutableIntStateOf(30) }
    LaunchedEffect(left) { if (left > 0) { delay(1000); left-- } }
    LaunchedEffect(s.devCode) { s.devCode?.let { code = it } } // dev mode only: the server returns the code
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f)); NovaLogo(72.dp); Spacer(Modifier.weight(1f))
        Text(s.email, color = Color.White, fontSize = 17.sp)
        Text("Check your email for a 6-digit login code", color = Color.White.copy(0.7f), fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))
        BasicTextField(code, { code = it.filter(Char::isDigit).take(6) }, singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 30.sp, textAlign = TextAlign.Center, letterSpacing = 8.sp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            decorationBox = { inner -> Box(Modifier.fillMaxWidth(), Alignment.Center) { if (code.isEmpty()) Text("······", color = Color.White.copy(0.4f), fontSize = 30.sp, letterSpacing = 8.sp); inner() } })
        HorizontalDivider(color = Color.White.copy(0.35f))
        Spacer(Modifier.height(18.dp))
        PillButton(if (vm.busy) "Checking…" else "Verify", { vm.verify(s.email, code) }, enabled = code.length == 6 && !vm.busy)
        Spacer(Modifier.height(10.dp))
        PillButton(if (left > 0) "Resend in $left seconds" else "Resend code", { left = 30; vm.requestCode(s.email) }, enabled = left == 0 && !vm.busy)
        Spacer(Modifier.height(10.dp))
        Text("Use a different email", color = Color.White.copy(0.8f), fontSize = 12.sp, textDecoration = TextDecoration.Underline, modifier = Modifier.clickable { vm.back() })
        Spacer(Modifier.weight(1.4f))
    }
}

// ---------------------------------------------------------------- Terms
@Composable
fun TermsScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    var privacy by remember { mutableStateOf(false) }
    var terms by remember { mutableStateOf(false) }
    fun open(path: String) = ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Backend.base + path)))
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f)); NovaLogo(72.dp); Spacer(Modifier.weight(0.8f))
        Text("Please accept our", color = Color.White, fontSize = 15.sp)
        Spacer(Modifier.height(14.dp))
        listOf(
            Triple("Privacy Policy", "What data Nova collects from you, when it is shared, and how you can control it.", "/privacy"),
            Triple("Terms of Service", "An agreement detailing the user's legal rights and obligations when using Nova.", "/terms"),
        ).forEachIndexed { i, (t, d, path) ->
            val on = if (i == 0) privacy else terms
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (i == 0) Icons.Default.Visibility else Icons.Default.Description, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(t, color = Color.White, fontSize = 15.sp, textDecoration = TextDecoration.Underline, modifier = Modifier.clickable { open(path) })
                    Text(d, color = Color.White.copy(0.7f), fontSize = 10.sp, lineHeight = 13.sp)
                }
                Switch(on, { if (i == 0) privacy = it else terms = it }, colors = SwitchDefaults.colors(checkedTrackColor = NovaTeal, checkedThumbColor = Color.White))
            }
        }
        Spacer(Modifier.height(14.dp))
        PillButton("CONTINUE", { vm.acceptTerms() }, enabled = privacy && terms)
        Spacer(Modifier.weight(1.4f))
    }
}

// ---------------------------------------------------------------- Profile setup
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val u = vm.user ?: return
    var name by remember { mutableStateOf(if (u.name.contains("@")) "" else u.name) }
    var handle by remember { mutableStateOf(u.handle) }
    var suggestions by remember { mutableStateOf(emptyList<String>()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { vm.uploadAvatar(ctx, it) } }
    LaunchedEffect(name) { delay(350); suggestions = vm.suggestHandles(name.ifBlank { "nova" }) }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(36.dp))
        Box(Modifier.size(150.dp).clip(CircleShape).background(Color.Black.copy(0.35f)).clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, Alignment.Center) {
            if (u.avatar.isNotBlank()) Avatar(u.avatar, u.name, 150.dp) else Icon(Icons.Default.AddAPhoto, "Choose avatar", tint = Color.White, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text("choose avatar", color = Color.White.copy(0.8f), fontSize = 12.sp)
        Spacer(Modifier.height(18.dp))
        BasicTextField(name, { name = it.take(24) }, singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Light, textAlign = TextAlign.Center),
            decorationBox = { inner -> Box(Modifier.fillMaxWidth(), Alignment.Center) { if (name.isEmpty()) Text("username", color = Color.White.copy(0.4f), fontSize = 30.sp, fontWeight = FontWeight.Light); inner() } })
        Text("your display name", color = SubtleText, fontSize = 12.sp)
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("@", color = Color.White, fontSize = 22.sp)
            BasicTextField(handle, { handle = it.lowercase().filter { c -> c.isLetterOrDigit() || c in "_.-" }.take(30) }, singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 22.sp, textAlign = TextAlign.Center), modifier = Modifier.widthIn(min = 120.dp))
        }
        Text("handle · harder to change later", color = SubtleText, fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.forEach { h -> Box(Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(0.12f)).clickable { handle = h }.padding(horizontal = 12.dp, vertical = 6.dp)) { Text("@$h", color = Color.White.copy(0.85f), fontSize = 13.sp) } }
        }
        Spacer(Modifier.height(26.dp))
        PillButton(if (vm.busy) "Saving…" else "looks good!", {
            vm.saveProfile(name.ifBlank { "Nova" }, handle, setupDone = true) { vm.reset(Screen.Home); vm.consumePendingJoin() }
        }, enabled = handle.length >= 3 && !vm.busy)
        Spacer(Modifier.height(30.dp))
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF14102B, name = "OnboardingPhoneFrame")
@Composable
private fun PreviewOnboardingPhoneFrame() {
    NovaTheme {
        Box(Modifier.padding(24.dp)) {
            PhoneFrame(Modifier.fillMaxWidth().height(300.dp)) {
                PhoneArt(0)
            }
        }
    }
}
