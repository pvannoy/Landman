package com.landman;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Proxy;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scraper that queries truepeoplesearch.com for a name + city/state and
 * extracts phone numbers and email addresses from the detail pages.
 *
 * Uses Playwright with Firefox and a rotating proxy to bypass Cloudflare.
 */
public class TruePeopleSearchScraper {

    private static final String BASE_LINK = "https://www.truepeoplesearch.com/";
    private static final int MAX_RETRIES = 7;

    // Proxy configuration
    private static final String PROXY_SERVER = "http://usa.rotating.proxyrack.net:9000";
    private static final String PROXY_USERNAME = "lylleryals11";
    private static final String PROXY_PASSWORD = "5cbb55-0fe3ef-e78508-6f475b-ce994a";

    // Patterns for phone numbers and emails
    private static final Pattern PHONE_PATTERN = Pattern.compile("\\(\\d{3}\\) \\d{3}-\\d{4}");
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "\\b[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}\\b");

    /**
     * Holds the scraped contact data for a search.
     */
    public static class PersonContact {
        public final List<String> phones = new ArrayList<>();
        public final List<String> emails = new ArrayList<>();

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Phones:\n");
            for (String p : phones) sb.append("  ").append(p).append("\n");
            sb.append("Emails:\n");
            for (String e : emails) sb.append("  ").append(e).append("\n");
            return sb.toString();
        }
    }

    // Track whether the proxy is working; if not, skip it on future attempts
    private static boolean proxyFailed = false;

    /**
     * Search TruePeopleSearch for the given name and city/state.
     *
     * @param name  full name to search (e.g. "John Doe")
     * @param city  city name
     * @param state two-letter state abbreviation
     * @return PersonContact with discovered phones and emails
     */
    public static PersonContact search(String name, String city, String state) {
        // Prevent Playwright from re-downloading browsers on every Playwright.create()
        System.setProperty("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");

        PersonContact result = new PersonContact();
        List<String> resultLinks = new ArrayList<>();

        // ── Step 1: Search page – fill form, get result links ──────────────
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try (Playwright playwright = Playwright.create()) {
                Browser browser = launchFirefox(playwright, attempt);
                BrowserContext context = browser.newContext();
                Page page = context.newPage();

                try {
                    page.navigate(BASE_LINK, new Page.NavigateOptions()
                            .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(60000));

                    String title = page.title();
                    if (title.contains("Just a moment...") || title.contains("Attention Required!")) {
                        System.out.println("  Cloudflare challenge detected (attempt " + (attempt + 1) + "), retrying...");
                        browser.close();
                        continue;
                    }

                    // Wait a moment for the page to settle
                    page.waitForTimeout(3000);

                    // Fill in search form fields
                    page.fill("#id-d-n", name);
                    page.fill("#id-d-loc-name", city + "," + state);
                    page.click("#btnSubmit-d-n");

                    // Wait for results to load
                    page.waitForTimeout(5000);

                    // Get page content and parse with Jsoup
                    String html = page.content();
                    Document doc = Jsoup.parse(html);

                    // Find all result cards with data-detail-link attributes
                    Elements cards = doc.select("div.card.card-body.shadow-form.card-summary.pt-3");

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

                    System.out.println("  Was found persons: " + resultLinks.size());
                    browser.close();
                    break; // success

                } catch (Exception ex) {
                    String msg = ex.getMessage();
                    System.out.println("  Error on search attempt " + (attempt + 1) + ": " + msg);
                    if (msg != null && msg.contains("PROXY_CONNECTION_REFUSED")) {
                        proxyFailed = true;
                        System.out.println("  Proxy appears down, switching to direct connection...");
                    }
                    browser.close();
                }
            }
        }

        // ── Step 2: Visit each detail link and scrape phones/emails ────────
        Set<String> foundPhones = new LinkedHashSet<>();
        Set<String> foundEmails = new LinkedHashSet<>();

        for (String link : resultLinks) {
            for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
                try (Playwright playwright = Playwright.create()) {
                    Browser browser = launchFirefox(playwright, attempt);
                    BrowserContext context = browser.newContext();
                    Page page = context.newPage();

                    try {
                        page.navigate(link, new Page.NavigateOptions()
                                .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                                .setTimeout(60000));

                        String title = page.title();
                        if (title.contains("Just a moment...") || title.contains("Attention Required!")) {
                            System.out.println("  Cloudflare challenge on detail page (attempt "
                                    + (attempt + 1) + "), retrying...");
                            browser.close();
                            continue;
                        }

                        // Wait for page to settle
                        page.waitForTimeout(3000);

                        // Get the full HTML and parse with Jsoup
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

                        browser.close();
                        break; // success

                    } catch (Exception ex) {
                        String msg = ex.getMessage();
                        System.out.println("  Error on detail attempt " + (attempt + 1) + ": " + msg);
                        if (msg != null && msg.contains("PROXY_CONNECTION_REFUSED")) {
                            proxyFailed = true;
                            System.out.println("  Proxy appears down, switching to direct connection...");
                        }
                        browser.close();
                    }
                }
            }
        }

        result.phones.addAll(foundPhones);
        result.emails.addAll(foundEmails);
        return result;
    }

    /**
     * Launch Firefox with proxy if available, or without proxy as fallback.
     * First attempt always tries the proxy; if proxy has been flagged as failed
     * we go direct immediately.
     */
    private static Browser launchFirefox(Playwright playwright, int attempt) {
        BrowserType.LaunchOptions opts = new BrowserType.LaunchOptions()
                .setHeadless(true);

        if (!proxyFailed) {
            opts.setProxy(new Proxy(PROXY_SERVER)
                    .setUsername(PROXY_USERNAME)
                    .setPassword(PROXY_PASSWORD));
            System.out.println("  Launching Firefox with proxy (attempt " + (attempt + 1) + ")");
        } else {
            System.out.println("  Launching Firefox without proxy (attempt " + (attempt + 1) + ")");
        }

        return playwright.firefox().launch(opts);
    }
}