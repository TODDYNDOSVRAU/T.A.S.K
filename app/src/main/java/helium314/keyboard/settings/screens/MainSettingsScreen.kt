// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
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

private enum class TaskSettingsLayer(val label: String) {
    ESSENTIAL("ESSENTIAL"),
    ADVANCED("ADVANCED"),
    ENGINEERING("ENGINEERING")
}

private val CardShape = RoundedCornerShape(16.dp)
private val PillShape = RoundedCornerShape(10.dp)

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
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(innerPadding)
        ) {
            // T.A.R.K.A.S.-style Editorial Header + 3 Product Layers Switcher
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
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
                            fontFamily = TalJetBrainsMono,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = PillShape,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Text(
                            text = "OFFLINE · AOSP C++",
                            fontFamily = TalJetBrainsMono,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }

                // Segmented 3-Layer Pill Bar (48 dp touch target)
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TaskSettingsLayer.entries.forEach { layer ->
                            val selected = layer == activeLayer
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surface
                                    )
                                    .clickable {
                                        activeLayer = layer
                                        refreshTick++
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = layer.label,
                                    fontFamily = TalJetBrainsMono,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Layer Content
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                when (activeLayer) {
                    TaskSettingsLayer.ESSENTIAL -> {
                        // 1. Live Typing Playground Card
                        TalSectionCard(title = "PLAYGROUND · TESTE AO VIVO") {
                            OutlinedTextField(
                                value = testInput,
                                onValueChange = {
                                    testInput = it
                                    refreshTick++
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                placeholder = {
                                    Text(
                                        "Toque aqui para testar o T.A.S.K. com hitboxes dinâmicos e offset do polegar...",
                                        fontFamily = TalInter,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                    focusedContainerColor = MaterialTheme.colorScheme.background,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.background
                                ),
                                minLines = 2,
                                maxLines = 4
                            )
                        }

                        // 2. Apple/iPhone Bayesian Ergonomics & Visualizer Quick Controls
                        TalSectionCard(title = "PRECISÃO ESTILO IPHONE & VISUALIZADOR") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "Modo do Visualizador (botão rápido na barra do teclado)",
                                    fontFamily = TalInter,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                TalSegmentedRow(
                                    labels = TaskHitboxBridge.VisualizerMode.entries.map { it.label },
                                    selectedIndex = telemetry.visualizerMode.ordinal,
                                    onSelect = { idx ->
                                        TaskHitboxBridge.setVisualizerMode(
                                            context,
                                            TaskHitboxBridge.VisualizerMode.entries[idx]
                                        )
                                        refreshTick++
                                    }
                                )

                                Text(
                                    text = "Sensibilidade Bayesiana 2D (núcleo central 56% protegido)",
                                    fontFamily = TalInter,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                TalSegmentedRow(
                                    labels = TaskHitboxBridge.Sensitivity.entries.map { it.label },
                                    selectedIndex = telemetry.sensitivity.ordinal,
                                    onSelect = { idx ->
                                        TaskHitboxBridge.setSensitivity(
                                            context,
                                            TaskHitboxBridge.Sensitivity.entries[idx]
                                        )
                                        refreshTick++
                                    }
                                )

                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                        Text(
                                            text = "Calibração adaptativa do polegar",
                                            fontFamily = TalSpaceGrotesk,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "Compensa paralaxe vertical por fileira (apenas floats dx/dy, zero texto)",
                                            fontFamily = TalInter,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = telemetry.adaptiveEnabled,
                                        onCheckedChange = { enabled ->
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

                        // 3. Core AOSP Keyboard Settings
                        TalSectionCard(title = "CONFIGURAÇÃO DO TECLADO") {
                            TalSettingRow(
                                title = stringResource(R.string.language_and_layouts_title),
                                subtitle = enabledSubtypes.joinToString(", ") { it.displayName() },
                                iconRes = R.drawable.ic_settings_languages,
                                onClick = onClickLanguage
                            )
                            TalRowDivider()
                            TalSettingRow(
                                title = stringResource(R.string.settings_screen_appearance),
                                subtitle = "Temas T.A.L. Dark / Light, altura 1.06x e bordas",
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
                                subtitle = "S23 PT-BR (169k) + EN-US (129k) offline",
                                iconRes = R.drawable.ic_dictionary,
                                onClick = onClickDictionaries
                            )
                        }
                    }

                    TaskSettingsLayer.ADVANCED -> {
                        TalSectionCard(title = "ERGONOMIA, LAYOUT E BARRA") {
                            TalSettingRow(
                                title = stringResource(R.string.settings_screen_preferences),
                                subtitle = "Háptico seco Taptic (8 ms), popup de tecla e som",
                                iconRes = R.drawable.ic_settings_preferences,
                                onClick = onClickPreferences
                            )
                            TalRowDivider()
                            TalSettingRow(
                                title = stringResource(R.string.settings_screen_toolbar),
                                subtitle = "Chaves fixadas, clipboard e barra superior",
                                iconRes = R.drawable.ic_settings_toolbar,
                                onClick = onClickToolbar
                            )
                            TalRowDivider()
                            TalSettingRow(
                                title = stringResource(R.string.settings_screen_secondary_layouts),
                                subtitle = "Símbolos, teclado numérico 3x4 e emojis",
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
                                subtitle = "Ajustes avançados do motor AOSP LatinIME",
                                iconRes = R.drawable.ic_settings_advanced,
                                onClick = onClickAdvanced
                            )
                            TalRowDivider()
                            TalSettingRow(
                                title = stringResource(R.string.settings_screen_about),
                                subtitle = "T.A.S.K. v2.1.0 · Totally Accurate Labs (GPL-3.0)",
                                iconRes = R.drawable.ic_settings_about,
                                onClick = onClickAbout
                            )
                        }
                    }

                    TaskSettingsLayer.ENGINEERING -> {
                        TalSectionCard(title = "TELEMETRIA BAYESIANA 2D (META <= 2.00 ms)") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TalMetricRow("p99 resolução de toque", "${"%.3f".format(telemetry.p99LatencyMs)} ms")
                                TalMetricRow("Última resolução", "${"%.3f".format(telemetry.lastLatencyMs)} ms")
                                TalMetricRow("Último mapeamento", "${telemetry.lastGeoChar} -> ${telemetry.lastResChar} (${telemetry.lastProbPercent}%)")
                                TalMetricRow("Total de toques analisados", "${telemetry.totalTaps}")
                                TalMetricRow("Toques resgatados (Bayes + offset)", "${telemetry.rescuedTaps}")
                                TalMetricRow("Toques garantidos pelo núcleo (56%)", "${telemetry.coreProtectedTaps}")
                                TalMetricRow("Último offset vertical aplicado", "${"%.1f".format(telemetry.lastOffsetYPx)} px")
                            }
                        }

                        TalSectionCard(title = "CALIBRAÇÃO ADAPTATIVA DO POLEGAR (POR FILEIRA)") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val rowNames = listOf("Fileira 0 (QWERTY)", "Fileira 1 (ASDF)", "Fileira 2 (ZXCV)", "Fileira 3 (Base)")
                                for (r in 0 until 4) {
                                    val bx = telemetry.rowBiasX[r] * 100f
                                    val by = telemetry.rowBiasY[r] * 100f
                                    TalMetricRow(rowNames[r], "dx=${"%+.1f".format(bx)}%  dy=${"%+.1f".format(by)}%")
                                }
                                TalMetricRow("Amostras acumuladas", "${telemetry.calibrationSamples}")
                                Spacer(Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            TaskHitboxBridge.resetThumbCalibration(context)
                                            refreshTick++
                                        },
                                        modifier = Modifier.weight(1f).height(44.dp),
                                        shape = PillShape,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                                    ) {
                                        Text("Resetar polegar", fontFamily = TalJetBrainsMono, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            TaskHitboxBridge.resetTelemetry()
                                            refreshTick++
                                        },
                                        modifier = Modifier.weight(1f).height(44.dp),
                                        shape = PillShape,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                                    ) {
                                        Text("Zerar métricas", fontFamily = TalJetBrainsMono, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }
                        }

                        TalSectionCard(title = "AUDITORIA DE PRIVACIDADE & MOTOR") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TalMetricRow("Motor nativo", "AOSP C++ libjni_latinime.so")
                                TalMetricRow("Permissão INTERNET", "AUSENTE (0)")
                                TalMetricRow("Dicionários S23", "PT-BR (169k) + EN-US (129k)")
                                TalMetricRow("Dados da calibração", "Apenas floats (dx, dy), zero texto")
                                TalMetricRow("Proteção em senha/URI/Email", "Hitbox estático + zero gravação")
                            }
                        }
                    }
                }

                // T.A.L. Fixed Signature Footer
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Developed by Totally Accurate Labs.",
                        fontFamily = TalJetBrainsMono,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "© 2026 Totally Accurate Labs · MADE BY T.A.L.",
                        fontFamily = TalJetBrainsMono,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            fontFamily = TalJetBrainsMono,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp)
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontFamily = TalSpaceGrotesk,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    fontFamily = TalInter,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        NextScreenIcon()
    }
}

@Composable
private fun TalRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 14.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
private fun TalSegmentedRow(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        labels.forEachIndexed { idx, label ->
            val selected = idx == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(PillShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable { onSelect(idx) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    fontFamily = TalJetBrainsMono,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun TalMetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontFamily = TalInter,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontFamily = TalJetBrainsMono,
            fontSize = 12.sp,
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
