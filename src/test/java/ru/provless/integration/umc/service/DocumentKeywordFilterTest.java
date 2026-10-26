package ru.provless.umc.service;

import org.junit.jupiter.api.Test;
import ru.provless.umc.entity.DocumentType;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentKeywordFilterTest {

    private final DocumentKeywordFilter filter = new DocumentKeywordFilter();

    @Test
    void passesPassportWhenKeywordPresent() {
        var result = filter.filter(DocumentType.PASSPORT, "РОССИЙСКАЯ ФЕДЕРАЦИЯ\nПАСПОРТ ГРАЖДАНИНА\n...");

        assertThat(result.passed()).isTrue();
        assertThat(result.matchedKeywords()).contains("ПАСПОРТ ГРАЖДАНИНА");
    }

    @Test
    void passesPassportViaMrzWhenNoKeywordMatch() {
        String mrz = "P<RUSIVANOV<<IVAN<<<<<<<<<<<<<<<<<<<<<<<<<<";
        var result = filter.filter(DocumentType.PASSPORT, mrz);

        assertThat(result.passed()).isTrue();
        assertThat(result.matchedKeywords()).isEmpty();
    }

    @Test
    void rejectsPassportWhenNeitherKeywordNorMrzFound() {
        var result = filter.filter(DocumentType.PASSPORT, "случайное фото кота");

        assertThat(result.passed()).isFalse();
    }

    @Test
    void passesDiplomaWhenKeywordPresent() {
        var result = filter.filter(DocumentType.DIPLOMA, "ДИПЛОМ О ВЫСШЕМ ОБРАЗОВАНИИ");

        assertThat(result.passed()).isTrue();
        assertThat(result.matchedKeywords()).contains("ДИПЛОМ");
    }

    @Test
    void rejectsWhenOcrTextIsBlank() {
        var result = filter.filter(DocumentType.MARRIAGE_CERT, "   ");

        assertThat(result.passed()).isFalse();
        assertThat(result.matchedKeywords()).isEmpty();
    }

    @Test
    void isCaseInsensitive() {
        var result = filter.filter(DocumentType.MARRIAGE_CERT, "свидетельство о браке серия I-АБ №123456");

        assertThat(result.passed()).isTrue();
    }
}
