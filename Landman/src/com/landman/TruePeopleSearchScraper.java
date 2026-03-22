package com.landman;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.devtools.DevTools;
import org.openqa.selenium.devtools.v144.page.Page;
import org.openqa.selenium.support.ui.ExpectedCondition;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

/**
 * Scraper for truepeoplesearch.com.
 *
 * Called from Main.java as: TruePeopleSearchScraper.search(name, city, state)
 *
 * Cloudflare bypass strategy:
 *   1. Uses your REAL Chrome user profile (Default) — this carries existing
 *      Cloudflare trust cookies and a real browsing history fingerprint.
 *   2. Injects CDP-level stealth scripts via Page.addScriptToEvaluateOnNewDocument
 *      so they run before any page JS, masking every Selenium/automation signal.
 *   3. Removes all Selenium automation flags from ChromeOptions.
 *   4. PageLoadStrategy.NONE prevents driver.get() from hanging on challenge pages.
 *
 * IMPORTANT: Chrome must be fully closed before starting the program.
 * Selenium cannot attach to an already-running Chrome using a user profile.
 */
public class TruePeopleSearchScraper {

    // ── Inner result type ─────────────────────────────────────────────────────

    public static class PersonContact {
        public String detailUrl;
        public final List<String> phones = new ArrayList<>();
        public final List<String> emails = new ArrayList<>();

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Detail URL: ").append(detailUrl).append("\n");
            sb.append("Phones:\n");
            for (String p : phones) sb.append("  ").append(p).append("\n");
            sb.append("Emails:\n");
            for (String e : emails) sb.append("  ").append(e).append("\n");
            return sb.toString();
        }
    }

    // ── Proxy configuration ──────────────────────────────────────────────────
    //
    // Set PROXY_ENABLED = true and fill in your ProxyRack credentials to route
    // all browser traffic through a rotating residential proxy. This prevents
    // IP-based rate limiting from truepeoplesearch.com (/ratelimited page).
    //
    // How to get these values from ProxyRack:
    //   1. Log in at proxyrack.com → Dashboard → Residential Proxies
    //   2. Copy your Username and Password from the "Authentication" section
    //   3. The gateway host and port are shown in the "Endpoint" section
    //      (typically: proxy.proxyrack.net  port: 10000)
    //
    // Leave PROXY_ENABLED = false to run without a proxy (default).
    // ─────────────────────────────────────────────────────────────────────────

    private static final boolean PROXY_ENABLED  = false;
    private static final String  PROXY_HOST     = "premium.residential.proxyrack.net";
    private static final int     PROXY_PORT     = 10000;
    private static final String  PROXY_USERNAME = "gefabebibozasu-country-US";
    private static final String  PROXY_PASSWORD = "TJEZYCE-6CBC90A-NUMCLFK-QVADQS7-7YK3VAU-UCFS8QQ-PCHOUQJ";

    // ── Patterns ──────────────────────────────────────────────────────────────

    /** US phone numbers: (555) 123-4567, 555-123-4567, 5551234567 */
    private static final Pattern PHONE_PATTERN =
            Pattern.compile("(\\(?\\d{3}\\)?[-.\\s]?\\d{3}[-.\\s]?\\d{4})");

    /** Email addresses */
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");

    /** c/o PersonName on a line */
    private static final Pattern CO_PATTERN =
            Pattern.compile("(?i)c/o\\s+(.+)");

    /** a/k/a alias — keep only the primary name */
    private static final Pattern AKA_PATTERN =
            Pattern.compile("(?i)\\s+a/k/a\\b.*");

    /** "and SecondPerson" — keep only the first */
    private static final Pattern AND_PATTERN =
            Pattern.compile("(?i)\\s+and\\b.*");

    /** Entity suffix words to strip */
    private static final Pattern ENTITY_SUFFIX_PATTERN =
            Pattern.compile("(?i)\\s+(gst|exemption|residuary|trust|estate|revocable|living|"
                    + "irrevocable|testamentary|fund|foundation|llc|inc\\.?|ltd\\.?|corp\\.?|"
                    + "co\\.|l\\.l\\.c\\.).*$");

    /** Entity prefix words to strip */
    private static final Pattern ENTITY_PREFIX_PATTERN =
            Pattern.compile("(?i)^(estate|trust|gst|residuary|exemption|revocable|living|family|"
                    + "irrevocable|testamentary|intervivos|inter\\s+vivos)\\s+(of\\s+)?");

    /** Trailing generational suffixes */
    private static final Pattern SUFFIX_PATTERN =
            Pattern.compile("(?i),?\\s*(Jr\\.?|Sr\\.?|II|III|IV|V|VI)\\s*$");

    // ── Stealth script injected before every page load ────────────────────────

    /**
     * This script runs via CDP's Page.addScriptToEvaluateOnNewDocument,
     * meaning it executes BEFORE any page JavaScript sees the window.
     * It masks every property Cloudflare checks to detect automation.
     */
    private static final String STEALTH_SCRIPT = """
            // 1. Remove navigator.webdriver
            Object.defineProperty(navigator, 'webdriver', { get: () => undefined });

            // 2. Restore plugins array (headless Chrome has 0 plugins)
            Object.defineProperty(navigator, 'plugins', {
                get: () => [1, 2, 3, 4, 5],
            });

            // 3. Restore languages
            Object.defineProperty(navigator, 'languages', {
                get: () => ['en-US', 'en'],
            });

            // 4. Fix chrome object (missing in CDP-controlled Chrome)
            window.chrome = {
                runtime: {},
                loadTimes: function() {},
                csi: function() {},
                app: {}
            };

            // 5. Fix permissions (automation returns different values)
            const originalQuery = window.navigator.permissions.query;
            window.navigator.permissions.query = (parameters) => (
                parameters.name === 'notifications'
                    ? Promise.resolve({ state: Notification.permission })
                    : originalQuery(parameters)
            );

            // 6. WebGL vendor/renderer — headless Chrome reports "SwiftShader"
            const getParameter = WebGLRenderingContext.prototype.getParameter;
            WebGLRenderingContext.prototype.getParameter = function(parameter) {
                if (parameter === 37445) return 'Intel Inc.';
                if (parameter === 37446) return 'Intel Iris OpenGL Engine';
                return getParameter.call(this, parameter);
            };
            """;

    // ── Shared WebDriver singleton ────────────────────────────────────────────

    private static ChromeDriver driver;

    /**
     * Kills any lingering Chrome processes that would block profile access.
     * Chrome keeps a background process alive even after all windows are closed,
     * which causes "DevToolsActivePort file doesn't exist" on the next launch.
     */
    /**
     * Runs a single taskkill command with a 5-second timeout.
     * Uses ProcessBuilder with stream redirection to prevent output-buffer hangs.
     */
    private static void taskkill(String... args) {
        try {
            String[] cmd = new String[args.length + 1];
            cmd[0] = "taskkill";
            System.arraycopy(args, 0, cmd, 1, args.length);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);                  // merge stderr into stdout
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD); // discard output entirely
            Process p = pb.start();
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();                       // kill if still running after 5 s
            }
        } catch (Exception ignored) {}
    }

    private static void killExistingChrome() {
        try {
            System.out.println("Attempting to terminate Existing Chrome processes...");

            // Graceful shutdown first, then force-kill
            taskkill("/IM", "chrome.exe", "/T");
            Thread.sleep(1000);
            taskkill("/F", "/IM", "chrome.exe", "/T");
            taskkill("/F", "/IM", "chrome_crashpad_handler.exe", "/T");

            // Diagnose profile directory state
            String userDataPath = System.getenv("LOCALAPPDATA")
                    + "\\Google\\Chrome\\User Data";
            String defaultPath  = userDataPath + "\\Default";
            String lockPath     = defaultPath  + "\\lockfile";

            java.io.File userDataDir = new java.io.File(userDataPath);
            java.io.File defaultDir  = new java.io.File(defaultPath);
            java.io.File lockFile    = new java.io.File(lockPath);

            System.out.println("  LOCALAPPDATA     = " + System.getenv("LOCALAPPDATA"));
            System.out.println("  User Data exists : " + userDataDir.exists()
                    + "  (" + userDataDir.getAbsolutePath() + ")");
            System.out.println("  Default exists   : " + defaultDir.exists());
            System.out.println("  lockfile exists  : " + lockFile.exists());

            // Poll up to 15 s for lockfile to disappear
            for (int i = 0; i < 30; i++) {
                if (!lockFile.exists()) {
                    System.out.println("  lockfile gone after " + (i * 500) + " ms");
                    break;
                }
                if (i == 29) {
                    System.out.println("  WARNING: lockfile still present after 15 s");
                }
                Thread.sleep(500);
            }

            // Verify write access to the Default directory
            java.io.File testFile = new java.io.File(defaultPath + "\\selenium_write_test.tmp");
            try {
                boolean created = testFile.createNewFile();
                System.out.println("  Write access to Default dir: " + created);
                if (created) testFile.delete();
            } catch (Exception writeEx) {
                System.out.println("  Write access FAILED: " + writeEx.getMessage());
            }

            Thread.sleep(1000);
            System.out.println("Existing Chrome processes terminated.");
        } catch (Exception e) {
            System.out.println("killExistingChrome error: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
    }

    /**
     * Builds a ChromeOptions object with all stealth and stability flags.
     */
    private static ChromeOptions buildBaseOptions() {
        ChromeOptions opts = new ChromeOptions();
        opts.addArguments("--disable-blink-features=AutomationControlled");
        opts.addArguments("--no-sandbox");
        opts.addArguments("--disable-dev-shm-usage");
        opts.addArguments("--disable-infobars");
        opts.addArguments("--start-maximized");
        opts.addArguments("--no-first-run");
        opts.addArguments("--no-default-browser-check");
        opts.addArguments("--disable-extensions");
        opts.addArguments("--disable-component-update");
        opts.addArguments("--disable-background-networking");
        opts.addArguments(
                "--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                + "AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/146.0.0.0 Safari/537.36");
        opts.setExperimentalOption("excludeSwitches", new String[]{"enable-automation"});
        opts.setExperimentalOption("useAutomationExtension", false);
        opts.setPageLoadStrategy(PageLoadStrategy.NONE);

        // ── Proxy (set PROXY_ENABLED = true and fill in credentials above) ───
        if (PROXY_ENABLED) {
            // Embed credentials directly in the proxy URL using the standard
            // http://user:pass@host:port format. Chrome 130+ supports this format
            // reliably without needing an extension for authentication.
            String proxyUrl = "http://" + PROXY_USERNAME + ":" + PROXY_PASSWORD
                    + "@" + PROXY_HOST + ":" + PROXY_PORT;
            opts.addArguments("--proxy-server=" + proxyUrl);
            // Tell Chrome to use the proxy for all traffic including localhost
            opts.addArguments("--proxy-bypass-list=<-loopback>");
            System.out.println("  Proxy enabled: " + PROXY_HOST + ":" + PROXY_PORT
                    + " (user: " + PROXY_USERNAME + ")");
        }

        return opts;
    }

    /**
     * Copies just the Cookies file from the real Chrome Default profile into a
     * Selenium-owned temp directory. This lets Selenium launch a clean profile
     * (no crash-restore dialogs, no session conflicts) while still carrying the
     * real Cloudflare trust cookies.
     *
     * Returns the path to the temp user-data-dir, or null if the copy failed.
     */
    private static String buildSeleniumProfile() {
        try {
            String localAppData = System.getenv("LOCALAPPDATA");
            java.nio.file.Path realDefault = java.nio.file.Paths.get(
                    localAppData, "Google", "Chrome", "User Data", "Default");
            java.nio.file.Path realUserData = java.nio.file.Paths.get(
                    localAppData, "Google", "Chrome", "User Data");

            // Selenium-owned user-data-dir — separate from the live Chrome profile
            java.nio.file.Path seleniumUserData = java.nio.file.Paths.get(
                    localAppData, "SeleniumLandman", "User Data");
            java.nio.file.Path seleniumDefault = seleniumUserData.resolve("Default");
            java.nio.file.Files.createDirectories(seleniumDefault);

            // Copy Local State (global Chrome preferences)
            java.nio.file.Path localState = realUserData.resolve("Local State");
            if (localState.toFile().exists()) {
                java.nio.file.Files.copy(localState,
                        seleniumUserData.resolve("Local State"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            // Copy Cookies (carries Cloudflare trust tokens)
            java.nio.file.Path cookies = realDefault.resolve("Cookies");
            if (cookies.toFile().exists()) {
                java.nio.file.Files.copy(cookies,
                        seleniumDefault.resolve("Cookies"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                System.out.println("  Copied Cookies from real profile.");
            }

            // Chrome 96+ stores cookies in Default/Network/Cookies on some builds
            java.nio.file.Path networkCookies = realDefault.resolve("Network").resolve("Cookies");
            if (networkCookies.toFile().exists()) {
                java.nio.file.Path seleniumNetwork = seleniumDefault.resolve("Network");
                java.nio.file.Files.createDirectories(seleniumNetwork);
                java.nio.file.Files.copy(networkCookies,
                        seleniumNetwork.resolve("Cookies"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                System.out.println("  Copied Network/Cookies from real profile.");
            }

            return seleniumUserData.toString();
        } catch (Exception e) {
            System.out.println("  buildSeleniumProfile failed: " + e.getMessage());
            return null;
        }
    }

    private static synchronized ChromeDriver getDriver() {
        if (driver == null) {

            killExistingChrome();

            // ── Strategy 1: Selenium-owned profile with real cookies copied in ─
            // Copying just the Cookies file (not the whole profile) avoids the
            // session-restore / crash-dialog conflicts that cause DevToolsActivePort
            // errors, while still carrying the Cloudflare trust tokens.
            System.out.println("Attempting to use the real Chrome user profile...");
            String seleniumProfilePath = buildSeleniumProfile();

            ChromeDriver launched = null;

            if (seleniumProfilePath != null) {
                try {
                    ChromeOptions opts = buildBaseOptions();
                    opts.addArguments("--user-data-dir=" + seleniumProfilePath);
                    opts.addArguments("--profile-directory=Default");
                    launched = new ChromeDriver(opts);
                    System.out.println("  Launched with Selenium profile (cookies imported).");
                } catch (Exception e) {
                    String reason = e.getMessage() == null ? "unknown"
                            : e.getMessage().lines().filter(l -> !l.isBlank()).findFirst().orElse("unknown");
                    System.out.println("  Selenium profile launch failed: " + reason);
                    launched = null;
                }
            }

            // ── Strategy 2: Clean temp profile fallback ───────────────────────
            if (launched == null) {
                System.out.println("Falling back to a temporary profile (Cloudflare may prompt captcha).");
                try {
                    launched = new ChromeDriver(buildBaseOptions());
                } catch (Exception e2) {
                    throw new RuntimeException("Could not launch Chrome: " + e2.getMessage(), e2);
                }
            }

            driver = launched;

            // ── Inject stealth script via CDP ─────────────────────────────────
            try {
                DevTools devTools = driver.getDevTools();
                devTools.createSession();
                devTools.send(Page.addScriptToEvaluateOnNewDocument(STEALTH_SCRIPT, null, null, null));
                System.out.println("CDP stealth script injected successfully.");

                // ── Inject Proxy-Authorization header via CDP ──────────────────
                // Chrome sometimes ignores credentials embedded in --proxy-server URL.
                // Setting the header directly via CDP Network.setExtraHTTPHeaders
                // ensures every request carries the proxy auth token.
                if (PROXY_ENABLED) {
                    try {
                        String credentials = PROXY_USERNAME + ":" + PROXY_PASSWORD;
                        String encoded = java.util.Base64.getEncoder()
                                .encodeToString(credentials.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        devTools.send(org.openqa.selenium.devtools.v144.network.Network.enable(
                                java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty()));
                        devTools.send(org.openqa.selenium.devtools.v144.network.Network.setExtraHTTPHeaders(
                                new org.openqa.selenium.devtools.v144.network.model.Headers(
                                        java.util.Map.of("Proxy-Authorization", "Basic " + encoded))));
                        System.out.println("  Proxy-Authorization header injected via CDP.");
                    } catch (Exception proxyEx) {
                        System.out.println("  CDP proxy auth header skipped: " + proxyEx.getMessage());
                    }
                }
            } catch (Exception e) {
                System.out.println("CDP stealth injection skipped (" + e.getMessage() + "); using JS fallback.");
                try {
                    ((JavascriptExecutor) driver).executeScript(
                            "Object.defineProperty(navigator, 'webdriver', {get: () => undefined})");
                } catch (Exception ignored) {}
            }
        }
        return driver;
    }

    /** Close the shared browser. Call once after all rows are processed. */
    public static synchronized void quit() {
        if (driver != null) {
            try { driver.quit(); } catch (Exception ignored) {}
            driver = null;
        }
    }

    /**
     * Kills the current browser session and clears the singleton so the next
     * call to getDriver() (or search()) will launch a fresh Chrome instance.
     * Called by Main.java when an UnreachableBrowserException is caught.
     */
    public static synchronized void restartDriver() {
        System.out.println("Restarting browser...");
        if (driver != null) {
            try { driver.quit(); } catch (Exception ignored) {}
            driver = null;
        }
        // Kill zombie Chrome processes from the crash, then refresh cookie copy
        taskkill("/F", "/IM", "chrome.exe", "/T");
        taskkill("/F", "/IM", "chrome_crashpad_handler.exe", "/T");
        try { Thread.sleep(2000); } catch (Exception ignored) {}
        // Refresh the Selenium profile with fresh cookies before next launch
        buildSeleniumProfile();
        System.out.println("Browser restart complete. New session will start on next search.");
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Entry point called by Main.java.
     *
     * @param name  raw name from Excel (may include suffixes, c/o, entities)
     * @param city  city name
     * @param state two-letter state abbreviation
     */
    public static PersonContact search(String name, String city, String state) throws Exception {
        return search(getDriver(), name, city, state);
    }

    // ── Core scraping logic ───────────────────────────────────────────────────

    static PersonContact search(WebDriver webDriver, String name, String city, String state)
            throws Exception {

        PersonContact result = new PersonContact();

        // ── Step 1: Clean the name ────────────────────────────────────────────
        String searchName = cleanName(name);
        System.out.println("Search name: \"" + searchName + "\""
                + (searchName.equals(name.trim()) ? "" : "  (cleaned from: \"" + name.trim() + "\")"));

        if (searchName.isEmpty()) {
            System.out.println("  Skipping — could not extract a usable person name.");
            return result;
        }

        // ── Step 2: Build the results URL ─────────────────────────────────────
        String query = "name=" + pctEncode(searchName) + "&citystatezip=" + pctEncode(city + ", " + state);
        String resultsUrl = "https://www.truepeoplesearch.com/results?" + query;

        webDriver.get(resultsUrl);
        System.out.println("Navigated to: " + resultsUrl);

        // ── Step 3: Wait for results page ────────────────────────────────────
        System.out.println("Waiting for results page...");
        boolean resultsReady = waitForResultsPage(webDriver, resultsUrl);
        if (!resultsReady) {
            System.out.println("  Skipping row — results page did not load.");
            return result;
        }
        System.out.println("Results page ready.");

        // ── Step 4: Get data-detail-link from the first result card ───────────
        String detailPath = null;
        try {
            WebDriverWait wait = new WebDriverWait(webDriver, Duration.ofSeconds(10));
            WebElement firstCard = wait.until(
                    ExpectedConditions.presenceOfElementLocated(By.cssSelector("[data-detail-link]")));
            detailPath = firstCard.getAttribute("data-detail-link");
        } catch (Exception ex) {
            System.out.println("  No result cards found for: " + searchName + " / " + city + ", " + state);
            return result;
        }

        if (detailPath == null || detailPath.isBlank()) {
            System.out.println("  data-detail-link was empty.");
            return result;
        }

        // ── Step 5: Navigate to the detail page ───────────────────────────────
        result.detailUrl = "https://www.truepeoplesearch.com" + detailPath;
        System.out.println("Detail page: " + result.detailUrl);
        webDriver.get(result.detailUrl);

        // ── Step 6: Wait for detail page to render contact data ─────────────
        System.out.println("Waiting for detail page...");
        boolean detailReady = waitForDetailPage(webDriver, result.detailUrl);
        if (detailReady) {
            System.out.println("Detail page ready.");
        } else {
            System.out.println("  Detail page did not fully load; scraping whatever is present.");
        }

        // ── Step 7: Extract phones and emails ────────────────────────────────
        String html = pageHtml(webDriver);
        if (html == null) html = "";

        Set<String> phones = new LinkedHashSet<>();
        Matcher pm = PHONE_PATTERN.matcher(html);
        while (pm.find()) phones.add(pm.group(1).trim());

        Set<String> emails = new LinkedHashSet<>();
        Matcher em = EMAIL_PATTERN.matcher(html);
        while (em.find()) {
            String c = em.group();
            if (!c.endsWith(".png") && !c.endsWith(".jpg") && !c.endsWith(".svg")
                    && !c.endsWith(".gif") && !c.contains("sentry.io")
                    && !c.contains("example.com") && !c.contains("w3.org")
                    && !c.contains("schema.org")) {
                emails.add(c);
            }
        }

        result.phones.addAll(phones);
        result.emails.addAll(emails);
        return result;
    }

    // ── Detail page wait ─────────────────────────────────────────────────────

    /**
     * Waits for a TPS detail page to fully render its contact data.
     * Handles the same three blocking states as waitForResultsPage:
     *   A) Normal render  — waits for card-summary / card-block / data-link
     *   B) Cloudflare / InternalCaptcha — prompts user to solve manually
     *   C) "Access is temporarily restricted" — waits up to 10 min for cooldown
     *
     * If a captcha or restriction clears, re-navigates to detailUrl and waits again.
     * Returns true if the page loaded successfully, false if it timed out.
     */
    private static boolean waitForDetailPage(WebDriver webDriver, String detailUrl) {
        // Brief render pause (PageLoadStrategy.NONE returns before DOM is ready)
        try { Thread.sleep(1500); } catch (Exception ignored) {}

        // Check for immediate block states before polling
        String currentUrl = webDriver.getCurrentUrl();
        if (currentUrl != null && currentUrl.toLowerCase().contains("internalcaptcha")) {
            boolean solved = waitForManualCaptcha(webDriver);
            if (solved) {
                // Re-navigate to the detail page after captcha
                webDriver.get(detailUrl);
                try { Thread.sleep(2000); } catch (Exception ignored) {}
            }
            return solved;
        }
        String immediateHtml = pageHtml(webDriver);
        if (isAccessRestricted(immediateHtml)) {
            return waitForAccessRestriction(webDriver, detailUrl);
        }

        // Main poll loop — wait up to 45 s for content to appear
        try {
            WebDriverWait wait = new WebDriverWait(webDriver, Duration.ofSeconds(45));
            wait.until((ExpectedCondition<Boolean>) d -> {
                String url = d.getCurrentUrl();
                String src = pageHtml(d);
                if (src == null) return false;
                // Break out immediately if we hit any blocking state
                if (isCaptchaPage(src, url)) return true;
                if (isAccessRestricted(src)) return true;
                boolean hasContent = src.contains("card-summary")
                        || src.contains("card-block")
                        || src.contains("data-link");
                return hasContent;
            });
        } catch (Exception ignored) {}

        // Render buffer — give DOM time to finish updating
        try { Thread.sleep(2000); } catch (Exception ignored) {}

        String currentUrl2 = webDriver.getCurrentUrl();
        String src = pageHtml(webDriver);

        // Handle access restriction
        if (isAccessRestricted(src)) {
            System.out.println("  Access restricted on detail page.");
            return waitForAccessRestriction(webDriver, detailUrl);
        }

        // Handle any captcha type (redirect OR inline Cloudflare OR TPS internal)
        if (isCaptchaPage(src, currentUrl2)) {
            System.out.println();
            System.out.println("  *** CAPTCHA REQUIRED ***");
            System.out.println("  TruePeopleSearch has shown a captcha in the browser window.");
            System.out.println("  Please solve it manually. The program will resume automatically.");
            System.out.println("  Waiting up to 3 minutes...");
            System.out.println();
            try {
                WebDriverWait captchaWait = new WebDriverWait(webDriver, Duration.ofMinutes(3));
                captchaWait.until((ExpectedCondition<Boolean>) d -> {
                    String u = d.getCurrentUrl();
                    String s = pageHtml(d);
                    if (s == null) return false;
                    // Break out on access restriction so we can handle it
                    if (isAccessRestricted(s)) return true;
                    // Still on captcha — keep waiting
                    if (isCaptchaPage(s, u)) return false;
                    // Captcha is gone — success regardless of whether cards have loaded yet
                    return true;
                });
                // Brief pause to let the post-captcha page finish rendering
                try { Thread.sleep(2000); } catch (Exception ignored) {}
                String postSrc = pageHtml(webDriver);
                if (isAccessRestricted(postSrc)) {
                    System.out.println("  Access restricted after captcha.");
                    return waitForAccessRestriction(webDriver, detailUrl);
                }
                System.out.println("  Captcha solved — resuming.");
                return true;
            } catch (Exception ex) {
                System.out.println("  Captcha not solved within 3 minutes; scraping whatever is present.");
                return false;
            }
        }

        boolean hasContent = src != null
                && (src.contains("card-summary") || src.contains("card-block") || src.contains("data-link"));
        if (!hasContent) {
            System.out.println("  Detail page timed out; scraping whatever is present.");
        }
        return hasContent;
    }

    // ── Name cleaning ─────────────────────────────────────────────────────────

    static String cleanName(String raw) {
        if (raw == null) return "";
        String name = raw.trim();

        String[] lines = name.split("\\r?\\n");
        String firstLine = lines[0].trim();
        String coName = null;
        for (String line : lines) {
            Matcher m = CO_PATTERN.matcher(line.trim());
            if (m.matches()) {
                coName = m.group(1).trim();
                break;
            }
        }

        if (coName != null) {
            name = coName;
        } else {
            name = firstLine;
        }

        name = AKA_PATTERN.matcher(name).replaceAll("").trim();
        name = AND_PATTERN.matcher(name).replaceAll("").trim();
        name = ENTITY_SUFFIX_PATTERN.matcher(name).replaceAll("").trim();
        name = ENTITY_PREFIX_PATTERN.matcher(name).replaceAll("").trim();
        name = SUFFIX_PATTERN.matcher(name).replaceAll("").trim();

        if (name.contains("&")) {
            name = name.split("&")[0].trim();
        }

        return name;
    }

    // ── Captcha / rate-limit handling ─────────────────────────────────────────

    /** Returns true if the page HTML or URL indicates TPS has blocked access. */
    private static boolean isAccessRestricted(String src) {
        if (src == null) return false;
        return src.contains("Access is temporarily restricted")
                || src.contains("temporarily restricted")
                || src.contains("Automated (bot) activity")
                || src.contains("rate-limited")          // /ratelimited page
                || src.contains("Rate Limited")          // /ratelimited page title
                || src.contains("ratelimited")           // URL in page source
                || src.contains("IP address has been temporarily");
    }

    /** Returns true if the current URL is a known TPS block/rate-limit page. */
    private static boolean isBlockedUrl(WebDriver d) {
        try {
            String url = d.getCurrentUrl();
            if (url == null) return false;
            return url.toLowerCase().contains("ratelimited")
                    || url.toLowerCase().contains("internalcaptcha")
                    || url.toLowerCase().contains("captchasubmit");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns true if the page is showing any kind of captcha challenge —
     * either a Cloudflare Turnstile widget or TPS's own internal captcha form.
     * This catches cases where the captcha is rendered inline on the page
     * rather than via a URL redirect to /internalcaptcha.
     */
    private static boolean isCaptchaPage(String src, String url) {
        if (url != null && url.toLowerCase().contains("internalcaptcha")) return true;
        if (src == null) return false;
        return src.contains("cf-turnstile-response")
                || src.toLowerCase().contains("internalcaptcha")
                || src.contains("captchasubmit")
                || src.contains("rrstamp");   // TPS rate-limit stamp present in captcha pages
    }

    private static boolean waitForResultsPage(WebDriver webDriver, String targetUrl) {
        // Brief pause for initial page render (PageLoadStrategy.NONE returns before DOM is ready)
        try { Thread.sleep(1500); } catch (Exception ignored) {}

        // Check immediately for known block states before starting the wait loop
        String currentUrl = webDriver.getCurrentUrl();
        String immediateHtml = pageHtml(webDriver);
        // Check URL first — /ratelimited is a hard block distinct from captcha
        if (isBlockedUrl(webDriver) || isAccessRestricted(immediateHtml)) {
            if (isCaptchaPage(immediateHtml, currentUrl)) {
                return waitForManualCaptcha(webDriver);
            }
            return waitForAccessRestriction(webDriver, targetUrl);
        }
        if (isCaptchaPage(immediateHtml, currentUrl)) {
            return waitForManualCaptcha(webDriver);
        }

        try {
            WebDriverWait wait = new WebDriverWait(webDriver, Duration.ofSeconds(45));
            wait.until((ExpectedCondition<Boolean>) d -> {
                String url = d.getCurrentUrl();
                String src = pageHtml(d);
                if (src == null) return false;
                // Break out immediately on any blocking state
                if (url != null && url.toLowerCase().contains("ratelimited")) return true;
                if (isCaptchaPage(src, url)) return true;
                if (isAccessRestricted(src)) return true;
                return src.contains("data-detail-link");
            });
        } catch (Exception ignored) {}

        // Give the page a moment to finish rendering before final checks.
        try { Thread.sleep(2000); } catch (Exception ignored) {}

        String finalUrl = webDriver.getCurrentUrl();
        String src = pageHtml(webDriver);

        if (finalUrl != null && finalUrl.toLowerCase().contains("ratelimited")) {
            return waitForAccessRestriction(webDriver, targetUrl);
        }
        if (isCaptchaPage(src, finalUrl)) {
            return waitForManualCaptcha(webDriver);
        }
        if (isAccessRestricted(src)) {
            return waitForAccessRestriction(webDriver, targetUrl);
        }
        if (src != null && src.contains("data-detail-link")) return true;
        if (src != null && src.contains("No results found")) {
            System.out.println("  TPS returned no results for this search.");
            return false;
        }

        // Log a snippet of the actual page so we can diagnose unknown states
        String snippetText = src != null
                ? src.replaceAll("<[^>]+>", " ").replaceAll("\s+", " ").trim()
                : "(null)";
        String snippet = snippetText.length() > 300 ? snippetText.substring(0, 300) : snippetText;
        System.out.println("  Page did not reach expected state.");
        System.out.println("  Current URL: " + webDriver.getCurrentUrl());
        System.out.println("  Page text preview: " + snippet);
        return false;
    }

    /**
     * TPS has rate-limited or restricted this IP.
     *
     * Strategy:
     *   1. Wait up to 15 minutes, polling every 10 seconds, for the block page to clear.
     *   2. Once the block page is gone, re-navigate to the original targetUrl.
     *   3. Wait up to 45 seconds for results to appear at that URL.
     *
     * The targetUrl parameter is the page we were trying to load when blocked.
     * Pass null to skip re-navigation (e.g. when called from detail page handler).
     */
    private static boolean waitForAccessRestriction(WebDriver webDriver, String targetUrl) {
        System.out.println();
        System.out.println("  *** ACCESS TEMPORARILY RESTRICTED / RATE LIMITED ***");
        System.out.println("  TruePeopleSearch has blocked this session (IP rate-limited or flagged).");
        System.out.println("  Waiting up to 15 minutes for the restriction to lift...");
        System.out.println("  The program will re-navigate and resume automatically.");
        System.out.println();

        // Phase 1: Wait for the block page itself to go away (up to 15 minutes).
        // We poll every 10 seconds rather than using WebDriverWait's 500ms polling
        // to avoid hammering TPS while blocked — that makes bans last longer.
        boolean blockCleared = false;
        long deadline = System.currentTimeMillis() + (15 * 60 * 1000L);
        while (System.currentTimeMillis() < deadline) {
            try { Thread.sleep(10_000); } catch (Exception ignored) {}
            String src = pageHtml(webDriver);
            String url = webDriver.getCurrentUrl();
            if (src == null) continue;
            // Still blocked?
            if (isAccessRestricted(src)) continue;
            if (url != null && (url.toLowerCase().contains("ratelimited")
                    || url.toLowerCase().contains("internalcaptcha"))) continue;
            // Block page is gone
            blockCleared = true;
            System.out.println("  Block page cleared — re-navigating...");
            break;
        }

        if (!blockCleared) {
            System.out.println("  Block did not clear within 15 minutes. Skipping this row.");
            return false;
        }

        // Phase 2: Re-navigate to the target URL (the page we were originally trying to load).
        if (targetUrl != null) {
            try {
                // Extra pause before re-navigating — give TPS time to fully lift the block
                Thread.sleep(5000);
                webDriver.get(targetUrl);
                System.out.println("  Re-navigated to: " + targetUrl);

                // Wait up to 45 s for results to appear
                WebDriverWait resumeWait = new WebDriverWait(webDriver, Duration.ofSeconds(45));
                resumeWait.until((ExpectedCondition<Boolean>) d -> {
                    String src = pageHtml(d);
                    String url = d.getCurrentUrl();
                    if (src == null) return false;
                    if (isAccessRestricted(src)) return true;   // re-blocked — break out
                    if (isCaptchaPage(src, url)) return true;   // captcha — break out
                    return src.contains("data-detail-link") || src.contains("card-summary")
                            || src.contains("card-block");
                });

                String postSrc = pageHtml(webDriver);
                String postUrl = webDriver.getCurrentUrl();
                if (isAccessRestricted(postSrc) || (postUrl != null && postUrl.contains("ratelimited"))) {
                    System.out.println("  Re-blocked immediately after restriction cleared. Skipping row.");
                    return false;
                }
                if (isCaptchaPage(postSrc, postUrl)) {
                    System.out.println("  Captcha appeared after restriction cleared.");
                    return waitForManualCaptcha(webDriver);
                }
                System.out.println("  Access restored — resuming.");
                return true;
            } catch (Exception ex) {
                System.out.println("  Re-navigation failed: " + ex.getMessage());
                return false;
            }
        }

        System.out.println("  Access restored — resuming.");
        return true;
    }

    private static boolean waitForManualCaptcha(WebDriver webDriver) {
        System.out.println();
        System.out.println("  *** CAPTCHA REQUIRED ***");
        System.out.println("  TruePeopleSearch has shown a captcha in the browser window.");
        System.out.println("  Please solve it manually. The program will resume automatically.");
        System.out.println("  Waiting up to 3 minutes...");
        System.out.println();

        try {
            WebDriverWait longWait = new WebDriverWait(webDriver, Duration.ofMinutes(3));
            longWait.until((ExpectedCondition<Boolean>) d -> {
                String url = d.getCurrentUrl();
                String src = pageHtml(d);
                if (src == null) return false;
                // Break out on access restriction
                if (isAccessRestricted(src)) return true;
                // Still on captcha — keep waiting
                if (isCaptchaPage(src, url)) return false;
                // Captcha is gone — success
                return true;
            });
            // Brief pause to let the post-captcha page finish rendering
            try { Thread.sleep(2000); } catch (Exception ignored) {}
            String postSrc = pageHtml(webDriver);
            if (isAccessRestricted(postSrc)) {
                System.out.println("  Access restricted after captcha.");
                // No target URL available here — caller will handle re-navigation
                return waitForAccessRestriction(webDriver, null);
            }
            // Extra pause after captcha solve — TPS is already suspicious of this session.
            // A brief cooldown reduces the chance of hitting rate limiting immediately.
            System.out.println("  Captcha solved — pausing 15 seconds before resuming...");
            try { Thread.sleep(15_000); } catch (Exception ignored) {}
            return true;
        } catch (Exception ex) {
            System.out.println("  Captcha was not solved within 3 minutes. Skipping this row.");
            return false;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String pageHtml(WebDriver d) {
        try {
            return (String) ((JavascriptExecutor) d)
                    .executeScript("return document.documentElement.outerHTML;");
        } catch (Exception e) {
            return null;
        }
    }

    private static String pctEncode(String value) {
        if (value == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : value.toCharArray()) {
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c);
            } else if (c == ' ') {
                sb.append('+');
            } else {
                try {
                    byte[] bytes = String.valueOf(c).getBytes("UTF-8");
                    for (byte b : bytes) sb.append(String.format("%%%02X", b & 0xFF));
                } catch (Exception e) { /* skip */ }
            }
        }
        return sb.toString();
    }
}