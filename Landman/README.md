Landman - TruePeopleSearch Scraper

This small Java program uses Selenium WebDriver (ChromeDriver) to search
https://www.truepeoplesearch.com for a person by name and address and extract
phone numbers and email addresses found on the result page.

Files added
- src/com/landman/TruePeopleSearchScraper.java  - scraper that queries the site and extracts phones/emails
- src/com/landman/Main.java                   - small CLI program that runs the scraper

Setup (Windows / Eclipse / command line)

1) Install Chrome and ChromeDriver
   - Download ChromeDriver that matches your Chrome version: https://sites.google.com/chromium.org/driver/
   - Unzip chromedriver.exe and place it somewhere on your PATH, or note its path and set the system property
     `-Dwebdriver.chrome.driver=C:\path\to\chromedriver.exe` when running.

2) Add Selenium Java bindings to your project
   - Option A: Maven
     Add the following dependency to your pom.xml:

     <dependency>
       <groupId>org.seleniumhq.selenium</groupId>
       <artifactId>selenium-java</artifactId>
       <version>4.11.0</version>
     </dependency>

   - Option B: Download Selenium standalone JARs
     Download from https://www.selenium.dev/downloads/ and add them to your Eclipse project's build path.

3) Run from command line (example)

   Compile (if using plain javac, include selenium jars on the classpath):

   javac -cp "lib/*" -d bin src\com\landman\*.java

   Run (example using chromedriver on PATH):

   java -cp "bin;lib/*" com.landman.Main "John Doe" "Springfield, IL"

   Or run inside Eclipse: add Selenium jars to Build Path and run `com.landman.Main` with program arguments.

Notes and caveats
- The scraper reads the page source and uses regexes to find phone numbers and emails. If the website layout changes, targeted selectors may be required.
- Respect the website's robots.txt and terms of service. This code is for educational/demonstration purposes.
- If you see compile errors about missing Selenium classes, add the selenium-java dependency (see above).

Next steps
- Add Maven/Gradle build files to manage dependencies automatically.
- Improve result parsing to uniquely match a single person profile when the site returns multiple hits.
