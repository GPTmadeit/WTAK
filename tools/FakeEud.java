import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

/**
 * FakeEud — a stand-in for a phone running ATAK, used to prove interop with the
 * watch app. Zero dependencies; run directly with JDK 11+:
 *
 *   java FakeEud.java inject   — sends TAK Protocol v1 (protobuf) mesh PLIs over
 *                                UDP to 127.0.0.1:6969, i.e. exactly the bytes a
 *                                modern ATAK EUD multicasts on 239.2.3.1:6969.
 *                                (Route into an emulator with:
 *                                 adb emu redir add udp:6969:6969)
 *
 *   java FakeEud.java server   — acts as a minimal TAK server STCP input on TCP
 *                                8087: accepts the watch's connection, prints the
 *                                CoT XML PLIs the watch streams up, and pushes a
 *                                "TOC" contact down the stream.
 *
 * The protobuf layout follows the official schemas in
 * AndroidTacticalAssaultKit-CIV/commoncommo/core/impl/protobuf/.
 */
public class FakeEud {

    // ------------------------------------------------------------ protobuf

    static void varint(ByteArrayOutputStream o, long v) {
        while (true) {
            if ((v & ~0x7FL) == 0) { o.write((int) v); return; }
            o.write((int) ((v & 0x7F) | 0x80)); v >>>= 7;
        }
    }
    static void str(ByteArrayOutputStream o, int field, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        varint(o, (field << 3) | 2); varint(o, b.length); o.writeBytes(b);
    }
    static void dbl(ByteArrayOutputStream o, int field, double d) {
        varint(o, (field << 3) | 1);
        long bits = Double.doubleToLongBits(d);
        for (int i = 0; i < 8; i++) { o.write((int) (bits & 0xFF)); bits >>>= 8; }
    }
    static void u64(ByteArrayOutputStream o, int field, long v) {
        varint(o, (field << 3)); varint(o, v);
    }
    static void msg(ByteArrayOutputStream o, int field, byte[] m) {
        varint(o, (field << 3) | 2); varint(o, m.length); o.writeBytes(m);
    }

