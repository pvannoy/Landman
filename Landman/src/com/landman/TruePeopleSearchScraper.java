package com.landman;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/**
 * Scraper for truepeoplesearch.com.
 *
 * Delegates all browser automation to a Python subprocess (scraper.py) that
 * uses undetected-chromedriver. This bypasses DataDome's JA3/TLS fingerprint
 * detection that blocks standard Selenium.
 *
 * Protocol between Java and Python:
 *   Java → Python (stdin):  {"name":"...", "city":"...", "state":"..."}\n
 *   Python → Java (stdout): {"phones":[...], "emails":[...], "error":null}\n
 *   Python → Java (stdout): {"prompt":"CAPTCHA_REQUIRED"}\n  (user action needed)
 *   Java → Python (stdin):  {"quit":true}\n  (on shutdown)
 *
 * scraper.py must be in the same directory as the compiled jar, or on the
 * path configured by SCRAPER_PY_PATH below.
 */
public class TruePeopleSearchScraper {

    // ── Configuration ─────────────────────────────────────────────────────────

    /**
     * Path to scraper.py. Defaults to the same directory as the running jar.
     * Change this if scraper.py lives elsewhere.
     */
    private static final String SCRAPER_PY_PATH = resolvePyPath();

    /**
     * Full path to the Python executable. Uses the path discovered during
     * pip install. Adjust if Python is installed elsewhere on your machine.
     */
    private static final String PYTHON_EXE =
            "C:\\Users\\Phil\\AppData\\Local\\Python\\pythoncore-3.14-64\\python.exe";

    // ── Inner types ───────────────────────────────────────────────────────────

    public static class PersonContact {
        public final List<String> phones = new ArrayList<>();
        public final List<String> emails = new ArrayList<>();

        @Override
        public String toString() {
            return "Phones: " + phones + "\nEmails: " + emails;
        }
    }

    /** Thrown when TPS IP-bans this session and it doesn't clear within 15 min. */
    @SuppressWarnings("serial")
	public static class IpBanException extends Exception {
        public IpBanException(String message) { super(message); }
    }

    // ── Subprocess singleton ──────────────────────────────────────────────────

    private static Process        pyProcess;
    private static BufferedWriter pyIn;     // Java → Python
    private static BufferedReader pyOut;    // Python → Java
    private static Thread         pyErrThread; // drains stderr so it doesn't block

    /**
     * Start the Python subprocess if it isn't already running.
     * Blocks until scraper.py prints {"ready": true}.
     */
    private static synchronized void ensureStarted() throws Exception {
        if (pyProcess != null && pyProcess.isAlive()) return;

        System.out.println("Starting Python scraper subprocess...");
        System.out.println("  Python : " + PYTHON_EXE);
        System.out.println("  Script : " + SCRAPER_PY_PATH);

        if (!new File(PYTHON_EXE).exists()) {
            throw new RuntimeException(
                    "Python executable not found at: " + PYTHON_EXE
                    + "\nUpdate PYTHON_EXE in TruePeopleSearchScraper.java.");
        }
        if (!new File(SCRAPER_PY_PATH).exists()) {
            throw new RuntimeException(
                    "scraper.py not found at: " + SCRAPER_PY_PATH
                    + "\nPlace scraper.py in the same directory as the jar.");
        }

        ProcessBuilder pb = new ProcessBuilder(PYTHON_EXE, "-u", SCRAPER_PY_PATH);
        pb.redirectErrorStream(false);
        pyProcess = pb.start();

        pyIn  = new BufferedWriter(new OutputStreamWriter(
                pyProcess.getOutputStream(), StandardCharsets.UTF_8));
        pyOut = new BufferedReader(new InputStreamReader(
                pyProcess.getInputStream(), StandardCharsets.UTF_8));

        // Drain stderr to console so Python errors are visible
        pyErrThread = new Thread(() -> {
            try (BufferedReader err = new BufferedReader(new InputStreamReader(
                    pyProcess.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = err.readLine()) != null) {
                    System.err.println("[scraper.py] " + line);
                }
            } catch (IOException ignored) {}
        });
        pyErrThread.setDaemon(true);
        pyErrThread.start();

        // Wait for {"ready": true}
        String ready = pyOut.readLine();
        if (ready == null || !ready.contains("\"ready\"")) {
            pyProcess.destroyForcibly();
            throw new RuntimeException("scraper.py did not send ready signal. Got: " + ready);
        }
        System.out.println("Python scraper ready.");
    }

    /** Shut down the Python subprocess. */
    public static synchronized void quit() {
        if (pyProcess != null) {
            try {
                pyIn.write("{\"quit\":true}\n");
                pyIn.flush();
            } catch (Exception ignored) {}
            try { pyProcess.waitFor(5, java.util.concurrent.TimeUnit.SECONDS); }
            catch (Exception ignored) {}
            pyProcess.destroyForcibly();
            pyProcess = null;
        }
    }

