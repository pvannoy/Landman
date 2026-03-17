package com.landman;

import com.google.gson.*;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal Chrome DevTools Protocol client over WebSocket.
 *
 * This replaces Playwright entirely.  Chrome sees ZERO automation libraries —
 * just a plain WebSocket connection to its own DevTools, which is identical to
 * what happens when a user opens DevTools (F12).
 *
 * We only enable the bare minimum CDP domains needed (Page, Runtime) and we
 * do NOT enable Network, Fetch, DOM or any domain that DataDome checks for.
 */
public class RawCdpClient implements AutoCloseable {

    private final String wsUrl;
    private CdpWebSocket ws;
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final BlockingQueue<JsonObject> events = new LinkedBlockingQueue<>();
    private volatile boolean connected = false;

    /**
     * Connect to Chrome's first page target.
     * @param debugPort Chrome's --remote-debugging-port
     */
    public RawCdpClient(int debugPort) throws IOException {
        // Get the first page's WebSocket URL
        String jsonUrl = "http://localhost:" + debugPort + "/json";
        HttpURLConnection conn = (HttpURLConnection) new URL(jsonUrl).openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        String body;
        try (var is = conn.getInputStream()) {
            body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        conn.disconnect();

        JsonArray targets = JsonParser.parseString(body).getAsJsonArray();
        String pageWsUrl = null;
        for (JsonElement el : targets) {
            JsonObject t = el.getAsJsonObject();
            if ("page".equals(t.get("type").getAsString())) {
                pageWsUrl = t.get("webSocketDebuggerUrl").getAsString();
                break;
            }
        }
        if (pageWsUrl == null) {
            throw new IOException("No page target found on debug port " + debugPort);
        }
        this.wsUrl = pageWsUrl;
    }

    /** Open the WebSocket connection. */
    public void connect() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        ws = new CdpWebSocket(URI.create(wsUrl), latch);
        ws.connect();
        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw new IOException("WebSocket connection timeout");
        }
        connected = true;

