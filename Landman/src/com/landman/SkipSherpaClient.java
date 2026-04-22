package com.landman;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.*;
import java.util.regex.*;

/**
 * Client for the Skip Sherpa Person and Business Lookup APIs.
 *
 * Person  endpoint: PUT https://skipsherpa.com/api/beta6/person
 * Business endpoint: PUT https://skipsherpa.com/api/beta6/business
 * Auth: Header  API-Key: <your-key>
 *
 * Automatically detects whether a name is a person or a business and
 * routes to the appropriate endpoint.
 */
public class SkipSherpaClient {

    // ── Configuration ─────────────────────────────────────────────────────────

    private static final String PERSON_API_URL   = "https://skipsherpa.com/api/beta6/person";
    private static final String BUSINESS_API_URL = "https://skipsherpa.com/api/beta6/business";
    private static final String API_KEY = "107a8ea6-b4ab-4968-8ec0-8bedefa4a6e3";

    // Email blocklist — skip generic/placeholder addresses
    private static final Set<String> EMAIL_BLOCKLIST = new HashSet<>(Arrays.asList(
            "support@skipsherpa.com", "noreply@skipsherpa.com"
    ));

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    // ── Public API ─────────────────────────────────────────────────────────────

    public static class Relative {
        public String name    = "";
        public String address = "";
        public final List<String> phones = new ArrayList<>();
        public final List<String> emails = new ArrayList<>();
    }

    public static class PersonContact {
        public final List<String> phones   = new ArrayList<>();
        public final List<String> emails   = new ArrayList<>();
        public boolean deceased            = false;
        /** True if the Skip Sherpa API returned a non-200 status (404 = not in database, 5xx = server error). */
        public boolean notFound            = false;
        public final List<Relative> relatives = new ArrayList<>();
        /** Names of associated persons returned by a business lookup (officers, owners, etc.) */
        public final List<String> associatedPersons = new ArrayList<>();
    }

    /** Thrown when the Skip Sherpa API returns HTTP 429 (quota exhausted). */
    @SuppressWarnings("serial")
	public static class RateLimitException extends RuntimeException {
        public RateLimitException(String msg) { super(msg); }
    }

    /**
     * Look up a person by name and address using the Skip Sherpa API.
     *
     * @param name  Full name (e.g. "Alan Clint Howenstine")
     * @param street Street address (may be empty)
     * @param city  City name
     * @param state Two-letter state code
     * @param zip   ZIP code (may be empty — omitted from request if blank)
     * @return PersonContact with deduplicated phones and emails
     */
    public static PersonContact search(String name, String street, String city, String state,
                                       String zip)
            throws IOException, InterruptedException {

        // Split name into first/last
        String[] nameParts = splitName(name);
        String firstName = nameParts[0];
        String lastName  = nameParts[1];

        // Build request JSON manually — no external JSON library needed
        String body = buildRequestJson(firstName, lastName, street, city, state, zip);

        System.out.println("  [SkipSherpa] PUT " + PERSON_API_URL);
        System.out.println("  [SkipSherpa] Lookup: " + firstName + " " + lastName
                + " | " + city + ", " + state);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(PERSON_API_URL))
                .timeout(Duration.ofSeconds(60))
                .header("API-Key", API_KEY)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .method("PUT", BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = HTTP.send(request, BodyHandlers.ofString());

        System.out.println("  [SkipSherpa] HTTP " + response.statusCode());

        if (response.statusCode() == 429) {
            System.out.println("  [SkipSherpa] Rate limit exceeded: " + response.body());
            throw new RateLimitException("Skip Sherpa lookup quota exhausted (HTTP 429).");
        }

        if (response.statusCode() != 200) {
            System.out.println("  [SkipSherpa] Error response: " + response.body());
            PersonContact empty = new PersonContact();
            empty.notFound = true;
            return empty;
        }

        return parseResponse(response.body());
    }

    /**
     * Returns true if the name is a joint tenancy entry (person, not business).
     * Examples: "Tom & Susan Diel, JT"  /  "John and Mary Smith, JTWROS"
     */
    public static boolean isJointTenancy(String name) {
        if (name == null) return false;
        String upper = name.toUpperCase().trim();
        return upper.matches(".*,\\s*(JT|JTWROS|JT/WROS|JOINT\\s+TENANTS?)\\s*$");
    }

