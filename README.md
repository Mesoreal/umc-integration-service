# integration-service

Все интеграции бэкенда с внешними системами: УМЦ (проверка документов, amoCRM, Yandex OCR)
и Chatwoot (чат менеджеров). До 2026-10-25 сервис назывался `umc-integration-service` и лежал
в `umc/`; новые внешние системы добавляются сюда отдельным пакетом, а не в доменные сервисы.

## Пакеты

| Пакет | Что внутри |
|---|---|
| `umc` | Проверка документов слушателей УМЦ: OCR-precheck, сделка в amoCRM, вебхук о решении менеджера. Бизнес-логика УМЦ; план — [document-verification-plan.md](../umc/document-verification-plan.md). |
| `amocrm` | Клиент amoCRM API v4 и хранение OAuth2-токенов. |
| `ocr` | Yandex Vision OCR. |
| `chatwoot` | Контакт приложения в Chatwoot: при регистрации пользователь привязывается к контакту с тем же телефоном. План — [plan-app-contact-merge.md](../docs/backend/artifacts/shop/chatwoot/plan-app-contact-merge.md). |
| `profile` | Клиент внутреннего API profile-service. |
| `common` | Внутренняя авторизация `/internal/**`, RabbitMQ, обработка ошибок. |

## Что НЕ входит

- Хранение профиля/идентичности клиента — это [profile-service](../profile-service).
- Логика клиники (врачи, приёмы) — это [clinic-integration-service](../clinic/clinic-integration-service).
- Мосты мессенджеров (WhatsApp, Telegram, MAX, VK → Chatwoot) — отдельные процессы в
  [shop/chatwoot](../shop/chatwoot): другой стек и долгоживущие сессии мессенджеров.

## Данные

БД `integration_db`, пользователь `integration_user`. Схема `umc` — проверка документов и токены amoCRM,
схема `chatwoot` — `app_contact_link` (пользователь приложения ↔ контакт Chatwoot). Миграции Flyway —
`src/main/resources/db/migration/<схема>/`, номера версий общие на все папки.

## Взаимодействие с другими сервисами

- `profile-service → integration-service`: синхронный `POST /internal/document-review/precheck`.
- `integration-service → profile-service`: `GET /internal/storage/{userId}/{fileId}` (байты файла)
  и `GET /internal/profiles/{userId}/contact-info` (телефон/имя — для контактов amoCRM и Chatwoot).
  Оба защищены общим `X-Internal-Auth` (`INTERNAL_API_KEY`), как auth-service ↔ phone-auth.
- `integration-service → amoCRM`: API v4, OAuth2 (refresh-токен ротируется amoCRM при каждом
  использовании — текущий хранится в БД, `AMOCRM_REFRESH_TOKEN` только сеет первую запись).
- `integration-service → Chatwoot`: application API v1, токен сервисного пользователя-администратора
  (`CHATWOOT_API_TOKEN`), `CHATWOOT_ENABLED=false` — интеграция выключена.
- RabbitMQ (общий vhost `/`, изоляция по префиксу имени очереди). Сервис читает:
  - `auth.user-logged-in` `{userId, phone}` — каждый вход по OTP (кроме review-номеров) → привязать к контакту Chatwoot
    (идемпотентно: уже привязанный — только проверка контакта и имени);
  - `profile.contacts-updated` `{userId}` — могло смениться ФИО → обновить имя контакта.

  Ошибка обработки повторяется 5 раз (2–30 с), затем сообщение уходит в `<очередь>.dlq`. Очереди
  объявляют и производители, и этот сервис — с одинаковыми аргументами dead-letter.
  `integration.document-uploaded` (резерв precheck при сбое синхронного вызова) объявлена в
  profile-service, потребителя пока нет.
- `POST /internal/chatwoot/app-contacts/{userId}/ensure` → `{"contactId": …}` — ленивая привязка
  (BE-19: первое открытие чата) и ручной ремонт пропущенного события.
- Публичные роуты (не `/internal/**`): `GET /files/document-review/{reviewId}/{token}` и
  `POST /webhooks/amocrm/documents/{secret}` — ссылка на файл в карточке amoCRM и вебхук о смене
  этапа сделки. Аутентифицированы секретом/токеном в пути: у классических вебхуков amoCRM нет
  гарантированной подписи payload.

## Как Chatwoot-контакт выбирается

Один телефон = один человек = один контакт: Chatwoot держит телефон уникальным в аккаунте, мосты
мессенджеров уже переиспользуют контакт с тем же номером. Приложение — ещё один канал контакта
(inbox «Приложение», `source_id = userId`, `custom_attributes.app_user_id`):

1. есть `app_contact_link` и контакт жив → только обновить имя;
2. контакт с подтверждённым телефоном (точное совпадение) → добавить ему канал приложения;
3. иначе создать контакт сразу с каналом приложения; 422 при создании (гонка с мостом) → шаг 2.

ФИО из профиля заменяет только пустое имя, телефон или заглушку моста (`Telegram 123`, `VK id123`,
`MAX 123`) — имя, введённое менеджером, не трогается. Review-аккаунты в Chatwoot не попадают
(auth-service не отправляет для них событие).

## Статус

УМЦ: Фаза 1 (precheck), Фаза 2 (сделка в amoCRM) и Фаза 3 (вебхук, статус `document_review`)
реализованы и проверены на реальном Postgres/RabbitMQ, но не на реальном amoCRM/Yandex OCR
(`AMOCRM_*`/`YANDEX_*` — плейсхолдеры). Фаза 4 (`document.approved/rejected` → profile-service +
уведомление клиента) не начата.

Chatwoot: привязка контакта при регистрации — итерация 1, локальный стек (`local/`).
