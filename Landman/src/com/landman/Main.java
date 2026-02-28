package com.landman;

import java.util.Scanner;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

/**
 * Simple main program that starts WebDriver, calls the scraper, and prints
 * the discovered phone numbers and emails.
 *
 * Usage:
 *   java -cp "<classpath-with-selenium-and-drivers>" com.landman.Main "John Doe" "City, ST"
 *
 * If no command-line arguments are provided, the program will prompt for name and address.
 * TODO: Add abiltiy to import a list of name/address pairs from an Excel or CSV file.
 */
public class Main {
    public static void main(String[] args) {
        String name;
        String address;
        if (args.length >= 2) {
            name = args[0];
            address = args[1];
        } else {
            Scanner s = new Scanner(System.in);
            System.out.print("Enter full name to search: ");
            name = s.nextLine().trim();
            System.out.print("Enter city/state/zip (or address): ");
            address = s.nextLine().trim();
            s.close();
        }

        if (name.isEmpty() || address.isEmpty()) {
            System.err.println("Name and address are required.");
            System.exit(2);
        }

        // IMPORTANT: You must have chromedriver on your PATH or set webdriver.chrome.driver system property.
        ChromeOptions options = new ChromeOptions();
        // Try to run headless by default (remove if you want to see the browser)
        try {
            options.addArguments("--headless=new");
        } catch (IllegalArgumentException ignore) {
            // older ChromeOptions may not support 'new' headless; ignore
        }

        WebDriver driver = null;
        try {
            driver = new ChromeDriver(options);
            TruePeopleSearchScraper.PersonContact contact = TruePeopleSearchScraper.search(driver, name, address);
            System.out.println("Search results for: " + name + " | " + address);
            System.out.println(contact.toString());
        } catch (Throwable t) {
            System.err.println("Error while running scraper: " + t.getMessage());
            t.printStackTrace(System.err);
        } finally {
            if (driver != null) {
                try { driver.quit(); } catch (Exception e) { /* ignore */ }
            }
        }
    }
}
