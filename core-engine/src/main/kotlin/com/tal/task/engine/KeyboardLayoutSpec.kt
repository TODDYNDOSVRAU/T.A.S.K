package com.tal.task.engine

/**
 * AOSP LatinIME-derived keyboard layout specifications adapted to the T.A.L. 2x Grid.
 * Includes top-row number hints (1..0), full PT-BR/EN-US moreKeys accents,
 * dynamic EnterAction glyph, and AOSP-style 3x4 NUMBER_PAD.
 */
object KeyboardLayoutSpec {

    private val topRowNumberHints: Map<Char, Char> = mapOf(
        'q' to '1', 'w' to '2', 'e' to '3', 'r' to '4', 't' to '5',
        'y' to '6', 'u' to '7', 'i' to '8', 'o' to '9', 'p' to '0'
    )

    private val ptAccents: Map<Char, List<Char>> = mapOf(
        'a' to listOf('á', 'à', 'â', 'ã', 'ä', 'ª'),
        'c' to listOf('ç', 'ć', 'č'),
        'e' to listOf('é', 'ê', 'è', 'ë'),
        'i' to listOf('í', 'î', 'ì', 'ï'),
        'n' to listOf('ñ'),
        'o' to listOf('ó', 'ô', 'õ', 'ò', 'ö', 'º'),
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
        showNumberRow: Boolean,
        enterAction: EnterAction = EnterAction.NEWLINE
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
                rows.add(alphaRow("qwertyuiop", upper, accentsMap, includeNumberHints = !showNumberRow))
                rows.add(alphaRow("asdfghjkl", upper, accentsMap, includeNumberHints = false))
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
                        addAll(alphaRow("zxcvbnm", upper, accentsMap, includeNumberHints = false))
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
                rows.add(bottomRow(isSymbols = false, language = language, enterAction = enterAction))
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
                rows.add(bottomRow(isSymbols = true, language = language, enterAction = enterAction))
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
                rows.add(bottomRow(isSymbols = true, language = language, enterAction = enterAction))
            }

            KeyboardLayer.NUMBER_PAD -> {
                rows.add(
                    listOf(
                        numKey('1'), numKey('2'), numKey('3'),
                        KeySpec("np_minus", "-", KeyAction.Character('-'), weight = 1f, isSpecial = true)
                    )
                )
                rows.add(
                    listOf(
                        numKey('4'), numKey('5'), numKey('6'),
                        KeySpec("np_space", "␣", KeyAction.Space, weight = 1f, isSpecial = true)
                    )
                )
                rows.add(
                    listOf(
                        numKey('7'), numKey('8'), numKey('9'),
                        KeySpec("np_bs", "⌫", KeyAction.Backspace, weight = 1f, isSpecial = true)
                    )
                )
                rows.add(
                    listOf(
                        KeySpec("np_star", "*", KeyAction.Character('*'), weight = 1f, accents = listOf('#', '+', '(', ')'), isSpecial = true),
                        numKey('0', accents = listOf('+')),
                        KeySpec("np_dot", ".", KeyAction.Character('.'), weight = 1f, accents = listOf(',', '-'), isSpecial = true),
                        KeySpec("enter", enterAction.glyph, KeyAction.Enter, weight = 1f, isSpecial = true)
                    )
                )
            }
        }
        return rows
    }

    private fun numKey(ch: Char, accents: List<Char> = emptyList()): KeySpec = KeySpec(
        id = "np_$ch",
        label = ch.toString(),
        action = KeyAction.Character(ch),
        weight = 1f,
        accents = accents
    )

    private fun alphaRow(
        chars: String,
        upper: Boolean,
        accentsMap: Map<Char, List<Char>>,
        includeNumberHints: Boolean
    ): List<KeySpec> = chars.map { base ->
        val display = if (upper) base.uppercaseChar() else base
        val hintChar = if (includeNumberHints) topRowNumberHints[base] else null
        val accents = buildList {
            if (hintChar != null) add(hintChar)
            accentsMap[base].orEmpty().forEach { add(if (upper) it.uppercaseChar() else it) }
        }
        KeySpec(
            id = "key_$base",
            label = display.toString(),
            action = KeyAction.Character(display),
            weight = 1f,
            accents = accents,
            hintLabel = hintChar?.toString()
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

    private fun bottomRow(
        isSymbols: Boolean,
        language: Language,
        enterAction: EnterAction
    ): List<KeySpec> = listOf(
        KeySpec(
            id = "toggle_sym",
            label = if (isSymbols) "ABC" else "?123",
            action = KeyAction.ToggleSymbols,
            weight = 1.45f,
            isSpecial = true
        ),
        KeySpec(
            id = "comma",
            label = ",",
            action = KeyAction.Character(','),
            weight = 0.85f,
            accents = listOf('(', ')', ';', ':')
        ),
        KeySpec(
            id = "emoji",
            label = "☺",
            action = KeyAction.ToggleEmoji,
            weight = 0.95f,
            isSpecial = true
        ),
        KeySpec(
            id = "space",
            label = "T.A.S.K. · ${language.label}",
            action = KeyAction.Space,
            weight = 4.10f
        ),
        KeySpec(
            id = "period",
            label = ".",
            action = KeyAction.Character('.'),
            weight = 0.95f,
            accents = listOf('!', '?', ':', ';', '-', '/', '\'', '"'),
            hintLabel = "?"
        ),
        KeySpec(
            id = "enter",
            label = enterAction.glyph,
            action = KeyAction.Enter,
            weight = 1.70f,
            isSpecial = true
        )
    )

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
