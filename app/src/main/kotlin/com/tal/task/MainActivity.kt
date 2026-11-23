package com.tal.task

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.tal.task.data.AospDictionaryLoader
import com.tal.task.data.LocalClipboardStore
import com.tal.task.data.TaskConfig
import com.tal.task.data.TaskPreferences
import com.tal.task.data.TaskTelemetry
import com.tal.task.data.TelemetrySnapshot
import com.tal.task.data.ThemeMode
import com.tal.task.data.UserDictionaryStore
import com.tal.task.engine.HitboxSensitivity
import com.tal.task.engine.Language
import com.tal.task.engine.TrieDictionary
import com.tal.task.engine.VisualizerMode
import com.tal.task.ime.KeyboardHostCallbacks
import com.tal.task.ime.TaskKeyboardView
import com.tal.task.ui.theme.JetBrainsMonoFamily
import com.tal.task.ui.theme.SpaceGroteskFamily
import com.tal.task.ui.theme.TalTheme
import kotlin.math.roundToInt

private enum class AppLayer(val label: String) {
    ESSENTIAL("ESSENTIAL"),
    ADVANCED("ADVANCED"),
    ENGINEERING("ENGINEERING")
}

class MainActivity : ComponentActivity() {

    private lateinit var prefs: TaskPreferences
    private lateinit var userDictStore: UserDictionaryStore
    private lateinit var dictionaries: Map<Language, TrieDictionary>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = TaskPreferences(this)
        userDictStore = UserDictionaryStore(this)
        dictionaries = AospDictionaryLoader.getOrLoad(this, userDictStore)

        setContent {
            val config by prefs.config.collectAsState()
            val telemetry by TaskTelemetry.state.collectAsState()
            TalTheme(themeMode = config.themeMode) {
                TaskCompanionApp(
                    config = config,
                    telemetry = telemetry,
                    dictionaries = dictionaries,
                    userDictStore = userDictStore,
                    onUpdateConfig = { prefs.update(it) }
                )
            }
        }
    }

    override fun onDestroy() {
        prefs.release()
        userDictStore.close()
        super.onDestroy()
    }
}

@Composable
private fun TaskCompanionApp(
    config: TaskConfig,
    telemetry: TelemetrySnapshot,
    dictionaries: Map<Language, TrieDictionary>,
    userDictStore: UserDictionaryStore,
    onUpdateConfig: ((TaskConfig) -> TaskConfig) -> Unit
) {
    var activeLayer by remember { mutableStateOf(AppLayer.ESSENTIAL) }
    val darkTheme = when (config.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "T.A.S.K.",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "Totally Accurate Smart Keyboard",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "OFFLINE · ${config.language.label}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 3 T.A.L. Product Layers switcher (48 dp touch height)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AppLayer.entries.forEach { layer ->
                        val selected = layer == activeLayer
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable { activeLayer = layer },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = layer.label,
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (activeLayer) {
                AppLayer.ESSENTIAL -> EssentialScreen(
                    config = config,
                    darkTheme = darkTheme,
                    dictionaries = dictionaries,
                    onUpdateConfig = onUpdateConfig
                )
                AppLayer.ADVANCED -> AdvancedScreen(
                    config = config,
                    onUpdateConfig = onUpdateConfig
                )
                AppLayer.ENGINEERING -> EngineeringScreen(
                    config = config,
                    telemetry = telemetry,
                    dictionaries = dictionaries,
                    userDictStore = userDictStore
                )
            }
        }
    }
}

