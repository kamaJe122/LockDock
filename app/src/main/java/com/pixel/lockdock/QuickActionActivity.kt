package com.pixel.lockdock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Opens directly on top of the locked screen (showWhenLocked="true") so the user can
 * toggle each Lock Screen block ON/OFF, check todos, or edit memos without unlocking!
 */
class QuickActionActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var config by remember { mutableStateOf(LockDockRepository.loadConfig(this)) }
                var memoDraft by remember { mutableStateOf(config.memoText) }
                var newTodoText by remember { mutableStateOf("") }

                fun save(next: LockDockConfig) {
                    config = next
                    LockDockRepository.saveConfig(this@QuickActionActivity, next)
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f))
                        .clickable { finish() }
                        .imePadding(),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                            .clickable(enabled = false) {},
                        shape = RoundedCornerShape(28.dp),
                        color = Color(0xF2181E2C),
                        tonalElevation = 8.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(20.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        text = "🔒 免解鎖快速控制面板",
                                        color = Color.White,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "隨時切換各區塊顯示／隱藏，立即生效",
                                        color = Color(0xFFB0BEC5),
                                        fontSize = 12.sp
                                    )
                                }
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.Default.Close, contentDescription = "關閉", tint = Color.White)
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Quick Per-Block Visibility Switches right over Lock Screen!
                            Text(
                                text = "🎛️ 各區塊顯示開關",
                                color = Color(0xFFFFD54F),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))

                            QuickBlockSwitchRow(
                                label = "頂部資訊膠囊（日期／電量）",
                                checked = config.showTopCapsule,
                                onCheckedChange = { save(config.copy(showTopCapsule = it)) }
                            )
                            QuickBlockSwitchRow(
                                label = "iPhone 4 格小工具列",
                                checked = config.showIosShelfInOverlay,
                                onCheckedChange = { save(config.copy(showIosShelfInOverlay = it)) }
                            )
                            config.hostedWidgets.forEachIndexed { idx, entry ->
                                QuickBlockSwitchRow(
                                    label = entry.label,
                                    checked = entry.enabled,
                                    onCheckedChange = { on ->
                                        val nextList = config.hostedWidgets.mapIndexed { i, e ->
                                            if (i == idx) e.copy(enabled = on) else e
                                        }
                                        save(config.copy(hostedWidgets = nextList))
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            HorizontalDivider(color = Color(0x24FFFFFF))
                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "🎨 全部小工具統一圓角",
                                    color = Color(0xFFCE93D8),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = WidgetCornerStyler.describeCornerStyle(config.widgetCornerRadiusDp),
                                    color = Color(0xFFE1BEE7),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            androidx.compose.material3.Slider(
                                value = config.widgetCornerRadiusDp.toFloat(),
                                onValueChange = { newR ->
                                    save(config.copy(widgetCornerRadiusDp = (newR + 0.5f).toInt()))
                                },
                                valueRange = WidgetCornerStyler.MIN_CORNER_RADIUS_DP.toFloat()..WidgetCornerStyler.MAX_CORNER_RADIUS_DP.toFloat()
                            )

                            Spacer(modifier = Modifier.height(6.dp))
                            HorizontalDivider(color = Color(0x24FFFFFF))
                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = "📝 鎖屏置頂備忘錄／座右銘",
                                color = Color(0xFF90CAF9),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            OutlinedTextField(
                                value = memoDraft,
                                onValueChange = {
                                    memoDraft = it
                                    save(config.copy(memoText = it))
                                },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("輸入想顯示在鎖定畫面的文字...") },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFF64B5F6),
                                    unfocusedBorderColor = Color(0x55FFFFFF)
                                )
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            Text(
                                text = "✅ 待辦事項清單",
                                color = Color(0xFF81C784),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = newTodoText,
                                    onValueChange = { newTodoText = it },
                                    modifier = Modifier.weight(1f),
                                    placeholder = { Text("新增待辦事項...") },
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedBorderColor = Color(0xFF81C784),
                                        unfocusedBorderColor = Color(0x55FFFFFF)
                                    )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        val clean = newTodoText.trim()
                                        if (clean.isNotEmpty()) {
                                            newTodoText = ""
                                            save(config.copy(todos = listOf(TodoItem(text = clean)) + config.todos))
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF42A5F5))
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "新增")
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            config.todos.forEachIndexed { index, item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0x1FFFFFFF))
                                        .clickable {
                                            val updatedList = config.todos.mapIndexed { idx, t ->
                                                if (idx == index) t.copy(done = !t.done) else t
                                            }
                                            save(config.copy(todos = updatedList))
                                        }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (item.done) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                        contentDescription = null,
                                        tint = if (item.done) Color(0xFF81C784) else Color(0xCCFFFFFF)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = item.text,
                                        modifier = Modifier.weight(1f),
                                        color = if (item.done) Color(0x99FFFFFF) else Color.White,
                                        textDecoration = if (item.done) TextDecoration.LineThrough else null,
                                        fontSize = 14.sp
                                    )
                                    IconButton(
                                        onClick = {
                                            val updatedList = config.todos.filterIndexed { idx, _ -> idx != index }
                                            save(config.copy(todos = updatedList))
                                        }
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "刪除", tint = Color(0x99FF8A80))
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { save(config.copy(waterCount = 0, habitCount = 0)) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("歸零喝水/打卡", color = Color(0xFFB0BEC5), fontSize = 12.sp)
                                }
                                Button(
                                    onClick = { finish() },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF64B5F6))
                                ) {
                                    Text("完成並返回鎖屏", color = Color(0xFF0D1321), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun QuickBlockSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x1AFFFFFF))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
