# Fiverr Support

An Android app that keeps your Fiverr app "active" when your phone's screen is off.

## Key Features

1. **Auto-refresh Fiverr** - Periodically wakes the screen and either:

    - Opens Fiverr if it's not running
    - Performs a "pull-down" scroll gesture if Fiverr is already open (to refresh notifications/messages)

2. **Battery-conscious automation** - Dims screen to minimum brightness during automated actions to save power

3. **Configurable intervals** - Refresh every 10s, 30s, 1m, 2m, 3m, 5m, 10m, or 30m

4. **Smart connectivity check** - Skips actions if there's no internet connection

5. **Self-healing service** - Restarts automatically if killed by Android

6. **Auto Screen-Off on Inactivity** - When the user manually unlocks the device:
    - If **no interaction within the timeout** (default 10s) → screen turns off immediately
    - If **user interacts within the timeout** → stops monitoring, phone's default screen timeout applies
    - Only activates on **manual** unlocks (not automation)
    - Timeout configurable: 5s, 10s, 15s, 20s, 30s, 45s, 1m, 1m30s, 2m

## Why Use This?

👉 **Never miss Fiverr client messages/notifications** — the app keeps Fiverr refreshed in the background even when your phone is locked, so you get alerts faster (important for freelancers who need quick response times).
