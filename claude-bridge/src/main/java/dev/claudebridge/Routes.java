package dev.claudebridge;

import java.util.LinkedHashMap;
import java.util.Map;

/** All API endpoints. Parameters can be sent as a JSON body (POST) or as query parameters. */
final class Routes {

    private Routes() {
    }

    static void register(ClaudeBridgePlugin plugin, ApiServer api) {
        ServerService server = new ServerService(plugin);
        FileService files = new FileService(plugin);
        DbService db = new DbService(plugin, files);
        Avatar avatar = plugin.avatar();

        api.route("GET", "/api", "This list of endpoints", r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("plugin", "ClaudeBridge " + plugin.getPluginMeta().getVersion());
            m.put("auth", "Authorization: Bearer <token from plugins/ClaudeBridge/token.txt>");
            m.put("params", "POST: JSON body; GET: query string");
            m.put("endpoints", api.describeRoutes());
            return m;
        });

        // --- server administration
        api.route("GET", "/api/status", "Server version, TPS/MSPT, memory, online players, worlds", r -> server.status());
        api.route("POST", "/api/console", "Run a console command. {command, console?: run as the real console (output only in log), wait_ms?: 500}. Returns feedback and new log lines", server::console);
        api.route("GET", "/api/logs", "Tail a log file. ?file=latest.log&lines=200&grep=text (.gz archives supported)", server::logs);
        api.route("GET", "/api/logs/list", "Log files in logs/", r -> server.logList());
        api.route("GET", "/api/plugins", "Installed plugins with versions, authors, dependencies and jar files", r -> server.plugins());
        api.route("GET", "/api/plugins/info", "?name=Plugin: plugin details and its data folder files", server::pluginInfo);

        // --- files (read-only)
        api.route("GET", "/api/files/list", "?path=plugins&depth=1&max=1000: directory listing", files::list);
        api.route("GET", "/api/files/read", "?path=server.properties&offset=0&limit=...&encoding=auto|utf-8|base64", files::read);
        api.route("GET", "/api/files/hash", "?path=plugins/X.jar: size, SHA-1, SHA-256 and SHA-512 of a file (check a jar against its official release without downloading it)", files::hash);
        api.route("GET", "/api/files/find", "?path=plugins&pattern=**/*.yml: find files by glob", files::find);
        api.route("GET", "/api/files/grep", "?path=plugins&text=...&pattern=*.yml: search text in files", files::grep);

        // --- databases (read-only)
        api.route("GET", "/api/db/connections", "Configured connections and SQLite files found on the server", r -> db.connections());
        api.route("GET", "/api/db/tables", "?sqlite=path.db or ?connection=name, &columns=true", db::tables);
        api.route("POST", "/api/db/query", "{sqlite|connection, sql, params?: [...], max_rows?}: one read-only statement", db::query);

        // --- chat and events
        api.route("GET", "/api/chat", "?since=<id>&limit=100: chat, joins, deaths, avatar events", r ->
                plugin.chat().since(r.lng("since", 0), Math.max(1, Math.min(r.integer("limit", 100), 1000))));

        // --- avatar: lifecycle and perception
        api.route("POST", "/api/avatar/spawn", "{player?} next to a player, or {x, z, y?, world?}, or the last saved place", r -> plugin.sync(() -> avatar.spawn(r)));
        api.route("POST", "/api/avatar/despawn", "Remove the avatar from the world (inventory is kept)", r -> plugin.sync(avatar::despawn));
        api.route("GET", "/api/avatar/state", "Position, health, held item, what it looks at, nearby players and entities, current task", r -> plugin.sync(avatar::state));
        api.route("GET", "/api/avatar/map", "?radius=10: top-down ASCII map around the avatar (with legend)", r -> plugin.sync(() -> avatar.map(r)));
        api.route("GET", "/api/avatar/blocks", "?radius=3&filter=ORE: blocks around the avatar as [dx,dy,dz,type]", r -> plugin.sync(() -> avatar.blocks(r)));
        api.route("GET", "/api/avatar/find", "?block=IRON_ORE&radius=16 (or a part of a name, e.g. ORE): nearest matching blocks", r -> plugin.sync(() -> avatar.find(r)));
        api.route("GET", "/api/avatar/inventory", "Inventory slots, held slot and armor", r -> plugin.sync(avatar::inventory));

