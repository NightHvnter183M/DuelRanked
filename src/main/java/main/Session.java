package main;

import arc.struct.Seq;
import arc.util.serialization.Jval;

public class Session {

    public static final int DEFAULT_API_PORT = 7567;

    public static class Slot {
        public String uuid = "";
        public String name = "";
        public int team;
    }

    public String id = "";
    public String map = "";
    public String mapBase64;
    public Jval rules = Jval.newObject();
    public final Seq<Slot> players = new Seq<>();

    public Slot find(String uuid) {
        return players.find(p -> p.uuid.equals(uuid));
    }

    public String toJson() {
        Jval root = Jval.newObject();
        root.add("id", Jval.valueOf(id));
        root.add("map", Jval.valueOf(map));
        if (mapBase64 != null) root.add("mapBase64", Jval.valueOf(mapBase64));
        root.add("rules", rules);
        Jval list = Jval.newArray();
        for (Slot p : players) {
            Jval o = Jval.newObject();
            o.add("uuid", Jval.valueOf(p.uuid));
            o.add("name", Jval.valueOf(p.name));
            o.add("team", Jval.valueOf(p.team));
            list.asArray().add(o);
        }
        root.add("players", list);
        return root.toString();
    }

    public static Session fromJson(String json) {
        Jval root = Jval.read(json);
        Session s = new Session();
        s.id = root.getString("id", "");
        s.map = root.getString("map", "");
        s.mapBase64 = root.getString("mapBase64", null);
        s.rules = root.has("rules") ? root.get("rules") : Jval.newObject();
        for (Jval o : root.get("players").asArray()) {
            Slot p = new Slot();
            p.uuid = o.getString("uuid", "");
            p.name = o.getString("name", "");
            p.team = o.getInt("team", 0);
            s.players.add(p);
        }
        return s;
    }
}