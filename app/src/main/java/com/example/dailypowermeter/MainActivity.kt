package com.example.dailypowermeter

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.room.*
import com.google.accompanist.permissions.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// =========================================================================================
// --- [1. 資料模型與資料庫] ---
// =========================================================================================
@Entity(tableName = "bus_logs")
data class BusLog(
    @PrimaryKey(autoGenerate = true) val uid: Int = 0,
    val busId: String,
    val driverId: String,
    val chargerId: String,
    val outMileage: String,
    val inMileage: String,
    val outSoc: String,
    val inSoc: String,
    val timestamp: String
)

@Dao
interface BusDao {
    @Query("SELECT * FROM bus_logs ORDER BY uid DESC LIMIT 20")
    fun getRecentLogs(): Flow<List<BusLog>>
    @Insert suspend fun insertLog(log: BusLog)
}

@Database(entities = [BusLog::class], version = 4, exportSchema = false)
abstract class BusDatabase : RoomDatabase() {
    abstract fun busDao(): BusDao
    companion object {
        @Volatile private var INSTANCE: BusDatabase? = null
        fun getDatabase(ctx: Context): BusDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(ctx.applicationContext, BusDatabase::class.java, "bus_system_db")
                .fallbackToDestructiveMigration().build().also { INSTANCE = it }
        }
    }
}

// =========================================================================================
// --- [2. ViewModel] ---
// =========================================================================================
data class PageState(
    val busId: String = "",
    val driverId: String = "",
    val outMileage: String = "",
    val inMileage: String = "",
    val outSoc: String = "",
    val inSoc: String = ""
)

class BusViewModel(private val dao: BusDao) : ViewModel() {
    val logs = dao.getRecentLogs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _pageStates = MutableStateFlow(List(5) { PageState() })
    val pageStates = _pageStates.asStateFlow()

    private val _toastEvent = Channel<String>()
    val toastEvent = _toastEvent.receiveAsFlow()

    private val googleScriptUrl = "https://script.google.com/macros/s/你的腳本ID/exec"
    private val httpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).build()

    fun updateState(index: Int, update: (PageState) -> PageState) {
        _pageStates.update { currentList ->
            currentList.toMutableList().apply {
                this[index] = update(this[index])
            }
        }
    }

    fun submit(index: Int) {
        val s = _pageStates.value[index]
        if (s.busId.isBlank()) {
            viewModelScope.launch { _toastEvent.send("車號不能為空") }
            return
        }
        val time = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date())
        val log = BusLog(0, s.busId, s.driverId, (index + 1).toString(), s.outMileage, s.inMileage, s.outSoc, s.inSoc, time)

        viewModelScope.launch(Dispatchers.IO) {
            dao.insertLog(log)
            postToCloud(s, index)
        }
    }

    private fun postToCloud(s: PageState, index: Int) {
        val dateStr = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date())
        val json = JSONObject().apply {
            put("date", dateStr); put("busId", s.busId); put("driverId", s.driverId)
            put("outMileage", s.outMileage); put("inMileage", s.inMileage)
            put("outSoc", s.outSoc); put("inSoc", s.inSoc); put("chargerId", (index + 1).toString())
        }
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(googleScriptUrl).post(body).build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                viewModelScope.launch { _toastEvent.send("網路異常，已儲存本地") }
            }
            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    viewModelScope.launch {
                        _toastEvent.send("同步成功")
                        updateState(index) { PageState() }
                    }
                }
                response.close()
            }
        })
    }
}

// =========================================================================================
// --- [3. UI 主介面] ---
// =========================================================================================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = BusDatabase.getDatabase(this)
        val vm = BusViewModel(db.busDao())

        setContent {
            val context = LocalContext.current
            LaunchedEffect(Unit) {
                vm.toastEvent.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
            }

            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF81C784), background = Color.Black)) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    BusAppMain(vm)
                }
            }
        }
    }
}

