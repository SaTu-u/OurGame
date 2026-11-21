# ClaudeBridge: guide for AI agents

> **Для человека.** Это инструкция для нейросетей (на английском, чтобы её понимала любая модель).
> Передайте её ИИ-агенту вместе с адресом API. Токен передавайте только через защищённый канал
> (например, форму секретов вашего агента), не вставляйте его в чат.

You are connected to a Minecraft Java Edition server (Purpur/Paper **26.2**) through the **ClaudeBridge** plugin
(version 1.4.7 or later). The plugin exposes a JSON-over-HTTPS API that lets you:

1. **Administer the server**: run console commands, read logs, inspect plugins, browse files, query databases (read-only),
   and, only if the owner enabled it, change files (every change keeps a backup).
2. **Play the game** through an **avatar**: a player-shaped entity (named `Claude` by default) that walks with pathfinding,
   mines with real mining times, places blocks, uses doors/levers/chests/furnaces, crafts, fights and chats.

Every request and response below was checked against a real Purpur 26.2 server; examples are real (shortened with `…`).

---

## 0. Quick start (do exactly this first)

1. `GET {BASE}/health` (no token). Expect `{"service":"claudebridge","instance":"…"}`. Anything else means the address is wrong
   or the tunnel changed: ask the owner for the current address (section 2.4). Do not guess addresses.
2. `GET {BASE}/api/status` with the token. Expect HTTP 200 with `tps`, `players`, `avatarSpawned`. A 401 means the token
   is missing or wrong: stop and ask the owner.
3. `GET {BASE}/api/chat?since=0`. Remember `latestId`.
4. Only if the owner wants you to play: `POST {BASE}/api/avatar/spawn` with `{}` (last place) or `{"player":"<name>"}`,
   then `GET {BASE}/api/avatar/state`.
5. Work in small steps: observe → one action → read `status`/`reason` → decide again (section 7.2).

## 1. Mental model

- **The avatar is not a real player client.** It is a server-side `Mannequin` entity controlled only through this API.
  Players see it walk, swing, mine, place blocks, hold items, wear armor and talk in chat.
  It is **not** in the player list (Tab), has **no hunger, XP or advancements**, cannot sprint, swim properly or use elytra,
  and you **do not see a rendered picture**: you perceive the world only through JSON (`state`, `map`, `blocks`, `find`, `chat`).
- **One avatar per server.** It keeps its inventory and position across restarts (`plugins/ClaudeBridge/avatar.yml`) and
  comes back by itself a few seconds after the server starts.
- **It can die** (falls, lava, mobs, drowning) unless the owner made it invulnerable. On death it drops its inventory
  where it died (unless `keep-inventory` is on) and must be spawned again: `spawn {}` brings it back to that spot and it
  picks the items up (dropped items vanish after 5 minutes). If it died in lava or a pit, spawn it next to a player instead.
- **Long actions are tasks** (walk, follow, mine, fight). Only one task runs at a time; starting a new one cancels the old one.
- **Admin access is real.** Console commands run with full console permissions. Be careful (section 5).

## 2. Connecting

### 2.1 Basics

| Item | Value |
|---|---|
| Base URL (`{BASE}`) | Given by the owner. Usually a tunnel address: `https://<id>.lhr.life` or `https://<id>.app.lhr.life` (localhost.run), `https://<name>.ru.tuna.am` (tuna.am), `https://<words>.trycloudflare.com`, `https://<name>.ngrok-free.app`; or an own domain such as `https://bridge.example.com`; or `http://127.0.0.1:8765` on the server itself. |
| Auth | Every `/api…` request needs the header `Authorization: Bearer <token>`. If your environment injects this header for you (a secret store or proxy), do not add it yourself. `/health` needs no token. |
| GET requests | Parameters go in the **query string**: `/api/avatar/find?block=OAK_LOG&radius=16`. A JSON body on a GET is ignored. URL-encode values (`grep=Can%27t%20keep%20up`, or `curl -G --data-urlencode "grep=Can't keep up"`). |
| POST requests | Parameters go in a **JSON object body** with `Content-Type: application/json`, e.g. `{"x":10,"y":64,"z":-3}`. Numbers may be JSON numbers or strings. An empty body is the same as `{}`. |
| Responses | Always JSON. Errors are `{"error":"message"}` with an HTTP status code (2.3). |
| Discovery | `GET /api` lists every endpoint with a one-line description of its parameters. |

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/api/status"
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d '{"command":"list"}' "$BASE/api/console"
curl -s -G -H "Authorization: Bearer $TOKEN" "$BASE/api/logs" --data-urlencode "grep=Can't keep up" --data-urlencode "lines=50"
```

### 2.2 Timeouts (important)

`goto`, `break`, `attack` wait for their result up to `timeout` seconds (default 30, max 120). Set your HTTP client timeout to
at least **`timeout` + 15 s** (so 45 s with the default). If your tool cannot wait that long, pass `"wait": false` (the call
returns at once with `{"status":"started"}`) and poll `GET /api/avatar/state` every 1–2 s until `task` is `null`,
then read `lastTask` (3.5). Any request that needs the server's main thread fails with 504 after 20 s if the server is frozen.

### 2.3 HTTP status codes

| Code | Meaning | What to do |
|---|---|---|
| 200 | Done. Avatar actions also have a `status` field (3.5): read it, `failed` is a normal 200 answer. | |
| 400 | Bad or missing parameter, invalid JSON, body not a JSON object, slot out of range. | Fix the request (`GET /api` shows parameters). |
| 401 | Missing or wrong token. | Stop and ask the owner. Do not retry in a loop. |
| 403 | Forbidden: feature switched off in `config.yml`, path outside the server folder, a secret file, a write attempt on a database, or your IP is not allowed. | Do not try to bypass it. |
| 404 | Not found: offline player, unknown item, no target nearby, missing file or plugin, item not in the inventory. | |
| 405 | Wrong HTTP method for this path (e.g. POST to a GET endpoint). | |
| 409 | The avatar is not in the world. | `POST /api/avatar/spawn`. Right after a server restart wait ~15 s: it comes back by itself. |
| 413 | Request body larger than 1 MB. | |
| 422 | Valid request but impossible right now: too far, occupied, not enough ingredients, chunk not loaded… | Read the message: it says what to do. |
| 500 | Unexpected server-side error. | Report the message to the owner. |
| 503 | The API was switched off by an admin (`/claudebridge off`). | Wait for the owner. |
| 504 | The server main thread did not answer in 20 s (lag or crash). | Check `GET /api/status` later. |

### 2.4 When the address stops working

Free tunnel addresses change (localhost.run without a registered key: every few minutes to hours; Cloudflare quick tunnels:
on every restart). Symptoms: DNS error, connection refused, an HTML page instead of JSON, or `/health` not answering.
Then **stop and ask the owner for the current address**. The owner finds it in the server console (line
`[ClaudeBridge] Tunnel ready: https://…`) or in `plugins/ClaudeBridge/tunnel-url.txt`. While you are still connected,
`GET /api/status` → `tunnel.url` shows the current address and `tunnel.check` whether it works.

