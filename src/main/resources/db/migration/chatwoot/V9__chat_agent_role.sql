-- The line under the manager's name in the app chat (front requests S5 §11.4, contract §14.0 «Manager.role»): Directus «Менеджеры» →
-- «Подпись в чате», empty → by the department (chatwoot.chat.departments[].manager-role). Written only by the managers sync.
ALTER TABLE chatwoot.chat_agent ADD COLUMN role TEXT;
