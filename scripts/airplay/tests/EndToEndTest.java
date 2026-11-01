/* CleanFrame AirPlay receiver tests (desktop, see ../test.sh). SPDX-License-Identifier: GPL-3.0-or-later */
package com.cleanframe.firetv.airplay;

import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.*;
import java.security.spec.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/**
 * A fake iPhone against the real server: discovery info, pairing (checked with the JDK's own
 * Ed25519/X25519), FairPlay (expected key from the C playfair vectors), SETUP of mirror and
 * audio, an ffmpeg-made H.264 stream encrypted with the JDK's AES-CTR, AAC-ELD-style audio sent
 * three times with a gap that must be resent, volume, teardown, takeover and the TV code.
 */
public final class EndToEndTest {
    static int fails = 0;
    static void check(String name, boolean ok) { System.out.println((ok ? "ok - " : "FAIL - ") + name); if (!ok) fails++; }

    static Path fixtures, work;
    static final List<String> events = Collections.synchronizedList(new ArrayList<String>());
    static volatile String shownCode;

    /* ---------- sinks that record ---------- */
    static final class VideoFile implements Sinks.Video {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        int frames, configures, width, height; boolean released, paused;
        public synchronized void configure(byte[] sps, byte[] pps, int w, int h) {
            configures++; width = w; height = h;
            out.write(0); out.write(0); out.write(0); out.write(1); out.write(sps, 0, sps.length);
            out.write(0); out.write(0); out.write(0); out.write(1); out.write(pps, 0, pps.length);
        }
        public synchronized void frame(byte[] annexB, long t) { frames++; out.write(annexB, 0, annexB.length); }
        public synchronized void pause() { paused = true; }
        public synchronized void release() { released = true; }
    }
    static final class AudioList implements Sinks.Audio {
        final List<byte[]> frames = Collections.synchronizedList(new ArrayList<byte[]>());
        final List<Long> times = Collections.synchronizedList(new ArrayList<Long>());
        int ct, spf; float volume = 99; boolean released; int flushes;
        public void start(int c, int s) { ct = c; spf = s; }
        public void frame(byte[] d, long ts) { frames.add(d.clone()); times.add(ts); }
        public void volume(float db) { volume = db; }
        public void flush() { flushes++; }
        public void release() { released = true; }
    }
    static final List<VideoFile> videos = new ArrayList<>();
    static final List<AudioList> audios = new ArrayList<>();

    /* ---------- tiny RTSP client ---------- */
    static final class Client implements Closeable {
        final Socket s; final InputStream in; final OutputStream out; int cseq = 0;
        Client(int port) throws IOException { s = new Socket("127.0.0.1", port); s.setSoTimeout(10000); in = new BufferedInputStream(s.getInputStream()); out = s.getOutputStream(); }
        Reply send(String method, String url, String type, byte[] body, String... extra) throws IOException {
            StringBuilder h = new StringBuilder(method + " " + url + " RTSP/1.0\r\nCSeq: " + (++cseq) + "\r\nUser-Agent: AirPlay/790.0.0\r\n");
            for (int i = 0; i + 1 < extra.length; i += 2) h.append(extra[i]).append(": ").append(extra[i + 1]).append("\r\n");
            if (type != null) h.append("Content-Type: ").append(type).append("\r\n");
            if (body != null) h.append("Content-Length: ").append(body.length).append("\r\n");
            h.append("\r\n");
            out.write(h.toString().getBytes("ISO-8859-1")); if (body != null) out.write(body); out.flush();
            return Reply.read(in);
        }
        Reply plist(String method, String url, Map<String, Object> m, String... extra) throws IOException {
            return send(method, url, "application/x-apple-binary-plist", BPlist.write(m), extra);
        }
        public void close() throws IOException { s.close(); }
    }
    static final class Reply {
        int status; Map<String, String> headers = new LinkedHashMap<>(); byte[] body = new byte[0];
        static Reply read(InputStream in) throws IOException {
            String line = readLine(in); if (line == null) return null;
            Reply r = new Reply(); r.status = Integer.parseInt(line.split(" ")[1]);
            for (String h; (h = readLine(in)) != null && !h.isEmpty(); ) { int c = h.indexOf(':'); r.headers.put(h.substring(0, c).trim(), h.substring(c + 1).trim()); }
            String len = r.headers.get("Content-Length");
            if (len != null) r.body = AirPlayServer.readFully(in, Integer.parseInt(len));
            return r;
        }
        static String readLine(InputStream in) throws IOException {
            StringBuilder s = new StringBuilder(); int c;
            while ((c = in.read()) >= 0 && c != '\n') if (c != '\r') s.append((char) c);
            return c < 0 && s.length() == 0 ? null : s.toString();
        }
        Map<String, Object> dict() { return BPlist.dict(BPlist.parse(body)); }
    }

