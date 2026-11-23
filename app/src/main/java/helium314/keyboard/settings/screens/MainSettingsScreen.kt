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
import com.tal.task.TaskHitboxBridge
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.latin.utils.SubtypeLocaleUtils.displayName
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.TalInter
import helium314.keyboard.latin.utils.TalJetBrainsMono
import helium314.keyboard.latin.utils.TalSpaceGrotesk
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.screens.gesturedata.END_DATE_EPOCH_MILLIS
import helium314.keyboard.settings.screens.gesturedata.TWO_WEEKS_IN_MILLIS
import kotlinx.coroutines.launch
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

// Apple WWDC critical no-bounce spring (response ≈ 0.30s -> stiffness = (2π/0.3)² ≈ 439)
private val AppleCriticalSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 439f
)
private val TalEaseOut = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1.0f)

/**
 * Apple WWDC pointer-down response: immediate 0.96 scale rendered in graphicsLayer
 * (zero recomposition, zero neighbor shift, interruptible mid-flight).
 */
private fun Modifier.applePressScale(interactionSource: MutableInteractionSource): Modifier = composed {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = AppleCriticalSpring,
        label = "applePressScale"
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
    onClickDataGathering: () -> Unit,
    onClickAdvanced: () -> Unit,
    onClickAppearance: () -> Unit,
    onClickLanguage: () -> Unit,
    onClickLayouts: () -> Unit,
    onClickDictionaries: () -> Unit,
    onClickBack: () -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val enabledSubtypes = SubtypeSettings.getEnabledSubtypes(true)

    var activeLayer by rememberSaveable { mutableStateOf(TaskSettingsLayer.ESSENTIAL) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var testInput by rememberSaveable { mutableStateOf("") }

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
                .imePadding()
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
                TalAppleSegmentedControl(
                    labels = TaskSettingsLayer.entries.map { it.label },
                    selectedIndex = activeLayer.ordinal,
                    onSelect = { idx ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        activeLayer = TaskSettingsLayer.entries[idx]
                        refreshTick++
                    }
                )
            }

            // Fluid Apple view transition: 150 ms out / 260 ms in, ease-out, zero input lock-out
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
                                                "Toque aqui para digitar com Dynamic Tap Zones e paralaxe do polegar.",
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
                                    val statusText = "Zonas ${telemetry.visualizerMode.label} · ${telemetry.sensitivity.label} · núcleo 56% · ${telemetry.rescuedTaps} resgates"
                                    Text(
                                        text = statusText,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            // 2. Apple/iPhone Dynamic Tap Zones & Ergonomics Controls
                            TalSectionCard(title = "PRECISÃO ESTILO IPHONE") {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(GridInset),
                                    verticalArrangement = Arrangement.spacedBy(GridInset)
                                ) {
                                    Text(
                                        text = "Visualizador de Dynamic Tap Zones",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    TalAppleSegmentedControl(
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

                                    Text(
                                        text = "Amplitude de adaptação das zonas",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    TalAppleSegmentedControl(
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

                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = TouchTargetMin)
                                            .semantics(mergeDescendants = true) {},
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f).padding(end = GridInset)) {
                                            Text(
                                                text = "Calibração adaptativa do polegar",
                                                style = MaterialTheme.typography.titleSmall,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = "Compensa paralaxe vertical por fileira (apenas floats dx/dy)",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Switch(
                                            checked = telemetry.adaptiveEnabled,
                                            onCheckedChange = { enabled ->
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                TaskHitboxBridge.setAdaptiveThumbEnabled(context, enabled)
                                                refreshTick++
                                            },
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                                checkedTrackColor = MaterialTheme.colorScheme.primary
                                            )
                                        )
                                    }
                                }
                            }

                            // 3. Core AOSP Settings
                            TalSectionCard(title = "TECLADO") {
                                TalSettingRow(
                                    title = stringResource(R.string.language_and_layouts_title),
                                    subtitle = enabledSubtypes.joinToString(", ") { it.displayName() },
                                    iconRes = R.drawable.ic_settings_languages,
                                    onClick = onClickLanguage
                                )
                                TalRowDivider()
                                TalSettingRow(
                                    title = stringResource(R.string.settings_screen_appearance),
                                    subtitle = "T.A.L. Dark / Light · altura 1.06x · bordas",
                                    iconRes = R.drawable.ic_settings_appearance,
                                    onClick = onClickAppearance
                                )
                                TalRowDivider()
                                TalSettingRow(
                                    title = stringResource(R.string.settings_screen_correction),
                                    subtitle = "Autocorreção nativa AOSP C++ e sugestões",
                                    iconRes = R.drawable.ic_settings_correction,
                                    onClick = onClickTextCorrection
                                )
                                TalRowDivider()
                                TalSettingRow(
                                    title = stringResource(R.string.dictionary_settings_category),
                                    subtitle = "S23 PT-BR (169k) · EN-US (129k) offline",
                                    iconRes = R.drawable.ic_dictionary,
                                    onClick = onClickDictionaries
                                )
                            }
                        }

                        TaskSettingsLayer.ADVANCED -> {
                            TalSectionCard(title = "ERGONOMIA, LAYOUT E BARRA") {
                                TalSettingRow(
                                    title = stringResource(R.string.settings_screen_preferences),
                                    subtitle = "Háptico seco Taptic (8 ms) · popup · som",
                                    iconRes = R.drawable.ic_settings_preferences,
                                    onClick = onClickPreferences
                                )
                                TalRowDivider()
                                TalSettingRow(
                                    title = stringResource(R.string.settings_screen_toolbar),
                                    subtitle = "Chaves fixadas · clipboard · barra superior",
                                    iconRes = R.drawable.ic_settings_toolbar,
                                    onClick = onClickToolbar
                                )
                                TalRowDivider()
                                TalSettingRow(
                                    title = stringResource(R.string.settings_screen_secondary_layouts),
                                    subtitle = "Símbolos · teclado numérico 3x4 · emojis",
                                    iconRes = R.drawable.ic_settings_layout,
                                    onClick = onClickLayouts
                                )
                                if (JniUtils.sHaveGestureLib) {
                                    TalRowDivider()
                                    TalSettingRow(
                                        title = stringResource(R.string.settings_screen_gesture),
                                        subtitle = "Digitação por gestos (biblioteca nativa)",
                                        iconRes = R.drawable.ic_settings_gesture,
                                        onClick = onClickGestureTyping
                                    )
                                }
                                if (JniUtils.sHaveGestureLib && System.currentTimeMillis() < END_DATE_EPOCH_MILLIS + TWO_WEEKS_IN_MILLIS) {
                                    TalRowDivider()
                                    TalSettingRow(
                                        title = stringResource(R.string.gesture_data_screen),
                                        subtitle = null,
                                        iconRes = R.drawable.ic_settings_gesture,
                                        onClick = onClickDataGathering
                                    )
                                }
                            }

                            TalSectionCard(title = "SISTEMA") {
                                TalSettingRow(
                                    title = stringResource(R.string.settings_screen_advanced),
                                    subtitle = "Parâmetros avançados do AOSP LatinIME",
                                    iconRes = R.drawable.ic_settings_advanced,
                                    onClick = onClickAdvanced
                                )
                                TalRowDivider()
                                TalSettingRow(
                                    title = stringResource(R.string.settings_screen_about),
                                    subtitle = "T.A.S.K. v3.0.0 · Totally Accurate Labs (GPL-3.0)",
                                    iconRes = R.drawable.ic_settings_about,
                                    onClick = onClickAbout
                                )
                            }
                        }

                        TaskSettingsLayer.ENGINEERING -> {
                            TalSectionCard(title = "TELEMETRIA DE TOQUE (META <= 2.00 ms)") {
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
                                    TalMetricRow("Núcleo protegido (56%)", "${telemetry.coreProtectedTaps}")
                                    TalMetricRow("Offset vertical", "${"%.1f".format(telemetry.lastOffsetYPx)} px")
                                    TalMetricRow("Palavras no contexto atual", "${telemetry.contextWordCount}")
                                    val activePrev = telemetry.prevWord.ifEmpty { "—" }
                                    val activePref = telemetry.currentPrefix.ifEmpty { "—" }
                                    TalMetricRow("Contexto ativo", "$activePrev → $activePref")
                                }
                            }

                            TalSectionCard(title = "CALIBRAÇÃO DO POLEGAR (POR FILEIRA)") {
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
                                                val prevX = telemetry.rowBiasX.copyOf()
                                                val prevY = telemetry.rowBiasY.copyOf()
                                                val prevSamples = telemetry.calibrationSamples
                                                TaskHitboxBridge.resetThumbCalibration(context)
                                                refreshTick++
                                                scope.launch {
                                                    val res = snackbarHostState.showSnackbar(
                                                        message = "Calibração do polegar restaurada para o padrão.",
                                                        actionLabel = "Desfazer",
                                                        duration = SnackbarDuration.Short
                                                    )
                                                    if (res == SnackbarResult.ActionPerformed) {
                                                        TaskHitboxBridge.restoreThumbCalibration(context, prevX, prevY, prevSamples)
                                                        refreshTick++
                                                    }
                                                }
                                            },
                                            interactionSource = resetInteraction,
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(TouchTargetMin)
                                                .applePressScale(resetInteraction),
                                            shape = InnerPillShape,
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                                        ) {
                                            Text(
                                                "Restaurar polegar",
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
                                                .applePressScale(clearInteraction),
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
                                    TalMetricRow("RAM Trie (Flat-Int)", "${telemetry.estimatedTrieRamKb} KB")
                                    TalMetricRow("Permissão INTERNET", "AUSENTE (0)")
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
            .applePressScale(interactionSource)
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
                lineHeight = 22.sp,
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
 * Apple-style interruptible spring-sliding segmented control (48 dp target, 6 dp inner inset).
 * Indicator slides smoothly via critical no-bounce spring (stiffness = 439) in graphicsLayer/offset.
 */
@Composable
private fun TalAppleSegmentedControl(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    val count = labels.size.coerceAtLeast(1)
    val animatedIndex by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = AppleCriticalSpring,
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
                            .applePressScale(interactionSource)
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
            MainSettingsScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }
    }
}
