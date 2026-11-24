package com.netpulse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/** Accumulates adapter counter differences and keeps a small, local usage history. */
final class UsageTracker {
    private static final int HISTORY_DAYS = 120;
    private final Path file;
    private final Map<String, AdapterUsage> records = new LinkedHashMap<>();
    private LocalDateTime trackingSince = LocalDateTime.now();
    private Instant lastPoll;
    private String saveError = "";

    UsageTracker() {
        String local = System.getenv("LOCALAPPDATA");
        Path root = local == null || local.isBlank()
                ? Path.of(System.getProperty("user.home"), "AppData", "Local")
                : Path.of(local);
        file = root.resolve("NetPulse").resolve("usage.properties");
        load();
    }

    synchronized PollResult update(List<NetworkSample> samples, boolean paused) {
        Instant now = Instant.now();
        double seconds = lastPoll == null ? Double.POSITIVE_INFINITY : Math.max(0.25, Duration.between(lastPoll, now).toMillis() / 1000.0);
        lastPoll = now;
        long deltaReceived = 0;
        long deltaSent = 0;
        Map<String, Totals> adapterDeltas = new LinkedHashMap<>();
        LocalDate today = LocalDate.now();

        for (NetworkSample sample : samples) {
            AdapterUsage usage = records.get(sample.id());
            if (usage == null) {
                usage = new AdapterUsage(sample.id(), sample.name(), sample.description());
                records.put(sample.id(), usage);
            }
            usage.name = sample.name();
            usage.description = sample.description();

            if (!usage.initialized) {
                // The first reading is a baseline; don't count traffic from before installation.
                usage.baselineReceived = sample.receivedBytes();
                usage.baselineSent = sample.sentBytes();
                usage.initialized = true;
                adapterDeltas.put(sample.id(), new Totals(0, 0));
                continue;
            }

            long rx = counterDelta(sample.receivedBytes(), usage.baselineReceived);
            long tx = counterDelta(sample.sentBytes(), usage.baselineSent);
            usage.baselineReceived = sample.receivedBytes();
            usage.baselineSent = sample.sentBytes();

            adapterDeltas.put(sample.id(), paused ? new Totals(0, 0) : new Totals(rx, tx));
            if (!paused) {
                usage.totalReceived = safeAdd(usage.totalReceived, rx);
                usage.totalSent = safeAdd(usage.totalSent, tx);
                DailyUsage day = usage.days.computeIfAbsent(today, ignored -> new DailyUsage());
                day.received = safeAdd(day.received, rx);
                day.sent = safeAdd(day.sent, tx);
                deltaReceived = safeAdd(deltaReceived, rx);
                deltaSent = safeAdd(deltaSent, tx);
            }
            usage.days.headMap(today.minusDays(HISTORY_DAYS), false).clear();
        }
        persist();
        return new PollResult(deltaReceived, deltaSent, seconds, Map.copyOf(adapterDeltas));
    }

    synchronized Totals totals(String selectedId) {
        long rx = 0;
        long tx = 0;
        for (AdapterUsage usage : selectedRecords(selectedId)) {
            rx = safeAdd(rx, usage.totalReceived);
            tx = safeAdd(tx, usage.totalSent);
        }
        return new Totals(rx, tx);
    }

    synchronized Totals today(String selectedId) {
        long rx = 0;
        long tx = 0;
        LocalDate date = LocalDate.now();
        for (AdapterUsage usage : selectedRecords(selectedId)) {
            DailyUsage today = usage.days.get(date);
            if (today != null) {
                rx = safeAdd(rx, today.received);
                tx = safeAdd(tx, today.sent);
            }
        }
        return new Totals(rx, tx);
    }

    synchronized Map<LocalDate, Totals> dailyTotals(String selectedId, int days) {
        Map<LocalDate, Totals> result = new TreeMap<>();
        LocalDate first = LocalDate.now().minusDays(days - 1L);
        for (AdapterUsage usage : selectedRecords(selectedId)) {
            usage.days.tailMap(first, true).forEach((date, value) -> {
                Totals old = result.getOrDefault(date, new Totals(0, 0));
                result.put(date, new Totals(
                        safeAdd(old.receivedBytes(), value.received),
                        safeAdd(old.sentBytes(), value.sent)));
            });
        }
        return result;
    }

