package com.nova.watchparty.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nova.watchparty.AppViewModel
import com.nova.watchparty.Friend
import com.nova.watchparty.ProfileDetail
import com.nova.watchparty.VideoItem
import com.nova.watchparty.net.ApiError
import com.nova.watchparty.net.Backend

private val REPORT_REASONS = listOf("Spam", "Harassment or hate", "Inappropriate content", "Impersonation", "Other")

/** Another person's profile: bio, interests, gallery, socials, with Add friend / Report / Block. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Suppress("UNUSED_PARAMETER")
@Composable
fun UserSheet(vm: AppViewModel, handle: String, roomCode: String? = null, onDismiss: () -> Unit) {
    var detail by remember(handle) { mutableStateOf<ProfileDetail?>(null) }
    val p: Friend? = detail?.friend
    var tab by remember(handle) { mutableIntStateOf(0) }
    var videos by remember(handle, tab) { mutableStateOf<List<VideoItem>?>(null) }
    var hidden by remember(handle, tab) { mutableStateOf(false) }
    var failed by remember(handle) { mutableStateOf(false) }
    var reportDialog by remember { mutableStateOf(false) }
    LaunchedEffect(handle) { try { detail = Backend.profileDetail(handle) } catch (_: ApiError) { failed = true } }
    LaunchedEffect(handle, tab) {
        videos = null
        try { val (h, items) = Backend.userVideos(handle, listOf("top", "history", "liked")[tab]); hidden = h; videos = items } catch (_: ApiError) { videos = emptyList() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF1C1838)) {
        val u = p
        when {
            failed -> Text("Could not load this profile.", color = SubtleText, modifier = Modifier.padding(24.dp))
            u == null -> Spinner()
            else -> Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(u.avatar, u.name, 96.dp)
                Spacer(Modifier.height(8.dp))
                Text(u.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                Text("@${u.handle}", color = SubtleText, fontSize = 14.sp)
                detail?.let { d ->
                    d.presence?.let { pr ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(if (pr.online) NovaTeal else SubtleText))
                            Spacer(Modifier.width(6.dp))
                            Text(if (pr.online) "Online" else lastSeen(pr.lastActive), color = SubtleText, fontSize = 12.sp)
                        }
                    }
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        StatCell("Joined", java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(d.stats.joined)))
                        StatCell("Watch time", formatMinutes(d.stats.minutes))
                        StatCell("Rooms", d.stats.rooms.toString())
                        StatCell("Videos", d.stats.videos.toString())
                    }
                }
                if (u.bio.isNotBlank()) Text(u.bio, color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
                if (u.interests.isNotEmpty()) FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    u.interests.forEach { Box(Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(0.12f)).padding(horizontal = 10.dp, vertical = 4.dp)) { Text(it, color = Color.White, fontSize = 12.sp) } }
                }
                if (u.gallery.isNotEmpty()) Row(Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    u.gallery.forEach { AsyncImage(Backend.absolute(it), null, Modifier.size(84.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop) }
                }
                if (u.channels.isNotEmpty()) Text("Channels: " + u.channels.joinToString(", "), color = SubtleText, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                u.socials.forEach { (k, v) -> Text("${k.replaceFirstChar { c -> c.uppercase() }}: $v", color = SubtleText, fontSize = 12.sp) }
                // Videos: Top / History / Liked (hidden when the person keeps their activity private)
                TabRow(tab, containerColor = Color.Transparent, modifier = Modifier.padding(top = 14.dp)) {
                    listOf("Top", "History", "Liked").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
                }
                val vs = videos
                when {
                    vs == null -> Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) { CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp) }
                    hidden -> Text("This person keeps their activity private.", color = SubtleText, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
                    vs.isEmpty() -> Text("Nothing here yet.", color = SubtleText, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
                    else -> Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        vs.forEach { v ->
                            Column(Modifier.width(130.dp)) {
                                AsyncImage(v.thumb, null, Modifier.fillMaxWidth().height(74.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                                Text(v.title, color = Color.White, fontSize = 11.sp, maxLines = 2)
                                Text(if (v.views == 1) "1 view" else "${v.views} views", color = SubtleText, fontSize = 10.sp)
                            }
                        }
                    }
                }
                if (u.id != vm.user?.id) Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (detail?.relation) {
                        "friends" -> PillButton("Friends", {})
                        "requested" -> PillButton("Request sent", {})
                        "incoming" -> PillButton("Accept request", { vm.requestFriend(u.handle); onDismiss() })
                        else -> PillButton("Add friend", { vm.requestFriend(u.handle); onDismiss() })
                    }
                    PillButton("Report", { reportDialog = true })
                    PillButton("Block", { vm.block(u.id); onDismiss() })
                }
            }
        }
    }
    if (reportDialog) p?.let { u ->
        AlertDialog(onDismissRequest = { reportDialog = false }, containerColor = Color(0xFF1C1838), title = { Text("Report ${u.name}", color = Color.White) }, text = {
            Column { REPORT_REASONS.forEach { r ->
                Text(r, color = Color.White, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().clickable { reportDialog = false; vm.report(u.id, r); onDismiss() }.padding(vertical = 10.dp))
            } }
        }, confirmButton = {}, dismissButton = { TextButton({ reportDialog = false }) { Text("Cancel") } })
    }
}

@Composable
private fun StatCell(label: String, value: String) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    Text(label, color = SubtleText, fontSize = 10.sp)
}

private fun formatMinutes(m: Int) = if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"

private fun lastSeen(ts: Long): String {
    if (ts <= 0L) return "Offline"
    val mins = (System.currentTimeMillis() - ts) / 60000
    return when { mins < 2 -> "Online"; mins < 60 -> "Last seen $mins min ago"; mins < 1440 -> "Last seen ${mins / 60} h ago"; else -> "Last seen ${mins / 1440} d ago" }
}
