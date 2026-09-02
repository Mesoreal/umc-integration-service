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
- `GET /files/document-review/{reviewId}/{token}` — единственный публичный роут
  (не `/internal/**`): ссылка на файл в карточке amoCRM, аутентифицирована
  непредсказуемым токеном в пути, не сессией.
- RabbitMQ — общий дефолтный vhost `/` со всеми сервисами (не отдельный vhost),
  изоляция по префиксу имени очереди (`umc.*`).

Статус: Фаза 1 (precheck) и Фаза 2 (создание сделки в amoCRM, без вебхука) реализованы,
не проверялись на реальном amoCRM/Yandex OCR аккаунте. Фаза 3 (вебхук) и Фаза 4
(публикация статуса в profile-service) — не начаты.
