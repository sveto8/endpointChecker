# Endpoint Checker

A small, private, Postman-like API tester that runs on your own computer and is used through the browser.
You start a single Java program, it opens a page on `http://localhost:8421/endpointChecker`, and from there you
can send HTTP requests to any API and inspect the response.

- Java 11+, **no external libraries** (built-in `HttpServer` and `java.net.http.HttpClient`)
- Single runnable `.jar`, works on Windows, Linux and macOS
- Everything you save stays on your machine, in one JSON file next to the jar

---

## What it does

### Sending requests

The top of the left panel is a single request bar: **method**, **URL**, **Send** and **★ Save**.
Enter in the URL field also sends.

- **Methods:** GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS
- **Params tab:** query parameters as key/value rows with an on/off checkbox. The table and the URL stay in sync
  both ways: add a row and `?key=value` appears in the URL, type a query string in the URL and the table fills in.
- **Path tab:** for endpoints with variables inside the path. Write `:name` in the URL, for example
  `/api/http/:code` or `/api/:num.jpg`; a field per variable appears and the values are substituted when you press
  Send. The URL itself keeps the `:name` placeholders, so it can be saved as a reusable template. Sending with an empty
  variable shows which one is missing.
- **Headers tab:** key/value rows with on/off checkboxes, plus quick-add buttons for `Authorization: Bearer`,
  `X-API-Key` and `Accept: application/json`.
- **Body tab:** JSON, plain text or form (`a=1&b=2`), with a **Format JSON** button. `Content-Type` is set
  automatically from the chosen type unless you add your own in Headers. The body is not sent for GET and HEAD.

Requests are not sent from the browser directly. The page calls the local Java program, which sends the real request
and returns the result, so **CORS restrictions do not apply**.

### Reading responses

The right panel shows the result.

- **Status badge** with the code and name (for example `404 Not Found`) and a description next to it
  (for example `4xx Client error – The requested resource does not exist at this URL.`).
  All standard codes from 100 to 511 are covered; unknown codes fall back to their class (`2xx Success`, ...).
- **Time** in milliseconds and **size** of the response.
- **Redirects** are followed (up to 5). When the final URL differs from the one you sent, it is shown as `→ final URL`.
- **Body / Headers** tabs for the response body and the response headers.
- **Pretty / Raw** toggle. JSON is indented and colour-highlighted; if the body is not valid JSON it is shown raw
  with a note. A **Copy** button copies the displayed text (or the URL for images, video and audio).
- **Clickable links in JSON:** any `http(s)://` string value in a JSON response can be clicked to request that URL
  with GET.
- **Media preview:** images, video and audio are displayed in the page (with dimensions or duration). Other binary
  content (PDF, zip, ...) is shown as a short description with type and size.
- Responses larger than **10 MB** are rejected.

### Saved endpoints and history

- **★ Save** stores the current method and URL. Endpoints are grouped **automatically by host**
  (`random-d.uk`, `placebear.com`, ...), with the full path under each host.
- Click a saved endpoint to load it into the request bar; GET and HEAD are sent immediately, unless the URL has
  path variables, in which case the Path tab opens so you can fill them in.
- **History** keeps the last 30 requests including headers, body, body type and path variable values.
  Click an entry to replay it. **Clear** empties it.
- Both lists can be resized by dragging the bar under them (double-click resets). Heights are remembered in the browser.

### Where your data lives

Saved endpoints and history are stored in **`endpoint-checker-data.json` next to the jar** (or in `DATA_DIR`),
and the path is shown under the History list.

- Data does not depend on the port or the browser; copy the file to move your data to another computer.
- Files are written to a temporary file first and then moved into place, so a crash cannot corrupt them.
- If the file exists but is not valid JSON, it is **not overwritten**; a warning is shown instead.
- If the file cannot be written (for example a read-only folder), data is kept in the browser as a fallback
  and a warning is shown.
