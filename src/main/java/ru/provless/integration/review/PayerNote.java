package ru.provless.integration.review;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The note of a payer request: what the client typed, the DaData suggestion they picked and where the two differ. The suggestion came
 * from the client's app, so it is a hint for the manager, not a proof.
 */
final class PayerNote {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private PayerNote() {
    }

    static String of(JsonNode payer, String client) {
        boolean legal = "LEGAL".equals(payer.path("payer_type").asText());
        StringBuilder text = new StringBuilder("Плательщик на проверку: ").append(legal ? "организация" : "ИП").append('\n')
                .append(client).append("\n\n");
        line(text, "Название", text(payer, "name"));
        line(text, "ИНН", text(payer, "inn"));
        if (legal) {
            line(text, "КПП", text(payer, "kpp"));
        }
        line(text, "Адрес", text(payer, "address"));
        String until = text(payer, "license_valid_until");
        line(text, "Лицензия", text(payer, "license_number") == null ? null
                : text(payer, "license_number") + (until == null ? "" : ", до " + LocalDate.parse(until).format(DAY)));

        JsonNode d = payer.path("dadata");
        text.append('\n');
        if (!d.isObject()) {
            return text.append("DaData: нет — клиент ввёл реквизиты вручную, сверьте по ЕГРЮЛ / ЕГРИП.")
                    .append("\n\nРешение — во вкладке «Проверка документов».").toString();
        }
        text.append("DaData (выбрал клиент в подсказке):\n");
        line(text, "Статус", status(d.path("state").path("status").asText(null)));
        line(text, "Название", first(text(d.path("name"), "short_with_opf"), text(d.path("name"), "full_with_opf")));
        line(text, "ИНН", text(d, "inn"));
        line(text, "КПП", text(d, "kpp"));
        line(text, "ОГРН", text(d, "ogrn"));
        line(text, "Адрес", first(text(d.path("address"), "unrestricted_value"), text(d.path("address"), "value")));
        JsonNode management = d.path("management");
        line(text, "Руководитель", text(management, "name") == null ? null
                : text(management, "name") + (text(management, "post") == null ? "" : ", " + text(management, "post")));
        line(text, "ОКВЭД", text(d, "okved"));

        List<String> differs = differences(payer, d, legal);
        text.append('\n').append(differs.isEmpty() ? "Расхождений с введённым нет."
                : "Отличается от введённого: " + String.join(", ", differs) + ".");
        return text.append("\n\nРешение — во вкладке «Проверка документов».").toString();
    }

    static List<String> differences(JsonNode payer, JsonNode d, boolean legal) {
        List<String> differs = new ArrayList<>();
        String name = norm(text(payer, "name"));
        if (name != null && !name.equals(norm(text(d.path("name"), "short_with_opf")))
                && !name.equals(norm(text(d.path("name"), "full_with_opf"))) && !name.equals(norm(text(d.path("name"), "full")))
                && !name.equals(norm(text(d.path("name"), "short")))) {
            differs.add("название");
        }
        if (legal && text(payer, "kpp") != null && text(d, "kpp") != null && !text(payer, "kpp").equals(text(d, "kpp"))) {
            differs.add("КПП");
        }
        String address = norm(text(payer, "address"));
        if (address != null && !address.equals(norm(text(d.path("address"), "value")))
                && !address.equals(norm(text(d.path("address"), "unrestricted_value")))) {
            differs.add("адрес");
        }
        return differs;
    }

    private static String status(String s) {
        if (s == null) {
            return null;
        }
        return switch (s) {
            case "ACTIVE" -> "действует";
            case "LIQUIDATING" -> "ликвидируется";
            case "LIQUIDATED" -> "ликвидирована";
            case "BANKRUPT" -> "банкротство";
            case "REORGANIZING" -> "в процессе присоединения к другой организации";
            default -> s;
        };
    }

    /** Lower case, letters and digits only: «ООО "Клиника"» equals «ооо «клиника»». */
    static String norm(String s) {
        if (s == null) {
            return null;
        }
        String n = s.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[^\\p{L}\\p{N}]", "");
        return n.isEmpty() ? null : n;
    }

    private static void line(StringBuilder text, String label, String value) {
        if (value != null) {
            text.append(label).append(": ").append(value).append('\n');
        }
    }

    private static String first(String a, String b) {
        return a != null ? a : b;
    }

    static String text(JsonNode json, String field) {
        JsonNode value = json.path(field);
        return value.isNull() || value.isMissingNode() || value.asText().isBlank() ? null : value.asText();
    }
}
