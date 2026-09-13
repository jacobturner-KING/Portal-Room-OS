package com.portalroomos;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Puts "Room OS" in the YouTube app's cast picker, next to the TVs on the same
 * Wi-Fi.
 *
 * Phones find cast targets two ways. Google Cast needs device certificates
 * signed by Google, so a sideloaded app cannot be one. DIAL is the other, the
 * protocol Rokus and most smart TVs answer: an SSDP responder on the multicast
 * group, a device description at the URL it points to, and one HTTP resource per
 * app that a phone can GET for state and POST to launch. This is that, for one
 * app, and the launch hands the payload to {@link YouTubeActivity}, which opens
 * YouTube's own TV site with it. The site does the pairing and the playback;
 * this class only makes the Portal discoverable and starts the screen.
 *
 * Spotify's zeroconf is broken on this device because mDNS advertises the
 * hostname and Android's is "localhost". DIAL carries the IP address itself in
 * every LOCATION header, so that problem does not reach here.
 *
 * Runs for the life of the process (started from {@link RoomOsApp}). Wi-Fi may
 * not be up yet at boot, so the SSDP side is a loop that reopens its socket on
 * any failure and whenever the address changes, rather than a one-shot bind.
 * Every request is logged under the "Dial" tag, which is the way to watch a
 * phone probe the device: {@code adb logcat -s Dial}.
 */
final class DialServer {

    private static final String TAG = "Dial";

    /** The HTTP side. Any free port works; SSDP tells phones which one. */
    static final int PORT = 8091;
    static final String FRIENDLY_NAME = "Room OS";
    private static final String APP = "YouTube";
    private static final String DIAL_ST = "urn:dial-multiscreen-org:service:dial:1";
    private static final String SSDP_GROUP = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final int MAX_AGE_S = 1800;
    /** Unsolicited alive announcements, well inside max-age. */
    private static final int ALIVE_EVERY_S = 600;
    private static final int RETRY_MS = 5000;

    private final Context context;
    private final String uuid;
    private volatile boolean running;
    private volatile MulticastSocket ssdp;
    private volatile ServerSocket http;
    /** Address the SSDP socket was joined on; a change makes the loop rejoin. */
    private volatile String joinedIp;
    private ExecutorService handlers;
    private ScheduledExecutorService notifier;
    private WifiManager.MulticastLock multicastLock;
    /**
     * What the YouTube site posted back to dial_data after launch (its screen id
     * and friends). Echoed inside additionalData on every app-info GET, which is
     * how a phone that prefers that route finds the screen it just launched.
     */
    private final Map<String, String> additionalData = new LinkedHashMap<>();

    DialServer(Context context) {
        this.context = context.getApplicationContext();
        SharedPreferences prefs = this.context.getSharedPreferences("dial", Context.MODE_PRIVATE);
        String id = prefs.getString("uuid", null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString("uuid", id).apply();
        }
        uuid = id;
    }

