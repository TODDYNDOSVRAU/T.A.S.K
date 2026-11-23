package com.tal.task.engine

object KeyboardLayoutSpec {

    private val ptAccents: Map<Char, List<Char>> = mapOf(
        'a' to listOf('á', 'à', 'â', 'ã', 'ä'),
        'c' to listOf('ç'),
        'e' to listOf('é', 'ê', 'è', 'ë'),
        'i' to listOf('í', 'î', 'ì'),
        'n' to listOf('ñ'),
        'o' to listOf('ó', 'ô', 'õ', 'ò', 'ö'),
        'u' to listOf('ú', 'ü', 'ù', 'û')
    )

    private val enAccents: Map<Char, List<Char>> = mapOf(
        'a' to listOf('á', 'à', 'â', 'ä', 'æ'),
        'c' to listOf('ç'),
        'e' to listOf('é', 'è', 'ê', 'ë'),
        'i' to listOf('í', 'î', 'ï'),
        'n' to listOf('ñ'),
        'o' to listOf('ó', 'ô', 'ö', 'œ'),
        'u' to listOf('ú', 'ü', 'û')
    )

    fun buildRows(
        layer: KeyboardLayer,
        language: Language,
        showNumberRow: Boolean
    ): List<List<KeySpec>> {
        val rows = mutableListOf<List<KeySpec>>()
        val upper = layer == KeyboardLayer.ALPHA_UPPER || layer == KeyboardLayer.ALPHA_CAPS
        val accentsMap = if (language == Language.PT_BR) ptAccents else enAccents

        if (showNumberRow && (layer == KeyboardLayer.ALPHA_LOWER || upper)) {
            rows.add(
                "1234567890".map { ch ->
                    KeySpec(
                        id = "num_$ch",
                        label = ch.toString(),
                        action = KeyAction.Character(ch),
                        weight = 1f
                    )
                }
            )
        }

        when (layer) {
            KeyboardLayer.ALPHA_LOWER, KeyboardLayer.ALPHA_UPPER, KeyboardLayer.ALPHA_CAPS -> {
                rows.add(alphaRow("qwertyuiop", upper, accentsMap))
                rows.add(alphaRow("asdfghjkl", upper, accentsMap))
                rows.add(
                    buildList {
                        add(
                            KeySpec(
                                id = "shift",
                                label = if (layer == KeyboardLayer.ALPHA_CAPS) "CAPS" else "⇧",
                                action = KeyAction.Shift,
                                weight = 1.5f,
                                isSpecial = true
                            )
                        )
                        addAll(alphaRow("zxcvbnm", upper, accentsMap))
                        add(
                            KeySpec(
                                id = "backspace",
                                label = "⌫",
                                action = KeyAction.Backspace,
                                weight = 1.5f,
                                isSpecial = true
                            )
                        )
                    }
                )
                rows.add(bottomRow(isSymbols = false, language = language))
            }

            KeyboardLayer.SYMBOLS -> {
                rows.add(symbolRow("1234567890"))
                rows.add(symbolRow("@#\$_&-+()/"))
                rows.add(
                    buildList {
                        add(
                            KeySpec(
                                id = "ext_sym",
                                label = "=\\<",
                                action = KeyAction.ToggleExtendedSymbols,
                                weight = 1.5f,
                                isSpecial = true
                            )
                        )
                        addAll(symbolRow("*\"':;!?"))
                        add(
                            KeySpec(
                                id = "backspace",
                                label = "⌫",
                                action = KeyAction.Backspace,
                                weight = 1.5f,
                                isSpecial = true
                            )
                        )
                    }
                )
                rows.add(bottomRow(isSymbols = true, language = language))
            }

            KeyboardLayer.SYMBOLS_EXTENDED -> {
                rows.add(symbolRow("~`|•√π÷×§Δ"))
                rows.add(symbolRow("£¢€¥^°={}\\"))
                rows.add(
                    buildList {
                        add(
                            KeySpec(
                                id = "back_sym",
                                label = "?123",
                                action = KeyAction.ToggleSymbols,
                                weight = 1.5f,
                                isSpecial = true
                            )
                        )
                        addAll(symbolRow("%©®™✓[]"))
                        add(
                            KeySpec(
                                id = "backspace",
                                label = "⌫",
                                action = KeyAction.Backspace,
                                weight = 1.5f,
                                isSpecial = true
                            )
                        )
                    }
                )
                rows.add(bottomRow(isSymbols = true, language = language))
            }
        }
        return rows
    }

