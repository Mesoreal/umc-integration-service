#!/usr/bin/env bash
# Разово накатывает секреты umc-integration-service в mesoreal-secrets на сервере.
#
# Использование:
#   cp .env.example .env && vim .env   # заполнить реальными значениями
#   ./apply-secrets.sh                 # накатить в кластер (merge, ничего чужого не трогает)
#   rm .env                            # секреты уже в kube-секрете, файл больше не нужен
#
# ./apply-secrets.sh --delete          # то же самое + сам себя удаляет .env при успехе
set -euo pipefail

ENV_FILE="${1:-.env}"
DELETE=false
for arg in "$@"; do
  [ "$arg" = "--delete" ] && DELETE=true
done
SECRET_NAME="${SECRET_NAME:-mesoreal-secrets}"

if [ ! -f "$ENV_FILE" ]; then
  echo "Не найден $ENV_FILE (запусти из папки, где он лежит, или укажи путь первым аргументом)" >&2
  exit 1
fi

json="{"
first=true
count=0
while IFS='=' read -r key value || [ -n "$key" ]; do
  key="$(echo -n "$key" | tr -d '[:space:]')"
  [ -z "$key" ] && continue
  case "$key" in \#*) continue ;; esac
  [ -z "$value" ] && continue   # пустое значение — нечего накатывать, оставляем как есть в секрете

  escaped_value=$(printf '%s' "$value" | sed 's/\\/\\\\/g; s/"/\\"/g')
  $first || json+=","
  first=false
  json+="\"$key\":\"$escaped_value\""
  count=$((count + 1))
done < "$ENV_FILE"
json+="}"

if [ "$count" -eq 0 ]; then
  echo "В $ENV_FILE нет ни одного заполненного значения — нечего накатывать." >&2
  exit 1
fi

echo "Обновляю $count ключ(ей) в секрете $SECRET_NAME (merge — остальные ключи не трогаются)..."
kubectl patch secret "$SECRET_NAME" --type=merge -p "{\"stringData\":$json}"

echo "Готово. Не забудь перезапустить зависимые деплойменты/statefulset-ы, если пароль меняет уже работающее соединение."

if [ "$DELETE" = true ]; then
  rm -f "$ENV_FILE"
  echo "$ENV_FILE удалён."
else
  echo "Теперь можно удалить $ENV_FILE — значения уже в кластере: rm $ENV_FILE"
fi
