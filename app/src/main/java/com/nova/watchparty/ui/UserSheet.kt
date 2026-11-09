package com.nova.watchparty.ui

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
import com.nova.watchparty.net.ApiError
import com.nova.watchparty.net.Backend

private val REPORT_REASONS = listOf("Spam", "Harassment or hate", "Inappropriate content", "Impersonation", "Other")

/** Another person's profile: bio, interests, gallery, socials, with Add friend / Report / Block. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun UserSheet(vm: AppViewModel, handle: String, roomCode: String?, onDismiss: () -> Unit) {
    var p by remember(handle) { mutableStateOf<Friend?>(null) }
    var failed by remember(handle) { mutableStateOf(false) }
    var reportDialog by remember { mutableStateOf(false) }
    LaunchedEffect(handle) { try { p = Backend.profile(handle) } catch (e: ApiError) { failed = true } }

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
                if (u.bio.isNotBlank()) Text(u.bio, color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
                if (u.interests.isNotEmpty()) FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    u.interests.forEach { Box(Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(0.12f)).padding(horizontal = 10.dp, vertical = 4.dp)) { Text(it, color = Color.White, fontSize = 12.sp) } }
                }
                if (u.gallery.isNotEmpty()) Row(Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    u.gallery.forEach { AsyncImage(Backend.absolute(it), null, Modifier.size(84.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop) }
                }
                if (u.channels.isNotEmpty()) Text("Channels: " + u.channels.joinToString(", "), color = SubtleText, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                u.socials.forEach { (k, v) -> Text("${k.replaceFirstChar { c -> c.uppercase() }}: $v", color = SubtleText, fontSize = 12.sp) }
                if (u.id != vm.user?.id) Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Add friend", { vm.requestFriend(u.handle); onDismiss() })
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
