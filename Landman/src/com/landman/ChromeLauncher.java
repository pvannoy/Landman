package com.landman;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Launches a real Chrome process via ProcessBuilder — completely independent
 * of Playwright.  This avoids all automation fingerprints that Playwright's
 * launchPersistentContext adds (navigator.webdriver, __playwright markers, CDP
 * pipe artifacts, etc.).
 *
 * Playwright later connects to the running Chrome via connectOverCDP().
 */
public class ChromeLauncher {

    private Process process;
    private int debugPort;

    /** Auto-detect Chrome install path on Windows. */
    public static String findChromePath() {
        String[] candidates = {
            System.getenv("PROGRAMFILES") + "\\Google\\Chrome\\Application\\chrome.exe",
            System.getenv("PROGRAMFILES(X86)") + "\\Google\\Chrome\\Application\\chrome.exe",
            System.getProperty("user.home") + "\\AppData\\Local\\Google\\Chrome\\Application\\chrome.exe",
        };
        for (String path : candidates) {
            if (path != null && Files.exists(Path.of(path))) {
                return path;
            }
        }
        // Fallback: just use "chrome" and hope it's on PATH
        return "chrome";
    }

    /** Find a free port starting from the given port number. */
    private static int findFreePort(int startPort) {
        for (int port = startPort; port < startPort + 100; port++) {
            try (ServerSocket ss = new ServerSocket(port)) {
                ss.setReuseAddress(true);
                return port;
            } catch (IOException ignored) {
                // port in use, try next
            }
        }
        throw new RuntimeException("Could not find a free debug port in range " + startPort + "-" + (startPort + 99));
    }

    /**
     * Launch Chrome with remote debugging enabled.
     *
     * @param profileDir     user-data-dir for Chrome (session persistence)
     * @param proxyAddress   proxy address for --proxy-server (e.g. "http://localhost:18080"), or null for no proxy
     * @param viewportWidth  window width
     * @param viewportHeight window height
     */
    public void launch(String profileDir, String proxyAddress, int viewportWidth, int viewportHeight)
            throws IOException {

        String chromePath = findChromePath();
        debugPort = findFreePort(9222);

        List<String> cmd = new ArrayList<>();
        cmd.add(chromePath);
        cmd.add("--remote-debugging-port=" + debugPort);
        cmd.add("--user-data-dir=" + profileDir);
        cmd.add("--window-size=" + viewportWidth + "," + viewportHeight);

        // Anti-detection flags — these are the same flags a normal user has
        // except disable-blink-features prevents navigator.webdriver=true
        cmd.add("--disable-blink-features=AutomationControlled");
        cmd.add("--no-first-run");
        cmd.add("--no-default-browser-check");
        cmd.add("--disable-infobars");
        cmd.add("--disable-session-crashed-bubble");
        cmd.add("--disable-backgrounding-occluded-windows");

        // Do NOT add --enable-automation (which Playwright normally adds)

        if (proxyAddress != null && !proxyAddress.isEmpty()) {
            cmd.add("--proxy-server=" + proxyAddress);
        }

        // Start with a blank page — we'll navigate ourselves
        cmd.add("about:blank");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        // Discard Chrome's stderr/stdout to avoid blocking
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);

        process = pb.start();
        System.out.println("  Chrome launched (PID " + process.pid() + ") with debug port " + debugPort);
    }

    /** Wait for Chrome's DevTools to be ready (polls /json/version). */
    public void waitForReady(int timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        IOException lastEx = null;

        while (System.currentTimeMillis() < deadline) {
            try {
                URL url = new URL("http://localhost:" + debugPort + "/json/version");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(1000);
                conn.setReadTimeout(1000);
                int code = conn.getResponseCode();
                conn.disconnect();
                if (code == 200) {
                    System.out.println("  Chrome DevTools ready on port " + debugPort);
                    return;
                }
            } catch (IOException e) {
                lastEx = e;
            }
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        }

        throw new IOException("Chrome DevTools did not become ready within " + timeoutMs + "ms",
                lastEx);
    }

    public int getDebugPort() {
        return debugPort;
    }

    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    /** Kill the Chrome process. */
    public void kill() {
        if (process != null) {
            process.destroyForcibly();
            try { process.waitFor(); } catch (InterruptedException ignored) {}
            process = null;
        }
    }
}

