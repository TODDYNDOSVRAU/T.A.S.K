// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.tal.task.TaskHitboxBridge
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.createPrefKeyForBooleanSettings
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.latin.utils.SubtypeLocaleUtils.displayName
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.TalInter
import helium314.keyboard.latin.utils.TalJetBrainsMono
import helium314.keyboard.latin.utils.TalSpaceGrotesk
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.settings.initPreview
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class TaskSettingsLayer(val label: String) {
    ESSENTIAL("ESSENTIAL"),
    ADVANCED("ADVANCED"),
    ENGINEERING("ENGINEERING")
}

// T.A.L. 2x Grid constants (ui.md §6): 6, 12, 24, 48 dp
private val ScreenMargin = 24.dp
private val GridInset = 12.dp
private val SegmentedInset = 6.dp
private val TouchTargetMin = 48.dp
private val CardShape = RoundedCornerShape(12.dp)
private val InnerPillShape = RoundedCornerShape(6.dp)

// T.A.L. critical no-bounce spring (response ≈ 0.30s -> stiffness = (2π/0.3)² ≈ 439)
private val TaskCriticalSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 439f
)
private val TalEaseOut = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1.0f)

/**
 * Interruptible pointer-down feedback: immediate 0.96 scale rendered in graphicsLayer
 * (zero recomposition, zero neighbor shift).
 */
