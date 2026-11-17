# ClaudeBridge: guide for AI agents

> **Для человека.** Это инструкция для нейросетей (на английском, чтобы её понимала любая модель).
> Передайте её ИИ-агенту вместе с адресом API. Токен передавайте только через защищённый канал
> (например, форму секретов вашего агента), не вставляйте его в чат.

You are connected to a Minecraft Java Edition server (Purpur/Paper **26.2**) through the **ClaudeBridge** plugin.
The plugin exposes a JSON-over-HTTPS API that lets you:

1. **Administer the server**: run console commands, read logs, inspect plugins, browse files, query databases (read-only).
2. **Play the game** through an **avatar**: a player-shaped entity (named `Claude` by default) that walks with pathfinding,
   mines with real mining times, places blocks, uses doors/levers/chests/furnaces, crafts, fights and chats.

Everything below was verified against a real Purpur 26.2 server; example responses are real (shortened with `…`).

---

## 1. Mental model

- **The avatar is not a real player client.** It is a server-side `Mannequin` entity controlled only through this API.
  Players see it walk, swing, mine, place blocks, hold items, wear armor and talk in chat.
  It is **not** in the player list (Tab), has **no hunger, XP or advancements**, cannot sprint, swim properly or use elytra,
  and you **do not see a rendered picture**: you perceive the world through JSON (`state`, `map`, `blocks`, `find`, `chat`).
- **One avatar per server.** It keeps its inventory and position across restarts (`plugins/ClaudeBridge/avatar.yml`).
- **Long actions are tasks** (walk, follow, mine, fight). Only one task runs at a time; starting a new one cancels the old one.
- **Admin access is real.** Console commands run with full server permissions. Be careful (see section 4).

## 2. Connecting