        // Enable only Page domain (for navigation events)
        send("Page.enable", new JsonObject());
    }

    /**
     * Send a CDP command and wait for its response.
     */
    public JsonObject send(String method, JsonObject params) {
        return send(method, params, 30000);
    }

    public JsonObject send(String method, JsonObject params, int timeoutMs) {
        int id = nextId.getAndIncrement();
        JsonObject msg = new JsonObject();
        msg.addProperty("id", id);
        msg.addProperty("method", method);
        msg.add("params", params != null ? params : new JsonObject());

        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pending.put(id, future);

        ws.send(msg.toString());

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            throw new RuntimeException("CDP command timed out: " + method, e);
        } catch (Exception e) {
            pending.remove(id);
            throw new RuntimeException("CDP command failed: " + method, e);
        }
    }

    /** Navigate to a URL and wait for Page.loadEventFired. */
    public void navigate(String url, int timeoutMs) {
        // Clear any stale events
        events.clear();

        JsonObject params = new JsonObject();
        params.addProperty("url", url);
        send("Page.navigate", params);

        // Wait for load
        waitForEvent("Page.loadEventFired", timeoutMs);
    }

    /** Wait for a specific CDP event. */
    public JsonObject waitForEvent(String eventName, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                JsonObject evt = events.poll(500, TimeUnit.MILLISECONDS);
                if (evt != null && eventName.equals(evt.get("method").getAsString())) {
                    return evt;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null; // timeout
    }

    /** Execute JavaScript in the page and return the result as a string. */
    public String evaluate(String expression) {
        return evaluate(expression, 30000);
    }

    public String evaluate(String expression, int timeoutMs) {
        JsonObject params = new JsonObject();
        params.addProperty("expression", expression);
        params.addProperty("returnByValue", true);
        params.addProperty("awaitPromise", false);

        JsonObject result = send("Runtime.evaluate", params, timeoutMs);
        if (result.has("result")) {
            JsonObject r = result.getAsJsonObject("result");
            if (r.has("result")) {
                JsonObject val = r.getAsJsonObject("result");
                if (val.has("value")) {
                    JsonElement v = val.get("value");
                    if (v.isJsonNull()) return "";
                    return v.getAsString();
                }
            }
        }
        return "";
    }

    /** Get the full HTML of the page via JS. */
    public String getPageHtml() {
        return evaluate("document.documentElement.outerHTML", 15000);
    }

    /** Get document.title */
    public String getTitle() {
        return evaluate("document.title");
    }

    /** Get body innerText */
    public String getBodyText() {
        return evaluate("document.body ? document.body.innerText : ''");
    }

    /** Get current URL */
    public String getCurrentUrl() {
        return evaluate("window.location.href");
    }

    /** Type text into a focused element, char by char with random delays. */
    public void typeText(String text, int minDelayMs, int maxDelayMs) {
        java.util.Random rng = new java.util.Random();
        for (char c : text.toCharArray()) {
            dispatchKeyEvent("keyDown", c);
            dispatchKeyEvent("keyUp", c);
            try {
                Thread.sleep(minDelayMs + rng.nextInt(Math.max(1, maxDelayMs - minDelayMs)));
            } catch (InterruptedException ignored) {}
        }
    }

    private void dispatchKeyEvent(String type, char c) {
        JsonObject params = new JsonObject();
        params.addProperty("type", type);
        params.addProperty("text", String.valueOf(c));
        params.addProperty("key", String.valueOf(c));
        params.addProperty("unmodifiedText", String.valueOf(c));
        send("Input.dispatchKeyEvent", params, 5000);
    }

    /** Click at specific coordinates. */
    public void click(double x, double y) {
        JsonObject down = new JsonObject();
        down.addProperty("type", "mousePressed");
        down.addProperty("x", x);
        down.addProperty("y", y);
        down.addProperty("button", "left");
        down.addProperty("clickCount", 1);
        send("Input.dispatchMouseEvent", down, 5000);

        try { Thread.sleep(50 + new java.util.Random().nextInt(100)); } catch (InterruptedException ignored) {}

        JsonObject up = new JsonObject();
        up.addProperty("type", "mouseReleased");
        up.addProperty("x", x);
        up.addProperty("y", y);
        up.addProperty("button", "left");
        up.addProperty("clickCount", 1);
        send("Input.dispatchMouseEvent", up, 5000);
    }

    /** Move mouse to coordinates. */
    public void mouseMove(double x, double y) {
        JsonObject params = new JsonObject();
        params.addProperty("type", "mouseMoved");
        params.addProperty("x", x);
        params.addProperty("y", y);
        send("Input.dispatchMouseEvent", params, 5000);
    }

    /** Scroll by delta. */
    public void scroll(double deltaX, double deltaY, double x, double y) {
        JsonObject params = new JsonObject();
        params.addProperty("type", "mouseWheel");
        params.addProperty("x", x);
        params.addProperty("y", y);
        params.addProperty("deltaX", deltaX);
        params.addProperty("deltaY", deltaY);
        send("Input.dispatchMouseEvent", params, 5000);
    }

    public boolean isConnected() {
        return connected && ws != null && ws.isOpen();
    }

    @Override
    public void close() {
        connected = false;
        if (ws != null) {
            try { ws.closeBlocking(); } catch (Exception ignored) {}
        }
    }

    // ── WebSocket inner class ──────────────────────────────────────────

    private class CdpWebSocket extends WebSocketClient {
        private final CountDownLatch openLatch;

        CdpWebSocket(URI uri, CountDownLatch openLatch) {
            super(uri);
            this.openLatch = openLatch;
            this.setConnectionLostTimeout(0); // disable ping
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            openLatch.countDown();
        }

        @Override
        public void onMessage(String message) {
            try {
                JsonObject json = JsonParser.parseString(message).getAsJsonObject();
                if (json.has("id")) {
                    // Response to a command
                    int id = json.get("id").getAsInt();
                    CompletableFuture<JsonObject> future = pending.remove(id);
                    if (future != null) {
                        future.complete(json);
                    }
                } else if (json.has("method")) {
                    // Event
                    events.offer(json);
                }
            } catch (Exception e) {
                System.err.println("  [CDP] Parse error: " + e.getMessage());
            }
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            connected = false;
            // Complete all pending futures with an error
            for (var entry : pending.entrySet()) {
                entry.getValue().completeExceptionally(
                        new IOException("WebSocket closed: " + reason));
            }
            pending.clear();
        }

        @Override
        public void onError(Exception ex) {
            System.err.println("  [CDP] WebSocket error: " + ex.getMessage());
        }
    }
}

