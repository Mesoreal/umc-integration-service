package ru.provless.integration.review;

import ru.provless.integration.umc.entity.DocumentType;

/** Names of the documents for people (notes, deals). */
public final class DocumentLabels {

    private DocumentLabels() {
    }

    public static String of(DocumentType type) {
        return switch (type) {
            case DIPLOMA -> "Диплом";
            case PASSPORT -> "Паспорт";
            case NAME_CHANGE -> "Свидетельство о смене ФИО";
            case MARRIAGE_CERT -> "Свидетельство о браке";
        };
    }
}
