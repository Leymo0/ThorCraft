package dev.leymo.thorcraft.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.leymo.thorcraft.ThorConfig;
import dev.leymo.thorcraft.ThorCraft;
import dev.leymo.thorcraft.cobble.CobbleBridge;
import dev.leymo.thorcraft.input.KeyTools;
import dev.leymo.thorcraft.map.MiniMap;
import dev.leymo.thorcraft.render.IconRenderer;
import dev.leymo.thorcraft.render.MirrorCapture;
import dev.leymo.thorcraft.state.ChatFeed;
import dev.leymo.thorcraft.state.StateBuilder;
import net.minecraft.client.MinecraftClient;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Tiny dependency-free HTTP server on 127.0.0.1.
 * GET  /            panel UI (served from the mod jar, so UI updates ship with the mod)
 * GET  /events      Server-Sent Events: "state", "chat", "fx"
 * POST /cmd         one JSON command (see Commands)
 * GET  /icon/K.png  rendered item / Pokémon icon
 * GET  /mirror.png  live picture of the current menu
 * GET  /api/...     on-demand data (summary, keys, map)
 */
public final class PanelServer {
    private final ThorConfig cfg;
    private final List<Sse> clients = new CopyOnWriteArrayList<>();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ThorCraft-http");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running;

    public PanelServer(ThorConfig cfg) {
        this.cfg = cfg;
    }

    public void start() {
        Thread t = new Thread(this::acceptLoop, "ThorCraft-server");
        t.setDaemon(true);
        t.start();
    }

    public int clientCount() {
        return clients.size();
    }

    public void broadcast(String event, String data) {
        if (clients.isEmpty()) return;
        String msg = "event: " + event + "\ndata: " + data + "\n\n";
        for (Sse c : clients) {
            if (!c.queue.offer(msg)) {
                c.queue.clear();
                c.queue.offer(msg);
            }
        }
    }