    synchronized List<NetworkSample> onlineSamples(List<NetworkSample> samples) {
        return samples.stream().sorted(Comparator.comparing(NetworkSample::displayName, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    synchronized LocalDateTime trackingSince() { return trackingSince; }

    synchronized String saveError() { return saveError; }

    synchronized void reset(List<NetworkSample> currentSamples) {
        for (AdapterUsage usage : records.values()) {
            usage.totalReceived = 0;
            usage.totalSent = 0;
            usage.days.clear();
        }
        for (NetworkSample sample : currentSamples) {
            AdapterUsage usage = records.get(sample.id());
            if (usage != null) {
                usage.baselineReceived = sample.receivedBytes();
                usage.baselineSent = sample.sentBytes();
                usage.initialized = true;
            }
        }
        trackingSince = LocalDateTime.now();
        persist();
    }

    synchronized List<ExportRow> exportRows(String selectedId) {
        List<ExportRow> result = new ArrayList<>();
        for (AdapterUsage usage : selectedRecords(selectedId)) {
            usage.days.forEach((date, day) -> result.add(new ExportRow(
                    date.toString(), usage.displayName(), day.received, day.sent)));
            result.add(new ExportRow("All time", usage.displayName(), usage.totalReceived, usage.totalSent));
        }
        return result;
    }

    private List<AdapterUsage> selectedRecords(String selectedId) {
        if (selectedId == null) return new ArrayList<>(records.values());
        AdapterUsage selected = records.get(selectedId);
        return selected == null ? List.of() : List.of(selected);
    }

    private void load() {
        if (!Files.isRegularFile(file)) return;
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
            String since = properties.getProperty("trackingSince");
            if (since != null) trackingSince = LocalDateTime.parse(since);
            int count = Integer.parseInt(properties.getProperty("adapter.count", "0"));
            for (int i = 0; i < count; i++) {
                String prefix = "adapter." + i + ".";
                String id = properties.getProperty(prefix + "id");
                if (id == null) continue;
                AdapterUsage usage = new AdapterUsage(id,
                        properties.getProperty(prefix + "name", id),
                        properties.getProperty(prefix + "description", ""));
                usage.baselineReceived = parseLong(properties, prefix + "baselineReceived");
                usage.baselineSent = parseLong(properties, prefix + "baselineSent");
                usage.totalReceived = parseLong(properties, prefix + "totalReceived");
                usage.totalSent = parseLong(properties, prefix + "totalSent");
                usage.initialized = Boolean.parseBoolean(properties.getProperty(prefix + "initialized", "false"));
                int dayCount = Integer.parseInt(properties.getProperty(prefix + "day.count", "0"));
                for (int j = 0; j < dayCount; j++) {
                    String dayPrefix = prefix + "day." + j + ".";
                    LocalDate date = LocalDate.parse(properties.getProperty(dayPrefix + "date"));
                    usage.days.put(date, new DailyUsage(parseLong(properties, dayPrefix + "received"),
                            parseLong(properties, dayPrefix + "sent")));
                }
                records.put(id, usage);
            }
        } catch (Exception exception) {
            saveError = "Saved usage history could not be read. A new history will be created.";
            records.clear();
            trackingSince = LocalDateTime.now();
        }
    }

    private void persist() {
        Properties properties = new Properties();
        properties.setProperty("version", "1");
        properties.setProperty("trackingSince", trackingSince.toString());
        properties.setProperty("adapter.count", Integer.toString(records.size()));
        int index = 0;
        for (AdapterUsage usage : records.values()) {
            String prefix = "adapter." + index++ + ".";
            properties.setProperty(prefix + "id", usage.id);
            properties.setProperty(prefix + "name", usage.name);
            properties.setProperty(prefix + "description", usage.description == null ? "" : usage.description);
            properties.setProperty(prefix + "baselineReceived", Long.toString(usage.baselineReceived));
            properties.setProperty(prefix + "baselineSent", Long.toString(usage.baselineSent));
            properties.setProperty(prefix + "totalReceived", Long.toString(usage.totalReceived));
            properties.setProperty(prefix + "totalSent", Long.toString(usage.totalSent));
            properties.setProperty(prefix + "initialized", Boolean.toString(usage.initialized));
            properties.setProperty(prefix + "day.count", Integer.toString(usage.days.size()));
            int dayIndex = 0;
            for (Map.Entry<LocalDate, DailyUsage> entry : usage.days.entrySet()) {
                String dayPrefix = prefix + "day." + dayIndex++ + ".";
                properties.setProperty(dayPrefix + "date", entry.getKey().toString());
                properties.setProperty(dayPrefix + "received", Long.toString(entry.getValue().received));
                properties.setProperty(dayPrefix + "sent", Long.toString(entry.getValue().sent));
            }
        }
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "NetPulse local network usage history");
            }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            saveError = "";
        } catch (IOException exception) {
            saveError = "NetPulse cannot save history in your local app data folder.";
        }
    }

    private static long parseLong(Properties properties, String key) {
        return Long.parseLong(properties.getProperty(key, "0"));
    }

    private static long counterDelta(long current, long previous) {
        // Adapter counters can reset at reboot; in that case count from the new counter baseline.
        return current >= previous ? current - previous : current;
    }

    static long safeAdd(long first, long second) {
        if (second > 0 && first > Long.MAX_VALUE - second) return Long.MAX_VALUE;
        if (second < 0 && first < Long.MIN_VALUE - second) return Long.MIN_VALUE;
        return first + second;
    }

    record Totals(long receivedBytes, long sentBytes) {
        long totalBytes() { return safeAdd(receivedBytes, sentBytes); }
    }
    record PollResult(long receivedDelta, long sentDelta, double seconds, Map<String, Totals> adapterDeltas) {}
    record ExportRow(String date, String adapter, long receivedBytes, long sentBytes) {}

    private static final class AdapterUsage {
        final String id;
        String name;
        String description;
        long baselineReceived;
        long baselineSent;
        long totalReceived;
        long totalSent;
        boolean initialized;
        final TreeMap<LocalDate, DailyUsage> days = new TreeMap<>();

        AdapterUsage(String id, String name, String description) {
            this.id = id;
            this.name = name;
            this.description = description == null ? "" : description;
        }

        String displayName() {
            return description.isBlank() || description.equalsIgnoreCase(name)
                    ? name : name + " · " + description;
        }
    }

    private static final class DailyUsage {
        long received;
        long sent;
        DailyUsage() {}
        DailyUsage(long received, long sent) { this.received = received; this.sent = sent; }
    }
}