### 2.5 TLS

If the owner uses the **self-signed** mode behind their own reverse proxy, you talk to the proxy and TLS is handled there.
If you connect to a self-signed listener directly, trust the server's `plugins/ClaudeBridge/tls-cert.pem` and use the
host name `claudebridge.local`, e.g. `curl --cacert tls-cert.pem --resolve claudebridge.local:PORT:IP https://claudebridge.local:PORT/api`.
Never disable certificate verification and never print, log or chat the token.

## 3. Conventions

### 3.1 Coordinates

- **Axes:** `x` east (+) / west (−), `y` up, `z` south (+) / north (−).
- **Block coordinates** are integers: the block that contains a point is `floor(x), floor(y), floor(z)`
  (so position `-12.5` is in block `-13`). Endpoints that act on a block (`break`, `place`, `use`, `container…`) take block coordinates.
- **Entity positions** are decimals at the feet. The middle of block `(X, Z)` is `(X + 0.5, Z + 0.5)`
  (for `X = -7` that is `-6.5`, not `-7.5`). `goto` and `teleport` accept decimals. `spawn` and `teleport` put the avatar on
  the nearest spot where its whole body fits, and if it ever ends up inside a block it steps out by itself within a second
  (chat entry `stepped out of a block…`).
- **Rotation:** `yaw` 0 = south, 90 = west, 180/−180 = north, −90 = east; `pitch` −90 = up, 90 = down.

### 3.2 Items and blocks

Bukkit material names, case-insensitive: `OAK_LOG`, `COBBLESTONE`, `RAW_IRON`, `IRON_INGOT`, `CRAFTING_TABLE`, `FURNACE`,
`WOODEN_PICKAXE`, `STONE_SWORD`… Mining stone gives `COBBLESTONE`, iron ore gives `RAW_IRON`, coal ore gives `COAL`.
Item objects look like `{"type":"WOODEN_PICKAXE","amount":1,"durability":"58/59"}` (`durability` only for tools and armor).

### 3.3 Inventory

36 slots (`0`–`35`). `heldSlot` is the slot in hand; `hold` changes it. Armor is separate (`boots`, `leggings`,
`chestplate`, `helmet`). Picking up is automatic: items on the ground within 1.5 blocks go into the inventory.

### 3.4 Reach

Blocks within **4.5** blocks of the avatar's eyes (eyes are 1.62 above the feet); melee hits within about **3**.
Line of sight is not required. `break` walks into reach by itself (`approach`); `place`, `use` and `container…` do not:
call `goto` first if they answer `422 block is too far`.

### 3.5 Tasks and the `status` field

Avatar endpoints answer HTTP 200 with a `status` field:

| `status` | Meaning |
|---|---|
| `ok` | Done. |
| `failed` | Not done; `reason` says why. Change the plan instead of repeating the same call. |
| `running` | The task is still going when `timeout` ran out. Poll `GET /api/avatar/state` until `task` is `null`, then read `lastTask`. |
| `started` | Task started without waiting (`"wait": false`, and always for `follow`). |
| `cancelled` | Replaced by a newer task, `stop`, teleport, spawn or death (`reason` says which). |
| `spawned` / `teleported` / `despawned` / `not spawned` | Results of `spawn` and `despawn`. |
| `sent` | Chat message sent. |
| `stopped` / `idle` | Result of `stop` (a task was running / nothing was running). |

`GET /api/avatar/state` has `task` (`{"name":…,"status":"running"}` or `null`) and `lastTask`: the full result of the most
recently finished task plus `finishedAt`, e.g.
`{"status":"failed","reason":"could not get close to the block: no path found (6.1 blocks left)","task":"break STONE at -12 106 -11","finishedAt":"…"}`.

### 3.6 Chunks

The avatar keeps the 3×3 chunks around itself loaded. Pathfinding, `find` and `map` only see loaded chunks
(`map` shows `?` elsewhere), so long trips happen in segments automatically.

## 4. Admin endpoint reference

### 4.1 Server

