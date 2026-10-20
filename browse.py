#!/usr/bin/env python3
"""Headless Chromium browser helper for Jarvis."""
import sys
from selenium import webdriver
from selenium.webdriver.chrome.options import Options
from selenium.webdriver.chrome.service import Service

CHROMEDRIVER = "/data/data/com.termux/files/usr/lib/chromium/chromedriver"
CHROMIUM_BIN = "/data/data/com.termux/files/usr/lib/chromium/chrome"

def get_driver():
    opts = Options()
    opts.binary_location = CHROMIUM_BIN
    opts.add_argument('--headless')
    opts.add_argument('--no-sandbox')
    opts.add_argument('--disable-dev-shm-usage')
    opts.add_argument('--disable-gpu')
    opts.add_argument('--window-size=1280,720')
    service = Service(CHROMEDRIVER)
    return webdriver.Chrome(service=service, options=opts)

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: browse.py <url> [screenshot.png]")
        sys.exit(1)

    url = sys.argv[1]
    screenshot = sys.argv[2] if len(sys.argv) > 2 else None

    driver = get_driver()
    driver.get(url)
    title = driver.title
    if screenshot:
        driver.save_screenshot(screenshot)
        print(f"Screenshot saved: {screenshot}")
    print(f"Title: {title}")
    driver.quit()