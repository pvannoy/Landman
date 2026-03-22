"""
TruePeopleSearch scraper using undetected-chromedriver.
Launched as a subprocess by TruePeopleSearchScraper.java.

Protocol (stdin/stdout, one JSON object per line):
  Input:  {"name": "John Smith", "city": "Edmond", "state": "OK"}
  Output: {"phones": ["(405) 123-4567"], "emails": ["john@example.com"], "error": null}
  Quit:   {"quit": true}
"""

import sys
import json
import time
import re
import os

SCRAPER_VERSION = "2.5"  # Updated: check success before restriction
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
        if "No results found" in src:
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
    """
    try:
        driver.get(url)
    except Exception as e:
        if "timeout" in str(e).lower() or "TimeoutException" in type(e).__name__:
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

# ── Main search function ──────────────────────────────────────────────────────

def search(driver, name, city, state):
    search_name = clean_name(name)
    if not search_name:
        return {"phones": [], "emails": [], "error": "Could not extract usable name"}

    query = f"name={pct_encode(search_name)}&citystatezip={pct_encode(city + ', ' + state)}"
    results_url = f"https://www.truepeoplesearch.com/results?{query}"

    safe_get(driver, results_url)
    time.sleep(5)  # initial render pause — wait for page to fully load

    if not wait_for_results(driver, results_url):
        return {"phones": [], "emails": [], "error": "Results page did not load"}

    # Get first result's detail link
    src = page_html(driver)
    if not src or "data-detail-link" not in src:
        return {"phones": [], "emails": [], "error": "No results found"}

    # Extract detail link directly from HTML — avoids WebDriver connection timeout
    m = re.search(r'data-detail-link="(/find/person/[^"]+)"', src)
    if not m:
        return {"phones": [], "emails": [], "error": "No result cards found"}
    detail_path = m.group(1)

    detail_url = "https://www.truepeoplesearch.com" + detail_path
    print(f"[scraper] navigating to detail: {detail_url}", file=sys.stderr, flush=True)
    safe_get(driver, detail_url)
    time.sleep(5)  # detail page render pause
    print(f"[scraper] calling wait_for_detail", file=sys.stderr, flush=True)

    if not wait_for_detail(driver, detail_url):
        return {"phones": [], "emails": [], "error": "Detail page did not load"}

    html = page_html(driver) or ""

    phones = list(dict.fromkeys(PHONE_RE.findall(html)))  # deduplicated, order preserved

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

            name  = req.get("name", "")
            city  = req.get("city", "")
            state = req.get("state", "")

            try:
                result = search(driver, name, city, state)
            except Exception as ex:
                result = {"phones": [], "emails": [], "error": str(ex)}

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
