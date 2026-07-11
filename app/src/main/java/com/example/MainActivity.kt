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
    val signingLogs = remember { mutableStateListOf<String>() }
    var currentStep by remember { mutableStateOf("") }
    var signedApkFile by remember { mutableStateOf<File?>(null) }
    var signingError by remember { mutableStateOf<String?>(null) }
    var fontSearchQuery by remember { mutableStateOf("") }

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
                                    text = "v2.1.1",
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

        val successCardBg = if (isDark) successBg.copy(alpha = 0.15f) else successBg.copy(alpha = 0.4f)

        val fontCardBorderColor = if (step1Completed) successColor.copy(alpha = 0.4f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
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

                            // Preset filter pills
                            val presets = listOf(
                                "✨ Стандарт" to "Съешь ещё этих мягких французских булок, да выпей чаю! ABC 123",
                                "💬 Чат Roblox" to "[Guest_8293]: OMG! This font looks absolutely incredible in-game!",
                                "🎮 Никнейм" to "⚡_RobloxMaster_⚡ [VIP] Level 100",
                                "🔢 Цифры" to "0 1 2 3 4 5 6 7 8 9 (XP +450, HP -12)"
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                            ) {
                                presets.forEach { (label, text) ->
                                    val isSelected = previewText == text
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(
                                                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                            )
                                            .clickable { previewText = text }
                                            .border(
                                                width = 1.dp,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                                shape = RoundedCornerShape(20.dp)
                                            )
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
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
        val apkCardBorderColor = if (step2Completed) successColor.copy(alpha = 0.4f) else if (step1Completed) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
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
        val modeCardBorderColor = if (step2Completed) successColor.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
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

                        // INLINE COMPARISON MATRIX: В чем разница?
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isDark) 0.15f else 0.35f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Какая разница между режимами?",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                            // Rows of the matrix
                            val matrixItems = listOf(
                                Triple(
                                    "Область замены",
                                    "Заменяет 73 встроенных файла шрифтов в APK.",
                                    "Встроенные шрифты + модифицирует облачные JSON-схемы."
                                ),
                                Triple(
                                    "Эффект в игре",
                                    "Меняет системные меню, классический игровой чат и базовые UI.",
                                    "Дополнительно перенаправляет облачные шрифты rbxassetid:// на ваш."
                                ),
                                Triple(
                                    "Стабильность",
                                    "⚡ 100% совместимость, минимальное вмешательство в логику.",
                                    "🛠 Экспериментально (иногда в плейсах с кастомным UI шрифты сбрасываются)."
                                )
                            )

                            matrixItems.forEach { (feature, standardDesc, extendedDesc) ->
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = feature,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text("•", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                                                Text(
                                                    text = standardDesc,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontSize = 11.sp,
                                                    lineHeight = 14.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text("•", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 11.sp)
                                                Text(
                                                    text = extendedDesc,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontSize = 11.sp,
                                                    lineHeight = 14.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                                if (feature != matrixItems.last().first) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 2.dp))
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
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            text = currentStep,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 14.sp
                        )

                        // Logs terminal block
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF151515), RoundedCornerShape(12.dp))
                                .border(1.dp, Color(0xFF333333), RoundedCornerShape(12.dp))
                                .padding(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(Color(0xFF00FF00), RoundedCornerShape(50))
                                    )
                                    Text(
                                        text = "КОНСОЛЬ СБОРКИ (ПРОЦЕСС...)",
                                        color = Color(0xFF9E9E9E),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(150.dp)
                            ) {
                                val scrollState = rememberScrollState()
                                LaunchedEffect(signingLogs.size) {
                                    scrollState.animateScrollTo(scrollState.maxValue)
                                }
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(scrollState),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    signingLogs.forEach { log ->
                                        Text(
                                            text = log,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = if (log.contains("[ОШИБКА]")) Color(0xFFFF5252) else Color(0xFF00FF00)
                                        )
                                    }
                                }
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

                                // Logs terminal box with Copy Button
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF151515), RoundedCornerShape(12.dp))
                                        .border(1.dp, Color(0xFF333333), RoundedCornerShape(12.dp))
                                        .padding(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "ЛОГИ СБОРКИ (ДЕТАЛИ ОШИБКИ):",
                                            color = Color(0xFF9E9E9E),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        
                                        if (signingLogs.isNotEmpty()) {
                                            Text(
                                                text = "Скопировать логи",
                                                color = MaterialTheme.colorScheme.primary,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier
                                                    .clickable {
                                                        try {
                                                            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                            val clip = android.content.ClipData.newPlainText("Fontonica Logs", signingLogs.joinToString("\n"))
                                                            clipboardManager.setPrimaryClip(clip)
                                                            Toast.makeText(context, "Логи скопированы!", Toast.LENGTH_SHORT).show()
                                                        } catch (e: Exception) {
                                                            Toast.makeText(context, "Ошибка копирования: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                    .padding(4.dp)
                                            )
                                        }
                                    }

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(150.dp)
                                    ) {
                                        val scrollState = rememberScrollState()
                                        LaunchedEffect(signingLogs.size) {
                                            scrollState.animateScrollTo(scrollState.maxValue)
                                        }
                                        Column(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .verticalScroll(scrollState),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            signingLogs.forEach { log ->
                                                Text(
                                                    text = log,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp,
                                                    color = if (log.contains("[ОШИБКА]")) Color(0xFFFF5252) else Color(0xFF00FF00)
                                                )
                                            }
                                        }
                                    }
                                }

                                Button(
                                    onClick = {
                                        isSigning = true
                                        signedApkFile = null
                                        signingError = null
                                        signingLogs.clear()
                                        scope.launch {
                                            var lastLogMessage = "Произошла неизвестная ошибка при сборке."
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
                                            if (result != null) {
                                                signedApkFile = result
                                                Toast.makeText(context, "Сборка завершена!", Toast.LENGTH_SHORT).show()
                                            } else {
                                                signingError = lastLogMessage
                                                Toast.makeText(context, "Сборка завершилась с ошибкой.", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    },
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
                                onClick = {
                                    isSigning = true
                                    signedApkFile = null
                                    signingError = null
                                    signingLogs.clear()
                                    scope.launch {
                                        var lastLogMessage = "Произошла неизвестная ошибка при сборке."
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
                                        if (result != null) {
                                            signedApkFile = result
                                            Toast.makeText(context, "Сборка завершена!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            signingError = lastLogMessage
                                            Toast.makeText(context, "Сборка завершилась с ошибкой.", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
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
    val tempUnsignedApk = File(context.cacheDir, "fontonica_unsigned.apk")
    val tempOutApk = File(context.cacheDir, "fontonica_output.apk")
    
    if (tempUnsignedApk.exists()) {
        tempUnsignedApk.delete()
    }
    if (tempOutApk.exists()) {
        tempOutApk.delete()
    }

    try {
        logger("[Fontonica] Начинается обработка...", "Инициализация...")
        
        // 1. Generate key pair and certificate
        logger("[1/3] Генерация приватного ключа и самоподписанного сертификата...", "Генерация подписи...")
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048)
        val keyPair = keyGen.generateKeyPair()

        val issuer = X500Name("CN=Fontonica, O=Fontonica, C=US")
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
        
        logger("[Fontonica] Ключ подписи сгенерирован.", "Сборка...")

        // Read font file bytes once
        val fontBytes = fontFile.readBytes()

        // 2. Open input stream and output stream for unsigned ZIP
        logger("[2/3] Чтение оригинального APK и замена шрифтов...", "Копирование и модификация файлов...")

        context.contentResolver.openInputStream(apkUri)?.use { apkInputStream ->
            ZipInputStream(apkInputStream).use { zis ->
                val fos = FileOutputStream(tempUnsignedApk)
                val bos = BufferedOutputStream(fos)
                val cos = CountingOutputStream(bos)
                ZipOutputStream(cos).use { zos ->
                    val newFontsToCreate = mutableSetOf<String>()
                    val writtenEntries = mutableSetOf<String>()

                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null) {
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

                                // Read the entry's content into memory
                                val entryBytes = if (isFontToReplace) {
                                    logger("-> Заменен шрифт: $name", "Замена шрифтов...")
                                    fontBytes
                                } else if (isJsonToProcess) {
                                    val originalBytes = zis.readBytes()
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
                                                    // Duplicate/ensure existence of local fonts referenced by the JSON file
                                                    val filename = assetId.substringAfterLast("/")
                                                    if (filename.isNotEmpty() && (filename.endsWith(".ttf", ignoreCase = true) || filename.endsWith(".otf", ignoreCase = true))) {
                                                        newFontsToCreate.add("$directoryPrefix$filename")
                                                    }
                                                }
                                            }
                                        }

                                        if (modified) {
                                            logger("-> Изменен JSON шрифта: $name (перенаправлено на локальные ttf/otf)", "Замена ссылок в JSON...")
                                            obj.toString(2).toByteArray(Charsets.UTF_8)
                                        } else {
                                            originalBytes
                                        }
                                    } catch (e: Exception) {
                                        // Fallback to simple regex replacement if JSON parsing fails
                                        try {
                                            val originalStr = String(originalBytes, Charsets.UTF_8)
                                            val targetFontFile = if (allFontFiles.isNotEmpty()) {
                                                allFontFiles.find { it.endsWith("SourceSansPro-Regular.ttf", ignoreCase = true) }
                                                    ?: allFontFiles.find { it.endsWith("gotham-regular.ttf", ignoreCase = true) }
                                                    ?: allFontFiles.first()
                                            } else {
                                                "SourceSansPro-Regular.ttf"
                                            }
                                            val replacedStr = originalStr.replace(Regex("rbxassetid://[0-9]+"), "rbxasset://fonts/$targetFontFile")
                                            if (replacedStr != originalStr) {
                                                logger("-> Изменен JSON шрифта (regex): $name", "Замена ссылок в JSON...")
                                            }
                                            replacedStr.toByteArray(Charsets.UTF_8)
                                        } catch (e2: Exception) {
                                            originalBytes
                                        }
                                    }
                                } else {
                                    zis.readBytes()
                                }

                                val newEntry = ZipEntry(name)

                                // Determine compression method
                                var method = entry.method
                                if (method != ZipEntry.STORED && method != ZipEntry.DEFLATED) {
                                    method = ZipEntry.DEFLATED
                                }
                                if (name == "resources.arsc") {
                                    method = ZipEntry.STORED
                                }

                                if (method == ZipEntry.STORED) {
                                    val crc32 = CRC32()
                                    crc32.update(entryBytes)
                                    newEntry.method = ZipEntry.STORED
                                    newEntry.size = entryBytes.size.toLong()
                                    newEntry.compressedSize = entryBytes.size.toLong()
                                    newEntry.crc = crc32.value

                                    // Align uncompressed entry to 4-byte boundary
                                    val position = cos.getCount()
                                    val nameBytesSize = name.toByteArray(Charsets.UTF_8).size
                                    val extra = getPaddingExtraField(position, nameBytesSize, 4)
                                    if (extra.isNotEmpty()) {
                                        newEntry.extra = extra
                                    }
                                } else {
                                    newEntry.method = ZipEntry.DEFLATED
                                }

                                // Write to output ZIP
                                zos.putNextEntry(newEntry)
                                zos.write(entryBytes)
                                zos.closeEntry()
                            }
                        }
                        entry = zis.nextEntry
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

        // 3. Perform official V1 + V2 + V3 signing using Google's ApkSigner
        logger("[3/3] Подписание APK (схемы подписи V1 + V2 + V3)...", "Запуск ApkSigner...")
        
        val signerConfig = com.android.apksig.ApkSigner.SignerConfig.Builder(
            "cert",
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
        if (tempUnsignedApk.exists()) {
            tempUnsignedApk.delete()
        }

        // Diagnostic analysis of signed APK alignment
        analyzeApkAlignment(tempOutApk, logger)

        logger("[Fontonica] Готово! APK успешно модифицирован и подписан.", "Завершено!")
        return tempOutApk
    } catch (e: Exception) {
        logger("[ОШИБКА]: ${e.localizedMessage}", "Ошибка")
        e.printStackTrace()
        return null
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

// Diagnostic parser to check alignment of entries in signed APK
fun analyzeApkAlignment(apkFile: File, logger: (String, String) -> Unit) {
    try {
        logger("[Диагностика] Анализ выравнивания подписанного APK...", "Диагностика...")
        val bytes = apkFile.readBytes()
        var offset = 0
        val size = bytes.size
        var count = 0
        
        while (offset < size - 30) {
            // Check for Local File Header Signature: 0x04034b50 (little endian: 50 4b 03 04)
            if (bytes[offset] == 0x50.toByte() && 
                bytes[offset + 1] == 0x4b.toByte() && 
                bytes[offset + 2] == 0x03.toByte() && 
                bytes[offset + 3] == 0x04.toByte()) {
                
                val method = (bytes[offset + 8].toInt() and 0xff) or ((bytes[offset + 9].toInt() and 0xff) shl 8)
                val compressedSize = (bytes[offset + 18].toLong() and 0xff) or 
                                     ((bytes[offset + 19].toLong() and 0xff) shl 8) or 
                                     ((bytes[offset + 20].toLong() and 0xff) shl 16) or 
                                     ((bytes[offset + 21].toLong() and 0xff) shl 24)
                                     
                val filenameLen = (bytes[offset + 26].toInt() and 0xff) or ((bytes[offset + 27].toInt() and 0xff) shl 8)
                val extraLen = (bytes[offset + 28].toInt() and 0xff) or ((bytes[offset + 29].toInt() and 0xff) shl 8)
                
                if (offset + 30 + filenameLen > size) break
                val filename = String(bytes, offset + 30, filenameLen, Charsets.UTF_8)
                val dataOffset = offset + 30 + filenameLen + extraLen
                
                if (method == 0) { // STORED
                    val isAligned = (dataOffset % 4) == 0
                    logger("-> Entry: $filename | STORED | LFH Offset: $offset | Extra Len: $extraLen | Data Offset: $dataOffset | Aligned: $isAligned", "Диагностика")
                } else if (filename == "resources.arsc") {
                    logger("-> Entry: $filename | DEFLATED (Сжатый!) | LFH Offset: $offset | Data Offset: $dataOffset", "Диагностика")
                }
                
                offset += 30 + filenameLen + extraLen + compressedSize.toInt()
                count++
            } else {
                var foundNext = false
                for (i in (offset + 1) until (size - 30)) {
                    if (bytes[i] == 0x50.toByte() && 
                        bytes[i + 1] == 0x4b.toByte() && 
                        bytes[i + 2] == 0x03.toByte() && 
                        bytes[i + 3] == 0x04.toByte()) {
                        offset = i
                        foundNext = true
                        break
                    }
                }
                if (!foundNext) break
            }
        }
        logger("[Диагностика] Всего найдено LFH записей: $count", "Диагностика...")
    } catch (e: Exception) {
        logger("[Диагностика ОШИБКА]: ${e.localizedMessage}", "Диагностика")
    }
}