private fun Modifier.taskPressScale(interactionSource: MutableInteractionSource): Modifier = composed {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = TaskCriticalSpring,
        label = "taskPressScale"
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

@Composable
fun MainSettingsScreen(
    onClickAbout: () -> Unit,
    onClickTextCorrection: () -> Unit,
    onClickPreferences: () -> Unit,
    onClickToolbar: () -> Unit,
    onClickGestureTyping: () -> Unit,
    onClickAdvanced: () -> Unit,
    onClickAppearance: () -> Unit,
    onClickLanguage: () -> Unit,
    onClickLayouts: () -> Unit,
    onClickDictionaries: () -> Unit,
    onClickBack: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember(context) { context.prefs() }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val enabledSubtypes = SubtypeSettings.getEnabledSubtypes(true)

    var activeLayer by rememberSaveable { mutableStateOf(TaskSettingsLayer.ESSENTIAL) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var testInput by rememberSaveable { mutableStateOf("") }

    var gestureEnabledPref by remember(refreshTick) {
        mutableStateOf(prefs.getBoolean(Settings.PREF_GESTURE_INPUT, Defaults.PREF_GESTURE_INPUT))
    }
    var bilingualPtEnPref by remember(refreshTick) {
        mutableStateOf(prefs.getBoolean(Settings.PREF_MULTILINGUAL_PT_EN, Defaults.PREF_MULTILINGUAL_PT_EN))
    }
    var autospacePunctPref by remember(refreshTick) {
        mutableStateOf(prefs.getBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, Defaults.PREF_AUTOSPACE_AFTER_PUNCTUATION))
    }
    var smartToolbarPref by remember(refreshTick) {
        mutableStateOf(
            prefs.getBoolean(Settings.PREF_AUTO_SHOW_TOOLBAR, Defaults.PREF_AUTO_SHOW_TOOLBAR) &&
                prefs.getBoolean(Settings.PREF_AUTO_HIDE_TOOLBAR, Defaults.PREF_AUTO_HIDE_TOOLBAR)
        )
    }
    var showBottomPunctuationPref by remember(refreshTick) {
        mutableStateOf(prefs.getBoolean(Settings.PREF_SHOW_BOTTOM_PUNCTUATION, Defaults.PREF_SHOW_BOTTOM_PUNCTUATION))
    }
    var showHintsPref by remember(refreshTick) {
        mutableStateOf(prefs.getBoolean(Settings.PREF_SHOW_HINTS, Defaults.PREF_SHOW_HINTS))
    }
    var popupOnPref by remember(refreshTick) {
        mutableStateOf(prefs.getBoolean(Settings.PREF_POPUP_ON, Defaults.PREF_POPUP_ON))
    }
    var doubleSpacePeriodPref by remember(refreshTick) {
        mutableStateOf(prefs.getBoolean(Settings.PREF_KEY_USE_DOUBLE_SPACE_PERIOD, Defaults.PREF_KEY_USE_DOUBLE_SPACE_PERIOD))
    }
    var activeThemePref by remember(refreshTick) {
        mutableStateOf(prefs.getString(Settings.PREF_THEME_COLORS_NIGHT, Defaults.PREF_THEME_COLORS_NIGHT) ?: Defaults.PREF_THEME_COLORS_NIGHT)
    }
    var keyboardHeightScale by remember(refreshTick) {
        mutableStateOf(Settings.readHeightScale(prefs, false, false))
    }

    val telemetry = remember(refreshTick, testInput) {
        TaskHitboxBridge.getTelemetrySnapshot(context)
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(innerPadding)
        ) {
            // Editorial Header on 24 dp outer margin, 12 dp vertical rhythm
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScreenMargin, vertical = GridInset),
                verticalArrangement = Arrangement.spacedBy(GridInset)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "T.A.S.K.",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.semantics { heading() }
                        )
                        Text(
                            text = "Totally Accurate Smart Keyboard",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = InnerPillShape,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Text(
                            text = "OFFLINE",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = GridInset, vertical = SegmentedInset)
                        )
                    }
                }

                // Interruptible Spring-Sliding 3-Layer Segmented Control (48 dp target)
                TalSegmentedControl(
                    labels = TaskSettingsLayer.entries.map { it.label },
                    selectedIndex = activeLayer.ordinal,
                    onSelect = { idx ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        activeLayer = TaskSettingsLayer.entries[idx]
                        refreshTick++
                    }
                )
            }

            // Fluid transition: 150 ms out / 260 ms in, ease-out, zero input lock-out
            AnimatedContent(
                targetState = activeLayer,
                transitionSpec = {
                    (fadeIn(tween(260, easing = TalEaseOut)) +
                        slideInVertically(tween(260, easing = TalEaseOut)) { it / 18 }) togetherWith
                        fadeOut(tween(150, easing = TalEaseOut))
                },
                modifier = Modifier.weight(1f),
                label = "taskLayerTransition"
            ) { layer ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = ScreenMargin),
                    verticalArrangement = Arrangement.spacedBy(GridInset)
                ) {
                    when (layer) {
                        TaskSettingsLayer.ESSENTIAL -> {
                            // 1. Live Typing Playground Card
                            TalSectionCard(title = "PLAYGROUND · TESTE AO VIVO") {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(GridInset),
                                    verticalArrangement = Arrangement.spacedBy(GridInset)
                                ) {
                                    OutlinedTextField(
                                        value = testInput,
                                        onValueChange = {
                                            testInput = it
                                            refreshTick++
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                                            color = MaterialTheme.colorScheme.onSurface
                                        ),
                                        placeholder = {
                                            Text(
                                                "Digite aqui para calibrar cada tecla em tempo real.",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        },
                                        shape = CardShape,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                            focusedContainerColor = MaterialTheme.colorScheme.background,
                                            unfocusedContainerColor = MaterialTheme.colorScheme.background
                                        ),
                                        minLines = 2,
                                        maxLines = 4
                                    )
                                    val statusText = "${telemetry.adaptedKeyCount}/26 teclas adaptadas · ${telemetry.learnedCorrectionPairs} erros aprendidos · ${telemetry.rescuedTaps} resgates"
                                    Text(
                                        text = statusText,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            // 2. Gboard-aligned Core Categories
                            TalSectionCard(title = "CONFIGURAÇÕES DO TECLADO") {
                                TalSettingRow(
                                    title = "Idiomas e layouts",
                                    subtitle = enabledSubtypes.joinToString(", ") { it.displayName() },
                                    iconRes = R.drawable.ic_settings_languages,
                                    onClick = onClickLanguage
                                )
                                TalRowDivider()
                                TalSettingRow(
                                    title = "Preferências",
                                    subtitle = "Som discreto · vibração de precisão · altura ${"%.2f".format(keyboardHeightScale)}x",
                                    iconRes = R.drawable.ic_settings_preferences,
                                    onClick = onClickPreferences
                                )
                                TalRowDivider()
                                TalSettingRow(
                                    title = "Aparência",
                                    subtitle = "Temas OLED, Esmeralda, Claro e bordas das teclas",
                                    iconRes = R.drawable.ic_settings_appearance,
                                    onClick = onClickAppearance
                                )
                                TalRowDivider()
                                TalSettingRow(
                                    title = "Correção de texto",
                                    subtitle = "Autocorreção adaptativa, sugestões e escudo de reversão",
                                    iconRes = R.drawable.ic_settings_correction,
                                    onClick = onClickTextCorrection
                                )
                                TalRowDivider()
                                TalSettingRow(
                                    title = "Digitação por deslize",
                                    subtitle = "Digitação por deslize offline (T.A.S.K. Glide)",
                                    iconRes = R.drawable.ic_settings_gesture,
                                    onClick = onClickGestureTyping
                                )
                                TalRowDivider()
                                TalSettingRow(
                                    title = "Barra de ferramentas",
                                    subtitle = "Microfone fixado · Clipboard · Editor DPAD · Flutuante",
                                    iconRes = R.drawable.ic_settings_toolbar,
                                    onClick = onClickToolbar
                                )
                                TalRowDivider()
                                TalSettingRow(
                                    title = "Dicionário",
                                    subtitle = "PT-BR (169k) · EN-US (129k) offline e dicionário pessoal",
                                    iconRes = R.drawable.ic_dictionary,
                                    onClick = onClickDictionaries
                                )
                            }

                            // 3. Per-Key Self-Learning & Anti-Error Controls
                            TalSectionCard(title = "MOTOR ANTI-ERRO ADAPTATIVO (OFFLINE)") {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(GridInset),
                                    verticalArrangement = Arrangement.spacedBy(GridInset)
                                ) {
                                    Text(
                                        text = "Amplitude de adaptação das zonas por tecla",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    TalSegmentedControl(
                                        labels = TaskHitboxBridge.Sensitivity.entries.map { it.label },
                                        selectedIndex = telemetry.sensitivity.ordinal,
                                        onSelect = { idx ->
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            TaskHitboxBridge.setSensitivity(
                                                context,
                                                TaskHitboxBridge.Sensitivity.entries[idx]
                                            )
                                            refreshTick++
                                        }
                                    )
                                }
                                TalRowDivider()
                                TalSwitchRow(
                                    title = "Aprendizado direcional por tecla",
                                    subtitle = "Expande a área de cada letra na direção onde você costuma errar ou clicar",
                                    checked = telemetry.adaptiveEnabled,
                                    onCheckedChange = { enabled ->
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        TaskHitboxBridge.setAdaptiveThumbEnabled(context, enabled)
                                        refreshTick++
                                    }
                                )
                                TalRowDivider()
                                TalSwitchRow(
                                    title = "Digitação por deslize (Glide)",
                                    subtitle = "Deslize entre letras 100% offline com contexto PT-BR/EN-US",
                                    checked = gestureEnabledPref,
                                    onCheckedChange = { enabled ->
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        gestureEnabledPref = enabled
                                        prefs.edit { putBoolean(Settings.PREF_GESTURE_INPUT, enabled) }
                                        refreshTick++
                                    }
                                )
                                TalRowDivider()
                                TalSwitchRow(
                                    title = "Multilíngue simultâneo (PT-BR + EN-US)",
                                    subtitle = "Prevê, corrige e desliza nos dois idiomas sem trocar teclado",
                                    checked = bilingualPtEnPref,
                                    onCheckedChange = { enabled ->
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        bilingualPtEnPref = enabled
                                        prefs.edit { putBoolean(Settings.PREF_MULTILINGUAL_PT_EN, enabled) }
                                        refreshTick++
                                    }
                                )
                            }
                        }

                        TaskSettingsLayer.ADVANCED -> {
                            // 1. Gboard-inspired Quick Visual Theme Selector
                            TalSectionCard(title = "TEMAS & APARÊNCIA RÁPIDA") {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(GridInset),
                                    verticalArrangement = Arrangement.spacedBy(GridInset)
                                ) {
                                    val themeChoices = remember {
                                        listOf(
                                            Triple("Escuro", KeyboardTheme.THEME_TAL_DARK, Color(0xFF0A4E52)),
                                            Triple("Esmeralda", KeyboardTheme.THEME_TASK_EMERALD, Color(0xFF084D3D)),
                                            Triple("Claro", KeyboardTheme.THEME_TAL_LIGHT, Color(0xFFD4EAEB)),
                                            Triple("Dinâmico", KeyboardTheme.THEME_DYNAMIC, Color(0xFF7EE3EA))
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(SegmentedInset)
                                    ) {
                                        for ((label, themeKey, swatchColor) in themeChoices) {
                                            val selected = activeThemePref == themeKey
                                            val chipInteraction = remember { MutableInteractionSource() }
                                            Surface(
                                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                                shape = InnerPillShape,
                                                border = BorderStroke(
                                                    width = if (selected) 2.dp else 1.dp,
                                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                                ),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .heightIn(min = TouchTargetMin)
                                                    .taskPressScale(chipInteraction)
                                                    .clickable(
                                                        interactionSource = chipInteraction,
                                                        indication = null
                                                    ) {
                                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                        activeThemePref = themeKey
                                                        prefs.edit {
                                                            putString(Settings.PREF_THEME_COLORS, themeKey)
                                                            putString(Settings.PREF_THEME_COLORS_NIGHT, themeKey)
                                                        }
                                                        KeyboardSwitcher.getInstance().setThemeNeedsReload()
                                                        refreshTick++
                                                    }
                                            ) {
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = SegmentedInset, horizontal = SegmentedInset),
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.spacedBy(SegmentedInset)
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(18.dp)
                                                            .clip(CircleShape)
                                                            .background(swatchColor)
                                                    )
                                                    Text(
                                                        text = label,
                                                        fontFamily = TalInter,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                TalRowDivider()
                                TalSettingRow(
                                    title = "Cores e papel de parede",
                                    subtitle = "Estilos completos, imagem de fundo e espaçamento entre teclas",
                                    iconRes = R.drawable.ic_settings_appearance,
                                    onClick = onClickAppearance
                                )
                            }

                            // 2. Layout, Teclas & Proteções de Digitação
                            TalSectionCard(title = "LAYOUT & TECLAS") {
                                TalSwitchRow(
                                    title = "Mostrar dicas de números e símbolos nas teclas",
                                    subtitle = "Exibe números 1–0 e símbolos no canto superior direito das letras",
                                    checked = showHintsPref,
                                    onCheckedChange = { enabled ->
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        showHintsPref = enabled
                                        prefs.edit { putBoolean(Settings.PREF_SHOW_HINTS, enabled) }
                                        KeyboardSwitcher.getInstance().setThemeNeedsReload()
                                        refreshTick++
                                    }
                                )
                                TalRowDivider()
                                TalSwitchRow(
                                    title = "Mostrar vírgula e ponto na fileira inferior",
                                    subtitle = "Exibe ',' e '.' dedicados ao lado da barra de espaço (com proteção de borda)",
                                    checked = showBottomPunctuationPref,
                                    onCheckedChange = { enabled ->
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        showBottomPunctuationPref = enabled
                                        prefs.edit { putBoolean(Settings.PREF_SHOW_BOTTOM_PUNCTUATION, enabled) }
                                        KeyboardSwitcher.getInstance().setThemeNeedsReload()
                                        refreshTick++
                                    }
                                )
                                TalRowDivider()
                                TalSwitchRow(
                                    title = "Ampliação da tecla ao tocar (Popup)",
                                    subtitle = "Mostra extensão elevada com mola analítica acima do dedo",
                                    checked = popupOnPref,
                                    onCheckedChange = { enabled ->
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        popupOnPref = enabled
                                        prefs.edit { putBoolean(Settings.PREF_POPUP_ON, enabled) }
                                        KeyboardSwitcher.getInstance().setThemeNeedsReload()
                                        refreshTick++
                                    }
                                )
                                TalRowDivider()
                                TalSwitchRow(
                                    title = "Espaço automático após pontuação",
                                    subtitle = "Insere espaço após vírgula, ponto e interrogação",
                                    checked = autospacePunctPref,
                                    onCheckedChange = { enabled ->
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        autospacePunctPref = enabled
                                        prefs.edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, enabled) }
                                        refreshTick++
                                    }
                                )
                                TalRowDivider()
                                TalSwitchRow(
                                    title = "Ponto com duplo espaço",
                                    subtitle = "Converte espaço duplo em ponto (desligado por padrão para evitar pontos acidentais)",
                                    checked = doubleSpacePeriodPref,
                                    onCheckedChange = { enabled ->
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        doubleSpacePeriodPref = enabled
                                        prefs.edit { putBoolean(Settings.PREF_KEY_USE_DOUBLE_SPACE_PERIOD, enabled) }
                                        refreshTick++
                                    }
                                )
                                TalRowDivider()
                                TalSwitchRow(
                                    title = "Barra inteligente de ferramentas",
                                    subtitle = "Exibe Clipboard, Editor DPAD, Flutuante e Emojis quando ocioso",
                                    checked = smartToolbarPref,
                                    onCheckedChange = { enabled ->
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        smartToolbarPref = enabled
                                        prefs.edit {
                                            putBoolean(Settings.PREF_AUTO_SHOW_TOOLBAR, enabled)
                                            putBoolean(Settings.PREF_AUTO_HIDE_TOOLBAR, enabled)
                                        }
                                        refreshTick++
                                    }
                                )
                                TalRowDivider()
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(GridInset),
                                    verticalArrangement = Arrangement.spacedBy(GridInset)
                                ) {
                                    Text(
                                        text = "Altura física do teclado",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    val heightValues = remember { listOf(0.94f, 1.00f, 1.06f, 1.12f) }
                                    val heightLabels = remember { listOf("0.94x", "1.00x", "1.06x", "1.12x") }
                                    val selectedHeightIdx = remember(keyboardHeightScale) {
                                        heightValues.indices.minByOrNull { abs(heightValues[it] - keyboardHeightScale) } ?: 2
                                    }
                                    TalSegmentedControl(
                                        labels = heightLabels,
                                        selectedIndex = selectedHeightIdx,
                                        onSelect = { idx ->
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            val target = heightValues[idx]
                                            keyboardHeightScale = target
                                            prefs.edit {
                                                for (i in 0 until 4) {
                                                    putFloat(
                                                        createPrefKeyForBooleanSettings(Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX, i, 2),
                                                        target
                                                    )
                                                }
                                            }
                                            KeyboardSwitcher.getInstance().setThemeNeedsReload()
                                            refreshTick++
                                        }
                                    )
                                }
                            }

                            TalSectionCard(title = "SISTEMA") {
                                TalSettingRow(
                                    title = stringResource(R.string.settings_screen_secondary_layouts),
                                    subtitle = "Símbolos · teclado numérico 3x4 · emojis",
                                    iconRes = R.drawable.ic_settings_layout,
                                    onClick = onClickLayouts
                                )
                                TalRowDivider()
                                TalSettingRow(
                                    title = stringResource(R.string.settings_screen_advanced),
                                    subtitle = "Parâmetros avançados do AOSP LatinIME",
                                    iconRes = R.drawable.ic_settings_advanced,
                                    onClick = onClickAdvanced
                                )
                                TalRowDivider()
                                TalSettingRow(
                                    title = stringResource(R.string.settings_screen_about),
                                    subtitle = "T.A.S.K. v3.3.0 · Totally Accurate Labs (GPL-3.0)",
                                    iconRes = R.drawable.ic_settings_about,
                                    onClick = onClickAbout
                                )
                            }
                        }

                        TaskSettingsLayer.ENGINEERING -> {
                            TalSectionCard(title = "VISUALIZADOR DE ZONAS 2D (DEBUG)") {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(GridInset),
                                    verticalArrangement = Arrangement.spacedBy(GridInset)
                                ) {
                                    Text(
                                        text = "Modo do overlay sobre o teclado (padrão OFF = invisível)",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    TalSegmentedControl(
                                        labels = TaskHitboxBridge.VisualizerMode.entries.map { it.label },
                                        selectedIndex = telemetry.visualizerMode.ordinal,
                                        onSelect = { idx ->
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            TaskHitboxBridge.setVisualizerMode(
                                                context,
                                                TaskHitboxBridge.VisualizerMode.entries[idx]
                                            )
                                            refreshTick++
                                        }
                                    )
                                }
                            }

                            TalSectionCard(title = "APRENDIZADO POR TECLA & TELEMETRIA (META <= 2.00 ms)") {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(GridInset),
                                    verticalArrangement = Arrangement.spacedBy(GridInset)
                                ) {
                                    TalMetricRow("p99 resolução", "${"%.3f".format(telemetry.p99LatencyMs)} ms")
                                    TalMetricRow("Última resolução", "${"%.3f".format(telemetry.lastLatencyMs)} ms")
                                    TalMetricRow("Último mapeamento", "${telemetry.lastGeoChar} -> ${telemetry.lastResChar} (${telemetry.lastProbPercent}%)")
                                    TalMetricRow("Total de toques", "${telemetry.totalTaps}")
                                    TalMetricRow("Toques resgatados", "${telemetry.rescuedTaps}")
                                    TalMetricRow("Núcleo protegido (52%)", "${telemetry.coreProtectedTaps}")
                                    TalMetricRow("Teclas adaptadas (26)", "${telemetry.adaptedKeyCount}/26")
                                    TalMetricRow("Erros -> correções aprendidos", "${telemetry.learnedCorrectionPairs}")
                                    TalMetricRow("Escudos anti-autocorreção", "${telemetry.blockedAutocorrectWords}")
                                    TalMetricRow("Mapa direcional ativo", telemetry.topAdaptedSummary)
                                    TalMetricRow("Offset vertical", "${"%.1f".format(telemetry.lastOffsetYPx)} px")
                                    TalMetricRow("Palavras no contexto atual", "${telemetry.contextWordCount}")
                                    val activePrev = telemetry.prevWord.ifEmpty { "—" }
                                    val activePref = telemetry.currentPrefix.ifEmpty { "—" }
                                    TalMetricRow("Contexto ativo", "$activePrev → $activePref")
                                }
                            }

                            TalSectionCard(title = "CALIBRAÇÃO POR FILEIRA & CONTROLE") {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(GridInset),
                                    verticalArrangement = Arrangement.spacedBy(GridInset)
                                ) {
                                    val rowNames = listOf("Fileira 0 (QWERTY)", "Fileira 1 (ASDF)", "Fileira 2 (ZXCV)", "Fileira 3 (Base)")
                                    for (r in 0 until 4) {
                                        val bx = telemetry.rowBiasX[r] * 100f
                                        val by = telemetry.rowBiasY[r] * 100f
                                        TalMetricRow(rowNames[r], "dx=${"%+.1f".format(bx)}%  dy=${"%+.1f".format(by)}%")
                                    }
                                    TalMetricRow("Amostras acumuladas", "${telemetry.calibrationSamples}")
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(GridInset)
                                    ) {
                                        val resetInteraction = remember { MutableInteractionSource() }
                                        OutlinedButton(
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                val snapshot = TaskHitboxBridge.captureCalibrationSnapshot()
                                                TaskHitboxBridge.resetThumbCalibration(context)
                                                refreshTick++
                                                scope.launch {
                                                    val res = snackbarHostState.showSnackbar(
                                                        message = "Aprendizado direcional e por fileira restaurado.",
                                                        actionLabel = "Desfazer",
                                                        duration = SnackbarDuration.Short
                                                    )
                                                    if (res == SnackbarResult.ActionPerformed) {
                                                        TaskHitboxBridge.restoreCalibrationSnapshot(context, snapshot)
                                                        refreshTick++
                                                    }
                                                }
                                            },
                                            interactionSource = resetInteraction,
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(TouchTargetMin)
                                                .taskPressScale(resetInteraction),
                                            shape = InnerPillShape,
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                                        ) {
                                            Text(
                                                "Restaurar aprendizado",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }

                                        val clearInteraction = remember { MutableInteractionSource() }
                                        OutlinedButton(
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                TaskHitboxBridge.resetTelemetry()
                                                refreshTick++
                                            },
                                            interactionSource = clearInteraction,
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(TouchTargetMin)
                                                .taskPressScale(clearInteraction),
                                            shape = InnerPillShape,
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                                        ) {
                                            Text(
                                                "Zerar métricas",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }

                            TalSectionCard(title = "AUDITORIA DE PRIVACIDADE & MOTOR") {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(GridInset),
                                    verticalArrangement = Arrangement.spacedBy(GridInset)
                                ) {
                                    TalMetricRow("Motor nativo", "AOSP C++ libjni_latinime.so")
                                    TalMetricRow("Motor Glide", "T.A.S.K. SHARK² offline")
                                    TalMetricRow("Último deslize", "${telemetry.lastGlideWord} (${"%.2f".format(telemetry.lastGlideLatencyMs)} ms · ${telemetry.totalGlides}x)")
                                    TalMetricRow("Idiomas simultâneos", if (telemetry.bilingualPtEn) "PT-BR + EN-US" else "Idioma único")
                                    TalMetricRow("Busca de emojis", "PT-BR + EN-US offline")
                                    TalMetricRow("RAM Trie (Flat-Int)", "${telemetry.estimatedTrieRamKb} KB")
                                    TalMetricRow("Permissão INTERNET", "AUSENTE (0)")
                                    TalMetricRow("Permissão READ_CONTACTS", "AUSENTE (0)")
                                    TalMetricRow("Lexicon Glide", "Packed ByteArray (0 String[])")
                                    TalMetricRow("Dicionários S23", "PT-BR 169k · EN-US 129k")
                                    TalMetricRow("Armazenamento local", "Apenas floats dx/dy")
                                    TalMetricRow("Campos sensíveis", "Estático · zero gravação")
                                }
                            }
                        }
                    }

                    // Fixed T.A.L. Signature Footer
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = GridInset),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(SegmentedInset)
                    ) {
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
            }
        }
    }
}