    /**
     * For a joint tenancy name like "Tom & Susan Diel, JT",
     * extract the first person's name: "Tom Diel".
     */
    public static String extractFirstPersonFromJT(String name) {
        if (name == null) return name;
        // Strip the JT suffix
        String stripped = name.replaceAll("(?i),?\\s*(JTWROS|JT/WROS|JOINT\\s+TENANTS?|JT)\\s*$", "").trim();
        stripped = stripped.replaceAll(",\\s*$", "").trim();

        // "Tom & Susan Diel"  or  "John and Mary Smith"  → take first forename + shared last name
        java.util.regex.Matcher m = Pattern.compile(
                "(?i)^(.+?)\\s+(?:&|and)\\s+(?:.+?\\s+)?(\\S+)$").matcher(stripped);
        if (m.matches()) {
            return m.group(1).trim() + " " + m.group(2).trim();
        }
        return stripped;
    }

    /**
     * Determine whether a name refers to a business rather than a person.
     * Checks for common business suffixes and keywords.
     */
    public static boolean isBusiness(String name) {
        if (name == null || name.isBlank()) return false;

        // Joint tenancy entries look like businesses due to " & " but are persons
        if (isJointTenancy(name)) return false;

        String upper = name.toUpperCase().trim();

        // Names starting with "The " are almost always companies (e.g. "The Allar Company")
        if (upper.startsWith("THE ")) return true;

        // Common legal entity suffixes
        String[] businessIndicators = {
            " LLC", " INC", " INC.", " LTD", " LTD.", " CO.", " CORP", " CORP.",
            " LP", " L.P.", " LLP", " L.L.C.", " L.L.P.", " P.C.", " P.A.",
            // Common business type words
            " COMPANY", " COMPANIES",
            " PROPERTIES", " PROPERTY", " RESOURCES", " ENERGY", " EXPLORATION",
            " PARTNERS", " PARTNERSHIP", " ROYALT", " MINERALS", " PETROLEUM",
            " OIL", " GAS", " HOLDINGS", " ENTERPRISES", " VENTURES", " TRUST",
            " MANAGEMENT", " INVESTMENTS", " INVESTMENT", " CAPITAL", " GROUP",
            " SERVICES", " SOLUTIONS", " CONSULTING", " ASSOCIATES", " FOUNDATION",
            " FUND", " REALTY", " REAL ESTATE", " INDUSTRIES", " INDUSTRY",
            " COMPANY", " CORPORATION",
            // Patterns that indicate business names
            " & ", "O&G", "O & G"
        };

        for (String indicator : businessIndicators) {
            if (upper.contains(indicator)) return true;
        }

        // Ends with common abbreviations even without a space (e.g. "Ivco Inc.")
        if (upper.matches(".*(\\bINC\\.?|\\bLLC\\.?|\\bLTD\\.?|\\bCORP\\.?|\\bCO\\.?)\\s*$")) {
            return true;
        }

        return false;
    }

