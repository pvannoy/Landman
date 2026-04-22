"""
TruePeopleSearch scraper using undetected-chromedriver.
Launched as a subprocess by TruePeopleSearchScraper.java.

Protocol (stdin/stdout, one JSON object per line):
  Input:  {"name": "John Smith", "street": "123 Main St", "city": "Edmond", "state": "OK", "zipcode": "73034"}
  Output: {"phones": ["(405) 123-4567"], "emails": ["john@example.com"], "error": null}
  Quit:   {"quit": true}
"""

import sys
import json
import time
import re
import os

SCRAPER_VERSION = "2.9"  # Auto-restart Chrome on connection errors
print(f"[scraper] version {SCRAPER_VERSION}", file=__import__("sys").stderr, flush=True)

# Suppress noisy logs from selenium/urllib3
os.environ["WDM_LOG"] = "0"
import logging
logging.disable(logging.CRITICAL)

try:
    import undetected_chromedriver as uc
except ImportError as e:
    print(json.dumps({"error": f"Import failed: {e}"}), flush=True)
    sys.exit(1)

# ── Patterns ──────────────────────────────────────────────────────────────────

PHONE_RE = re.compile(r'\(\d{3}\)\s\d{3}-\d{4}')
EMAIL_RE = re.compile(r'[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}')

EMAIL_BLOCKLIST = {
    "jsmith@gmail.com",
    "support@truepeoplesearch.com",
}

EMAIL_NOISE = {".png", ".jpg", ".svg", ".gif"}
EMAIL_NOISE_DOMAINS = {"sentry.io", "example.com", "w3.org", "schema.org"}

# ── Browser setup ─────────────────────────────────────────────────────────────

def make_driver():
    opts = uc.ChromeOptions()
    opts.add_argument("--start-maximized")
    opts.add_argument("--no-first-run")
    opts.add_argument("--no-default-browser-check")
    # undetected_chromedriver patches the binary — no extra stealth flags needed
    driver = uc.Chrome(options=opts, version_main=146)
    driver.set_page_load_timeout(30)   # don't hang forever on driver.get()
    driver.set_script_timeout(30)
    return driver

# ── URL encoding ──────────────────────────────────────────────────────────────

def pct_encode(value):
    """Simple percent-encoder for query parameter values."""
    out = []
    for ch in value:
        if ch.isalnum() or ch in "-_.~":
            out.append(ch)
        elif ch == " ":
            out.append("+")
        else:
            for byte in ch.encode("utf-8"):
                out.append(f"%{byte:02X}")
    return "".join(out)

# ── Detection helpers ─────────────────────────────────────────────────────────

def is_captcha(src, url):
    if url and "internalcaptcha" in url.lower():
        return True
    if not src:
        return False
    return any(s in src for s in [
        "cf-turnstile-response", "internalcaptcha", "captchasubmit",
        "rrstamp", "Verification Required", "Slide right to secure",
        "captcha-delivery.com", "geo.captcha-delivery", "var dd={", "'rt':'c'",
        "Incorrect device time",          # Cloudflare clock-mismatch challenge
        "Automatic submission failed",    # Cloudflare failed auto-solve page
        "device time",                    # broader match for same variant
    ])

def is_restricted(src):
    if not src:
        return False
    if "captcha-delivery.com" in src or "var dd={" in src:
        return False  # DataDome captcha, not a restriction
    # Never treat a page with real TPS content as a restriction
    if "card-summary" in src or "card-block" in src or "data-detail-link" in src:
        return False
    # Use very specific phrases that only appear on the actual block pages,
    # not in footers or boilerplate of normal pages
    return ("Access is temporarily restricted" in src
            or "IP address has been temporarily" in src
            or ("ratelimited" in src.lower() and "data-detail-link" not in src))

def is_ratelimited_url(url):
    if not url:
        return False
    low = url.lower()
    return "ratelimited" in low or "internalcaptcha" in low or "captchasubmit" in low

def page_html(driver):
    try:
        return driver.execute_script("return document.documentElement.outerHTML;")
    except Exception:
        return None