    /* ---------- JDK crypto for the "phone" ---------- */
    static byte[] le(BigInteger v) { byte[] be = v.toByteArray(), o = new byte[32]; for (int i = 0; i < 32 && i < be.length; i++) o[i] = be[be.length - 1 - i]; return o; }
    static BigInteger fromLe(byte[] b) { byte[] be = new byte[33]; for (int i = 0; i < 32; i++) be[32 - i] = b[i]; return new BigInteger(be); }
    static PublicKey edPublic(byte[] raw) throws Exception {
        byte[] c = raw.clone(); boolean xOdd = (c[31] & 0x80) != 0; c[31] &= 0x7f;
        return KeyFactory.getInstance("Ed25519").generatePublic(new EdECPublicKeySpec(NamedParameterSpec.ED25519, new EdECPoint(xOdd, fromLe(c))));
    }
    static byte[] sha512(byte[]... parts) throws Exception { MessageDigest md = MessageDigest.getInstance("SHA-512"); for (byte[] p : parts) md.update(p); return md.digest(); }
    static byte[] cat(byte[]... parts) { ByteArrayOutputStream o = new ByteArrayOutputStream(); for (byte[] p : parts) o.write(p, 0, p.length); return o.toByteArray(); }

    static byte[][] fairPlayVector() throws IOException {
        byte[] all = Files.readAllBytes(fixtures.resolve("playfair-vectors.bin"));
        int p = 0;
        while (p < all.length) {
            int type = all[p++];
            int len = type == 1 ? 80 : type == 2 ? 96 : type == 3 ? 292 : type == 4 ? 48 : 252;
            if (type == 5 && all[p + 4] == 3) return new byte[][] {Arrays.copyOfRange(all, p, p + 164), Arrays.copyOfRange(all, p + 164, p + 236), Arrays.copyOfRange(all, p + 236, p + 252)};
            p += len;
        }
        throw new IllegalStateException("no FairPlay vector with type 3");
    }

