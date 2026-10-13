package hr.endpointchecker;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Endpoint Checker - a small "Postman" that runs in the browser.
 *
 * Settings (environment variables):
 *   PORT          port (default 8421; can also be the first argument)
 *   BIND_ADDRESS  listen address (default 127.0.0.1; 0.0.0.0 in Docker)
 *   BLOCK_PRIVATE true = block localhost/private/internal addresses (default false;
 *                 MUST be true when the app is publicly reachable)
 *   DATA_DIR      folder for endpoint-checker-data.json (default: the folder of the jar)
 *   OPEN_BROWSER  true = open the browser on start (default true; false in Docker)
 *   ALLOW_SHUTDOWN true = show a Quit button that stops the program (default true; false in Docker)
 */
public class Main {

    private static final String BIND = env("BIND_ADDRESS", "127.0.0.1");
    private static final boolean BLOCK_PRIVATE = Boolean.parseBoolean(env("BLOCK_PRIVATE", "false"));
    private static final boolean OPEN_BROWSER = Boolean.parseBoolean(env("OPEN_BROWSER", "true"));
    private static final boolean ALLOW_SHUTDOWN = Boolean.parseBoolean(env("ALLOW_SHUTDOWN", "true"));
    private static final Object DATA_LOCK = new Object();
    private static final int MAX_DATA_BYTES = 5 * 1024 * 1024; // 5 MB
    private static final Path DATA_FILE = resolveDataFile();
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_BODY_BYTES = 10 * 1024 * 1024; // 10 MB

    // Redirects are followed manually so every hop can be checked (private address blocking)
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final Set<String> METHODS =
            Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

    // Headers that Java's HttpClient does not allow to be set manually
    private static final Set<String> RESTRICTED =
            Set.of("connection", "content-length", "expect", "host", "upgrade", "accept-encoding");

    // Not forwarded when a redirect goes to another host (so tokens don't leak)
    private static final Set<String> SENSITIVE =
            Set.of("authorization", "cookie", "proxy-authorization");

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : Integer.parseInt(env("PORT", "8421"));
        String url = "http://localhost:" + port + "/endpointChecker";

        HttpServer created;
        try {
            created = HttpServer.create(new InetSocketAddress(BIND, port), 0);
        } catch (BindException e) {
            // Most likely a second double-click: just open the already running instance
            System.out.println("Port " + port + " is already in use - Endpoint Checker is probably already running.");
            openBrowser(url);
            return;
        }
        final HttpServer server = created;
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.createContext("/api/proxy", Main::handleProxy);
        server.createContext("/api/data", Main::handleData);
        if (ALLOW_SHUTDOWN) server.createContext("/api/shutdown", ex -> handleShutdown(ex, server));
        server.createContext("/", Main::handlePage);
        server.start();

