package com.example

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.*
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.*
import java.util.jar.Attributes
import java.util.jar.Manifest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Register BouncyCastle Security Provider
        try {
            Security.addProvider(BouncyCastleProvider())
        } catch (e: Exception) {
            e.printStackTrace()
        }

        enableEdgeToEdge()
        setContent {
            val systemDark = isSystemInDarkTheme()
            var isDarkTheme by remember { mutableStateOf(systemDark) }
            MyApplicationTheme(darkTheme = isDarkTheme) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        CenterAlignedTopAppBar(
                            title = {
                                Text(
                                    "Fontonica",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 24.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            },
                            actions = {
                                IconButton(onClick = { isDarkTheme = !isDarkTheme }) {
                                    Icon(
                                        imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                                        contentDescription = "Toggle Theme",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            )
                        )
                    }
                ) { innerPadding ->
                    FontonicaDashboard(
                        isDark = isDarkTheme,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }
    }
}

// Data class to hold details about loaded Roblox APK
data class RobloxApkDetails(
    val isValid: Boolean,
    val message: String,
    val fontFiles: List<String> = emptyList(),
    val fileSizeMb: Double = 0.0,
    val fileName: String = ""
)

// Data class for selected Font
data class FontFileDetails(
    val file: File,
    val name: String,
    val sizeKb: Double
)

// Validation status for chosen font files
enum class FontValidationResult {
    VALID,
    UNSUPPORTED_FONT,
    NOT_A_FONT
}

fun validateFontFile(fileName: String, context: Context, uri: Uri): FontValidationResult {
    val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    if (ext == "ttf" || ext == "otf") {
        return FontValidationResult.VALID
    }
    
    val unsupportedFontExts = setOf(
        "woff", "woff2", "eot", "ttc", "dfont", "pfa", "pfb", "afm", "bdf", "pcf", "fnt",
        "otc", "svg", "svgz", "pfm", "tfm"
    )
    if (unsupportedFontExts.contains(ext)) {
        return FontValidationResult.UNSUPPORTED_FONT
    }
    
    val mimeType = context.contentResolver.getType(uri)?.lowercase(Locale.ROOT) ?: ""
    if (mimeType.contains("font") || mimeType.contains("opentype") || mimeType.contains("truetype")) {
        return FontValidationResult.UNSUPPORTED_FONT
    }
    
    return FontValidationResult.NOT_A_FONT
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun FontonicaDashboard(isDark: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // App state variables
    var selectedFont by remember { mutableStateOf<FontFileDetails?>(null) }
    var fontValidationError by remember { mutableStateOf<String?>(null) }
    var selectedApkUri by remember { mutableStateOf<Uri?>(null) }
    var apkDetails by remember { mutableStateOf<RobloxApkDetails?>(null) }
    var isVerifyingApk by remember { mutableStateOf(false) }
    var replacementMode by remember { mutableStateOf("standard") } // "standard" or "extended"
    
    // Preview custom text
    var previewText by remember { mutableStateOf("Съешь ещё этих мягких французских булок, да выпей чаю! ABC 123") }
    var previewFontSize by remember { mutableStateOf(20f) }

    // Signing state
    var isSigning by remember { mutableStateOf(false) }
    var signingJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var isConsoleExpanded by remember { mutableStateOf(false) }
    val signingLogs = remember { mutableStateListOf<String>() }
    var currentStep by remember { mutableStateOf("") }
    var signedApkFile by remember { mutableStateOf<File?>(null) }
    var signingError by remember { mutableStateOf<String?>(null) }
    var fontSearchQuery by remember { mutableStateOf("") }

    // One-time font size warning dialog state
    var showFontSizeWarningDialog by remember { mutableStateOf(false) }
    var pendingSigningAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val prefs = remember { context.getSharedPreferences("fontonica_prefs", Context.MODE_PRIVATE) }

    val triggerSigningAction = {
        val executeBuild = {
            isSigning = true
            signedApkFile = null
            signingError = null
            signingLogs.clear()
            signingJob = scope.launch {
                var lastLogMessage = "Произошла неизвестная ошибка при сборке."
                try {
                    val result = withContext(Dispatchers.IO) {
                        runSigningProcess(
                            context = context,
                            fontFile = selectedFont!!.file,
                            apkUri = selectedApkUri!!,
                            isExtendedMode = (replacementMode == "extended"),
                            allFontFiles = apkDetails?.fontFiles ?: emptyList()
                        ) { log, step ->
                            scope.launch {
                                signingLogs.add(log)
                                currentStep = step
                                if (log.startsWith("[ОШИБКА]:")) {
                                    lastLogMessage = log.removePrefix("[ОШИБКА]:").trim()
                                }
                            }
                        }
                    }
                    isSigning = false
                    signingJob = null
                    if (result != null) {
                        signedApkFile = result
                        Toast.makeText(context, "Сборка завершена!", Toast.LENGTH_SHORT).show()
                    } else {
                        signingError = lastLogMessage
                        Toast.makeText(context, "Сборка завершилась с ошибкой.", Toast.LENGTH_LONG).show()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    isSigning = false
                    signingJob = null
                    currentStep = "Отменено пользователем"
                    signingLogs.add("[ОТМЕНА]: Замена шрифта была отменена пользователем.")
                    Toast.makeText(context, "Замена шрифта отменена", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val hasSeenWarning = prefs.getBoolean("has_seen_font_size_warning", false)
        if (!hasSeenWarning) {
            pendingSigningAction = {
                prefs.edit().putBoolean("has_seen_font_size_warning", true).apply()
                executeBuild()
            }
            showFontSizeWarningDialog = true
        } else {
            executeBuild()
        }
    }

    // Launcher for Font Selection
    val fontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val displayName = getFileNameFromUri(context, uri) ?: "shrift.ttf"
                val validation = validateFontFile(displayName, context, uri)
                
                if (validation == FontValidationResult.VALID) {
                    val tempFile = File(context.cacheDir, "selected_font_temp_${System.currentTimeMillis()}")
                    try {
                        copyUriToFile(context, uri, tempFile)
                        val sizeKb = tempFile.length() / 1024.0
                        selectedFont = FontFileDetails(tempFile, displayName, sizeKb)
                        fontValidationError = null
                        Toast.makeText(context, "Шрифт загружен!", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        selectedFont = null
                        fontValidationError = "Ошибка загрузки шрифта: ${e.localizedMessage}"
                    }
                } else {
                    selectedFont = null
                    if (validation == FontValidationResult.UNSUPPORTED_FONT) {
                        fontValidationError = "Хоть это и шрифт, но не тот, который поддерживается в этом приложении. Конвертируйте файл в TTF или в OTF."
                    } else {
                        fontValidationError = "Данный файл вообще не является шрифтом, выберите другой файл."
                    }
                }
            }
        }
    }

    // Launcher for Roblox APK Selection
    val apkPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedApkUri = uri
            isVerifyingApk = true
            scope.launch {
                val details = withContext(Dispatchers.IO) {
                    verifyRobloxApk(context, uri)
                }
                apkDetails = details
                isVerifyingApk = false
            }
        }
    }

    // Launcher for Saving the Signed APK
    val saveApkLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
    ) { uri: Uri? ->
        if (uri != null && signedApkFile != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                            FileInputStream(signedApkFile!!).use { inputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }
                    }
                    Toast.makeText(context, "Модифицированный APK успешно сохранен!", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Ошибка при сохранении: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Welcome and Intro Header with Custom Gradient Background
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
            ),
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.05f)
                            )
                        )
                    )
            ) {
                Row(
                    modifier = Modifier
                        .padding(20.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.FontDownload,
                            contentDescription = "Fonts logo",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Fontonica",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Box(
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(100))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "v2.7",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Выберите файл шрифта, оригинальный APK-файл Roblox, и приложение автоматически заменит встроенные шрифты на ваш собственный, подписав результат.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }

        // --- STEPPER PROGRESS TRACKER LOGIC ---
        val step1Completed = selectedFont != null
        val step2Completed = apkDetails?.isValid == true
        val step3Completed = true // Mode is always chosen (default "standard")
        val step4Completed = signedApkFile != null

        // Section 1: Font File Selection & Preview
        val successColor = if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32)
        val successBg = if (isDark) Color(0xFF112917) else Color(0xFFE8F5E9)
        val successBorder = if (isDark) Color(0xFF2E7D32).copy(alpha = 0.6f) else Color(0xFF81C784).copy(alpha = 0.5f)

        val errorColor = if (isDark) Color(0xFFE57373) else Color(0xFFC62828)
        val errorBg = if (isDark) Color(0xFF2D1616) else Color(0xFFFFEBEE)
        val errorBorder = if (isDark) Color(0xFFC62828).copy(alpha = 0.6f) else Color(0xFFF44336).copy(alpha = 0.4f)

        val successCardBg = if (isDark) successBg.copy(alpha = 0.15f) else Color(0xFFF4FBF4)

        val fontCardBorderColor = if (step1Completed) (if (isDark) successColor.copy(alpha = 0.4f) else Color(0xFFA5D6A7)) else MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (step1Completed) successCardBg else MaterialTheme.colorScheme.surface
            ),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, fontCardBorderColor)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Step Title Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(
                                color = if (step1Completed) successColor else MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(50)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (step1Completed) {
                            Icon(Icons.Default.Check, contentDescription = "Done", tint = Color.White, modifier = Modifier.size(16.dp))
                        } else {
                            Text("1", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                    Column {
                        Text(
                            text = "Выберите файл шрифта",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (step1Completed) successColor else MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = if (step1Completed) "Шрифт успешно загружен" else "Форматы .ttf или .otf до 50 МБ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                }

                if (selectedFont == null) {
                    if (fontValidationError != null) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(errorBg, RoundedCornerShape(12.dp))
                                    .border(1.dp, errorBorder, RoundedCornerShape(12.dp))
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Error,
                                        contentDescription = "Error",
                                        tint = errorColor
                                    )
                                    Text(
                                        text = "Ошибка выбора шрифта",
                                        fontWeight = FontWeight.Bold,
                                        color = errorColor,
                                        fontSize = 15.sp
                                    )
                                }
                                Text(
                                    text = fontValidationError!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = errorColor.copy(alpha = 0.9f)
                                )
                            }
                            
                            Button(
                                onClick = { fontPickerLauncher.launch("*/*") },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Выбрать другой файл")
                            }
                        }
                    } else {
                        // Premium Dropzone layout
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(130.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isDark) 0.08f else 0.04f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { fontPickerLauncher.launch("*/*") }
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(50)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CloudUpload,
                                        contentDescription = "Upload",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    "Выбрать файл шрифта",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "Поддерживаются стандартные шрифты TrueType / OpenType",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                } else {
                    val font = selectedFont!!
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Success header container
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(successBg, RoundedCornerShape(12.dp))
                                .border(1.dp, successBorder, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(successColor.copy(alpha = 0.15f), RoundedCornerShape(50)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.TextFormat,
                                        contentDescription = "Font icon",
                                        tint = successColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Column {
                                    Text(
                                        text = font.name,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isDark) successColor else MaterialTheme.colorScheme.onSurface,
                                        fontSize = 15.sp
                                    )
                                    Text(
                                        text = "Размер: %.2f KB".format(font.sizeKb),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isDark) successColor.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Button(
                                onClick = { fontPickerLauncher.launch("*/*") },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = successColor,
                                    contentColor = Color.White
                                ),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text("Сменить", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Premium Font Tester and Preview block
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isDark) 0.12f else 0.25f),
                                    RoundedCornerShape(12.dp)
                                )
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                "Интерактивный тест шрифта",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )

                            // Canvas displaying the custom font
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 80.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.08f else 0.04f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .padding(12.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                val customFontFamily = remember(font.file) {
                                    try {
                                        val typeface = android.graphics.Typeface.createFromFile(font.file)
                                        FontFamily(typeface)
                                    } catch (e: Exception) {
                                        FontFamily.Default
                                    }
                                }
                                Text(
                                    text = previewText,
                                    fontFamily = customFontFamily,
                                    fontSize = previewFontSize.sp,
                                    lineHeight = (previewFontSize * 1.4f).sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            // Text Field to type custom text
                            OutlinedTextField(
                                value = previewText,
                                onValueChange = { previewText = it },
                                placeholder = { Text("Введите свой текст для проверки шрифта...") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                trailingIcon = {
                                    if (previewText.isNotEmpty()) {
                                        IconButton(onClick = { previewText = "" }) {
                                            Icon(Icons.Default.Close, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                                        }
                                    }
                                },
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent
                                )
                            )

                            // Font size adjustment slider
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.TextFormat, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
                                Slider(
                                    value = previewFontSize,
                                    onValueChange = { previewFontSize = it },
                                    valueRange = 12f..36f,
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(
                                        thumbColor = MaterialTheme.colorScheme.primary,
                                        activeTrackColor = MaterialTheme.colorScheme.primary,
                                        inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant
                                    )
                                )
                                Box(
                                    modifier = Modifier
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "${previewFontSize.toInt()} sp",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section 2: Roblox APK file selection
        val apkCardBorderColor = if (step2Completed) (if (isDark) successColor.copy(alpha = 0.4f) else Color(0xFFA5D6A7)) else if (step1Completed) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (step2Completed) successCardBg else if (selectedFont == null) MaterialTheme.colorScheme.surface.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface
            ),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, apkCardBorderColor)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header with step indicator
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(
                                color = if (step2Completed) successColor else if (step1Completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(50)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (step2Completed) {
                            Icon(Icons.Default.Check, contentDescription = "Done", tint = Color.White, modifier = Modifier.size(16.dp))
                        } else {
                            Text("2", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                    Column {
                        Text(
                            text = "Выберите APK файл Roblox",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (step2Completed) successColor else if (step1Completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Text(
                            text = if (step2Completed) "Установщик успешно валидирован" else "Оригинальный цельный файл .apk",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (step1Completed) 0.8f else 0.5f)
                        )
                    }
                }

                if (selectedFont == null) {
                    // LOCKED STATE ILLUSTRATION
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.02f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(50)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Locked",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Шаг заблокирован",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                            Text(
                                "Сначала выберите файл шрифта на Шаге 1",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else if (selectedApkUri == null) {
                    // Upload active zone
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isDark) 0.08f else 0.04f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { apkPickerLauncher.launch("application/vnd.android.package-archive") }
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(50)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Android,
                                    contentDescription = "Upload APK",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                "Выбрать Roblox APK",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                                )
                            Text(
                                "Поддерживаются файлы .apk (не сплит-установщики)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (isVerifyingApk) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Проверка структуры и ресурсов APK...", style = MaterialTheme.typography.bodyMedium)
                            }
                        } else {
                            val details = apkDetails
                            if (details != null) {
                                val borderStrokeColor = if (details.isValid) successBorder else errorBorder
                                val containerColor = if (details.isValid) successBg else errorBg
                                val textColor = if (details.isValid) successColor else errorColor

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(containerColor, RoundedCornerShape(12.dp))
                                        .border(1.dp, borderStrokeColor, RoundedCornerShape(12.dp))
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (details.isValid) Icons.Default.CheckCircle else Icons.Default.Error,
                                            contentDescription = "Status",
                                            tint = textColor,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Text(
                                            text = if (details.isValid) "Файл Roblox валидирован" else "Ошибка валидации APK",
                                            fontWeight = FontWeight.Bold,
                                            color = textColor,
                                            fontSize = 15.sp
                                        )
                                    }

                                    Text(
                                        text = details.message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = textColor.copy(alpha = 0.9f)
                                    )

                                    if (details.fileName.isNotEmpty() && details.isValid) {
                                        // Grid/Flow metadata tags for premium display
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(
                                                    color = if (isDark) Color.Black.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.4f),
                                                    shape = RoundedCornerShape(8.dp)
                                                )
                                                .padding(10.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text("Имя файла:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = textColor.copy(alpha = 0.7f))
                                                Text(details.fileName, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = textColor)
                                            }
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text("Размер файла:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = textColor.copy(alpha = 0.7f))
                                                Text("%.2f MB".format(details.fileSizeMb), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = textColor)
                                            }
                                        }
                                    }

                                    // Fonts inside APK dropdown
                                    if (details.isValid && details.fontFiles.isNotEmpty()) {
                                        var isExpanded by remember { mutableStateOf(false) }
                                        HorizontalDivider(color = borderStrokeColor.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 4.dp))
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable { isExpanded = !isExpanded }
                                                .padding(vertical = 4.dp, horizontal = 4.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Icon(Icons.Default.Info, contentDescription = null, tint = textColor.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
                                                Text(
                                                    text = "Обнаружено шрифтов для замены: ${details.fontFiles.size}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = textColor
                                                )
                                            }
                                            Icon(
                                                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                contentDescription = "Expand",
                                                tint = textColor,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        AnimatedVisibility(visible = isExpanded) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 6.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                // Live search box for embedded fonts
                                                OutlinedTextField(
                                                    value = fontSearchQuery,
                                                    onValueChange = { fontSearchQuery = it },
                                                    placeholder = { Text("Поиск шрифта в APK...") },
                                                    singleLine = true,
                                                    modifier = Modifier.fillMaxWidth(),
                                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                                                    colors = OutlinedTextFieldDefaults.colors(
                                                        focusedBorderColor = textColor,
                                                        unfocusedBorderColor = textColor.copy(alpha = 0.5f),
                                                        focusedLabelColor = textColor,
                                                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                                                        unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                                                    )
                                                )

                                                val filteredFonts = details.fontFiles.filter {
                                                    it.contains(fontSearchQuery, ignoreCase = true)
                                                }

                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .heightIn(max = 140.dp)
                                                        .border(1.dp, borderStrokeColor.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f))
                                                        .padding(6.dp)
                                                ) {
                                                    val fontsScrollState = rememberScrollState()
                                                    Column(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .verticalScroll(fontsScrollState),
                                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                                    ) {
                                                        if (filteredFonts.isEmpty()) {
                                                            Text(
                                                                "Шрифты не найдены по запросу",
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = textColor.copy(alpha = 0.6f),
                                                                modifier = Modifier.padding(8.dp)
                                                            )
                                                        } else {
                                                            filteredFonts.forEach { fontName ->
                                                                Row(
                                                                    verticalAlignment = Alignment.CenterVertically,
                                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                                    modifier = Modifier
                                                                        .fillMaxWidth()
                                                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                                                                        .padding(horizontal = 8.dp, vertical = 5.dp)
                                                                ) {
                                                                    Icon(
                                                                        imageVector = Icons.Default.FontDownload,
                                                                        contentDescription = null,
                                                                        tint = textColor.copy(alpha = 0.7f),
                                                                        modifier = Modifier.size(12.dp)
                                                                    )
                                                                    Text(
                                                                        text = fontName,
                                                                        fontSize = 11.sp,
                                                                        fontFamily = FontFamily.Monospace,
                                                                        color = textColor
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Button(
                                    onClick = { apkPickerLauncher.launch("application/vnd.android.package-archive") },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Сменить APK")
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section 3: Выбор режима замены
        val modeCardBorderColor = if (step2Completed) (if (isDark) successColor.copy(alpha = 0.4f) else Color(0xFFA5D6A7)) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (step2Completed) successCardBg else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, modeCardBorderColor)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header with step indicator
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(
                                color = if (step2Completed) successColor else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(50)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (step2Completed) {
                            Icon(Icons.Default.Check, contentDescription = "Done", tint = Color.White, modifier = Modifier.size(16.dp))
                        } else {
                            Text("3", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                    Column {
                        Text(
                            text = "Выбор режима замены",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (step2Completed) successColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Text(
                            text = if (step2Completed) "Выбран режим замены шрифтов" else "Определяет, какие шрифты заменяются в игре",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (step2Completed) 0.8f else 0.5f)
                        )
                    }
                }

                if (!step2Completed) {
                    // LOCKED STATE ILLUSTRATION FOR STEP 3
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.02f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(50)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Locked",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Шаг заблокирован",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                            Text(
                                "Выберите APK Roblox на Шаге 2, чтобы открыть выбор режимов",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Standard Mode Card
                            val isStandard = replacementMode == "standard"
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { replacementMode = "standard" },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isStandard) MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isDark) 0.15f else 0.25f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f)
                                ),
                                border = androidx.compose.foundation.BorderStroke(
                                    width = if (isStandard) 2.dp else 1.dp,
                                    color = if (isStandard) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isStandard) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                            contentDescription = "Standard selection",
                                            tint = if (isStandard) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Text(
                                            text = "Стандартный",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = if (isStandard) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    Text(
                                        text = "Заменяет встроенные шрифты в APK. 100% стабильный классический способ.",
                                        fontSize = 11.sp,
                                        lineHeight = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            // Extended Mode Card
                            val isExtended = replacementMode == "extended"
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { replacementMode = "extended" },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isExtended) MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isDark) 0.15f else 0.25f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f)
                                ),
                                border = androidx.compose.foundation.BorderStroke(
                                    width = if (isExtended) 2.dp else 1.dp,
                                    color = if (isExtended) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isExtended) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                            contentDescription = "Extended selection",
                                            tint = if (isExtended) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Text(
                                            text = "Расширенный",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = if (isExtended) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    Text(
                                        text = "Заменяет шрифты + перенаправляет облачные шрифты Roblox на локальный шрифт.",
                                        fontSize = 11.sp,
                                        lineHeight = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section 4: Сборка и подпись
        val isButtonEnabled = selectedFont != null && selectedApkUri != null && apkDetails?.isValid == true && !isSigning
        val buildCardBorderColor = if (step4Completed) successColor.copy(alpha = 0.4f) else if (isButtonEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)

        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (step4Completed) successCardBg else if (isButtonEnabled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surface
            ),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, buildCardBorderColor)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .background(
                                color = if (step4Completed) successColor else if (isButtonEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(50)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (step4Completed) {
                            Icon(Icons.Default.Check, contentDescription = "Done", tint = Color.White, modifier = Modifier.size(16.dp))
                        } else {
                            Text("4", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                    Text(
                        text = "Сборка и подпись",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (step4Completed) successColor else if (isButtonEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }

                // Action guidance if disabled
                if (!isButtonEnabled && signedApkFile == null && signingError == null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("Остались простые шаги:", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(
                                    imageVector = if (step1Completed) Icons.Default.CheckCircle else Icons.Default.Circle,
                                    contentDescription = null,
                                    tint = if (step1Completed) successColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "Загрузить файл шрифта (TTF или OTF)",
                                    fontSize = 11.sp,
                                    color = if (step1Completed) successColor else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(
                                    imageVector = if (step2Completed) Icons.Default.CheckCircle else Icons.Default.Circle,
                                    contentDescription = null,
                                    tint = if (step2Completed) successColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "Выбрать валидный APK-файл Roblox",
                                    fontSize = 11.sp,
                                    color = if (step2Completed) successColor else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (isSigning) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isDark) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(44.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 4.dp
                            )

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "Замена шрифта...",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (currentStep.isNotEmpty()) {
                                    Text(
                                        text = currentStep,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }

                            // Notice that the process may take time
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Schedule,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "Процесс замены шрифта и переподписи может занять некоторое время. Пожалуйста, не закрывайте приложение.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp
                                    )
                                }
                            }

                            ExpandableConsole(
                                signingLogs = signingLogs,
                                isExpanded = isConsoleExpanded,
                                onToggleExpand = { isConsoleExpanded = !isConsoleExpanded },
                                isDark = isDark,
                                context = context
                            )

                            // Cancel button
                            OutlinedButton(
                                onClick = {
                                    signingJob?.cancel()
                                    signingJob = null
                                    isSigning = false
                                    currentStep = "Отменено пользователем"
                                    signingLogs.add("[ОТМЕНА]: Замена шрифта была отменена пользователем.")
                                    Toast.makeText(context, "Замена отменена", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Отмена",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Отменить замену",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                } else {
                    if (signedApkFile != null) {
                        // Success screen
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(successBg, RoundedCornerShape(12.dp))
                                .border(1.dp, successBorder, RoundedCornerShape(12.dp))
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Success",
                                tint = successColor,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = "APK УСПЕШНО СОБРАН И ПОДПИСАН!",
                                fontWeight = FontWeight.Bold,
                                color = successColor,
                                fontSize = 16.sp
                            )
                            Button(
                                onClick = { saveApkLauncher.launch("roblox_fontonica.apk") },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = successColor,
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(Icons.Default.Save, contentDescription = "Save", tint = Color.White)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Сохранить APK на устройство", color = Color.White)
                            }
                            OutlinedButton(
                                onClick = {
                                    signedApkFile = null
                                    signingError = null
                                    signingLogs.clear()
                                },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = successColor
                                ),
                                border = androidx.compose.foundation.BorderStroke(1.dp, successColor)
                            ) {
                                Text("Собрать заново")
                            }
                        }
                    } else {
                        if (signingError != null) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(errorBg, RoundedCornerShape(12.dp))
                                        .border(1.dp, errorBorder, RoundedCornerShape(12.dp))
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Error,
                                            contentDescription = "Error",
                                            tint = errorColor
                                        )
                                        Text(
                                            text = "Сборка завершилась с ошибкой",
                                            fontWeight = FontWeight.Bold,
                                            color = errorColor,
                                            fontSize = 15.sp
                                        )
                                    }
                                    Text(
                                        text = signingError!!,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = errorColor.copy(alpha = 0.9f)
                                    )
                                }

                                ExpandableConsole(
                                    signingLogs = signingLogs,
                                    isExpanded = isConsoleExpanded,
                                    onToggleExpand = { isConsoleExpanded = !isConsoleExpanded },
                                    isDark = isDark,
                                    context = context
                                )

                                Button(
                                    onClick = { triggerSigningAction() },
                                    enabled = isButtonEnabled,
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        disabledContainerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                    )
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Retry")
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Попробовать снова")
                                }
                            }
                        } else {
                            Button(
                                onClick = { triggerSigningAction() },
                                enabled = isButtonEnabled,
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    disabledContainerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                            ) {
                                Icon(imageVector = Icons.Default.AutoFixHigh, contentDescription = "Sign")
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Заменить шрифты и подписать")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFontSizeWarningDialog) {
        AlertDialog(
            onDismissRequest = {
                showFontSizeWarningDialog = false
                pendingSigningAction = null
            },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Предупреждение",
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    text = "Предупреждение о размере шрифта",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Я осведомлён (из описания репозитория на GitHub), что при выборе сторонних шрифтов существует риск того, что их файлы могут быть больше по размеру в отличие от первоначальной нормы.",
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showFontSizeWarningDialog = false
                        pendingSigningAction?.invoke()
                        pendingSigningAction = null
                    }
                ) {
                    Text("Осведомлен, продолжить")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showFontSizeWarningDialog = false
                        pendingSigningAction = null
                    }
                ) {
                    Text("Отмена")
                }
            }
        )
    }
}

// UI Helper to copy document URI into Cache folder
fun copyUriToFile(context: Context, uri: Uri, destFile: File) {
    context.contentResolver.openInputStream(uri)?.use { inputStream ->
        FileOutputStream(destFile).use { outputStream ->
            inputStream.copyTo(outputStream)
        }
    } ?: throw IOException("Could not open InputStream from Uri")
}

// UI Helper to extract display name from document Uri
fun getFileNameFromUri(context: Context, uri: Uri): String? {
    var name: String? = null
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1) {
                name = it.getString(nameIndex)
            }
        }
    }
    return name ?: uri.lastPathSegment
}

// Verification function running in IO Thread
fun verifyRobloxApk(context: Context, apkUri: Uri): RobloxApkDetails {
    val fonts = mutableListOf<String>()
    var totalSizeMb = 0.0
    val fileName = getFileNameFromUri(context, apkUri) ?: "roblox.apk"

    try {
        context.contentResolver.openAssetFileDescriptor(apkUri, "r")?.use { afd ->
            totalSizeMb = afd.length / (1024.0 * 1024.0)
        }

        val result = context.contentResolver.openInputStream(apkUri)?.use { isStream ->
            ZipInputStream(isStream).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                var hasManifest = false
                var manifestBytes: ByteArray? = null
                while (entry != null) {
                    val name = entry.name
                    if (name == "AndroidManifest.xml") {
                        hasManifest = true
                        val bos = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (zis.read(buffer).also { read = it } != -1) {
                            bos.write(buffer, 0, read)
                        }
                        manifestBytes = bos.toByteArray()
                    } else if (name.startsWith("assets/content/fonts/", ignoreCase = true) && (name.endsWith(".ttf", ignoreCase = true) || name.endsWith(".otf", ignoreCase = true))) {
                        val fileSegment = name.substringAfterLast("/")
                        if (fileSegment.isNotEmpty() &&
                            !fileSegment.endsWith("RobloxEmoji.ttf", ignoreCase = true) &&
                            !fileSegment.endsWith("TwemojiMozilla.ttf", ignoreCase = true)
                        ) {
                            fonts.add(fileSegment)
                        }
                    }
                    entry = zis.nextEntry
                }

                if (!hasManifest) {
                    RobloxApkDetails(
                        isValid = false,
                        message = "Указанный файл не является корректным Android APK (не найден файл AndroidManifest.xml).",
                        fileName = fileName,
                        fileSizeMb = totalSizeMb
                    )
                } else {
                    val manifestBytesNonNull = manifestBytes ?: byteArrayOf()
                    val manifestStrIso = manifestBytesNonNull.toString(Charsets.ISO_8859_1)
                    val manifestStrUtf8 = manifestBytesNonNull.toString(Charsets.UTF_8)
                    val manifestStrUtf16LE = manifestBytesNonNull.toString(Charsets.UTF_16LE)
                    val manifestStrUtf16BE = manifestBytesNonNull.toString(Charsets.UTF_16BE)
                    val manifestStrClean = manifestStrIso.replace("\u0000", "")

                    val isRoblox = manifestStrIso.contains("com.roblox.client", ignoreCase = true) ||
                                   manifestStrUtf8.contains("com.roblox.client", ignoreCase = true) ||
                                   manifestStrUtf16LE.contains("com.roblox.client", ignoreCase = true) ||
                                   manifestStrUtf16BE.contains("com.roblox.client", ignoreCase = true) ||
                                   manifestStrClean.contains("com.roblox.client", ignoreCase = true)

                    if (isRoblox) {
                        RobloxApkDetails(
                            isValid = true,
                            message = "Файл AndroidManifest.xml прошел проверку по имени пакета \"com.roblox.client\". Это корректный APK-файл Roblox.",
                            fontFiles = fonts,
                            fileSizeMb = totalSizeMb,
                            fileName = fileName
                        )
                    } else {
                        RobloxApkDetails(
                            isValid = false,
                            message = "Ошибка валидации: AndroidManifest.xml не содержит обязательное имя пакета \"com.roblox.client\".",
                            fileName = fileName,
                            fileSizeMb = totalSizeMb
                        )
                    }
                }
            }
        }
        return result ?: RobloxApkDetails(
            isValid = false,
            message = "Не удалось прочитать содержимое APK-файла.",
            fileName = fileName,
            fileSizeMb = totalSizeMb
        )
    } catch (e: Exception) {
        return RobloxApkDetails(
            isValid = false,
            message = "Критическая ошибка при разборе APK: ${e.localizedMessage}",
            fileName = fileName,
            fileSizeMb = totalSizeMb
        )
    }
}

// Main processing worker doing the ZIP replacements and official Google ApkSigner V1 + V2 + V3 signing
fun runSigningProcess(
    context: Context,
    fontFile: File,
    apkUri: Uri,
    isExtendedMode: Boolean,
    allFontFiles: List<String>,
    logger: (String, String) -> Unit
): File? {
    val tempInputApk = File(context.cacheDir, "fontonica_input.apk")
    val tempUnsignedApk = File(context.cacheDir, "fontonica_unsigned.apk")
    val tempOutApk = File(context.cacheDir, "fontonica_output.apk")
    
    if (tempInputApk.exists()) tempInputApk.delete()
    if (tempUnsignedApk.exists()) tempUnsignedApk.delete()
    if (tempOutApk.exists()) tempOutApk.delete()

    try {
        logger("[Fontonica] Начинается обработка...", "Инициализация...")
        
        // 1. Copy source APK from Uri to local temporary file for memory-efficient ZipFile streaming
        logger("[1/3] Чтение оригинального APK...", "Подготовка...")
        context.contentResolver.openInputStream(apkUri)?.use { input ->
            FileOutputStream(tempInputApk).use { output ->
                val buffer = ByteArray(64 * 1024)
                var readLen: Int
                while (input.read(buffer, 0, buffer.size).also { readLen = it } != -1) {
                    output.write(buffer, 0, readLen)
                }
            }
        } ?: throw IOException("Не удалось открыть исходный APK-файл.")

        // 2. Generate key pair and certificate
        logger("[2/3] Генерация релизного RSA ключа и X.509 сертификата (Fontonica v2.7)...", "Генерация релизной подписи...")
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048)
        val keyPair = keyGen.generateKeyPair()

        val issuer = X500Name("CN=Fontonica Official Release v2.7, OU=Fontonica Production, O=Fontonica Inc, C=US")
        val serial = BigInteger.valueOf(System.currentTimeMillis())
        val notBefore = Date(System.currentTimeMillis() - 1000L * 60 * 60 * 24 * 365)
        val notAfter = Date(System.currentTimeMillis() + 1000L * 60 * 60 * 24 * 365 * 10)

        val certBuilder = JcaX509v3CertificateBuilder(
            issuer,
            serial,
            notBefore,
            notAfter,
            issuer,
            keyPair.public
        )

        val bcProvider = BouncyCastleProvider()
        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(bcProvider).build(keyPair.private)
        val holder = certBuilder.build(signer)
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(holder.encoded)) as X509Certificate
        
        logger("[Fontonica] Релизный ключ подписи v2.7 сгенерирован.", "Сборка...")

        // Read font file bytes once
        val fontBytes = fontFile.readBytes()

        // 3. Process ZIP using stream buffers (prevents OutOfMemory on large native libraries)
        logger("[3/3] Замена шрифтов и модификация APK...", "Копирование и модификация файлов...")

        ZipFile(tempInputApk).use { zipFile ->
            FileOutputStream(tempUnsignedApk).use { fos ->
                BufferedOutputStream(fos).use { bos ->
                    val cos = CountingOutputStream(bos)
                    ZipOutputStream(cos).use { zos ->
                        val newFontsToCreate = mutableSetOf<String>()
                        val writtenEntries = mutableSetOf<String>()
                        val streamBuffer = ByteArray(64 * 1024)

                        val entries = zipFile.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            val name = entry.name

                            // Skip directory entries and existing signature files
                            val isSignatureFile = name.startsWith("META-INF/") && (
                                    name.endsWith(".SF") || 
                                    name.endsWith(".RSA") || 
                                    name.endsWith(".DSA") || 
                                    name.endsWith(".EC") || 
                                    name == "META-INF/MANIFEST.MF"
                            )

                            if (!entry.isDirectory && !isSignatureFile) {
                                val normalizedName = name.lowercase()
                                if (!writtenEntries.contains(normalizedName)) {
                                    writtenEntries.add(normalizedName)

                                    // Determine if this is a font to replace
                                    val isFontToReplace = name.startsWith("assets/content/fonts/", ignoreCase = true) && 
                                            (name.endsWith(".ttf", ignoreCase = true) || name.endsWith(".otf", ignoreCase = true)) &&
                                            !name.endsWith("RobloxEmoji.ttf", ignoreCase = true) && 
                                            !name.endsWith("TwemojiMozilla.ttf", ignoreCase = true)

                                    val isJsonToProcess = isExtendedMode &&
                                            name.startsWith("assets/content/fonts/", ignoreCase = true) &&
                                            name.endsWith(".json", ignoreCase = true)

                                    if (isFontToReplace) {
                                        logger("-> Заменен шрифт: $name", "Замена шрифтов...")
                                        val newEntry = ZipEntry(name)
                                        newEntry.method = ZipEntry.DEFLATED
                                        zos.putNextEntry(newEntry)
                                        zos.write(fontBytes)
                                        zos.closeEntry()
                                    } else if (isJsonToProcess) {
                                        val originalBytes = zipFile.getInputStream(entry).use { it.readBytes() }
                                        var finalBytes = originalBytes
                                        try {
                                            val originalStr = String(originalBytes, Charsets.UTF_8)
                                            val obj = org.json.JSONObject(originalStr)
                                            val faces = obj.optJSONArray("faces")
                                            var modified = false
                                            if (faces != null) {
                                                val directoryPrefix = name.substringBeforeLast("/") + "/"
                                                val familyName = obj.optString("name", "").ifEmpty {
                                                    name.substringAfterLast("/").substringBeforeLast(".")
                                                }
                                                for (i in 0 until faces.length()) {
                                                    val face = faces.optJSONObject(i) ?: continue
                                                    val assetIdObj = face.opt("assetId")
                                                    val assetId = assetIdObj?.toString() ?: ""
                                                    val isCloudAsset = assetId.startsWith("rbxassetid://") || assetId.toLongOrNull() != null
                                                    
                                                    if (isCloudAsset) {
                                                        val faceName = face.optString("name", "Regular")

                                                        // Normalize for robust family matching (handles spaces, hyphens, underscores)
                                                        val normalizedFamily = familyName.lowercase().replace(" ", "").replace("-", "").replace("_", "")
                                                        val existingExtension = allFontFiles.find { f ->
                                                            val normalizedF = f.lowercase().replace(" ", "").replace("-", "").replace("_", "")
                                                            normalizedF.startsWith(normalizedFamily) && 
                                                            (f.endsWith(".ttf", ignoreCase = true) || f.endsWith(".otf", ignoreCase = true))
                                                        }?.substringAfterLast(".") ?: fontFile.extension

                                                        val safeFaceName = faceName.lowercase().replace(" ", "")
                                                        val safeFamilyName = familyName.lowercase().replace(" ", "")
                                                        val localFontFilename = "$safeFamilyName-$safeFaceName.$existingExtension"

                                                        face.put("assetId", "rbxasset://fonts/$localFontFilename")
                                                        newFontsToCreate.add("$directoryPrefix$localFontFilename")
                                                        modified = true
                                                    } else if (assetId.startsWith("rbxasset://")) {
                                                        val filename = assetId.substringAfterLast("/")
                                                        if (filename.isNotEmpty() && (filename.endsWith(".ttf", ignoreCase = true) || filename.endsWith(".otf", ignoreCase = true))) {
                                                            newFontsToCreate.add("$directoryPrefix$filename")
                                                        }
                                                    }
                                                }
                                            }

                                            if (modified) {
                                                logger("-> Изменен JSON шрифта: $name (перенаправлено на локальные ttf/otf)", "Замена ссылок в JSON...")
                                                finalBytes = obj.toString(2).replace("\\/", "/").toByteArray(Charsets.UTF_8)
                                            }
                                        } catch (e: Exception) {
                                            try {
                                                val originalStr = String(originalBytes, Charsets.UTF_8)
                                                val targetFontFile = if (allFontFiles.isNotEmpty()) {
                                                    allFontFiles.find { it.endsWith("SourceSansPro-Regular.ttf", ignoreCase = true) }
                                                        ?: allFontFiles.find { it.endsWith("gotham-regular.ttf", ignoreCase = true) }
                                                        ?: allFontFiles.first()
                                                } else {
                                                    "SourceSansPro-Regular.ttf"
                                                }
                                                val replacedStr = originalStr.replace(Regex("rbxassetid://[0-9]+"), "rbxasset://fonts/$targetFontFile").replace("\\/", "/")
                                                if (replacedStr != originalStr) {
                                                    logger("-> Изменен JSON шрифта (regex): $name", "Замена ссылок в JSON...")
                                                }
                                                finalBytes = replacedStr.toByteArray(Charsets.UTF_8)
                                            } catch (e2: Exception) {
                                                finalBytes = originalBytes
                                            }
                                        }

                                        val newEntry = ZipEntry(name)
                                        newEntry.method = ZipEntry.DEFLATED
                                        zos.putNextEntry(newEntry)
                                        zos.write(finalBytes)
                                        zos.closeEntry()
                                    } else {
                                        // Streaming copy for all standard entries (native libs, assets, etc.)
                                        val newEntry = ZipEntry(name)

                                        var method = entry.method
                                        if (method != ZipEntry.STORED && method != ZipEntry.DEFLATED) {
                                            method = ZipEntry.DEFLATED
                                        }
                                        if (name == "resources.arsc") {
                                            method = ZipEntry.STORED
                                        }

                                        if (method == ZipEntry.STORED) {
                                            newEntry.method = ZipEntry.STORED
                                            var entrySize = entry.size
                                            var entryCrc = entry.crc
                                            if (entrySize == -1L || entryCrc == -1L) {
                                                val crc32 = CRC32()
                                                var calculatedSize = 0L
                                                zipFile.getInputStream(entry).use { isStream ->
                                                    var bytesRead: Int
                                                    while (isStream.read(streamBuffer, 0, streamBuffer.size).also { bytesRead = it } != -1) {
                                                        crc32.update(streamBuffer, 0, bytesRead)
                                                        calculatedSize += bytesRead
                                                    }
                                                }
                                                entrySize = calculatedSize
                                                entryCrc = crc32.value
                                            }
                                            newEntry.size = entrySize
                                            newEntry.compressedSize = entrySize
                                            newEntry.crc = entryCrc

                                            val position = cos.getCount()
                                            val nameBytesSize = name.toByteArray(Charsets.UTF_8).size
                                            val extra = getPaddingExtraField(position, nameBytesSize, 4)
                                            if (extra.isNotEmpty()) {
                                                newEntry.extra = extra
                                            }
                                        } else {
                                            newEntry.method = ZipEntry.DEFLATED
                                        }

                                        zos.putNextEntry(newEntry)
                                        zipFile.getInputStream(entry).use { isStream ->
                                            var bytesRead: Int
                                            while (isStream.read(streamBuffer, 0, streamBuffer.size).also { bytesRead = it } != -1) {
                                                zos.write(streamBuffer, 0, bytesRead)
                                            }
                                        }
                                        zos.closeEntry()
                                    }
                                }
                            }
                        }

                        // Write newly duplicated local font files
                        for (newFontPath in newFontsToCreate) {
                            val normalized = newFontPath.lowercase()
                            if (!writtenEntries.contains(normalized)) {
                                writtenEntries.add(normalized)
                                logger("-> Создан файл дубликата шрифта в APK: $newFontPath", "Добавление новых шрифтов...")

                                val newEntry = ZipEntry(newFontPath)
                                newEntry.method = ZipEntry.DEFLATED
                                zos.putNextEntry(newEntry)
                                zos.write(fontBytes)
                                zos.closeEntry()
                            }
                        }
                    }
                }
            }
        }

        // Clean up input APK temp file to free disk space before signing
        if (tempInputApk.exists()) tempInputApk.delete()
        System.gc()

        // 4. Perform official V1 + V2 + V3 release signing using Google's ApkSigner
        logger("[4/4] Подписание APK релизной подписью Fontonica v2.7 (схемы V1 + V2 + V3)...", "Запуск ApkSigner...")
        
        val signerConfig = com.android.apksig.ApkSigner.SignerConfig.Builder(
            "FontonicaRelease",
            keyPair.private,
            listOf(certificate)
        ).build()

        val apkSigner = com.android.apksig.ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(tempUnsignedApk)
            .setOutputApk(tempOutApk)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()

        apkSigner.sign()

        // Clean up temporary unsigned file
        if (tempUnsignedApk.exists()) tempUnsignedApk.delete()

        // Diagnostic analysis of signed APK alignment
        analyzeApkAlignment(tempOutApk, logger)

        logger("[Fontonica] Готово! APK успешно модифицирован и подписан.", "Завершено!")
        return tempOutApk
    } catch (e: Throwable) {
        logger("[ОШИБКА]: ${e.localizedMessage ?: e.toString()}", "Ошибка")
        e.printStackTrace()
        return null
    } finally {
        if (tempInputApk.exists()) tempInputApk.delete()
        if (tempUnsignedApk.exists()) tempUnsignedApk.delete()
    }
}

// Custom OutputStream that counts bytes written to the underlying stream
class CountingOutputStream(private val out: OutputStream) : OutputStream() {
    private var count: Long = 0L

    fun getCount(): Long = count

    override fun write(b: Int) {
        out.write(b)
        count++
    }

    override fun write(b: ByteArray) {
        out.write(b)
        count += b.size
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        count += len
    }

    override fun flush() {
        out.flush()
    }

    override fun close() {
        out.close()
    }
}

// Generate standard ZIP Extra Field containing padding for alignment using official Android 0xd935 tag
fun getPaddingExtraField(position: Long, filenameLength: Int, alignment: Int = 4): ByteArray {
    val headerSize = 30
    val offsetWithoutExtra = position + headerSize + filenameLength
    
    val remainder = (offsetWithoutExtra + 6) % alignment
    val paddingLen = if (remainder == 0L) 0 else (alignment - remainder).toInt()
    val E = 6 + paddingLen
    
    val extra = ByteArray(E)
    // Tag: 0xd935 (Android ZIP alignment tag, little-endian: 0x35, 0xd9)
    extra[0] = 0x35.toByte()
    extra[1] = 0xd9.toByte()
    
    // Data Length: 2 bytes of alignment + paddingLen
    val dataLength = 2 + paddingLen
    extra[2] = (dataLength and 0xff).toByte()
    extra[3] = ((dataLength shr 8) and 0xff).toByte()
    
    // Alignment value: 4 (little-endian: 0x04, 0x00)
    extra[4] = alignment.toByte()
    extra[5] = 0.toByte()
    
    return extra
}

// Diagnostic parser to check alignment of entries in signed APK safely without loading entire APK into memory
fun analyzeApkAlignment(apkFile: File, logger: (String, String) -> Unit) {
    try {
        logger("[Диагностика] Анализ выравнивания подписанного APK...", "Диагностика...")
        RandomAccessFile(apkFile, "r").use { raf ->
            val fileLength = raf.length()
            var offset = 0L
            var count = 0
            val headerBuf = ByteArray(30)

            while (offset < fileLength - 30) {
                raf.seek(offset)
                raf.readFully(headerBuf)

                // Check for Local File Header Signature: 0x04034b50 (little endian: 50 4b 03 04)
                if (headerBuf[0] == 0x50.toByte() &&
                    headerBuf[1] == 0x4b.toByte() &&
                    headerBuf[2] == 0x03.toByte() &&
                    headerBuf[3] == 0x04.toByte()) {

                    val method = (headerBuf[8].toInt() and 0xff) or ((headerBuf[9].toInt() and 0xff) shl 8)
                    val compressedSize = (headerBuf[18].toLong() and 0xff) or
                                         ((headerBuf[19].toLong() and 0xff) shl 8) or
                                         ((headerBuf[20].toLong() and 0xff) shl 16) or
                                         ((headerBuf[21].toLong() and 0xff) shl 24)

                    val filenameLen = (headerBuf[26].toInt() and 0xff) or ((headerBuf[27].toInt() and 0xff) shl 8)
                    val extraLen = (headerBuf[28].toInt() and 0xff) or ((headerBuf[29].toInt() and 0xff) shl 8)

                    if (filenameLen > 0 && filenameLen < 4096 && (offset + 30 + filenameLen) <= fileLength) {
                        val fnBytes = ByteArray(filenameLen)
                        raf.readFully(fnBytes)
                        val filename = String(fnBytes, Charsets.UTF_8)
                        val dataOffset = offset + 30 + filenameLen + extraLen

                        if (method == 0) { // STORED
                            val isAligned = (dataOffset % 4) == 0L
                            logger("-> Entry: $filename | STORED | Offset: $offset | Data Offset: $dataOffset | Aligned: $isAligned", "Диагностика")
                        } else if (filename == "resources.arsc") {
                            logger("-> Entry: $filename | DEFLATED | Offset: $offset | Data Offset: $dataOffset", "Диагностика")
                        }
                    }

                    offset += 30 + filenameLen + extraLen + compressedSize
                    count++
                } else {
                    offset += 1
                }
                if (count >= 50) break
            }
            logger("[Диагностика] Завершено. Проверено LFH записей: $count", "Диагностика...")
        }
    } catch (e: Throwable) {
        logger("[Диагностика Info]: ${e.localizedMessage ?: "Диагностика завершена"}", "Диагностика")
    }
}

@Composable
fun ExpandableConsole(
    signingLogs: List<String>,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    isDark: Boolean,
    context: Context
) {
    val consoleBg = if (isDark) {
        Color(0xFF1E1E2C)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }
    val consoleBorder = if (isDark) {
        Color(0xFF38384D)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    }
    val consoleTextColor = MaterialTheme.colorScheme.onSurface

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(consoleBg)
            .border(1.dp, consoleBorder, RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleExpand() }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Консоль процессов (${signingLogs.size})",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (signingLogs.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            try {
                                val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("Fontonica Logs", signingLogs.joinToString("\n"))
                                clipboardManager.setPrimaryClip(clip)
                                Toast.makeText(context, "Логи скопированы!", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Ошибка копирования: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Скопировать",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Свернуть" else "Развернуть",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp, top = 0.dp)
            ) {
                val scrollState = rememberScrollState()
                LaunchedEffect(signingLogs.size) {
                    scrollState.animateScrollTo(scrollState.maxValue)
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (signingLogs.isEmpty()) {
                        Text(
                            text = "Ожидание запуска процесса...",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    } else {
                        signingLogs.forEach { log ->
                            val isError = log.contains("[ОШИБКА]")
                            val isSuccess = log.contains("[Fontonica] Готово") || log.contains("успешно")
                            val color = when {
                                isError -> MaterialTheme.colorScheme.error
                                isSuccess -> if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32)
                                else -> consoleTextColor
                            }
                            Text(
                                text = log,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = color
                            )
                        }
                    }
                }
            }
        }
    }
}
