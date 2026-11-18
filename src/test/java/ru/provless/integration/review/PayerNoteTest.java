package ru.provless.integration.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The payer note: what the client typed against the DaData suggestion (front requests S5 §10). */
class PayerNoteTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void sameDataInOtherQuotesAndCase_noDifference() throws Exception {
        JsonNode payer = json.readTree("""
                {"payer_type":"LEGAL","name":"ооо \\"Клиника\\"","inn":"7701234567","kpp":"770101001","address":"г Москва, ул Тверская, д 1",
                 "dadata":{"inn":"7701234567","kpp":"770101001","name":{"short_with_opf":"ООО «Клиника»"},
                           "address":{"value":"г Москва, ул Тверская, д 1"}}}""");
        assertThat(PayerNote.differences(payer, payer.path("dadata"), true)).isEmpty();
        assertThat(PayerNote.of(payer, "Клиент: —")).contains("Расхождений с введённым нет.");
    }

    @Test
    void soleTrader_withoutDadata_typedByHand() throws Exception {
        JsonNode payer = json.readTree("""
                {"payer_type":"IE","name":"ИП Иванова","inn":"770123456789","license_number":"ЛО-1"}""");
        String text = PayerNote.of(payer, "Клиент: Иванова Анна, +79001234567");
        assertThat(text).startsWith("Плательщик на проверку: ИП").contains("Лицензия: ЛО-1")
                .contains("DaData: нет — клиент ввёл реквизиты вручную").doesNotContain("КПП");
    }
}