    /** Build a TAK Protocol v1 mesh frame for a PLI, as ATAK would. */
    static byte[] meshPli(String uid, String callsign, String team, String role,
                          double lat, double lon, int battery) {
        long now = System.currentTimeMillis();

        ByteArrayOutputStream contact = new ByteArrayOutputStream();
        str(contact, 1, "*:-1:stcp"); str(contact, 2, callsign);

        ByteArrayOutputStream group = new ByteArrayOutputStream();
        str(group, 1, team); str(group, 2, role);

        ByteArrayOutputStream status = new ByteArrayOutputStream();
        u64(status, 1, battery);

        ByteArrayOutputStream takv = new ByteArrayOutputStream();
        str(takv, 1, "FAKE EUD"); str(takv, 2, "ATAK-CIV"); str(takv, 3, "34"); str(takv, 4, "5.2.0");

        ByteArrayOutputStream detail = new ByteArrayOutputStream();
        msg(detail, 2, contact.toByteArray());
        msg(detail, 3, group.toByteArray());
        msg(detail, 5, status.toByteArray());
        msg(detail, 6, takv.toByteArray());

        ByteArrayOutputStream cot = new ByteArrayOutputStream();
        str(cot, 1, "a-f-G-U-C");
        str(cot, 5, uid);
        u64(cot, 6, now); u64(cot, 7, now); u64(cot, 8, now + 75_000);
        str(cot, 9, "m-g");
        dbl(cot, 10, lat); dbl(cot, 11, lon); dbl(cot, 12, 12.0);
        dbl(cot, 13, 8.0); dbl(cot, 14, 10.0);
        msg(cot, 15, detail.toByteArray());

        ByteArrayOutputStream tak = new ByteArrayOutputStream();
        msg(tak, 2, cot.toByteArray());

        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0xBF); frame.write(0x01); frame.write(0xBF);
        frame.writeBytes(tak.toByteArray());
        return frame.toByteArray();
    }

    // ----------------------------------------------------------------- xml

    static String iso(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(millis));
    }

    static String xmlPli(String uid, String callsign, String team, String role,
                         double lat, double lon) {
        long now = System.currentTimeMillis();
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<event version=\"2.0\" uid=\"" + uid + "\" type=\"a-f-G-U-C\" how=\"m-g\""
            + " time=\"" + iso(now) + "\" start=\"" + iso(now) + "\" stale=\"" + iso(now + 75_000) + "\">"
            + "<point lat=\"" + lat + "\" lon=\"" + lon + "\" hae=\"12.0\" ce=\"8.0\" le=\"10.0\"/>"
            + "<detail>"
            + "<takv device=\"FAKE TOC\" platform=\"ATAK-CIV\" os=\"34\" version=\"5.2.0\"/>"
            + "<contact callsign=\"" + callsign + "\" endpoint=\"*:-1:stcp\"/>"
            + "<__group name=\"" + team + "\" role=\"" + role + "\"/>"
            + "<status battery=\"71\"/>"
            + "</detail></event>";
    }

    // --------------------------------------------------------------- modes

    static void inject(String host, int count) throws Exception {
        try (DatagramSocket sock = new DatagramSocket()) {
            InetAddress addr = InetAddress.getByName(host);
            for (int i = 0; i < count; i++) {
                // A fake teammate walking ~2 m east per beacon, NE of Times Square.
                byte[] frame = meshPli("ANDROID-fakeEud1", "EUD-ALPHA", "Red", "Team Lead",
                        40.75950, -73.98310 + i * 0.00002, 87);
                sock.send(new DatagramPacket(frame, frame.length, addr, 6969));
                System.out.println("[inject] sent TAK proto mesh PLI #" + (i + 1)
                        + " (" + frame.length + " B) uid=ANDROID-fakeEud1 callsign=EUD-ALPHA team=Red");
                Thread.sleep(2000);
            }
        }
    }

    static void server(int port, int lifetimeSec) throws Exception {
        try (ServerSocket ss = new ServerSocket(port)) {
            System.out.println("[server] TAK-server STCP input listening on :" + port);
            try (Socket s = ss.accept()) {
                System.out.println("[server] watch connected from " + s.getRemoteSocketAddress());
                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream();

                Thread reader = new Thread(() -> {
                    byte[] buf = new byte[16384];
                    StringBuilder acc = new StringBuilder();
                    try {
                        int n;
                        while ((n = in.read(buf)) >= 0) {
                            acc.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                            int end;
                            while ((end = acc.indexOf("</event>")) >= 0) {
                                int start = acc.indexOf("<event");
                                String xml = acc.substring(Math.max(start, 0), end + 8);
                                acc.delete(0, end + 8);
                                System.out.println("[server] RECEIVED from watch:\n  " + xml + "\n");
                            }
                        }
                    } catch (Exception ignored) { }
                });
                reader.setDaemon(true);
                reader.start();

                long until = System.currentTimeMillis() + lifetimeSec * 1000L;
                while (System.currentTimeMillis() < until) {
                    String toc = xmlPli("ANDROID-fakeEud2", "TOC", "Dark Green", "HQ",
                            40.75620, -73.99010);
                    out.write(toc.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    System.out.println("[server] sent TOC contact down the stream");
                    Thread.sleep(5000);
                }
            }
        }
    }

    // ------------------------------------------------- TLS enrollment server

    static final String STOREPASS = "changeme1";
    static final String ENROLL_USER = "watchuser";
    static final String ENROLL_PASS = "watchpass";
    static File tlsDir;

    static void keytool(String... args) throws Exception {
        String kt = new File(new File(System.getProperty("java.home"), "bin"), "keytool").getAbsolutePath();
        String[] cmd = new String[args.length + 1];
        cmd[0] = kt;
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).directory(tlsDir).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new RuntimeException("keytool failed: " + String.join(" ", args) + "\n" + out);
    }

    /** One-time CA + TLS server certificate setup under tools/tls/. */
    static void ensurePki() throws Exception {
        tlsDir = new File("tls");
        tlsDir.mkdirs();
        if (new File(tlsDir, "ca.p12").exists()) { System.out.println("[pki] reusing existing CA in tools/tls/"); return; }
        System.out.println("[pki] generating CA + server certificate...");
        keytool("-genkeypair", "-alias", "ca", "-dname", "CN=FAKE-TAK-CA,O=FakeTAK",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "365", "-ext", "bc:c",
                "-keystore", "ca.p12", "-storetype", "PKCS12", "-storepass", STOREPASS);
        keytool("-exportcert", "-alias", "ca", "-keystore", "ca.p12", "-storepass", STOREPASS,
                "-rfc", "-file", "ca.pem");
        keytool("-genkeypair", "-alias", "server", "-dname", "CN=10.0.2.2,O=FakeTAK",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "365",
                "-keystore", "server.p12", "-storetype", "PKCS12", "-storepass", STOREPASS);
        keytool("-certreq", "-alias", "server", "-keystore", "server.p12", "-storepass", STOREPASS,
                "-file", "server.csr");
        keytool("-gencert", "-alias", "ca", "-keystore", "ca.p12", "-storepass", STOREPASS,
                "-infile", "server.csr", "-outfile", "server.crt", "-rfc", "-validity", "365",
                "-ext", "san=ip:10.0.2.2,ip:127.0.0.1", "-ext", "ku:c=digitalSignature,keyEncipherment",
                "-ext", "eku=serverAuth");
        keytool("-importcert", "-alias", "ca", "-keystore", "server.p12", "-storepass", STOREPASS,
                "-file", "ca.pem", "-noprompt");
        keytool("-importcert", "-alias", "server", "-keystore", "server.p12", "-storepass", STOREPASS,
                "-file", "server.crt");
        System.out.println("[pki] done");
    }

    static SSLContext serverSslContext(boolean trustClientCa) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(new File(tlsDir, "server.p12"))) {
            ks.load(in, STOREPASS.toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, STOREPASS.toCharArray());

        TrustManagerFactory tmf = null;
        if (trustClientCa) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate ca;
            try (FileInputStream in = new FileInputStream(new File(tlsDir, "ca.pem"))) {
                ca = (X509Certificate) cf.generateCertificate(in);
            }
            KeyStore trust = KeyStore.getInstance("PKCS12");
            trust.load(null, null);
            trust.setCertificateEntry("ca", ca);
            tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trust);
        }
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf != null ? tmf.getTrustManagers() : null, null);
        return ctx;
    }

    static String pemBody(File pem) throws Exception {
        String s = Files.readString(pem.toPath());
        return s.replaceAll("-----BEGIN [^-]+-----", "").replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", "");
    }

    static boolean authorized(HttpExchange ex) {
        String expect = "Basic " + Base64.getEncoder()
                .encodeToString((ENROLL_USER + ":" + ENROLL_PASS).getBytes(StandardCharsets.UTF_8));
        String got = ex.getRequestHeaders().getFirst("Authorization");
        return expect.equals(got);
    }

    static void respond(HttpExchange ex, int code, String body, String type) throws Exception {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    /** Marti-style certificate enrollment API + mutual-TLS CoT input. */
    static void tlsServer(int enrollPort, int cotPort, int lifetimeSec) throws Exception {
        ensurePki();

        HttpsServer https = HttpsServer.create(new InetSocketAddress(enrollPort), 0);
        https.setHttpsConfigurator(new HttpsConfigurator(serverSslContext(false)));

        https.createContext("/Marti/api/tls/config", ex -> {
            try {
                if (!authorized(ex)) { respond(ex, 401, "unauthorized", "text/plain"); return; }
                System.out.println("[enroll] GET tls/config (auth OK)");
                respond(ex, 200,
                        "<ns2:certificateConfig xmlns:ns2=\"com.bbn.marti.config\"><nameEntries>"
                        + "<nameEntry name=\"O\" value=\"FakeTAK\"/>"
                        + "<nameEntry name=\"OU\" value=\"Watch\"/>"
                        + "</nameEntries></ns2:certificateConfig>", "application/xml");
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        https.createContext("/Marti/api/tls/signClient/v2", ex -> {
            try {
                if (!authorized(ex)) { respond(ex, 401, "unauthorized", "text/plain"); return; }
                String query = ex.getRequestURI().getQuery();
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
                System.out.println("[enroll] POST signClient/v2 ?" + query + " — CSR " + body.length() + " chars");
                String csrPem = body.contains("BEGIN CERTIFICATE REQUEST") ? body
                        : "-----BEGIN CERTIFICATE REQUEST-----\n" + body + "\n-----END CERTIFICATE REQUEST-----\n";
                Files.writeString(new File(tlsDir, "client.csr").toPath(), csrPem);
                new File(tlsDir, "client.crt").delete();
                keytool("-gencert", "-alias", "ca", "-keystore", "ca.p12", "-storepass", STOREPASS,
                        "-infile", "client.csr", "-outfile", "client.crt", "-rfc", "-validity", "30",
                        "-ext", "ku:c=digitalSignature", "-ext", "eku=clientAuth");
                String signed = pemBody(new File(tlsDir, "client.crt"));
                String ca = pemBody(new File(tlsDir, "ca.pem"));
                System.out.println("[enroll] signed client cert, returning JSON (signedCert + ca0)");
                respond(ex, 200, "{\"signedCert\":\"" + signed + "\",\"ca0\":\"" + ca + "\"}", "application/json");
            } catch (Exception e) {
                e.printStackTrace();
                try { respond(ex, 500, "sign error", "text/plain"); } catch (Exception ignored) { }
            }
        });

        https.start();
        System.out.println("[enroll] Marti cert API (HTTPS) on :" + enrollPort
                + "  (user=" + ENROLL_USER + " pass=" + ENROLL_PASS + ")");

        // Mutual-TLS streaming CoT input.
        SSLServerSocket ss = (SSLServerSocket) serverSslContext(true)
                .getServerSocketFactory().createServerSocket(cotPort);
        ss.setNeedClientAuth(true);
        System.out.println("[tls-cot] mutual-TLS CoT input on :" + cotPort + " (client cert REQUIRED)");

        long until = System.currentTimeMillis() + lifetimeSec * 1000L;
        while (System.currentTimeMillis() < until) {
            ss.setSoTimeout((int) Math.max(1000, until - System.currentTimeMillis()));
            try (SSLSocket s = (SSLSocket) ss.accept()) {
                s.startHandshake();
                X509Certificate peer = (X509Certificate) s.getSession().getPeerCertificates()[0];
                System.out.println("[tls-cot] client connected, VERIFIED cert subject: "
                        + peer.getSubjectX500Principal().getName());

                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream();
                Thread reader = new Thread(() -> {
                    byte[] buf = new byte[16384];
                    StringBuilder acc = new StringBuilder();
                    try {
                        int n;
                        while ((n = in.read(buf)) >= 0) {
                            acc.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                            int end;
                            while ((end = acc.indexOf("</event>")) >= 0) {
                                int start = acc.indexOf("<event");
                                String xml = acc.substring(Math.max(start, 0), end + 8);
                                acc.delete(0, end + 8);
                                System.out.println("[tls-cot] RECEIVED over mTLS:\n  " + xml + "\n");
                            }
                        }
                    } catch (Exception ignored) { }
                });
                reader.setDaemon(true);
                reader.start();

                while (System.currentTimeMillis() < until && !s.isClosed()) {
                    String toc = xmlPli("ANDROID-fakeEud2", "TOC", "Dark Green", "HQ",
                            40.75620, -73.99010);
                    out.write(toc.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    System.out.println("[tls-cot] sent TOC contact over mTLS");
                    Thread.sleep(5000);
                }
            } catch (java.net.SocketTimeoutException t) {
                // lifetime elapsed with no client — fall through
            } catch (Exception e) {
                System.out.println("[tls-cot] connection ended: " + e.getMessage());
            }
        }
        https.stop(0);
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "inject";
        switch (mode) {
            case "inject" -> inject(args.length > 1 ? args[1] : "127.0.0.1",
                    args.length > 2 ? Integer.parseInt(args[2]) : 5);
            case "server" -> server(args.length > 1 ? Integer.parseInt(args[1]) : 8087,
                    args.length > 2 ? Integer.parseInt(args[2]) : 90);
            case "tlsserver" -> tlsServer(
                    args.length > 1 ? Integer.parseInt(args[1]) : 8446,
                    args.length > 2 ? Integer.parseInt(args[2]) : 8089,
                    args.length > 3 ? Integer.parseInt(args[3]) : 300);
            // Write one mesh PLI frame to a file — for injecting via
            // `adb push` + guest-side `nc -u` when emulator NAT drops UDP redirects.
            case "dump" -> {
                // dump <file> [lat] [lon] [callsign] [team] [role]
                double dLat = args.length > 2 ? Double.parseDouble(args[2]) : 40.75950;
                double dLon = args.length > 3 ? Double.parseDouble(args[3]) : -73.98310;
                String cs = args.length > 4 ? args[4] : "EUD-ALPHA";
                String team = args.length > 5 ? args[5] : "Red";
                String role = args.length > 6 ? args[6] : "Team Lead";
                byte[] frame = meshPli("ANDROID-" + cs, cs, team, role, dLat, dLon, 87);
                Files.write(Path.of(args.length > 1 ? args[1] : "pli.bin"), frame);
                System.out.println("[dump] wrote " + frame.length + " B frame for " + cs
                        + " @ " + dLat + "," + dLon);
            }
            default -> System.err.println(
                    "usage: java FakeEud.java [inject [host] [count] | server [port] [seconds] | "
                    + "tlsserver [enrollPort] [cotPort] [seconds] | dump [file]]");
        }
    }
}
