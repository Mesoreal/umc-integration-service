package ru.provless.integration.order;

import java.util.Map;

/** Order statuses in words, as the app shows them (contract §17.6.10). */
public final class OrderLabels {

    public static final Map<String, String> STATUS = Map.of("pending", "Принят", "invoiced", "Ждёт оплаты",
            "processing", "Собираем заказ", "shipped", "Передано в доставку", "delivered", "Получен", "cancelled", "Отменён");

    private OrderLabels() {
    }

    public static String status(String code) {
        return code == null ? null : STATUS.getOrDefault(code, code);
    }
}
