# NetPulse

NetPulse is a Windows desktop network monitor written in Java. It reads the byte counters Windows reports for each network adapter, calculates the changes, and presents usage in both bits and bytes.

## Quick start

1. Open this folder in File Explorer.
2. Double-click dist\NetPulse\NetPulse.exe.
3. Select All adapters or a single network interface.
4. Read received, sent, combined, today, and live speed figures on the dashboard.

The app image includes its Java runtime. You do not need to install Java to run the executable.

## Features

- Received, sent, and combined transfer totals in bits and bytes
- Current receive and send speed in bits per second
- Per-adapter totals and combined totals across adapters
- Daily usage chart and a rolling 120-day local history
- CSV export and a reset control
- Optional pause/resume and notification-area tray controls
- Dark Windows desktop interface with no third-party libraries

## Build from source

Install a JDK 17 or newer, then run build.ps1 from PowerShell. It compiles the app, creates build\input\NetPulse.jar, and, when jpackage is available, creates the self-contained Windows app under dist\NetPulse.

You can also run run.bat after building.

## How totals are counted

On first launch, NetPulse records the current Windows adapter counters as a starting point. It adds later counter changes to the local totals. It can include traffic from times when the app was closed if Windows' adapter counters still cover that period. If Windows resets an adapter counter, tracking continues from its new reading; usage before that reset cannot be recovered by the app. Paused traffic is excluded.

Windows may report VPN and virtual adapters alongside physical adapters. Selecting All adapters adds the counters for every listed adapter; for a VPN or virtual interface, that may overlap with traffic also shown on a physical interface. Select a single adapter when you want to inspect that path by itself.

All history is kept on this PC in the local application data folder under NetPulse\usage.properties. NetPulse does not send usage data anywhere.

## Units

Units use decimal prefixes: 1 kbit = 1,000 bits; 1 MB = 1,000,000 bytes. Network counters are read as bytes and converted to bits for the dashboard.