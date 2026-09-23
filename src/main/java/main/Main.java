package main;

import arc.Events;
import arc.struct.Seq;
import arc.util.CommandHandler;
import arc.util.Log;
import arc.util.Timer;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.mod.Plugin;

public class Main extends Plugin {
    Seq<String> votes = new Seq<>();
    ///Takes the match (map, rules, players with teams) from the lobby. Config: config/mods/FoundationRanked-arena.json
    ArenaLink arena;
    private Timer.Task emptyServerRestartTask;

    @Override
    public void init() {
        arena = ArenaLink.load();
        arena.init();

        /// Start the 3-minute countdown only when the last player leaves.
        Events.on(EventType.PlayerLeave.class, event -> {
            if (emptyServerRestartTask != null) {
                emptyServerRestartTask.cancel();
            }

            Log.info("[Foundation] Server is empty. Restarting in 3 minutes if nobody joins.");

            emptyServerRestartTask = Timer.schedule(() -> {
                emptyServerRestartTask = null;

                if (Groups.player.isEmpty()) {
                    Log.info("[Foundation] No players for 3 minutes. Restarting...");
                    Events.fire(new EventType.GameOverEvent(Team.derelict));
                }
            }, 180f);
        });

        /// Cancel the scheduled restart as soon as a player joins.
        Events.on(EventType.PlayerJoin.class, event -> {
            if (emptyServerRestartTask != null) {
                emptyServerRestartTask.cancel();
                emptyServerRestartTask = null;
                Log.info("[Foundation] Player joined. Empty-server restart cancelled.");
            }
        });

        Events.on(EventType.GameOverEvent.class, event -> {
            ///First the lobby learns that the match is over (so it doesn't send anyone back), then everybody goes there
            arena.finish(true);
            arena.sendAllToLobby();
            Log.info("AutoRestart");
            votes.clear();
        });

        Events.on(EventType.PlayEvent.class, event -> {
            ///The map was loaded by the server itself (start / next map), not for a match: the arena is free
            if (!arena.busy()) arena.onIdleMapLoaded();
            Vars.state.rules.pvp = true;
            Vars.state.rules.pvpAutoPause = true;
            Vars.maps.setNextMapOverride(Vars.maps.customMaps().random());
            Call.setRules(Vars.state.rules);
        });
    }
    public void registerClientCommands(CommandHandler handler) {
        handler.<Player>register("surrender", "end a duel and come back to lobby", (args, p) -> {
            ///Only the players of the match can vote (the teams come from the lobby's session)
            if (!arena.isParticipant(p.uuid())) {
                p.sendMessage("You can't have permission for that");
                return;
            }
            arena.finish(false);
            arena.sendAllToLobby();
            votes.clear();
        });
    }
    public void registerServerCommands(CommandHandler handler) {
        handler.register("restart", "restarts the game", (args) -> Events.fire(new EventType.GameOverEvent(Team.derelict)));
    }
}