@Composable
private fun EssentialScreen(
    config: TaskConfig,
    darkTheme: Boolean,
    dictionaries: Map<Language, TrieDictionary>,
    onUpdateConfig: ((TaskConfig) -> TaskConfig) -> Unit
) {
    val context = LocalContext.current
    var playgroundText by remember { mutableStateOf("") }
    var cursorPos by remember { mutableIntStateOf(0) }
    var lastAutoRaw by remember { mutableStateOf<String?>(null) }
    var lastAutoCommitted by remember { mutableStateOf<String?>(null) }

    val imm = remember { context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager }
    val isImeEnabled = remember(context) {
        imm?.enabledInputMethodList?.any { it.packageName == context.packageName } == true
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Setup card
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = if (isImeEnabled) "Teclado ativo no sistema" else "Ativação do teclado no Android",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text("1. Ativar IME", fontFamily = SpaceGroteskFamily, fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = { imm?.showInputMethodPicker() },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Text(
                                "2. Selecionar",
                                fontFamily = SpaceGroteskFamily,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // Live Playground Buffer
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "PLAYGROUND · PREDIÇÃO AO VIVO",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "LIMPAR",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clickable {
                                    playgroundText = ""
                                    cursorPos = 0
                                }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                    }
                    val rendered = buildString {
                        val safe = cursorPos.coerceIn(0, playgroundText.length)
                        append(playgroundText.substring(0, safe))
                        append("|")
                        append(playgroundText.substring(safe))
                    }
                    Text(
                        text = if (playgroundText.isEmpty()) "Digite abaixo para ver os hitboxes dinâmicos crescerem em tempo real..." else rendered,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (playgroundText.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }
            }
        }

        // Embedded real TaskKeyboardView (works immediately without switching system IME)
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { ctx ->
                TaskKeyboardView(ctx).apply {
                    bindDictionaries(dictionaries)
                    applyConfig(config, darkTheme)
                    updateFieldPolicy(dynamicAllowed = true, incognito = false)
                }
            },
            update = { view ->
                view.applyConfig(config, darkTheme)
                val safe = cursorPos.coerceIn(0, playgroundText.length)
                val before = playgroundText.substring(0, safe)
                val prefix = before.takeLastWhile { it.isLetter() }
                val prevWord = before.dropLast(prefix.length).trimEnd().takeLastWhile { it.isLetter() }.takeIf { it.isNotEmpty() }
                view.updateComposingContext(prefix, prevWord)

                view.callbacks = object : KeyboardHostCallbacks {
                    override fun onCommitText(text: String) {
                        val pos = cursorPos.coerceIn(0, playgroundText.length)
                        playgroundText = playgroundText.substring(0, pos) + text + playgroundText.substring(pos)
                        cursorPos = pos + text.length
                        lastAutoRaw = null
                        lastAutoCommitted = null
                    }

                    override fun onCommitWordSuggestion(word: String, replaceLength: Int) {
                        val pos = cursorPos.coerceIn(0, playgroundText.length)
                        val start = (pos - replaceLength).coerceAtLeast(0)
                        val raw = playgroundText.substring(start, pos)
                        val committed = "$word "
                        playgroundText = playgroundText.substring(0, start) + committed + playgroundText.substring(pos)
                        cursorPos = start + committed.length
                        lastAutoRaw = raw.takeIf { it.isNotEmpty() && !it.equals(word, ignoreCase = true) }
                        lastAutoCommitted = committed
                    }

                    override fun onBackspace() {
                        val pos = cursorPos.coerceIn(0, playgroundText.length)
                        val raw = lastAutoRaw
                        val committed = lastAutoCommitted
                        if (raw != null && committed != null && pos >= committed.length &&
                            playgroundText.substring(pos - committed.length, pos) == committed
                        ) {
                            playgroundText = playgroundText.substring(0, pos - committed.length) + raw + playgroundText.substring(pos)
                            cursorPos = pos - committed.length + raw.length
                            lastAutoRaw = null
                            lastAutoCommitted = null
                            return
                        }
                        if (pos > 0) {
                            playgroundText = playgroundText.substring(0, pos - 1) + playgroundText.substring(pos)
                            cursorPos = pos - 1
                        }
                    }

                    override fun onEnter() {
                        onCommitText("\n")
                    }

                    override fun onMoveCursor(delta: Int) {
                        cursorPos = (cursorPos + delta).coerceIn(0, playgroundText.length)
                    }

                    override fun onVisualizerModeChanged(mode: VisualizerMode) {
                        onUpdateConfig { it.copy(visualizerMode = mode) }
                    }

                    override fun onLanguageSwitched(language: Language) {
                        onUpdateConfig { it.copy(language = language) }
                    }
                }
            }
        )
    }
}

@Composable
private fun AdvancedScreen(
    config: TaskConfig,
    onUpdateConfig: ((TaskConfig) -> TaskConfig) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OptionGroup(
            title = "Sensibilidade do Hitbox Dinâmico (D8: núcleo protegido)",
            options = HitboxSensitivity.entries.map { it.label },
            selectedIndex = config.sensitivity.ordinal,
            onSelect = { idx -> onUpdateConfig { it.copy(sensitivity = HitboxSensitivity.entries[idx]) } }
        )

        OptionGroup(
            title = "Modo de Visualização da Predição (D5)",
            options = VisualizerMode.entries.map { it.label },
            selectedIndex = config.visualizerMode.ordinal,
            onSelect = { idx -> onUpdateConfig { it.copy(visualizerMode = VisualizerMode.entries[idx]) } }
        )

        OptionGroup(
            title = "Idioma Ativo (100% Offline)",
            options = Language.entries.map { "${it.label} (${it.code})" },
            selectedIndex = config.language.ordinal,
            onSelect = { idx -> onUpdateConfig { it.copy(language = Language.entries[idx]) } }
        )

        OptionGroup(
            title = "Tema Visual T.A.L.",
            options = ThemeMode.entries.map { it.label },
            selectedIndex = config.themeMode.ordinal,
            onSelect = { idx -> onUpdateConfig { it.copy(themeMode = ThemeMode.entries[idx]) } }
        )

        ToggleRow(
            title = "Linha numérica dedicada (1–0)",
            checked = config.showNumberRow,
            onCheckedChange = { v -> onUpdateConfig { it.copy(showNumberRow = v) } }
        )
        ToggleRow(
            title = "Autocorreção ao pressionar espaço (undo no backspace)",
            checked = config.autoCorrect,
            onCheckedChange = { v -> onUpdateConfig { it.copy(autoCorrect = v) } }
        )
        ToggleRow(
            title = "Popup de prévia ao pressionar tecla (AOSP)",
            checked = config.showKeyPreview,
            onCheckedChange = { v -> onUpdateConfig { it.copy(showKeyPreview = v) } }
        )
        ToggleRow(
            title = "Feedback háptico nas teclas",
            checked = config.hapticsEnabled,
            onCheckedChange = { v -> onUpdateConfig { it.copy(hapticsEnabled = v) } }
        )

        Spacer(Modifier.height(12.dp))
        TalFooter()
    }
}

