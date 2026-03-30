package com.example.dailypowermeter

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

// --- [1. 資料模型與本地資料庫] ---
@Entity(tableName = "bus_logs")
data class BusLog(
    @PrimaryKey(autoGenerate = true) val uid: Int = 0,
    val busId: String, val driverId: String, val chargerId: String,
    val outMileage: String, val inMileage: String, val outSoc: String, val inSoc: String,
    val timestamp: String
)

@Dao interface BusDao {
    @Query("SELECT * FROM bus_logs ORDER BY uid DESC LIMIT 20") fun getRecentLogs(): Flow<List<BusLog>>
    @Insert suspend fun insertLog(log: BusLog)
}

@Database(entities = [BusLog::class], version = 5, exportSchema = false)
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

// --- [2. ViewModel: 處理邏輯與雲端上傳] ---
data class PageState(val busId: String = "", val driverId: String = "", val outMileage: String = "", val inMileage: String = "", val outSoc: String = "", val inSoc: String = "")

class BusViewModel(private val dao: BusDao) : ViewModel() {
    // 改成你部署後的 Google Apps Script URL
    private val GOOGLE_SCRIPT_URL = "https://script.google.com/macros/s/你的腳本ID/exec"

    val logs = dao.getRecentLogs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _pageStates = MutableStateFlow(List(5) { PageState() })
    val pageStates = _pageStates.asStateFlow()

    private val _toastEvent = Channel<String>()
    val toastEvent = _toastEvent.receiveAsFlow()

    fun updateState(index: Int, update: (PageState) -> PageState) {
        _pageStates.update { currentList -> currentList.toMutableList().apply { this[index] = update(this[index]) } }
    }

    fun submit(index: Int) {
        val s = _pageStates.value[index]
        if (s.busId.isBlank()) { viewModelScope.launch { _toastEvent.send("請先填寫/辨識車號") }; return }

        val time = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date())
        val log = BusLog(0, s.busId, s.driverId, (index + 1).toString(), s.outMileage, s.inMileage, s.outSoc, s.inSoc, time)

        viewModelScope.launch(Dispatchers.IO) {
            // A. 先存本地
            dao.insertLog(log)

            // B. 上傳 Google Sheets
            try {
                val json = JSONObject().apply {
                    put("timestamp", time); put("busId", s.busId); put("driverId", s.driverId)
                    put("chargerId", (index + 1).toString()); put("outMileage", s.outMileage)
                    put("inMileage", s.inMileage); put("outSoc", s.outSoc); put("inSoc", s.inSoc)
                }
                val client = OkHttpClient.Builder().followRedirects(true).build()
                val request = Request.Builder().url(GOOGLE_SCRIPT_URL)
                    .post(json.toString().toRequestBody("application/json".toMediaType())).build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) _toastEvent.send("雲端同步成功！")
                    else _toastEvent.send("雲端錯誤: ${response.code}")
                }
            } catch (e: Exception) {
                _toastEvent.send("本地已存，但雲端失敗: 檢查網路")
            }
        }
    }
}

