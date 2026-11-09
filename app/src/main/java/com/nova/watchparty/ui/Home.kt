package com.nova.watchparty.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
                    BadgedBox({ if (vm.pendingRequests > 0) Badge(containerColor = NovaCoral) { Text("${vm.pendingRequests}") } }) { Icon(Icons.Default.Group, "Friends", tint = Color.White) }
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
                    decorationBox = { inner -> if (query.isEmpty()) Text("Search", color = Color.White.copy(0.55f), fontSize = 16.sp); inner() })
                if (query.isNotEmpty()) Icon(Icons.Default.Close, "Clear", tint = Color.White, modifier = Modifier.clickable { query = "" })
            }
            // join-by-code strip
            Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(0.12f), Color.Transparent)))
                .clickable { joinDialog = true }.padding(vertical = 10.dp), Alignment.Center) {
                Text("ENTER A ROOM CODE", fontStyle = FontStyle.Italic, fontWeight = FontWeight.Black, fontSize = 20.sp, color = Color.White)
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Public, null, tint = Color.White, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(10.dp))
                Text("Public", color = Color.White, fontSize = 16.sp)
            }
            if (vm.rooms.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(bottom = 80.dp), Alignment.CenterHorizontally, Arrangement.Center) {
                    if (!vm.roomsLoaded) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                    else {
                        Icon(Icons.Default.SentimentDissatisfied, null, tint = Color.White.copy(0.8f), modifier = Modifier.size(84.dp))
                        Text(if (query.isBlank()) "No public rooms right now. Start one!" else "No rooms match \"$query\"", color = Color.White.copy(0.85f), fontSize = 14.sp)
                    }
                }
            } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp)) {
                items(vm.rooms, key = { it.code }) { r -> RoomRow(r) { vm.joinRoom(r.code) } }
            }
        }
        // FAB
        Box(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp).size(58.dp).clip(CircleShape).background(Color.White)
            .clickable { vm.go(Screen.Sources(SourceMode.Start)) }, Alignment.Center) { Icon(Icons.Default.Add, "New room", tint = Color.Black, modifier = Modifier.size(32.dp)) }
        if (vm.connecting.collectAsState().value) Box(Modifier.fillMaxSize().background(Color.Black.copy(0.5f)), Alignment.Center) { CircularProgressIndicator(color = Color.White) }
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
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp).height(74.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(132.dp).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(0.4f))) {
            if (r.thumb.isNotBlank()) AsyncImage(r.thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Icon(Icons.Default.PlayCircle, null, tint = Color.White.copy(0.85f), modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(16.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(r.title, color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                r.members.take(5).forEach { Avatar(it.avatar, it.name, 26.dp, Modifier.padding(end = 3.dp)) }
                if (r.count > 5) Box(Modifier.clip(RoundedCornerShape(50)).background(NovaCoral).padding(horizontal = 6.dp, vertical = 1.dp)) {
                    Text("+${r.count - 5}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
