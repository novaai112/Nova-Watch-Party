package com.nova.watchparty.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nova.watchparty.AppViewModel
import com.nova.watchparty.RoomCard
import com.nova.watchparty.Screen
import com.nova.watchparty.SourceMode
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(vm: AppViewModel) {
    var query by remember { mutableStateOf("") }
    var joinDialog by remember { mutableStateOf(false) }
    var people by remember { mutableStateOf<List<Pair<com.nova.watchparty.Friend, String>>>(emptyList()) }
    var profileFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(query) {
        if (query.trim().length < 2) { people = emptyList(); return@LaunchedEffect }
        delay(400)
        people = try { com.nova.watchparty.net.Backend.searchUsers(query.trim()) } catch (_: com.nova.watchparty.net.ApiError) { emptyList() }
    }
    val me = vm.user
    val ctx = LocalContext.current
    val hide = me?.flags?.hideLocation == true
    val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) shareLastLocation(ctx, vm) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.registerPushIfNeeded() }
    LaunchedEffect(hide) {
        if (hide) vm.shareLocation(null, null)
        else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) shareLastLocation(ctx, vm)
        else locLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            delay(2500); notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(query) { delay(if (query.isEmpty()) 0 else 400); while (true) { vm.refreshRooms(query); vm.refreshBadge(); delay(15000) } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ vm.go(Screen.Settings) }) { Icon(Icons.Default.Settings, "Settings", tint = Color.White) }
                Box(Modifier.weight(1f), Alignment.Center) { NovaLogo(30.dp) }
                IconButton({ vm.go(Screen.Friends) }) {
                    BadgedBox({ if (vm.pendingRequests > 0) Badge(containerColor = NovaCoral) { Text(vm.pendingRequests.toString()) } }) { Icon(Icons.Default.Group, "Friends", tint = Color.White) }
                }
            }
            // search bar
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth().height(46.dp).glass(RoundedCornerShape(23.dp), 0.14f).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, null, tint = Color.White)
                Spacer(Modifier.width(10.dp))
                BasicTextField(query, { query = it }, singleLine = true, modifier = Modifier.weight(1f),
                    textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { vm.refreshRooms(query) }),
                    decorationBox = { inner -> if (query.isEmpty()) Text("Search rooms…", color = Color.White.copy(0.55f), fontSize = 16.sp); inner() })
                if (query.isNotEmpty()) Icon(Icons.Default.Close, "Clear", tint = Color.White, modifier = Modifier.clickable { query = "" })
            }
            // join-by-code strip
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).glass(RoundedCornerShape(14.dp), 0.12f)
                .clickable { joinDialog = true }.padding(vertical = 12.dp), Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VpnKey, null, tint = NovaGold, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("ENTER A ROOM CODE", fontStyle = FontStyle.Italic, fontWeight = FontWeight.Black, fontSize = 15.sp, color = Color.White)
                }
            }
            if (people.isNotEmpty()) {
                Text("People", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                people.take(5).forEach { (f, rel) ->
                    Row(Modifier.fillMaxWidth().clickable { profileFor = f.handle }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(f.avatar, f.name, 40.dp); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) { Text(f.name, color = Color.White, fontSize = 14.sp, maxLines = 1); Text("@${f.handle}", color = SubtleText, fontSize = 12.sp, maxLines = 1) }
                        if (rel == "friends") Icon(Icons.Default.Check, "Friend", tint = NovaTeal)
                    }
                }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Public, null, tint = Color.White, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                Text("Public Rooms", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            if (vm.rooms.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(bottom = 80.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!vm.roomsLoaded) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                    else {
                        Icon(Icons.Default.SentimentDissatisfied, null, tint = Color.White.copy(0.8f), modifier = Modifier.size(72.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(if (query.isBlank()) "No public rooms right now. Start one!" else "No rooms match \"$query\"", color = Color.White.copy(0.85f), fontSize = 14.sp)
                    }
                }
            } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(vm.rooms, key = { it.code }) { r -> RoomRow(r) { vm.joinRoom(r.code) } }
            }
        }
        // FAB
        Box(
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp).size(56.dp).clip(CircleShape)
                .background(Brush.linearGradient(listOf(NovaViolet, NovaCoral)))
                .clickable { vm.go(Screen.Sources(SourceMode.Start)) },
            Alignment.Center
        ) { Icon(Icons.Default.Add, "New room", tint = Color.White, modifier = Modifier.size(32.dp)) }
        if (vm.connecting.collectAsState().value) Box(Modifier.fillMaxSize().background(Color.Black.copy(0.5f)), Alignment.Center) { CircularProgressIndicator(color = Color.White) }
    }

    profileFor?.let { h -> UserSheet(vm, h) { profileFor = null } }
    vm.passcodeFor?.let { code ->
        var pc by remember(code) { mutableStateOf("") }
        AlertDialog(onDismissRequest = { vm.passcodeFor = null }, containerColor = Color(0xFF1C1838),
            title = { Text("Passcode needed", color = Color.White) },
            text = { OutlinedTextField(pc, { pc = it.take(12) }, singleLine = true, label = { Text("Passcode") }) },
            confirmButton = { TextButton({ vm.passcodeFor = null; vm.joinRoom(code, pc) }, enabled = pc.length >= 4) { Text("Join") } },
            dismissButton = { TextButton({ vm.passcodeFor = null }) { Text("Cancel") } })
    }
    if (joinDialog) {
        var code by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { joinDialog = false }, containerColor = Color(0xFF1C1838),
            title = { Text("Join with a code", color = Color.White) },
            text = { OutlinedTextField(code, { code = it.uppercase().filter(Char::isLetterOrDigit).take(8) }, singleLine = true, label = { Text("Room code") }) },
            confirmButton = { TextButton({ joinDialog = false; vm.joinRoom(code) }, enabled = code.length >= 4) { Text("Join") } },
            dismissButton = { TextButton({ joinDialog = false }) { Text("Cancel") } })
    }
}