#### `GET /api/status`
```json
{"name":"Purpur","version":"26.2-2633-3f5d9c0 (MC: 26.2)","minecraftVersion":"26.2","tps":[20.0,20.0,20.0],"mspt":0.58,
 "memory":{"usedMB":710,"allocatedMB":1024,"maxMB":2048},"uptimeSeconds":58,"onlineMode":false,"motd":"My server","maxPlayers":20,
 "players":[{"name":"player1","uuid":"…","world":"world","x":10.5,"y":64.0,"z":-3.2,"health":20.0,"food":20,"level":0,
             "gameMode":"SURVIVAL","ping":40,"op":false}],
 "worlds":[{"name":"world","environment":"NORMAL","players":1,"entities":12,"loadedChunks":81,"time":472,"storm":false,
            "thundering":false,"difficulty":"EASY","spawn":[0,87,0]}, …],
 "plugins":23,"avatarSpawned":true,
 "tls":{"mode":"off","state":"plain HTTP"},
 "tunnel":{"provider":"localhostrun","state":"online","url":"https://33a8deeca0b657.app.lhr.life","connected":true,"check":"ok"}}
```
TPS 20 is perfect; below ~18 means lag (TPS is low for the first minute after a start). MSPT above 50 means the server
cannot keep up. `tunnel.check` is the plugin's own test of its public address (`ok`, or `failing: <reason>`).

#### `POST /api/console` `{command, console?=false, wait_ms?=500}`
Runs a console command (leading `/` optional) with console permissions. `feedback` holds the command's own answer;
`log` holds log lines written during `wait_ms` (0–10000). `console: true` runs it as the real console (answer only in `log`).
```json
{"command":"list","dispatched":true,"feedback":["There are 0 of a max of 20 players online: "],"log":[]}
{"command":"time set day","dispatched":true,"feedback":["Set minecraft:overworld to time marker minecraft:day"],"log":[]}
{"command":"nosuchcommand","dispatched":false,"feedback":[],"log":[],"note":"the command does not exist or reported failure"}
{"command":"gamerule spawn_radius","dispatched":false,"feedback":[],"log":[],"error":"the server rejected the command: Incorrect argument for command at position 9: gamerule <--[HERE]"}
```
`error` means the server could not parse the command (wrong name or arguments): fix it before retrying.
Some command names are taken over by plugins (EssentialsX answers `/time`, `/kill`, `/give`…); use the
`minecraft:` prefix for the vanilla command (`minecraft:time set day`). Game rules in 26.2 use snake_case names
(`respawn_radius`, `reduced_debug_info`).
Plugin self-management: `claudebridge status` (answers in Russian) shows the API, avatar and tunnel state;
`claudebridge reload` re-reads the config and restarts the API about 2 s after answering (retry requests that fail
in the next 2–3 s). Never run `claudebridge off`: you would lock yourself out until an admin runs `on`.

#### `GET /api/logs?file=latest.log&lines=200&grep=text` and `GET /api/logs/list`
Tail of a file in `logs/` (`lines` 1–5000, case-insensitive `grep`, `.gz` archives work).
```json
{"file":"latest.log","lines":["[20:49:04] [Server thread/INFO]: [ClaudeBridge] Enabling ClaudeBridge v1.4.7", …]}
{"files":[{"file":"latest.log","size":8425,"modified":"2026-11-20T20:49:09.759Z"},{"file":"2026-11-17-16.log.gz", …}]}
```

#### `GET /api/plugins` and `GET /api/plugins/info?name=X`
```json
{"plugins":[{"name":"ClaudeBridge","version":"1.4.7","enabled":true,"description":"…","authors":["Claude"],"website":null,
 "main":"dev.claudebridge.ClaudeBridgePlugin","apiVersion":"26.2","depend":[],"softDepend":[],
 "dataFolder":"plugins/ClaudeBridge","jar":"plugins/ClaudeBridge-1.4.7.jar"}, …]}
```
`plugins/info` returns one plugin plus `dataFiles` (the files in its data folder).

### 4.2 Files (inside the server folder only)

| Endpoint | Parameters |
|---|---|
| `GET /api/files/list` | `path` (default: server root), `depth` 1–5 (default 1), `max` (default 1000) |
| `GET /api/files/read` | `path`, `offset` (bytes, default 0), `limit` (bytes, default and max 1 MB), `encoding` = `auto` / `utf-8` / `base64` |
| `GET /api/files/find` | `path`, `pattern` (glob such as `**/*.yml` or `config.yml`), `max` (default 200) |
| `GET /api/files/hash` | `path`: answers `size`, `sha1`, `sha256`, `sha512` computed on the server |
| `GET /api/files/grep` | `path`, `text` (case-insensitive), optional `pattern` (file glob), `max` (default 200); files over 2 MB are skipped |

```json
{"path":".","entries":[{"path":"banned-ips.json","dir":false,"size":2,"modified":"…"},{"path":"config","dir":true,"size":null,"modified":"…"}],"truncated":false}
{"path":"server.properties","size":1792,"offset":0,"length":200,"truncated":true,"encoding":"utf-8","content":"#Minecraft server properties\n…"}
{"matches":[{"path":"plugins/ClaudeBridge/config.yml","dir":false,"size":8144,"modified":"…"}]}
{"matches":[{"path":"server.properties","line":47,"text":"online-mode=false"}],"truncated":false}
{"path":"plugins/Chunky-Bukkit-1.5.3.jar","size":304616,"sha1":"7ff47ee3afec89a1725e6eef393f373c549eff3b","sha256":"530d2c74…","sha512":"…"}
{"error":"path is outside of the server directory"}                                                      // 403
{"error":"this path holds a ClaudeBridge secret (API token or private key) and is never readable through the API"} // 403
```
Large files: read in pieces with `offset` and `limit` until `truncated` is false. Binary files come back as base64.
**Free tunnels are slow** (localhost.run free plan: about 1 MB per minute, and heavy traffic makes the service throttle the
tunnel and change its address). Do not download big files such as plugin jars to inspect them: use `files/hash` and compare
the checksum with the official release (for example Modrinth `GET https://api.modrinth.com/v2/version_file/<sha1>?algorithm=sha1`),
and download only what you really have to read.
Other plugins' configs may contain passwords: never repeat secrets in chat or in your answers.

