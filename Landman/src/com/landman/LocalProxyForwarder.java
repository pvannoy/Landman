package com.landman;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A minimal local HTTP/CONNECT proxy that forwards all traffic to an upstream
 * proxy with Basic auth credentials. Chrome's --proxy-server flag cannot handle
 * authenticated proxies natively, so we run this on localhost and point Chrome at it.
 *
 * Supports both HTTP (GET/POST) and HTTPS (CONNECT tunnel) requests.
 */
public class LocalProxyForwarder {

    private final String upstreamHost;
    private final int upstreamPort;
    private final String authHeader; // "Basic base64(user:pass)"
    private final int localPort;

    private ServerSocket serverSocket;
    private ExecutorService pool;
    private volatile boolean running = false;

    /**
     * @param localPort     port to listen on (e.g. 18080)
     * @param upstreamHost  upstream proxy hostname
     * @param upstreamPort  upstream proxy port
     * @param username      upstream proxy username
     * @param password      upstream proxy password
     */
    public LocalProxyForwarder(int localPort, String upstreamHost, int upstreamPort,
                               String username, String password) {
        this.localPort = localPort;
        this.upstreamHost = upstreamHost;
        this.upstreamPort = upstreamPort;
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Start the proxy in background threads.
     */
    public void start() throws IOException {
        serverSocket = new ServerSocket(localPort, 50, InetAddress.getLoopbackAddress());
        pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "proxy-forwarder");
            t.setDaemon(true);
            return t;
        });
        running = true;

        Thread acceptThread = new Thread(() -> {
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    pool.submit(() -> handleClient(client));
                } catch (IOException e) {
                    if (running) {
                        System.err.println("  [Proxy] Accept error: " + e.getMessage());
                    }
                }
            }
        }, "proxy-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();

        System.out.println("  [Proxy] Local forwarder listening on localhost:" + localPort);
    }

    /**
     * Stop the proxy.
     */
    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        if (pool != null) pool.shutdownNow();
    }

    public int getLocalPort() {
        return localPort;
    }

    // ─────────────────────────────────────────────────────────────────────

    private void handleClient(Socket client) {
        try {
            client.setSoTimeout(60000);
            InputStream clientIn = client.getInputStream();
            OutputStream clientOut = client.getOutputStream();

            // Read the first line to determine if it's CONNECT or a regular request
            String firstLine = readLine(clientIn);
            if (firstLine == null || firstLine.isEmpty()) {
                client.close();
                return;
            }

            if (firstLine.toUpperCase().startsWith("CONNECT ")) {
                handleConnect(firstLine, clientIn, clientOut, client);
            } else {
                handleHttp(firstLine, clientIn, clientOut, client);
            }
        } catch (Exception e) {
            // Connection closed or timeout — normal
        } finally {
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Handle CONNECT tunnel (used for HTTPS).
     * We forward the CONNECT request to the upstream proxy with auth.
     */
    private void handleConnect(String connectLine, InputStream clientIn, OutputStream clientOut,
                               Socket client) throws IOException {
        // Read remaining headers from client (and discard them)
        StringBuilder clientHeaders = new StringBuilder();
        String line;
        while ((line = readLine(clientIn)) != null && !line.isEmpty()) {
            clientHeaders.append(line).append("\r\n");
        }

        // Open connection to upstream proxy
        Socket upstream = new Socket();
        upstream.connect(new InetSocketAddress(upstreamHost, upstreamPort), 30000);
        upstream.setSoTimeout(60000);
        OutputStream upOut = upstream.getOutputStream();
        InputStream upIn = upstream.getInputStream();

        // Send CONNECT with auth to upstream
        upOut.write((connectLine + "\r\n").getBytes(StandardCharsets.UTF_8));
        upOut.write(("Proxy-Authorization: " + authHeader + "\r\n").getBytes(StandardCharsets.UTF_8));
        upOut.write("\r\n".getBytes(StandardCharsets.UTF_8));
        upOut.flush();

        // Read upstream's response
        String responseLine = readLine(upIn);
        if (responseLine == null) {
            upstream.close();
            return;
        }

        // Read remaining response headers
        StringBuilder responseHeaders = new StringBuilder();
        responseHeaders.append(responseLine).append("\r\n");
        while ((line = readLine(upIn)) != null && !line.isEmpty()) {
            responseHeaders.append(line).append("\r\n");
        }
        responseHeaders.append("\r\n");

        // Forward response to client
        clientOut.write(responseHeaders.toString().getBytes(StandardCharsets.UTF_8));
        clientOut.flush();

        // If upstream said 200, start bidirectional tunnel
        if (responseLine.contains("200")) {
            tunnel(client, upstream);
        } else {
            upstream.close();
        }
    }

    /**
     * Handle plain HTTP request (GET/POST etc.) — forward with auth header added.
     */
    private void handleHttp(String requestLine, InputStream clientIn, OutputStream clientOut,
                            Socket client) throws IOException {
        // Read remaining headers
        StringBuilder headers = new StringBuilder();
        String line;
        while ((line = readLine(clientIn)) != null && !line.isEmpty()) {
            // Skip any existing Proxy-Authorization
            if (!line.toLowerCase().startsWith("proxy-authorization:")) {
                headers.append(line).append("\r\n");
            }
        }

        // Connect to upstream proxy
        Socket upstream = new Socket();
        upstream.connect(new InetSocketAddress(upstreamHost, upstreamPort), 30000);
        upstream.setSoTimeout(60000);
        OutputStream upOut = upstream.getOutputStream();

        // Forward request with our auth
        upOut.write((requestLine + "\r\n").getBytes(StandardCharsets.UTF_8));
        upOut.write(("Proxy-Authorization: " + authHeader + "\r\n").getBytes(StandardCharsets.UTF_8));
        upOut.write(headers.toString().getBytes(StandardCharsets.UTF_8));
        upOut.write("\r\n".getBytes(StandardCharsets.UTF_8));
        upOut.flush();

        // Bidirectional pipe
        tunnel(client, upstream);
    }

    /**
     * Bidirectional data pipe between two sockets.
     */
    private void tunnel(Socket a, Socket b) {
        Thread t1 = new Thread(() -> pipe(a, b), "tunnel-a2b");
        Thread t2 = new Thread(() -> pipe(b, a), "tunnel-b2a");
        t1.setDaemon(true);
        t2.setDaemon(true);
        t1.start();
        t2.start();
        try { t1.join(); } catch (InterruptedException ignored) {}
        try { t2.join(); } catch (InterruptedException ignored) {}
        try { a.close(); } catch (IOException ignored) {}
        try { b.close(); } catch (IOException ignored) {}
    }

    private void pipe(Socket from, Socket to) {
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
            // connection closed — normal
        }
    }

    /**
     * Read a single line (terminated by \n or \r\n) from the input stream.
     */
    private String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                sb.append((char) c);
            }
        }
        if (c == -1 && sb.length() == 0) return null;
        return sb.toString();
    }
}