@Composable
fun BusAppMain(vm: BusViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF1E1E1E)) {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Default.Edit, null) }, label = { Text("錄入") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Default.History, null) }, label = { Text("紀錄") })
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            if (tab == 0) ChargingEntryPager(vm) else HistoryScreen(vm)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChargingEntryPager(vm: BusViewModel) {
    val pagerState = rememberPagerState(pageCount = { 5 })
    Column {
        ScrollableTabRow(selectedTabIndex = pagerState.currentPage, containerColor = Color.Black, contentColor = Color(0xFF81C784), edgePadding = 16.dp) {
            repeat(5) { i ->
                Tab(
                    selected = pagerState.currentPage == i,
                    onClick = { /* 由 Pager 處理切換 */ },
                    text = { Text("${i+1}號樁") }
                )
            }
        }
        HorizontalPager(state = pagerState) { pageIndex -> BusEntryScreen(vm, pageIndex) }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun BusEntryScreen(vm: BusViewModel, pageIndex: Int) {
    val states by vm.pageStates.collectAsState()
    val s = states[pageIndex]
    var scanningField by remember { mutableStateOf<String?>(null) }
    val perm = rememberPermissionState(Manifest.permission.CAMERA)

    if (scanningField != null && perm.status.isGranted) {
        Box(Modifier.fillMaxSize()) {
            CameraOCRScanner(targetField = scanningField!!) { result ->
                vm.updateState(pageIndex) { state ->
                    when(scanningField) {
                        "busId" -> state.copy(busId = result)
                        "driverId" -> state.copy(driverId = result)
                        "outM" -> state.copy(outMileage = result)
                        "inM" -> state.copy(inMileage = result)
                        "outS" -> state.copy(outSoc = result)
                        "inS" -> state.copy(inSoc = result)
                        else -> state
                    }
                }
                scanningField = null
            }
            IconButton(onClick = { scanningField = null }, modifier = Modifier.align(Alignment.TopStart).padding(16.dp).background(Color.Black.copy(0.6f), CircleShape)) {
                Icon(Icons.Default.ArrowBack, null, tint = Color.White)
            }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("憑單採集 - ${pageIndex + 1}號充電樁", style = MaterialTheme.typography.headlineSmall, color = Color(0xFF81C784), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(20.dp))

            DarkOCRInput("公車車號", s.busId, false, { v -> vm.updateState(pageIndex){ it.copy(busId = v) } }) {
                scanningField = "busId"; if(!perm.status.isGranted) perm.launchPermissionRequest()
            }
            Spacer(Modifier.height(16.dp))
            DarkOCRInput("駕駛員編號", s.driverId, true, { v -> vm.updateState(pageIndex){ it.copy(driverId = v) } }) {
                scanningField = "driverId"; if(!perm.status.isGranted) perm.launchPermissionRequest()
            }

            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { DarkOCRInput("出場里程", s.outMileage, true, { v -> vm.updateState(pageIndex){ it.copy(outMileage = v) } }) { scanningField = "outM" } }
                Box(Modifier.weight(1f)) { DarkOCRInput("返場里程", s.inMileage, true, { v -> vm.updateState(pageIndex){ it.copy(inMileage = v) } }) { scanningField = "inM" } }
            }

            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { DarkOCRInput("出場電量 (%)", s.outSoc, true, { v -> vm.updateState(pageIndex){ it.copy(outSoc = v) } }) { scanningField = "outS" } }
                Box(Modifier.weight(1f)) { DarkOCRInput("返場電量 (%)", s.inSoc, true, { v -> vm.updateState(pageIndex){ it.copy(inSoc = v) } }) { scanningField = "inS" } }
            }

            Spacer(Modifier.height(40.dp))
            Button(onClick = { vm.submit(pageIndex) }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(12.dp)) {
                Text("儲存並上傳", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun DarkOCRInput(label: String, value: String, isNumber: Boolean, onValueChange: (String) -> Unit, onScan: () -> Unit) {
    Column {
        Text(label, color = Color.Gray, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF1E1E1E),
                    unfocusedContainerColor = Color(0xFF1E1E1E),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Color(0xFF81C784)
                ),
                keyboardOptions = KeyboardOptions(keyboardType = if (isNumber) KeyboardType.Number else KeyboardType.Text)
            )
            IconButton(onClick = onScan) { Icon(Icons.Default.PhotoCamera, null, tint = Color(0xFF81C784)) }
        }
    }
}

@Composable
fun HistoryScreen(vm: BusViewModel) {
    val logs by vm.logs.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        item { Text("最近 20 筆紀錄", style = MaterialTheme.typography.titleLarge, color = Color(0xFF81C784)) }
        items(logs) { log ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("車號: ${log.busId}", color = Color.White, fontWeight = FontWeight.Bold)
                        Text(log.timestamp, color = Color.Gray, fontSize = 12.sp)
                    }
                    Text("${log.chargerId}號樁 | 駕駛: ${log.driverId}", color = Color(0xFF81C784), fontSize = 14.sp)
                    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = Color.DarkGray)
                    Row(Modifier.fillMaxWidth()) {
                        Text("里程: ${log.outMileage} -> ${log.inMileage}", Modifier.weight(1f), color = Color.LightGray, fontSize = 13.sp)
                        Text("電量: ${log.outSoc}% -> ${log.inSoc}%", Modifier.weight(1f), color = Color.LightGray, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

// =========================================================================================
// --- [4. CameraX + ML Kit OCR 元件 (純黑遮罩版)] ---
// =========================================================================================
@SuppressLint("UnsafeOptInUsageError")
@Composable
fun CameraOCRScanner(targetField: String, onResult: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    var camera by remember { mutableStateOf<Camera?>(null) }
    var isTorchOn by remember { mutableStateOf(false) }
    var lastZoomTime by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        val cameraProvider = ProcessCameraProvider.getInstance(context).get()
        // 建議使用 1080p 以確保 OCR 精度
        val targetSize = Size(1080, 1920)

        val preview = Preview.Builder()
            .setTargetResolution(targetSize)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(targetSize)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        val blocks = visionText.textBlocks
                        if (blocks.isNotEmpty()) {
                            // 1. 自動縮放邏輯
                            val boundingBox = blocks[0].boundingBox
                            if (boundingBox != null) {
                                val imageWidth = imageProxy.width.toFloat()
                                val blockWidth = boundingBox.width().toFloat()
                                val ratio = blockWidth / imageWidth
                                val currentTime = System.currentTimeMillis()

                                if (currentTime - lastZoomTime > 800) {
                                    if (ratio < 0.18f) {
                                        camera?.cameraControl?.setLinearZoom(0.4f)
                                        lastZoomTime = currentTime
                                    } else if (ratio > 0.6f) {
                                        camera?.cameraControl?.setLinearZoom(0f)
                                        lastZoomTime = currentTime
                                    }
                                }
                            }

                            // 2. 數據過濾
                            val detectedLines = blocks.flatMap { it.lines }.map { it.text.trim() }
                            val isNumericField = targetField.contains("M") || targetField.contains("S") || targetField.contains("driverId")

                            val finalResult = if (isNumericField) {
                                detectedLines.map { line -> line.filter { it.isDigit() } }
                                    .firstOrNull { it.length >= 1 } ?: ""
                            } else {
                                detectedLines.firstOrNull { it.length >= 3 } ?: ""
                            }

                            if (finalResult.isNotEmpty()) {
                                onResult(finalResult)
                                camera?.cameraControl?.setLinearZoom(0f)
                            }
                        }
                    }
                    .addOnCompleteListener { imageProxy.close() }
            } else {
                imageProxy.close()
            }
        }

        try {
            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
        } catch (e: Exception) { Log.e("OCR", "Camera binding failed", e) }
    }

    Box(Modifier.fillMaxSize()) {
        // 底層攝像頭預覽
        AndroidView({ previewView }, Modifier.fillMaxSize())

        // 上層遮罩層
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // --- [關鍵修改：將 alpha 移除，設為純黑] ---
            drawRect(Color.Black)

            // 挖空中間的掃描框
            drawRoundRect(
                color = Color.Transparent,
                topLeft = Offset(w * 0.1f, h * 0.4f),
                size = androidx.compose.ui.geometry.Size(w * 0.8f, h * 0.15f),
                blendMode = BlendMode.Clear
            )

            // 繪製掃描框的綠色邊框
            drawRoundRect(
                color = Color(0xFF81C784),
                topLeft = Offset(w * 0.1f, h * 0.4f),
                size = androidx.compose.ui.geometry.Size(w * 0.8f, h * 0.15f),
                style = Stroke(width = 4f)
            )
        }

        // 控制按鈕
        IconButton(
            onClick = {
                isTorchOn = !isTorchOn
                camera?.cameraControl?.enableTorch(isTorchOn)
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .background(Color.Gray.copy(0.3f), CircleShape) // 改為灰色半透明，在全黑背景下較易看見
        ) {
            Icon(
                imageVector = if (isTorchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                contentDescription = null,
                tint = if (isTorchOn) Color.Yellow else Color.White
            )
        }

        Text(
            text = "請將文字對準綠色框內",
            modifier = Modifier.align(Alignment.Center).padding(top = 180.dp),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
    }
}