    /** Pairs like iOS; returns the shared X25519 secret. */
    static byte[] pair(Client c, byte[] serverPk, boolean breakSignature) throws Exception {
        byte[] edSeed = new byte[32]; new SecureRandom().nextBytes(edSeed);
        byte[] edPub = new Curve25519.Ed25519(edSeed).publicKey;
        Reply r = c.send("POST", "/pair-setup", "application/octet-stream", edPub);
        check("pair-setup returns the receiver's identity key", r.status == 200 && Arrays.equals(r.body, serverPk));
        KeyPair x = KeyPairGenerator.getInstance("X25519").generateKeyPair();
        byte[] xPub = le(((XECPublicKey) x.getPublic()).getU());
        r = c.send("POST", "/pair-verify", "application/octet-stream", cat(new byte[] {1, 0, 0, 0}, xPub, edPub));
        check("pair-verify 1 answers with key and signature", r.status == 200 && r.body.length == 96);
        byte[] theirX = Arrays.copyOf(r.body, 32);
        KeyAgreement ka = KeyAgreement.getInstance("X25519");
        ka.init(x.getPrivate());
        ka.doPhase(KeyFactory.getInstance("X25519").generatePublic(new XECPublicKeySpec(NamedParameterSpec.X25519, fromLe(theirX))), true);
        byte[] secret = ka.generateSecret();
        Cipher ctr = Cipher.getInstance("AES/CTR/NoPadding");
        ctr.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Arrays.copyOf(sha512("Pair-Verify-AES-Key".getBytes(), secret), 16), "AES"),
                 new IvParameterSpec(Arrays.copyOf(sha512("Pair-Verify-AES-IV".getBytes(), secret), 16)));
        byte[] theirSig = ctr.update(Arrays.copyOfRange(r.body, 32, 96));
        Signature v = Signature.getInstance("Ed25519"); v.initVerify(edPublic(serverPk)); v.update(cat(theirX, xPub));
        check("receiver's pair-verify signature checks out with the JDK", v.verify(theirSig));
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(KeyFactory.getInstance("Ed25519").generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, edSeed)));
        s.update(cat(xPub, theirX));
        byte[] mine = s.sign();
        if (breakSignature) mine[3] ^= 0x40;
        r = c.send("POST", "/pair-verify", "application/octet-stream", cat(new byte[] {0, 0, 0, 0}, ctr.update(mine)));
        if (breakSignature) check("a wrong pair-verify signature closes the connection", r == null || c.in.read() < 0);
        else check("pair-verify 2 accepted", r.status == 200);
        return secret;
    }

    static void fairPlay(Client c, byte[] message3) throws IOException {
        byte[] req1 = {0x46, 0x50, 0x4c, 0x59, 0x03, 0x01, 0x01, 0x00, 0, 0, 0, 0x04, 0x02, 0x00, message3[12], (byte) 0xbb};
        Reply r = c.send("POST", "/fp-setup", "application/octet-stream", req1);
        check("fp-setup 1 answers 142 bytes", r.status == 200 && r.body.length == 142 && r.body[13] == message3[12]);
        r = c.send("POST", "/fp-setup", "application/octet-stream", message3);
        check("fp-setup 2 answers header + last 20 bytes", r.status == 200 && r.body.length == 32 && Arrays.equals(Arrays.copyOfRange(r.body, 12, 32), Arrays.copyOfRange(message3, 144, 164)));
    }

    /* ---------- the phone's UDP side ---------- */
    static final class Udp implements Closeable {
        final DatagramSocket s; final List<byte[]> got = Collections.synchronizedList(new ArrayList<byte[]>()); volatile boolean timing;
        volatile Map<Integer, byte[]> resendSource; volatile int resendTo; final List<int[]> resendRequests = Collections.synchronizedList(new ArrayList<int[]>());
        Udp(boolean timing) throws IOException {
            s = new DatagramSocket(0, InetAddress.getLoopbackAddress()); this.timing = timing;
            Thread t = new Thread(() -> {
                byte[] b = new byte[2048];
                while (!s.isClosed()) {
                    DatagramPacket p = new DatagramPacket(b, b.length);
                    try { s.receive(p); } catch (IOException e) { break; }
                    byte[] d = Arrays.copyOf(p.getData(), p.getLength()); got.add(d);
                    try {
                        if (this.timing && (d[1] & 0x7f) == 0x52) {
                            byte[] reply = new byte[32]; reply[0] = (byte) 0x80; reply[1] = (byte) 0xd3; reply[3] = 7;
                            System.arraycopy(d, 24, reply, 8, 8);
                            TimingClient.putNtp(reply, 16, 5_000_000_000L); TimingClient.putNtp(reply, 24, 5_000_100_000L);
                            s.send(new DatagramPacket(reply, 32, p.getSocketAddress()));
                        } else if ((d[1] & 0x7f) == 0x55 && resendSource != null) {
                            int from = ((d[4] & 0xff) << 8) | (d[5] & 0xff), count = ((d[6] & 0xff) << 8) | (d[7] & 0xff);
                            resendRequests.add(new int[] {from, count});
                            for (int q = 0; q < count; q++) {
                                byte[] pkt = resendSource.get((from + q) & 0xffff);
                                if (pkt == null) continue;
                                byte[] wrapped = cat(new byte[] {(byte) 0x80, (byte) 0xd6, 0, 1}, pkt);
                                s.send(new DatagramPacket(wrapped, wrapped.length, InetAddress.getLoopbackAddress(), resendTo));
                            }
                        }
                    } catch (IOException ignored) { }
                }
            });
            t.setDaemon(true); t.start();
        }
        int port() { return s.getLocalPort(); }
        public void close() { s.close(); }
    }

    /* ---------- H.264 access units from the ffmpeg file ---------- */
    static List<byte[]> nals(byte[] annexB) {
        List<byte[]> out = new ArrayList<>(); int start = -1;
        for (int i = 0; i + 3 <= annexB.length; ) {
            boolean sc3 = annexB[i] == 0 && annexB[i + 1] == 0 && annexB[i + 2] == 1;
            if (sc3) { if (start >= 0) out.add(trimZeros(Arrays.copyOfRange(annexB, start, i))); start = i + 3; i += 3; } else i++;
        }
        if (start >= 0) out.add(Arrays.copyOfRange(annexB, start, annexB.length));
        return out;
    }
    static byte[] trimZeros(byte[] b) { int n = b.length; while (n > 0 && b[n - 1] == 0) n--; return Arrays.copyOf(b, n); }

    static byte[] header(int size, int type, int option) {
        byte[] h = new byte[128];
        h[0] = (byte) size; h[1] = (byte) (size >> 8); h[2] = (byte) (size >> 16); h[3] = (byte) (size >> 24);
        h[4] = (byte) type; h[6] = (byte) option; h[7] = (byte) (type == 1 ? 1 : 0);
        return h;
    }
    static void putFloat(byte[] h, int off, float f) { int v = Float.floatToIntBits(f); for (int i = 0; i < 4; i++) h[off + i] = (byte) (v >>> (8 * i)); }

    static void await(String what, Callable<Boolean> c) throws Exception {
        long until = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < until) { if (c.call()) return; Thread.sleep(20); }
        check(what + " (timed out)", false);
    }

    public static void main(String[] args) throws Exception {
        // args: playfair.bin (the app asset), the fixtures folder, a scratch folder
        Playfair playfair = Playfair.load(new FileInputStream(args[0]));
        fixtures = Paths.get(args[1]);
        work = Paths.get(args[2]);
        byte[] deviceId = {(byte) 0x2a, 0x11, 0x22, 0x33, 0x44, 0x55};
        byte[] seed = new byte[32]; for (int i = 0; i < 32; i++) seed[i] = (byte) (i * 7 + 1);
        AirPlayServer.Listener listener = new AirPlayServer.Listener() {
            public void code(String code) { shownCode = code; events.add("code:" + (code == null ? "hide" : "show")); }
            public void connected(String n, String m) { events.add("connected:" + n + "/" + m); }
            public void mirroring(boolean on) { events.add("mirroring:" + on); }
            public void disconnected() { events.add("disconnected"); }
            public void log(String m) { if (System.getenv("VERBOSE") != null) System.out.println("  [server] " + m); }
        };
        Sinks.Factory factory = new Sinks.Factory() {
            public Sinks.Video video() { VideoFile v = new VideoFile(); synchronized (videos) { videos.add(v); } return v; }
            public Sinks.Audio audio() { AudioList a = new AudioList(); synchronized (audios) { audios.add(a); } return a; }
        };
        AirPlayServer server = new AirPlayServer("Wohnzimmer", deviceId, seed, playfair, factory, listener);
        int port = server.start(0);
        byte[] serverPk = new Curve25519.Ed25519(seed).publicKey;

        /* discovery */
        check("RAOP instance name is HEX@name", server.raopName().equals("2A1122334455@Wohnzimmer"));
        Map<String, String> txt = server.airplayTxt();
        check("AirPlay TXT carries id, features and key", txt.get("deviceid").equals("2a:11:22:33:44:55") && txt.get("features").equals("0x5A7FFEE6,0x0")
              && txt.get("pk").equals(AirPlayServer.hex(serverPk, false)) && txt.get("pw").equals("false") && txt.get("model").equals("AppleTV3,2"));
        check("RAOP TXT lists AAC-ELD and FairPlay", server.raopTxt().get("cn").equals("0,1,2,3") && server.raopTxt().get("et").equals("0,3,5") && server.raopTxt().get("sf").equals("0x4"));

        Client c = new Client(port);
        Reply r = c.send("GET", "/info", null, null);
        Map<String, Object> info = r.dict();
        check("GET /info is a binary plist with CSeq and Server", r.status == 200 && "1".equals(r.headers.get("CSeq")) && "AirTunes/220.68".equals(r.headers.get("Server")));
        check("/info: name, deviceID, features, pk", "Wohnzimmer".equals(BPlist.string(info, "name")) && "2a:11:22:33:44:55".equals(BPlist.string(info, "deviceID"))
              && BPlist.number(info, "features", 0) == 0x5A7FFEE6L && Arrays.equals(BPlist.data(info, "pk"), serverPk));
        Map<String, Object> display = BPlist.dict(BPlist.list(info, "displays").get(0));
        check("/info: 1920x1080 display, 30 fps", BPlist.number(display, "width", 0) == 1920 && BPlist.number(display, "height", 0) == 1080 && BPlist.number(display, "maxFPS", 0) == 30);
        Map<String, Object> q = new LinkedHashMap<>(); q.put("qualifier", Arrays.asList((Object) "txtAirPlay"));
        r = c.plist("GET", "/info", q);
        check("/info with qualifier txtAirPlay returns the TXT record bytes", Arrays.equals(BPlist.data(r.dict(), "txtAirPlay"), AirPlayServer.txtBytes(txt)) && r.dict().size() == 1);

        /* pairing + FairPlay + SETUP */
        byte[] secret = pair(c, serverPk, false);
        byte[][] fp = fairPlayVector();
        fairPlay(c, fp[0]);
        byte[] expectedKey = Arrays.copyOf(sha512(fp[2], secret), 16);
        byte[] eiv = new byte[16]; new SecureRandom().nextBytes(eiv);
        Udp timing = new Udp(true);
        Map<String, Object> s1 = new LinkedHashMap<>();
        s1.put("deviceID", "AA:BB:CC:DD:EE:01"); s1.put("ekey", fp[1]); s1.put("eiv", eiv); s1.put("isScreenMirroringSession", true);
        s1.put("model", "iPhone15,2"); s1.put("name", "Jasons iPhone"); s1.put("timingPort", (long) timing.port()); s1.put("timingProtocol", "NTP");
        s1.put("sessionUUID", "4F0E..."); s1.put("macAddress", "AA:BB:CC:DD:EE:01"); s1.put("osName", "iPhone OS"); s1.put("osVersion", "27.0");
        r = c.plist("SETUP", "rtsp://127.0.0.1/1234567890", s1);
        check("SETUP 1 returns timing and event ports", r.status == 200 && BPlist.number(r.dict(), "timingPort", 0) > 0 && BPlist.number(r.dict(), "eventPort", -1) == 0);
        await("receiver sends NTP requests to the phone's timing port", () -> timing.got.size() >= 2);
        check("NTP request format 0x80 0xd2, 32 bytes", timing.got.get(0).length == 32 && (timing.got.get(0)[1] & 0xff) == 0xd2);
        check("listener: connected with the phone's name", events.contains("connected:Jasons iPhone/iPhone15,2"));

        byte[] setup110 = Files.readAllBytes(fixtures.resolve("setup110.bplist")); // from Python plistlib: a 16-byte integer ID
        r = c.send("SETUP", "rtsp://127.0.0.1/1234567890", "application/x-apple-binary-plist", setup110);
        Map<String, Object> st = BPlist.dict(BPlist.list(r.dict(), "streams").get(0));
        int mirrorPort = (int) BPlist.number(st, "dataPort", 0);
        check("SETUP type 110 returns a mirror data port", r.status == 200 && BPlist.number(st, "type", 0) == 110 && mirrorPort > 0);

        Udp control = new Udp(false);
        Map<String, Object> a = new LinkedHashMap<>(); a.put("audioFormat", 0x1000000L); a.put("audioMode", "default"); a.put("controlPort", (long) control.port());
        a.put("ct", 8L); a.put("isMedia", true); a.put("latencyMax", 88200L); a.put("latencyMin", 11025L); a.put("shk", new byte[32]); a.put("spf", 480L); a.put("sr", 44100L); a.put("type", 96L); a.put("usingScreen", true);
        Map<String, Object> s3 = new LinkedHashMap<>(); s3.put("streams", Arrays.asList((Object) a));
        r = c.plist("SETUP", "rtsp://127.0.0.1/1234567890", s3);
        Map<String, Object> as = BPlist.dict(BPlist.list(r.dict(), "streams").get(0));
        int audioData = (int) BPlist.number(as, "dataPort", 0), audioControl = (int) BPlist.number(as, "controlPort", 0);
        check("SETUP type 96 returns audio data and control ports", BPlist.number(as, "type", 0) == 96 && audioData > 0 && audioControl > 0);
        r = c.send("RECORD", "rtsp://127.0.0.1/1234567890", null, null);
        check("RECORD answers Audio-Latency", r.status == 200 && r.headers.containsKey("Audio-Latency"));
        r = c.send("SET_PARAMETER", "rtsp://127.0.0.1/1234567890", "text/parameters", "volume: -11.250000\r\n".getBytes());
        AudioList audio = audios.get(0);
        check("SET_PARAMETER volume reaches the audio sink", r.status == 200 && Math.abs(audio.volume + 11.25f) < 1e-4);
        r = c.send("GET_PARAMETER", "rtsp://127.0.0.1/1234567890", "text/parameters", "volume\r\n".getBytes());
        check("GET_PARAMETER volume", new String(r.body).trim().equals("volume: -11.250000"));
        check("audio sink started as AAC-ELD, 480 samples", audio.ct == 8 && audio.spf == 480);

        /* mirror stream */
        byte[] source = Files.readAllBytes(fixtures.resolve("clip.h264"));
        List<byte[]> units = nals(source);
        byte[] sps = null, pps = null;
        for (byte[] n : units) { if ((n[0] & 0x1f) == 7 && sps == null) sps = n; if ((n[0] & 0x1f) == 8 && pps == null) pps = n; }
        BigInteger connId = new BigInteger("F123456789ABCDEF", 16);
        Cipher ctr = Cipher.getInstance("AES/CTR/NoPadding");
        ctr.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Arrays.copyOf(sha512(("AirPlayStreamKey" + connId).getBytes(), expectedKey), 16), "AES"),
                 new IvParameterSpec(Arrays.copyOf(sha512(("AirPlayStreamIV" + connId).getBytes(), expectedKey), 16)));
        Socket m = new Socket("127.0.0.1", mirrorPort);
        OutputStream mo = new BufferedOutputStream(m.getOutputStream());
        ByteArrayOutputStream avcc = new ByteArrayOutputStream();
        avcc.write(new byte[] {1, sps[1], sps[2], sps[3], (byte) 0xff, (byte) 0xe1, (byte) (sps.length >> 8), (byte) sps.length});
        avcc.write(sps); avcc.write(1); avcc.write(new byte[] {(byte) (pps.length >> 8), (byte) pps.length}); avcc.write(pps);
        byte[] h = header(avcc.size(), 1, 0x16); putFloat(h, 16, 320); putFloat(h, 20, 180); putFloat(h, 40, 320); putFloat(h, 44, 180); putFloat(h, 56, 320); putFloat(h, 60, 180);
        mo.write(h); mo.write(avcc.toByteArray());
        int sentFrames = 0;
        ByteArrayOutputStream au = new ByteArrayOutputStream(), expected = new ByteArrayOutputStream();
        expected.write(new byte[] {0, 0, 0, 1}); expected.write(sps); expected.write(new byte[] {0, 0, 0, 1}); expected.write(pps);
        for (byte[] n : units) {
            int type = n[0] & 0x1f;
            if (type == 7 || type == 8 || type == 9) continue;
            expected.write(new byte[] {0, 0, 0, 1}); expected.write(n);
            au.write(new byte[] {(byte) (n.length >>> 24), (byte) (n.length >> 16), (byte) (n.length >> 8), (byte) n.length}); au.write(n);
            if (type == 1 || type == 5) {
                byte[] plain = au.toByteArray(); au.reset();
                byte[] enc = ctr.update(plain);
                mo.write(header(enc.length, 0, 0)); mo.write(enc); sentFrames++;
                if (sentFrames % 7 == 0) { byte[] stats = "report".getBytes(); mo.write(header(stats.length, 5, 0)); mo.write(stats); }
            }
        }
        mo.flush();
        final int total = sentFrames;
        await("all video frames reach the sink", () -> videos.get(0).frames == total);
        check("listener: mirroring started", events.contains("mirroring:true"));
        VideoFile video = videos.get(0);
        check("video configured once at 320x180", video.configures == 1 && video.width == 320 && video.height == 180);
        check("decrypted H.264 is byte-identical to the source (" + total + " pictures)", Arrays.equals(video.out.toByteArray(), expected.toByteArray()));
        Files.write(work.resolve("received.h264"), video.out.toByteArray());
        List<String> want = decode(fixtures.resolve("clip.h264"), work.resolve("source.md5")), got = decode(work.resolve("received.h264"), work.resolve("received.md5"));
        if (want == null) System.out.println("skip - decoding with FFmpeg (not installed)");
        else check("FFmpeg decodes the received stream to the same pictures (" + got.size() + " of " + want.size() + ")", !want.isEmpty() && want.equals(got));

        /* audio: each packet three times (ELD style), one packet lost and resent */
        byte[] key = expectedKey;
        Map<Integer, byte[]> packets = new HashMap<>();
        List<byte[]> plainFrames = new ArrayList<>();
        Random rnd = new Random(3);
        int firstSeq = 65530; // wraps around 0
        for (int i = 0; i < 60; i++) {
            int seq = (firstSeq + i) & 0xffff;
            byte[] frame = new byte[100 + rnd.nextInt(200)]; rnd.nextBytes(frame);
            plainFrames.add(frame);
            byte[] enc = frame.clone();
            int whole = enc.length / 16 * 16;
            Cipher cbc = Cipher.getInstance("AES/CBC/NoPadding"); cbc.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(eiv));
            byte[] encPart = cbc.doFinal(enc, 0, whole); System.arraycopy(encPart, 0, enc, 0, whole);
            long ts = 1000L + 480L * i;
            byte[] pkt = cat(new byte[] {(byte) 0x80, (byte) 0xe0, (byte) (seq >> 8), (byte) seq, (byte) (ts >> 24), (byte) (ts >> 16), (byte) (ts >> 8), (byte) ts, 0, 0, 0, 0}, enc);
            packets.put(seq, pkt);
        }
        control.resendSource = packets; control.resendTo = audioControl;
        DatagramSocket ad = new DatagramSocket();
        int lost = (firstSeq + 20) & 0xffff;
        // a no-data marker packet first, then frames
        byte[] noData = {(byte) 0x80, (byte) 0xe0, (byte) 0xff, (byte) 0xf9, 0, 0, 0, 0, 0, 0, 0, 0, 0x00, 0x68, 0x34, 0x00};
        ad.send(new DatagramPacket(noData, noData.length, InetAddress.getLoopbackAddress(), audioData));
        for (int i = 0; i < 60; i++) {
            for (int back = 2; back >= 0; back--) {
                int k = i - back; if (k < 0) continue;
                int seq = (firstSeq + k) & 0xffff;
                if (seq == lost) continue;
                byte[] pkt = packets.get(seq);
                ad.send(new DatagramPacket(pkt, pkt.length, InetAddress.getLoopbackAddress(), audioData));
            }
            Thread.sleep(2);
        }
        await("all 60 audio frames delivered once", () -> audio.frames.size() >= 60);
        Thread.sleep(100);
        boolean same = audio.frames.size() == 60;
        for (int i = 0; same && i < 60; i++) same = Arrays.equals(audio.frames.get(i), plainFrames.get(i)) && audio.times.get(i) == 1000L + 480L * i;
        check("audio frames decrypted, in order, no duplicates, across the 16-bit wrap", same);
        boolean asked = false; for (int[] q2 : control.resendRequests) asked |= AudioReceiver.diff(lost, q2[0]) >= 0 && AudioReceiver.diff(lost, q2[0]) < q2[1];
        check("the lost packet was requested through the control port (" + control.resendRequests.size() + " request)", asked && control.resendRequests.size() == 1);

        /* teardown of the mirror stream, then of everything */
        r = c.plist("TEARDOWN", "rtsp://127.0.0.1/1234567890", s("streams", Arrays.asList((Object) s("type", 110L))));
        await("TEARDOWN 110 stops mirroring", () -> events.contains("mirroring:false") && video.released);
        r = c.plist("TEARDOWN", "rtsp://127.0.0.1/1234567890", new LinkedHashMap<String, Object>());
        await("plain TEARDOWN ends the session", () -> events.contains("disconnected") && audio.released);
        check("server has no session after teardown", !server.sessionActive());
        c.close(); m.close(); timing.close(); control.close();

        /* takeover: a second phone wins */
        events.clear();
        Client one = session(server, port, playfair, serverPk, "Phone A", null);
        Client two = session(server, port, playfair, serverPk, "Phone B", null);
        await("the first phone is disconnected when the second starts", () -> { try { one.s.setSoTimeout(50); return one.in.read() < 0; } catch (SocketTimeoutException e) { return false; } });
        check("events: A connected, A disconnected, B connected", events.indexOf("connected:Phone A/iPhone15,2") >= 0 && events.indexOf("disconnected") > events.indexOf("connected:Phone A/iPhone15,2")
              && events.indexOf("connected:Phone B/iPhone15,2") > 0);
        server.endSession();
        await("endSession() disconnects phone B", () -> { try { two.s.setSoTimeout(50); return two.in.read() < 0; } catch (SocketTimeoutException e) { return false; } });

        /* code shown on the TV */
        events.clear();
        server.setRequireCode(true);
        check("TXT says a password is needed", server.airplayTxt().get("pw").equals("true") && server.raopTxt().get("sf").equals("0x84"));
        Client coded = new Client(port);
        pair(coded, serverPk, false);
        fairPlay(coded, fp[0]);
        Map<String, Object> s1b = new LinkedHashMap<>(s1); s1b.put("name", "Gast"); s1b.put("timingPort", 0L);
        r = coded.plist("SETUP", "rtsp://127.0.0.1/99", s1b);
        String www = r.headers.get("WWW-Authenticate");
        check("SETUP without code is refused with a digest challenge", r.status == 401 && www != null && www.startsWith("Digest realm=\"raop\", nonce=\""));
        check("the TV shows a 4-digit code", shownCode != null && shownCode.matches("\\d{4}"));
        String nonce = www.substring(www.indexOf("nonce=\"") + 7, www.length() - 1);
        r = coded.plist("SETUP", "rtsp://127.0.0.1/99", s1b, "Authorization", digest("SETUP", "rtsp://127.0.0.1/99", nonce, "0000".equals(shownCode) ? "1111" : "0000"));
        check("a wrong code is refused", r.status == 401);
        nonce = r.headers.get("WWW-Authenticate"); nonce = nonce.substring(nonce.indexOf("nonce=\"") + 7, nonce.length() - 1);
        r = coded.plist("SETUP", "rtsp://127.0.0.1/99", s1b, "Authorization", digest("SETUP", "rtsp://127.0.0.1/99", nonce, shownCode));
        check("the right code is accepted and the code disappears", r.status == 200 && shownCode == null && events.contains("connected:Gast/iPhone15,2"));
        coded.close();

        /* a wrong pair-verify signature */
        Client bad = new Client(port);
        pair(bad, serverPk, true);
        bad.close();

        /* Bluetooth-style /info without CSeq still answers */
        try (Socket raw = new Socket("127.0.0.1", port)) {
            raw.getOutputStream().write("GET /info?txtAirPlay&txtRAOP RTSP/1.0\r\n\r\n".getBytes()); raw.getOutputStream().flush();
            Reply br = Reply.read(new BufferedInputStream(raw.getInputStream()));
            check("/info?txtAirPlay&txtRAOP without CSeq returns both TXT records", br != null && br.status == 200 && BPlist.data(br.dict(), "txtRAOP") != null && BPlist.data(br.dict(), "txtAirPlay") != null);
        }

        server.stop();
        System.out.println(fails == 0 ? "ALL PASSED" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    static Map<String, Object> s(String k, Object v) { Map<String, Object> m = new LinkedHashMap<>(); m.put(k, v); return m; }

    static Client session(AirPlayServer server, int port, Playfair pf, byte[] serverPk, String name, String code) throws Exception {
        Client c = new Client(port);
        pair(c, serverPk, false);
        byte[][] fp = fairPlayVector();
        fairPlay(c, fp[0]);
        Map<String, Object> s1 = new LinkedHashMap<>();
        s1.put("deviceID", "AA:BB:CC:DD:EE:" + name.length()); s1.put("ekey", fp[1]); s1.put("eiv", new byte[16]); s1.put("model", "iPhone15,2"); s1.put("name", name); s1.put("timingPort", 0L);
        Reply r = c.plist("SETUP", "rtsp://127.0.0.1/1", s1);
        check("SETUP for " + name, r.status == 200);
        return c;
    }

    static String digest(String method, String uri, String nonce, String password) {
        String ha1 = AirPlayServer.Crypto.md5Hex("AirPlay:raop:" + password), ha2 = AirPlayServer.Crypto.md5Hex(method + ":" + uri);
        return "Digest username=\"AirPlay\", realm=\"raop\", nonce=\"" + nonce + "\", uri=\"" + uri + "\", response=\"" + AirPlayServer.Crypto.md5Hex(ha1 + ":" + nonce + ":" + ha2) + "\"";
    }

    /** Per-picture hashes from FFmpeg, or null without FFmpeg. */
    static List<String> decode(Path h264, Path md5) throws Exception {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", h264.toString(), "-f", "framemd5", md5.toString()).inheritIO().start();
            if (p.waitFor() != 0) return new ArrayList<>();
        } catch (IOException missing) {
            return null;
        }
        return frameHashes(md5);
    }

    static List<String> frameHashes(Path p) throws IOException {
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(p)) if (!line.startsWith("#")) { String[] f = line.split(","); out.add(f[f.length - 1].trim()); }
        return out;
    }
}
