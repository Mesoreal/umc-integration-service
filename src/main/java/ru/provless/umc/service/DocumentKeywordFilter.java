package ru.provless.umc.service;

import org.springframework.stereotype.Component;
import ru.provless.umc.entity.DocumentType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Cheap, fast "does this even look like the right document" gate — not a replacement for
 * the manager's manual review. A passed filter means "looks like a document of the right
 * type", not "approved". See umc/document-verification-plan.md, stage 3.
 */
@Component
public class DocumentKeywordFilter {

    /** MRZ (machine-readable zone) line: e.g. "P<RUSIVANOV<<IVAN<<<<<<<<<<<<<<<<<<<<<<<<<<". */
    private static final Pattern MRZ_PATTERN = Pattern.compile("[A-Z0-9<]{20,}");

    private static final Map<DocumentType, List<String>> EXPECTED_KEYWORDS = new EnumMap<>(DocumentType.class);

    static {
        EXPECTED_KEYWORDS.put(DocumentType.PASSPORT, List.of("ПАСПОРТ", "ПАСПОРТ ГРАЖДАНИНА"));
        EXPECTED_KEYWORDS.put(DocumentType.DIPLOMA, List.of("ДИПЛОМ", "ОБРАЗОВАНИЕ"));
        EXPECTED_KEYWORDS.put(DocumentType.MARRIAGE_CERT, List.of("СВИДЕТЕЛЬСТВО О БРАКЕ"));
        // Not specified in the technical plan's keyword table — inferred from the standard
        // Russian "свидетельство о перемене имени" wording. Revisit against real samples.
        EXPECTED_KEYWORDS.put(DocumentType.NAME_CHANGE, List.of("ПЕРЕМЕНЕ ИМЕНИ", "О ПЕРЕМЕНЕ ИМЕНИ"));
    }

    public record Result(boolean passed, List<String> matchedKeywords) {
    }

    public Result filter(DocumentType documentType, String ocrText) {
        if (ocrText == null || ocrText.isBlank()) {
            return new Result(false, List.of());
        }

        String normalized = ocrText.toUpperCase();
        List<String> matched = EXPECTED_KEYWORDS.getOrDefault(documentType, List.of()).stream()
                .filter(normalized::contains)
                .toList();

        boolean passed = !matched.isEmpty();
        if (documentType == DocumentType.PASSPORT && !passed) {
            passed = MRZ_PATTERN.matcher(ocrText).find();
        }

        return new Result(passed, matched);
    }
}
