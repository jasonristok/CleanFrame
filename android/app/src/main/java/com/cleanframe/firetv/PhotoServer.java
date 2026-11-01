package com.cleanframe.firetv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * A small web server that runs only while the "Own photos" screen is open. The phone opens its
 * page, sends JPEGs and can remove earlier photos. Every API call needs the four-digit code shown
 * on the TV; repeated wrong codes stop the server. It stops by itself after 15 idle minutes.
 * Plain Java (no Android classes), so it can be tested on a desktop JVM.
 */
final class PhotoServer {
    interface Listener {
        void visited();
        void received(String id);
        void removed(String id);
        void stopped(String reason);
    }

    interface Page {
        byte[] html() throws IOException;
    }

    static final int PORT = 8765;
    private static final long IDLE_MS = 15 * 60 * 1000L;
    private static final int MAX_FAILURES = 20;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");

    private final PhotoStore store;
    private final Page page;
    private final Listener listener;
    private final String pin;
    private ServerSocket socket;
    private ExecutorService workers;
    private volatile long lastActivity;
    private volatile boolean running;
    private int failures;

    PhotoServer(PhotoStore store, Page page, Listener listener) {
        this.store = store;
        this.page = page;
        this.listener = listener;
        this.pin = String.format(Locale.ROOT, "%04d", new SecureRandom().nextInt(10000));
    }

    String pin() {
        return pin;
    }