    private fun alphaRow(
        chars: String,
        upper: Boolean,
        accentsMap: Map<Char, List<Char>>
    ): List<KeySpec> = chars.map { base ->
        val display = if (upper) base.uppercaseChar() else base
        val accents = accentsMap[base].orEmpty().map { if (upper) it.uppercaseChar() else it }
        KeySpec(
            id = "key_$base",
            label = display.toString(),
            action = KeyAction.Character(display),
            weight = 1f,
            accents = accents
        )
    }

    private fun symbolRow(chars: String): List<KeySpec> = chars.mapIndexed { idx, ch ->
        KeySpec(
            id = "sym_${idx}_${ch.code}",
            label = ch.toString(),
            action = KeyAction.Character(ch),
            weight = 1f
        )
    }

    private fun bottomRow(isSymbols: Boolean, language: Language): List<KeySpec> = listOf(
        KeySpec(
            id = "toggle_sym",
            label = if (isSymbols) "ABC" else "?123",
            action = KeyAction.ToggleSymbols,
            weight = 1.45f,
            isSpecial = true
        ),
        KeySpec(
            id = "emoji",
            label = "☺",
            action = KeyAction.ToggleEmoji,
            weight = 1.05f,
            isSpecial = true
        ),
        KeySpec(
            id = "comma",
            label = ",",
            action = KeyAction.Character(','),
            weight = 0.85f
        ),
        KeySpec(
            id = "space",
            label = "T.A.S.K. · ${language.label}",
            action = KeyAction.Space,
            weight = 4.15f
        ),
        KeySpec(
            id = "period",
            label = ".",
            action = KeyAction.Character('.'),
            weight = 0.85f,
            accents = listOf('!', '?', ':', ';', '-', '/')
        ),
        KeySpec(
            id = "enter",
            label = "↵",
            action = KeyAction.Enter,
            weight = 1.65f,
            isSpecial = true
        )
    )

    /**
     * Computes exact key rectangles using the T.A.L. grid rhythm:
     * horizontal margin, uniform key gaps, and centered inset for 9-key row ("asdfghjkl").
     */
    fun layoutKeys(
        rows: List<List<KeySpec>>,
        totalWidth: Float,
        totalHeight: Float,
        horizontalPadding: Float,
        verticalPadding: Float,
        keyGapX: Float,
        keyGapY: Float
    ): List<LayoutKey> {
        if (rows.isEmpty() || totalWidth <= 0f || totalHeight <= 0f) return emptyList()

        val usableWidth = (totalWidth - horizontalPadding * 2f).coerceAtLeast(1f)
        val usableHeight = (totalHeight - verticalPadding * 2f - keyGapY * (rows.size - 1)).coerceAtLeast(1f)
        val rowHeight = usableHeight / rows.size
        val result = ArrayList<LayoutKey>(48)
        var globalIndex = 0

        for (rowIdx in rows.indices) {
            val row = rows[rowIdx]
            val isHomeAlphaRow = row.size == 9 && row.all { it.action is KeyAction.Character }
            val rowInset = if (isHomeAlphaRow) usableWidth * 0.05f else 0f
            val rowWidth = (usableWidth - rowInset * 2f).coerceAtLeast(1f)
            val totalWeight = row.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(1f)
            val totalGaps = keyGapX * (row.size - 1)
            val availableKeyWidth = (rowWidth - totalGaps).coerceAtLeast(1f)

            val top = verticalPadding + rowIdx * (rowHeight + keyGapY)
            val bottom = top + rowHeight
            var cursorX = horizontalPadding + rowInset

            for (spec in row) {
                val keyW = availableKeyWidth * (spec.weight / totalWeight)
                val rect = KeyRect(
                    left = cursorX,
                    top = top,
                    right = cursorX + keyW,
                    bottom = bottom
                )
                result += LayoutKey(
                    index = globalIndex++,
                    row = rowIdx,
                    spec = spec,
                    rect = rect
                )
                cursorX += keyW + keyGapX
            }
        }
        return result
    }
}
