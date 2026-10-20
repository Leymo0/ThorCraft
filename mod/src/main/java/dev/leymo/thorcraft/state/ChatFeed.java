package dev.leymo.thorcraft.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.leymo.thorcraft.ThorCraft;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.Deque;

/** Mirrors chat to the bottom screen (last 150 lines kept for when the panel reconnects). */
public final class ChatFeed {
    private ChatFeed() {}

    private static final Deque<JsonObject> HISTORY = new ArrayDeque<>();
    private static long seq;

    public static void register() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) push(message, "game");
        });
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, ts) -> {
            Text decorated;
            try {
                decorated = params.applyChatDecoration(message);
            } catch (Throwable t) {
                decorated = message;
            }
            push(decorated, "chat");
        });
    }

    private static synchronized void push(Text text, String kind) {
        JsonObject o = new JsonObject();
        o.addProperty("id", ++seq);
        o.addProperty("k", kind);
        o.add("s", TextJson.segs(text));
        HISTORY.addLast(o);
        while (HISTORY.size() > 150) HISTORY.removeFirst();
        if (ThorCraft.SERVER != null) ThorCraft.SERVER.broadcast("chat", o.toString());
    }

    public static synchronized JsonArray history() {
        JsonArray a = new JsonArray();
        for (JsonObject o : HISTORY) a.add(o);
        return a;
    }
}
