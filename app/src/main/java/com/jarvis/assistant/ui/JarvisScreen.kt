package com.jarvis.assistant.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ChatMessage
import com.jarvis.assistant.JarvisEngine
import com.jarvis.assistant.JarvisState
import com.jarvis.assistant.SystemInfo
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

private fun stateColor(s: JarvisState) = when (s) {
    JarvisState.IDLE -> JColors.Blue
    JarvisState.LISTENING -> JColors.Cyan
    JarvisState.THINKING -> JColors.Amber
    JarvisState.SPEAKING -> JColors.Green
}

@Composable
fun JarvisScreen(engine: JarvisEngine) {
    val state by engine.state.collectAsState()
    val level by engine.level.collectAsState()
    val partial by engine.partial.collectAsState()
    val msgs by engine.messages.collectAsState()
    val pending by engine.pending.collectAsState()
    val wake by engine.wake.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.lastIndex) }

    Column(
        Modifier.fillMaxSize().background(JColors.Bg)
            .statusBarsPadding().navigationBarsPadding().imePadding().padding(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("J.A.R.V.I.S", color = JColors.Cyan, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(if (wake) "WAKE WORD ON" else "WAKE WORD OFF", color = if (wake) JColors.Green else JColors.Dim, fontSize = 11.sp)
            IconButton(onClick = { showSettings = true }) { Icon(Icons.Filled.Settings, "Settings", tint = JColors.Cyan) }
        }

        Box(
            Modifier.fillMaxWidth().height(190.dp).clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null
            ) { engine.micTapped() },
            contentAlignment = Alignment.Center
        ) { AiCore(state, level) }

        Text(
            when (state) {
                JarvisState.IDLE -> "STANDBY"; JarvisState.LISTENING -> "LISTENING"
                JarvisState.THINKING -> "THINKING"; JarvisState.SPEAKING -> "SPEAKING  (tap to interrupt)"
            },
            color = stateColor(state), fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Text(
            partial, color = JColors.Dim, fontSize = 12.sp, maxLines = 1,
            modifier = Modifier.align(Alignment.CenterHorizontally).height(18.dp)
        )
        Waveform(state, level)
        Dashboard()

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) { items(msgs) { Bubble(it) } }

        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("Type a command…", color = JColors.Dim) }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { engine.sendText(input); input = "" })
            )
            IconButton(onClick = { engine.sendText(input); input = "" }) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = JColors.Cyan)
            }
            IconButton(
                onClick = { engine.micTapped() },
                modifier = Modifier.size(52.dp).clip(CircleShape).background(stateColor(state))
            ) {
                Icon(if (state == JarvisState.IDLE) Icons.Filled.Mic else Icons.Filled.Stop, "Mic", tint = Color.Black)
            }
        }
    }

    pending?.let { p ->
        AlertDialog(
            onDismissRequest = { engine.resolvePending(false) },
            title = { Text("Confirm action") },
            text = {
                Column {
                    Text(p.question)
                    p.actions.forEach { Text("• $it", color = JColors.Dim, fontSize = 12.sp) }
                }
            },
            confirmButton = { Button(onClick = { engine.resolvePending(true) }) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = { engine.resolvePending(false) }) { Text("Cancel") } }
        )
    }
    if (showSettings) SettingsDialog(engine) { showSettings = false }
}

@Composable
fun AiCore(state: JarvisState, level: Float) {
    val t = rememberInfiniteTransition(label = "core")
    val rot by t.animateFloat(0f, 360f, infiniteRepeatable(tween(8000, easing = LinearEasing)), label = "rot")
    val rot2 by t.animateFloat(360f, 0f, infiniteRepeatable(tween(5000, easing = LinearEasing)), label = "rot2")
    val pulse by t.animateFloat(
        0.92f, 1.08f, infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse"
    )
    val lv by animateFloatAsState(if (state == JarvisState.LISTENING) level else 0f, label = "lv")
    val color = stateColor(state)

    Canvas(Modifier.size(190.dp)) {
        val c = center
        val r = size.minDimension / 2
        drawCircle(
            Brush.radialGradient(listOf(color.copy(alpha = 0.45f), Color.Transparent), center = c, radius = r),
            radius = r * pulse * (1f + lv * 0.15f), center = c
        )
        drawCircle(color, radius = r * 0.92f, center = c, alpha = 0.5f, style = Stroke(2f))
        for (i in 0 until 6) drawArc(
            color, rot + i * 60f, 35f, false,
            topLeft = Offset(c.x - r * 0.78f, c.y - r * 0.78f), size = Size(r * 1.56f, r * 1.56f),
            style = Stroke(6f, cap = StrokeCap.Round)
        )
        for (i in 0 until 4) drawArc(
            JColors.Blue, rot2 + i * 90f, 50f, false,
            topLeft = Offset(c.x - r * 0.6f, c.y - r * 0.6f), size = Size(r * 1.2f, r * 1.2f),
            style = Stroke(4f, cap = StrokeCap.Round)
        )
        drawCircle(color, radius = r * 0.28f * (if (state == JarvisState.THINKING) pulse else 1f + lv * 0.4f), center = c)
        drawCircle(Color.White.copy(alpha = 0.8f), radius = r * 0.1f, center = c)
    }
}