# ── Wait helpers ──────────────────────────────────────────────────────────────

def wait_for_results(driver, url, timeout=60):
    """
    Wait for the results page to load. Returns True if ready, False if timed out.
    Check order: success → captcha → restriction
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        time.sleep(2)
        src = page_html(driver)
        cur = driver.current_url
        if not src:
            continue

        # 1. Success
        if "data-detail-link" in src:
            return True
        # All TPS "no results" messages — skip and move on
        if any(s in src for s in [
            "No results found",
            "We could not find any records",
            "Last name alone is not enough",
            "could not find any records for that search",
            "no records for that search",
        ]):
            return False

        # 2. Captcha (check BEFORE restriction — InternalCaptcha URL also matches
        #    is_ratelimited_url, so we must handle it as captcha first)
        if is_captcha(src, cur) or (cur and "internalcaptcha" in cur.lower()):
            print(json.dumps({"prompt": "CAPTCHA_REQUIRED"}), flush=True)
            captcha_deadline = time.time() + 180
            while time.time() < captcha_deadline:
                time.sleep(1)
                s2 = page_html(driver)
                u2 = driver.current_url
                if not is_captcha(s2, u2) and "internalcaptcha" not in (u2 or "").lower():
                    print(json.dumps({"prompt": "CAPTCHA_SOLVED"}), flush=True)
                    # Short cooldown with keepalive pings
                    for _ in range(5):
                        time.sleep(1)
                        try: driver.current_url
                        except Exception: pass
                    break
            else:
                print(json.dumps({"prompt": "CAPTCHA_TIMEOUT"}), flush=True)
                return False
            continue

        # 3. Access restriction (pure block page, no captcha)
        if is_restricted(src):
            print(json.dumps({"prompt": "ACCESS_RESTRICTED"}), flush=True)
            restrict_deadline = time.time() + 900
            while time.time() < restrict_deadline:
                time.sleep(10)
                src2 = page_html(driver)
                cur2 = driver.current_url
                if not is_restricted(src2) and not is_ratelimited_url(cur2):
                    print(json.dumps({"prompt": "ACCESS_RESTORED"}), flush=True)
                    safe_get(driver, url)
                    time.sleep(5)
                    break
            else:
                return False
            continue

    return False


def wait_for_detail(driver, detail_url, timeout=60):
    """Wait for the detail page to load. Returns True if ready."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        time.sleep(2)
        src = page_html(driver)
        cur = driver.current_url
        if not src:
            continue

        # 1. Success
        has_content = any(s in src for s in ["card-summary", "card-block", "data-link"])
        print(f"[scraper] detail poll: url={cur[-50:]} card-summary={'card-summary' in src} card-block={'card-block' in src} data-link={'data-link' in src} len={len(src)}", file=sys.stderr, flush=True)
        if has_content:
            return True

        # 2. Captcha (before restriction)
        if is_captcha(src, cur) or (cur and "internalcaptcha" in cur.lower()):
            print(json.dumps({"prompt": "CAPTCHA_REQUIRED"}), flush=True)
            captcha_deadline = time.time() + 180
            while time.time() < captcha_deadline:
                time.sleep(1)
                s2 = page_html(driver)
                u2 = driver.current_url
                if is_restricted(s2) or is_ratelimited_url(u2) and "internalcaptcha" not in (u2 or "").lower():
                    print(json.dumps({"prompt": "ACCESS_RESTRICTED"}), flush=True)
                    break
                if not is_captcha(s2, u2) and "internalcaptcha" not in (u2 or "").lower():
                    print(json.dumps({"prompt": "CAPTCHA_SOLVED"}), flush=True)
                    # Short cooldown — keep browser alive with small polls
                    for _ in range(5):
                        time.sleep(1)
                        try: driver.current_url  # keepalive ping
                        except Exception: pass
                    break
            else:
                print(json.dumps({"prompt": "CAPTCHA_TIMEOUT"}), flush=True)
                return False
            print(f"[scraper] captcha resolved, continuing poll loop", file=sys.stderr, flush=True)
            continue

        # 3. Access restriction
        if is_restricted(src) or is_ratelimited_url(cur):
            print(json.dumps({"prompt": "ACCESS_RESTRICTED"}), flush=True)
            restrict_deadline = time.time() + 900
            while time.time() < restrict_deadline:
                time.sleep(10)
                src2 = page_html(driver)
                cur2 = driver.current_url
                if not is_restricted(src2) and not is_ratelimited_url(cur2):
                    print(json.dumps({"prompt": "ACCESS_RESTORED"}), flush=True)
                    safe_get(driver, detail_url)
                    time.sleep(5)
                    break
            else:
                return False
            continue

    # Timed out — dump page text snippet for diagnosis
    try:
        src_final = page_html(driver) or ""
        snippet = src_final[:400]
        print(f"[scraper] wait_for_detail timed out. URL={driver.current_url} snippet={snippet}", file=sys.stderr, flush=True)
    except Exception:
        pass
    return False


