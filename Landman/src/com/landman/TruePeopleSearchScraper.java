package com.landman;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Proxy;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scraper that queries truepeoplesearch.com for a name + city/state and
 * extracts phone numbers and email addresses from the detail pages.
 *
 * Uses Playwright with Firefox and a rotating proxy to bypass Cloudflare.
 * Mirrors the Python TruePeopleSearch class logic:
 *   1. Navigate to the homepage
 *   2. Fill in the name and city/state fields, click search
 *   3. Parse result cards with matching city/state
 *   4. Visit each detail link and extract phones/emails
 *   5. Retry up to MAX_RETRIES times on Cloudflare challenges
 */
public class TruePeopleSearchScraper {

    private static final String BASE_LINK = "https://www.truepeoplesearch.com/";
    private static final int MAX_RETRIES = 7;

    // Proxy configuration – same as the Python script
    private static final String PROXY_SERVER = "https://usa.rotating.proxyrack.net:9000";
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

    /**
     * Search TruePeopleSearch for the given name and city/state.
     *
     * @param playwright shared Playwright instance (caller manages lifecycle)
     * @param name       full name to search (e.g. "John Doe")
     * @param city       city name
     * @param state      two-letter state abbreviation
     * @return PersonContact with discovered phones and emails
     */
    public static PersonContact search(Playwright playwright, String name, String city, String state) {
        PersonContact result = new PersonContact();
        List<String> resultLinks = new ArrayList<>();

        // ── Step 1: Search page – fill form, get result links ──────────────
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try (Browser browser = launchBrowser(playwright);
                 BrowserContext context = browser.newContext()) {

                Page page = context.newPage();
                page.navigate(BASE_LINK, new Page.NavigateOptions()
                        .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(0));

                String title = page.title();
                if (title.contains("Just a moment...") || title.contains("Attention Required!")) {
                    System.out.println("  Cloudflare challenge detected (attempt " + (attempt + 1) + "), retrying...");
                    continue;
                }

                // Wait a moment for the page to settle
                page.waitForTimeout(3000);

                // Fill in search form fields (same IDs as the Python script)
                page.fill("#id-d-n", name);
                page.fill("#id-d-loc-name", city + "," + state);
                page.click("#btnSubmit-d-n");

                // Wait for results to load
                page.waitForTimeout(5000);

                // Parse result cards that match the target city/state
                List<ElementHandle> cards = page.querySelectorAll(
                        "div.card.card-body.shadow-form.card-summary.pt-3");

                String cityState = city + ", " + state;
                for (ElementHandle card : cards) {
                    String text = card.innerText();
                    if (text != null && text.contains(cityState)) {
                        String detailLink = card.getAttribute("data-detail-link");
                        if (detailLink != null && !detailLink.isEmpty()) {
                            resultLinks.add(BASE_LINK + detailLink);
                        }
                    }
                }

                System.out.println("  Was found persons: " + resultLinks.size());
                break; // success

            } catch (Exception ex) {
                System.out.println("  Error on search attempt " + (attempt + 1) + ": " + ex.getMessage());
            }
        }

        // ── Step 2: Visit each detail link and scrape phones/emails ────────
        Set<String> foundPhones = new LinkedHashSet<>();
        Set<String> foundEmails = new LinkedHashSet<>();

        for (String link : resultLinks) {
            for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
                try (Browser browser = launchBrowser(playwright);
                     BrowserContext context = browser.newContext()) {

                    Page page = context.newPage();
                    page.navigate(link, new Page.NavigateOptions()
                            .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(0));

                    String title = page.title();
                    if (title.contains("Just a moment...") || title.contains("Attention Required!")) {
                        System.out.println("  Cloudflare challenge on detail page (attempt "
                                + (attempt + 1) + "), retrying...");
                        continue;
                    }

                    // Wait for page to settle
                    page.waitForTimeout(3000);

                    // Get the visible text of the page (like soup.text in Python)
                    String pageText = page.innerText("body");

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

                    break; // success

                } catch (Exception ex) {
                    System.out.println("  Error on detail attempt " + (attempt + 1) + ": " + ex.getMessage());
                }
            }
        }

        result.phones.addAll(foundPhones);
        result.emails.addAll(foundEmails);
        return result;
    }

    /**
     * Launch a headless Firefox browser with the rotating proxy.
     */
    private static Browser launchBrowser(Playwright playwright) {
        return playwright.firefox().launch(new BrowserType.LaunchOptions()
                .setHeadless(true)
                .setProxy(new Proxy(PROXY_SERVER)
                        .setUsername(PROXY_USERNAME)
                        .setPassword(PROXY_PASSWORD)));
    }
}