@Composable
fun Waveform(state: JarvisState, level: Float) {
    val t = rememberInfiniteTransition(label = "wf")
    val phase by t.animateFloat(
        0f, (2 * PI).toFloat(), infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "ph"
    )
    val amp = when (state) {
        JarvisState.LISTENING -> 0.15f + level * 0.85f
        JarvisState.SPEAKING -> 0.55f
        JarvisState.THINKING -> 0.25f
        JarvisState.IDLE -> 0.06f
    }
    val color = stateColor(state)
    Canvas(Modifier.fillMaxWidth().height(40.dp)) {
        val n = 40
        val gap = size.width / n
        for (i in 0 until n) {
            val h = size.height * amp * (0.35f + 0.65f * abs(sin(phase + i * 0.45f)))
            val x = i * gap + gap / 2
            drawLine(color, Offset(x, size.height / 2 - h / 2), Offset(x, size.height / 2 + h / 2),
                strokeWidth = gap * 0.5f, cap = StrokeCap.Round)
        }
    }
}

@Composable
fun Dashboard() {
    val ctx = LocalContext.current
    var info by remember { mutableStateOf(SystemInfo.snapshot(ctx)) }
    LaunchedEffect(Unit) { while (true) { info = SystemInfo.snapshot(ctx); delay(10_000) } }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        info.forEach { (label, value, healthy) ->
            val c = if (healthy) JColors.Cyan else JColors.Amber
            Column(
                Modifier.clip(RoundedCornerShape(10.dp)).background(JColors.Panel)
                    .border(1.dp, c.copy(alpha = 0.5f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(label, color = JColors.Dim, fontSize = 9.sp)
                Text(value, color = c, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun Bubble(m: ChatMessage) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start
    ) {
        val shape = RoundedCornerShape(14.dp)
        Box(
            Modifier.widthIn(max = 300.dp).clip(shape)
                .background(if (m.fromUser) JColors.Blue.copy(alpha = 0.25f) else JColors.Panel)
                .border(1.dp, if (m.fromUser) JColors.Blue else JColors.Cyan.copy(alpha = 0.4f), shape)
                .padding(10.dp)
        ) { Text(m.text, color = Color.White, fontSize = 14.sp) }
    }
}

private fun open(ctx: Context, action: String, pkg: Boolean = false) {
    val i = Intent(action)
    if (pkg) i.data = Uri.parse("package:${ctx.packageName}")
    ctx.startActivity(i)
}

@Composable
fun SettingsDialog(engine: JarvisEngine, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var key by remember { mutableStateOf(engine.store.apiKey) }
    var model by remember { mutableStateOf(engine.store.model) }
    val wake by engine.wake.collectAsState()

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(key, { key = it }, label = { Text("AI API key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(model, { model = it }, label = { Text("Model") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("\"Hey Jarvis\" wake word", Modifier.weight(1f))
                    Switch(wake, { engine.setWake(it) })
                }
                Text("Special access (granted by you, in system settings):", color = JColors.Dim, fontSize = 12.sp)
                OutlinedButton({ open(ctx, Settings.ACTION_ACCESSIBILITY_SETTINGS) }) { Text("Accessibility service") }
                OutlinedButton({ open(ctx, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }) { Text("Notification access") }
                OutlinedButton({ open(ctx, Settings.ACTION_USAGE_ACCESS_SETTINGS) }) { Text("Usage access") }
                OutlinedButton({ open(ctx, Settings.ACTION_MANAGE_WRITE_SETTINGS, true) }) { Text("Modify system settings (brightness)") }
                OutlinedButton({ open(ctx, Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true) }) { Text("Display over other apps (launch apps from background)") }
                OutlinedButton({ engine.clearMemory() }) { Text("Clear Jarvis memory") }
            }
        },
        confirmButton = {
            Button({ engine.store.apiKey = key; engine.store.model = model; onClose() }) { Text("Save") }
        },
        dismissButton = { TextButton(onClose) { Text("Close") } }
    )
}