@Composable
private fun RoomRow(r: RoomCard, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(14.dp), 0.10f)
            .clickable(onClick = onClick)
            .padding(8.dp)
            .height(72.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(120.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(0.4f))
        ) {
            if (r.thumb.isNotBlank()) AsyncImage(r.thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(0.6f)),
                Alignment.Center
            ) {
                Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(12.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.title.ifBlank { "Live Nova Room" }, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                r.members.take(4).forEach { Avatar(it.avatar, it.name, 24.dp, Modifier.padding(end = 4.dp)) }
                if (r.count > 4) Box(Modifier.clip(RoundedCornerShape(50)).background(NovaCoral).padding(horizontal = 6.dp, vertical = 1.dp)) {
                    Text("+${r.count - 4}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** Coarse (~1 km) last-known location, used only to list "nearby" rooms. Skipped when Hide Location is on. */
@SuppressLint("MissingPermission")
private fun shareLastLocation(ctx: Context, vm: AppViewModel) {
    try {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        if (loc != null) vm.shareLocation(loc.latitude, loc.longitude)
    } catch (_: Exception) {}
}

@Preview(showBackground = true, backgroundColor = 0xFF14102B, name = "RoomCard")
@Composable
private fun PreviewRoomCard() {
    NovaTheme {
        Box(Modifier.padding(16.dp)) {
            RoomRow(
                RoomCard(
                    code = "NOVA-99",
                    title = "Lo-Fi Beats to Relax / Study to [24/7 Live Stream]",
                    thumb = "",
                    count = 4,
                    members = listOf(
                        com.nova.watchparty.MemberLite("Alice", ""),
                        com.nova.watchparty.MemberLite("Bob", ""),
                        com.nova.watchparty.MemberLite("Charlie", ""),
                    )
                ),
                onClick = {}
            )
        }
    }
}
