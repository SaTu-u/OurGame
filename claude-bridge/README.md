# ClaudeBridge

Плагин для **Purpur / Paper 26.2**, который даёт Claude удалённый доступ к серверу через HTTPS API:

- **Администрирование:** любые консольные команды с выводом, состояние сервера (TPS, MSPT, память, игроки, миры), логи с поиском.
- **Плагины и файлы:** список плагинов с версиями и зависимостями, просмотр файлов сервера, поиск по имени и по тексту. Доступ к файлам только на чтение.
- **Базы данных:** SELECT-запросы к SQLite-файлам и MySQL/MariaDB. Только чтение: опасные запросы отсекает валидатор, а само соединение открывается в режиме read-only.
- **Игра:** аватар Claude в мире. Это фигура игрока (`Mannequin`), которой Claude управляет через API: ходит с поиском пути, следует за игроком, копает с реальным временем добычи, ставит блоки, открывает двери, пользуется сундуками и печами, крафтит, сражается, пишет в чат и читает чат.

## Как это работает и почему именно так

Claude работает в облачной сессии, откуда наружу разрешён только **HTTPS на порт 443** к хостам из allowlist. Игровой протокол Minecraft через такую сеть не проходит, поэтому настоящий клиент Claude подключить не может.

Плагин решает это иначе: он поднимает **обычный веб-API**. Claude отправляет HTTPS-запросы, а плагин выполняет их **внутри сервера**: команды, чтение файлов и действия аватара. Это не туннель игрового трафика, а штатная работа с веб-API.

```
Claude (облако) --HTTPS:443--> Caddy/nginx на вашем сервере --> ClaudeBridge (127.0.0.1:8765) --> сервер Purpur
```

## Установка

1. Скопируйте `dist/ClaudeBridge-1.1.0.jar` в `plugins/` и перезапустите сервер.
2. Плагин создаст `plugins/ClaudeBridge/config.yml` и `plugins/ClaudeBridge/token.txt` со случайным 256-битным токеном.
3. API слушает **только** `127.0.0.1:8765`. Не открывайте этот порт в интернет.

Проверка на самом сервере:
```bash
TOKEN=$(cat plugins/ClaudeBridge/token.txt)
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8765/api/status
```

## Открыть доступ для Claude (HTTPS)

Claude может обращаться только к домену с действующим сертификатом на порту **443**, потому что прокси облака не работает с другими портами. Этот домен обслуживает ваш веб-сервер (Caddy или nginx), а он уже передаёт запросы плагину.

### Вариант A: Minecraft и веб-сервер на одной машине (VPS)