    /** Kill and restart the subprocess (called after browser crash). */
    public static synchronized void restartDriver() {
        System.out.println("Restarting Python scraper...");
        if (pyProcess != null) {
            pyProcess.destroyForcibly();
            pyProcess = null;
        }
        System.out.println("Restart complete. New session will start on next search.");
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public static PersonContact search(String name, String city, String state)
            throws Exception, IpBanException {

        ensureStarted();

        // Build JSON request
        String req = String.format(
                "{\"name\":%s,\"city\":%s,\"state\":%s}\n",
                jsonStr(name), jsonStr(city), jsonStr(state));

        synchronized (TruePeopleSearchScraper.class) {
            pyIn.write(req);
            pyIn.flush();
        }

        // Read response lines until we get a result (not a prompt)
        PersonContact result = new PersonContact();
        while (true) {
            String line;
            synchronized (TruePeopleSearchScraper.class) {
                line = pyOut.readLine();
            }
            if (line == null) {
                throw new RuntimeException("scraper.py closed unexpectedly.");
            }

            // Handle prompt messages from Python
            if (line.contains("\"prompt\"")) {
                String prompt = extractString(line, "prompt");
                switch (prompt) {
                    case "CAPTCHA_REQUIRED" -> {
                        System.out.println();
                        System.out.println("  *** CAPTCHA REQUIRED ***");
                        System.out.println("  TruePeopleSearch has shown a captcha in the browser window.");
                        System.out.println("  Please solve it manually. The program will resume automatically.");
                        System.out.println("  TIP: Slider captcha — drag SLOWLY with a slight wobble.");
                    System.out.println("       'Incorrect device time' captcha — click Submit. Also check your system clock is correct.");
                    System.out.println("       Cloudflare checkbox — click the checkbox.");
                        System.out.println("  Waiting up to 3 minutes...");
                        System.out.println();
                    }
                    case "CAPTCHA_SOLVED" ->
                        System.out.println("  Captcha solved — resuming shortly...");
                    case "CAPTCHA_TIMEOUT" ->
                        System.out.println("  Captcha not solved within 3 minutes. Skipping row.");
                    case "ACCESS_RESTRICTED" -> {
                        System.out.println();
                        System.out.println("  *** ACCESS TEMPORARILY RESTRICTED / RATE LIMITED ***");
                        System.out.println("  Waiting up to 15 minutes for the restriction to lift...");
                        System.out.println();
                    }
                    case "ACCESS_RESTORED" ->
                        System.out.println("  Access restored — resuming.");
                    default ->
                        System.out.println("  [scraper] " + prompt);
                }
                continue; // keep reading until we get the result JSON
            }

            // Result line
            if (line.contains("\"error\"")) {
                String error = extractString(line, "error");
                if (error != null && error.contains("ban did not lift")) {
                    throw new IpBanException(error);
                }
                // Non-fatal error — return empty contact
                if (error != null && !error.equals("null")) {
                    System.out.println("  Search error: " + error);
                }
            }

            // Parse phones array
            result.phones.addAll(parseJsonStringArray(line, "phones"));

            // Parse emails array
            result.emails.addAll(parseJsonStringArray(line, "emails"));

            return result;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String resolvePyPath() {
        // Search for scraper.py in several likely locations:
        // 1. Same directory as the running jar (target/)
        // 2. Parent of jar directory (project root)
        // 3. src/ folder next to target/
        // 4. Current working directory
        try {
            String jarDir = new File(
                    TruePeopleSearchScraper.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).getParent();
            for (String candidate : new String[]{
                    jarDir + "\\scraper.py",
                    jarDir + "\\..\\scraper.py",
                    jarDir + "\\..\\src\\scraper.py",
                    "scraper.py"
            }) {
                File f = new File(candidate);
                if (f.exists()) return f.getAbsolutePath();
            }
        } catch (Exception ignored) {}
        return "scraper.py"; // final fallback
    }

    /** Minimal JSON string escaping. */
    private static String jsonStr(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r") + "\"";
    }

    /** Extract a string value from a flat JSON line by key. */
    private static String extractString(String json, String key) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|null)");
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** Parse a JSON string array value from a flat JSON line by key. */
    private static List<String> parseJsonStringArray(String json, String key) {
        List<String> result = new ArrayList<>();
        Pattern outer = Pattern.compile("\"" + key + "\"\\s*:\\s*\\[([^\\]]*)\\]");
        Matcher om = outer.matcher(json);
        if (!om.find()) return result;
        String inner = om.group(1);
        Pattern item = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher im = item.matcher(inner);
        while (im.find()) {
            result.add(im.group(1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\n", "\n"));
        }
        return result;
    }
}