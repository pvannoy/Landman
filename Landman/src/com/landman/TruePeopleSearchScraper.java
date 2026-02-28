package com.landman;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.openqa.selenium.support.ui.ExpectedCondition;
import org.openqa.selenium.support.ui.ExpectedConditions;

/**
 * Lightweight scraper that queries truepeoplesearch.com for a name+address and
 * extracts phone numbers and email addresses found on the result page.
 *
 * Notes:
 * - This is a simple implementation that uses the site's "results" URL with
 *   query parameters. The exact site structure may change; the code is written
 *   to be fault tolerant (it reads the page source and runs regexes).
 * - This class intentionally has no dependency on how the WebDriver is
 *   constructed (the caller should supply a ready WebDriver instance).
 */
public class TruePeopleSearchScraper {

    public static class PersonContact {
        public final List<String> phones = new ArrayList<>();
        public final List<String> emails = new ArrayList<>();
        
        //TODO: These results should be feed to an Excel file or database instead of just printed. Add methods to export to CSV or Excel.
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

    // Simple phone and email regexes. These are intentionally permissive.
    private static final Pattern PHONE_PATTERN = Pattern.compile("(\\(?\\d{3}\\)?[-.\\s]?\\d{3}[-.\\s]?\\d{4})");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /**
     * Search truepeoplesearch.com for the given name and address and extract
     * phone numbers and emails from the first result page.
     *
     * @param driver a Selenium WebDriver (caller is responsible for setup and teardown)
     * @param name full name to search (e.g. "John Doe")
     * @param address city/state/zip or full address (will be URL-encoded)
     * @return PersonContact containing discovered phones and emails (may be empty lists)
     * @throws Exception on unexpected errors (network / encoding / interrupted)
     */
    public static PersonContact search(WebDriver driver, String name, String address) throws Exception {
        PersonContact result = new PersonContact();

        String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8.toString());
        String encodedAddr = URLEncoder.encode(address, StandardCharsets.UTF_8.toString());
        String url = "https://www.truepeoplesearch.com/results?name=" + encodedName + "&citystatezip=" + encodedAddr;

        driver.get(url);

        // Wait briefly for the page to load (presence of body). Caller controls timeouts via driver config.
        try {
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
            wait.until(new ExpectedCondition<Boolean>() {
                @Override
                public Boolean apply(WebDriver d) {
                    return d.getPageSource() != null && d.getPageSource().length() > 0;
                }
            });
        } catch (Exception ex) {
        	 System.out.println("An error occurred: " + ex.getMessage());
        }

        String html = driver.getPageSource();
        if (html == null) html = "";

        // For debugging: print all links on the page to see if working (can be removed in production)
        System.out.println("URL: " + driver.getCurrentUrl());
        System.out.println("Page Source: " + driver.getPageSource());
        
//      String ariaLabelText = "View All Details";
//      String cssSelector = String.format("[aria-label='%s']", ariaLabelText);
//      List<WebElement> allLinks = driver.findElements(By.cssSelector(cssSelector));
        try {
			 WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));
			 WebElement linkElement = wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(".card.card-body.shadow-form.card-summary.pt-3")));
			 
		     List<WebElement> allLinks = linkElement.findElements(By.cssSelector("a"));
		     System.out.println("Found " + allLinks.size() + " links on the page.");
		      for(WebElement link : allLinks) {
		        System.out.println("Link text: " + link.getText() + " | href: " + link.getAttribute("href") + " | Label: " + link.getAttribute("aria-label"));
		      }
		} catch (Exception ex) {
			System.out.println("URL: " + driver.getCurrentUrl());
			System.out.println("An error occurred while waiting for the element: " + ex.getMessage());
		}

        
        // Find phone numbers
        Set<String> phones = new HashSet<>();
        Matcher pm = PHONE_PATTERN.matcher(html);
        while (pm.find()) {
            String phone = pm.group(1).trim();
            phones.add(phone);
        }

        // Find emails
        Set<String> emails = new HashSet<>();
        Matcher em = EMAIL_PATTERN.matcher(html);
        while (em.find()) {
            emails.add(em.group());
        }

        result.phones.addAll(phones);
        result.emails.addAll(emails);

        return result;
    }
}