    synchronized void start() {
        if (running) return;
        running = true;
        WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("dial-ssdp");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        }
        handlers = Executors.newCachedThreadPool();
        notifier = Executors.newSingleThreadScheduledExecutor();
        Thread s = new Thread(this::ssdpLoop, "dial-ssdp");
        s.setDaemon(true);
        s.start();
        Thread h = new Thread(this::httpLoop, "dial-http");
        h.setDaemon(true);
        h.start();
        notifier.scheduleWithFixedDelay(this::heartbeat, ALIVE_EVERY_S, ALIVE_EVERY_S, TimeUnit.SECONDS);
    }

    synchronized void stop() {
        if (!running) return;
        running = false;
        try { announce("ssdp:byebye"); } catch (IOException ignored) {}
        closeQuietly(ssdp);
        ServerSocket srv = http;
        if (srv != null) try { srv.close(); } catch (IOException ignored) {}
        if (notifier != null) notifier.shutdownNow();
        if (handlers != null) handlers.shutdownNow();
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
    }

    // ---- SSDP: answer searches, announce ourselves ----

    private void ssdpLoop() {
        while (running) {
            MulticastSocket sock = null;
            try {
                NetworkInterface nic = lanInterface();
                String ip = nic == null ? null : ipOf(nic);
                if (ip == null) {
                    sleep(RETRY_MS);
                    continue;
                }
                sock = new MulticastSocket(null);
                sock.setReuseAddress(true);
                sock.bind(new InetSocketAddress(SSDP_PORT));
                sock.setNetworkInterface(nic);
                sock.setTimeToLive(4);
                sock.joinGroup(new InetSocketAddress(InetAddress.getByName(SSDP_GROUP), SSDP_PORT), nic);
                ssdp = sock;
                joinedIp = ip;
                Log.i(TAG, "SSDP listening on " + ip + " (" + nic.getName() + "), http on :" + PORT);
                announce("ssdp:alive");
                byte[] buf = new byte[2048];
                while (running) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    sock.receive(p);
                    answerSearch(sock, p);
                }
            } catch (IOException e) {
                if (running) Log.w(TAG, "SSDP socket: " + e.getMessage() + "; reopening in " + RETRY_MS / 1000 + " s");
            } finally {
                closeQuietly(sock);
                if (ssdp == sock) ssdp = null;
            }
            if (running) sleep(RETRY_MS);
        }
    }

    private void answerSearch(MulticastSocket sock, DatagramPacket p) throws IOException {
        String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.ISO_8859_1);
        if (!msg.startsWith("M-SEARCH")) return;
        Map<String, String> h = headers(msg);
        String st = h.get("st");
        if (st == null || !"\"ssdp:discover\"".equals(h.get("man"))) return;
        if (!DIAL_ST.equals(st) && !"ssdp:all".equals(st)) return;
        String ip = joinedIp;
        if (ip == null) return;
        Log.i(TAG, "M-SEARCH from " + p.getAddress().getHostAddress() + " for " + st);
        String res = "HTTP/1.1 200 OK\r\n"
            + "CACHE-CONTROL: max-age=" + MAX_AGE_S + "\r\n"
            + "EXT:\r\n"
            + "LOCATION: " + location(ip) + "\r\n"
            + "SERVER: Android/9 UPnP/1.1 RoomOS/1.0\r\n"
            + "ST: " + DIAL_ST + "\r\n"
            + "USN: uuid:" + uuid + "::" + DIAL_ST + "\r\n"
            + "BOOTID.UPNP.ORG: 1\r\n"
            + "CONFIGID.UPNP.ORG: 1\r\n"
            + "\r\n";
        byte[] bytes = res.getBytes(StandardCharsets.ISO_8859_1);
        sock.send(new DatagramPacket(bytes, bytes.length, p.getAddress(), p.getPort()));
    }

    /** NOTIFY to the group, so phones that are not searching right now learn of us too. */
    private void announce(String nts) throws IOException {
        MulticastSocket sock = ssdp;
        String ip = joinedIp;
        if (sock == null || ip == null) return;
        String msg = "NOTIFY * HTTP/1.1\r\n"
            + "HOST: " + SSDP_GROUP + ":" + SSDP_PORT + "\r\n"
            + "CACHE-CONTROL: max-age=" + MAX_AGE_S + "\r\n"
            + "LOCATION: " + location(ip) + "\r\n"
            + "NT: " + DIAL_ST + "\r\n"
            + "NTS: " + nts + "\r\n"
            + "SERVER: Android/9 UPnP/1.1 RoomOS/1.0\r\n"
            + "USN: uuid:" + uuid + "::" + DIAL_ST + "\r\n"
            + "BOOTID.UPNP.ORG: 1\r\n"
            + "CONFIGID.UPNP.ORG: 1\r\n"
            + "\r\n";
        byte[] bytes = msg.getBytes(StandardCharsets.ISO_8859_1);
        InetAddress group = InetAddress.getByName(SSDP_GROUP);
        // Twice, as UPnP recommends: multicast is best-effort and one can drop.
        for (int i = 0; i < 2; i++) sock.send(new DatagramPacket(bytes, bytes.length, group, SSDP_PORT));
    }

    /** Periodic alive, and a rejoin when the address has moved under us. */
    private void heartbeat() {
        if (!running) return;
        try {
            NetworkInterface nic = lanInterface();
            String now = nic == null ? null : ipOf(nic);
            String joined = joinedIp;
            if (joined != null && !joined.equals(now)) {
                Log.i(TAG, "address changed " + joined + " -> " + now + "; rejoining");
                closeQuietly(ssdp);   // the receive loop reopens on a fresh interface
                return;
            }
            announce("ssdp:alive");
        } catch (IOException e) {
            Log.w(TAG, "alive announce failed: " + e.getMessage());
        }
    }

    // ---- HTTP: device description and the one app ----

    private void httpLoop() {
        while (running) {
            try (ServerSocket srv = new ServerSocket()) {
                srv.setReuseAddress(true);
                srv.bind(new InetSocketAddress(PORT));
                http = srv;
                while (running) {
                    Socket s = srv.accept();
                    handlers.execute(() -> serve(s));
                }
            } catch (IOException e) {
                if (running) Log.w(TAG, "HTTP socket: " + e.getMessage() + "; reopening in " + RETRY_MS / 1000 + " s");
            } finally {
                http = null;
            }
            if (running) sleep(RETRY_MS);
        }
    }

    private void serve(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(5000);
            Request req = Request.read(new BufferedInputStream(sock.getInputStream()));
            if (req == null) return;
            // Build URLs from the address the phone actually reached us on.
            String base = "http://" + sock.getLocalAddress().getHostAddress() + ":" + PORT;
            Response res = route(req, base);
            Log.i(TAG, req.method + " " + req.path + (req.query.isEmpty() ? "" : "?" + req.query)
                + " from " + sock.getInetAddress().getHostAddress() + " -> " + res.status);
            OutputStream out = sock.getOutputStream();
            out.write(res.bytes());
            out.flush();
        } catch (IOException e) {
            Log.d(TAG, "http connection: " + e.getMessage());
        }
    }

    private Response route(Request r, String base) {
        String appPath = "/apps/" + APP;
        if ("OPTIONS".equals(r.method)) return Response.empty(200);
        if ("GET".equals(r.method) && "/dd.xml".equals(r.path)) {
            Response res = Response.xml(200, deviceDescription());
            res.headers.put("Application-URL", base + "/apps/");
            return res;
        }
        if (appPath.equals(r.path)) {
            if ("GET".equals(r.method)) return Response.xml(200, appInfo());
            if ("POST".equals(r.method)) {
                String payload = new String(r.body, StandardCharsets.UTF_8).trim();
                synchronized (additionalData) { additionalData.clear(); }
                Log.i(TAG, "launch: " + payload);
                YouTubeActivity.open(context, launchUrl(payload));
                Response res = Response.empty(201);
                res.headers.put("Location", base + appPath + "/run");
                return res;
            }
        }
        if ((appPath + "/run").equals(r.path) && "DELETE".equals(r.method)) {
            Log.i(TAG, "stop requested");
            synchronized (additionalData) { additionalData.clear(); }
            YouTubeActivity.stop();
            return Response.empty(200);
        }
        if ((appPath + "/dial_data").equals(r.path) && ("POST".equals(r.method) || "GET".equals(r.method))) {
            // The site sends the same fields both ways, in the body and in the query.
            Map<String, String> form = form("POST".equals(r.method) ? new String(r.body, StandardCharsets.UTF_8) : r.query);
            synchronized (additionalData) {
                additionalData.clear();
                additionalData.putAll(form);
            }
            Log.i(TAG, "dial_data: " + form.keySet());
            return Response.empty(200);
        }
        return Response.empty(404);
    }

    /**
     * The TV site's URL for a launch. The payload is YouTube's own query string
     * from the phone (pairing code, video id, theme) and goes on as is. The
     * additionalDataUrl is what tells the site it was launched by DIAL: with it
     * the site registers as a screen, posts its screen id back and holds a
     * lounge connection for phones; a bare /tv load does none of that. The page
     * and this server share a device, and the site refuses to post anywhere
     * but localhost, so that is the host.
     */
    static String launchUrl(String payload) {
        StringBuilder url = new StringBuilder(YouTubeActivity.TV_URL).append('?');
        if (!payload.isEmpty()) url.append(payload).append('&');
        try {
            url.append("additionalDataUrl=")
               .append(URLEncoder.encode("http://localhost:" + PORT + "/apps/" + APP + "/dial_data", "UTF-8"));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return url.toString();
    }

    private String deviceDescription() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<root xmlns=\"urn:schemas-upnp-org:device-1-0\">\n"
            + "  <specVersion><major>1</major><minor>0</minor></specVersion>\n"
            + "  <device>\n"
            + "    <deviceType>urn:dial-multiscreen-org:device:dial:1</deviceType>\n"
            + "    <friendlyName>" + xml(FRIENDLY_NAME) + "</friendlyName>\n"
            + "    <manufacturer>Portal Room OS</manufacturer>\n"
            + "    <modelName>Meta Portal</modelName>\n"
            + "    <UDN>uuid:" + uuid + "</UDN>\n"
            + "    <serviceList>\n"
            + "      <service>\n"
            + "        <serviceType>" + DIAL_ST + "</serviceType>\n"
            + "        <serviceId>urn:dial-multiscreen-org:serviceId:dial</serviceId>\n"
            + "        <controlURL>/ssdp/notfound</controlURL>\n"
            + "        <eventSubURL>/ssdp/notfound</eventSubURL>\n"
            + "        <SCPDURL>/ssdp/notfound</SCPDURL>\n"
            + "      </service>\n"
            + "    </serviceList>\n"
            + "  </device>\n"
            + "</root>\n";
    }

    private String appInfo() {
        boolean on = YouTubeActivity.running();
        StringBuilder b = new StringBuilder()
            .append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            .append("<service xmlns=\"urn:dial-multiscreen-org:schemas:dial\" dialVer=\"2.1\">\n")
            .append("  <name>").append(APP).append("</name>\n")
            .append("  <options allowStop=\"true\"/>\n")
            .append("  <state>").append(on ? "running" : "stopped").append("</state>\n");
        if (on) b.append("  <link rel=\"run\" href=\"run\"/>\n");
        b.append("  <additionalData>\n");
        synchronized (additionalData) {
            for (Map.Entry<String, String> e : additionalData.entrySet()) {
                String key = e.getKey().replaceAll("[^A-Za-z0-9_]", "");
                if (key.isEmpty()) continue;
                b.append("    <").append(key).append('>').append(xml(e.getValue()))
                    .append("</").append(key).append(">\n");
            }
        }
        return b.append("  </additionalData>\n</service>\n").toString();
    }

    private String location(String ip) {
        return "http://" + ip + ":" + PORT + "/dd.xml";
    }

    // ---- plumbing ----

    /** The Wi-Fi interface, or failing that any LAN interface with an IPv4 address. */
    private static NetworkInterface lanInterface() {
        try {
            NetworkInterface fallback = null;
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            if (all == null) return null;
            for (NetworkInterface nic : Collections.list(all)) {
                if (nic.isLoopback() || !nic.isUp() || ipOf(nic) == null) continue;
                if (nic.getName().startsWith("wlan")) return nic;
                if (fallback == null) fallback = nic;
            }
            return fallback;
        } catch (SocketException e) {
            return null;
        }
    }

    private static String ipOf(NetworkInterface nic) {
        for (InetAddress a : Collections.list(nic.getInetAddresses())) {
            if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
        }
        return null;
    }

    private static Map<String, String> headers(String msg) {
        Map<String, String> h = new HashMap<>();
        for (String line : msg.split("\r?\n")) {
            int i = line.indexOf(':');
            if (i > 0) h.put(line.substring(0, i).trim().toLowerCase(Locale.ROOT), line.substring(i + 1).trim());
        }
        return h;
    }

    private static Map<String, String> form(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) continue;
            int i = pair.indexOf('=');
            try {
                String k = URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), "UTF-8");
                String v = i < 0 ? "" : URLDecoder.decode(pair.substring(i + 1), "UTF-8");
                out.put(k, v);
            } catch (Exception ignored) {}
        }
        return out;
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static void closeQuietly(MulticastSocket s) {
        if (s != null) s.close();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    /** Just enough HTTP for a phone: request line, headers, Content-Length body. */
    private static final class Request {
        String method, path, query = "";
        final Map<String, String> headers = new HashMap<>();
        byte[] body = new byte[0];

        static Request read(InputStream in) throws IOException {
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int b, seen = 0;
            while ((b = in.read()) != -1) {
                head.write(b);
                if (b == '\r' && (seen == 0 || seen == 2)) seen++;
                else if (b == '\n' && (seen == 1 || seen == 3)) seen++;
                else seen = 0;
                if (seen == 4) break;
                if (head.size() > 16384) return null;
            }
            if (seen != 4) return null;
            String[] lines = head.toString("ISO-8859-1").split("\r\n");
            String[] first = lines[0].split(" ");
            if (first.length < 2) return null;
            Request r = new Request();
            r.method = first[0].toUpperCase(Locale.ROOT);
            String target = first[1];
            int q = target.indexOf('?');
            r.path = q < 0 ? target : target.substring(0, q);
            r.query = q < 0 ? "" : target.substring(q + 1);
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c > 0) r.headers.put(lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT), lines[i].substring(c + 1).trim());
            }
            int len = 0;
            try { len = Integer.parseInt(r.headers.getOrDefault("content-length", "0")); } catch (NumberFormatException ignored) {}
            if (len < 0 || len > 65536) return null;
            r.body = new byte[len];
            int got = 0;
            while (got < len) {
                int n = in.read(r.body, got, len - got);
                if (n < 0) break;
                got += n;
            }
            return r;
        }
    }

    private static final class Response {
        final int status;
        final byte[] body;
        final Map<String, String> headers = new LinkedHashMap<>();

        private Response(int status, byte[] body) {
            this.status = status;
            this.body = body;
        }

        static Response empty(int status) {
            return new Response(status, new byte[0]);
        }

        static Response xml(int status, String body) {
            Response r = new Response(status, body.getBytes(StandardCharsets.UTF_8));
            r.headers.put("Content-Type", "application/xml; charset=utf-8");
            return r;
        }

        byte[] bytes() {
            String reason = status == 200 ? "OK" : status == 201 ? "Created" : status == 404 ? "Not Found" : "Error";
            StringBuilder b = new StringBuilder("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
            for (Map.Entry<String, String> e : headers.entrySet()) b.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            b.append("Access-Control-Allow-Origin: *\r\n")
             .append("Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS\r\n")
             .append("Access-Control-Allow-Headers: Content-Type\r\n")
             .append("Content-Length: ").append(body.length).append("\r\n")
             .append("Connection: close\r\n\r\n");
            byte[] head = b.toString().getBytes(StandardCharsets.ISO_8859_1);
            byte[] out = new byte[head.length + body.length];
            System.arraycopy(head, 0, out, 0, head.length);
            System.arraycopy(body, 0, out, head.length, body.length);
            return out;
        }
    }
}