    /**
     * For trust/estate/heirs names, attempt to extract a real person's name
     * that can be searched via the person endpoint instead.
     *
     * Returns the extracted person name, or null if none can be found.
     *
     * Handles patterns like:
     *   "Estate of Bobby J. Darnell c/o Jeffrey Darnell"  → "Jeffrey Darnell"
     *   "Known and Unknown Heirs... c/o Larry M. Durham"  → "Larry M. Durham"
     *   "Frank Bannister Revocable Trust dated 7/7/95"    → "Frank Bannister"
     *   "Richard Finney, Trustee of the Richard Finney..."→ "Richard Finney"
     *   "Daniel W. Whitten GST Exemption Residuary Trust" → "Daniel W. Whitten"
     *   "Louis H. Witwer III, as Trustee of the..."       → "Louis H. Witwer"
     *   "Ronald C. Boden Family Trust c/o Jonathan Boden" → "Ronald C. Boden"
     *   "Sheryl Ann Dungan's Children's Trust"             → "Sheryl Ann Dungan"
     */
    public static String extractPersonFromTrust(String name) {
        if (name == null || name.isBlank()) return null;
        // Normalize all whitespace variants (newlines, tabs, non-breaking spaces)
        String n = name.replaceAll("[\\r\\n\\t\\u00A0]+", " ").trim();

        // 1. "Known and Unknown Heirs..." → search the c/o contact (the living person to reach)
        if (n.matches("(?si).*Known and Unknown.*")) {
            java.util.regex.Matcher m = Pattern.compile("(?i)c/o\\s+(.+?)\\s*$").matcher(n);
            if (m.find()) return m.group(1).trim();
            return null; // no c/o — can't identify a real contact
        }

        // 2. "Estate of [Person] c/o [Contact]" → search the contact
        java.util.regex.Matcher m2 = Pattern.compile("(?i)Estate\\s+of\\s+.+?\\s+c/o\\s+(.+)$").matcher(n);
        if (m2.find()) return m2.group(1).trim();

        // 3. "Estate of [Person]" (no c/o) → search the deceased's name
        java.util.regex.Matcher m3 = Pattern.compile("(?i)Estate\\s+of\\s+(.+)$").matcher(n);
        if (m3.find()) return m3.group(1).trim();

        // 4. "Trust Name (Person Name, Trustee)" — trustee in parentheses
        //    e.g. "Cornerstone Family Trust (John Lawrence Thoma, Trustee)"
        //    e.g. "Lanita C. Williamson Family Living Trust (Lanita C. Williamson, Trustee)"
        //    Use DOTALL so it matches even if there's unusual whitespace
        java.util.regex.Matcher mParen = Pattern.compile(
                "\\(\\s*(.+?)\\s*,\\s*[Tt]rustees?\\s*\\)").matcher(n);
        if (mParen.find()) {
            String person = mParen.group(1).trim();
            person = person.replaceAll("(?i),?\\s+(jr\\.?|sr\\.?|ii|iii|iv|v)\\s*$", "").trim();
            if (person.split("\\s+").length >= 2
                    && !person.matches("(?i).*(\\bBank\\b|\\bCompany\\b|\\bCorp\\b|\\bInc\\b|\\bLLC\\b).*")) {
                return person;
            }
        }

        // 5. "[Name], [as] Trustee[s] of ..." → person before "Trustee"
        java.util.regex.Matcher m4 = Pattern.compile("^(.+?),?\\s+(?:as\\s+)?[Tt]rustees?\\s+of\\b").matcher(n);
        if (m4.find()) {
            String person = m4.group(1).trim();
            // Joint names: "Gary Lowe and Tarilyn Lowe, Trustees..." → take first person
            person = person.split("(?i)\\s+and\\s+")[0].trim();
            person = person.replaceAll("(?i),?\\s+(jr\\.?|sr\\.?|ii|iii|iv|v)\\s*$", "").trim();
            // Skip if it looks like a bank or company
            if (!person.matches("(?i).*(\\bBank\\b|\\bCompany\\b|\\bCorp\\b|\\bInc\\b|\\bLLC\\b).*")
                    && person.split("\\s+").length >= 2) {
                return person;
            }
            return null;
        }

        // 6. "[Name] Family/Revocable/GST/Exemption/Residuary Trust ..."
        //    Handles: "Frank Bannister Revocable Trust"
        //             "Gary Charles & Tarilyn Renee Lowe Revocable Trust"
        //             "Daniel W. Whitten GST Exemption Residuary Trust"
        //    Also handles intervening words: "Family Living Trust", "Family Revocable Trust"
        java.util.regex.Matcher m5 = Pattern.compile(
                "^(.+?)\\s+(?:(?:Family|Living|Revocable|Irrevocable|Testamentary|Inter\\s*Vivos|GST|Exemption|Residuary)\\s+)+Trust\\b",
                Pattern.CASE_INSENSITIVE).matcher(n);
        if (m5.find()) {
            String person = m5.group(1).replaceAll("(?i),?\\s*c/o.*$", "").trim();
            person = person.replaceAll("(?i),?\\s+(jr\\.?|sr\\.?|ii|iii|iv|v)\\s*$", "").trim();
            person = stripPossessiveSuffix(person);
            if (person.split("\\s+").length >= 2
                    && !looksLikeBusiness(person)) {
                return person;
            }
        }

        // 7. "[Name] Trust" (simple, e.g. "James R. Biddick Trust")
        java.util.regex.Matcher m6 = Pattern.compile("^(.+?)\\s+Trust\\b", Pattern.CASE_INSENSITIVE).matcher(n);
        if (m6.find()) {
            String person = m6.group(1).replaceAll("(?i),?\\s*(dated|c/o).*$", "").trim();
            // Strip trailing articles: "Mizel Resources, a" → "Mizel Resources"
            person = person.replaceAll("(?i),?\\s+(?:a|an|the)\\s*$", "").trim();
            person = person.replaceAll("(?i),?\\s+(jr\\.?|sr\\.?|ii|iii|iv|v)\\s*$", "").trim();
            person = stripPossessiveSuffix(person);
            // Must look like a person name (2+ words, no company words)
            if (person.split("\\s+").length >= 2
                    && !looksLikeBusiness(person)
                    && person.matches(".*[A-Z][a-z].*[A-Z][a-z].*")) {  // at least two capitalized words
                return person;
            }
        }

        return null;
    }