@Composable
private fun TalSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(SegmentedInset)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() }
        )
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = CardShape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(modifier = Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
private fun TalSettingRow(
    title: String,
    subtitle: String?,
    iconRes: Int,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTargetMin)
            .taskPressScale(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = GridInset, vertical = GridInset)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 24 dp vector icon centered with 0 px offset inside 48 dp target (ui.md §6)
        Box(
            modifier = Modifier
                .size(TouchTargetMin)
                .clip(InnerPillShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(Modifier.width(GridInset))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontFamily = TalSpaceGrotesk,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                lineHeight = 24.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(GridInset))
        NextScreenIcon()
    }
}

@Composable
private fun TalRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = GridInset),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

/**
 * T.A.L. interruptible spring-sliding segmented control (48 dp target, 6 dp inner inset).
 * Indicator slides smoothly via critical no-bounce spring (stiffness = 439) in graphicsLayer/offset.
 */
@Composable
private fun TalSegmentedControl(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    val count = labels.size.coerceAtLeast(1)
    val animatedIndex by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = TaskCriticalSpring,
        label = "segmentedIndicator"
    )

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = CardShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(TouchTargetMin)
                .padding(SegmentedInset)
        ) {
            val segmentWidthPx = constraints.maxWidth.toFloat() / count
            val segmentWidthDp = maxWidth / count

            // Sliding physical pill indicator
            Box(
                modifier = Modifier
                    .offset { IntOffset((animatedIndex * segmentWidthPx).roundToInt(), 0) }
                    .width(segmentWidthDp)
                    .fillMaxHeight()
                    .clip(InnerPillShape)
                    .background(MaterialTheme.colorScheme.primary)
            )

            Row(modifier = Modifier.fillMaxSize()) {
                labels.forEachIndexed { idx, label ->
                    val selected = idx == selectedIndex
                    val interactionSource = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .taskPressScale(interactionSource)
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null
                            ) { onSelect(idx) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            fontFamily = TalJetBrainsMono,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TalSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTargetMin)
            .padding(horizontal = GridInset, vertical = GridInset)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = GridInset)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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

@Composable
private fun TalMetricRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Preview
@Composable
private fun PreviewScreen() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            MainSettingsScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }
    }
}
