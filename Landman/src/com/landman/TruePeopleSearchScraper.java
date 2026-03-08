package com.landman;

import org.openqa.selenium.*;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scraper that queries truepeoplesearch.com for a name + city/state and
 * extracts phone numbers and email addresses from the detail pages.
 *
 * Uses Selenium with Firefox and a rotating proxy to bypass Cloudflare.
 */
public class TruePeopleSearchScraper {

    private static final String BASE_LINK = "https://www.truepeoplesearch.com/";
    private static final int MAX_RETRIES = 7;

    // Proxy configuration
    private static final String PROXY_HOST = "usa.rotating.proxyrack.net";
    private static final int PROXY_PORT = 9000;
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
     * @param name  full name to search (e.g. "John Doe")
     * @param city  city name
     * @param state two-letter state abbreviation
     * @return PersonContact with discovered phones and emails
     */
    public static PersonContact search(String name, String city, String state) {
        PersonContact result = new PersonContact();
        List<String> resultLinks = new ArrayList<>();

        // ── Step 1: Search page – fill form, get result links ──────────────
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            WebDriver driver = null;
            try {
                driver = createDriver();
                driver.get(BASE_LINK);

                String title = driver.getTitle();
                if (title.contains("Just a moment...") || title.contains("Attention Required!")) {
                    System.out.println("  Cloudflare challenge detected (attempt " + (attempt + 1) + "), retrying...");
                    continue;
                }

                // Wait a moment for the page to settle
                Thread.sleep(3000);

                // Fill in search form fields
                driver.findElement(By.id("id-d-n")).clear();
                driver.findElement(By.id("id-d-n")).sendKeys(name);

                driver.findElement(By.id("id-d-loc-name")).clear();
                driver.findElement(By.id("id-d-loc-name")).sendKeys(city + "," + state);

                driver.findElement(By.id("btnSubmit-d-n")).click();

                // Wait for results to load
                Thread.sleep(5000);

                // Parse result cards that match the target city/state
                List<WebElement> cards = driver.findElements(
                        By.cssSelector("div.card.card-body.shadow-form.card-summary.pt-3"));

                String cityState = city + ", " + state;
                for (WebElement card : cards) {
                    String text = card.getText();
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
            } finally {
                if (driver != null) {
                    driver.quit();
                }
            }
        }

        // ── Step 2: Visit each detail link and scrape phones/emails ────────
        Set<String> foundPhones = new LinkedHashSet<>();
        Set<String> foundEmails = new LinkedHashSet<>();

        for (String link : resultLinks) {
            for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
                WebDriver driver = null;
                try {
                    driver = createDriver();
                    driver.get(link);

                    String title = driver.getTitle();
                    if (title.contains("Just a moment...") || title.contains("Attention Required!")) {
                        System.out.println("  Cloudflare challenge on detail page (attempt "
                                + (attempt + 1) + "), retrying...");
                        continue;
                    }

                    // Wait for page to settle
                    Thread.sleep(3000);

                    // Get the visible text of the page
                    String pageText = driver.findElement(By.tagName("body")).getText();

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
                } finally {
                    if (driver != null) {
                        driver.quit();
                    }
                }
            }
        }

        result.phones.addAll(foundPhones);
        result.emails.addAll(foundEmails);
        return result;
    }

    /**
     * Create a headless Firefox WebDriver with the rotating proxy.
     */
    private static WebDriver createDriver() {
        FirefoxOptions options = new FirefoxOptions();
        options.addArguments("--headless");

        // Configure proxy via Firefox preferences (supports authenticated proxies)
        options.addPreference("network.proxy.type", 1);
        options.addPreference("network.proxy.ssl", PROXY_HOST);
        options.addPreference("network.proxy.ssl_port", PROXY_PORT);
        options.addPreference("network.proxy.http", PROXY_HOST);
        options.addPreference("network.proxy.http_port", PROXY_PORT);

        // Set proxy authentication via URL-embedded credentials in the SOCKS config,
        // or use an extension. For basic auth proxies with Firefox/Selenium, the
        // simplest approach is to set the credentials via a profile preference:
        options.addPreference("network.proxy.socks_username", PROXY_USERNAME);
        options.addPreference("network.proxy.socks_password", PROXY_PASSWORD);

        return new FirefoxDriver(options);
    }
}