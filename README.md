# umc-integration-service

Интеграция с УМЦ (учебно-методический центр): CRM, расписание курсов/занятий,
уведомления слушателям, проверка документов слушателей.

## Зона ответственности

- Синхронизация карточек клиентов с CRM УМЦ.
- Расписание курсов, преподавателей, групп.
- Уведомления, связанные с обучением (напоминания о занятиях, изменения расписания и т.п.).
- Проверка документов (паспорт, диплом, свидетельство о браке): OCR-precheck,
  очередь ручной проверки и интеграция с amoCRM, приём вебхука о решении менеджера.
  Документы нужны именно для зачисления в учебный центр — это бизнес-логика УМЦ,
  а не отдельная административная функция (было запланировано как отдельный
  `administrator-service` — решение перенесено сюда, чтобы граница сервиса совпадала
  с границей бизнес-ответственности). Полный технический план: [document-verification-plan.md](../document-verification-plan.md).

## Что НЕ входит

- Хранение профиля/идентичности клиента — это [profile-service](../../profile-service).
- Логика клиники (врачи, приёмы) — это [clinic-integration-service](../../clinic/clinic-integration-service).

## Взаимодействие с другими сервисами

- `profile-service → umc-integration-service`: синхронный `POST /internal/document-review/precheck`.
- `umc-integration-service → profile-service`: `GET /internal/storage/{userId}/{fileId}` (байты файла)
  и `GET /internal/profiles/{userId}/contact-info` (телефон/имя — для контакта в amoCRM).
  Оба защищены общим `X-Internal-Auth` (`INTERNAL_API_KEY`), как auth-service ↔ phone-auth.
- `umc-integration-service → amoCRM`: API v4, OAuth2 (refresh-токен ротируется amoCRM
  при каждом использовании — текущий хранится в БД, `AMOCRM_REFRESH_TOKEN` только
  сеет первую запись).
- `GET /files/document-review/{reviewId}/{token}` и `POST /webhooks/amocrm/documents/{secret}` —
  единственные публичные роуты (не `/internal/**`): ссылка на файл в карточке amoCRM и
  приём вебхука о смене этапа сделки. Оба аутентифицированы секретом/токеном в самом
  пути, а не сессией или заголовком — у amoCRM нет гарантированной подписи payload для
  классических вебхуков, поэтому секрет здесь наш собственный, а не то, что шлёт amoCRM.
- RabbitMQ — общий дефолтный vhost `/` со всеми сервисами (не отдельный vhost),
  изоляция по префиксу имени очереди (`umc.*`).

Статус: Фаза 1 (precheck), Фаза 2 (создание сделки в amoCRM) и Фаза 3 (приём вебхука,
обновление статуса `document_review`) реализованы и живьём проверены на реальном
Postgres/RabbitMQ (миграции, старт контекста, HTTP-эндпоинты) — но не на реальном
amoCRM/Yandex OCR аккаунте (все `AMOCRM_*`/`YANDEX_*` — плейсхолдеры). Фаза 4
(публикация `document.approved/rejected` в profile-service + уведомление клиента) — не начата.
