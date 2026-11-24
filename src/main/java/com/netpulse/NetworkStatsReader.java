package com.netpulse;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Reads the byte counters maintained by the Windows network adapter driver. */
final class NetworkStatsReader {
    private static final String MARKER = "__NETPULSE_OK__";
    private static final String POWERSHELL_SCRIPT = ""
            + "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'; "
            + "[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false); "
            + "$stats = Get-NetAdapterStatistics -ErrorAction Stop; "
            + "$adapters = Get-NetAdapter -IncludeHidden -ErrorAction SilentlyContinue; "
            + "[Console]::WriteLine('" + MARKER + "'); "
            + "foreach ($s in $stats) { "
            + "$a = $adapters | Where-Object { $_.Name -eq $s.Name } | Select-Object -First 1; "
            + "$name = [string]$s.Name; $desc = ''; $id = $name; "
            + "if ($null -ne $a) { $desc = [string]$a.InterfaceDescription; "
            + "if ($a.InterfaceGuid) { $id = [string]$a.InterfaceGuid } }; "
            + "$rx = ([long]$s.ReceivedBytes).ToString([Globalization.CultureInfo]::InvariantCulture); "
            + "$tx = ([long]$s.SentBytes).ToString([Globalization.CultureInfo]::InvariantCulture); "
            + "[Console]::WriteLine(('{0}|{1}|{2}|{3}|{4}' -f "
            + "[uri]::EscapeDataString($id), [uri]::EscapeDataString($name), "
            + "[uri]::EscapeDataString($desc), $rx, $tx)) }";

    List<NetworkSample> read() throws IOException, InterruptedException {
        Process process = new ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", POWERSHELL_SCRIPT)
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(Duration.ofSeconds(8).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Windows network counters did not respond in time.");
        }

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IOException(explain(output));
        }

        List<NetworkSample> samples = new ArrayList<>();
        boolean foundMarker = false;
        for (String line : output.split("\\R")) {
            line = line.strip();
            if (line.equals(MARKER)) {
                foundMarker = true;
                continue;
            }
            if (!foundMarker || line.isEmpty()) continue;
            String[] columns = line.split("\\|", -1);
            if (columns.length != 5) continue;
            try {
                samples.add(new NetworkSample(
                        decode(columns[0]), decode(columns[1]), decode(columns[2]),
                        Long.parseLong(columns[3]), Long.parseLong(columns[4])));
            } catch (IllegalArgumentException ignored) {
                // Ignore unexpected PowerShell output; valid adapter rows are still useful.
            }
        }
        if (!foundMarker) {
            throw new IOException(explain(output));
        }
        return samples;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String explain(String output) {
        String detail = output.lines()
                .map(String::strip)
                .filter(line -> !line.isBlank() && !line.startsWith("At line:"))
                .reduce((first, next) -> next)
                .orElse("Windows did not return network adapter statistics.");
        if (detail.contains("Get-NetAdapterStatistics") || detail.contains("not recognized")) {
            return "Windows network statistics are unavailable. Make sure this is a supported Windows version and PowerShell can access Get-NetAdapterStatistics.";
        }
        return detail.length() > 240 ? detail.substring(0, 237) + "..." : detail;
    }
}

record NetworkSample(String id, String name, String description, long receivedBytes, long sentBytes) {
    String displayName() {
        if (description == null || description.isBlank() || description.equalsIgnoreCase(name)) return name;
        return name + "  ·  " + description;
    }
}