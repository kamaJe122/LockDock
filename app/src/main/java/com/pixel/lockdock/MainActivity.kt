package com.pixel.lockdock

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private var configuringWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    private var pendingConfigureCallback: ((Boolean) -> Unit)? = null

    fun launchHostedWidgetConfigure(
        appWidgetId: Int,
        onResult: (Boolean) -> Unit
    ) {
        pendingConfigureCallback = onResult
        try {
            val host = LockDockWidgetHostManager.getHost(this)
            host.startAppWidgetConfigureActivityForResult(
                this,
                appWidgetId,
                0,
                REQ_CONFIGURE_HOSTED_WIDGET,
                DeviceStateHelper.buildHostConfigureActivityOptions()
            )
        } catch (_: Throwable) {
            pendingConfigureCallback = null
            onResult(true)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CONFIGURE_HOSTED_WIDGET) {
            val cb = pendingConfigureCallback
            pendingConfigureCallback = null
            cb?.invoke(resultCode == Activity.RESULT_OK)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        configuringWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (configuringWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, configuringWidgetId)
            setResult(Activity.RESULT_OK, resultValue)
        }

        setContent {
            val colorScheme = darkColorScheme(
                primary = Color(0xFF82B1FF),
                secondary = Color(0xFF80CBC4),
                background = Color(0xFF0B0F19),
                surface = Color(0xFF151B2B),
                surfaceVariant = Color(0xFF1E273C)
            )
            MaterialTheme(colorScheme = colorScheme) {
                LockDockStudioScreen(
                    activity = this,
                    isConfiguringWidget = configuringWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID,
                    onFinishConfigure = {
                        LockDockRepository.notifyAllWidgetsChanged(this)
                        val resultValue = Intent().putExtra(
                            AppWidgetManager.EXTRA_APPWIDGET_ID,
                            configuringWidgetId
                        )
                        setResult(Activity.RESULT_OK, resultValue)
                        finish()
                    }
                )
            }
        }
    }

    companion object {
        private const val REQ_CONFIGURE_HOSTED_WIDGET = 4401
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockDockStudioScreen(
    activity: MainActivity,
    isConfiguringWidget: Boolean,
    onFinishConfigure: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var config by remember { mutableStateOf(LockDockRepository.loadConfig(context)) }
    var isAccessibilityActive by remember {
        mutableStateOf(DeviceStateHelper.isOverlayAccessibilityEnabled(context))
    }
    var hasCalendarPerm by remember {
        mutableStateOf(KmCalendarHelper.hasCalendarPermission(context))
    }
    var currentTimeStr by remember {
        mutableStateOf(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")))
    }
    var batteryInfo by remember { mutableStateOf(DeviceStateHelper.getBatteryStatus(context)) }
    var resumeGeneration by remember { mutableIntStateOf(0) }

    // Default to Tab 0: Per-Block Visibility Control Center!
    var selectedTab by remember { mutableIntStateOf(0) }
    var showAddWidgetSheet by remember { mutableStateOf(false) }
    var showSystemWidgetPickerSheet by remember { mutableStateOf(false) }
    var editingSlotIndex by remember { mutableIntStateOf(-1) }
    var showAppPickerForSlot by remember { mutableIntStateOf(-1) }
    var showInlinePickerDialog by remember { mutableStateOf(false) }
    var installedApps by remember { mutableStateOf<List<InstalledAppInfo>>(emptyList()) }
    var allSystemWidgets by remember { mutableStateOf<List<SystemWidgetCandidate>>(emptyList()) }
    var newTodoDraft by remember { mutableStateOf("") }

    fun updateAndSave(next: LockDockConfig) {
        config = next
        LockDockRepository.saveConfig(context, next, notify = true)
    }

    val calendarPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCalendarPerm = granted
        LockDockRepository.notifyAllWidgetsChanged(context)
        if (granted) {
            Toast.makeText(context, "✅ 已授權讀取系統行事曆！", Toast.LENGTH_SHORT).show()
        }
    }

    val coroutineScope = rememberCoroutineScope()
    val (currentVerCode, currentVerName) = remember { AppUpdater.getCurrentVersionInfo(context) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var downloadingUpdate by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    var latestRelease by remember { mutableStateOf<ReleaseUpdateInfo?>(null) }
    var updateError by remember { mutableStateOf<String?>(null) }

    fun triggerUpdateCheck(openDialog: Boolean) {
        if (checkingUpdate) return
        if (openDialog) showUpdateDialog = true
        checkingUpdate = true
        updateError = null
        coroutineScope.launch {
            val res = AppUpdater.checkLatestRelease(context)
            checkingUpdate = false
            res.onSuccess { info ->
                latestRelease = info
            }.onFailure { err ->
                updateError = err.message ?: "檢查更新失敗"
            }
        }
    }

    LaunchedEffect(Unit) {
        installedApps = DeviceStateHelper.getInstalledLauncherApps(context)
        allSystemWidgets = LockDockWidgetHostManager.loadAllInstalledWidgets(context)
        triggerUpdateCheck(openDialog = false)
        while (true) {
            currentTimeStr = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
            batteryInfo = DeviceStateHelper.getBatteryStatus(context)
            delay(15_000L)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    config = LockDockRepository.loadConfig(context)
                    isAccessibilityActive = DeviceStateHelper.isOverlayAccessibilityEnabled(context)
                    hasCalendarPerm = KmCalendarHelper.hasCalendarPermission(context)
                    batteryInfo = DeviceStateHelper.getBatteryStatus(context)
                    allSystemWidgets = LockDockWidgetHostManager.loadAllInstalledWidgets(context)
                    resumeGeneration++
                }
                Lifecycle.Event.ON_PAUSE -> {
                    LockScreenOverlayService.requestRefresh(forceRebindHosted = true)
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var pendingCandidate by remember { mutableStateOf<SystemWidgetCandidate?>(null) }
    var pendingAllocatedId by remember { mutableIntStateOf(-1) }

    fun finalizeHostedWidget(id: Int, candidate: SystemWidgetCandidate) {
        val defaultScale = if (candidate.cellBadge.startsWith("5")) 96 else 100
        val defaultH = if (candidate.cellBadge.endsWith("×1")) 105 else 195
        val entry = HostedWidgetEntry(
            appWidgetId = id,
            label = "${candidate.appLabel} (${candidate.cellBadge})",
            enabled = true,
            heightDp = defaultH,
            scalePercent = defaultScale
        )
        val next = config.copy(
            overlayEnabled = true,
            hostedWidgets = config.hostedWidgets + entry
        )
        updateAndSave(next)
        selectedTab = 0
        resumeGeneration++
        Toast.makeText(context, "✅ 已加入區塊「${candidate.appLabel}」！可隨時開關顯示", Toast.LENGTH_SHORT).show()
    }

    fun maybeConfigureThenFinalize(id: Int, candidate: SystemWidgetCandidate) {
        if (candidate.info.configure != null) {
            activity.launchHostedWidgetConfigure(id) { ok ->
                if (ok) {
                    finalizeHostedWidget(id, candidate)
                } else {
                    runCatching { LockDockWidgetHostManager.getHost(context).deleteAppWidgetId(id) }
                }
            }
        } else {
            finalizeHostedWidget(id, candidate)
        }
    }

    val bindWidgetLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val cand = pendingCandidate
        val id = pendingAllocatedId
        pendingCandidate = null
        pendingAllocatedId = -1
        if (res.resultCode == Activity.RESULT_OK && cand != null && id >= 0) {
            maybeConfigureThenFinalize(id, cand)
        } else if (id >= 0) {
            runCatching { LockDockWidgetHostManager.getHost(context).deleteAppWidgetId(id) }
        }
    }

    fun selectSystemWidget(candidate: SystemWidgetCandidate) {
        val host = LockDockWidgetHostManager.getHost(context)
        val mgr = AppWidgetManager.getInstance(context)
        val id = host.allocateAppWidgetId()
        val info: AppWidgetProviderInfo = candidate.info

        val boundImmediately = runCatching {
            mgr.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)
        }.getOrDefault(false)

        if (boundImmediately) {
            maybeConfigureThenFinalize(id, candidate)
        } else {
            pendingCandidate = candidate
            pendingAllocatedId = id
            val bindIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
            }
            bindWidgetLauncher.launch(bindIntent)
        }
    }

    val hasNewUpdate = latestRelease != null && latestRelease!!.remoteVersionCode > currentVerCode

    Scaffold(
        containerColor = Color(0xFF0B0F19),
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF101626))
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "LockDock 鎖屏小工具 v$currentVerName",
                            color = Color.White,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "支援 App 內一鍵檢查與自動下載更新",
                            color = Color(0xFF90CAF9),
                            fontSize = 11.sp
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { triggerUpdateCheck(openDialog = true) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (hasNewUpdate) Color(0xFF69F0AE) else Color(0xFF263859)
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "檢查更新",
                                tint = if (hasNewUpdate) Color(0xFF061A10) else Color(0xFF90CAF9),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (hasNewUpdate) "有新版本！" else "檢查更新",
                                color = if (hasNewUpdate) Color(0xFF061A10) else Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (isConfiguringWidget) {
                            Button(
                                onClick = onFinishConfigure,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF66BB6A))
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("套用", color = Color(0xFF08140A), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (hasNewUpdate && latestRelease != null) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF16382B),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFF69F0AE), RoundedCornerShape(16.dp))
                        .clickable { showUpdateDialog = true }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "🎉 發現新版本 ${latestRelease!!.tagName}！",
                                color = Color(0xFF69F0AE),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "點此直接在 App 內一鍵下載並安裝更新",
                                color = Color(0xFFD7FFE8),
                                fontSize = 12.sp
                            )
                        }
                        Text("立即更新 →", color = Color(0xFF69F0AE), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }

            // 1. Live WYSIWYG Lock Screen Preview Card (reflects exact block switches & order!)
            IphoneLockScreenPreviewCard(
                config = config,
                currentTimeStr = currentTimeStr,
                batteryInfo = batteryInfo,
                resumeGeneration = resumeGeneration,
                onTapInlinePill = { showInlinePickerDialog = true },
                onTapExistingSlot = { index -> editingSlotIndex = index },
                onTapAddSlot = { showAddWidgetSheet = true }
            )

            // 2. Main Control Tabs (Tab 0 = Per-Block Visibility Control!)
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141B2D)),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = Color(0xFF1A233A),
                        contentColor = Color(0xFF82B1FF)
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("🎛️ 區塊顯示開關", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("📅 原生鎖屏月曆", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        )
                        Tab(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            text = { Text("📝 編輯筆記/待辦", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        )
                    }

                    when (selectedTab) {
                        0 -> BlockVisibilityControlSection(
                            config = config,
                            isAccessibilityActive = isAccessibilityActive,
                            onUpdateConfig = {
                                updateAndSave(it)
                                resumeGeneration++
                            },
                            onChangeCapsuleMode = { showInlinePickerDialog = true },
                            onAddSlotToShelf = { showAddWidgetSheet = true },
                            onEditSlotAt = { idx -> editingSlotIndex = idx },
                            onAddSystemWidget = {
                                allSystemWidgets = LockDockWidgetHostManager.loadAllInstalledWidgets(context)
                                showSystemWidgetPickerSheet = true
                            },
                            onRemoveHostedAt = { removeIdx ->
                                val target = config.hostedWidgets.getOrNull(removeIdx)
                                if (target != null) {
                                    runCatching {
                                        LockDockWidgetHostManager.getHost(context)
                                            .deleteAppWidgetId(target.appWidgetId)
                                    }
                                    val nextList = config.hostedWidgets.filterIndexed { i, _ -> i != removeIdx }
                                    updateAndSave(config.copy(hostedWidgets = nextList))
                                    resumeGeneration++
                                }
                            },
                            onOpenAppDetails = {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                                runCatching { context.startActivity(intent) }
                            },
                            onOpenAccessibility = {
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                }
                            },
                            onTestPreview = {
                                val ok = LockScreenOverlayService.requestTestPreview(4500L)
                                if (!ok) {
                                    Toast.makeText(
                                        context,
                                        "請先啟用上方「無障礙鎖屏服務」即可預覽！",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } else {
                                    Toast.makeText(
                                        context,
                                        "已在螢幕上方顯示 4.5 秒鎖屏預覽！",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        )

                        1 -> NativeAndroid17GuideSection(
                            config = config,
                            hasCalendarPerm = hasCalendarPerm,
                            onRequestCalendarPerm = {
                                calendarPermLauncher.launch(Manifest.permission.READ_CALENDAR)
                            },
                            onUpdateConfig = { updateAndSave(it) },
                            onPinKmCalendarToHome = {
                                val mgr = AppWidgetManager.getInstance(context)
                                if (mgr.isRequestPinAppWidgetSupported) {
                                    mgr.requestPinAppWidget(
                                        ComponentName(context, KmCalendarWidgetProvider::class.java),
                                        null,
                                        null
                                    )
                                }
                            },
                            onPinShelfToHome = {
                                val mgr = AppWidgetManager.getInstance(context)
                                if (mgr.isRequestPinAppWidgetSupported) {
                                    mgr.requestPinAppWidget(
                                        ComponentName(context, IosShelfWidgetProvider::class.java),
                                        null,
                                        null
                                    )
                                }
                            },
                            onOpenDisplaySettings = {
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS))
                                }
                            }
                        )

                        2 -> ContentDataEditorSection(
                            config = config,
                            newTodoDraft = newTodoDraft,
                            onNewTodoDraftChange = { newTodoDraft = it },
                            onUpdateConfig = { updateAndSave(it) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Custom Full-Phone System Widget Picker BottomSheet
    if (showSystemWidgetPickerSheet) {
        var query by remember { mutableStateOf("") }
        val filteredWidgets = remember(allSystemWidgets, query) {
            if (query.isBlank()) {
                allSystemWidgets
            } else {
                allSystemWidgets.filter {
                    it.appLabel.contains(query, ignoreCase = true) ||
                        it.widgetLabel.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
                }
            }
        }

        ModalBottomSheet(
            onDismissRequest = { showSystemWidgetPickerSheet = false },
            containerColor = Color(0xFF151D30)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "🧩 新增手機內小工具區塊（共 ${allSystemWidgets.size} 個）",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "加入後每個小工具都會變成獨立區塊，可隨時單獨開關顯示／隱藏！",
                    color = Color(0xFF90CAF9),
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜尋名稱（如 KM、發票、行事曆、天氣）...") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(10.dp))

                LazyColumn(
                    modifier = Modifier.height(400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 28.dp)
                ) {
                    items(filteredWidgets, key = { it.info.provider.flattenToString() }) { item ->
                        val iconBmp = remember(item.packageName) {
                            DeviceStateHelper.loadAppIconBitmap(context, item.packageName, 84)
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0x22FFFFFF))
                                .clickable {
                                    showSystemWidgetPickerSheet = false
                                    selectSystemWidget(item)
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (iconBmp != null) {
                                Image(
                                    bitmap = iconBmp.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.appLabel,
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = item.widgetLabel,
                                    color = Color(0xFFB8C7E0),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = Color(0x3382B1FF)
                            ) {
                                Text(
                                    text = item.cellBadge,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    color = Color(0xFF82B1FF),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Dialog: Top Inline Capsule Content Mode Picker
    if (showInlinePickerDialog) {
        AlertDialog(
            onDismissRequest = { showInlinePickerDialog = false },
            title = { Text("選擇「頂部資訊膠囊」顯示內容") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        InlineMode.DATE_AND_BATTERY,
                        InlineMode.COUNTDOWN_INLINE,
                        InlineMode.MEMO_INLINE
                    ).forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (config.inlineMode == mode && config.showTopCapsule) Color(0x3382B1FF)
                                    else Color(0x14FFFFFF)
                                )
                                .clickable {
                                    updateAndSave(
                                        config.copy(
                                            showTopCapsule = true,
                                            inlineMode = mode
                                        )
                                    )
                                    showInlinePickerDialog = false
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = mode.label, color = Color.White, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInlinePickerDialog = false }) {
                    Text("關閉")
                }
            }
        )
    }

    // BottomSheet: Add built-in iOS widget into the 4-unit shelf
    if (showAddWidgetSheet) {
        val usedSpan = config.slots.sumOf { it.type.span }
        val remainingSpan = (4 - usedSpan).coerceAtLeast(0)
        ModalBottomSheet(
            onDismissRequest = { showAddWidgetSheet = false },
            containerColor = Color(0xFF161E31)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "＋ 新增 iPhone 4 格列小工具（剩餘 $remainingSpan 格）",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(10.dp))

                LazyColumn(
                    modifier = Modifier.height(360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    val candidates = SlotWidgetType.entries.filter { it != SlotWidgetType.EMPTY_SLOT }
                    items(candidates) { type ->
                        val fits = type.span <= remainingSpan
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (fits) Color(0x24FFFFFF) else Color(0x0FFFFFFF))
                                .clickable(enabled = fits) {
                                    val newItem = SlotItem(
                                        type = type,
                                        enabled = true,
                                        customLabel = if (type == SlotWidgetType.COUNTDOWN_PILL) "重要日子" else "",
                                        customEmoji = if (type == SlotWidgetType.COUNTDOWN_PILL) "🎉" else "🔥"
                                    )
                                    val nextSlots = config.slots + newItem
                                    updateAndSave(config.copy(slots = nextSlots))
                                    showAddWidgetSheet = false
                                    if (type == SlotWidgetType.APP_SHORTCUT) {
                                        showAppPickerForSlot = nextSlots.lastIndex
                                    }
                                }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = type.title,
                                    color = if (fits) Color.White else Color(0x66FFFFFF),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (fits) type.subtitle else "空間不足（需 ${type.span} 格，請先刪除其他格子）",
                                    color = if (fits) Color(0xFFB0BEC5) else Color(0x66FF8A80),
                                    fontSize = 12.sp
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = if (fits) Color(0x3382B1FF) else Color(0x1AFFFFFF)
                            ) {
                                Text(
                                    text = type.badge,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    color = if (fits) Color(0xFF82B1FF) else Color(0x66FFFFFF),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Dialog: Edit / Toggle / Reorder / Remove an existing slot
    if (editingSlotIndex in config.slots.indices) {
        val idx = editingSlotIndex
        val slot = config.slots[idx]
        var labelDraft by remember(slot.id) { mutableStateOf(slot.customLabel) }
        var emojiDraft by remember(slot.id) { mutableStateOf(slot.customEmoji) }
        var dateDraft by remember(slot.id) { mutableStateOf(slot.targetDateIso) }

        AlertDialog(
            onDismissRequest = { editingSlotIndex = -1 },
            title = { Text("編輯格子：${slot.displayTitle()}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("顯示此格子", color = Color.White, fontSize = 14.sp)
                        Switch(
                            checked = slot.enabled,
                            onCheckedChange = { on ->
                                val nextSlots = config.slots.mapIndexed { i, item ->
                                    if (i == idx) item.copy(enabled = on) else item
                                }
                                updateAndSave(config.copy(slots = nextSlots))
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (idx > 0) {
                                    val mutable = config.slots.toMutableList()
                                    val tmp = mutable[idx - 1]
                                    mutable[idx - 1] = mutable[idx]
                                    mutable[idx] = tmp
                                    updateAndSave(config.copy(slots = mutable))
                                    editingSlotIndex = idx - 1
                                }
                            },
                            enabled = idx > 0,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("左移")
                        }
                        OutlinedButton(
                            onClick = {
                                if (idx < config.slots.lastIndex) {
                                    val mutable = config.slots.toMutableList()
                                    val tmp = mutable[idx + 1]
                                    mutable[idx + 1] = mutable[idx]
                                    mutable[idx] = tmp
                                    updateAndSave(config.copy(slots = mutable))
                                    editingSlotIndex = idx + 1
                                }
                            },
                            enabled = idx < config.slots.lastIndex,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("右移")
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                        }
                    }

                    if (slot.type == SlotWidgetType.APP_SHORTCUT) {
                        Text(
                            text = "目前綁定 App：${slot.appName.ifBlank { "尚未選擇" }}",
                            color = Color(0xFF90CAF9),
                            fontSize = 13.sp
                        )
                        Button(
                            onClick = {
                                editingSlotIndex = -1
                                showAppPickerForSlot = idx
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("更換啟動的手機 App")
                        }
                    }

                    if (slot.type == SlotWidgetType.COUNTDOWN_PILL) {
                        OutlinedTextField(
                            value = emojiDraft,
                            onValueChange = { emojiDraft = it.take(4) },
                            label = { Text("前綴圖示 Emoji") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = labelDraft,
                            onValueChange = { labelDraft = it },
                            label = { Text("倒數事件名稱") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = dateDraft,
                            onValueChange = { dateDraft = it },
                            label = { Text("目標日期 (YYYY-MM-DD)") },
                            singleLine = true
                        )
                    }

                    if (slot.type == SlotWidgetType.HABIT_STREAK) {
                        OutlinedTextField(
                            value = emojiDraft,
                            onValueChange = { emojiDraft = it.take(4) },
                            label = { Text("習慣圖示 Emoji") },
                            singleLine = true
                        )
                    }

                    Button(
                        onClick = {
                            val nextSlots = config.slots.filterIndexed { i, _ -> i != idx }
                            updateAndSave(config.copy(slots = nextSlots))
                            editingSlotIndex = -1
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("刪除此格子（釋放空間）", color = Color.White)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val updatedList = config.slots.mapIndexed { i, item ->
                            if (i == idx) {
                                item.copy(
                                    customLabel = labelDraft,
                                    customEmoji = emojiDraft.ifBlank { "🔥" },
                                    targetDateIso = dateDraft.trim()
                                )
                            } else {
                                item
                            }
                        }
                        updateAndSave(config.copy(slots = updatedList))
                        editingSlotIndex = -1
                    }
                ) {
                    Text("儲存設定")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingSlotIndex = -1 }) {
                    Text("取消")
                }
            }
        )
    }

    // Dialog: Installed App Picker for APP_SHORTCUT slot
    if (showAppPickerForSlot in config.slots.indices) {
        val targetSlotIdx = showAppPickerForSlot
        var searchQuery by remember { mutableStateOf("") }
        val filtered = remember(installedApps, searchQuery) {
            if (searchQuery.isBlank()) installedApps
            else installedApps.filter {
                it.label.contains(searchQuery, ignoreCase = true) ||
                    it.packageName.contains(searchQuery, ignoreCase = true)
            }
        }

        AlertDialog(
            onDismissRequest = { showAppPickerForSlot = -1 },
            title = { Text("選擇鎖定畫面快捷啟動 App") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("搜尋 App 名稱...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.height(300.dp)) {
                        items(filtered) { app ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        val updatedSlots = config.slots.mapIndexed { i, s ->
                                            if (i == targetSlotIdx) {
                                                s.copy(appPackage = app.packageName, appName = app.label)
                                            } else {
                                                s
                                            }
                                        }
                                        updateAndSave(config.copy(slots = updatedSlots))
                                        showAppPickerForSlot = -1
                                    }
                                    .padding(vertical = 10.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "🚀  ${app.label}",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAppPickerForSlot = -1 }) {
                    Text("取消")
                }
            }
        )
    }

    // Dialog: One-Tap In-App OTA Update Center (GitHub Releases)
    if (showUpdateDialog) {
        var repoDraft by remember { mutableStateOf(AppUpdater.getConfiguredRepo(context)) }
        var showRepoEdit by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = {
                if (!downloadingUpdate) showUpdateDialog = false
            },
            title = {
                Text("🔄 LockDock 線上更新中心", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "目前安裝版本：v$currentVerName (Build $currentVerCode)",
                        color = Color(0xFFB8C7E0),
                        fontSize = 13.sp
                    )

                    if (checkingUpdate) {
                        Text("正在連線 GitHub 檢查最新版本⋯⋯", color = Color(0xFF90CAF9), fontSize = 13.sp)
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else if (updateError != null) {
                        Text(
                            text = "⚠ ${updateError}",
                            color = Color(0xFFFF8A80),
                            fontSize = 13.sp
                        )
                    } else if (latestRelease != null) {
                        val rel = latestRelease!!
                        val isNewer = rel.remoteVersionCode > currentVerCode
                        val sizeMbStr = String.format("%.2f MB", rel.sizeBytes / (1024f * 1024f))

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isNewer) Color(0xFF17352A) else Color(0xFF1B2438)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = if (isNewer) "🎉 發現新版本：${rel.tagName} (Build ${rel.remoteVersionCode})"
                                    else "✓ 目前已是最新版本 (${rel.tagName})",
                                    color = if (isNewer) Color(0xFF69F0AE) else Color(0xFF90CAF9),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "檔案大小：$sizeMbStr",
                                    color = Color(0xFFB0BEC5),
                                    fontSize = 11.sp
                                )
                                if (rel.changelog.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = rel.changelog,
                                        color = Color.White,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }

                        if (downloadingUpdate) {
                            Text(
                                text = "正在下載更新檔⋯⋯ $downloadProgress%",
                                color = Color(0xFF69F0AE),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            LinearProgressIndicator(
                                progress = { (downloadProgress / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Button(
                                onClick = {
                                    downloadingUpdate = true
                                    downloadProgress = 0
                                    updateError = null
                                    coroutineScope.launch {
                                        val dl = AppUpdater.downloadAndInstallApk(
                                            context = context,
                                            apkUrl = rel.apkDownloadUrl,
                                            onProgress = { pct -> downloadProgress = pct }
                                        )
                                        downloadingUpdate = false
                                        dl.onSuccess {
                                            showUpdateDialog = false
                                        }.onFailure { err ->
                                            updateError = err.message ?: "下載失敗"
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isNewer) Color(0xFF69F0AE) else Color(0xFF4FC3F7)
                                )
                            ) {
                                Text(
                                    text = if (isNewer) "⬇ 立即下載並安裝更新" else "↻ 重新下載並覆蓋安裝此版本",
                                    color = Color(0xFF061610),
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                    }

                    TextButton(
                        onClick = { showRepoEdit = !showRepoEdit },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            text = "更新來源：github.com/${AppUpdater.getConfiguredRepo(context)}（點此修改）",
                            fontSize = 11.sp,
                            color = Color(0xFF78909C)
                        )
                    }

                    if (showRepoEdit) {
                        OutlinedTextField(
                            value = repoDraft,
                            onValueChange = { repoDraft = it },
                            label = { Text("GitHub 帳號/倉庫") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedButton(
                            onClick = {
                                AppUpdater.setConfiguredRepo(context, repoDraft)
                                showRepoEdit = false
                                triggerUpdateCheck(openDialog = true)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("儲存來源並重新檢查")
                        }
                    }
                }
            },
            confirmButton = {
                if (!downloadingUpdate) {
                    TextButton(onClick = { triggerUpdateCheck(openDialog = true) }) {
                        Text("重新檢查")
                    }
                }
            },
            dismissButton = {
                if (!downloadingUpdate) {
                    TextButton(onClick = { showUpdateDialog = false }) {
                        Text("關閉")
                    }
                }
            }
        )
    }
}

/**
 * Tab 0: Unified Per-Block Visibility & Order Control Center!
 * Every single block on the lock screen has its own independent ON/OFF Switch.
 */
@Composable
private fun BlockVisibilityControlSection(
    config: LockDockConfig,
    isAccessibilityActive: Boolean,
    onUpdateConfig: (LockDockConfig) -> Unit,
    onChangeCapsuleMode: () -> Unit,
    onAddSlotToShelf: () -> Unit,
    onEditSlotAt: (Int) -> Unit,
    onAddSystemWidget: () -> Unit,
    onRemoveHostedAt: (Int) -> Unit,
    onOpenAppDetails: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onTestPreview: () -> Unit
) {
    var expandShelfOptions by remember { mutableStateOf(false) }
    var expandedHostedIdx by remember { mutableIntStateOf(-1) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Master Lock Screen Overlay Switch
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1D2844)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "🔒 鎖定畫面顯示總開關",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isAccessibilityActive) "✓ 鎖屏服務運作中 · 下方各區塊可獨立開關" else "⚠ 請先啟用下方無障礙鎖屏服務",
                        color = if (isAccessibilityActive) Color(0xFF69F0AE) else Color(0xFFFFB74D),
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = config.overlayEnabled,
                    onCheckedChange = { onUpdateConfig(config.copy(overlayEnabled = it)) }
                )
            }
        }

        if (!isAccessibilityActive) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF28213A)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "啟用無障礙鎖屏服務（若遇受限制提示，先點左邊按鈕允許受限制的設定）：",
                        color = Color(0xFFFFE082),
                        fontSize = 12.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(onClick = onOpenAppDetails, modifier = Modifier.weight(1f)) {
                            Text("App 資訊 (⋮)", fontSize = 12.sp)
                        }
                        Button(onClick = onOpenAccessibility, modifier = Modifier.weight(1f)) {
                            Text("開啟無障礙服務", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        Text(
            text = "👇 勾選要在鎖定畫面上顯示的區塊（關閉不會刪除設定，可隨時再打開）",
            color = Color(0xFFB8C7E0),
            fontSize = 12.sp
        )

        // ================= BLOCK 1: TOP CAPSULE =================
        BlockControlCard(
            badge = "區塊 1",
            title = "頂部資訊膠囊",
            subtitle = "目前樣式：${config.inlineMode.label}",
            checked = config.showTopCapsule,
            onCheckedChange = { onUpdateConfig(config.copy(showTopCapsule = it)) },
            trailingAction = {
                TextButton(onClick = onChangeCapsuleMode) {
                    Text("換內容", fontSize = 12.sp)
                }
            }
        )

        // ================= BLOCK 2: IPHONE 4-SLOT SHELF =================
        BlockControlCard(
            badge = "區塊 2",
            title = "iPhone 4 格小工具列",
            subtitle = "已啟用 ${config.slots.count { it.enabled }} 個小工具 · 點「細項」可個別開關格子",
            checked = config.showIosShelfInOverlay,
            onCheckedChange = { onUpdateConfig(config.copy(showIosShelfInOverlay = it)) },
            trailingAction = {
                TextButton(onClick = { expandShelfOptions = !expandShelfOptions }) {
                    Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(if (expandShelfOptions) "收起" else "細項", fontSize = 12.sp)
                }
            }
        ) {
            if (expandShelfOptions) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    HorizontalDivider(color = Color(0x22FFFFFF))

                    // Sub-switches for each slot inside the 4-slot shelf!
                    Text("個別格子顯示開關：", color = Color(0xFF90CAF9), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    config.slots.forEachIndexed { idx, slot ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0x18FFFFFF))
                                .clickable { onEditSlotAt(idx) }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${slot.displayTitle()} (${slot.type.span}格) · 點擊編輯",
                                color = if (slot.enabled) Color.White else Color(0x77FFFFFF),
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = slot.enabled,
                                onCheckedChange = { on ->
                                    val nextSlots = config.slots.mapIndexed { i, s ->
                                        if (i == idx) s.copy(enabled = on) else s
                                    }
                                    onUpdateConfig(config.copy(slots = nextSlots))
                                }
                            )
                        }
                    }

                    if (config.slots.sumOf { it.type.span } < 4) {
                        OutlinedButton(
                            onClick = onAddSlotToShelf,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("在此列新增小工具格", fontSize = 12.sp)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("鎖屏隱藏未放滿的空白「＋」格", color = Color.White, fontSize = 12.sp)
                        Switch(
                            checked = config.hideEmptySlotsOnLockScreen,
                            onCheckedChange = { onUpdateConfig(config.copy(hideEmptySlotsOnLockScreen = it)) }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("顯示外層毛玻璃邊框底板", color = Color.White, fontSize = 12.sp)
                        Switch(
                            checked = config.showShelfOuterFrame,
                            onCheckedChange = { onUpdateConfig(config.copy(showShelfOuterFrame = it)) }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("將此列移至嵌入小工具（如月曆）下方", color = Color.White, fontSize = 12.sp)
                        Switch(
                            checked = config.shelfBelowHosted,
                            onCheckedChange = { onUpdateConfig(config.copy(shelfBelowHosted = it)) }
                        )
                    }
                }
            }
        }

        // ================= BLOCK 3..N: EACH HOSTED WIDGET (KM 行事曆, 發票存摺, etc.) =================
        config.hostedWidgets.forEachIndexed { idx, entry ->
            val isExpanded = expandedHostedIdx == idx
            BlockControlCard(
                badge = "區塊 ${idx + 3}",
                title = entry.label,
                subtitle = "高度 ${entry.heightDp}dp · 寬度 ${entry.scalePercent}%",
                checked = entry.enabled,
                onCheckedChange = { on ->
                    val updated = config.hostedWidgets.mapIndexed { i, e ->
                        if (i == idx) e.copy(enabled = on) else e
                    }
                    onUpdateConfig(config.copy(hostedWidgets = updated))
                },
                trailingAction = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (config.hostedWidgets.size > 1) {
                            IconButton(
                                onClick = {
                                    if (idx > 0) {
                                        val list = config.hostedWidgets.toMutableList()
                                        val tmp = list[idx - 1]
                                        list[idx - 1] = list[idx]
                                        list[idx] = tmp
                                        onUpdateConfig(config.copy(hostedWidgets = list))
                                    }
                                },
                                enabled = idx > 0,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "上移", tint = Color.White)
                            }
                            IconButton(
                                onClick = {
                                    if (idx < config.hostedWidgets.lastIndex) {
                                        val list = config.hostedWidgets.toMutableList()
                                        val tmp = list[idx + 1]
                                        list[idx + 1] = list[idx]
                                        list[idx] = tmp
                                        onUpdateConfig(config.copy(hostedWidgets = list))
                                    }
                                },
                                enabled = idx < config.hostedWidgets.lastIndex,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "下移", tint = Color.White)
                            }
                        }
                        TextButton(
                            onClick = { expandedHostedIdx = if (isExpanded) -1 else idx },
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) {
                            Text(if (isExpanded) "收起" else "尺寸", fontSize = 12.sp)
                        }
                    }
                }
            ) {
                if (isExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        HorizontalDivider(color = Color(0x22FFFFFF))
                        Text(
                            text = "顯示高度：${entry.heightDp} dp（像發票載具條碼可調低至 80~100dp）",
                            color = Color(0xFFB8C7E0),
                            fontSize = 12.sp
                        )
                        Slider(
                            value = entry.heightDp.toFloat(),
                            onValueChange = { newH ->
                                val updated = config.hostedWidgets.mapIndexed { i, e ->
                                    if (i == idx) e.copy(heightDp = newH.toInt()) else e
                                }
                                onUpdateConfig(config.copy(hostedWidgets = updated))
                            },
                            valueRange = 60f..380f
                        )
                        Text(
                            text = "寬度縮放比例：${entry.scalePercent}%",
                            color = Color(0xFFB8C7E0),
                            fontSize = 12.sp
                        )
                        Slider(
                            value = entry.scalePercent.toFloat(),
                            onValueChange = { newS ->
                                val updated = config.hostedWidgets.mapIndexed { i, e ->
                                    if (i == idx) e.copy(scalePercent = newS.toInt()) else e
                                }
                                onUpdateConfig(config.copy(hostedWidgets = updated))
                            },
                            valueRange = 75f..100f
                        )
                        OutlinedButton(
                            onClick = {
                                expandedHostedIdx = -1
                                onRemoveHostedAt(idx)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFFF8A80))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("永久刪除此小工具區塊", color = Color(0xFFFF8A80), fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Add another system widget block button
        Button(
            onClick = onAddSystemWidget,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4FC3F7))
        ) {
            Icon(Icons.Default.Widgets, contentDescription = null, tint = Color(0xFF061320))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "＋ 新增手機內其他小工具區塊",
                color = Color(0xFF061320),
                fontWeight = FontWeight.Bold
            )
        }

        HorizontalDivider(color = Color(0x24FFFFFF))

        // Unlock Zone 1-Second Hold Dismissal Section
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFF1A253D),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0x554FC3F7), RoundedCornerShape(18.dp))
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "👆 手指放在解鎖區按住自動關閉",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "手指放在下方 🔓 解鎖圓圈滿 ${(config.holdUnlockDurationMs / 100f).roundToInt() / 10f} 秒立即收起小工具（下次喚醒螢幕自動恢復）",
                            color = Color(0xFFB8C7E0),
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = config.holdUnlockZoneToDismiss,
                        onCheckedChange = { onUpdateConfig(config.copy(holdUnlockZoneToDismiss = it)) }
                    )
                }

                if (config.holdUnlockZoneToDismiss) {
                    val context = LocalContext.current
                    Text(
                        text = "按住觸發時間：${String.format("%.1f", config.holdUnlockDurationMs / 1000f)} 秒",
                        color = Color(0xFF90CAF9),
                        fontSize = 12.sp
                    )
                    Slider(
                        value = config.holdUnlockDurationMs.toFloat(),
                        onValueChange = {
                            val snapped = ((it / 100f).roundToInt() * 100).coerceIn(400, 2000)
                            onUpdateConfig(config.copy(holdUnlockDurationMs = snapped))
                        },
                        valueRange = 400f..2000f
                    )

                    Text(
                        text = "解鎖圓圈中心位置：水平 ${String.format("%.1f", config.unlockZoneXPermille / 10f)}% · 垂直 ${String.format("%.1f", config.unlockZoneYPermille / 10f)}%（Pixel 10 Pro XL 實測值 74.2%）",
                        color = Color(0xFF90CAF9),
                        fontSize = 12.sp
                    )
                    Slider(
                        value = config.unlockZoneYPermille.toFloat(),
                        onValueChange = {
                            onUpdateConfig(config.copy(unlockZoneYPermille = it.roundToInt()))
                        },
                        valueRange = 550f..880f
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val ok = LockScreenOverlayService.requestDragCalibration(10_000L)
                                if (ok) {
                                    Toast.makeText(
                                        context,
                                        "已顯示藍色準星 10 秒！可直接用手指按住準星拖曳對準 🔓 位置",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } else {
                                    Toast.makeText(
                                        context,
                                        "請先啟用上方無障礙鎖屏服務",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            modifier = Modifier.weight(1.3f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4FC3F7))
                        ) {
                            Text("🎯 10秒實景拖曳對位", color = Color(0xFF061320), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                onUpdateConfig(
                                    config.copy(unlockZoneXPermille = 500, unlockZoneYPermille = 742)
                                )
                                Toast.makeText(context, "已恢復 Pixel 10 Pro XL 精準位置 (50.0%, 74.2%)", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("↺ 恢復預設 74.2%", fontSize = 12.sp)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "📞⏰ 來電（含 LINE 通話）、鬧鐘、計時器或鎖屏相機時自動隱藏",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = config.hideOnCallAndAlarm,
                        onCheckedChange = { onUpdateConfig(config.copy(hideOnCallAndAlarm = it)) }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "指紋／人臉從上鎖剛解鎖時也自動關閉",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = config.dismissOnBiometricUnlock,
                        onCheckedChange = { onUpdateConfig(config.copy(dismissOnBiometricUnlock = it)) }
                    )
                }
            }
        }

        HorizontalDivider(color = Color(0x24FFFFFF))

        // Global Position, Spacing & Opacity Sliders
        Text(
            text = "📐 整體鎖屏位置與間距微調",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        Text("距離螢幕頂端高度：${config.overlayYOffsetDp} dp", color = Color(0xFFB8C7E0), fontSize = 12.sp)
        Slider(
            value = config.overlayYOffsetDp.toFloat(),
            onValueChange = { onUpdateConfig(config.copy(overlayYOffsetDp = it.toInt())) },
            valueRange = 70f..580f
        )

        Text("區塊之間上下間距：${config.blockSpacingDp} dp", color = Color(0xFFB8C7E0), fontSize = 12.sp)
        Slider(
            value = config.blockSpacingDp.toFloat(),
            onValueChange = { onUpdateConfig(config.copy(blockSpacingDp = it.toInt())) },
            valueRange = 0f..24f
        )

        Text("整體不透明度：${config.overlayAlphaPercent}%", color = Color(0xFFB8C7E0), fontSize = 12.sp)
        Slider(
            value = config.overlayAlphaPercent.toFloat(),
            onValueChange = { onUpdateConfig(config.copy(overlayAlphaPercent = it.toInt())) },
            valueRange = 50f..100f
        )

        OutlinedButton(
            onClick = onTestPreview,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("👀 立即預覽 4.5 秒（同時顯示藍色解鎖偵測圈對位）", color = Color(0xFF82B1FF), fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

@Composable
private fun BlockControlCard(
    badge: String,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    trailingAction: @Composable (() -> Unit)? = null,
    expandedContent: @Composable (() -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (checked) Color(0xFF1E2945) else Color(0xFF161C2C),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (checked) Color(0x6682B1FF) else Color(0x22FFFFFF),
                shape = RoundedCornerShape(18.dp)
            )
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .alpha(if (checked) 1f else 0.55f)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (checked) Color(0x3369F0AE) else Color(0x22FFFFFF)
                        ) {
                            Text(
                                text = badge,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                color = if (checked) Color(0xFF69F0AE) else Color(0xFF90A4AE),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = title,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        color = Color(0xFFB0BEC5),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                trailingAction?.invoke()

                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange
                )
            }
            expandedContent?.invoke()
        }
    }
}

@Composable
private fun IphoneLockScreenPreviewCard(
    config: LockDockConfig,
    currentTimeStr: String,
    batteryInfo: Pair<Int, Boolean>,
    resumeGeneration: Int,
    onTapInlinePill: () -> Unit,
    onTapExistingSlot: (Int) -> Unit,
    onTapAddSlot: () -> Unit
) {
    val context = LocalContext.current
    val enabledSlotsWithIdx = config.slots.mapIndexedNotNull { idx, item ->
        if (item.enabled) idx to item else null
    }
    val usedTotalSpan = config.slots.sumOf { it.type.span }
    val showAddPlaceholder = !config.hideEmptySlotsOnLockScreen && usedTotalSpan < 4
    val emptyCount = if (showAddPlaceholder) (4 - enabledSlotsWithIdx.sumOf { it.second.type.span }).coerceAtLeast(0) else 0

    @Composable
    fun RenderShelfPreview() {
        if (!config.showIosShelfInOverlay) return
        if (enabledSlotsWithIdx.isEmpty() && emptyCount == 0) return

        Spacer(modifier = Modifier.height(config.blockSpacingDp.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .then(
                    if (config.showShelfOuterFrame) {
                        Modifier
                            .clip(RoundedCornerShape(22.dp))
                            .background(Color(0x280D111C))
                            .border(1.5.dp, Color(0x66FFFFFF), RoundedCornerShape(22.dp))
                    } else {
                        Modifier
                    }
                )
                .padding(7.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            enabledSlotsWithIdx.forEach { (realIdx, item) ->
                if (item.type.span == 1) {
                    ComposeCircleSlotPreview(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .fillMaxHeight()
                            .clickable { onTapExistingSlot(realIdx) },
                        item = item,
                        config = config,
                        batteryInfo = batteryInfo
                    )
                } else {
                    val (header, title, sub) = IosShelfWidgetProvider.formatPillStrings(item, config)
                    Column(
                        modifier = Modifier
                            .weight(2f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0x38FFFFFF))
                            .border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(16.dp))
                            .clickable { onTapExistingSlot(realIdx) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(header, color = Color(0xCCFFFFFF), fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(sub, color = Color(0xB8FFFFFF), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            repeat(emptyCount) {
                Box(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxHeight()
                        .aspectRatio(1f)
                        .clip(CircleShape)
                        .background(Color(0x1FFFFFFF))
                        .border(1.dp, Color(0x6682B1FF), CircleShape)
                        .clickable { onTapAddSlot() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Add, contentDescription = "新增小工具", tint = Color(0xFF90CAF9))
                }
            }
        }
    }

    @Composable
    fun RenderHostedPreviews() {
        config.hostedWidgets.filter { it.enabled }.forEach { entry ->
            Spacer(modifier = Modifier.height(config.blockSpacingDp.dp))
            key(entry.appWidgetId, entry.heightDp, entry.scalePercent, resumeGeneration) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(entry.heightDp.dp),
                    factory = { ctx ->
                        FrameLayout(ctx).apply {
                            clipChildren = false
                            clipToPadding = false
                            val density = ctx.resources.displayMetrics.density.coerceAtLeast(1f)
                            val availWidthPx = ctx.resources.displayMetrics.widthPixels - (56 * density).roundToInt()
                            val scale = (entry.scalePercent / 100f).coerceIn(0.75f, 1f)
                            val virtW = (availWidthPx / scale).roundToInt()
                            val virtH = ((entry.heightDp * density) / scale).roundToInt()
                            val virtWDp = (virtW / density).roundToInt().coerceAtLeast(390)
                            val virtHDp = (virtH / density).roundToInt().coerceAtLeast(60)

                            val hv = LockDockWidgetHostManager.createConfiguredHostView(
                                context = ctx,
                                appWidgetId = entry.appWidgetId,
                                heightDp = virtHDp,
                                virtualWidthDp = virtWDp
                            )
                            if (hv != null) {
                                hv.scaleX = scale
                                hv.scaleY = scale
                                addView(
                                    hv,
                                    FrameLayout.LayoutParams(virtW, virtH, Gravity.CENTER)
                                )
                            }
                        }
                    }
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1C294A),
                        Color(0xFF231F42),
                        Color(0xFF101526)
                    )
                )
            )
            .border(2.dp, Color(0x38FFFFFF), RoundedCornerShape(28.dp))
            .padding(horizontal = 12.dp, vertical = 14.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = currentTimeStr,
                color = Color(0xF5FFFFFF),
                fontSize = 52.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-2).sp
            )

            // Block 1: Top Capsule (only shown when showTopCapsule == true!)
            if (config.showTopCapsule && config.inlineMode != InlineMode.HIDDEN) {
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0x29FFFFFF))
                        .border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(999.dp))
                        .clickable { onTapInlinePill() }
                        .padding(horizontal = 14.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = IosShelfWidgetProvider.formatInlineText(context, config),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Block 2 & Block 3..N in user-chosen vertical order!
            if (config.shelfBelowHosted) {
                RenderHostedPreviews()
                RenderShelfPreview()
            } else {
                RenderShelfPreview()
                RenderHostedPreviews()
            }
        }
    }
}

@Composable
private fun ComposeCircleSlotPreview(
    modifier: Modifier,
    item: SlotItem,
    config: LockDockConfig,
    batteryInfo: Pair<Int, Boolean>
) {
    val (progress, centerText, bottomLabel, ringColor) = when (item.type) {
        SlotWidgetType.BATTERY_RING -> {
            val (lvl, chg) = batteryInfo
            Quad(lvl / 100f, "$lvl%", if (chg) "⚡" else "🔋", if (chg) Color(0xFF69F0AE) else Color.White)
        }
        SlotWidgetType.WATER_RING -> {
            val g = config.waterGoal.coerceAtLeast(1)
            Quad((config.waterCount.toFloat() / g).coerceIn(0f, 1f), "${config.waterCount}/$g", "💧", Color(0xFF70D1FF))
        }
        SlotWidgetType.HABIT_STREAK -> {
            val g = config.habitGoal.coerceAtLeast(1)
            Quad((config.habitCount.toFloat() / g).coerceIn(0f, 1f), "${config.habitCount}/$g", item.customEmoji.ifBlank { "🔥" }, Color(0xFFFFB74D))
        }
        SlotWidgetType.YEAR_RING -> {
            val now = LocalDate.now()
            val pct = ((now.dayOfYear.toFloat() / now.lengthOfYear()) * 100f).toInt()
            Quad(pct / 100f, "$pct%", "${now.year}", Color(0xFFC5A3FF))
        }
        SlotWidgetType.TORCH_TOGGLE -> Quad(1f, "🔦", "手電筒", Color(0xFFFFD54F))
        SlotWidgetType.APP_SHORTCUT -> Quad(1f, "🚀", item.appName.ifBlank { "選App" }.take(5), Color(0xFF82B1FF))
        else -> Quad(0f, "＋", "", Color.White)
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(Color(0x38FFFFFF)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(6.dp)) {
            val stroke = 5.dp.toPx()
            drawArc(
                color = Color(0x3DFFFFFF),
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(size.width - stroke, size.height - stroke)
            )
            drawArc(
                color = ringColor,
                startAngle = 135f,
                sweepAngle = 270f * progress.coerceIn(0.03f, 1f),
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(size.width - stroke, size.height - stroke)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerText, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(bottomLabel, color = Color(0xDDFFFFFF), fontSize = 9.sp, maxLines = 1)
        }
    }
}

private data class Quad(
    val progress: Float,
    val centerText: String,
    val bottomLabel: String,
    val color: Color
)

@Composable
private fun NativeAndroid17GuideSection(
    config: LockDockConfig,
    hasCalendarPerm: Boolean,
    onRequestCalendarPerm: () -> Unit,
    onUpdateConfig: (LockDockConfig) -> Unit,
    onPinKmCalendarToHome: () -> Unit,
    onPinShelfToHome: () -> Unit,
    onOpenDisplaySettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF212B46)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "📅 KM 同款 4×2 鎖屏月曆＋可滑動行程小工具",
                    color = Color(0xFF90CAF9),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "專為 Android 17 原生左滑鎖屏小工具頁打造（4 格寬相容版），讀取手機系統行事曆資料庫與日曆顏色。",
                    color = Color(0xFFE0E8FF),
                    fontSize = 12.sp
                )

                Button(
                    onClick = onRequestCalendarPerm,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (hasCalendarPerm) Color(0xFF66BB6A) else Color(0xFFFFB74D)
                    )
                ) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = Color(0xFF0A140E))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (hasCalendarPerm) "✅ 已授權讀取系統行事曆" else "① 點此授權讀取系統行事曆",
                        color = Color(0xFF0A140E),
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("顯示全天行程", color = Color.White, fontSize = 13.sp)
                    Switch(
                        checked = config.calendarShowAllDay,
                        onCheckedChange = { onUpdateConfig(config.copy(calendarShowAllDay = it)) }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("右邊顯示天數：${config.calendarDaysAhead} 天", color = Color.White, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = {
                                onUpdateConfig(
                                    config.copy(calendarDaysAhead = (config.calendarDaysAhead - 1).coerceAtLeast(1))
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                        ) { Text("－") }
                        OutlinedButton(
                            onClick = {
                                onUpdateConfig(
                                    config.copy(calendarDaysAhead = (config.calendarDaysAhead + 1).coerceAtMost(60))
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                        ) { Text("＋") }
                    }
                }
            }
        }

        Button(
            onClick = onOpenDisplaySettings,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF82B1FF))
        ) {
            Icon(Icons.Default.Settings, contentDescription = null, tint = Color(0xFF0A1224))
            Spacer(modifier = Modifier.width(8.dp))
            Text("前往 Pixel「顯示與鎖定畫面設定」", color = Color(0xFF0A1224), fontWeight = FontWeight.Bold)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onPinKmCalendarToHome, modifier = Modifier.weight(1f)) {
                Text("加「4×2 月曆」到桌面", fontSize = 12.sp)
            }
            OutlinedButton(onClick = onPinShelfToHome, modifier = Modifier.weight(1f)) {
                Text("加「4格列」到桌面", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ContentDataEditorSection(
    config: LockDockConfig,
    newTodoDraft: String,
    onNewTodoDraftChange: (String) -> Unit,
    onUpdateConfig: (LockDockConfig) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("✨ 快速套用 iPhone 4 格列範本", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AssistChip(
                onClick = { onUpdateConfig(config.copy(slots = LockDockConfig.defaultSlots())) },
                label = { Text("經典 (電量+喝水+倒數)") },
                colors = AssistChipDefaults.assistChipColors(labelColor = Color.White)
            )
            AssistChip(
                onClick = {
                    onUpdateConfig(
                        config.copy(
                            slots = listOf(
                                SlotItem(type = SlotWidgetType.TODO_PILL),
                                SlotItem(type = SlotWidgetType.MEMO_PILL)
                            )
                        )
                    )
                },
                label = { Text("雙膠囊 (待辦+備忘)") },
                colors = AssistChipDefaults.assistChipColors(labelColor = Color.White)
            )
            AssistChip(
                onClick = {
                    onUpdateConfig(
                        config.copy(
                            slots = listOf(
                                SlotItem(type = SlotWidgetType.BATTERY_RING),
                                SlotItem(type = SlotWidgetType.TORCH_TOGGLE),
                                SlotItem(type = SlotWidgetType.WATER_RING),
                                SlotItem(type = SlotWidgetType.YEAR_RING)
                            )
                        )
                    )
                },
                label = { Text("極簡四圓環") },
                colors = AssistChipDefaults.assistChipColors(labelColor = Color.White)
            )
        }

        HorizontalDivider(color = Color(0x24FFFFFF))

        Text("📝 鎖屏置頂備忘錄／座右銘", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = config.memoText,
            onValueChange = { onUpdateConfig(config.copy(memoText = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("顯示於備忘錄膠囊與待辦卡") },
            singleLine = true
        )

        HorizontalDivider(color = Color(0x24FFFFFF))

        Text("💧 每日喝水與習慣目標設定", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("今日喝水：${config.waterCount} / ${config.waterGoal} 杯", color = Color(0xFF90CAF9))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = { onUpdateConfig(config.copy(waterCount = 0)) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) { Text("歸零", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = { onUpdateConfig(config.copy(waterGoal = (config.waterGoal % 16) + 1)) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) { Text("目標 +1", fontSize = 12.sp) }
            }
        }

        HorizontalDivider(color = Color(0x24FFFFFF))

        Text("✅ 鎖定畫面待辦事項清單", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = newTodoDraft,
                onValueChange = onNewTodoDraftChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("輸入新的待辦事項...") },
                singleLine = true
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    val clean = newTodoDraft.trim()
                    if (clean.isNotEmpty()) {
                        onUpdateConfig(config.copy(todos = listOf(TodoItem(text = clean)) + config.todos))
                        onNewTodoDraftChange("")
                    }
                }
            ) {
                Icon(Icons.Default.Add, contentDescription = "新增")
            }
        }

        config.todos.forEachIndexed { index, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x1AFFFFFF))
                    .clickable {
                        val updated = config.todos.mapIndexed { i, t ->
                            if (i == index) t.copy(done = !t.done) else t
                        }
                        onUpdateConfig(config.copy(todos = updated))
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (item.done) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (item.done) Color(0xFF81C784) else Color.White
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = item.text,
                    modifier = Modifier.weight(1f),
                    color = if (item.done) Color(0x88FFFFFF) else Color.White,
                    textDecoration = if (item.done) TextDecoration.LineThrough else null,
                    fontSize = 14.sp
                )
                IconButton(
                    onClick = {
                        val updated = config.todos.filterIndexed { i, _ -> i != index }
                        onUpdateConfig(config.copy(todos = updated))
                    }
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "刪除", tint = Color(0x99FF8A80))
                }
            }
        }
    }
}