Плагин остаётся на `127.0.0.1:8765`. Проще всего настроить [Caddy](https://caddyserver.com): он сам получит сертификат Let's Encrypt.
```
mc.dee47.ru {
    reverse_proxy 127.0.0.1:8765
}
```

### Вариант B: Minecraft на игровом хостинге, веб-сервер на другой машине

Между веб-сервером и хостингом запросы идут через интернет. Поэтому плагин принимает их **только по HTTPS** со своим сертификатом, который веб-сервер проверяет (pinning), и **только с IP вашего веб-сервера**.

1. **Выделите дополнительный порт** в панели хостинга. Например, в Pterodactyl это делается в разделе Network → Create allocation. Дальше он обозначен как `PORT`.

2. **Замените jar** на `ClaudeBridge-1.1.0.jar` и удалите старый `plugins/ClaudeBridge/config.yml`, чтобы появились новые настройки. Затем в новом `config.yml` укажите:
   ```yaml
   http:
     bind: 0.0.0.0
     port: PORT            # порт из шага 1
     tls:
       enabled: true
     allowed-ips: ["IP_ВЕБ_СЕРВЕРА"]   # узнать на веб-сервере: curl -4 ifconfig.me
   ```

3. **Перезапустите сервер.** В консоли должны появиться две строки:
   - `HTTPS certificate: plugins/ClaudeBridge/tls-cert.pem (SHA-256 …)`;
   - `ClaudeBridge API listening on https://0.0.0.0:PORT, allowed clients: [...]`.

4. **Скопируйте сертификат.** Скачайте `plugins/ClaudeBridge/tls-cert.pem` через файловый менеджер панели и положите на веб-сервер, например в `/etc/caddy/claudebridge.pem`. Это публичный сертификат, не секрет.

5. **Настройте веб-сервер**, подставив `PORT`.

   Caddy:
   ```
   mc.dee47.ru {
       reverse_proxy https://87.228.74.140:PORT {
           transport http {
               tls_trust_pool file /etc/caddy/claudebridge.pem
               tls_server_name claudebridge.local
           }
       }
   }
   ```
   В Caddy старше 2.9 вместо `tls_trust_pool file …` пишется `tls_trusted_ca_certs /etc/caddy/claudebridge.pem`.

   nginx (сертификат для `mc.dee47.ru` выпускается через `certbot --nginx -d mc.dee47.ru`):
   ```
   server {
       listen 443 ssl;
       server_name mc.dee47.ru;
       location / {
           proxy_pass https://87.228.74.140:PORT;
           proxy_ssl_verify on;
           proxy_ssl_trusted_certificate /etc/nginx/claudebridge.pem;
           proxy_ssl_name claudebridge.local;
           proxy_ssl_server_name on;
           proxy_set_header X-Forwarded-For $remote_addr;
       }
   }
   ```

6. **Проверьте с веб-сервера:** `curl https://mc.dee47.ru/api`. Правильный ответ — `{"error":"unauthorized: ..."}`: без токена API должен отказывать.

Если выпустили новый сертификат командой `/claudebridge tls`, повторите шаг 4.

Если хостинг не даёт дополнительный порт, скажите Claude: тогда плагину нужен другой режим, в котором он сам подключается к вашему веб-серверу.

## Как передать токен Claude

**Не отправляйте токен в чат.** Claude откроет защищённую форму (`request_credential`) для хоста `mc.dee47.ru`, и вы вставите токен туда. Прокси облака сам будет подставлять заголовок `Authorization: Bearer …` в запросы к этому хосту, а Claude сам токен не увидит.

Как отозвать доступ:
- `/claudebridge token` — сгенерировать новый токен, старый перестанет работать;
- `/claudebridge off` — мгновенно выключить API;
- `/claudebridge on` — включить обратно.

## Команды в игре (permission `claudebridge.admin`, по умолчанию у op)

| Команда | Что делает |
|---|---|
| `/claudebridge status` | состояние API и аватара |
| `/claudebridge on` / `off` | включить или выключить API |
| `/claudebridge token` | выпустить новый токен |
| `/claudebridge tls` | выпустить новый HTTPS-сертификат (потом заново скопируйте `tls-cert.pem` на веб-сервер) |
| `/claudebridge reload` | перечитать конфиг и перезапустить API |
| `/claudebridge despawn` | убрать аватара |

## API

Все запросы требуют заголовок `Authorization: Bearer <token>`. Параметры передаются JSON-телом в POST или query-строкой в GET. Полный список эндпоинтов с описаниями возвращает `GET /api`.

**Сервер:**
- `GET /api/status`
- `POST /api/console {command}`
- `GET /api/logs?lines=200&grep=error`
- `GET /api/logs/list`
- `GET /api/plugins`
- `GET /api/plugins/info?name=X`

**Файлы:**
- `GET /api/files/list?path=plugins`
- `GET /api/files/read?path=server.properties`
- `GET /api/files/find?pattern=**/*.yml`
- `GET /api/files/grep?text=...`

**Базы:**
- `GET /api/db/connections`
- `GET /api/db/tables?sqlite=plugins/X/data.db`
- `POST /api/db/query {sqlite|connection, sql, params}`

**Чат:**
- `GET /api/chat?since=<id>` — сообщения игроков, входы и выходы, смерти, события аватара. Команды игроков не записываются.

**Аватар:**
- Появление и уход: `spawn`, `despawn`.
- Восприятие: `state`, `map` (ASCII-карта сверху), `blocks`, `find`, `inventory`.
- Движение: `goto`, `follow`, `stop`, `look`, `jump`, `teleport`.
- Мир: `break`, `place`, `use`, `attack`, `chat`.
- Предметы: `hold`, `drop`, `equip`, `craft`, `container`, `container/put`, `container/take`, `give`.

Все эти пути начинаются с `/api/avatar/…`.

Пример:
```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" -d '{"player":"player1"}' http://127.0.0.1:8765/api/avatar/spawn
curl -s -X POST -H "Authorization: Bearer $TOKEN" -d '{"message":"Привет! Я Claude"}' http://127.0.0.1:8765/api/avatar/chat
```

## Безопасность

- Токен 256 бит, сравнивается за постоянное время. Неверные попытки замедляются и пишутся в журнал.
- На хостинге: встроенный HTTPS с сертификатом, который проверяет веб-сервер, плюс `allowed-ips`. Проверяется реальный IP соединения, а не заголовок `X-Forwarded-For`, который можно подделать. Если в списке ошибка, плагин отклоняет все запросы.
- Каждый запрос пишется в `plugins/ClaudeBridge/audit.log`: время, IP, путь и параметры, включая текст команд.
- Файлы доступны только на чтение и только внутри папки сервера: выход наружу через `..` и симлинки заблокирован. Закрыть отдельные папки можно через `files.deny`.
- Базы только на чтение. Для MySQL лучше завести пользователя с одними правами SELECT.
- Любую часть можно выключить в `features:` в `config.yml`.
- Консоль даёт полный контроль над сервером. Перед началом сделайте бэкап мира.

## Ограничения (честно)

- **Аватар — не настоящий клиент.** Это серверная сущность `Mannequin`, поэтому клиентскую сборку 26.3 он не тестирует. Его не видно в списке игроков (Tab), у него нет голода и опыта.
- Ломание и установка блоков аватаром не вызывают `BlockBreakEvent` и `BlockPlaceEvent`, поэтому плагины защиты территорий их не видят. Это инструмент администратора.
- Двери и кровати аватар пока не ставит. Рычаги и кнопки работают упрощённо: сложные редстоун-схемы могут не среагировать.
- Базы H2 (`*.mv.db`, например стандартное хранилище LuckPerms) не поддерживаются: в сервере нет драйвера H2.

## Сборка из исходников

Нужны JDK 25 и jar Purpur 26.2. Скрипт берёт API из самого jar сервера, а Gson, Guava и аннотации скачивает с Maven Central с проверкой SHA1.
```bash
./build.sh /path/to/purpur-26.2.jar
```
