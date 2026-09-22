package main;

import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.util.Log;
import arc.util.serialization.Jval;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.game.Gamemode;
import mindustry.game.Rules;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.maps.Map;
import mindustry.net.WorldReloader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class ArenaLink {

    private final String token;
    private final String lobbyHost;
    private final int lobbyWebPort;
    private final String lobbyPublicHost;
    private final int lobbyPublicPort;
    private final int apiPort;

    private HttpServer http;

    private volatile Session session;

    private volatile boolean rotating;

    public ArenaLink(String token, String lobbyHost, int lobbyWebPort, String lobbyPublicHost,
                     int lobbyPublicPort, int apiPort) {
        this.token = token;
        this.lobbyHost = lobbyHost;
        this.lobbyWebPort = lobbyWebPort;
        this.lobbyPublicHost = lobbyPublicHost;
        this.lobbyPublicPort = lobbyPublicPort;
        this.apiPort = apiPort;
    }

    public static ArenaLink load() {
        Fi file = Core.settings.getDataDirectory().child("mods/FoundationRanked-arena.json");
        try {
            if (!file.exists()) {
                Jval def = Jval.newObject();
                def.add("token", Jval.valueOf(""));
                def.add("lobbyHost", Jval.valueOf("06b55ab3-1ee8-4c37-bd49-337deea6447e"));
                def.add("lobbyWebPort", Jval.valueOf(25000));
                def.add("lobbyPublicHost", Jval.valueOf("188.126.61.232"));
                def.add("lobbyPublicPort", Jval.valueOf(6568));
                def.add("apiPort", Jval.valueOf(Session.DEFAULT_API_PORT));
                file.writeString(def.toString());
                Log.info("[Arena] Created " + file.path() + ", set the token there");
            }

            Jval json = Jval.read(file.readString());
            return new ArenaLink(
                    json.getString("token", ""),
                    json.getString("lobbyHost", ""),
                    json.getInt("lobbyWebPort", 25000),
                    json.getString("lobbyPublicHost", ""),
                    json.getInt("lobbyPublicPort", 6567),
                    json.getInt("apiPort", Session.DEFAULT_API_PORT)
            );
        } catch (Exception e) {
            Log.err("[Arena] cannot read " + file.path() + ": ", e);
            return new ArenaLink("", "", 25000, "", 6567, Session.DEFAULT_API_PORT);
        }
    }

    public void init() {
        if (token.isEmpty() || lobbyHost.isEmpty() || lobbyPublicHost.isEmpty()) {
            Log.err("[Arena] token / lobbyHost / lobbyPublicHost are not set in the config, matches are disabled");
            return;
        }

        startApi();

        Events.on(EventType.PlayerJoin.class, e -> place(e.player));

        Events.on(EventType.DisposeEvent.class, e -> {
            if (http != null) http.stop(0);
        });
    }

    public boolean busy() {
        return session != null;
    }

    public boolean isParticipant(String uuid) {
        Session s = session;
        return s != null && s.find(uuid) != null;
    }

    public int participantCount() {
        Session s = session;
        return s == null ? 0 : s.players.size;
    }

    public void finish(boolean rotation) {
        session = null;
        rotating = rotation;
        reportFree();
    }

    public void onIdleMapLoaded() {
        rotating = false;
        reportFree();
    }

    public void sendAllToLobby() {
        Groups.player.each(this::sendToLobby);
    }

    public void sendToLobby(Player player) {
        if (player.con != null) Call.connect(player.con, lobbyPublicHost, lobbyPublicPort);
    }

    public void reportFree() {
        if (lobbyHost.isEmpty()) return;

        new Thread(() -> {
            try {
                int myPort = Core.settings.getInt("port", 6567);
                URL url = new URL("http://" + lobbyHost + ":" + lobbyWebPort + "/arenaFree?port=" + myPort);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(3000);
                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    Log.info("[DuelArena] Connected to lobby by port: " + myPort);
                } else {
                    Log.err("[DuelArena] Lobby response bad: : " + responseCode);
                }
                conn.disconnect();
            } catch (Exception e) {
                Log.err("[DuelArena] Couldn't do a response: : " + e.getMessage());
            }
        }).start();
    }

    private void place(Player player) {
        Session s = session;
        if (s == null) {
            sendToLobby(player);
            return;
        }

        Session.Slot slot = s.find(player.uuid());
        player.team(slot != null ? Team.get(slot.team) : Team.derelict);
    }
    private void startApi() {
        try {
            http = HttpServer.create(new InetSocketAddress(apiPort), 0);
            http.createContext("/session", this::handleSession);
            http.createContext("/maps", this::handleMaps);
            http.setExecutor(Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "fr-arena-api");
                t.setDaemon(true);
                return t;
            }));
            http.start();
            Log.info("[Arena] API started on port " + apiPort);
        } catch (Exception e) {
            Log.err("[Arena] cannot start API: ", e);
        }
    }

    private boolean authorized(HttpExchange ex) {
        return token.equals(ex.getRequestHeaders().getFirst("X-Token"));
    }

    private void handleSession(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod()) || !authorized(ex)) {
            reply(ex, 403, "forbidden");
            return;
        }
        if (session != null || !waitForRotation()) {
            reply(ex, 409, "busy");
            return;
        }

        Session s = parse(ex);
        if (s == null) {
            reply(ex, 400, "bad request");
            return;
        }

        boolean ok;
        try {
            ok = callOnMain(() -> apply(s));
        } catch (Exception e) {
            Log.err("[Arena] apply failed: ", e);
            ok = false;
        }
        reply(ex, ok ? 200 : 500, ok ? "OK" : "error");
    }

    private void handleMaps(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            reply(ex, 403, "forbidden");
            return;
        }

        try {
            reply(ex, 200, callOnMain(this::mapsJson));
        } catch (Exception e) {
            Log.err("[Arena] maps failed: ", e);
            reply(ex, 500, "error");
        }
    }

    private boolean waitForRotation() {
        long deadline = System.currentTimeMillis() + 20_000;
        while (rotating && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return !rotating;
    }

    private String mapsJson() {
        Jval list = Jval.newArray();
        for (Map map : Vars.maps.customMaps()) {
            Jval o = Jval.newObject();
            o.add("file", Jval.valueOf(map.file.nameWithoutExtension()));
            o.add("name", Jval.valueOf(map.name()));
            list.asArray().add(o);
        }

        Jval root = Jval.newObject();
        root.add("maps", list);
        return root.toString();
    }

    private boolean apply(Session s) {
        if (s.mapBase64 != null) {
            Vars.customMapDirectory.child(s.map + ".msav").writeBytes(Base64.getDecoder().decode(s.mapBase64));
            Vars.maps.reload();
        }

        Map map = Vars.maps.all().find(m -> m.file.nameWithoutExtension().equals(s.map));
        if (map == null) {
            Log.err("[Arena] map not found: " + s.map);
            return false;
        }

        Rules rules = map.applyRules(Gamemode.pvp);
        rules.unitCap = s.rules.getInt("unitCap", rules.unitCap);

        session = s;
        try {
            WorldReloader reloader = new WorldReloader();
            reloader.begin();
            Vars.world.loadMap(map, rules);
            Vars.state.rules = rules;
            Vars.logic.play();
            reloader.end();
        } catch (RuntimeException e) {
            session = null;
            throw e;
        }

        Groups.player.each(this::place);
        return true;
    }

    private static <T> T callOnMain(Supplier<T> task) throws Exception {
        CompletableFuture<T> future = new CompletableFuture<>();
        Core.app.post(() -> {
            try {
                future.complete(task.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future.get(30, TimeUnit.SECONDS);
    }

    private static Session parse(HttpExchange ex) {
        try {
            return Session.fromJson(readBody(ex));
        } catch (Exception e) {
            Log.err("[Arena] bad session: ", e);
            return null;
        }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        InputStream in = ex.getRequestBody();
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void reply(HttpExchange ex, int code, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }
}