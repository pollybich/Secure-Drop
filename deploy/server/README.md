# Debian 13: drop.pollybich.ru

Для сервера с установленным Docker и свободными TCP-портами 80, 443 и 8088. Установка выполняется самим владельцем сервера от root; SSH-пароли и ключи передавать не нужно.

```bash
apt-get update && apt-get install -y git
git clone https://github.com/pollybich/pollyDrop.git /opt/pollydrop
cd /opt/pollydrop
./deploy/server/install-debian.sh
```

Если `/opt/pollydrop` уже существует, сначала проверьте его содержимое. Команда clone не перезаписывает существующий каталог.

Скрипт устанавливает недостающий Compose из официального релиза Docker с проверкой SHA-256, скачивает готовые JAR и интерфейс pollyDrop v0.1.0-beta.1 и проверяет их по опубликованному SHA256SUMS. Компиляторы Java и Node.js на сервере не нужны. `.env` с ключом и паролем создаётся непосредственно на сервере и сохраняется при повторном запуске. Готовые файлы остаются в игнорируемом `.runtime/server`.

Compose-проект `pollydrop-public` запускает PostgreSQL, backend, frontend и Caddy. Caddy получает и обновляет HTTPS-сертификат для указанного домена автоматически, сертификаты сохраняются в volumes. Порты 80/443 должны быть доступны из интернета, в том числе через firewall провайдера. PostgreSQL и backend не публикуются; порт 8088 слушает только loopback. Настроены автоперезапуск и ограничение размера Docker-журналов. Нельзя удалять volumes или менять ключ без плана сохранения данных.

На публичном frontend установлены общие для сервиса пределы: создание 12 передач в минуту с небольшим burst, до двух одновременных созданий и двух выдач. Ответ при перегрузке — 429 с предложением повторить через минуту. Это начальные ограничения небольшой беты, не полноценная защита от злоупотреблений. Пути с секретными токенами исключены из access/error log HTTP-прокси.

Проверка после выдачи сертификата:

```bash
curl --fail --retry 6 --retry-all-errors --retry-delay 5 --max-time 15 https://drop.pollybich.ru/api/health
```

Ожидается `status: ok`, версия `0.1.0-beta.1`. Затем проверьте создание и получение тестовой передачи через сайт. Если HTTPS не появился, посмотрите ошибки Caddy:

```bash
cd /opt/pollydrop
docker compose -p pollydrop-public -f compose.yaml -f deploy/server/compose.server.yaml logs --tail=50 caddy backend
```

Остановка сохраняет данные:

```bash
docker compose -p pollydrop-public -f compose.yaml -f deploy/server/compose.server.yaml down
```

Не добавляйте `-v`. Для восстановления нужны вместе база, содержимое и исходный APP_MASTER_KEY. Автоматические резервные копии этим установщиком не настраиваются. Если на сервере уже работает Nginx/Caddy на 80/443, не запускайте второй публичный proxy: используйте подготовленные nginx-конфигурации или Caddyfile.fragment и отдельную адаптацию Compose к существующему proxy.