        // --- avatar: movement
        api.route("POST", "/api/avatar/goto", "{x, z, y?} (without y: any height), or {player}, or {entity: uuid}; distance?, wait?=true, timeout?=30: walk there using pathfinding", r ->
                Avatar.await(plugin.sync(() -> avatar.startGoto(r)), r));
        api.route("POST", "/api/avatar/follow", "{player, distance?=2.5}: keep following a player until /stop", r ->
                Avatar.await(plugin.sync(() -> avatar.startFollow(r)), withNoWait(r)));
        api.route("POST", "/api/avatar/stop", "Cancel the current task (walking, following, mining)", r -> plugin.sync(avatar::stop));
        api.route("POST", "/api/avatar/look", "{yaw, pitch} or {x, y?, z} or {player}", r -> plugin.sync(() -> avatar.look(r)));
        api.route("POST", "/api/avatar/jump", "Jump", r -> plugin.sync(avatar::jump));
        api.route("POST", "/api/avatar/teleport", "{x, y, z, world?} or {player}: instant move (admin)", r -> plugin.sync(() -> avatar.teleport(r)));

        // --- avatar: world interaction
        api.route("POST", "/api/avatar/break", "{x, y, z, approach?=true}: mine a block with the held tool (real mining time), drops go to the inventory", r ->
                Avatar.await(plugin.sync(() -> avatar.startBreak(r)), r));
        api.route("POST", "/api/avatar/place", "{x, y, z, slot?}: place a block from the inventory", r -> plugin.sync(() -> avatar.place(r)));
        api.route("POST", "/api/avatar/use", "{x, y, z}: open/close doors, flip levers, press buttons, look into containers", r -> plugin.sync(() -> avatar.use(r)));
        api.route("POST", "/api/avatar/attack", "{entity: uuid} or {type: PIG} or {player}; default target: nearest hostile mob within 16 blocks. chase?=true: run after the target and hit until it dies, then pick up the drops (fight_timeout?=60 s); chase=false: a single hit within reach", r ->
                r.bool("chase", true)
                        ? Avatar.await(plugin.sync(() -> avatar.startAttack(r)), r)
                        : plugin.sync(() -> avatar.attackOnce(r)));
        api.route("POST", "/api/avatar/chat", "{message, to?: player name for a whisper}", r -> plugin.sync(() -> avatar.chat(r)));

        // --- avatar: items
        api.route("POST", "/api/avatar/hold", "{slot} or {item: MATERIAL}: choose the item in hand", r -> plugin.sync(() -> avatar.hold(r)));
        api.route("POST", "/api/avatar/drop", "{slot?, amount?}: throw items", r -> plugin.sync(() -> avatar.drop(r)));
        api.route("POST", "/api/avatar/equip", "{slot}: wear armor from the inventory", r -> plugin.sync(() -> avatar.equip(r)));
        api.route("POST", "/api/avatar/craft", "{item, times?=1}: craft from the inventory (3x3 recipes need a crafting table within 4 blocks)", r -> plugin.sync(() -> avatar.craft(r)));
        api.route("GET", "/api/avatar/container", "?x&y&z: contents of a chest/furnace/barrel within reach", r -> plugin.sync(() -> avatar.container(r)));
        api.route("POST", "/api/avatar/container/put", "{x, y, z, slot?, amount?, to_slot?}: move items from the inventory into a container", r -> plugin.sync(() -> avatar.containerPut(r)));
        api.route("POST", "/api/avatar/container/take", "{x, y, z, slot, amount?}: take items from a container", r -> plugin.sync(() -> avatar.containerTake(r)));
        api.route("POST", "/api/avatar/give", "{item, amount?}: put items into the avatar inventory (admin)", r -> plugin.sync(() -> avatar.give(r)));
    }

    /** Following never ends by itself, so the request returns right away unless the caller insists. */
    private static ApiServer.Request withNoWait(ApiServer.Request r) {
        if (r.has("wait")) return r;
        com.google.gson.JsonObject body = r.body == null ? new com.google.gson.JsonObject() : r.body.deepCopy();
        body.addProperty("wait", false);
        return new ApiServer.Request(r.method, r.path, r.remote, r.query, body);
    }
}