# ── Safe navigation ──────────────────────────────────────────────────────────

def safe_get(driver, url):
    """
    Navigate to a URL, catching page load timeouts gracefully.
    undetected_chromedriver can hang on driver.get() if the page load timeout
    is exceeded — this wrapper catches the TimeoutException so execution continues.
    Any ChromeDriver connection errors are re-raised so the main loop can
    restart the browser.
    """
    try:
        driver.get(url)
    except Exception as e:
        err_str = str(e)
        # Re-raise connection / session errors — browser needs a restart
        if any(s in err_str for s in [
            "ConnectionResetError", "Connection aborted",
            "No connection could be made", "NewConnectionError",
            "Failed to establish", "Max retries exceeded",
            "chrome not reachable", "session deleted", "invalid session",
        ]):
            raise
        if "timeout" in err_str.lower() or "TimeoutException" in type(e).__name__:
            print(f"[scraper] page load timeout for {url} — continuing anyway",
                  file=sys.stderr, flush=True)
        else:
            raise


# ── Name cleaning ─────────────────────────────────────────────────────────────

def clean_name(raw):
    if not raw:
        return ""
    # Split on newline, look for c/o line
    lines = raw.strip().split("\n")
    name = lines[0].strip()
    for line in lines:
        m = re.match(r'(?i)c/o\s+(.+)', line.strip())
        if m:
            name = m.group(1).strip()
            break
    # Strip a/k/a, "and Second Person", entity suffixes/prefixes, generational suffixes
    name = re.sub(r'(?i)\s+a/k/a\b.*', '', name).strip()
    name = re.sub(r'(?i)\s+and\b.*', '', name).strip()
    name = re.sub(r'(?i)\s+(gst|exemption|residuary|trust|estate|revocable|living|'
                  r'irrevocable|testamentary|fund|foundation|llc|inc\.?|ltd\.?|corp\.?|'
                  r'co\.|l\.l\.c\.).*$', '', name).strip()
    name = re.sub(r'(?i)^(estate|trust|gst|residuary|exemption|revocable|living|family|'
                  r'irrevocable|testamentary|intervivos|inter\s+vivos)\s+(of\s+)?', '', name).strip()
    name = re.sub(r'(?i),?\s*(Jr\.?|Sr\.?|II|III|IV|V|VI)\s*$', '', name).strip()
    if "&" in name:
        name = name.split("&")[0].strip()
    return name

# ── Name matching helpers ─────────────────────────────────────────────────────

def name_tokens(raw):
    """Return a set of lowercase word tokens from a name string."""
    if not raw:
        return set()
    # Strip punctuation except apostrophes, lowercase
    cleaned = re.sub(r"[^a-zA-Z' ]", " ", raw).lower()
    return {t for t in cleaned.split() if len(t) > 1}