// --- [3. UI 主程式] ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = BusDatabase.getDatabase(this)
        val vm = BusViewModel(db.busDao())
        setContent {
            val context = LocalContext.current
            LaunchedEffect(Unit) { vm.toastEvent.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF81C784), background = Color.Black)) {
                Surface(Modifier.fillMaxSize()) { BusAppMain(vm) }
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
        Box(Modifier.padding(padding)) { if (tab == 0) ChargingEntryPager(vm) else HistoryScreen(vm) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChargingEntryPager(vm: BusViewModel) {
    val pagerState = rememberPagerState(pageCount = { 5 })
    Column {
        ScrollableTabRow(selectedTabIndex = pagerState.currentPage, containerColor = Color.Black, contentColor = Color(0xFF81C784)) {
            repeat(5) { i -> Tab(selected = pagerState.currentPage == i, onClick = { }, text = { Text("${i+1}號樁") }) }
        }
        HorizontalPager(state = pagerState) { pageIndex -> BusEntryForm(vm, pageIndex) }
    }
}

@Composable
fun BusEntryForm(vm: BusViewModel, pageIndex: Int) {
    val states by vm.pageStates.collectAsState()
    val s = states[pageIndex]
    val context = LocalContext.current
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    // --- 相簿選取並自動辨識 ---
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val image = InputImage.fromFilePath(context, it)
            recognizer.process(image).addOnSuccessListener { visionText ->
                autoFillFromText(visionText.text, pageIndex, vm)
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("憑單錄入", style = MaterialTheme.typography.headlineSmall, color = Color(0xFF81C784), fontWeight = FontWeight.Bold)
            Button(onClick = { launcher.launch("image/*") }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))) {
                Icon(Icons.Default.PhotoLibrary, null)
                Text(" 上傳截圖")
            }
        }

        Spacer(Modifier.height(16.dp))
        DarkInput("公車車號", s.busId) { v -> vm.updateState(pageIndex) { it.copy(busId = v.uppercase()) } }
        DarkInput("駕駛員編號", s.driverId, true) { v -> vm.updateState(pageIndex) { it.copy(driverId = v) } }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { DarkInput("出場里程", s.outMileage, true) { v -> vm.updateState(pageIndex) { it.copy(outMileage = v) } } }
            Box(Modifier.weight(1f)) { DarkInput("返場里程", s.inMileage, true) { v -> vm.updateState(pageIndex) { it.copy(inMileage = v) } } }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { DarkInput("出場電量", s.outSoc, true) { v -> vm.updateState(pageIndex) { it.copy(outSoc = v) } } }
            Box(Modifier.weight(1f)) { DarkInput("返場電量", s.inSoc, true) { v -> vm.updateState(pageIndex) { it.copy(inSoc = v) } } }
        }

        Spacer(Modifier.height(32.dp))
        Button(onClick = { vm.submit(pageIndex) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("儲存並上傳至雲端", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// --- [4. 核心辨識邏輯：特徵排序法] ---
fun autoFillFromText(fullText: String, index: Int, vm: BusViewModel) {
    val text = fullText.uppercase().replace(" ", "")

    // 1. 車號
    Regex("[A-Z0-9]{3}-[0-9]{4}").find(text)?.value?.let { v -> vm.updateState(index) { it.copy(busId = v) } }

    // 2. 數字分類提取
    val allNumbers = Regex("[0-9]{1,7}").findAll(text).map { it.value }.toList()

    // 工號 (6位數)
    allNumbers.filter { it.length == 6 }.getOrNull(0)?.let { v -> vm.updateState(index) { it.copy(driverId = v) } }

    // 里程 (5位數，小的是出場，大的是返場)
    val miles = allNumbers.filter { it.length == 5 }.map { it.toInt() }.sorted()
    if (miles.size >= 2) {
        vm.updateState(index) { it.copy(outMileage = miles[0].toString(), inMileage = miles[1].toString()) }
    }

    // 電量 (<= 100 且 1-3位數，大的是出場，小的是返場)
    val socs = allNumbers.filter { it.toIntOrNull() in 1..100 && it.length <= 3 }.map { it.toInt() }.sortedDescending()
    if (socs.size >= 2) {
        vm.updateState(index) { it.copy(outSoc = socs[0].toString(), inSoc = socs[1].toString()) }
    }
}

@Composable
fun DarkInput(label: String, value: String, isNum: Boolean = false, onValueChange: (String) -> Unit) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(label, color = Color.Gray, fontSize = 12.sp)
        TextField(
            value = value, onValueChange = onValueChange, modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.colors(focusedContainerColor = Color(0xFF1E1E1E), unfocusedContainerColor = Color(0xFF1E1E1E), focusedTextColor = Color.White, unfocusedTextColor = Color.White),
            keyboardOptions = KeyboardOptions(keyboardType = if (isNum) KeyboardType.Number else KeyboardType.Text)
        )
    }
}

@Composable
fun HistoryScreen(vm: BusViewModel) {
    val logs by vm.logs.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        item { Text("歷史紀錄 (最近 20 筆)", style = MaterialTheme.typography.titleLarge, color = Color(0xFF81C784)) }
        items(logs) { log ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
                Column(Modifier.padding(12.dp)) {
                    Text("車號: ${log.busId} (${log.timestamp})", color = Color.White, fontWeight = FontWeight.Bold)
                    Text("里程: ${log.outMileage} -> ${log.inMileage} | 電量: ${log.outSoc}% -> ${log.inSoc}%", color = Color.LightGray, fontSize = 13.sp)
                }
            }
        }
    }
}