    private void acceptLoop() {
        String host = cfg.lan ? "0.0.0.0" : "127.0.0.1";
        try (ServerSocket ss = new ServerSocket()) {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(host, cfg.port));
            running = true;
            ThorCraft.LOG.info("ThorCraft panel: http://{}:{}/{}", host.equals("0.0.0.0") ? "<this-device-ip>" : "127.0.0.1",
                    cfg.port, cfg.lan ? "?token=" + cfg.token : "");
            while (running) {
                Socket s = ss.accept();
                pool.execute(() -> handle(s));
            }
        } catch (IOException e) {
            ThorCraft.LOG.error("ThorCraft panel server could not start on port {} (is another game instance running?)", cfg.port, e);
        }
    }

    // ------------------------------------------------------------------ request handling

    private void handle(Socket s) {
        try {
            s.setSoTimeout(15000);
            s.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                s.close();
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                s.close();
                return;
            }
            String method = parts[0].toUpperCase(Locale.ROOT);
            String target = parts[1];

            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0) headers.put(line.substring(0, c).trim().toLowerCase(Locale.ROOT), line.substring(c + 1).trim());
            }

            String path = target;
            String query = "";
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                query = target.substring(q + 1);
            }
            Map<String, String> params = parseQuery(query);

            boolean local = s.getInetAddress().isLoopbackAddress();
            String cookieToken = cookie(headers.get("cookie"), "tc");
            boolean authed = local || cfg.token.equals(params.get("token")) || cfg.token.equals(cookieToken);
            if (!authed && !path.equals("/ping")) {
                send(out, 403, "text/plain", "ThorCraft: add ?token=... (shown in config/thorcraft.json)".getBytes(StandardCharsets.UTF_8), "");
                s.close();
                return;
            }
            String setCookie = (!local && params.containsKey("token")) ? "Set-Cookie: tc=" + cfg.token + "; Path=/\r\n" : "";

            if (method.equals("OPTIONS")) {
                send(out, 204, "text/plain", new byte[0],
                        "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type\r\n");
                s.close();
                return;
            }

            if (method.equals("GET") && path.equals("/events")) {
                serveEvents(s, out);
                return; // serveEvents closes the socket
            }

            if (method.equals("POST") && path.equals("/cmd")) {
                int len = parseInt(headers.get("content-length"), 0);
                byte[] body = in.readNBytes(Math.min(len, 1 << 20));
                JsonElement el = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
                if (el.isJsonObject()) {
                    JsonObject cmd = el.getAsJsonObject();
                    MinecraftClient.getInstance().execute(() -> Commands.handle(cmd));
                }
                send(out, 204, "text/plain", new byte[0], "");
                s.close();
                return;
            }

            if (!method.equals("GET")) {
                send(out, 405, "text/plain", new byte[0], "");
                s.close();
                return;
            }

            switch (path) {
                case "/ping" -> sendText(out, 200, "text/plain", "thorcraft " + (CobbleBridge.present() ? "cobblemon" : "vanilla"));
                case "/mirror.png" -> {
                    float[] rc = {0f, 0f, 1f, 1f};
                    try {
                        if (params.containsKey("rect")) {
                            String[] p4 = params.get("rect").split(",");
                            for (int i = 0; i < 4 && i < p4.length; i++) rc[i] = Float.parseFloat(p4[i]);
                        }
                    } catch (NumberFormatException ignored) {
                    }
                    MirrorCapture.request(rc[0], rc[1], rc[2], rc[3]);
                    byte[] png = MirrorCapture.latest();
                    if (png == null) send(out, 404, "text/plain", new byte[0], "");
                    else send(out, 200, "image/png", png, "");
                }
                case "/api/summary" -> sendJson(out, onClientThread(() -> CobbleBridge.summaryJson(parseInt(params.get("slot"), 0))));
                case "/api/keys" -> sendJson(out, onClientThread(KeyTools::listJson));
                case "/api/map" -> sendJson(out, onClientThread(MiniMap::build));
                case "/api/chat" -> sendJson(out, ChatFeed.history());
                case "/api/debug" -> sendJson(out, onClientThread(CobbleBridge::debugJson));
                default -> {
                    if (path.startsWith("/tex/") && path.endsWith(".png")) {
                        byte[] png = GameAssets.texture(path.substring(5, path.length() - 4));
                        if (png == null) send(out, 404, "text/plain", new byte[0], "");
                        else send(out, 200, "image/png", png, "Cache-Control: max-age=60\r\n");
                    } else if (path.startsWith("/itemicon/") && path.endsWith(".png")) {
                        // Icon of any item by id, e.g. /itemicon/minecraft/chest.png -> redirect to its rendered icon
                        String key = GameAssets.itemIconKey(path.substring(10, path.length() - 4));
                        if (key == null) send(out, 404, "text/plain", new byte[0], "");
                        else send(out, 302, "text/plain", new byte[0], "Location: /icon/" + key + ".png\r\n");
                    } else if (path.startsWith("/icon/") && path.endsWith(".png")) {
                        String key = path.substring(6, path.length() - 4);
                        byte[] png = IconRenderer.get(key);
                        if (png == null) send(out, 404, "text/plain", new byte[0], "");
                        else send(out, 200, "image/png", png, "Cache-Control: max-age=30\r\n");
                    } else {
                        serveStatic(out, path, setCookie);
                    }
                }
            }
            s.close();
        } catch (Exception e) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static JsonElement onClientThread(Supplier<JsonElement> supplier) {
        try {
            return MinecraftClient.getInstance().submit(supplier).get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("error", String.valueOf(e));
            return err;
        }
    }

    private void serveEvents(Socket s, OutputStream out) {
        Sse c = new Sse();
        try {
            s.setSoTimeout(0);
            String head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-store\r\n"
                    + "Access-Control-Allow-Origin: *\r\nConnection: keep-alive\r\n\r\nretry: 1000\n\n";
            out.write(head.getBytes(StandardCharsets.UTF_8));
            out.flush();
            clients.add(c);
            StateBuilder.forceSoon();
            while (true) {
                String m = c.queue.poll(10, TimeUnit.SECONDS);
                if (m == null) m = ": ping\n\n";
                out.write(m.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (Exception ignored) {
        } finally {
            clients.remove(c);
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void serveStatic(OutputStream out, String path, String extraHeaders) throws IOException {
        if (path.equals("/") || path.isEmpty()) path = "/index.html";
        if (!path.matches("/[a-zA-Z0-9_.\\-]+")) {
            send(out, 404, "text/plain", new byte[0], "");
            return;
        }
        try (InputStream res = PanelServer.class.getResourceAsStream("/assets/thorcraft/web" + path)) {
            if (res == null) {
                send(out, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8), "");
                return;
            }
            byte[] data = res.readAllBytes();
            send(out, 200, contentType(path), data, extraHeaders);
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    // ------------------------------------------------------------------ helpers

    private static void sendJson(OutputStream out, JsonElement json) throws IOException {
        sendText(out, 200, "application/json; charset=utf-8", json == null ? "null" : json.toString());
    }

    private static void sendText(OutputStream out, int code, String type, String body) throws IOException {
        send(out, code, type, body.getBytes(StandardCharsets.UTF_8), "");
    }

    private static void send(OutputStream out, int code, String type, byte[] body, String extraHeaders) throws IOException {
        String reason = switch (code) {
            case 200 -> "OK";
            case 204 -> "No Content";
            case 302 -> "Found";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            default -> "Status";
        };
        String head = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + (extraHeaders.contains("Cache-Control") ? "" : "Cache-Control: no-store\r\n")
                + extraHeaders
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') buf.write(b);
            if (buf.size() > 16384) break;
        }
        if (b == -1 && buf.size() == 0) return null;
        return buf.toString(StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> m = new HashMap<>();
        if (q == null || q.isEmpty()) return m;
        for (String pair : q.split("&")) {
            int e = pair.indexOf('=');
            String k = e >= 0 ? pair.substring(0, e) : pair;
            String v = e >= 0 ? pair.substring(e + 1) : "";
            m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return m;
    }

    private static String cookie(String header, String name) {
        if (header == null) return null;
        for (String part : header.split(";")) {
            String p = part.trim();
            if (p.startsWith(name + "=")) return p.substring(name.length() + 1);
        }
        return null;
    }

    private static int parseInt(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static final class Sse {
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>(64);
    }
}