    /** Starts on port 8765, or any free port if that is taken. Returns the port. */
    synchronized int start() throws IOException {
        if (running) return socket.getLocalPort();
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        try {
            server.bind(new InetSocketAddress(PORT));
        } catch (IOException taken) {
            server.close();
            server = new ServerSocket();
            server.bind(new InetSocketAddress(0));
        }
        server.setSoTimeout(30000);
        socket = server;
        workers = Executors.newFixedThreadPool(3);
        running = true;
        lastActivity = System.currentTimeMillis();
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "CleanFrame photo server");
        thread.setDaemon(true);
        thread.start();
        return server.getLocalPort();
    }

    boolean running() {
        return running;
    }

    void stop() {
        stop(null);
    }

    private void stop(String reason) {
        ServerSocket server;
        ExecutorService pool;
        synchronized (this) {
            if (!running) return;
            running = false;
            server = socket;
            pool = workers;
        }
        try {
            server.close();
        } catch (IOException ignored) {
            // Already closed.
        }
        pool.shutdownNow();
        if (reason != null) listener.stopped(reason);
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket client = socket.accept();
                lastActivity = System.currentTimeMillis();
                try {
                    workers.execute(new Runnable() {
                        @Override
                        public void run() {
                            handle(client);
                        }
                    });
                } catch (RejectedExecutionException closed) {
                    client.close();
                }
            } catch (SocketTimeoutException idle) {
                if (System.currentTimeMillis() - lastActivity > IDLE_MS) stop("idle");
            } catch (IOException error) {
                if (running) stop("error");
            }
        }
    }

    private void handle(Socket client) {
        try {
            client.setSoTimeout(30000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            String head = readHead(in);
            if (head == null) {
                respond(out, 400, "text/plain", "Bad request".getBytes(UTF8));
                return;
            }
            String[] lines = head.split("\r\n");
            String[] request = lines[0].split(" ");
            if (request.length < 2) {
                respond(out, 400, "text/plain", "Bad request".getBytes(UTF8));
                return;
            }
            Map<String, String> headers = new HashMap<String, String>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
            }
            String method = request[0], target = request[1], path = target, query = "";
            int mark = target.indexOf('?');
            if (mark >= 0) {
                path = target.substring(0, mark);
                query = target.substring(mark + 1);
            }
            route(method, path, params(query), headers, in, out);
        } catch (IOException ignored) {
            // The phone went away; nothing to report.
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
                // Closed.
            }
        }
    }

    private void route(String method, String path, Map<String, String> query, Map<String, String> headers, InputStream in, OutputStream out) throws IOException {
        if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
            listener.visited();
            respond(out, 200, "text/html; charset=utf-8", page.html());
            return;
        }
        if (!path.startsWith("/api/")) {
            respond(out, 404, "text/plain", "Not found".getBytes(UTF8));
            return;
        }
        if (!pinMatches(query.get("pin"))) {
            respond(out, 403, "application/json", "{\"error\":\"pin\"}".getBytes(UTF8));
            return;
        }
        if ("GET".equals(method) && "/api/list".equals(path)) {
            respond(out, 200, "application/json", ("{\"limit\":" + PhotoStore.LIMIT + ",\"photos\":" + store.listJson() + "}").getBytes(UTF8));
        } else if ("GET".equals(method) && "/api/thumb".equals(path)) {
            String id = query.get("id");
            java.io.File thumb = PhotoStore.validId(id) ? store.file(id, "thumb") : null;
            if (thumb == null || !thumb.isFile()) thumb = PhotoStore.validId(id) ? store.incomingFile(id) : null;
            if (thumb == null || !thumb.isFile()) respond(out, 404, "text/plain", "Not found".getBytes(UTF8));
            else respond(out, 200, "image/jpeg", PhotoStore.read(thumb));
        } else if ("POST".equals(method) && "/api/upload".equals(path)) {
            byte[] body = readBody(in, headers);
            if (body == null) {
                respond(out, 413, "application/json", "{\"error\":\"size\"}".getBytes(UTF8));
                return;
            }
            String result = store.accept(body);
            if ("full".equals(result) || "invalid".equals(result)) {
                respond(out, "full".equals(result) ? 409 : 400, "application/json", ("{\"error\":\"" + result + "\"}").getBytes(UTF8));
                return;
            }
            respond(out, 200, "application/json", ("{\"id\":\"" + result + "\"}").getBytes(UTF8));
            listener.received(result);
        } else if ("POST".equals(method) && "/api/delete".equals(path)) {
            readBody(in, headers);
            String id = query.get("id");
            boolean removed = store.remove(id);
            respond(out, removed ? 200 : 404, "application/json", ("{\"removed\":" + removed + "}").getBytes(UTF8));
            if (removed) listener.removed(id);
        } else {
            respond(out, 404, "text/plain", "Not found".getBytes(UTF8));
        }
    }

    private synchronized boolean pinMatches(String value) {
        if (pin.equals(value)) return true;
        if (++failures >= MAX_FAILURES) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    stop("pin");
                }
            }).start();
        }
        return false;
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0, b;
        while ((b = in.read()) >= 0) {
            head.write(b);
            if (head.size() > 16384) return null;
            state = (b == '\r' && (state == 0 || state == 2)) ? state + 1 : (b == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            if (state == 4) return new String(head.toByteArray(), LATIN1).trim();
        }
        return null;
    }

    private static byte[] readBody(InputStream in, Map<String, String> headers) throws IOException {
        int length;
        try {
            length = Integer.parseInt(headers.containsKey("content-length") ? headers.get("content-length") : "0");
        } catch (NumberFormatException bad) {
            return null;
        }
        if (length < 0 || length > PhotoStore.MAX_UPLOAD) return null;
        byte[] body = new byte[length];
        int offset = 0, n;
        while (offset < length && (n = in.read(body, offset, length - offset)) > 0) offset += n;
        return offset == length ? body : null;
    }

    private static Map<String, String> params(String query) {
        Map<String, String> out = new HashMap<String, String>();
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            try {
                out.put(URLDecoder.decode(part.substring(0, eq), "UTF-8"), URLDecoder.decode(part.substring(eq + 1), "UTF-8"));
            } catch (IllegalArgumentException | java.io.UnsupportedEncodingException ignored) {
                // Skip malformed parameters.
            }
        }
        return out;
    }

    private static void respond(OutputStream out, int status, String type, byte[] body) throws IOException {
        String reason = status == 200 ? "OK" : status == 403 ? "Forbidden" : status == 404 ? "Not Found" : status == 409 ? "Conflict" : status == 413 ? "Payload Too Large" : "Bad Request";
        String head = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Referrer-Policy: no-referrer\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(LATIN1));
        out.write(body);
        out.flush();
    }
}