    /**
     * Strips trailing possessive-beneficiary suffixes like "'s Children's",
     * "'s Grandchildren's", "'s Descendants'", "'s Issue", etc.
     * Preserves real apostrophe names like "O'Brien" because the pattern
     * requires the specific beneficiary keywords after the "'s".
     */
    private static String stripPossessiveSuffix(String s) {
        if (s == null) return null;
        Pattern p = Pattern.compile(
                "\\s*'s\\s+(?:Children|Grandchildren|Descendants|Issue|Heirs|Beneficiaries)(?:'s?)?\\s*$",
                Pattern.CASE_INSENSITIVE);
        String prev;
        do {
            prev = s;
            s = p.matcher(s).replaceAll("").trim();
        } while (!s.equals(prev));
        return s;
    }

    /**
     * Returns true if the candidate name contains words that indicate it is
     * a business or entity name rather than a person name. Used by
     * {@link #extractPersonFromTrust} rules 6 and 7 to reject false
     * positives like "Mizel Resources" or "Cornerstone Foundation".
     */
    private static boolean looksLikeBusiness(String name) {
        if (name == null) return false;
        return name.matches("(?i).*\\b("
                + "Bank|Company|Companies|Corp|Corporation|Inc|LLC|L\\.L\\.C|LP|L\\.P\\."
                + "|LLP|LTD|Limited|Co\\."
                + "|Properties|Property|Resources|Energy|Exploration"
                + "|Partners|Partnership|Royalt|Minerals|Petroleum"
                + "|Oil|Gas|Holdings|Enterprises|Ventures"
                + "|Management|Investments?|Capital|Group"
                + "|Services|Solutions|Consulting|Associates|Foundation"
                + "|Fund|Realty|Industries|Industry"
                + "|Cornerstone"
                + ")\\b.*");
    }


