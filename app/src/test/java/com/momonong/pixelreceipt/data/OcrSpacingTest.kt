package com.momonong.pixelreceipt.data

import com.momonong.pixelreceipt.data.extraction.*
import org.junit.Assert.*
import org.junit.Test

class OcrSpacingTest {
    @Test fun missingChineseColumnSpacesAreRecoveredFromCharacterBoxes() {
        val symbols = listOf(OcrSymbol("牛", 0, 40), OcrSymbol("奶", 44, 84), OcrSymbol("2", 116, 139),
            OcrSymbol("5", 171, 194), OcrSymbol("0", 200, 223), OcrSymbol("1", 255, 278), OcrSymbol("0", 284, 307), OcrSymbol("0", 313, 336))
        assertEquals("牛奶 2 50 100", spacedOcrText("牛奶250 100", symbols))
    }
    @Test fun sdkWordsAreKeptWhenSymbolsHaveNoReliableGaps() {
        assertEquals("A B", spacedOcrText("A B", listOf(OcrSymbol("A", 0, 20), OcrSymbol("B", 21, 41))))
        assertEquals("MILK", spacedOcrText("MILK", emptyList()))
    }
    @Test fun actualInkGapsHandlePartitionedBoxesWithoutSplittingNarrowDigits() {
        val symbols = listOf(OcrSymbol("麵", 47, 127), OcrSymbol("包", 127, 176), OcrSymbol("1", 176, 233),
            OcrSymbol("2", 233, 279), OcrSymbol("5", 279, 310), OcrSymbol("2", 340, 367), OcrSymbol("5", 367, 398))
        assertEquals("麵包 1 25 25", spacedOcrText("麵包125 25", symbols, listOf(155..186, 200..244, 311..338)))
        assertEquals("100", spacedOcrText("100", listOf(OcrSymbol("1", 336, 348), OcrSymbol("0", 362, 386), OcrSymbol("0", 390, 414)), emptyList()))
    }
}
