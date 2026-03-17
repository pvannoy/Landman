package com.landman;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scraper for truepeoplesearch.com.
 *
 * Strategy: Launch a real Chrome process via ProcessBuilder, then control it
 * via raw CDP (WebSocket) — NO Playwright in the loop at all.  DataDome sees
 * zero automation libraries.  This is identical to what happens when a user
 * opens Chrome DevTools (F12) — the same WebSocket protocol.
 *
 * Proxy auth is handled by LocalProxyForwarder on localhost.
 * Search is done by filling the homepage form + clicking submit, not by
 * navigating directly to /results (which is a bot signal).
 */
public class TruePeopleSearchScraper {

    private static final String BASE_LINK = "https://www.truepeoplesearch.com/";

    // -- Proxy configuration --
    private static final boolean USE_PROXY = true;
    private static final String PROXY_HOST = "premium.residential.proxyrack.net";
    private static final int PROXY_PORT = 9000;
    private static final String PROXY_USERNAME = "gefabebibozasu-country-US";
    private static final String PROXY_PASSWORD = "TJEZYCE-6CBC90A-NUMCLFK-QVADQS7-7YK3VAU-UCFS8QQ-PCHOUQJ";
    private static final int LOCAL_PROXY_PORT = 18090;

    private static final Pattern PHONE_PATTERN = Pattern.compile("\\(\\d{3}\\) \\d{3}-\\d{4}");
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "\\b[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}\\b");
    private static final Random RNG = new Random();

    private static final String PROFILE_DIR =
            Paths.get(System.getProperty("user.home"), ".landman-browser-profile").toString();
    private static final Path FLAGGED_MARKER =
            Paths.get(PROFILE_DIR, ".flagged");

    private static final int[][] VIEWPORT_SIZES = {
            {1920, 1080}, {1536, 864}, {1440, 900}, {1366, 768},
            {1280, 720}, {1600, 900}, {1680, 1050}, {1920, 1200}
    };

    // -- Session state --
    private static ChromeLauncher chromeLauncher;
    private static LocalProxyForwarder proxyForwarder;
    private static RawCdpClient cdp;
    private static boolean initialized = false;
    private static int viewportWidth;
    private static int viewportHeight;
    private static String lastProxyIp = "";
    private static boolean homepageVisited = false;

    public static class PersonContact {
        public final List<String> phones = new ArrayList<>();
        public final List<String> emails = new ArrayList<>();
    }

    // ===================================================================
    //  Lifecycle
    // ===================================================================

    private static void clearFlaggedProfile() {
        if (Files.exists(FLAGGED_MARKER)) {
            System.out.println("  Browser profile was flagged - clearing for fresh start...");
            try {
                Path profilePath = Path.of(PROFILE_DIR);
                if (Files.exists(profilePath)) {
                    Files.walk(profilePath)
                            .sorted(Comparator.reverseOrder())
                            .forEach(p -> {
                                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                            });
                }
                System.out.println("  Profile cleared.");
            } catch (IOException e) {
                System.out.println("  WARNING: Could not fully clear profile: " + e.getMessage());
            }
        }
    }

    private static void markProfileFlagged() {
        try {
            Files.createDirectories(Path.of(PROFILE_DIR));
            Files.writeString(FLAGGED_MARKER, "flagged");
        } catch (IOException ignored) {}
    }

    /** Called once from Main.java. */
    public static void initialize() {
        clearFlaggedProfile();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { shutdown(); } catch (Exception ignored) {}
        }));

        launchAndConnect();
    }

    private static void launchAndConnect() {
        int[] vp = VIEWPORT_SIZES[RNG.nextInt(VIEWPORT_SIZES.length)];
        viewportWidth = vp[0];
        viewportHeight = vp[1];

        // 1. Start local proxy forwarder
        if (USE_PROXY && proxyForwarder == null) {
            try {
                proxyForwarder = new LocalProxyForwarder(
                        LOCAL_PROXY_PORT, PROXY_HOST, PROXY_PORT,
                        PROXY_USERNAME, PROXY_PASSWORD);
                proxyForwarder.start();
            } catch (IOException e) {
                System.out.println("  WARNING: Could not start proxy forwarder: " + e.getMessage());
            }
        }

        // 2. Launch real Chrome
        try {
            chromeLauncher = new ChromeLauncher();
            String proxyAddr = USE_PROXY ? "http://localhost:" + LOCAL_PROXY_PORT : null;

            System.out.println("  Launching real Chrome (no automation libraries)...");
            System.out.println("  Profile: " + PROFILE_DIR);
            System.out.println("  Viewport: " + viewportWidth + "x" + viewportHeight);
            if (USE_PROXY) {
                System.out.println("  Proxy: localhost:" + LOCAL_PROXY_PORT
                        + " -> " + PROXY_HOST + ":" + PROXY_PORT);
            }

            chromeLauncher.launch(PROFILE_DIR, proxyAddr, viewportWidth, viewportHeight);
            chromeLauncher.waitForReady(15000);
        } catch (IOException e) {
            System.out.println("  ERROR: Failed to launch Chrome: " + e.getMessage());
            throw new RuntimeException("Chrome launch failed", e);
        }

        // 3. Connect raw CDP WebSocket (NO Playwright)
        try {
            System.out.println("  Connecting via raw CDP WebSocket (no Playwright)...");
            cdp = new RawCdpClient(chromeLauncher.getDebugPort());
            cdp.connect();
            System.out.println("  CDP connected.");
        } catch (Exception e) {
            System.out.println("  ERROR: CDP connection failed: " + e.getMessage());
            throw new RuntimeException("CDP connection failed", e);
        }

        // 4. Verify proxy
        if (USE_PROXY) {
            verifyProxy();
        }

        System.out.println("  Browser session ready.");
        initialized = true;
        homepageVisited = false;
    }

    private static void verifyProxy() {
        try {
            System.out.println("  Verifying proxy IP...");
            cdp.navigate("https://api.ipify.org/?format=text", 30000);
            sleep(2000);
            String ip = cdp.getBodyText().trim();
            System.out.println("  External IP (should be proxy, NOT your real IP): " + ip);
            if (!ip.isEmpty() && ip.equals(lastProxyIp)) {
                System.out.println("  WARNING: Proxy IP has NOT changed from last session!");
            }
            lastProxyIp = ip;
        } catch (Exception e) {
            System.out.println("  WARNING: Could not verify proxy IP: " + e.getMessage());
        }
    }

    public static void shutdown() {
        if (cdp != null) {
            try { cdp.close(); } catch (Exception ignored) {}
            cdp = null;
        }
        if (chromeLauncher != null) {
            chromeLauncher.kill();
            chromeLauncher = null;
        }
        if (proxyForwarder != null) {
            proxyForwarder.stop();
            proxyForwarder = null;
        }
        initialized = false;
    }

    private static boolean isSessionAlive() {
        if (!initialized || cdp == null) return false;
        if (chromeLauncher == null || !chromeLauncher.isAlive()) return false;
        if (!cdp.isConnected()) return false;
        try {
            cdp.getTitle();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void reinitialize() {
        System.out.println("  Reinitializing browser session...");

        if (cdp != null) {
            try { cdp.close(); } catch (Exception ignored) {}
            cdp = null;
        }
        if (chromeLauncher != null) {
            chromeLauncher.kill();
            chromeLauncher = null;
        }
        if (proxyForwarder != null) {
            proxyForwarder.stop();
            proxyForwarder = null;
        }
        initialized = false;
        homepageVisited = false;

        markProfileFlagged();
        clearFlaggedProfile();

        launchAndConnect();
    }

    // ===================================================================
    //  Search
    // ===================================================================

    public static PersonContact search(String name, String city, String state) {
        int maxRetries = 5;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            if (!initialized || !isSessionAlive()) {
                if (initialized) {
                    System.out.println("  Browser session is dead, restarting...");
                }
                reinitialize();
            }

            try {
                return doSearch(name, city, state);
            } catch (Exception ex) {
                String msg = ex.getMessage() != null ? ex.getMessage() : "";
                if (msg.contains("BOT_BLOCK_DETECTED")) {
                    System.out.println("  Bot block on attempt " + attempt + "/" + maxRetries
                            + " - clearing profile and getting new proxy IP...");
                    if (attempt < maxRetries) {
                        reinitialize();
                        long waitMs = 12000L + RNG.nextInt(15000);
                        System.out.println("  Waiting " + (waitMs / 1000) + "s before retry...");
                        sleep(waitMs);
                        continue;
                    }
                    System.out.println("  All retry attempts exhausted. Returning empty results.");
                } else {
                    System.out.println("  Error during search: " + msg);
                    if (!isSessionAlive()) {
                        System.out.println("  Browser session lost - will reinitialize on next search.");
                        initialized = false;
                    }
                }
                break;
            }
        }
        return new PersonContact();
    }

    private static PersonContact doSearch(String name, String city, String state) {
        PersonContact result = new PersonContact();
        List<String> resultLinks = new ArrayList<>();

        // ── Visit homepage and fill the search form like a human ──────────
        if (!homepageVisited) {
            System.out.println("  Visiting homepage...");
            cdp.navigate(BASE_LINK, 60000);
            humanDelay(3000, 6000);

            checkForBlock("homepage");

            homepageVisited = true;
            System.out.println("  Homepage loaded: " + cdp.getTitle());
            humanDelay(2000, 4000);

            // Random mouse movements on homepage
            randomMouseMovement(2, 4);
        }

        // ── Fill the search form via JS + submit ─────────────────────────
        System.out.println("  Filling search form for: " + name + " in " + city + ", " + state);

        // Clear and fill the name field
        cdp.evaluate("document.querySelector('#id-d-n').value = ''");
        cdp.evaluate("document.querySelector('#id-d-n').focus()");
        humanDelay(300, 600);
        typeWithHumanSpeed("#id-d-n", name);
        humanDelay(500, 1000);

        // Clear and fill the location field
        String location = city + ", " + state;
        cdp.evaluate("document.querySelector('#id-d-loc-name').value = ''");
        cdp.evaluate("document.querySelector('#id-d-loc-name').focus()");
        humanDelay(300, 600);
        typeWithHumanSpeed("#id-d-loc-name", location);
        humanDelay(800, 1500);

        // Random mouse move before clicking
        randomMouseMovement(1, 2);

        // Click the search button via JS (simulates a real click event)
        System.out.println("  Clicking search button...");
        cdp.evaluate(
            "var btn = document.querySelector('#btnSubmit-d-n');" +
            "if(btn) { btn.click(); }"
        );

        // Wait for navigation/results to load
        humanDelay(5000, 8000);

        // Check for blocks on the results page
        checkForBlock("results");

        String title = cdp.getTitle();
        String currentUrl = cdp.getCurrentUrl();
        System.out.println("  Results URL: " + currentUrl);
        System.out.println("  Results title: " + title);

        randomMouseMovement(1, 3);
        randomScroll(1, 3);

        // Parse results HTML
        String html = cdp.getPageHtml();
        Document doc = Jsoup.parse(html);

        Elements cards = doc.select("[data-detail-link]");
        String cityState = city + ", " + state;
        for (Element card : cards) {
            if (card.text().contains(cityState)) {
                String detailLink = card.attr("data-detail-link");
                if (!detailLink.isEmpty()) {
                    String fullUrl = detailLink.startsWith("http") ? detailLink
                            : "https://www.truepeoplesearch.com" + detailLink;
                    resultLinks.add(fullUrl);
                }
            }
        }

        // Fallback: grab all person links
        if (resultLinks.isEmpty()) {
            for (Element card : cards) {
                String detailLink = card.attr("data-detail-link");
                if (!detailLink.isEmpty()) {
                    String fullUrl = detailLink.startsWith("http") ? detailLink
                            : "https://www.truepeoplesearch.com" + detailLink;
                    resultLinks.add(fullUrl);
                }
            }
        }

        if (resultLinks.isEmpty()) {
            Elements personLinks = doc.select("a[href*='/find/person/']");
            for (Element link : personLinks) {
                String href = link.attr("href");
                if (!href.isEmpty()) {
                    String fullUrl = href.startsWith("http") ? href
                            : "https://www.truepeoplesearch.com" + (href.startsWith("/") ? "" : "/") + href;
                    if (!resultLinks.contains(fullUrl)) {
                        resultLinks.add(fullUrl);
                    }
                }
            }
        }

        System.out.println("  Found persons: " + resultLinks.size());

        // ── Visit each detail page ───────────────────────────────────────
        Set<String> foundPhones = new LinkedHashSet<>();
        Set<String> foundEmails = new LinkedHashSet<>();

        for (int idx = 0; idx < resultLinks.size(); idx++) {
            String link = resultLinks.get(idx);
            try {
                System.out.println("  Visiting detail " + (idx + 1) + "/" + resultLinks.size()
                        + ": " + link);
                humanDelay(3000, 6000);

                cdp.navigate(link, 60000);
                humanDelay(3000, 5000);

                checkForBlock("detail");

                randomScroll(2, 5);
                humanDelay(1000, 2000);

                String detailHtml = cdp.getPageHtml();
                Document detailDoc = Jsoup.parse(detailHtml);
                String pageText = detailDoc.body().text();

                Matcher pm = PHONE_PATTERN.matcher(pageText);
                while (pm.find()) foundPhones.add(pm.group());

                Matcher em = EMAIL_PATTERN.matcher(pageText);
                while (em.find()) {
                    String email = em.group();
                    if (!"support@truepeoplesearch.com".equalsIgnoreCase(email)) {
                        foundEmails.add(email);
                    }
                }
            } catch (Exception ex) {
                String msg = ex.getMessage() != null ? ex.getMessage() : "";
                if (msg.contains("BOT_BLOCK_DETECTED")) throw ex;
                System.out.println("  Error on detail page: " + msg);
                if (!isSessionAlive()) {
                    System.out.println("  Browser session lost - skipping remaining detail pages.");
                    initialized = false;
                    break;
                }
            }
        }

        result.phones.addAll(foundPhones);
        result.emails.addAll(foundEmails);
        return result;
    }

    // ===================================================================
    //  Helpers
    // ===================================================================

    /**
     * Type text into a field using CDP Input events, character by character
     * with human-like random delays.
     */
    private static void typeWithHumanSpeed(String selector, String text) {
        // Focus the element
        cdp.evaluate("document.querySelector('" + escapeJs(selector) + "').focus()");
        humanDelay(200, 400);

        // Type each character via dispatchKeyEvent
        for (char c : text.toCharArray()) {
            cdp.typeText(String.valueOf(c), 50, 150);
            // Occasionally pause longer (simulating thinking)
            if (RNG.nextInt(10) == 0) {
                humanDelay(200, 500);
            }
        }

        // Dispatch input event so JS listeners fire
        cdp.evaluate(
            "var el = document.querySelector('" + escapeJs(selector) + "');" +
            "el.dispatchEvent(new Event('input', {bubbles:true}));" +
            "el.dispatchEvent(new Event('change', {bubbles:true}));"
        );
    }

    /** Escape a string for embedding in JS single-quoted string. */
    private static String escapeJs(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    /**
     * Check current page for bot blocks. Throws RuntimeException with
     * BOT_BLOCK_DETECTED if a hard block is detected. Waits for solvable
     * CAPTCHAs.
     */
    private static void checkForBlock(String ctx) {
        int maxWait = 90; // up to 180 seconds
        boolean prompted = false;

        for (int i = 0; i < maxWait; i++) {
            String title = "", bodyText = "", rawHtml = "";
            try { title = cdp.getTitle(); } catch (Exception ignored) {}
            try { bodyText = cdp.getBodyText(); } catch (Exception ignored) {}
            try { rawHtml = cdp.getPageHtml(); } catch (Exception ignored) {}

            String titleLower = title.toLowerCase();
            String bodyLower = bodyText.toLowerCase();
            String htmlLower = rawHtml.toLowerCase();

            // ── Hard block — need new IP ─────────────────────────────────
            boolean isHardBlock =
                    bodyLower.contains("access is temporarily restricted")
                    || htmlLower.contains("access is temporarily restricted")
                    || bodyLower.contains("we detected unusual activity")
                    || htmlLower.contains("we detected unusual activity")
                    || bodyLower.contains("automated (bot) activity")
                    || htmlLower.contains("automated (bot) activity");

            if (isHardBlock) {
                System.out.println("  !! Hard block detected on " + ctx);
                System.out.println("  [DEBUG] Title: " + title);
                System.out.println("  [DEBUG] Body (first 200): "
                        + bodyText.substring(0, Math.min(200, bodyText.length())).replace("\n", " "));
                markProfileFlagged();
                throw new RuntimeException("BOT_BLOCK_DETECTED: Access is temporarily restricted");
            }

            // ── Solvable CAPTCHA — wait for user ─────────────────────────
            boolean isBlocked = titleLower.isEmpty()
                    || titleLower.contains("just a moment")
                    || titleLower.contains("attention required")
                    || titleLower.contains("captcha challenge")
                    || bodyLower.contains("verification required")
                    || bodyLower.contains("slide right to secure your access")
                    || htmlLower.contains("captcha-delivery.com")
                    || htmlLower.contains("datadome device check")
                    || (bodyText.trim().length() < 50 && titleLower.equals("truepeoplesearch.com"));

            if (!isBlocked) return; // all clear!

            if (!prompted) {
                System.out.println("  ** CAPTCHA detected on " + ctx
                        + " - please solve it in the Chrome window! **");
                System.out.println("  [DEBUG] Title: " + title);
                java.awt.Toolkit.getDefaultToolkit().beep();
                prompted = true;
            }
            if (i % 5 == 0 && i > 0) {
                System.out.println("  Still waiting for verification... (" + (i * 2) + "s)");
                java.awt.Toolkit.getDefaultToolkit().beep();
            }

            sleep(2000);
        }
        System.out.println("  WARNING: Verification timeout after 180s, continuing anyway");
    }

    private static void randomMouseMovement(int minMoves, int maxMoves) {
        int moves = minMoves + RNG.nextInt(maxMoves - minMoves + 1);
        for (int i = 0; i < moves; i++) {
            double x = 100.0 + RNG.nextInt(Math.max(1, viewportWidth - 200));
            double y = 100.0 + RNG.nextInt(Math.max(1, viewportHeight - 200));
            try { cdp.mouseMove(x, y); } catch (Exception ignored) {}
            sleep(100 + RNG.nextInt(300));
        }
    }

    private static void randomScroll(int minScrolls, int maxScrolls) {
        int scrolls = minScrolls + RNG.nextInt(maxScrolls - minScrolls + 1);
        double cx = viewportWidth / 2.0;
        double cy = viewportHeight / 2.0;
        for (int i = 0; i < scrolls; i++) {
            int distance = 150 + RNG.nextInt(400);
            try { cdp.scroll(0, distance, cx, cy); } catch (Exception ignored) {}
            sleep(500 + RNG.nextInt(1500));
        }
    }

    private static void humanDelay(int minMs, int maxMs) {
        sleep(minMs + RNG.nextInt(Math.max(1, maxMs - minMs)));
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
