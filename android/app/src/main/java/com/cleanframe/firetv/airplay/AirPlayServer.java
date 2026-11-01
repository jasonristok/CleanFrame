/*
 * CleanFrame AirPlay receiver. SPDX-License-Identifier: GPL-3.0-or-later
 *
 * The RTSP side of AirPlay screen mirroring and audio, as an Apple TV 3 ("legacy" AirPlay):
 * /info, pairing, FairPlay key exchange, SETUP of the mirror and audio streams, volume and
 * teardown. The protocol follows UxPlay (github.com/FDH2/UxPlay, GPL-3.0), which in turn builds
 * on RPiPlay and shairplay. Pure Java without Android classes, so it runs in desktop tests too.
 */
package com.cleanframe.firetv.airplay;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class AirPlayServer {
    public static final String SOURCE_VERSION = "220.68";
    public static final String MODEL = "AppleTV3,2";
    /** Features bits 0-31 as an Apple TV 3 with mirroring, audio and legacy pairing (bit 27). */
    static final long FEATURES = 0x5A7FFEE6L;
    static final String PI = "2e388006-13ba-4041-9a67-25dd4a43d536";
    static final String DISPLAY_UUID = "e0ff8a27-6738-3d56-8a16-cc53aacee925";

    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_HEADERS = 40;
    private static final int MAX_LINE = 4096;
    private static final int MAX_BODY = 8 << 20;
    private static final int IDLE_TIMEOUT_MS = 60000;
    private static final int MAX_CODE_ATTEMPTS = 3;

    /** Events for the TV interface. Called on network threads; implementations hand over to the UI thread. */
    public interface Listener {
        /** Show this 4-digit code so the sender can type it, or hide it (null). */
        void code(String code);

        /** A phone, tablet or Mac started a session. */
        void connected(String clientName, String clientModel);

        void mirroring(boolean active);

        /** The session ended: the sender stopped, disconnected, another sender took over, or stop(). */
        void disconnected();

        void log(String message);
    }

    private final String name;
    private final byte[] deviceId;
    private final Curve25519.Ed25519 key;
    private final Playfair playfair;
    private final Sinks.Factory sinks;
    private final Listener listener;
    private final int width, height, refreshRate, maxFps;

    private volatile boolean requireCode;
    private volatile float volume = 0f;
    private volatile boolean running;

    private final Object lock = new Object();
    private final Set<Connection> connections = new HashSet<Connection>();
    private Connection owner;
    private String code, codeClient, nonce;
    private Connection codeConnection;
    private int codeShown;
    private ServerSocket server;

    /**
     * @param name what senders show in their AirPlay list
     * @param deviceId 6 bytes, the receiver's stable "hardware address" (a random one works)
     * @param seed 32 bytes, the stable Ed25519 identity used for pairing
     */
    public AirPlayServer(String name, byte[] deviceId, byte[] seed, Playfair playfair, Sinks.Factory sinks, Listener listener) {
        this(name, deviceId, seed, playfair, sinks, listener, 1920, 1080, 60, 30);
    }

    public AirPlayServer(String name, byte[] deviceId, byte[] seed, Playfair playfair, Sinks.Factory sinks, Listener listener,
                         int width, int height, int refreshRate, int maxFps) {
        if (deviceId == null || deviceId.length != 6) throw new IllegalArgumentException("deviceId");
        this.name = name;
        this.deviceId = deviceId.clone();
        this.key = new Curve25519.Ed25519(seed);
        this.playfair = playfair;
        this.sinks = sinks;
        this.listener = listener;
        this.width = width;
        this.height = height;
        this.refreshRate = refreshRate;
        this.maxFps = maxFps;
    }

    /* ---------- life cycle ---------- */

    /** Listens on the preferred port (AirPlay's usual 7000), or any free port if that is taken. Returns the port. */
    public int start(int preferredPort) throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        try {
            socket.bind(new InetSocketAddress(preferredPort), 16);
        } catch (IOException taken) {
            socket.close();
            socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(0), 16);
        }
        final ServerSocket bound = socket;
        synchronized (lock) {
            server = bound;
            running = true;
        }
        Thread accept = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop(bound);
            }
        }, "AirPlay-accept");
        accept.setDaemon(true);
        accept.start();
        log("listening on port " + bound.getLocalPort());
        return bound.getLocalPort();
    }

    public void stop() {
        List<Connection> open;
        synchronized (lock) {
            running = false;
            closeQuietly(server);
            server = null;
            open = new ArrayList<Connection>(connections);
        }
        for (Connection c : open) c.close();
    }

    /** Ends the current session, as if the sender had stopped. */
    public void endSession() {
        Connection current;
        synchronized (lock) {
            current = owner;
        }
        if (current != null) current.close();
    }

    public boolean sessionActive() {
        synchronized (lock) {
            return owner != null;
        }
    }

    public void setRequireCode(boolean on) {
        requireCode = on;
    }

    public boolean requireCode() {
        return requireCode;
    }

    public String name() {
        return name;
    }

    private void acceptLoop(ServerSocket socket) {
        while (running) {
            Socket client;
            try {
                client = socket.accept();
            } catch (IOException e) {
                if (running) log("accept failed: " + e);
                break;
            }
            final Connection c = new Connection(client);
            synchronized (lock) {
                if (!running) {
                    closeQuietly(client);
                    break;
                }
                connections.add(c);
            }
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    c.run();
                }
            }, "AirPlay-rtsp");
            t.setDaemon(true);
            t.start();
        }
    }

    /* ---------- service discovery ---------- */

    /** "AABBCCDDEEFF@Name", the _raop._tcp instance name. */
    public String raopName() {
        return hex(deviceId, true) + "@" + name;
    }

    /** "aa:bb:cc:dd:ee:ff" */
    public String deviceIdString() {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < deviceId.length; i++) {
            if (i > 0) s.append(':');
            s.append(String.format(Locale.ROOT, "%02x", deviceId[i] & 0xff));
        }
        return s.toString();
    }

    private String features() {
        return "0x" + Long.toHexString(FEATURES).toUpperCase(Locale.ROOT) + ",0x0";
    }

    /** TXT record of _airplay._tcp, in the order Apple TVs send it. */
    public Map<String, String> airplayTxt() {
        Map<String, String> t = new LinkedHashMap<String, String>();
        t.put("deviceid", deviceIdString());
        t.put("features", features());
        t.put("pw", requireCode ? "true" : "false");
        t.put("flags", "0x4");
        t.put("model", MODEL);
        t.put("pk", hex(key.publicKey, false));
        t.put("pi", PI);
        t.put("srcvers", SOURCE_VERSION);
        t.put("vv", "2");
        return t;
    }

    /** TXT record of _raop._tcp (the audio and mirroring service). */
    public Map<String, String> raopTxt() {
        Map<String, String> t = new LinkedHashMap<String, String>();
        t.put("ch", "2");
        t.put("cn", "0,1,2,3");
        t.put("da", "true");
        t.put("et", "0,3,5");
        t.put("vv", "2");
        t.put("ft", features());
        t.put("am", MODEL);
        t.put("md", "0,1,2");
        t.put("rhd", "5.6.0.0");
        t.put("pw", requireCode ? "true" : "false");
        t.put("sf", requireCode ? "0x84" : "0x4");
        t.put("sr", "44100");
        t.put("ss", "16");
        t.put("sv", "false");
        t.put("tp", "UDP");
        t.put("txtvers", "1");
        t.put("vs", SOURCE_VERSION);
        t.put("vn", "65537");
        t.put("pk", hex(key.publicKey, false));
        return t;
    }

    /** DNS TXT wire format: length-prefixed "key=value" strings. */
    static byte[] txtBytes(Map<String, String> txt) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, String> e : txt.entrySet()) {
            byte[] b = (e.getKey() + "=" + e.getValue()).getBytes(UTF8);
            out.write(Math.min(b.length, 255));
            out.write(b, 0, Math.min(b.length, 255));
        }
        return out.toByteArray();
    }

    /* ---------- one RTSP connection ---------- */

    final class Connection {
        private final Socket socket;
        private InputStream in;
        private OutputStream out;
        private volatile boolean closed;

        /* pairing (pair-setup / pair-verify) */
        private int pairState; // 0 initial, 1 setup, 2 handshake, 3 finished
        private byte[] ecdhOurs, ecdhOursPublic, ecdhTheirs, edTheirs, ecdhSecret;

        private final FairPlay fairPlay = new FairPlay(playfair);
        private boolean authenticated;

        /* session */
        private byte[] aesKey, aesIv;
        private String clientName = "", clientModel = "";
        private TimingClient timing;
        private MirrorReceiver mirror;
        private AudioReceiver audio;
        private boolean announcedMirroring, announcedSession;

        Connection(Socket socket) {
            this.socket = socket;
        }

        void run() {
            try {
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(IDLE_TIMEOUT_MS);
                in = new BufferedInputStream(socket.getInputStream(), 16384);
                out = socket.getOutputStream();
                while (!closed) {
                    Request request = Request.read(in);
                    if (request == null) break;
                    Response response = handle(request);
                    if (response == null) break;
                    synchronized (this) {
                        out.write(response.bytes());
                        out.flush();
                    }
                    if (response.disconnect) break;
                }
            } catch (SocketTimeoutException timeout) {
                log("connection idle, closing");
            } catch (SocketException gone) {
                // closed by the sender or by close()
            } catch (IOException | RuntimeException e) {
                if (!closed) log("connection error: " + e);
            } finally {
                close();
            }
        }

        void close() {
            boolean wasOwner;
            boolean hideCode;
            synchronized (lock) {
                if (closed) return;
                closed = true;
                connections.remove(this);
                wasOwner = owner == this;
                if (wasOwner) owner = null;
                hideCode = codeConnection == this;
                if (hideCode) codeConnection = null;
            }
            closeQuietly(socket);
            stopStreams(true);
            if (hideCode) listener.code(null);
            if (wasOwner) listener.disconnected();
        }

        private void stopStreams(boolean all) {
            MirrorReceiver m;
            AudioReceiver a;
            TimingClient t;
            synchronized (this) {
                m = mirror;
                a = audio;
                t = all ? timing : null;
                mirror = null;
                audio = null;
                if (all) timing = null;
            }
            if (m != null) m.stop();
            if (a != null) a.stop();
            if (t != null) t.stop();
            if (m != null && announcedMirroring) {
                announcedMirroring = false;
                listener.mirroring(false);
            }
        }

        private Response handle(Request r) throws IOException {
            String cseq = r.header("CSeq");
            boolean bluetooth = cseq == null && r.url.contains("txtAirPlay") || cseq == null && r.url.contains("txtRAOP");
            if (cseq == null && !bluetooth) {
                log("ignoring " + r.method + " " + r.url + " " + r.protocol);
                return null;
            }
            if (cseq != null) {
                try {
                    if (Integer.parseInt(cseq.trim()) < 0) return null;
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            Response res = new Response(r.protocol.startsWith("RTSP") ? "RTSP/1.0" : r.protocol, 200, "OK");
            if (cseq != null && !"RECORD".equals(r.method)) res.header("Audio-Jack-Status", "connected; type=digital");
            if (!"RTSP/1.0".equals(r.protocol)) {
                res.status(501, "Not Implemented");
            } else if ("POST".equals(r.method)) {
                if ("/feedback".equals(r.url)) {
                    // heartbeat
                } else if ("/pair-setup".equals(r.url)) {
                    pairSetup(r, res);
                } else if ("/pair-verify".equals(r.url)) {
                    pairVerify(r, res);
                } else if ("/fp-setup".equals(r.url)) {
                    fpSetup(r, res);
                } else if ("/audioMode".equals(r.url)) {
                    // "default"; nothing to change
                } else {
                    log("unhandled POST " + r.url);
                }
            } else if ("GET".equals(r.method) && r.url.contains("/info")) {
                info(r, res, cseq);
            } else if ("OPTIONS".equals(r.method)) {
                res.header("Public", "SETUP, RECORD, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER");
            } else if ("SETUP".equals(r.method)) {
                setup(r, res);
            } else if ("GET_PARAMETER".equals(r.method)) {
                getParameter(r, res);
            } else if ("SET_PARAMETER".equals(r.method)) {
                setParameter(r, res);
            } else if ("RECORD".equals(r.method)) {
                res.header("Audio-Latency", "0");
                res.header("Audio-Jack-Status", "connected; type=analog");
            } else if ("FLUSH".equals(r.method)) {
                flush(r);
            } else if ("TEARDOWN".equals(r.method)) {
                teardown(r, res);
            } else {
                res.status(501, "Not Implemented");
            }
            res.header("Server", "AirTunes/" + SOURCE_VERSION);
            if (cseq != null) res.header("CSeq", cseq.trim());
            return res;
        }

        /* ----- GET /info ----- */

        private void info(Request r, Response res, String cseq) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            String type = r.header("Content-Type");
            boolean txtAirPlay = false, txtRaop = false;
            if (type != null && type.contains("application/x-apple-binary-plist")) {
                try {
                    List<Object> qualifier = BPlist.list(BPlist.dict(BPlist.parse(r.body)), "qualifier");
                    Object first = qualifier == null || qualifier.isEmpty() ? null : qualifier.get(0);
                    txtAirPlay = "txtAirPlay".equals(first);
                    txtRaop = "txtRAOP".equals(first);
                } catch (IllegalArgumentException ignored) {
                    // answer without the TXT data
                }
            }
            if (cseq == null) {
                txtAirPlay = r.url.contains("txtAirPlay");
                txtRaop = r.url.contains("txtRAOP");
            }
            if (txtAirPlay) m.put("txtAirPlay", txtBytes(airplayTxt()));
            if (txtRaop) m.put("txtRAOP", txtBytes(raopTxt()));
            if (type == null) {
                m.put("deviceID", deviceIdString());
                m.put("macAddress", deviceIdString());
                m.put("pk", key.publicKey.clone());
                m.put("features", FEATURES);
                m.put("name", name);
                m.put("pi", PI);
                m.put("vv", 2L);
                m.put("statusFlags", 68L);
                m.put("keepAliveLowPower", Boolean.TRUE);
                m.put("sourceVersion", SOURCE_VERSION);
                m.put("keepAliveSendStatsAsBody", Boolean.TRUE);
                m.put("model", MODEL);
                if (cseq != null) {
                    m.put("initialVolume", (double) volume);
                    List<Object> latencies = new ArrayList<Object>();
                    List<Object> formats = new ArrayList<Object>();
                    for (long t : new long[] {100, 101}) {
                        Map<String, Object> l = new LinkedHashMap<String, Object>();
                        l.put("type", t);
                        l.put("inputLatencyMicros", 0L);
                        l.put("audioType", "default");
                        l.put("outputLatencyMicros", Boolean.FALSE);
                        latencies.add(l);
                        Map<String, Object> f = new LinkedHashMap<String, Object>();
                        f.put("audioOutputFormats", 0x3fffffcL);
                        f.put("type", t);
                        f.put("audioInputFormats", 0x3fffffcL);
                        formats.add(f);
                    }
                    m.put("audioLatencies", latencies);
                    m.put("audioFormats", formats);
                    Map<String, Object> d = new LinkedHashMap<String, Object>();
                    d.put("uuid", DISPLAY_UUID);
                    d.put("widthPhysical", 0L);
                    d.put("heightPhysical", 0L);
                    d.put("width", (long) width);
                    d.put("height", (long) height);
                    d.put("widthPixels", (long) width);
                    d.put("heightPixels", (long) height);
                    d.put("rotation", Boolean.FALSE);
                    d.put("refreshRate", 1.0 / refreshRate);
                    d.put("maxFPS", (long) maxFps);
                    d.put("overscanned", Boolean.FALSE);
                    d.put("features", 14L);
                    List<Object> displays = new ArrayList<Object>();
                    displays.add(d);
                    m.put("displays", displays);
                }
            }
            res.body("application/x-apple-binary-plist", BPlist.write(m));
        }

        /* ----- pairing ----- */

        private void pairSetup(Request r, Response res) {
            if (r.body.length != 32) {
                log("invalid pair-setup data");
                return;
            }
            pairState = 1;
            res.body("application/octet-stream", key.publicKey.clone());
        }

        private void pairVerify(Request r, Response res) throws IOException {
            if (pairState != 1 && pairState != 2) return;
            byte[] d = r.body;
            if (d.length < 4) return;
            if (d[0] == 1) {
                if (d.length != 4 + 32 + 32) return;
                ecdhTheirs = Arrays.copyOfRange(d, 4, 36);
                edTheirs = Arrays.copyOfRange(d, 36, 68);
                ecdhOurs = new byte[32];
                Curve25519.RANDOM.nextBytes(ecdhOurs);
                ecdhOursPublic = Curve25519.x25519Base(ecdhOurs);
                ecdhSecret = Curve25519.x25519(ecdhOurs, ecdhTheirs);
                pairState = 2;
                byte[] signature = key.sign(concat(ecdhOursPublic, ecdhTheirs));
                Cipher ctr = verifyCipher();
                byte[] encrypted = ctr.update(signature);
                res.body("application/octet-stream", concat(ecdhOursPublic, encrypted));
            } else if (d[0] == 0) {
                if (d.length != 4 + 64 || pairState != 2) return;
                Cipher ctr = verifyCipher();
                ctr.update(new byte[64]); // the keystream already used for our signature
                byte[] signature = ctr.update(Arrays.copyOfRange(d, 4, 68));
                if (!Curve25519.Ed25519.verify(edTheirs, concat(ecdhTheirs, ecdhOursPublic), signature)) {
                    log("pair-verify: signature does not match");
                    res.disconnect = true;
                    return;
                }
                pairState = 3;
                res.header("Content-Type", "application/octet-stream");
            }
        }

        private Cipher verifyCipher() {
            byte[] k = Arrays.copyOf(Curve25519.sha512("Pair-Verify-AES-Key".getBytes(LATIN1), ecdhSecret), 16);
            byte[] iv = Arrays.copyOf(Curve25519.sha512("Pair-Verify-AES-IV".getBytes(LATIN1), ecdhSecret), 16);
            return Crypto.aes("AES/CTR/NoPadding", Cipher.ENCRYPT_MODE, k, iv);
        }

        private void fpSetup(Request r, Response res) {
            if (r.body.length == 16) {
                if (r.body[4] != 0x03) {
                    log("unsupported FairPlay version " + r.body[4]);
                    res.status(501, "Not Implemented");
                    return;
                }
                byte[] reply = fairPlay.setup(r.body);
                if (reply != null) res.body("application/octet-stream", reply);
                else res.header("Content-Type", "application/octet-stream");
            } else if (r.body.length == 164) {
                byte[] reply = fairPlay.handshake(r.body);
                if (reply != null) res.body("application/octet-stream", reply);
                else res.header("Content-Type", "application/octet-stream");
            } else {
                log("invalid fp-setup length " + r.body.length);
            }
        }

        /* ----- SETUP ----- */

        private void setup(Request r, Response res) throws IOException {
            Map<String, Object> req;
            try {
                req = BPlist.dict(BPlist.parse(r.body));
            } catch (IllegalArgumentException e) {
                res.status(400, "Bad Request");
                return;
            }
            Map<String, Object> reply = new LinkedHashMap<String, Object>();
            byte[] ekey = BPlist.data(req, "ekey"), eiv = BPlist.data(req, "eiv");
            if (ekey != null && eiv != null) {
                String senderId = BPlist.string(req, "deviceID");
                if (requireCode && !authenticated && !checkCode(r, res, senderId)) return;
                if (ekey.length != 72 || eiv.length != 16) {
                    res.status(400, "Bad Request");
                    return;
                }
                byte[] k = fairPlay.decrypt(ekey);
                if (k == null) {
                    log("SETUP before fp-setup");
                    res.status(400, "Bad Request");
                    return;
                }
                if (pairState >= 2 && ecdhSecret != null) k = Arrays.copyOf(Curve25519.sha512(k, ecdhSecret), 16);
                aesKey = k;
                aesIv = eiv.clone();
                String n = BPlist.string(req, "name");
                String model = BPlist.string(req, "model");
                clientName = n != null ? n : "";
                clientModel = model != null ? model : "";

                takeOver();

                long remoteTiming = BPlist.number(req, "timingPort", 0);
                InetAddress remote = socket.getInetAddress();
                TimingClient t = new TimingClient(remote, (int) remoteTiming);
                int timingPort = t.start();
                TimingClient old;
                synchronized (this) {
                    old = timing;
                    timing = t;
                }
                if (old != null) old.stop();
                reply.put("timingPort", (long) timingPort);
                reply.put("eventPort", 0L);
                if (!announcedSession) {
                    announcedSession = true;
                    listener.connected(clientName, clientModel);
                }
                log("session from " + clientName + " (" + clientModel + ")" + (pairState == 3 ? ", paired" : ""));
            }

            List<Object> streams = BPlist.list(req, "streams");
            if (streams != null) {
                List<Object> out = new ArrayList<Object>();
                for (Object o : streams) {
                    Map<String, Object> s = BPlist.dict(o);
                    long type = BPlist.number(s, "type", -1);
                    if (aesKey == null) {
                        res.disconnect = true;
                        break;
                    }
                    if (type == 110) {
                        BigInteger id = new BigInteger(1, longBytes(BPlist.number(s, "streamConnectionID", 0)));
                        MirrorReceiver m = new MirrorReceiver(aesKey, id.toString(), sinks.video(), mirrorEvents());
                        int port = m.start();
                        MirrorReceiver old;
                        synchronized (this) {
                            old = mirror;
                            mirror = m;
                        }
                        if (old != null) old.stop();
                        Map<String, Object> o2 = new LinkedHashMap<String, Object>();
                        o2.put("dataPort", (long) port);
                        o2.put("type", 110L);
                        out.add(o2);
                    } else if (type == 96) {
                        int ct = (int) BPlist.number(s, "ct", 0);
                        int spf = (int) BPlist.number(s, "spf", 0);
                        int remoteControl = (int) BPlist.number(s, "controlPort", 0);
                        AudioReceiver a = new AudioReceiver(aesKey, aesIv, ct, spf, socket.getInetAddress(), remoteControl, sinks.audio(), AirPlayServer.this);
                        int[] ports = a.start();
                        a.volume(volume);
                        AudioReceiver old;
                        synchronized (this) {
                            old = audio;
                            audio = a;
                        }
                        if (old != null) old.stop();
                        Map<String, Object> o2 = new LinkedHashMap<String, Object>();
                        o2.put("dataPort", (long) ports[0]);
                        o2.put("controlPort", (long) ports[1]);
                        o2.put("type", 96L);
                        out.add(o2);
                        log("audio stream ct=" + ct + " spf=" + spf);
                    } else {
                        log("unknown stream type " + type);
                        res.disconnect = true;
                    }
                }
                reply.put("streams", out);
            }
            res.body("application/x-apple-binary-plist", BPlist.write(reply));
        }

        /** Ends any other sender's session: the newest sender wins, like on an Apple TV. */
        private void takeOver() {
            Connection previous;
            synchronized (lock) {
                previous = owner != this ? owner : null;
            }
            if (previous != null) {
                log("new sender takes over from " + previous.clientName);
                previous.close(); // reports disconnected() for the previous sender first
            }
            synchronized (lock) {
                owner = this;
            }
        }

        /** RFC 2617 digest with a 4-digit code shown on the TV. Returns true once the sender typed it. */
        private boolean checkCode(Request r, Response res, String senderId) {
            String authorization = r.header("Authorization");
            boolean show = false;
            synchronized (lock) {
                if (authorization == null) {
                    if (code == null || senderId == null || !senderId.equals(codeClient) || codeShown >= MAX_CODE_ATTEMPTS) {
                        code = String.format(Locale.ROOT, "%04d", Curve25519.RANDOM.nextInt(10000));
                        codeClient = senderId;
                        codeShown = 0;
                    }
                    codeShown++;
                    codeConnection = this;
                    show = true;
                } else if (code != null && nonce != null) {
                    String sent = Digest.token(authorization, "nonce");
                    authenticated = nonce.equals(sent) && Digest.verify(r.method, authorization, code);
                    nonce = null;
                    if (authenticated) {
                        code = null;
                        codeClient = null;
                        if (codeConnection != null) codeConnection = null;
                    }
                }
            }
            if (show) {
                listener.code(currentCode());
                log("sender must enter the code shown on the TV");
            }
            if (authenticated) {
                listener.code(null);
                return true;
            }
            byte[] n = new byte[16];
            Curve25519.RANDOM.nextBytes(n);
            synchronized (lock) {
                nonce = hex(n, false);
                res.status(401, "Unauthorized");
                res.header("WWW-Authenticate", "Digest realm=\"raop\", nonce=\"" + nonce + "\"");
            }
            return false;
        }

        private String currentCode() {
            synchronized (lock) {
                return code;
            }
        }

        private MirrorReceiver.Events mirrorEvents() {
            return new MirrorReceiver.Events() {
                @Override
                public void started() {
                    if (!announcedMirroring) {
                        announcedMirroring = true;
                        listener.mirroring(true);
                    }
                }

                @Override
                public void ended() {
                    if (announcedMirroring) {
                        announcedMirroring = false;
                        listener.mirroring(false);
                    }
                }

                @Override
                public void log(String message) {
                    AirPlayServer.this.log(message);
                }
            };
        }

        /* ----- parameters ----- */

        private void getParameter(Request r, Response res) {
            String type = r.header("Content-Type");
            if (type == null) {
                res.status(451, "Parameter not understood");
                return;
            }
            if ("text/parameters".equals(type) && new String(r.body, LATIN1).startsWith("volume")) {
                res.body("text/parameters", String.format(Locale.ROOT, "volume: %9.6f\r\n", volume).getBytes(LATIN1));
            }
        }

        private void setParameter(Request r, Response res) {
            String type = r.header("Content-Type");
            if (type == null) {
                res.status(451, "Parameter not understood");
                return;
            }
            if ("text/parameters".equals(type)) {
                String text = new String(r.body, LATIN1).trim();
                if (text.startsWith("volume:")) {
                    try {
                        float v = Float.parseFloat(text.substring(7).trim());
                        volume = v;
                        log("volume " + v);
                        AudioReceiver a;
                        synchronized (this) {
                            a = audio;
                        }
                        if (a != null) a.volume(v);
                    } catch (NumberFormatException ignored) {
                        // keep the previous volume
                    }
                }
                // "progress: start/current/end" (music): not shown
            }
            // cover art (image/jpeg) and track names (application/x-dmap-tagged) are not shown
        }

        private void flush(Request r) {
            int next = -1;
            String info = r.header("RTP-Info");
            if (info != null) {
                int i = info.indexOf("seq=");
                if (i >= 0) {
                    int j = i + 4;
                    while (j < info.length() && Character.isDigit(info.charAt(j))) j++;
                    try {
                        next = Integer.parseInt(info.substring(i + 4, j));
                    } catch (NumberFormatException ignored) {
                        next = -1;
                    }
                }
            }
            AudioReceiver a;
            synchronized (this) {
                a = audio;
            }
            if (a != null) a.flush(next);
        }

        private void teardown(Request r, Response res) {
            boolean audioOnly = false, mirrorOnly = false;
            try {
                List<Object> streams = BPlist.list(BPlist.dict(BPlist.parse(r.body)), "streams");
                if (streams != null) {
                    for (Object o : streams) {
                        long type = BPlist.number(BPlist.dict(o), "type", -1);
                        if (type == 96) audioOnly = true;
                        else if (type == 110) mirrorOnly = true;
                    }
                }
            } catch (IllegalArgumentException noBody) {
                // a plain TEARDOWN ends everything
            }
            res.header("Connection", "close");
            if (audioOnly) {
                AudioReceiver a;
                synchronized (this) {
                    a = audio;
                    audio = null;
                }
                if (a != null) a.stop();
            } else if (mirrorOnly) {
                MirrorReceiver m;
                synchronized (this) {
                    m = mirror;
                    mirror = null;
                }
                if (m != null) m.stop();
                if (announcedMirroring) {
                    announcedMirroring = false;
                    listener.mirroring(false);
                }
            } else {
                stopStreams(true);
                boolean wasOwner;
                synchronized (lock) {
                    wasOwner = owner == this;
                    if (wasOwner) owner = null;
                }
                aesKey = null;
                announcedSession = false;
                if (wasOwner) listener.disconnected();
            }
        }
    }

    /* ---------- RTSP messages ---------- */

    static final class Request {
        String method, url, protocol;
        final Map<String, String> headers = new LinkedHashMap<String, String>();
        byte[] body = new byte[0];

        String header(String name) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
            }
            return null;
        }

        /** Null at the end of the stream. */
        static Request read(InputStream in) throws IOException {
            String line = readLine(in);
            while (line != null && line.isEmpty()) line = readLine(in);
            if (line == null) return null;
            String[] parts = line.split(" ");
            if (parts.length != 3) throw new IOException("bad request line");
            Request r = new Request();
            r.method = parts[0];
            r.url = parts[1];
            r.protocol = parts[2];
            for (int i = 0; ; i++) {
                String h = readLine(in);
                if (h == null) return null;
                if (h.isEmpty()) break;
                if (i >= MAX_HEADERS) throw new IOException("too many headers");
                int colon = h.indexOf(':');
                if (colon <= 0) continue;
                r.headers.put(h.substring(0, colon).trim(), h.substring(colon + 1).trim());
            }
            String length = r.header("Content-Length");
            if (length != null) {
                int n;
                try {
                    n = Integer.parseInt(length.trim());
                } catch (NumberFormatException e) {
                    throw new IOException("bad Content-Length");
                }
                if (n < 0 || n > MAX_BODY) throw new IOException("body too large");
                r.body = readFully(in, n);
            }
            return r;
        }

        private static String readLine(InputStream in) throws IOException {
            StringBuilder s = new StringBuilder();
            while (true) {
                int c = in.read();
                if (c < 0) return s.length() == 0 ? null : s.toString();
                if (c == '\n') break;
                if (c != '\r') s.append((char) c);
                if (s.length() > MAX_LINE) throw new IOException("line too long");
            }
            return s.toString();
        }
    }

    static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int got = in.read(b, off, n - off);
            if (got < 0) throw new IOException("stream ended");
            off += got;
        }
        return b;
    }

    static final class Response {
        private final String protocol;
        private int status;
        private String reason;
        private final List<String[]> headers = new ArrayList<String[]>();
        private byte[] body;
        boolean disconnect;

        Response(String protocol, int status, String reason) {
            this.protocol = protocol;
            this.status = status;
            this.reason = reason;
        }

        void status(int status, String reason) {
            this.status = status;
            this.reason = reason;
        }

        int status() {
            return status;
        }

        void header(String name, String value) {
            headers.add(new String[] {name, value});
        }

        void body(String type, byte[] data) {
            header("Content-Type", type);
            body = data;
        }

        byte[] bytes() {
            StringBuilder s = new StringBuilder();
            s.append(protocol).append(' ').append(status).append(' ').append(reason).append("\r\n");
            boolean hasType = false;
            for (String[] h : headers) {
                s.append(h[0]).append(": ").append(h[1]).append("\r\n");
                if (h[0].equalsIgnoreCase("Content-Type")) hasType = true;
            }
            if (body != null && body.length > 0) s.append("Content-Length: ").append(body.length).append("\r\n");
            else if (hasType) s.append("Content-Length: 0\r\n");
            s.append("\r\n");
            byte[] head = s.toString().getBytes(UTF8);
            if (body == null || body.length == 0) return head;
            return concat(head, body);
        }
    }

    /* ---------- helpers ---------- */

    void log(String message) {
        try {
            listener.log(message);
        } catch (RuntimeException ignored) {
            // logging must never break the stream
        }
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    static byte[] longBytes(long v) {
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) b[i] = (byte) (v >>> (56 - 8 * i));
        return b;
    }

    static String hex(byte[] b, boolean upper) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(String.format(Locale.ROOT, upper ? "%02X" : "%02x", x & 0xff));
        return s.toString();
    }

    static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    /* AES and MD5 through the platform providers (Android's Conscrypt / the JDK). */
    static final class Crypto {
        private Crypto() {}

        static Cipher aes(String transformation, int mode, byte[] key, byte[] iv) {
            try {
                Cipher c = Cipher.getInstance(transformation);
                c.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
                return c;
            } catch (Exception e) {
                throw new IllegalStateException(transformation, e);
            }
        }

        static String md5Hex(String s) {
            try {
                return hex(MessageDigest.getInstance("MD5").digest(s.getBytes(UTF8)), false);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** RFC 2617 Digest, as AirPlay senders use it for the receiver password ("code"). */
    static final class Digest {
        private Digest() {}

        static String token(String header, String name) {
            int i = 0;
            while (true) {
                i = header.indexOf(name, i);
                if (i < 0) return null;
                int j = i + name.length();
                boolean boundary = i == 0 || !Character.isLetterOrDigit(header.charAt(i - 1));
                while (j < header.length() && header.charAt(j) == ' ') j++;
                if (boundary && j < header.length() && header.charAt(j) == '=') {
                    j++;
                    while (j < header.length() && header.charAt(j) == ' ') j++;
                    if (j < header.length() && header.charAt(j) == '"') {
                        int end = header.indexOf('"', j + 1);
                        return end < 0 ? null : header.substring(j + 1, end);
                    }
                    int end = j;
                    while (end < header.length() && header.charAt(end) != ',') end++;
                    return header.substring(j, end).trim();
                }
                i = j;
            }
        }

        static boolean verify(String method, String authorization, String password) {
            String username = token(authorization, "username"), realm = token(authorization, "realm");
            String nonce = token(authorization, "nonce"), uri = token(authorization, "uri");
            String response = token(authorization, "response"), qop = token(authorization, "qop");
            if (username == null || realm == null || nonce == null || uri == null || response == null || password == null) return false;
            String ha1 = Crypto.md5Hex(username + ":" + realm + ":" + password);
            String ha2 = Crypto.md5Hex(method + ":" + uri);
            String expected;
            if (qop != null) {
                String nc = token(authorization, "nc"), cnonce = token(authorization, "cnonce");
                if (nc == null || cnonce == null) return false;
                expected = Crypto.md5Hex(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2);
            } else {
                expected = Crypto.md5Hex(ha1 + ":" + nonce + ":" + ha2);
            }
            return expected.equalsIgnoreCase(response);
        }
    }
}
