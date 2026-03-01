package com.landman;

import java.io.IOException;
import java.util.Scanner;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

/**
 * Simple main program that starts a clean Chrome instance via remote debugging,
 * attaches Selenium to it, calls the scraper, and prints the discovered results.
 *
 * This approach launches Chrome as a normal user process (no Selenium automation
 * flags) and then connects to it via the Chrome DevTools Protocol debugging port.
 * Cloudflare Turnstile sees a regular browser, not an automated one.
 *
 * Usage:
 *   java -cp "&lt;classpath&gt;" com.landman.Main "John Doe" "City, ST"
 *
 * If no command-line arguments are provided, the program will prompt for name and address.
 * TODO: Add ability to import a list of name/address pairs from an Excel or CSV file.
 */
public class Main {

    private static final int DEBUG_PORT = 9222;

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

        // ── Step 1: Launch Chrome as a normal process with remote debugging ──
        // This creates an ordinary Chrome window with ZERO Selenium/automation
        // fingerprints.  We use a dedicated user-data-dir so it doesn't collide
        // with any Chrome instance you already have open.
        Process chromeProcess = null;
        WebDriver driver = null;
        try {
            String chromePath = findChromePath();
            String userDataDir = System.getProperty("java.io.tmpdir") + "landman_chrome_profile";

            System.out.println("Launching Chrome with remote debugging on port " + DEBUG_PORT + " ...");
            chromeProcess = new ProcessBuilder(
                    chromePath,
                    "--remote-debugging-port=" + DEBUG_PORT,
                    "--user-data-dir=" + userDataDir,
                    "--no-first-run",
                    "--no-default-browser-check",
                    "--start-maximized"
            ).start();

            // Give Chrome a moment to start and open the debugging port
            Thread.sleep(3000);

            // ── Step 2: Attach Selenium to the running Chrome via debuggerAddress ──
            ChromeOptions options = new ChromeOptions();
            options.setExperimentalOption("debuggerAddress", "localhost:" + DEBUG_PORT);

            driver = new ChromeDriver(options);

            System.out.println("Connected to Chrome. Running search...");
            TruePeopleSearchScraper.PersonContact contact =
                    TruePeopleSearchScraper.search(driver, name, address);
            System.out.println("Search results for: " + name + " | " + address);
            System.out.println(contact);
        } catch (Throwable t) {
            System.err.println("Error while running scraper: " + t.getMessage());
            t.printStackTrace(System.err);
        } finally {
            // Disconnect Selenium (does NOT close Chrome since we didn't launch it via Selenium)
            if (driver != null) {
                try { driver.quit(); } catch (Exception e) { /* ignore */ }
            }
            // Kill the Chrome process we launched
            if (chromeProcess != null) {
                chromeProcess.destroyForcibly();
            }
        }
    }

    /**
     * Locate the Chrome executable on this system.
     * Checks common Windows install paths; extend as needed for macOS / Linux.
     */
    private static String findChromePath() {
        String[] candidates = {
                System.getenv("ProgramFiles") + "\\Google\\Chrome\\Application\\chrome.exe",
                System.getenv("ProgramFiles(x86)") + "\\Google\\Chrome\\Application\\chrome.exe",
                System.getenv("LOCALAPPDATA") + "\\Google\\Chrome\\Application\\chrome.exe",
        };
        for (String path : candidates) {
            if (path != null && new java.io.File(path).exists()) {
                return path;
            }
        }
        // Fallback: assume it's on the PATH
        return "chrome";
    }
}
