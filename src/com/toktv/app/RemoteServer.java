package com.toktv.app;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TokTV Remote: a tiny web server on the home network. The phone opens the page from the QR
 * code; every API call must carry the pairing key from that QR code. Only runs while TokTV is open.
 */
public final class RemoteServer {
    public static final int PORT = 8686;
    private static RemoteServer instance;

    private final Context app;
    private final Prefs prefs;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private ServerSocket server;
    private volatile boolean running;

    private RemoteServer(Context c) {
        app = c.getApplicationContext();
        prefs = new Prefs(app);
    }

    public static synchronized RemoteServer get(Context c) {
        if (instance == null) instance = new RemoteServer(c);
        return instance;
    }

    /** The secret in the QR code. Changing it unpairs every phone. */
    public String key() {
        String k = prefs.get("remote_key", "");
        if (k.length() < 16) k = newKey();
        return k;
    }

    public String newKey() {
        byte[] b = new byte[12];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        prefs.put("remote_key", sb.toString());
        return sb.toString();
    }

    /** Address to type on the phone. */
    public String baseUrl() {
        String ip = localIp();
        return ip == null ? null : "http://" + ip + ":" + PORT + "/";
    }

    /** What the QR code holds: the address plus the current one-time code (never the key itself). */
    public String url() {
        String b = baseUrl();
        return b == null ? null : b + "?c=" + pairCode();
    }

    // ---------------------------------------------------------------- One-time pairing code

    private String code;
    private long codeAt;
    private int wrong;
    /** Runs on the UI thread after a phone pairs (the TV closes the QR dialog). */
    public volatile Runnable onPaired;

    /** 6 digits, good for one phone and 10 minutes. */
    public synchronized String pairCode() {
        if (code == null || System.currentTimeMillis() - codeAt > 10 * 60 * 1000) {
            code = String.format("%06d", new SecureRandom().nextInt(1000000));
            codeAt = System.currentTimeMillis();
            wrong = 0;
        }
        return code;
    }

    /** The key for a correct code (and the code is used up), or null. 5 wrong tries burn the code. */
    private synchronized String redeem(String c) {
        if (code == null || System.currentTimeMillis() - codeAt > 10 * 60 * 1000) return null;
        if (c == null || !c.trim().equals(code)) {
            if (++wrong >= 5) code = null;
            return null;
        }
        code = null;
        return key();
    }

    /** Home network only: private, link-local or this device. */
    private static boolean fromHome(Socket s) {
        InetAddress a = s.getInetAddress();
        return a != null && (a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isLoopbackAddress());
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        new Thread(new Runnable() {
            @Override
            public void run() { loop(); }
        }, "TokTVRemote").start();
    }

    public synchronized void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        server = null;
    }

    public boolean isRunning() { return running; }

    private void loop() {
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new java.net.InetSocketAddress(PORT));
            while (running) {
                final Socket s = server.accept();
                pool.execute(new Runnable() {
                    @Override
                    public void run() { handle(s); }
                });
            }
        } catch (Exception e) {
            running = false;
        }
    }

    // ---------------------------------------------------------------- HTTP

    private void handle(Socket s) {
        try {
            if (!fromHome(s)) { s.close(); return; }
            s.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            String requestLine = readLine(in);
            if (requestLine == null) { s.close(); return; }
            String[] parts = requestLine.split(" ");
            String method = parts[0];
            String target = parts.length > 1 ? parts[1] : "/";
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && line.length() > 0) {
                int i = line.indexOf(':');
                if (i > 0) headers.put(line.substring(0, i).trim().toLowerCase(), line.substring(i + 1).trim());
            }
            String body = "";
            int len = 0;
            try { len = Integer.parseInt(headers.containsKey("content-length") ? headers.get("content-length") : "0"); } catch (Exception ignored) { }
            if (len > 0 && len < 65536) {
                byte[] b = new byte[len];
                int got = 0;
                while (got < len) {
                    int n = in.read(b, got, len - got);
                    if (n < 0) break;
                    got += n;
                }
                body = new String(b, 0, got, "UTF-8");
            }
            String path = target, query = "";
            int q = target.indexOf('?');
            if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }
            Map<String, String> params = parseQuery(query);

            OutputStream out = s.getOutputStream();
            if ("/".equals(path) || "/index.html".equals(path)) {
                send(out, 200, "text/html; charset=utf-8", RemoteApi.page(app));
            } else if ("/pair".equals(path)) {
                String c = params.get("code");
                try { if (c == null && body.length() > 0) c = new JSONObject(body).optString("code", null); } catch (Exception ignored) { }
                String k = redeem(c);
                if (k == null) {
                    send(out, 403, "application/json", "{\"ok\":false,\"error\":\"That code didn't work. Check the code on the TV.\"}");
                } else {
                    send(out, 200, "application/json", "{\"ok\":true,\"key\":" + JSONObject.quote(k) + "}");
                    final Runnable r = onPaired;
                    if (r != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
                }
            } else if (path.startsWith("/api/")) {
                String k = headers.get("x-key");
                if (k == null || !k.equals(key())) {
                    send(out, 403, "application/json", "{\"error\":\"not paired\"}");
                } else {
                    JSONObject req = new JSONObject();
                    try { if (body.length() > 0) req = new JSONObject(body); } catch (Exception ignored) { }
                    for (Map.Entry<String, String> e : params.entrySet()) if (!req.has(e.getKey())) req.put(e.getKey(), e.getValue());
                    String res = RemoteApi.handle(app, method, path.substring(5), req);
                    send(out, 200, "application/json; charset=utf-8", res);
                }
            } else {
                send(out, 404, "text/plain", "not found");
            }
            out.flush();
            s.close();
        } catch (Exception e) {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    private static String readLine(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) break;
        }
        if (c < 0 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> m = new HashMap<>();
        if (q == null || q.isEmpty()) return m;
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            try {
                if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"), URLDecoder.decode(kv.substring(i + 1), "UTF-8"));
            } catch (Exception ignored) { }
        }
        return m;
    }

    private static void send(OutputStream out, int code, String type, String body) throws java.io.IOException {
        byte[] b = body.getBytes("UTF-8");
        String status = code == 200 ? "OK" : code == 403 ? "Forbidden" : "Not Found";
        String h = "HTTP/1.1 " + code + " " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + b.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes("UTF-8"));
        out.write(b);
    }

    /** First private IPv4 address of this device (Wi-Fi or Ethernet). */
    public static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return null;
    }
}
