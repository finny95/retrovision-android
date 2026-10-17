package com.retrovision.player;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A tiny web server on the home network so a phone can be the remote: the
 * phone opens http://<box>:<port>, types the code shown on the TV, then sends
 * button presses. Only answers with the right code; nothing leaves the network.
 */
final class RemoteServer {
    interface Listener {
        void onKey(String key);
    }

    private final Listener listener;
    private ServerSocket server;
    private ExecutorService pool;
    private Thread acceptor;
    volatile String code = "";
    volatile String page = "<html><body>RetroVision</body></html>";
    volatile String state = "{}";
    private int badTries = 0;
    private long badSince = 0;

    RemoteServer(Listener listener) {
        this.listener = listener;
    }

    synchronized int start(int preferredPort) throws IOException {
        if (server != null && !server.isClosed()) return server.getLocalPort();
        IOException last = null;
        for (int port = preferredPort; port < preferredPort + 10; port++) {
            try {
                server = new ServerSocket(port);
                break;
            } catch (IOException e) {
                last = e;
                server = null;
            }
        }
        if (server == null) throw last != null ? last : new IOException("No free port");
        pool = Executors.newFixedThreadPool(4);
        final ServerSocket s = server;
        acceptor = new Thread(() -> {
            while (!s.isClosed()) {
                try {
                    Socket sock = s.accept();
                    pool.execute(() -> handle(sock));
                } catch (IOException e) {
                    break;
                }
            }
        }, "rv-remote");
        acceptor.setDaemon(true);
        acceptor.start();
        return server.getLocalPort();
    }

    synchronized void stop() {
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
            // closing anyway
        }
        server = null;
        if (pool != null) pool.shutdownNow();
        pool = null;
    }

    synchronized boolean running() {
        return server != null && !server.isClosed();
    }

    synchronized int port() {
        return server != null ? server.getLocalPort() : -1;
    }

    /** This box's address on the home network (Wi-Fi or Ethernet). */
    static String localAddress() {
        try {
            String fallback = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    String ip = a.getHostAddress();
                    String name = ni.getName().toLowerCase(Locale.ROOT);
                    if (a.isSiteLocalAddress() && (name.startsWith("wlan") || name.startsWith("eth"))) return ip;
                    if (a.isSiteLocalAddress() && fallback == null) fallback = ip;
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    private void handle(Socket sock) {
        try (Socket s = sock) {
            s.setSoTimeout(5000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            String head = readHead(in);
            if (head == null) return;
            String[] lines = head.split("\r\n");
            String[] req = lines[0].split(" ");
            if (req.length < 2) return;
            String target = req[1];
            String path = target;
            Map<String, String> q = new HashMap<>();
            int qi = target.indexOf('?');
            if (qi >= 0) {
                path = target.substring(0, qi);
                for (String kv : target.substring(qi + 1).split("&")) {
                    int e = kv.indexOf('=');
                    if (e > 0) q.put(dec(kv.substring(0, e)), dec(kv.substring(e + 1)));
                }
            }
            OutputStream out = s.getOutputStream();
            if (path.equals("/") || path.equals("/remote")) {
                send(out, 200, "text/html; charset=utf-8", page);
                return;
            }
            if (!path.startsWith("/api/")) {
                send(out, 404, "text/plain", "Not found");
                return;
            }
            if (!checkCode(q.get("code"))) {
                send(out, 401, "application/json", "{\"error\":\"code\"}");
                return;
            }
            if (path.equals("/api/state")) {
                send(out, 200, "application/json", state);
            } else if (path.equals("/api/key")) {
                String k = q.get("k");
                if (k != null && k.matches("[a-z0-9]{1,12}")) listener.onKey(k);
                send(out, 200, "application/json", state);
            } else {
                send(out, 404, "application/json", "{}");
            }
        } catch (Exception ignored) {
            // phone went away mid-request
        }
    }

    private synchronized boolean checkCode(String given) {
        long now = System.currentTimeMillis();
        if (now - badSince > 60000) {
            badSince = now;
            badTries = 0;
        }
        if (badTries > 12) return false; // slow down guessing
        boolean ok = given != null && !code.isEmpty() && code.equals(given);
        if (!ok) badTries++;
        return ok;
    }

    private static String dec(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int prev3 = 0, prev2 = 0, prev1 = 0, b;
        while ((b = in.read()) != -1) {
            buf.write(b);
            if (prev3 == '\r' && prev2 == '\n' && prev1 == '\r' && b == '\n') break;
            prev3 = prev2;
            prev2 = prev1;
            prev1 = b;
            if (buf.size() > 16384) return null;
        }
        return buf.size() == 0 ? null : buf.toString("ISO-8859-1");
    }

    private static void send(OutputStream out, int status, String type, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        String reason = status == 200 ? "OK" : status == 401 ? "Unauthorized" : "Not Found";
        String h = "HTTP/1.1 " + status + " " + reason + "\r\n"
            + "Content-Type: " + type + "\r\n"
            + "Content-Length: " + data.length + "\r\n"
            + "Cache-Control: no-store\r\n"
            + "Connection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.ISO_8859_1));
        out.write(data);
        out.flush();
    }
}