| Item | Value |
|---|---|
| Base URL | Given by the server owner, for example `https://bridge.example.com` (Let's Encrypt, port 443). Local setups may use `http://127.0.0.1:8765`. |
| Auth | Every request: header `Authorization: Bearer <token>`. The token is in `plugins/ClaudeBridge/token.txt` on the server. |
| Requests | `GET` with query parameters, `POST` with a JSON body (`Content-Type: application/json`). Any parameter may be sent either way. |
| Responses | JSON. Errors are `{"error": "message"}` with an HTTP status code. |
| Start here | `GET /api` lists every endpoint with a one-line description; `GET /api/status` checks the server. |

```bash
curl -s -H "Authorization: Bearer $TOKEN" https://bridge.example.com/api/status
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d '{"command":"list"}' https://bridge.example.com/api/console
```

If the owner uses the **self-signed** mode behind their own reverse proxy, you talk to the proxy and TLS is handled there.
If you connect to a self-signed listener directly, trust the server's `plugins/ClaudeBridge/tls-cert.pem` and use the
host name `claudebridge.local`, e.g. `curl --cacert tls-cert.pem --resolve claudebridge.local:PORT:IP https://claudebridge.local:PORT/api`.
Never disable certificate verification and never print, log or chat the token.

### HTTP status codes

| Code | Meaning | What to do |
|---|---|---|
| 200 | Done. For tasks, also read the `status` field: `ok`, `failed`, `running`, `started`, `cancelled`. | |
| 400 | Bad or missing parameter. | Fix the request (`GET /api` shows parameters). |
| 401 | Missing or wrong token. | Stop; ask the owner. Do not retry in a loop. |
| 403 | Forbidden: feature switched off in `config.yml`, path outside the server folder, write attempt on a database, or your IP is not allowed. | Do not try to bypass it. |
| 404 | Not found: offline player, unknown item, no target, missing file. | |
| 405 | Wrong HTTP method for this path. | |
| 409 | The avatar is not in the world. | `POST /api/avatar/spawn`. |
| 422 | Valid request but impossible right now: too far, occupied, not enough ingredients, chunk not loaded… | Read the message: it says what to do. |
| 500 | Unexpected server-side error. | Report the message to the owner. |
| 503 | The API was switched off by an admin (`/claudebridge off`). | Wait for the owner. |
| 504 | The server main thread did not answer in 20 s (lag or crash). | Check `GET /api/status` later. |

## 3. Conventions

- **Axes:** `x` east (+) / west (−), `y` up, `z` south (+) / north (−).
  Block coordinates are integers (the floor of a position). Entity positions are decimals (feet position).
- **Rotation:** `yaw` 0 = south, 90 = west, 180 = north, −90 = east; `pitch` −90 = up, 90 = down.
- **Items and blocks** use Bukkit material names, case-insensitive: `OAK_LOG`, `COBBLESTONE`, `RAW_IRON`, `IRON_INGOT`,
  `CRAFTING_TABLE`, `FURNACE`, `WOODEN_PICKAXE`, `STONE_SWORD`…
- **Inventory:** 36 slots (`0`–`35`), `heldSlot` is the item in hand; armor is separate (`boots`, `leggings`, `chestplate`, `helmet`).
- **Reach:** blocks within **4.5** of the avatar's eyes (eyes are 1.62 above the feet); melee range about **3**.
- **Tasks:** `goto`, `follow`, `break`, `attack` wait up to `timeout` seconds (default 30, max 120) and then return the result.
  If the reply is `{"status":"running"}` the task is still going: poll `GET /api/avatar/state` (field `task`) or cancel with
  `POST /api/avatar/stop`. Pass `"wait": false` to return immediately. A new task cancels the running one
  (`{"status":"cancelled","reason":"replaced by …"}`).
- **Chunks:** the avatar keeps the 3×3 chunks around itself loaded. Pathfinding only walks through loaded chunks,
  so very long trips happen in segments automatically.

## 4. Rules for the agent (follow them)

1. **Ask the owner before anything destructive or irreversible:** `stop`, `restart`, `op`/`deop`, `ban`, `kick`, `whitelist off`,
   `kill @e`, `/fill` or `/clone` on large areas, `/gamerule` changes, deleting worlds, changing other players' inventories.
2. **Read before you change.** Check `GET /api/status`, the logs and the relevant config file before running admin commands.
3. **Respect players.** Do not grief, steal from chests that are not yours or attack players unless the owner explicitly asks.
4. **The avatar is visible.** Behave like a polite player; announce what you are doing in chat when players are nearby.
5. **Files and databases are read-only by design.** Do not try to write through console tricks unless the owner asks for that change.
6. **Everything is logged** in `plugins/ClaudeBridge/audit.log` (time, IP, endpoint and parameters).
7. **Never reveal the token.** Do not send it in chat, URLs or files.

## 5. Endpoint reference

### 5.1 Server administration

#### `GET /api/status`
Server version, TPS, MSPT, memory, players, worlds and HTTPS state.
```json
{"name":"Purpur","version":"26.2-2633-3f5d9c0 (MC: 26.2)","minecraftVersion":"26.2","tps":[20.0,20.01,20.01],
 "mspt":0.58,"memory":{"usedMB":710,"allocatedMB":1024,"maxMB":2048},"uptimeSeconds":58,"onlineMode":false,
 "players":[{"name":"player1","uuid":"…","world":"world","x":10.5,"y":64.0,"z":-3.2,"health":20.0,"food":20,
 "level":0,"gameMode":"SURVIVAL","ping":40,"op":false}],
 "worlds":[{"name":"world","environment":"NORMAL","players":0,"entities":12,"loadedChunks":81,"time":472,
 "storm":false,"thundering":false,"difficulty":"EASY","spawn":[0,87,0]}, …],
 "plugins":1,"avatarSpawned":true,
 "tls":{"mode":"acme","state":"Let's Encrypt certificate for bridge.example.com, valid until …","notAfter":"…"}}
```
TPS 20 is perfect; below ~18 means lag. MSPT above 50 means the server cannot keep up.

#### `POST /api/console` `{command, console?, wait_ms?}`
Runs a console command (leading `/` optional). `feedback` holds the command's answer; `log` holds new log lines
written during `wait_ms` (default 500 ms). `console: true` runs it as the real console (output then appears only in `log`).
```json
{"command":"list","dispatched":true,"feedback":["There are 0 of a max of 20 players online: "],"log":[]}
{"command":"time set day","dispatched":true,"feedback":["Set minecraft:overworld to time marker minecraft:day"],"log":[]}
{"command":"nosuchcommand","dispatched":false,"feedback":[],"log":[],"note":"the command does not exist or reported failure"}
```

#### `GET /api/logs?file=latest.log&lines=200&grep=text`
Tail of a log file (case-insensitive `grep` filter; `.gz` archives work too). `GET /api/logs/list` lists the files.
```json
{"file":"latest.log","lines":["[10:17:45] [Server thread/INFO]: [ClaudeBridge] Enabling ClaudeBridge v1.2.0", …]}
```

#### `GET /api/plugins` and `GET /api/plugins/info?name=X`
```json
{"plugins":[{"name":"ClaudeBridge","version":"1.2.0","enabled":true,"description":"…","authors":["Claude"],
 "main":"dev.claudebridge.ClaudeBridgePlugin","apiVersion":"26.2","depend":[],"softDepend":[],
 "dataFolder":"plugins/ClaudeBridge","jar":"plugins/ClaudeBridge-1.2.0.jar"}]}
```
`plugins/info` additionally returns `dataFiles` (the plugin's config and data files).

### 5.2 Files (read-only, inside the server folder only)

| Endpoint | Parameters |
|---|---|
| `GET /api/files/list` | `path` (default: server root), `depth` 1–5, `max` |
| `GET /api/files/read` | `path`, `offset`, `limit`, `encoding` = `auto` / `utf-8` / `base64` (binary files come back as base64) |
| `GET /api/files/find` | `path`, `pattern` (glob, e.g. `**/*.yml`), `max` |
| `GET /api/files/grep` | `path`, `text`, optional `pattern` (file glob), `max` |

```json
{"path":"server.properties","size":1792,"offset":0,"length":200,"truncated":true,"encoding":"utf-8","content":"#Minecraft server properties\n…"}
{"matches":[{"path":"server.properties","line":47,"text":"online-mode=false"}]}
{"error":"path is outside of the server directory"}            // HTTP 403 for ../ or absolute paths outside
```
Large files: read in pieces with `offset` and `limit` until `truncated` is false.

### 5.3 Databases (read-only)

| Endpoint | Parameters |
|---|---|
| `GET /api/db/connections` | — (configured MySQL/MariaDB connections and all SQLite files found) |
| `GET /api/db/tables` | `sqlite` (file path) or `connection` (name), `columns=true` |
| `POST /api/db/query` | `sqlite` or `connection`, `sql`, `params` (array for `?` placeholders), `max_rows` |

Only one statement starting with `SELECT`, `WITH`, `PRAGMA`, `EXPLAIN`, `SHOW`, `DESCRIBE` is accepted, and the
connection itself is read-only. H2 databases (`*.mv.db`) are not supported.
```json
{"tables":[{"name":"balances","type":"TABLE","columns":["player TEXT","money REAL","last_seen TEXT"]}]}
{"columns":["player","money"],"rows":[["admin",99999.0],["player1",150.5]],"rowCount":2,"truncated":false}
{"error":"only read-only queries are allowed (SELECT, WITH, PRAGMA, EXPLAIN, SHOW, DESCRIBE)"}   // 403
{"error":"write blocked: the database connection is read-only"}                                   // 403
{"error":"SQL error: [SQLITE_ERROR] SQL error or missing database (no such column: nonexistent)"} // 400
```
Use `params` instead of pasting values into SQL:
`{"sqlite":"plugins/X/data.db","sql":"SELECT * FROM t WHERE player = ?","params":["player1"]}`.

### 5.4 Chat and events

#### `GET /api/chat?since=<id>&limit=100`
Player chat, joins, quits, deaths, advancements and avatar events (damage, pickups, death). Commands are not recorded.
Remember `latestId` and pass it as `since` next time to get only new entries.
```json
{"latestId":4,"entries":[
 {"id":3,"time":"2026-11-17T10:37:20Z","type":"chat","who":"player1","text":"hi Claude, come here"},
 {"id":4,"time":"2026-11-17T10:37:22Z","type":"avatar-damage","who":"Claude","text":"took 7.0 damage from FALL (health 13.0)"}]}
```
Entry types: `chat`, `join`, `quit`, `death`, `advancement`, `whisper`, `avatar`, `avatar-damage`, `avatar-death`,
`avatar-pickup`, `avatar-interact` (a player right-clicked the avatar).

### 5.5 Avatar: lifecycle and perception

#### `POST /api/avatar/spawn` `{player?} | {x, z, y?, world?} | {}`
Puts the avatar into the world next to a player, at coordinates, or at its last saved place. If it is already
in the world, it is moved there. Positions are snapped onto the ground.

#### `POST /api/avatar/despawn`
Removes it from the world; inventory and position are kept.

#### `GET /api/avatar/state`
```json
{"spawned":true,"name":"Claude","world":"world","position":[-12.5,108.0,-0.5],"block":[-13,108,-1],
 "yaw":0.0,"pitch":0.0,"facing":"south","health":20.0,"maxHealth":20.0,"onGround":true,"inWater":false,
 "standingOn":"ORANGE_TERRACOTTA","light":15,"biome":"minecraft:wooded_badlands","time":1951,"day":true,"weather":"clear",
 "held":{"slot":2,"item":{"type":"WOODEN_PICKAXE","amount":1,"durability":"58/59"}},
 "armor":{"boots":null,"leggings":null,"chestplate":{"type":"IRON_CHESTPLATE","amount":1,"durability":"240/240"},"helmet":null},
 "lookingAt":{"type":"OAK_LEAVES","block":[-14,116,0],"face":"NORTH","distance":4.5},
 "task":null,
 "nearbyPlayers":[],
 "nearbyEntities":[{"type":"PIG","uuid":"3abf6315-…","name":null,"position":[-12.5,108.0,-0.5],"distance":4.6,"hostile":false,"health":10.0}]}
```

#### `GET /api/avatar/map?radius=10`
Top-down ASCII map (rows north→south, columns west→east), `@` is the avatar. Use it to plan routes.
```
###########^@-vvvvvv
###########^.-vvvvvv
###########^..-vvvv
```
Legend: `@` you, `P` player, `m` hostile mob, `a` other creature, `i` dropped item, `.` walkable at your level,
`+` one block up, `-` one block down, `^` 2+ up, `v` 2–6 down (cliff edge), `#` wall/solid, `~` water, `L` lava,
`T` tree log, `C` crafting table, `H` chest/barrel, `F` furnace, `D` door/gate, `?` not loaded, space = deep drop.
The response also has `originTopLeft` (world x/z of the top-left character) and `centerY`.

#### `GET /api/avatar/blocks?radius=3&filter=ORE`
Non-air blocks around the feet as `[dx, dy, dz, type]` relative to `origin`.
```json
{"origin":[0,87,0],"blocks":[[-1,1,-1,"TERRACOTTA"],[0,-1,0,"TERRACOTTA"], …]}
```

#### `GET /api/avatar/find?block=IRON_ORE&radius=16` (radius ≤ 32)
Nearest blocks of a type. A partial name also works (`block=LOG`, `block=ORE`, `contains=true`).
```json
{"query":"OAK_LOG","radius":8,"totalFound":23,"nearest":[{"type":"OAK_LOG","block":[-13,114,0],"distance":5.1}, …]}
```

#### `GET /api/avatar/inventory`
```json
{"heldSlot":2,"slots":[{"type":"OAK_LOG","amount":1,"slot":0},{"type":"WOODEN_PICKAXE","amount":1,"durability":"58/59","slot":2}],
 "freeSlots":31,"armor":{"boots":null,"leggings":null,"chestplate":{"type":"IRON_CHESTPLATE","amount":1},"helmet":null}}
```

### 5.6 Avatar: movement

| Endpoint | Body | Notes |
|---|---|---|
| `POST /api/avatar/goto` | `{x, z}` (any height), `{x, y, z}`, `{player}` or `{entity: uuid}`; `distance?`, `timeout?`, `wait?` | Pathfinding: walks, climbs 1-block steps, drops up to 3, opens wooden doors and gates, avoids lava. |
| `POST /api/avatar/follow` | `{player, distance?=2.5}` | Keeps following until `stop`. Returns immediately with `{"status":"started"}`. |
| `POST /api/avatar/stop` | — | Cancels the current task. |
| `POST /api/avatar/look` | `{yaw, pitch}` / `{x, y?, z}` / `{player}` | |
| `POST /api/avatar/jump` | — | Fails if there is no room above the head. |
| `POST /api/avatar/teleport` | `{x, y, z, world?}` / `{player}` | Instant (admin); snaps onto the ground. |

```json
{"status":"ok","arrived":true,"distance":1.0,"position":[7.5,84.0,-0.85],"task":"goto 8.0 0.0 (any height)"}
{"status":"failed","reason":"cannot get closer: target is unreachable from here (5.1 blocks left)","task":"goto …"}
```
Without `y`, `goto` ignores height (best for "walk over there"). With `y`, it targets that exact spot. Falling 4+ blocks hurts.

### 5.7 Avatar: world interaction

#### `POST /api/avatar/break` `{x, y, z, approach?=true}`
Mines one block with the held item using the **real mining time** (hand on oak log: 60 ticks = 3 s; wooden pickaxe on
terracotta: 19 ticks). Walks within reach first when `approach` is true. Drops go straight into the inventory.
Blocks that need a tool (stone, ores) drop nothing without the right pickaxe. Tools lose durability.
```json
{"status":"ok","broken":true,"block":"OAK_LOG","ticks":60,"collected":["1 x OAK_LOG"],"task":"break OAK_LOG at -13 114 0"}
```

#### `POST /api/avatar/place` `{x, y, z, slot?}`
Places a block from the held slot (or `slot`). The target must be free (air, grass, water…) and touch another block.
Furnaces and chests face the avatar; stairs face away. Doors and beds are not supported.
```json
{"status":"ok","placed":"CRAFTING_TABLE","block":[-15,112,-3]}
{"error":"the position is occupied by CRAFTING_TABLE"}    // 422
```

#### `POST /api/avatar/use` `{x, y, z}`
Toggles doors, trapdoors and fence gates, flips levers, presses buttons, or returns a container's content.
Iron doors need redstone.

#### Containers: `GET /api/avatar/container?x&y&z`, `POST /api/avatar/container/put`, `POST /api/avatar/container/take`
`put {x, y, z, slot?, amount?, to_slot?}` moves from the inventory into the container; `take {x, y, z, slot, amount?}` takes out.
Furnaces: slot 0 = input, 1 = fuel, 2 = result. Smelting takes 10 s per item.
```json
{"container":"FURNACE","block":[-14,109,-2],"size":3,"slots":[{"type":"RAW_IRON","amount":1,"slot":0},{"type":"IRON_INGOT","amount":1,"slot":2}],
 "hint":"slot 0 = input, 1 = fuel, 2 = result"}
```

#### `POST /api/avatar/attack` `{type?} | {entity: uuid} | {player}; chase?=true, fight_timeout?=60`
Default target: nearest hostile mob within 16 blocks. With `chase` (default) the avatar runs after the target, hits it
every ~0.6 s until it dies, then walks over the drops. `chase=false` makes one hit if the target is within reach.
Damage comes from the held weapon (fist 1, wooden sword 4, stone sword 5, iron sword 6, diamond sword 7, netherite 8; axes more).
```json
{"status":"ok","killed":true,"hits":7,"damageDealt":7.0,"pickedUp":["3 x PORKCHOP"],"task":"attack PIG"}
```
Attack players only when the owner explicitly asks.

#### `POST /api/avatar/chat` `{message, to?}`
Public chat as `<Claude> message`, or a whisper to one player with `to`.

### 5.8 Avatar: items

| Endpoint | Body | Notes |
|---|---|---|
| `POST /api/avatar/hold` | `{slot}` or `{item: "STONE_PICKAXE"}` | Choose the item in hand. |
| `POST /api/avatar/drop` | `{slot?, amount?}` | Throws items forward (players can pick them up). |
| `POST /api/avatar/equip` | `{slot}` | Wear armor from the inventory. |
| `POST /api/avatar/craft` | `{item, times?=1}` | Crafts from the inventory. 3×3 recipes need a crafting table within 4 blocks. |
| `POST /api/avatar/give` | `{item, amount?}` | Admin: creates items out of thin air. Use only when the owner allows it. |

```json
{"status":"ok","crafted":"OAK_PLANKS","amount":12,"inventory":{…}}
{"error":"this recipe needs a crafting table within 4 blocks"}                                  // 422
{"error":"not enough ingredients; recipe needs 3 x OAK_PLANKS (or similar), 2 x STICK"}         // 422
```

## 6. Playbooks

### 6.1 First contact
1. `GET /api/status`: note TPS, online players, `avatarSpawned`.
2. `GET /api/chat?since=0`: what happened recently; remember `latestId`.
3. If players are online and the owner wants you to play: `POST /api/avatar/spawn {"player":"<name>"}`, then
   `POST /api/avatar/chat {"message":"Hi! I'm Claude, an AI. What should I do?"}`.

### 6.2 The play loop (repeat)
1. **Observe:** `GET /api/avatar/state` (health, task, nearby entities), `GET /api/chat?since=<latestId>` (requests from players),
   and when navigating `GET /api/avatar/map`.
2. **Decide** one small step.
3. **Act** with one call; read `status` and `reason`.
4. On `failed`, change the plan (another tree, another route, gather the missing ingredient) instead of repeating the same call.

### 6.3 Early survival (verified sequence)
1. `find {"block":"LOG","radius":24}` → `break` the nearest logs (auto-approach). Get 4+ logs.
2. `craft {"item":"OAK_PLANKS","times":3}` (use the plank type that matches your log),
   `craft {"item":"CRAFTING_TABLE"}`, `craft {"item":"STICK"}`.
3. `hold {"item":"CRAFTING_TABLE"}`, `place` it on a free spot next to you (air with a solid block below).
4. Within 4 blocks of the table: `craft {"item":"WOODEN_PICKAXE"}`, `hold {"item":"WOODEN_PICKAXE"}`.
5. Mine stone (`find {"block":"STONE"}`) → `COBBLESTONE` → `STONE_PICKAXE`, `STONE_SWORD`, `FURNACE` (8 cobblestone).
6. Find `COAL_ORE` / `IRON_ORE` (`find {"block":"ORE","contains":true}`), mine with a stone pickaxe, smelt `RAW_IRON` in the furnace
   with `COAL` (`container/put` to slots 0 and 1, wait 10 s per item, `container/take` slot 2).
7. Kill animals with `attack {"type":"COW"}` for food items (the avatar does not need to eat).

### 6.4 Being a good companion
- Watch chat for your name; answer with `chat`. Use `follow {"player":"…"}` when asked to come along, `stop` when asked to wait.
- Before mining near players' builds, ask. Never break blocks in someone's base without permission.

### 6.5 Admin checks
- Lag: `GET /api/status` (TPS/MSPT, entity and chunk counts per world), then `GET /api/logs?grep=Can't keep up`.
- Errors: `GET /api/logs?grep=ERROR&lines=100`, `GET /api/logs?grep=Exception`.
- Plugin configs: `GET /api/plugins` → `GET /api/plugins/info?name=X` → `GET /api/files/read?path=plugins/X/config.yml`.
- Player data: `GET /api/db/connections` → `GET /api/db/tables?sqlite=…&columns=true` → `POST /api/db/query`.

## 7. Known limitations

- The avatar is not in the Tab list and players cannot target it with `@p`-style selectors meant for players.
- Avatar block changes do not fire `BlockBreakEvent`/`BlockPlaceEvent`, so land-protection plugins do not see them.
- No placing of doors and beds yet; levers/buttons use simplified redstone updates.
- Mobs far from real players are frozen by the server's "entity activation range": they do not move until a player comes near.
  The avatar itself has its own gravity and walking, so it still works alone.
- Pathfinding explores up to 3000 nodes per plan and only through loaded chunks; far goals are reached in segments.
  For goals more than 2000 blocks away use `teleport`.
- `follow` never ends by itself; always `stop` it.

## 8. Troubleshooting

| Symptom | Fix |
|---|---|
| `409 avatar is not in the world` | `POST /api/avatar/spawn` |
| `422 block is too far` | `break` with `approach=true` (default) or `goto` closer first |
| `goto` → `no path found` / `unreachable` | Look at `map`; choose another spot; maybe dig through (`break`) or place blocks to bridge |
| `place` → `nothing to attach the block to` | Choose a spot next to a solid block |
| `craft` → `needs a crafting table` | Place one and stand within 4 blocks |
| `attack` → `the fight took too long` | The target ran away; try again or pick another target |
| Task returns `running` | Poll `GET /api/avatar/state` (`task`), or `POST /api/avatar/stop` |
| `401` | Token missing or wrong: stop and ask the owner |
| `503` | The owner switched the API off |
