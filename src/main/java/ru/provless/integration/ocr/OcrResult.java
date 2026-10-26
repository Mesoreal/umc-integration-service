package ru.provless.umc.client.ocr;

import java.math.BigDecimal;

/**
 * @param confidence average per-word confidence from Yandex OCR, or {@code null} when the
 *                    response didn't carry per-word confidence (e.g. no text found).
 */
public record OcrResult(String text, BigDecimal confidence) {
}