- On first start with no data file, anything saved in the browser by earlier versions is migrated into it automatically.
- **The history stores request headers, so tokens such as `Authorization: Bearer ...` end up in this file in plain
  text.** Do not share the file or sync it to a cloud folder without checking it first.

### Running as a standalone app

- The browser opens automatically on start.
- The **⏻ Quit** button in the header stops the program.
- Starting it a second time does not fail: it just opens the browser on the instance that is already running.

---

## Running it

You need Java 11 or newer (a JRE is enough).

| System | How |
|---|---|
| Windows | Double-click `APP\Endpoint Checker.bat` (no console window). `Endpoint Checker (console).bat` shows the log. |
| Linux | `./endpoint-checker.sh` in `APP/` (first time: `chmod +x endpoint-checker.sh`). `sh install-linux-menu.sh` adds it to the applications menu. |
| Any | `java -jar endpoint-checker.jar [port]` |

Then open `http://localhost:8421/endpointChecker` (it opens by itself).

### Configuration

Set as environment variables (the port can also be the first command-line argument).

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `8421` | Port to listen on |
| `BIND_ADDRESS` | `127.0.0.1` | Listen address. The default makes it reachable only from this computer |
| `BLOCK_PRIVATE` | `false` | `true` blocks requests to localhost, private, link-local and internal addresses |
| `DATA_DIR` | folder of the jar | Folder for `endpoint-checker-data.json` |
| `OPEN_BROWSER` | `true` | Open the browser on start |
| `ALLOW_SHUTDOWN` | `true` | Show the Quit button and allow stopping the program from the page |

---

## Project layout

```
APP/            What you run: jar, launchers and your data file. This is the only folder you need to copy.
JAVA/           Source code (Maven project): pom.xml, src/, Dockerfile
dockerFiles/    Optional: docker-compose.yml, Caddyfile and .env.example for running on a NAS/server
```

### Building

Open `JAVA/pom.xml` in IntelliJ and run **Maven → Lifecycle → package**, or `mvn package` in `JAVA/`.
This builds `target/endpoint-checker.jar` and copies it into `../APP/`. On Windows, quit the running app first,
otherwise the jar is locked and the copy fails. When updating, replace only the jar and keep
`endpoint-checker-data.json`.

### Docker (optional)

`dockerFiles/` contains a setup with the app behind Caddy (HTTPS and basic auth) for running on a NAS or server.
Copy `.env.example` to `.env`, set your domain, put a password hash in `Caddyfile`
(`docker run --rm -it caddy caddy hash-password`) and run `docker compose up -d --build`.
In that setup `BLOCK_PRIVATE` is on, and the browser opening and Quit button are off.

---

## Security notes

- By default the server listens on `127.0.0.1` only. **Do not expose it to the internet without protection:**
  it is effectively an open proxy, and anyone who can open it can make your machine send requests to any address.
- `BLOCK_PRIVATE=true` resolves the host name and rejects internal addresses (loopback, `10.x`, `172.16–31.x`,
  `192.168.x`, `169.254.x`, `100.64.x`, IPv6 unique-local) at every redirect step. It is a safeguard, not a guarantee:
  DNS rebinding can still bypass it, so for a public setup also restrict the container's network access.
- On a redirect to a different host, `Authorization` and `Cookie` headers are not forwarded.
- Saving data and quitting need a custom request header, which other websites cannot send cross-origin,
  so a random web page cannot overwrite your data or stop the program.
- Headers Java does not allow to be set manually (`Host`, `Content-Length`, `Connection`, `Expect`, `Upgrade`,
  `Accept-Encoding`) are ignored.

## Limitations

- No built-in login; use a reverse proxy (like the Caddy setup) if it is reachable by others.
- No file upload (multipart), cookie jar, WebSocket or OpenAPI/Swagger import.
- Request timeout is 30 seconds (10 seconds to connect); responses are limited to 10 MB.
- A GET or HEAD request never sends a body.