#### Changing files (only when the owner enabled `features.file-write`)

Off by default: then every call below answers 403 `file writing is disabled…`. ClaudeBridge's own folder and jar and the
secret files can never be changed. Every change keeps the previous version in `plugins/ClaudeBridge/backups/<time>/<path>`
and answers that path as `backup`; `delete` moves the file there instead of deleting it.

| Endpoint | Body | Answer |
|---|---|---|
| `POST /api/files/replace` | `{path, find, replace, all?=false}` | `{"path":…,"replaced":3,"sha1":…,"backup":"plugins/ClaudeBridge/backups/20261121-040512-337/plugins/AntiSpam/config.yml"}`. `find` must occur exactly once unless `all: true` (422 otherwise). Best for config edits. |
| `POST /api/files/write` | `{path, content, encoding?=utf-8\|base64, expected_sha1?}` | Creates or overwrites the whole file. Pass the `sha1` from `files/hash` as `expected_sha1` to get 409 instead of overwriting a file that changed meanwhile. |
| `POST /api/files/delete` | `{path}` | `{"path":"plugins/ItemStacker.jar","backup":"plugins/ClaudeBridge/backups/…/plugins/ItemStacker.jar","note":"a plugin stays loaded until the server restarts"}` |
| `POST /api/files/move` | `{from, to}` | Moves or renames; 409 if `to` exists. |
| `POST /api/files/restore` | `{backup}` | Puts a backed-up file back (the current one is backed up first). |

How to change something safely:
1. Do it only when the owner asked for that change. Read the file first (`files/read`) and change as little as possible
   (`replace` with a unique `find`, keeping the YAML indentation).
2. Make the plugin use it: most plugins have a reload command (`<plugin> reload` through `POST /api/console`); removed or
   added plugin jars take effect only after a server restart, which the owner does (do not run `stop`/`restart` unless asked).