    public static PersonContact searchBusiness(String businessName, String street,
                                                String city, String state, String zip)
            throws IOException, InterruptedException {

        String body = buildBusinessRequestJson(businessName, street, city, state, zip);

        System.out.println("  [SkipSherpa] PUT " + BUSINESS_API_URL);
        System.out.println("  [SkipSherpa] Business lookup: "
                + businessName.replaceAll("[\\r\\n]+", " ")
                + " | " + city + ", " + state);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BUSINESS_API_URL))
                .timeout(Duration.ofSeconds(60))
                .header("API-Key", API_KEY)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .method("PUT", BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = HTTP.send(request, BodyHandlers.ofString());

        System.out.println("  [SkipSherpa] HTTP " + response.statusCode());

        if (response.statusCode() == 429) {
            System.out.println("  [SkipSherpa] Rate limit exceeded: " + response.body());
            throw new RateLimitException("Skip Sherpa lookup quota exhausted (HTTP 429).");
        }

        if (response.statusCode() != 200) {
            System.out.println("  [SkipSherpa] Error response: " + response.body());
            PersonContact empty = new PersonContact();
            empty.notFound = true;
            return empty;
        }

        return parseBusinessResponse(response.body());
    }

    /**
     * Validate the API key by sending a minimal test request.
     * Returns true if the key is accepted (any response except 401/403).
     * A 400 means the key was accepted but the test data had a validation issue — still valid.
     */
    public static boolean validateApiKey() {
        try {
            String body = buildRequestJson("John", "Smith", "123 Main St", "Oklahoma City", "OK", "");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(PERSON_API_URL))
                    .timeout(Duration.ofSeconds(30))
                    .header("API-Key", API_KEY)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .method("PUT", BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = HTTP.send(request, BodyHandlers.ofString());
            int status = response.statusCode();
            System.out.println("  [SkipSherpa] Validation HTTP status: " + status);
            if (status == 401 || status == 403) {
                System.out.println("  [SkipSherpa] Response: " + response.body());
            }
            // 401/403 = bad or inactive key. Anything else = key accepted.
            return status != 401 && status != 403;
        } catch (java.net.http.HttpTimeoutException e) {
            // Timeout — could be a slow connection or firewall. Assume key is valid
            // and let the first real search attempt determine if there's a problem.
            System.out.println("  [SkipSherpa] Validation timed out — assuming key is valid, will retry on first search.");
            return true;
        } catch (Exception e) {
            System.out.println("  [SkipSherpa] Validation error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            System.out.println("  [SkipSherpa] Network issue detected — assuming key is valid, will retry on first search.");
            return true;
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    /**
     * Split a full name into [firstName, lastName].
     * Handles suffixes (Jr., Sr., III etc.) and multi-word last names.
     */
    private static String[] splitName(String fullName) {
        if (fullName == null || fullName.isBlank()) return new String[]{"", ""};

        // Normalize newlines to spaces (multiline cells like "Name\nc/o Someone")
        String cleaned = fullName.replaceAll("[\\r\\n]+", " ").trim();

        // Strip multiline c/o suffix — take only the first line's name
        // e.g. "Vivian S. Payne\nc/o Matthew Payne" → "Vivian S. Payne"
        if (cleaned.contains("c/o") || cleaned.contains("C/O")) {
            cleaned = cleaned.replaceAll("(?i),?\\s*c/o\\s+.*$", "").trim();
        }

        // Strip a/k/a, n/k/a, f/k/a aliases — keep only the first name
        // e.g. "Brian Lowe a/k/a Brian J. Lowe" → "Brian Lowe"
        cleaned = cleaned.replaceAll("(?i)\\s+(a/k/a|n/k/a|f/k/a|aka)\\b.*$", "").trim();

        // Strip "and [Second Person]" — for joint names, search just the first person
        // e.g. "William Carl Bachle and Sandra Gayle Bachle" → "William Carl Bachle"
        cleaned = cleaned.replaceAll("(?i)\\s+and\\s+.*$", "").trim();

        // Strip common suffixes
        cleaned = cleaned.replaceAll("(?i),?\\s+(jr\\.?|sr\\.?|ii|iii|iv|v|esq\\.?)\\s*$", "")
                         .replaceAll(",\\s*JT\\s*$", "")
                         .trim();

        String[] parts = cleaned.split("\\s+");
        if (parts.length == 1) return new String[]{parts[0], ""};
        if (parts.length == 2) return new String[]{parts[0], parts[1]};

        // 3+ parts — detect and skip middle initials/names
        String first = parts[0];
        String second = parts[1];
        boolean secondIsInitial = second.matches("[A-Za-z]\\.?");

        int lastStart;
        if (secondIsInitial) {
            lastStart = 2;
        } else {
            lastStart = (parts.length == 3) ? 2 : 1;
        }

        if (lastStart >= parts.length) lastStart = parts.length - 1;
        String last = String.join(" ", Arrays.copyOfRange(parts, lastStart, parts.length));
        return new String[]{first, last};
    }

    /** Build the business lookup request JSON body. */
    private static String buildBusinessRequestJson(String businessName, String street,
                                                    String city, String state, String zip) {
        // Normalize newlines, strip c/o suffix, then truncate to API's 64-char limit
        String trimmed = businessName.replaceAll("[\\r\\n]+", " ").trim();
        trimmed = trimmed.replaceAll("(?i),?\\s*c/o\\s+.*$", "").trim();
        if (trimmed.length() > 64) trimmed = trimmed.substring(0, 64).trim();

        String name     = jsonEscape(trimmed);
        String addr     = jsonEscape(street);
        String cityEsc  = jsonEscape(city);
        String stateEsc = jsonEscape(state);

        // Include zipcode if available, otherwise null
        String zipcodeField = (zip != null && !zip.isBlank())
                ? "\"zipcode\":\"" + jsonEscape(zip) + "\""
                : "\"zipcode\":null";

        // Only include mailing_address if we have a city and state
        String mailingAddr;
        if (!city.isBlank() || !state.isBlank()) {
            mailingAddr = "{\"street\":\"" + addr + "\","
                        + "\"city\":\"" + cityEsc + "\","
                        + "\"state\":\"" + stateEsc + "\","
                        + zipcodeField + "}";
        } else {
            mailingAddr = "null";
        }

        return "{\"business_lookups\":[{"
             + "\"business_name\":\"" + name + "\","
             + "\"mailing_address\":" + mailingAddr + ","
             + "\"omit_registered_agents\":false"
             + "}]}";
    }

    /** Build the PUT request JSON body. */
    private static String buildRequestJson(String firstName, String lastName,
                                            String street, String city, String state,
                                            String zip) {
        // Escape special characters for JSON
        firstName = jsonEscape(firstName);
        lastName  = jsonEscape(lastName);
        street    = jsonEscape(street);
        city      = jsonEscape(city);
        state     = jsonEscape(state);

        // Include zipcode if available, otherwise null
        String zipcodeField = (zip != null && !zip.isBlank())
                ? "\"zipcode\": \"" + jsonEscape(zip) + "\""
                : "\"zipcode\": null";

        return "{"
            + "\"person_lookups\": [{"
            +   "\"first_name\": \"" + firstName + "\","
            +   "\"last_name\": \""  + lastName  + "\","
            +   "\"mailing_addresses\": [{"
            +     "\"street\": \""   + street    + "\","
            +     "\"city\": \""     + city      + "\","
            +     "\"state\": \""    + state     + "\","
            +     zipcodeField
            +   "}]"
            + "}]}";
    }

    /** Minimal JSON string escaping. */
    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", "");
    }

    /**
     * Parse the API response JSON and extract phone numbers, emails,
     * deceased status, and relatives (when the person is deceased).
     */
    private static PersonContact parseResponse(String json) {
        PersonContact result = new PersonContact();

        // ── Always extract phones and emails normally first ───────────────────
        // This ensures we always have data even if deceased handling is tricky.
        extractPhonesFromBlock(json, result.phones);
        extractEmailsFromBlock(json, result.emails);

        // ── Check deceased on the FIRST matched person only ───────────────────
        // The response can contain many persons[] with the same name — some may
        // be flagged deceased coincidentally. We only care about the first person
        // object returned, which is the best match.
        Matcher firstPersonMatcher = Pattern.compile(
                "\"persons\"\\s*:\\s*\\[\\s*\\{(.*?)\"status_code\"",
                Pattern.DOTALL).matcher(json);
        if (!firstPersonMatcher.find()) return result;

        String firstPersonBlock = firstPersonMatcher.group(1);

        // Find the deceased flag within ONLY the first person's block
        // (before the next top-level "persons" or "status_code" key)
        Matcher deceasedMatcher = Pattern.compile(
                "\"deceased\"\\s*:\\s*(true|false)").matcher(firstPersonBlock);
        boolean firstPersonDeceased = deceasedMatcher.find()
                && "true".equals(deceasedMatcher.group(1));

        if (!firstPersonDeceased) return result;

        result.deceased = true;
        System.out.println("  [Deceased] Person is flagged deceased — extracting relatives.");

        // ── Extract relatives from the first person's block ───────────────────
        int relativesIdx = firstPersonBlock.indexOf("\"relatives\"");
        if (relativesIdx < 0) return result;

        String relativesSection = firstPersonBlock.substring(relativesIdx);
        List<String> relObjs = splitJsonObjects(relativesSection);

        for (String relObj : relObjs) {
            Relative rel = new Relative();

            // Name
            Matcher nm = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(relObj);
            if (nm.find()) rel.name = nm.group(1).trim();

            // Address: delivery_line1 + last_line
            Matcher dl1 = Pattern.compile("\"delivery_line1\"\\s*:\\s*\"([^\"]+)\"").matcher(relObj);
            Matcher dll = Pattern.compile("\"last_line\"\\s*:\\s*\"([^\"]+)\"").matcher(relObj);
            String line1 = dl1.find() ? dl1.group(1).trim() : "";
            String lastLine = dll.find() ? dll.group(1).trim() : "";
            rel.address = (line1 + (lastLine.isEmpty() ? "" : ", " + lastLine)).trim();

            // Phones and emails
            extractPhonesFromBlock(relObj, rel.phones);
            extractEmailsFromBlock(relObj, rel.emails);

            if (!rel.name.isEmpty()
                    && !rel.name.toUpperCase().equals(rel.name)) { // skip ALL-CAPS company names
                result.relatives.add(rel);
                System.out.println("    Relative: " + rel.name
                        + " | " + rel.phones.size() + "p / " + rel.emails.size() + "e");
            }
        }

        return result;
    }

    /**
     * Parse a business API response.
     * Extracts phones and emails (same as normal), plus names of associated
     * persons (officers, owners, registered agents) from associated_persons[].
     *
     * Business response structure:
     *   business_results[] → businesses[]
     *     → phone_numbers[].local_format
     *     → emails[].email_address
     *     → associated_persons[].person.name   ← owner/officer names
     *     → associated_persons[].person.phone_numbers[]
     *     → associated_persons[].person.emails[]
     */
    private static PersonContact parseBusinessResponse(String json) {
        PersonContact result = parseResponse(json); // phones + emails via normal path

        // Extract associated person names from associated_persons[] blocks.
        // Pattern: find each associated_persons array and pull the "name" field
        // from each person object within it. Skip ALL-CAPS entries (companies/agents).
        Matcher apBlock = Pattern.compile(
                "\"associated_persons\"\\s*:\\s*\\[(.*?)\\]",
                Pattern.DOTALL).matcher(json);

        Set<String> seen = new LinkedHashSet<>();
        while (apBlock.find()) {
            String block = apBlock.group(1);
            // Each person object has a "name" field
            Matcher nameMatcher = Pattern.compile(
                    "\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(block);
            while (nameMatcher.find()) {
                String personName = nameMatcher.group(1).trim();
                // Skip ALL-CAPS (company names / registered agents)
                if (personName.equals(personName.toUpperCase())) continue;
                // Skip very short tokens (single letters, punctuation)
                if (personName.length() < 4) continue;
                if (seen.add(personName)) {
                    result.associatedPersons.add(personName);
                }
            }
        }

        if (!result.associatedPersons.isEmpty()) {
            System.out.println("  [SkipSherpa] Associated persons: " + result.associatedPersons);
        }

        return result;
    }

    /** Extract all local_format phone numbers from a JSON fragment into a list. */
    private static void extractPhonesFromBlock(String json, List<String> out) {
        Matcher m = Pattern.compile("\"local_format\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        Set<String> seen = new LinkedHashSet<>();
        // Preserve any already-added phones to avoid duplicates
        out.forEach(seen::add);
        while (m.find()) {
            String normalized = normalizePhone(m.group(1).trim());
            if (normalized != null && seen.add(normalized)) {
                out.add(normalized);
            }
        }
    }

    /** Extract all email_address values from a JSON fragment into a list. */
    private static void extractEmailsFromBlock(String json, List<String> out) {
        Matcher m = Pattern.compile("\"email_address\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        Set<String> seen = new LinkedHashSet<>();
        out.forEach(seen::add);
        while (m.find()) {
            String email = m.group(1).trim().toLowerCase();
            if (!EMAIL_BLOCKLIST.contains(email) && seen.add(email)) {
                out.add(email);
            }
        }
    }

    /**
     * Split a JSON string containing a "relatives": [ ... ] section into
     * individual JSON object strings, one per relative.
     * Uses brace-depth tracking to find object boundaries.
     */
    private static List<String> splitJsonObjects(String json) {
        List<String> objects = new ArrayList<>();
        int depth = 0;
        int start = -1;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    objects.add(json.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return objects;
    }

    /**
     * Normalize a phone string to (XXX) XXX-XXXX format.
     * Returns null if the string doesn't look like a US phone number.
     */
    private static String normalizePhone(String raw) {
        if (raw == null) return null;
        // Strip non-digit characters
        String digits = raw.replaceAll("\\D", "");
        // Strip leading country code 1
        if (digits.length() == 11 && digits.startsWith("1")) {
            digits = digits.substring(1);
        }
        if (digits.length() != 10) return null;
        return "(" + digits.substring(0, 3) + ") "
                   + digits.substring(3, 6) + "-"
                   + digits.substring(6);
    }
}