        System.out.println("Endpoint Checker listening on " + BIND + ":" + port
                + " (block private addresses: " + BLOCK_PRIVATE + ")");
        System.out.println("Data file: " + DATA_FILE);
        System.out.println("Open: " + url);
        openBrowser(url);
    }

    /** Data file location: DATA_DIR if set, otherwise the folder of the jar (or the working directory in an IDE). */
    private static Path resolveDataFile() {
        Path base;
        String dir = System.getenv("DATA_DIR");
        if (dir != null && !dir.isBlank()) {
            base = Path.of(dir);
        } else {
            base = Path.of(System.getProperty("user.dir"));
            try {
                Path loc = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                if (Files.isRegularFile(loc) && loc.getParent() != null) base = loc.getParent();
            } catch (Exception ignored) {
                // keep the working directory
            }
        }
        return base.toAbsolutePath().resolve("endpoint-checker-data.json");
    }

    /**
     * GET  /api/data -> contents of the data file (empty if it does not exist yet); the path is in X-Data-File
     * POST /api/data -> replaces the data file; needs the custom X-EC-Data header (other websites cannot send it)
     * The JSON is stored as-is; the browser owns its structure.
     */
    private static void handleData(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        if (method.equals("GET")) {
            byte[] body;
            synchronized (DATA_LOCK) {
                body = Files.exists(DATA_FILE) ? Files.readAllBytes(DATA_FILE) : new byte[0];
            }
            ex.getResponseHeaders().set("X-Data-File", URLEncoder.encode(DATA_FILE.toString(), StandardCharsets.UTF_8));
            send(ex, 200, "application/json; charset=utf-8", body);
        } else if (method.equals("POST")) {
            if (ex.getRequestHeaders().getFirst("X-EC-Data") == null) {
                send(ex, 403, "text/plain; charset=utf-8", "Forbidden".getBytes(StandardCharsets.UTF_8));
                return;
            }
            byte[] raw = ex.getRequestBody().readNBytes(MAX_DATA_BYTES + 1);
            if (raw.length > MAX_DATA_BYTES) {
                send(ex, 413, "text/plain; charset=utf-8", "Data is larger than 5 MB".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String text = new String(raw, StandardCharsets.UTF_8).trim();
            if (!text.startsWith("{") || !text.endsWith("}")) {
                send(ex, 400, "text/plain; charset=utf-8", "Expected a JSON object".getBytes(StandardCharsets.UTF_8));
                return;
            }
            try {
                synchronized (DATA_LOCK) {
                    writeDataFile(text);
                }
            } catch (IOException e) {
                send(ex, 500, "text/plain; charset=utf-8",
                        ("Cannot write " + DATA_FILE + ": " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
                return;
            }
            send(ex, 200, "text/plain; charset=utf-8", "saved".getBytes(StandardCharsets.UTF_8));
        } else {
            send(ex, 405, "text/plain; charset=utf-8", "Method not allowed".getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Writes to a temp file first, then moves it over the real one, so a crash cannot leave a half-written file. */
    private static void writeDataFile(String json) throws IOException {
        Path parent = DATA_FILE.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = DATA_FILE.resolveSibling(DATA_FILE.getFileName() + ".tmp");
        Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, DATA_FILE, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.move(tmp, DATA_FILE, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Opens the default browser (does nothing if OPEN_BROWSER=false or no desktop is available). */
    private static void openBrowser(String url) {
        if (!OPEN_BROWSER) return;
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("nux") || os.contains("nix")) {
            try {
                new ProcessBuilder("xdg-open", url).start();
                return;
            } catch (Exception ignored) {
                // fall through to java.awt.Desktop
            }
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception ignored) {
            // fall through to the message below
        }
        System.out.println("Could not open the browser automatically. Open: " + url);
    }

    /**
     * GET  /api/shutdown -> 200 if shutdown is enabled (the UI uses this to show the Quit button)
     * POST /api/shutdown -> stops the program; needs the custom X-EC-Shutdown header, which other
     *                       websites cannot send cross-origin, so they cannot stop it.
     */
    private static void handleShutdown(HttpExchange ex, HttpServer server) throws IOException {
        String method = ex.getRequestMethod();
        if (method.equals("GET")) {
            send(ex, 200, "text/plain; charset=utf-8", "enabled".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (!method.equals("POST") || ex.getRequestHeaders().getFirst("X-EC-Shutdown") == null) {
            send(ex, 403, "text/plain; charset=utf-8", "Forbidden".getBytes(StandardCharsets.UTF_8));
            return;
        }
        send(ex, 200, "text/plain; charset=utf-8", "Stopping".getBytes(StandardCharsets.UTF_8));
        new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                // stop anyway
            }
            server.stop(0);
            System.exit(0);
        }).start();
    }

    private static String env(String name, String def) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? def : v;
    }

    /** Serves index.html on / and /endpointChecker */
    private static void handlePage(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (!path.equals("/") && !path.equals("/endpointChecker")) {
            send(ex, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (InputStream in = Main.class.getResourceAsStream("/index.html")) {
            send(ex, 200, "text/html; charset=utf-8", in.readAllBytes());
        }
    }

    /**
     * Proxy: the browser calls /api/proxy?url=...&method=... and Java sends the real request.
     * The response is returned as-is; metadata goes into X-Upstream-* headers.
     */
    private static void handleProxy(HttpExchange ex) throws IOException {
        try {
            Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
            String url = q.get("url");
            String method = q.getOrDefault("method", "GET").toUpperCase();

            if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
                send(ex, 400, "text/plain; charset=utf-8",
                        "URL must start with http:// or https://".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (!METHODS.contains(method)) {
                send(ex, 400, "text/plain; charset=utf-8",
                        ("Unsupported method: " + method).getBytes(StandardCharsets.UTF_8));
                return;
            }

            byte[] reqBody = ex.getRequestBody().readAllBytes();
            String fwd = ex.getRequestHeaders().getFirst("X-EC-Headers");

            URI current = URI.create(url);
            String originalHost = current.getHost();
            String curMethod = method;
            byte[] curBody = reqBody;
            boolean crossHost = false;

            long start = System.nanoTime();
            HttpResponse<InputStream> resp;
            byte[] body;

            for (int hop = 0; ; hop++) {
                checkTarget(current);

                HttpRequest.Builder builder = HttpRequest.newBuilder(current)
                        .timeout(Duration.ofSeconds(30))
                        .header("User-Agent", "EndpointChecker/1.0")
                        .header("Accept", "application/json, */*")
                        .method(curMethod, curBody.length == 0
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(curBody));
                applyHeaders(builder, fwd, crossHost);

                resp = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());

                int sc = resp.statusCode();
                String loc = resp.headers().firstValue("Location").orElse(null);
                boolean redirect = loc != null && (sc == 301 || sc == 302 || sc == 303 || sc == 307 || sc == 308);

                if (!redirect) {
                    try (InputStream in = resp.body()) {
                        body = in.readNBytes(MAX_BODY_BYTES + 1);
                    }
                    if (body.length > MAX_BODY_BYTES) {
                        send(ex, 502, "text/plain; charset=utf-8",
                                "Response is larger than 10 MB".getBytes(StandardCharsets.UTF_8));
                        return;
                    }
                    break;
                }

                resp.body().close();
                if (hop >= MAX_REDIRECTS) throw new IOException("Too many redirects");
                URI next = current.resolve(loc);
                crossHost = crossHost || !originalHost.equalsIgnoreCase(String.valueOf(next.getHost()));
                if (sc == 303 || ((sc == 301 || sc == 302) && curMethod.equals("POST"))) {
                    if (!curMethod.equals("HEAD")) curMethod = "GET";
                    curBody = new byte[0];
                }
                current = next;
            }
            long ms = (System.nanoTime() - start) / 1_000_000;

            String headersText = resp.headers().map().entrySet().stream()
                    .map(e -> e.getKey() + ": " + String.join(", ", e.getValue()))
                    .sorted()
                    .collect(Collectors.joining("\n"));

            ex.getResponseHeaders().set("X-Upstream-Status", String.valueOf(resp.statusCode()));
            ex.getResponseHeaders().set("X-Upstream-Time", String.valueOf(ms));
            ex.getResponseHeaders().set("X-Upstream-Size", String.valueOf(body.length));
            ex.getResponseHeaders().set("X-Upstream-Content-Type", resp.headers().firstValue("content-type").orElse(""));
            ex.getResponseHeaders().set("X-Upstream-Url", current.toASCIIString());
            ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            ex.getResponseHeaders().set("X-Upstream-Headers",
                    Base64.getEncoder().encodeToString(headersText.getBytes(StandardCharsets.UTF_8)));
            send(ex, 200, "application/octet-stream", body);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            send(ex, 502, "text/plain; charset=utf-8", "Interrupted".getBytes(StandardCharsets.UTF_8));
        } catch (SecurityException e) {
            send(ex, 403, "text/plain; charset=utf-8", e.getMessage().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
            send(ex, 502, "text/plain; charset=utf-8", msg.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Headers from the UI arrive in X-EC-Headers (Base64 of "Name: value" lines). */
    private static void applyHeaders(HttpRequest.Builder builder, String fwd, boolean crossHost) {
        if (fwd == null || fwd.isEmpty()) return;
        String text = new String(Base64.getDecoder().decode(fwd), StandardCharsets.UTF_8);
        for (String line : text.split("\n")) {
            int i = line.indexOf(':');
            if (i <= 0) continue;
            String name = line.substring(0, i).trim();
            String value = line.substring(i + 1).trim();
            String lower = name.toLowerCase();
            if (RESTRICTED.contains(lower)) continue;
            if (crossHost && SENSITIVE.contains(lower)) continue;
            try {
                builder.setHeader(name, value);
            } catch (IllegalArgumentException ignored) {
                // invalid header name/value - skip
            }
        }
    }

    /** Allows only http/https; if BLOCK_PRIVATE is set, blocks private and internal addresses. */
    private static void checkTarget(URI uri) throws IOException {
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IOException("Only http and https are allowed");
        }
        String host = uri.getHost();
        if (host == null) throw new IOException("Invalid URL");
        if (!BLOCK_PRIVATE) return;

        for (InetAddress a : InetAddress.getAllByName(host)) {
            if (isInternal(a)) {
                throw new SecurityException("Blocked: " + host + " resolves to an internal address (" + a.getHostAddress() + ")");
            }
        }
    }

    static boolean isInternal(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) return true;
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int b0 = b[0] & 255, b1 = b[1] & 255;
            return b0 == 0                                  // 0.0.0.0/8
                    || (b0 == 100 && b1 >= 64 && b1 <= 127) // 100.64.0.0/10 (CGNAT, Tailscale)
                    || b0 >= 240;                           // reserved
        }
        return (b[0] & 0xfe) == 0xfc;                       // IPv6 fc00::/7 (unique local)
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw == null) return map;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i < 0) continue;
            map.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
        }
        return map;
    }

    private static void send(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }
}