def card_matches_name(card_html, target_name):
    """
    Return a match score (0–3) for how well a result card matches target_name.
      3 = full name found in card heading
      2 = first + last name both present (possibly in related/AKA names)
      1 = last name present
      0 = no match
    """
    target_tokens = name_tokens(clean_name(target_name))
    if not target_tokens:
        return 0

    card_lower = card_html.lower()

    # Grab the card heading name (primary name shown large)
    heading_match = re.search(r'<span[^>]*card-name[^>]*>([^<]+)</span>', card_html, re.IGNORECASE)
    if not heading_match:
        # Try data-detail-link text or any h2/h3
        heading_match = re.search(r'<(?:h2|h3)[^>]*>([^<]+)</(?:h2|h3)>', card_html, re.IGNORECASE)
    heading_tokens = name_tokens(heading_match.group(1)) if heading_match else set()

    # Full name in heading
    if target_tokens and target_tokens <= heading_tokens:
        return 3

    # Collect all text in the card (heading + AKA / related names section)
    all_text_tokens = name_tokens(card_html)

    # First name + last name both somewhere in the card
    if len(target_tokens) >= 2 and target_tokens <= all_text_tokens:
        return 2

    # At least the last token (likely last name) matches
    last_token = list(target_tokens)[-1] if target_tokens else None
    if last_token and last_token in all_text_tokens:
        return 1

    return 0


def extract_cards(src):
    """
    Split the results page HTML into individual result card HTML chunks.
    Each card is bounded by a data-detail-link attribute.
    Returns list of (detail_path, card_html) tuples.
    """
    cards = []
    for m in re.finditer(r'data-detail-link="(/find/person/[^"]+)"', src):
        start = src.rfind("<div", 0, m.start())
        # Find a reasonable end — next data-detail-link anchor or end of src
        next_card = src.find('data-detail-link="', m.end())
        end = src.rfind("</div>", m.start(), next_card) + 6 if next_card > 0 else m.end() + 2000
        card_html = src[start:end]
        cards.append((m.group(1), card_html))
    return cards


# ── Scraping detail page ──────────────────────────────────────────────────────

def scrape_detail(driver, detail_url):
    """Navigate to a detail page and return phones + emails."""
    print(f"[scraper] navigating to detail: {detail_url}", file=sys.stderr, flush=True)
    safe_get(driver, detail_url)
    time.sleep(5)
    print(f"[scraper] calling wait_for_detail", file=sys.stderr, flush=True)

    if not wait_for_detail(driver, detail_url):
        return None, None  # failed

    html = page_html(driver) or ""
    phones = list(dict.fromkeys(PHONE_RE.findall(html)))

    emails = []
    seen = set()
    for e in EMAIL_RE.findall(html):
        if e in seen:
            continue
        seen.add(e)
        if e.lower() in EMAIL_BLOCKLIST:
            continue
        if any(e.endswith(n) for n in EMAIL_NOISE):
            continue
        if any(d in e for d in EMAIL_NOISE_DOMAINS):
            continue
        emails.append(e)

    return phones, emails


# ── Main search function ──────────────────────────────────────────────────────