@Composable
private fun EngineeringScreen(
    config: TaskConfig,
    telemetry: TelemetrySnapshot,
    dictionaries: Map<Language, TrieDictionary>,
    userDictStore: UserDictionaryStore
) {
    var learnedCount by remember { mutableIntStateOf(userDictStore.countLearnedWords()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Real-time latency & rescue metrics
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("TELEMETRIA DO MOTOR (META <= 2.00 ms p99)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                MetricLine("p99 total (calc + resolve)", "${"%.3f".format(telemetry.p99TotalMs)} ms")
                MetricLine("Último recálculo de hitboxes", "${"%.3f".format(telemetry.lastComputeMs)} ms")
                MetricLine("Última resolução de toque", "${"%.3f".format(telemetry.lastResolveMs)} ms")
                MetricLine("Total de toques medidos", "${telemetry.totalTaps}")
                MetricLine("Erros resgatados pelo hitbox", "${telemetry.rescuedTaps}")
                MetricLine("Toques garantidos pelo núcleo (D8)", "${telemetry.coreProtectedTaps}")
            }
        }

        // Privacy & Corpus Audit
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("AUDITORIA DE PRIVACIDADE & CORPUS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                MetricLine("Permissão INTERNET no manifest", "AUSENTE (0)")
                MetricLine("Bloqueios em senha/incógnito", "${telemetry.incognitoBlockedEvents}")
                MetricLine("Corpus PT-BR embarcado", "${dictionaries[Language.PT_BR]?.wordCount ?: 0} palavras")
                MetricLine("Corpus EN-US embarcado", "${dictionaries[Language.EN_US]?.wordCount ?: 0} palavras")
                MetricLine("Palavras aprendidas localmente", "$learnedCount")
                MetricLine("Teto rígido de expansão (D8)", "<= +40% (${config.sensitivity.label})")
            }
        }

        // Recent Touch Log
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("LOG DE RESOLUÇÃO DE TOQUES", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                if (telemetry.recentResolutions.isEmpty()) {
                    Text(
                        "Nenhum toque registrado ainda. Digite no Playground ou no teclado.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    telemetry.recentResolutions.take(8).forEach { r ->
                        val g = r.geometricChar ?: '-'
                        val c = r.resolvedChar ?: '-'
                        val pct = (r.resolvedProbability * 100f).roundToInt()
                        val tag = when {
                            r.wasRescued -> "RESCUED"
                            r.protectedByCore -> "CORE"
                            else -> "EXACT"
                        }
                        Text(
                            text = "$g -> $c | p=$pct% | $tag | ${"%.3f".format(r.latencyMs)} ms",
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 11.sp,
                            color = if (r.wasRescued) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = {
                    userDictStore.clearAll()
                    LocalClipboardStore.clearAll()
                    learnedCount = 0
                },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Text("Limpar dados locais", fontFamily = JetBrainsMonoFamily, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            OutlinedButton(
                onClick = { TaskTelemetry.reset() },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Text("Zerar telemetria", fontFamily = JetBrainsMonoFamily, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
            }
        }

        Spacer(Modifier.height(12.dp))
        TalFooter()
    }
}

@Composable
private fun OptionGroup(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                options.forEachIndexed { idx, label ->
                    val selected = idx == selectedIndex
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable { onSelect(idx) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 11.sp,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
            )
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

@Composable
private fun MetricLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontFamily = JetBrainsMonoFamily, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun TalFooter() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Developed by Totally Accurate Labs.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "© 2026 Totally Accurate Labs · MADE BY T.A.L.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
