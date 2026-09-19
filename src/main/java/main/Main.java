package main;

import arc.Events;
import arc.struct.Seq;
import arc.util.CommandHandler;
import arc.util.Log;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.mod.Plugin;

import java.net.HttpURLConnection;
import java.net.URL;

public class Main extends Plugin {
    Seq<String> players = new Seq<>();
    Seq<String> votes = new Seq<>();
    @Override
    public void init() {
        Events.on(EventType.GameOverEvent.class, event -> {
            Groups.player.each(player -> Call.connect(player.con, "188.126.61.232", 6568));
            sendFreeSignalToLobby();
            Log.info("AutoRestart");
            votes.clear();
        });

        Events.on(EventType.PlayerJoin.class, event -> {
            if (players.isEmpty()) {
                players.add(event.player.uuid());
                event.player.team(Team.sharded);
                return;
            }
            if (players.size == 1 && !players.contains(event.player.uuid())) {
                players.add(event.player.uuid());
                event.player.team(Team.crux);
                return;
            }
            if(!players.contains(event.player.uuid())) {
                event.player.team(Team.all[0]);
            }
        });
        Events.on(EventType.PlayEvent.class, event -> {
            sendFreeSignalToLobby();
            Vars.state.rules.pvp = true;
            Vars.state.rules.pvpAutoPause = true;
            Vars.maps.setNextMapOverride(Vars.maps.customMaps().random());
            Call.setRules(Vars.state.rules);
        });
    }
    public void registerClientCommands(CommandHandler handler) {
        handler.<Player>register("restart", "end a duel and come back to lobby", (args, p) -> {
            if (players.contains(p.uuid())){
                if (!votes.contains(p.uuid())) votes.add(p.uuid());
                else p.sendMessage("You have already voted!");
            }
            else p.sendMessage("You can't have permission for that");
            if(votes.size == 2 || votes.size == Groups.player.size()) {
                sendFreeSignalToLobby();
                Groups.player.each(player -> Call.connect(player.con, "192.168.50.28", 6568));
            }
        });
    }
    public void registerServerCommands(CommandHandler handler) {
        handler.register("restart", "restarts the game", (args) -> Events.fire(new EventType.GameOverEvent(Team.derelict)));
    }

    private void sendFreeSignalToLobby() {
        new Thread(() -> {
            try {
                int myPort = arc.Core.settings.getInt("port", 6567);
                String lobbyIp = "06b55ab3-1ee8-4c37-bd49-337deea6447e";
                int lobbyWebPort = 25000;
                URL url = new URL("http://" + lobbyIp + ":" + lobbyWebPort + "/arenaFree?port=" + myPort);
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
}