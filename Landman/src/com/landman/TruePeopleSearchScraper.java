package com.landman;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scraper that queries truepeoplesearch.com for a name + city/state and
 * extracts phone numbers and email addresses from the detail pages.
 *
 * Launches real Chrome (not Playwright's Chromium) with remote debugging,
 * then connects via CDP. This avoids all Playwright automation flags that
 * anti-bot systems detect.
 */
public class TruePeopleSearchScraper {

    private static final String BASE_LINK = "https://www.truepeoplesearch.com/";

    // ── Proxy configuration ──────────────────────────────────────────────
    private static final boolean USE_PROXY = true;
    private static final String PROXY_SERVER = "premium.residential.proxyrack.net:9000";
    private static final String PROXY_USERNAME = "gefabebibozasu-country-US";
    private static final String PROXY_PASSWORD = "TJEZYCE-6CBC90A-NUMCLFK-QVADQS7-7YK3VAU-UCFS8QQ-PCHOUQJ";

    private static final int CDP_PORT = 9222;

    private static final Pattern PHONE_PATTERN = Pattern.compile("\\(\\d{3}\\) \\d{3}-\\d{4}");
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "\\b[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}\\b");
    private static final Random RANDOM = new Random();

    // Persistent profile so cookies/CAPTCHA tokens survive across runs
    private static final String PROFILE_DIR =
            Paths.get(System.getProperty("user.home"), ".landman-browser-profile").toString();

    // ── Shared browser session ──────────────────────────────────────────────
    private static Playwright playwright;
    private static Browser browser;
    private static BrowserContext context;
    private static Page page;
    private static CDPSession cdpSession;
    private static Process chromeProcess;
    private static boolean initialized = false;

    public static class PersonContact {
        public final List<String> phones = new ArrayList<>();
        public final List<String> emails = new ArrayList<>();
    }

    /**
     * Find Chrome executable on the system.
     */
    private static String findChrome() {
        String[] candidates = {
                "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
                System.getenv("LOCALAPPDATA") + "\\Google\\Chrome\\Application\\chrome.exe"
        };
        for (String path : candidates) {
            if (path != null && Files.exists(Path.of(path))) return path;
        }
        throw new RuntimeException("Chrome not found. Please install Google Chrome.");
    }

    /**
     * Set up CDP-level proxy authentication. This intercepts 407 challenges
     * and provides credentials directly via the DevTools protocol — no
     * extensions needed.
     */
    private static void setupCDPProxyAuth(Page pg) {
        try {
            cdpSession = context.newCDPSession(pg);

            // Register event handlers BEFORE enabling Fetch
            // so we don't miss any events

            // Handle proxy 407 auth challenges with our credentials
            cdpSession.on("Fetch.authRequired", event -> {
                try {
                    JsonObject ev = (JsonObject) event;
                    String requestId = ev.get("requestId").getAsString();
                    System.out.println("  [CDP] Auth challenge received, providing credentials...");

                    JsonObject authResponse = new JsonObject();
                    authResponse.addProperty("response", "ProvideCredentials");
                    authResponse.addProperty("username", PROXY_USERNAME);
                    authResponse.addProperty("password", PROXY_PASSWORD);

                    JsonObject params = new JsonObject();
                    params.addProperty("requestId", requestId);
                    params.add("authChallengeResponse", authResponse);

                    cdpSession.send("Fetch.continueWithAuth", params);
                } catch (Exception e) {
                    System.out.println("  CDP proxy auth error: " + e.getMessage());
                }
            });

            // Every intercepted request must be resumed immediately
            cdpSession.on("Fetch.requestPaused", event -> {
                try {
                    JsonObject ev = (JsonObject) event;
                    String requestId = ev.get("requestId").getAsString();

                    JsonObject params = new JsonObject();
                    params.addProperty("requestId", requestId);
                    cdpSession.send("Fetch.continueRequest", params);
                } catch (Exception e) {
                    // ignore — request may already have been handled
                }
            });

            // Now enable Fetch — intercept ALL requests so auth events fire
            JsonObject fetchParams = new JsonObject();
            fetchParams.addProperty("handleAuthRequests", true);
            // No patterns = all requests are intercepted (required for auth to work)
            cdpSession.send("Fetch.enable", fetchParams);

            System.out.println("  CDP proxy auth handler installed");
        } catch (Exception e) {
            System.out.println("  WARNING: Could not set up CDP proxy auth: " + e.getMessage());
            System.out.println("  You may need to enter proxy credentials manually in the browser.");
        }
    }

    /**
     * Launch real Chrome with remote debugging and proxy, then connect via CDP.
     * This avoids all Playwright automation flags that anti-bot systems detect.
     */
    public static void initialize() {
        System.setProperty("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");

        // Kill any Chrome already using our debug port
        try {
            new ProcessBuilder("cmd", "/c",
                    "for /f \"tokens=5\" %a in ('netstat -aon ^| findstr :" + CDP_PORT + "') do taskkill /F /PID %a")
                    .start().waitFor();
        } catch (Exception ignored) {}

        // Build Chrome command line — NO automation flags at all
        List<String> chromeArgs = new ArrayList<>(List.of(
                findChrome(),
                "--remote-debugging-port=" + CDP_PORT,
                "--user-data-dir=" + PROFILE_DIR,
                "--no-first-run",
                "--no-default-browser-check",
                "--window-size=1920,1080",
                "--start-maximized"
        ));

        if (USE_PROXY) {
            chromeArgs.add("--proxy-server=http://" + PROXY_SERVER);
            System.out.println("  Proxy enabled: " + PROXY_SERVER);
        }

        System.out.println("  Browser profile: " + PROFILE_DIR);
        System.out.println("  Launching Chrome with CDP on port " + CDP_PORT + "...");

        try {
            chromeProcess = new ProcessBuilder(chromeArgs)
                    .redirectErrorStream(true)
                    .start();

            // Give Chrome time to start and open the debug port
            Thread.sleep(3000);
        } catch (Exception e) {
            throw new RuntimeException("Failed to launch Chrome: " + e.getMessage(), e);
        }

        // Connect Playwright to the real Chrome via CDP
        playwright = Playwright.create();
        browser = playwright.chromium().connectOverCDP("http://localhost:" + CDP_PORT);

        List<BrowserContext> contexts = browser.contexts();
        context = contexts.isEmpty() ? browser.newContext() : contexts.get(0);

        List<Page> pages = context.pages();
        page = pages.isEmpty() ? context.newPage() : pages.get(0);

        // Set up CDP proxy auth BEFORE navigating
        if (USE_PROXY && !PROXY_USERNAME.isEmpty()) {
            setupCDPProxyAuth(page);
        }

        // Navigate to homepage
        System.out.println("  Opening TruePeopleSearch...");
        page.navigate(BASE_LINK, new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                .setTimeout(60000));

        // Wait for Cloudflare and any CAPTCHAs to be solved
        waitForHumanVerification(page, "homepage");

        // Disable CDP Fetch interception now that proxy credentials are cached.
        // Keeping Fetch active changes network timing in ways anti-bot detects.
        if (cdpSession != null) {
            try {
                cdpSession.send("Fetch.disable");
                System.out.println("  CDP Fetch disabled (proxy credentials cached)");
            } catch (Exception ignored) {}
        }

        System.out.println("  Browser session ready: " + page.title());
        initialized = true;
    }

    /**
     * Shut down the shared browser session.
     */
    public static void shutdown() {
        try {
            if (cdpSession != null) cdpSession.detach();
        } catch (Exception ignored) {}
        try {
            if (browser != null) browser.close();
        } catch (Exception ignored) {}
        try {
            if (playwright != null) playwright.close();
        } catch (Exception ignored) {}
        try {
            if (chromeProcess != null && chromeProcess.isAlive()) chromeProcess.destroyForcibly();
        } catch (Exception ignored) {}
        page = null;
        context = null;
        browser = null;
        cdpSession = null;
        playwright = null;
        chromeProcess = null;
        initialized = false;
    }

    /**
     * Check whether the browser session is still usable.
     */
    private static boolean isSessionAlive() {
        if (!initialized || page == null || browser == null) return false;
        try {
            page.title(); // lightweight check – throws if page/browser is closed
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Tear down the current session and start a fresh one.
     */
    private static void reinitialize() {
        System.out.println("  Reinitializing browser session...");
        shutdown();
        initialize();
    }

    /**
     * Search TruePeopleSearch for the given name and city/state
     * using the shared browser session.
     */
    public static PersonContact search(String name, String city, String state) {
        if (!initialized || !isSessionAlive()) {
            if (initialized) {
                System.out.println("  Browser session is dead, restarting...");
            }
            reinitialize();
        }

        PersonContact result = new PersonContact();
        List<String> resultLinks = new ArrayList<>();

        try {
            // ── Step 1: Build search URL and navigate directly ──────────
            String encodedName = java.net.URLEncoder.encode(name, "UTF-8");
            String encodedLocation = java.net.URLEncoder.encode(city + ", " + state, "UTF-8");
            String searchUrl = BASE_LINK + "results?name=" + encodedName
                    + "&citystatezip=" + encodedLocation;

            page.navigate(searchUrl, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(60000));

            // Wait for any CAPTCHAs on the results page
            waitForHumanVerification(page, "results");
            humanDelay(2000, 5000);

            System.out.println("  Results URL: " + page.url());
            System.out.println("  Results title: " + page.title());

            // Parse results
            String html = page.content();
            Document doc = Jsoup.parse(html);

            // Find result cards
            Elements cards = doc.select("[data-detail-link]");
            String cityState = city + ", " + state;
            for (Element card : cards) {
                String text = card.text();
                if (text != null && text.contains(cityState)) {
                    String detailLink = card.attr("data-detail-link");
                    if (detailLink != null && !detailLink.isEmpty()) {
                        resultLinks.add(BASE_LINK + detailLink);
                    }
                }
            }

            // Fallback: try links containing /find/person/
            if (resultLinks.isEmpty()) {
                Elements personLinks = doc.select("a[href*='/find/person/']");
                for (Element link : personLinks) {
                    String href = link.attr("href");
                    if (href != null && !href.isEmpty()) {
                        String fullUrl = href.startsWith("http") ? href : BASE_LINK + href.replaceFirst("^/", "");
                        if (!resultLinks.contains(fullUrl)) {
                            resultLinks.add(fullUrl);
                        }
                    }
                }
            }

            System.out.println("  Found persons: " + resultLinks.size());

        } catch (Exception ex) {
            System.out.println("  Error during search: " + ex.getMessage());
            // If the browser died, mark session as dead so next call reinitializes
            if (!isSessionAlive()) {
                System.out.println("  Browser session lost — will reinitialize on next search.");
                initialized = false;
            }
        }

        // ── Step 2: Visit each detail link and scrape phones/emails ─────
        Set<String> foundPhones = new LinkedHashSet<>();
        Set<String> foundEmails = new LinkedHashSet<>();

        for (String link : resultLinks) {
            try {
                System.out.println("  Visiting: " + link);
                page.navigate(link, new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(60000));

                waitForHumanVerification(page, "detail");
                humanDelay(2000, 5000);

                String html = page.content();
                Document doc = Jsoup.parse(html);
                String pageText = doc.body().text();

                // Extract phone numbers
                Matcher pm = PHONE_PATTERN.matcher(pageText);
                while (pm.find()) {
                    foundPhones.add(pm.group());
                }

                // Extract emails (exclude support email)
                Matcher em = EMAIL_PATTERN.matcher(pageText);
                while (em.find()) {
                    String email = em.group();
                    if (!"support@truepeoplesearch.com".equalsIgnoreCase(email)) {
                        foundEmails.add(email);
                    }
                }

            } catch (Exception ex) {
                System.out.println("  Error on detail page: " + ex.getMessage());
                if (!isSessionAlive()) {
                    System.out.println("  Browser session lost — skipping remaining detail pages.");
                    initialized = false;
                    break;
                }
            }
        }

        result.phones.addAll(foundPhones);
        result.emails.addAll(foundEmails);
        return result;
    }

    // ── Helper methods ──────────────────────────────────────────────────────

    /**
     * Returns true only for errors that mean the browser/page is gone for good.
     */
    private static boolean isFatalBrowserError(PlaywrightException e) {
        String msg = e.getMessage();
        if (msg == null) return false;
        return msg.contains("Target page, context or browser has been closed")
                || msg.contains("Browser has been closed")
                || msg.contains("browser.newContext: Browser closed");
    }

    private static void waitForHumanVerification(Page pg, String ctx) {
        int maxWait = 60; // 60 * 2s = 120 seconds max
        boolean prompted = false;
        for (int i = 0; i < maxWait; i++) {
            String title = "";
            String bodyText = "";

            try {
                // Let the page settle if it's mid-navigation
                pg.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED,
                        new Page.WaitForLoadStateOptions().setTimeout(5000));
            } catch (PlaywrightException e) {
                if (isFatalBrowserError(e)) throw e;
                // timeout or navigation-in-progress — that's fine, we'll check what we can
            }

            try {
                title = pg.title().toLowerCase();
            } catch (PlaywrightException e) {
                if (isFatalBrowserError(e)) throw e;
                // "Execution context was destroyed" = page is navigating, treat as still blocked
            }

            try {
                bodyText = pg.innerText("body").trim();
            } catch (PlaywrightException e) {
                if (isFatalBrowserError(e)) throw e;
                // transient error during navigation
            }

            boolean isBlocked = title.isEmpty() // couldn't even read title — page is navigating
                    || title.contains("just a moment")
                    || title.contains("attention required")
                    || title.contains("captcha challenge")
                    || bodyText.contains("Verification Required")
                    || bodyText.contains("Slide right to secure your access")
                    || bodyText.contains("Access is temporarily restricted")
                    || bodyText.contains("We detect unusual activity")
                    || (bodyText.length() < 50 && title.equals("truepeoplesearch.com"));

            if (!isBlocked) {
                return; // Page is clear, proceed
            }

            if (!prompted) {
                System.out.println("  ** CAPTCHA/verification detected on " + ctx
                        + " — please solve it in the browser window **");
                prompted = true;
            }
            if (i % 5 == 0 && i > 0) {
                System.out.println("  Waiting for manual verification... (" + (i * 2) + "s)");
            }

            try {
                pg.waitForTimeout(2000);
            } catch (PlaywrightException e) {
                if (isFatalBrowserError(e)) throw e;
            }
        }

        System.out.println("  WARNING: Verification timeout after 120s, continuing anyway");
    }

    /**
     * Random delay to mimic human behavior.
     */
    private static void humanDelay(int minMs, int maxMs) {
        page.waitForTimeout(minMs + RANDOM.nextInt(maxMs - minMs));
    }
}