3. Check the result (read the file again, the plugin's answer, the log) and tell the owner what changed and the `backup` path
   to undo it with `files/restore`.

### 4.3 Databases (read-only)

| Endpoint | Parameters |
|---|---|
| `GET /api/db/connections` | — |
| `GET /api/db/tables` | `sqlite` (file path) or `connection` (name), `columns=true` |
| `POST /api/db/query` | `sqlite` or `connection`, `sql`, `params` (JSON array for `?` placeholders), `max_rows` (default 500, max 5000) |

Exactly one statement starting with `SELECT`, `WITH`, `PRAGMA`, `EXPLAIN`, `SHOW`, `DESCRIBE`, `DESC` or `VALUES` is accepted,
and the connection itself is read-only. H2 databases (`*.mv.db`, e.g. LuckPerms by default) are not supported:
use the plugin's own commands through the console instead (e.g. `lp user <name> info`).
```json
{"configured":[],"sqliteFiles":[{"sqlite":"plugins/TestEconomy/data.db","size":12288}],"note":"H2 databases … are not supported …"}
{"tables":[{"name":"balances","type":"TABLE","columns":["player TEXT","money REAL","last_seen TEXT"]}]}
{"columns":["player","money","last_seen"],"rows":[["player1",150.5,"2026-11-17"]],"rowCount":1,"truncated":false}
{"error":"only read-only queries are allowed (SELECT, WITH, PRAGMA, EXPLAIN, SHOW, DESCRIBE, DESC, VALUES)"}   // 403
{"error":"SQL error: [SQLITE_ERROR] SQL error or missing database (no such column: nonexistent)"}               // 400
```
Use `params` instead of pasting values into SQL:
`{"sqlite":"plugins/TestEconomy/data.db","sql":"SELECT * FROM balances WHERE player = ?","params":["player1"]}`.

### 4.4 Chat and events: `GET /api/chat?since=<id>&limit=100`

Player chat, joins, quits, deaths, advancements and avatar events, oldest first. Commands are not recorded.
Pass the last `latestId` you saw as `since` to get only new entries (`limit` 1–1000). The server keeps the last 1000 entries.
```json
{"latestId":7,"entries":[
 {"id":6,"time":"2026-11-20T20:53:06.429Z","type":"avatar-pickup","who":"Claude","text":"picked up 1 x PORKCHOP"},
 {"id":7,"time":"2026-11-20T20:53:07.429Z","type":"chat","who":"player1","text":"hi Claude, come here"}]}
```
Entry types: `chat`, `join`, `quit`, `death`, `advancement`, `whisper`, `avatar` (joined/left/stepped out of a block),
`avatar-damage`, `avatar-death`, `avatar-pickup`, `avatar-interact` (a player right-clicked the avatar).
Your own `chat` messages appear here too (`who` = the avatar's name): do not answer yourself.

## 5. Rules for the agent (follow them)

1. **Ask the owner before anything destructive or irreversible:** `stop`, `restart`, `op`/`deop`, `ban`, `kick`, `whitelist off`,
   `kill @e`, `/fill` or `/clone` on large areas, `/gamerule` changes, deleting worlds, changing other players' inventories,
   permission changes (`lp …`).
2. **Read before you change.** Check `GET /api/status`, the logs and the relevant config file before running admin commands.
3. **Respect players.** Do not grief, steal from chests that are not yours or attack players unless the owner explicitly asks.
4. **The avatar is visible.** Behave like a polite player; announce what you are doing in chat when players are nearby.
5. **Databases are read-only; files change only through `files/*` write endpoints** when the owner enabled them and asked
   for the change. Never change files through console tricks, and always report the `backup` path.
6. **Everything is logged** in `plugins/ClaudeBridge/audit.log` (time, IP, endpoint and parameters).
7. **Never reveal the token or other secrets** (passwords from config files, keys). Do not send them in chat, URLs or files.
8. **Do not hammer the server.** Poll at most about once per second; one task at a time.

## 6. Avatar endpoint reference

All `POST` endpoints take a JSON body. Endpoints marked *task* wait for the result (`timeout`, `wait`, see 2.2).
While the avatar is not in the world only `spawn`, `despawn`, `state`, `inventory`, `hold`, `equip`, `give` and `chat` work;
every other avatar endpoint answers 409.

### 6.1 Lifecycle and perception

#### `POST /api/avatar/spawn` `{}` | `{"player":"name"}` | `{"x":…, "z":…, "y"?:…, "world"?:"world"}`
Puts the avatar into the world at its last place, next to a player, or at coordinates (without `y`: on the surface).
If it is already in the world it is moved there (`"status":"teleported"`).
```json
{"status":"spawned","state":{…same as GET /api/avatar/state…}}
```

#### `POST /api/avatar/despawn`
Removes it from the world; inventory and position are kept. `{"status":"despawned"}` (or `"not spawned"`).

#### `GET /api/avatar/state`
```json
{"spawned":true,"name":"Claude","world":"world","position":[-12.5,108.0,-0.5],"block":[-13,108,-1],
 "yaw":0.0,"pitch":0.0,"facing":"south","health":20.0,"maxHealth":20.0,"onGround":true,"inWater":false,
 "standingOn":"ORANGE_TERRACOTTA","light":15,"biome":"minecraft:wooded_badlands","time":1951,"day":true,"weather":"clear",
 "held":{"slot":2,"item":{"type":"WOODEN_PICKAXE","amount":1,"durability":"58/59"}},
 "armor":{"boots":null,"leggings":null,"chestplate":{"type":"IRON_CHESTPLATE","amount":1,"durability":"240/240"},"helmet":null},
 "lookingAt":{"type":"OAK_LEAVES","block":[-14,116,0],"face":"NORTH","distance":4.5},
 "task":null,"lastTask":{"status":"ok","broken":true,"block":"OAK_LOG","ticks":60,"collected":["1 x OAK_LOG"],"task":"break OAK_LOG at -13 114 0","finishedAt":"…"},
 "nearbyPlayers":[],
 "nearbyEntities":[{"type":"PIG","uuid":"3abf6315-…","name":null,"position":[-12.5,108.0,-0.5],"distance":4.6,"hostile":false,"health":10.0},
                   {"type":"ITEM","uuid":"…","name":null,"position":[-20.0,112.0,0.0],"distance":3.1,"hostile":false,"item":"5 x COBBLESTONE"}]}
```
While the avatar is not in the world: `{"spawned":false,"name":"Claude","lastLocation":[23.93,78.0,9.5]}`.
`time` is the world time in ticks (0–24000; day is about 0–12000). `nearbyEntities` lists up to 25 entities within 24 blocks, nearest first.

#### `GET /api/avatar/map?radius=10`
Top-down map of the area (`radius` 3–24). `rows[0]` is the north edge, the first character of a row is the west edge.
The character at row `r`, column `c` is the column `x = originTopLeft.x + c`, `z = originTopLeft.z + r`. `@` is you.
```json
{"originTopLeft":{"x":17,"z":3},"centerY":78,"orientation":"rows go north (-Z) to south (+Z), columns west (-X) to east (+X)",
 "facing":"east","rows":["--vvvvvvvvvvv","---vvvvvvvvvv","…","......@.---  ","…"],"legend":{…}}
```
Legend: `@` you, `P` player, `m` hostile mob, `a` other creature, `i` dropped item, `.` walkable at your level (`centerY`),
`+` one block up, `-` one block down, `^` 2–3 up, `v` 2–6 down, `#` wall/solid, `~` water, `L` lava,
`T` tree log, `C` crafting table, `H` chest/barrel, `F` furnace, `D` door/gate, `?` not loaded, space = deep drop.

#### `GET /api/avatar/blocks?radius=3&filter=ORE`
Non-air blocks in a cube around the feet (`radius` 1–5) as `[dx, dy, dz, type]`; the world block is `origin + [dx,dy,dz]`.
`filter` keeps types containing that text. Air is omitted, so a missing offset means air.
```json
{"origin":[23,78,9],"format":"[dx, dy, dz, type] relative to origin (the block at the avatar's feet); air is omitted",
 "blocks":[[-1,-1,0,"TERRACOTTA"],[0,-1,0,"TERRACOTTA"],[1,-1,0,"TERRACOTTA"], …]}
```

#### `GET /api/avatar/find?block=STONE&radius=16&limit=20&exposed=true`
Nearest blocks of a type in a cube of `radius` (1–32) around the avatar, nearest first (`limit` 1–100).
An exact name (`OAK_LOG`) matches only that type; a partial name (`LOG`, `ORE`) matches every type containing it
(force with `contains=true`). `exposed` in each result means at least one side touches air (not buried);
`exposed=true` returns only such blocks. Exposed blocks can still be unreachable (in a cave below you): see 7.4.
```json
{"query":"STONE","radius":16,"exposed":true,"totalFound":99,"nearest":[{"type":"STONE","block":[-13,104,-10],"distance":14.2,"exposed":true}, …]}
```
`totalFound: 0` means nothing in loaded chunks within the radius: walk 30–50 blocks somewhere else and search again.

#### `GET /api/avatar/inventory`
Works also while the avatar is not in the world.
```json
{"heldSlot":2,"slots":[{"type":"OAK_LOG","amount":1,"slot":0},{"type":"WOODEN_PICKAXE","amount":1,"durability":"58/59","slot":2}],
 "freeSlots":34,"armor":{"boots":null,"leggings":null,"chestplate":{"type":"IRON_CHESTPLATE","amount":1,"durability":"240/240"},"helmet":null}}
```

### 6.2 Movement

| Endpoint | Body | Answer |
|---|---|---|
| `POST /api/avatar/goto` *task* | `{x, z}` (any height) or `{x, y, z}` (that exact spot) or `{player}` or `{entity: uuid}`; `distance?` (how close is enough: default 1 for a point, 2 for a player or entity), `timeout?`, `wait?` | `{"status":"ok","arrived":true,"distance":1.0,"position":[-12.85,111.0,0.5],"task":"goto -12.0 0.0 (any height)"}` |
| `POST /api/avatar/follow` | `{player, distance?=2.5}` | `{"status":"started",…}`. Keeps following until `stop`. |
| `POST /api/avatar/stop` | — | `{"status":"stopped"}` or `{"status":"idle"}` |
| `POST /api/avatar/look` | `{yaw, pitch}` or `{x, y?, z}` or `{player}` | `{"yaw":-180.0,"pitch":0.0,"facing":"north"}` |
| `POST /api/avatar/jump` | — | `{"status":"ok"}`, or `failed` with `no room above the head` / `not on the ground` |
| `POST /api/avatar/teleport` | `{x, y, z, world?}` or `{player}` | `{"status":"ok","position":[-19.5,112.0,0.5]}`. Admin move: use it only when walking is impossible or the owner asks. |

`goto` uses pathfinding: walks, climbs 1-block steps, drops up to 3 blocks, opens wooden doors and gates, avoids lava,
does not dig or build. Falling 4+ blocks hurts. Targets more than 2000 blocks away are refused (422): use `teleport`.
`arrived: true` means "within `distance`", not "standing on that block": pass `"distance": 0.5` when the exact block matters
(climbing a step, standing next to a table).
```json
{"status":"failed","reason":"cannot get closer: target is unreachable from here (5.1 blocks left)","task":"goto …"}
{"status":"failed","reason":"no path found (6.1 blocks left)","task":"goto …"}
```

### 6.3 World interaction

#### `POST /api/avatar/break` *task* `{x, y, z, approach?=true}`
Mines one block with the held item using the **real mining time** (hand on oak log: 60 ticks = 3 s; stone pickaxe on stone:
12 ticks). Walks into reach first when `approach` is true. Drops go straight into the inventory.
Blocks that need a tool (stone, ores) drop nothing without the right pickaxe, and every tool loses durability on every
block, so hold the right tool (`hold`) before mining: axe or hand for wood, pickaxe for stone and ores, shovel or hand for dirt.
```json
{"status":"ok","broken":true,"block":"OAK_LOG","ticks":60,"collected":["1 x OAK_LOG"],"task":"break OAK_LOG at -13 115 0"}
{"status":"ok","broken":true,"block":"BROWN_TERRACOTTA","ticks":125,"collected":[],"note":"nothing dropped: BROWN_TERRACOTTA needs a pickaxe (iron ores and harder need a stone pickaxe or better) to drop items","task":"…"}
{"status":"failed","reason":"could not get close to the block: no path found (6.1 blocks left)","task":"break STONE at -12 106 -11"}
{"status":"failed","reason":"there is no block to break at this position","task":"break AIR at -13 106 -7"}
```
`broken: true` means the block is gone; `collected` lists what went into the inventory; `note` explains an empty `collected`.

#### `POST /api/avatar/place` `{x, y, z, slot?}`
Places a block from the held slot (or `slot`) **into the empty cell** `x, y, z`. The cell must be free (air, grass, water…),
within reach, not occupied by an entity, and touch another block. Furnaces and chests face the avatar; stairs face away.
Doors and beds are not supported. After placing your last block of that type the hand is empty: `hold` something else.
```json
{"status":"ok","placed":"CRAFTING_TABLE","block":[-21,114,1]}
{"error":"the position is occupied by COARSE_DIRT"}                                     // 422
{"error":"there is nothing next to this position to attach the block to"}               // 422
{"error":"block is too far (10.0 > 4.5); walk closer first (POST /api/avatar/goto)"}    // 422
```
How to find a free cell next to you: `GET /api/avatar/blocks?radius=1`; a side cell `[dx,0,dz]` (dx/dz = ±1) is free when
`[dx,0,dz]` is missing from the list (air) and `[dx,-1,dz]` is present (ground). Place at `origin + [dx,0,dz]`.

#### `POST /api/avatar/use` `{x, y, z}`
Doors, trapdoors and fence gates: `{"status":"ok","block":"OAK_DOOR","open":true}`. Levers and buttons:
`{"status":"ok","block":"LEVER","powered":true}`. Containers: the same answer as `container`. Crafting table:
`{"status":"ok","hint":"stand within 4 blocks of this table and call POST /api/avatar/craft"}`. Iron doors need redstone (422).

#### Containers: `GET /api/avatar/container?x&y&z`, `POST /api/avatar/container/put`, `POST /api/avatar/container/take`
`put {x, y, z, slot?, amount?, to_slot?}` moves items from your inventory slot `slot` (default: the held slot) into the container
(into `to_slot` if given); `take {x, y, z, slot, amount?}` takes from container slot `slot` into your inventory.
Furnaces: slot 0 = input, 1 = fuel, 2 = result. One `COAL` smelts 8 items, 10 s per item.
```json
{"container":"FURNACE","block":[-21,114,2],"size":3,"slots":[{"type":"RAW_IRON","amount":3,"slot":0},{"type":"COAL","amount":1,"slot":1}],
 "hint":"slot 0 = input, 1 = fuel, 2 = result","furnace":{"burning":false,"fuelTicksLeft":0,"cookTicks":0,"cookTicksTotal":200},"moved":1}
{"container":"FURNACE","block":[-21,114,2],"size":3,"slots":[{"type":"RAW_IRON","amount":3,"slot":0}],
 "hint":"slot 0 = input, 1 = fuel, 2 = result","furnace":{"burning":true,"fuelTicksLeft":1520,"cookTicks":81,"cookTicksTotal":200}}
```
`moved` (put) or `taken` (take) says how many items moved. The furnace starts within a second; the fuel item then leaves
slot 1 and `fuelTicksLeft` shows what is left of it. `cookTicks` counts up to `cookTicksTotal` (20 ticks = 1 s) for the
current item. Results collect in slot 2 (up to 64).

#### `POST /api/avatar/attack` *task* `{type?} | {entity: uuid} | {player}; chase?=true, fight_timeout?=60`
Default target: the nearest hostile mob within 16 blocks (404 if none). With `chase` (default) the avatar runs after the
target, hits it every ~0.6 s until it dies, then walks over the drops. `chase=false` makes one hit if the target is within
reach (a hit right after another hit may do no damage). Damage comes from the held weapon: fist and most items 1,
wooden sword 4, stone sword 5, iron sword 6, diamond sword 7, netherite 8; axes more (wooden axe 7).
```json
{"status":"ok","killed":true,"hits":1,"damageDealt":7.0,"pickedUp":["1 x PORKCHOP"],"task":"attack PIG"}
{"status":"ok","target":{"type":"COW","uuid":"…","name":null,"position":[-13.5,105.0,-6.5],"distance":0.0,"hostile":false,"health":3.0},
 "damage":7.0,"healthBefore":10.0,"healthAfter":3.0,"killed":false}
```
Attack players only when the owner explicitly asks.

#### `POST /api/avatar/chat` `{message, to?}`
Public chat as `<Claude> message`, or a whisper to an online player with `to` (404 if that player is offline).
Messages are cut at 256 characters. `{"status":"sent"}`.

### 6.4 Items

| Endpoint | Body | Answer / notes |
|---|---|---|
| `POST /api/avatar/hold` | `{slot}` or `{item: "STONE_PICKAXE"}` | `{"heldSlot":6,"item":{"type":"STONE_PICKAXE","amount":1,"durability":"131/131"}}`; 404 if you do not have it |
| `POST /api/avatar/drop` | `{slot?, amount?}` | `{"status":"ok","dropped":"1 x TERRACOTTA"}`. Throws items forward (players can pick them up). |
| `POST /api/avatar/equip` | `{slot}` | Wears armor from that slot; answers with the inventory. |
| `POST /api/avatar/craft` | `{item, times?=1}` | Crafts from the inventory. 3×3 recipes need a crafting table within 4 blocks. |
| `POST /api/avatar/give` | `{item, amount?}` | Admin: creates items out of thin air: `{"status":"ok","added":16,"didNotFit":0}`. Use only when the owner allows it. |

```json
{"status":"ok","crafted":"OAK_PLANKS","amount":12,"inventory":{…}}
{"error":"this recipe needs a crafting table within 4 blocks"}                                  // 422
{"error":"not enough ingredients; recipe needs 2 x COBBLESTONE (or similar), 1 x STICK"}        // 422
```
`times` repeats the recipe: `OAK_PLANKS` with `times: 3` uses 3 logs and gives 12 planks.

## 7. Playbooks

Short notation below: `craft {"item":"STICK"}` means `POST /api/avatar/craft` with the body `{"item":"STICK"}`.

### 7.1 First contact
1. `GET /api/status`: note TPS, online players, `avatarSpawned`.
2. `GET /api/chat?since=0`: what happened recently; remember `latestId`.
3. If players are online and the owner wants you to play: `POST /api/avatar/spawn {"player":"<name>"}`, then
   `POST /api/avatar/chat {"message":"Hi! I'm Claude, an AI. What should I do?"}`.

### 7.2 The play loop (repeat)
1. **Observe:** `GET /api/avatar/state` (health, task, lastTask, nearby entities), `GET /api/chat?since=<latestId>` (requests
   from players), and when navigating `GET /api/avatar/map`.
2. **Decide** one small step.
3. **Act** with one call; read `status` and `reason`.
4. On `failed`, change the plan (another tree, another route, gather the missing ingredient) instead of repeating the same call.
   After a `failed` move the avatar may have walked part of the way: read `state` again before the next action.

### 7.3 Early survival (verified sequence)
1. Logs: `GET /api/avatar/find?block=LOG&radius=24`. If `totalFound` is 0, `goto` 30–50 blocks in some direction and search
   again (trees are on the surface; look for `T` in `map`). `hold {"item":"WOODEN_AXE"}` if you have one, otherwise
   `hold {"slot": <an empty slot>}` to use the hand, then `break` the nearest logs (it walks there by itself). Get 4+ logs.
2. `craft {"item":"OAK_PLANKS","times":3}` (use the planks of your log type: `BIRCH_LOG` → `BIRCH_PLANKS`),
   `craft {"item":"CRAFTING_TABLE"}`, `craft {"item":"STICK"}`.
3. `hold {"item":"CRAFTING_TABLE"}`, `place` it on a free cell next to you (6.3 explains how to find one).
4. Within 4 blocks of the table: `craft {"item":"WOODEN_PICKAXE"}` (3 planks + 2 sticks), `hold {"item":"WOODEN_PICKAXE"}`.
5. Stone: `GET /api/avatar/find?block=STONE&radius=16&exposed=true`, `break` it with the pickaxe (gives `COBBLESTONE`).
   If it fails with `no path found`, see 7.4. Craft `STONE_PICKAXE` (3 cobblestone + 2 sticks), `STONE_SWORD`
   (2 cobblestone + 1 stick), `FURNACE` (8 cobblestone).
6. Ores: `GET /api/avatar/find?block=ORE&exposed=true` (or `IRON_ORE`, `COAL_ORE`), mine with a stone pickaxe.
   Place the furnace and stay within 4.5 blocks of it: `container/put` `RAW_IRON` with `to_slot: 0` and `COAL` with
   `to_slot: 1` (the `slot` numbers come from `GET /api/avatar/inventory`), wait 10 s per item (watch `furnace.cookTicks`),
   then `container/take {"x":…,"y":…,"z":…,"slot":2}`.
7. Food items: `attack {"type":"COW"}` or `{"type":"PIG"}` (the avatar does not need to eat).

### 7.4 Reaching blocks below or behind terrain (digging)
`find` sees blocks through terrain, but `break` and `goto` only walk. Dig your own way, and dig **stairs, not a shaft**:
`goto` climbs only 1-block steps, so from a straight hole 2+ blocks deep it cannot walk out again.

Let your feet block be `(X, Y, Z)` (`state.block`) and pick a direction `(dx, dz)` (e.g. `(1, 0)` = east).
The next column is `(X+dx, Z+dz)`; its centre is `(X+dx+0.5, Z+dz+0.5)`. Before every step check
`GET /api/avatar/blocks?radius=2` for `LAVA`/`WATER` near the blocks you are about to open; stop if you see any.

- **One step down:** `break` `(X+dx, Y+1, Z+dz)`, `(X+dx, Y, Z+dz)` and `(X+dx, Y-1, Z+dz)`, then
  `goto {"x": X+dx+0.5, "y": Y-1, "z": Z+dz+0.5, "distance": 0.5}`.
- **One step up:** `break` `(X, Y+2, Z)` (above your head), `(X+dx, Y+1, Z+dz)` and `(X+dx, Y+2, Z+dz)`, then
  `goto {"x": X+dx+0.5, "y": Y+1, "z": Z+dz+0.5, "distance": 0.5}`. The block `(X+dx, Y, Z+dz)` stays: it is the step.
- `failed: there is no block to break at this position` is fine: that cell was already open.
- When the target is within 4.5 blocks of your eyes, `break` it (line of sight is not needed).
- Through a wall at your level: `break` the feet-level and head-level blocks in front of you and walk in.

If the avatar is stuck in a hole anyway (`goto` → `no path found` in every direction), dig a staircase up as above, or ask
the owner whether you may `teleport` out.

### 7.5 Being a good companion
- Watch chat for your name; answer with `chat`. Use `follow {"player":"…"}` when asked to come along, `stop` when asked to wait.
- Before mining near players' builds, ask. Never break blocks in someone's base without permission.

### 7.6 Admin checks
- Lag: `GET /api/status` (TPS/MSPT, entity and chunk counts per world), then `GET /api/logs?grep=Can%27t%20keep%20up`.
- Errors: `GET /api/logs?grep=ERROR&lines=100`, `GET /api/logs?grep=Exception`; older days: `GET /api/logs/list`, then `file=<name>.log.gz`.
- Plugin configs: `GET /api/plugins` → `GET /api/plugins/info?name=X` → `GET /api/files/read?path=plugins/X/config.yml`.
- Player data: `GET /api/db/connections` → `GET /api/db/tables?sqlite=…&columns=true` → `POST /api/db/query`.
- Permissions (LuckPerms keeps them in H2): read-only console commands such as `lp group default permission info`
  or `lp user <name> permission check <node>`.

## 8. Known limitations

- The avatar is not in the Tab list and players cannot target it with `@p`-style selectors meant for players.
- Avatar block changes do not fire `BlockBreakEvent`/`BlockPlaceEvent`, so land-protection plugins do not see them.
- No placing of doors and beds yet; levers/buttons use simplified redstone updates.
- Mobs far from real players are frozen by the server's "entity activation range": they do not move until a player comes near.
  The avatar itself has its own gravity and walking, so it still works alone.
- Pathfinding explores up to 3000 nodes per plan, only through loaded chunks, and never digs or builds; far goals are
  reached in segments. For goals more than 2000 blocks away use `teleport`.
- `follow` never ends by itself; always `stop` it.
- Secret files of the plugin (token, private keys) cannot be read (403); ClaudeBridge's own folder and jar cannot be changed.
- There is no command to unload a plugin: a removed jar keeps working until the next server restart.

## 9. Troubleshooting

| Symptom | Fix |
|---|---|
| DNS error, connection refused or HTML instead of JSON | The tunnel address changed: ask the owner for the current one (2.4). |
| `401` | Token missing or wrong: stop and ask the owner. |
| `400 missing parameter` on a GET | Parameters of GET endpoints go in the query string, not in a body. |
| `409 avatar is not in the world` | `POST /api/avatar/spawn` (after a restart wait ~15 s first). |
| `422 block is too far` | `goto` closer first (`break` does it by itself). |
| `goto`/`break` → `no path found` / `unreachable` | Look at `map`; choose another spot or block; dig down or through (7.4). |
| `find` → `totalFound: 0` | Walk 30–50 blocks elsewhere and search again; use a partial name (`LOG`, `ORE`). |
| `place` → `occupied` / `nothing to attach` | Choose a free cell next to a solid block (6.3). |
| `place` → `slot N is empty` | The hand is empty: `hold` the block first. |
| `craft` → `needs a crafting table` | Place one and stand within 4 blocks. |
| `craft` → `not enough ingredients` | Gather what the message lists (`(or similar)` means any wood type works). |
| `attack` → `the fight took too long` | The target ran away; try again or pick another target. |
| Task returns `running` | Poll `GET /api/avatar/state` until `task` is `null`, then read `lastTask`. |
| Chat shows `avatar-death` | `spawn {}` within 5 minutes to get back to the items (or next to a player if it died in lava). |
| `503` | The owner switched the API off. |