def search(driver, name, street, city, state, zipcode):
    """
    Search strategy:
      1. If street address is available, search by address and pick the card
         whose name best matches target name (or AKA/related names).
         Fall back to first card if no name match found.
      2. If no street, fall back to name+city+state search (original behavior).
    """
    search_name = clean_name(name)
    if not search_name:
        return {"phones": [], "emails": [], "error": "Could not extract usable name"}

    # ── Address search path ────────────────────────────────────────────────────
    if street and street.strip():
        # Build city+state+zip string (zip optional)
        citystatezip = city.strip()
        if state:
            citystatezip += ", " + state.strip()
        if zipcode and zipcode.strip():
            citystatezip += " " + zipcode.strip()

        addr_query = (f"streetaddress={pct_encode(street.strip())}"
                      f"&citystatezip={pct_encode(citystatezip)}")
        results_url = f"https://www.truepeoplesearch.com/resultaddress?{addr_query}"
        print(f"[scraper] address search: {results_url}", file=sys.stderr, flush=True)

        safe_get(driver, results_url)
        time.sleep(5)

        if wait_for_results(driver, results_url):
            src = page_html(driver) or ""
            if "data-detail-link" in src:
                cards = extract_cards(src)
                print(f"[scraper] address search found {len(cards)} card(s)", file=sys.stderr, flush=True)

                # Score each card against the target name
                best_path = None
                best_score = -1
                for detail_path, card_html in cards:
                    score = card_matches_name(card_html, name)
                    print(f"[scraper]   card score={score} path={detail_path}", file=sys.stderr, flush=True)
                    if score > best_score:
                        best_score = score
                        best_path = detail_path

                if best_path:
                    if best_score == 0:
                        print(f"[scraper] no name match — using first card", file=sys.stderr, flush=True)
                    else:
                        print(f"[scraper] best match score={best_score}", file=sys.stderr, flush=True)
                    detail_url = "https://www.truepeoplesearch.com" + best_path
                    phones, emails = scrape_detail(driver, detail_url)
                    if phones is not None:
                        return {"phones": phones, "emails": emails, "error": None}

        # Address search failed or no results — fall through to name search
        print(f"[scraper] address search yielded nothing, falling back to name search",
              file=sys.stderr, flush=True)

    # ── Name + city/state search (original fallback) ───────────────────────────
    query = f"name={pct_encode(search_name)}&citystatezip={pct_encode(city + ', ' + state)}"
    results_url = f"https://www.truepeoplesearch.com/results?{query}"

    safe_get(driver, results_url)
    time.sleep(5)

    if not wait_for_results(driver, results_url):
        return {"phones": [], "emails": [], "error": "Results page did not load"}

    src = page_html(driver)
    if not src or "data-detail-link" not in src:
        return {"phones": [], "emails": [], "error": "No results found"}

    m = re.search(r'data-detail-link="(/find/person/[^"]+)"', src)
    if not m:
        return {"phones": [], "emails": [], "error": "No result cards found"}

    detail_url = "https://www.truepeoplesearch.com" + m.group(1)
    phones, emails = scrape_detail(driver, detail_url)
    if phones is None:
        return {"phones": [], "emails": [], "error": "Detail page did not load"}

    return {"phones": phones, "emails": emails, "error": None}

# ── Main loop ─────────────────────────────────────────────────────────────────

def main():
    driver = None
    try:
        driver = make_driver()
        # Signal ready
        print(json.dumps({"ready": True}), flush=True)

        for line in sys.stdin:
            line = line.strip()
            if not line:
                continue
            try:
                req = json.loads(line)
            except json.JSONDecodeError:
                print(json.dumps({"error": "Invalid JSON"}), flush=True)
                continue

            if req.get("quit"):
                break

            name    = req.get("name", "")
            street  = req.get("street", "")
            city    = req.get("city", "")
            state   = req.get("state", "")
            zipcode = req.get("zipcode", "")

            # Attempt the search; on any ChromeDriver / connection error, restart
            # Chrome once and retry before giving up.
            for attempt in range(2):
                try:
                    result = search(driver, name, street, city, state, zipcode)
                    break
                except Exception as ex:
                    err_str = str(ex)
                    is_browser_error = any(s in err_str for s in [
                        "ConnectionResetError", "Connection aborted",
                        "No connection could be made", "NewConnectionError",
                        "Failed to establish", "Max retries exceeded",
                        "WebDriverException", "chrome not reachable",
                        "session deleted", "invalid session",
                    ])
                    if is_browser_error and attempt == 0:
                        print(f"[scraper] Chrome connection lost ({err_str[:80]}) — restarting browser...",
                              file=sys.stderr, flush=True)
                        try:
                            driver.quit()
                        except Exception:
                            pass
                        time.sleep(5)
                        driver = make_driver()
                        print(f"[scraper] Chrome restarted — retrying search",
                              file=sys.stderr, flush=True)
                        continue
                    # Non-browser error or second attempt — return the error
                    result = {"phones": [], "emails": [], "error": err_str}
                    break

            print(json.dumps(result), flush=True)

    except Exception as ex:
        print(json.dumps({"error": f"Fatal: {ex}"}), flush=True)
    finally:
        if driver:
            try:
                driver.quit()
            except Exception:
                pass

if __name__ == "__main__":
    main()
