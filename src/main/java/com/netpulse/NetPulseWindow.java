package com.netpulse;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.border.EmptyBorder;

final class NetPulseWindow extends JFrame {
    private static final Color BACKGROUND = new Color(12, 17, 25);
    private static final Color SURFACE = new Color(19, 27, 39);
    private static final Color SURFACE_ALT = new Color(23, 32, 46);
    private static final Color BORDER = new Color(42, 54, 72);
    private static final Color TEXT = new Color(239, 244, 250);
    private static final Color MUTED = new Color(147, 162, 182);
    private static final Color TEAL = new Color(67, 211, 173);
    private static final Color PURPLE = new Color(151, 126, 255);
    private static final DateTimeFormatter SINCE_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm:ss a");

    private final UsageTracker tracker = new UsageTracker();
    private final NetworkStatsReader reader = new NetworkStatsReader();
    private final Timer timer = new Timer(2_000, event -> poll());
    private final JPanel root = new JPanel(new BorderLayout(0, 0));
    private final JComboBox<AdapterChoice> adapterBox = new JComboBox<>();
    private final JLabel connectionStatus = new JLabel("Connecting to Windows network counters…");
    private final JLabel footerStatus = new JLabel("Starting monitor");
    private final JLabel footerSaved = new JLabel();
    private final JLabel errorBanner = new JLabel();
    private final JLabel todayReceived = new JLabel("0 bit  ·  0 B");
    private final JLabel todaySent = new JLabel("0 bit  ·  0 B");
    private final JLabel todayTotal = new JLabel("0 bit  ·  0 B");
    private final JLabel trackingSinceValue = new JLabel();
    private final JLabel adapterSummary = new JLabel("Waiting for adapter details");
    private final JLabel lastUpdated = new JLabel("—");
    private final JLabel stateDot = new JLabel("●");
    private final JLabel stateLabel = new JLabel("CONNECTING");
    private final JButton pauseButton = new JButton("Pause tracking");
    private final MetricCard receivedCard = new MetricCard("RECEIVED · ALL TIME", TEAL);
    private final MetricCard sentCard = new MetricCard("SENT · ALL TIME", PURPLE);
    private final MetricCard totalCard = new MetricCard("TOTAL TRANSFERRED", TEXT);
    private final ThroughputChart throughputChart = new ThroughputChart();
    private final DailyUsageChart dailyChart = new DailyUsageChart();
    private final List<NetworkSample> currentSamples = new ArrayList<>();
    private final Map<String, UsageTracker.Totals> latestDeltas = new HashMap<>();
    private String selectedId;
    private boolean rebuildingChoices;
    private boolean inFlight;
    private boolean paused;
    private boolean trayNoticeShown;
    private TrayIcon trayIcon;
    private MenuItem trayPauseItem;
    private double lastSampleSeconds = 2;

    NetPulseWindow() {
        super("NetPulse — Network Usage");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setIconImage(createIcon(64));
        setMinimumSize(new Dimension(960, 720));
        setSize(1220, 860);
        setLocationRelativeTo(null);
        buildWindow();
        installTray();
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent event) {
                minimizeOrExit();
            }
        });
        timer.start();
        poll();
        render();
    }

    private void buildWindow() {
        root.setBackground(BACKGROUND);
        root.setBorder(new EmptyBorder(18, 24, 14, 24));
        setContentPane(root);

        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(buildFooter(), BorderLayout.SOUTH);

        JPanel content = new JPanel();
        content.setBackground(BACKGROUND);
        content.setBorder(new EmptyBorder(22, 2, 10, 2));
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.add(buildToolbar());
        content.add(Box.createVerticalStrut(16));
        content.add(buildErrorBanner());
        content.add(Box.createVerticalStrut(15));
        content.add(buildMetrics());
        content.add(Box.createVerticalStrut(16));
        content.add(buildAnalytics());
        content.add(Box.createVerticalStrut(16));
        content.add(buildDailyHistory());
        content.add(Box.createVerticalStrut(10));
        content.add(buildDataNote());
        content.add(Box.createVerticalStrut(8));

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.setBackground(BACKGROUND);
        scroll.getViewport().setBackground(BACKGROUND);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        root.add(scroll, BorderLayout.CENTER);
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout(14, 0));
        header.setOpaque(false);

        JPanel identity = new JPanel(new FlowLayout(FlowLayout.LEFT, 13, 0));
        identity.setOpaque(false);
        JLabel mark = new JLabel(new javax.swing.ImageIcon(createIcon(42)));
        identity.add(mark);
        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        JLabel title = label("NetPulse", 25, TEXT, Font.BOLD);
        JLabel subtitle = label("Windows network usage at a glance", 12, MUTED, Font.PLAIN);
        titles.add(title);
        titles.add(Box.createVerticalStrut(3));
        titles.add(subtitle);
        identity.add(titles);
        header.add(identity, BorderLayout.WEST);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 4));
        actions.setOpaque(false);
        stateDot.setForeground(MUTED);
        stateDot.setFont(stateDot.getFont().deriveFont(12f));
        stateLabel.setForeground(MUTED);
        stateLabel.setFont(new Font("Segoe UI", Font.BOLD, 10));
        JPanel status = new JPanel(new FlowLayout(FlowLayout.CENTER, 7, 8));
        status.setBackground(SURFACE_ALT);
        status.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER, 1, true), new EmptyBorder(0, 9, 0, 9)));
        status.add(stateDot);
        status.add(stateLabel);
        actions.add(status);
        styleButton(pauseButton, false);
        pauseButton.addActionListener(event -> togglePause());
        actions.add(pauseButton);
        header.add(actions, BorderLayout.EAST);
        return header;
    }

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new BorderLayout(12, 0));
        bar.setOpaque(false);
        JPanel network = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
        network.setOpaque(false);
        JLabel label = label("NETWORK INTERFACE", 10, MUTED, Font.BOLD);
        label.setBorder(new EmptyBorder(0, 0, 0, 4));
        network.add(label);
        adapterBox.setPreferredSize(new Dimension(330, 36));
        adapterBox.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        adapterBox.setForeground(TEXT);
        adapterBox.setBackground(SURFACE_ALT);
        adapterBox.setToolTipText("Choose an adapter or view the combined totals for all adapters");
        adapterBox.addActionListener(event -> selectionChanged());
        network.add(adapterBox);
        connectionStatus.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        connectionStatus.setForeground(MUTED);
        network.add(connectionStatus);
        bar.add(network, BorderLayout.WEST);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 1));
        actions.setOpaque(false);
        JButton export = new JButton("Export CSV");
        styleButton(export, false);
        export.addActionListener(event -> exportCsv());
        JButton reset = new JButton("Reset tracked data");
        styleButton(reset, true);
        reset.addActionListener(event -> confirmReset());
        actions.add(export);
        actions.add(reset);
        bar.add(actions, BorderLayout.EAST);
        return bar;
    }

    private JPanel buildErrorBanner() {
        JPanel banner = new JPanel(new BorderLayout(9, 0));
        banner.setBackground(new Color(55, 34, 34));
        banner.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(125, 66, 62), 1, true),
                new EmptyBorder(9, 13, 9, 13)));
        JLabel icon = label("!", 14, new Color(255, 174, 163), Font.BOLD);
        banner.add(icon, BorderLayout.WEST);
        errorBanner.setForeground(new Color(255, 203, 196));
        errorBanner.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        banner.add(errorBanner, BorderLayout.CENTER);
        banner.setVisible(false);
        return banner;
    }

    private JPanel buildMetrics() {
        JPanel cards = new JPanel(new GridLayout(1, 3, 12, 0));
        cards.setOpaque(false);
        cards.setAlignmentX(Component.LEFT_ALIGNMENT);
        cards.setPreferredSize(new Dimension(1000, 150));
        cards.setMaximumSize(new Dimension(Integer.MAX_VALUE, 150));
        cards.add(receivedCard);
        cards.add(sentCard);
        cards.add(totalCard);
        return cards;
    }

    private JPanel buildAnalytics() {
        JPanel row = new JPanel(new GridLayout(1, 2, 14, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setPreferredSize(new Dimension(1000, 305));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 305));

        JPanel chartCard = cardPanel();
        chartCard.setLayout(new BorderLayout(0, 8));
        chartCard.setBorder(new EmptyBorder(16, 17, 12, 17));
        JPanel titleRow = new JPanel(new BorderLayout());
        titleRow.setOpaque(false);
        titleRow.add(label("LIVE THROUGHPUT", 11, TEXT, Font.BOLD), BorderLayout.WEST);
        titleRow.add(label("LAST 60 SAMPLES", 9, MUTED, Font.PLAIN), BorderLayout.EAST);
        chartCard.add(titleRow, BorderLayout.NORTH);
        chartCard.add(throughputChart, BorderLayout.CENTER);
        chartCard.add(buildLegend(), BorderLayout.SOUTH);
        row.add(chartCard);

        JPanel infoCard = cardPanel();
        infoCard.setLayout(new BoxLayout(infoCard, BoxLayout.Y_AXIS));
        infoCard.setBorder(new EmptyBorder(17, 19, 15, 19));
        infoCard.add(label("DATA TODAY", 11, TEXT, Font.BOLD));
        infoCard.add(Box.createVerticalStrut(16));
        infoCard.add(dataRow("Received", todayReceived, TEAL));
        infoCard.add(Box.createVerticalStrut(12));
        infoCard.add(dataRow("Sent", todaySent, PURPLE));
        infoCard.add(Box.createVerticalStrut(12));
        infoCard.add(dataRow("Combined", todayTotal, TEXT));
        infoCard.add(Box.createVerticalStrut(15));
        infoCard.add(separator());
        infoCard.add(Box.createVerticalStrut(13));
        infoCard.add(label("CURRENT VIEW", 9, MUTED, Font.BOLD));
        infoCard.add(Box.createVerticalStrut(5));
        adapterSummary.setForeground(TEXT);
        adapterSummary.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        infoCard.add(adapterSummary);
        infoCard.add(Box.createVerticalStrut(10));
        infoCard.add(label("TRACKING SINCE", 9, MUTED, Font.BOLD));
        infoCard.add(Box.createVerticalStrut(4));
        trackingSinceValue.setForeground(TEXT);
        trackingSinceValue.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        infoCard.add(trackingSinceValue);
        infoCard.add(Box.createVerticalGlue());
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        bottom.add(label("Last updated", 10, MUTED, Font.PLAIN), BorderLayout.WEST);
        lastUpdated.setForeground(MUTED);
        lastUpdated.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        bottom.add(lastUpdated, BorderLayout.EAST);
        infoCard.add(Box.createVerticalStrut(10));
        infoCard.add(bottom);
        row.add(infoCard);
        return row;
    }

    private JPanel buildLegend() {
        JPanel legend = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 0));
        legend.setOpaque(false);
        legend.add(legendItem("↓  Received", TEAL));
        legend.add(legendItem("↑  Sent", PURPLE));
        return legend;
    }

    private JPanel buildDailyHistory() {
        JPanel card = cardPanel();
        card.setLayout(new BorderLayout(0, 10));
        card.setBorder(new EmptyBorder(16, 17, 9, 17));
        JPanel titleRow = new JPanel(new BorderLayout());
        titleRow.setOpaque(false);
        titleRow.add(label("DAILY USAGE", 11, TEXT, Font.BOLD), BorderLayout.WEST);
        titleRow.add(label("LAST 7 DAYS · RECEIVED + SENT", 9, MUTED, Font.PLAIN), BorderLayout.EAST);
        card.add(titleRow, BorderLayout.NORTH);
        card.add(dailyChart, BorderLayout.CENTER);
        return card;
    }

    private JPanel buildDataNote() {
        JPanel note = new JPanel(new BorderLayout());
        note.setOpaque(false);
        JLabel text = label("Data is stored locally on this PC · Daily history is kept for 120 days · All data units use decimal prefixes", 10, MUTED, Font.PLAIN);
        note.add(text, BorderLayout.WEST);
        return note;
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.setBorder(new EmptyBorder(11, 2, 0, 2));
        footerStatus.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        footerStatus.setForeground(MUTED);
        footer.add(footerStatus, BorderLayout.WEST);
        footerSaved.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        footerSaved.setForeground(MUTED);
        footer.add(footerSaved, BorderLayout.EAST);
        return footer;
    }

    private JPanel dataRow(String title, JLabel value, Color color) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        JLabel caption = label(title, 12, MUTED, Font.PLAIN);
        value.setFont(new Font("Segoe UI", Font.BOLD, 12));
        value.setForeground(color);
        row.add(caption, BorderLayout.WEST);
        row.add(value, BorderLayout.EAST);
        return row;
    }

    private JPanel legendItem(String text, Color color) {
        JPanel item = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        item.setOpaque(false);
        item.add(label("●", 10, color, Font.BOLD));
        item.add(label(text, 10, MUTED, Font.PLAIN));
        return item;
    }

    private JPanel separator() {
        JPanel line = new JPanel();
        line.setBackground(BORDER);
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        line.setPreferredSize(new Dimension(1, 1));
        return line;
    }

    private JPanel cardPanel() {
        JPanel panel = new JPanel();
        panel.setBackground(SURFACE);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER, 1, true), new EmptyBorder(1, 1, 1, 1)));
        return panel;
    }

    private JLabel label(String text, int size, Color color, int style) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("Segoe UI", style, size));
        label.setForeground(color);
        return label;
    }

    private void styleButton(JButton button, boolean secondary) {
        button.setFont(new Font("Segoe UI", Font.BOLD, 11));
        button.setForeground(secondary ? MUTED : TEXT);
        button.setBackground(secondary ? SURFACE : SURFACE_ALT);
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER, 1, true), new EmptyBorder(8, 12, 8, 12)));
        button.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
    }

    private void poll() {
        if (inFlight) return;
        inFlight = true;
        new SwingWorker<List<NetworkSample>, Void>() {
            @Override protected List<NetworkSample> doInBackground() throws Exception {
                return reader.read();
            }

            @Override protected void done() {
                inFlight = false;
                try {
                    List<NetworkSample> samples = tracker.onlineSamples(get());
                    currentSamples.clear();
                    currentSamples.addAll(samples);
                    updateChoices(samples);
                    UsageTracker.PollResult result = tracker.update(samples, paused);
                    latestDeltas.clear();
                    latestDeltas.putAll(result.adapterDeltas());
                    lastSampleSeconds = result.seconds();
                    setError(tracker.saveError());
                    connectionStatus.setText(samples.isEmpty()
                            ? "No network adapters are currently available"
                            : samples.size() + (samples.size() == 1 ? " network adapter found" : " network adapters found"));
                    lastUpdated.setText(LocalDateTime.now().format(TIME_FORMAT));
                    render();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    setError("Monitoring was interrupted. NetPulse will try again.");
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    setError(cause == null ? "Windows network counters could not be read." : cause.getMessage());
                    stateDot.setForeground(new Color(255, 171, 102));
                    stateLabel.setText("RECONNECTING");
                    stateLabel.setForeground(new Color(255, 171, 102));
                } catch (RuntimeException exception) {
                    setError("NetPulse could not update the usage history.");
                } finally {
                    if (currentSamples.isEmpty() && errorBanner.isVisible()) {
                        connectionStatus.setText("Waiting for Windows network counters");
                    }
                }
            }
        }.execute();
    }

    private void updateChoices(List<NetworkSample> samples) {
        String wanted = selectedId;
        List<AdapterChoice> choices = new ArrayList<>();
        choices.add(new AdapterChoice(null, "All adapters · combined usage"));
        for (NetworkSample sample : samples) choices.add(new AdapterChoice(sample.id(), sample.displayName()));
        boolean same = adapterBox.getItemCount() == choices.size();
        if (same) {
            for (int i = 0; i < choices.size(); i++) {
                AdapterChoice current = adapterBox.getItemAt(i);
                AdapterChoice next = choices.get(i);
                if (!current.idEquals(next) || !current.label.equals(next.label)) {
                    same = false;
                    break;
                }
            }
        }
        if (same) return;
        rebuildingChoices = true;
        adapterBox.setModel(new DefaultComboBoxModel<>(choices.toArray(AdapterChoice[]::new)));
        int selectedIndex = 0;
        if (wanted != null) {
            for (int i = 0; i < choices.size(); i++) {
                if (wanted.equals(choices.get(i).id)) selectedIndex = i;
            }
        }
        adapterBox.setSelectedIndex(selectedIndex);
        AdapterChoice selected = choices.get(selectedIndex);
        selectedId = selected.id;
        rebuildingChoices = false;
    }

    private void selectionChanged() {
        if (rebuildingChoices) return;
        AdapterChoice choice = (AdapterChoice) adapterBox.getSelectedItem();
        String next = choice == null ? null : choice.id;
        if ((selectedId == null && next == null) || (selectedId != null && selectedId.equals(next))) return;
        selectedId = next;
        throughputChart.clear();
        render();
    }

    private void render() {
        UsageTracker.Totals totals = tracker.totals(selectedId);
        UsageTracker.Totals today = tracker.today(selectedId);
        long receiveRateBytes = 0;
        long sentRateBytes = 0;
        for (Map.Entry<String, UsageTracker.Totals> entry : latestDeltas.entrySet()) {
            if (selectedId == null || selectedId.equals(entry.getKey())) {
                receiveRateBytes = UsageTracker.safeAdd(receiveRateBytes, entry.getValue().receivedBytes());
                sentRateBytes = UsageTracker.safeAdd(sentRateBytes, entry.getValue().sentBytes());
            }
        }
        double receiveBitsRate = receiveRateBytes * 8.0 / Math.max(0.25, lastSampleSeconds);
        double sentBitsRate = sentRateBytes * 8.0 / Math.max(0.25, lastSampleSeconds);
        double totalBitsRate = receiveBitsRate + sentBitsRate;
        receivedCard.setValue(UsageFormat.bits(totals.receivedBytes() * 8.0),
                UsageFormat.bytes(totals.receivedBytes()), UsageFormat.rate(receiveBitsRate));
        sentCard.setValue(UsageFormat.bits(totals.sentBytes() * 8.0),
                UsageFormat.bytes(totals.sentBytes()), UsageFormat.rate(sentBitsRate));
        totalCard.setValue(UsageFormat.bits(totals.totalBytes() * 8.0),
                UsageFormat.bytes(totals.totalBytes()), UsageFormat.rate(totalBitsRate));
        todayReceived.setText(UsageFormat.paired(today.receivedBytes()));
        todaySent.setText(UsageFormat.paired(today.sentBytes()));
        todayTotal.setText(UsageFormat.paired(today.totalBytes()));
        dailyChart.setData(tracker.dailyTotals(selectedId, 7));
        trackingSinceValue.setText(tracker.trackingSince().format(SINCE_FORMAT));

        AdapterChoice selected = (AdapterChoice) adapterBox.getSelectedItem();
        if (selectedId == null) {
            adapterSummary.setText(currentSamples.size() + (currentSamples.size() == 1 ? " adapter · combined" : " adapters · combined"));
        } else {
            adapterSummary.setText(selected == null ? "Selected adapter" : selected.label);
        }
        if (selectedId == null) {
            receivedCard.setTitle("RECEIVED · ALL TIME");
            sentCard.setTitle("SENT · ALL TIME");
        } else {
            receivedCard.setTitle("RECEIVED · TRACKED");
            sentCard.setTitle("SENT · TRACKED");
        }
        if (paused) {
            stateDot.setForeground(new Color(255, 190, 100));
            stateLabel.setForeground(new Color(255, 190, 100));
            stateLabel.setText("PAUSED");
        } else if (!errorBanner.isVisible()) {
            stateDot.setForeground(TEAL);
            stateLabel.setForeground(TEAL);
            stateLabel.setText("LIVE");
        }
        footerStatus.setText(paused ? "Tracking paused · New traffic is not being added to totals"
                : "Monitoring every 2 seconds · Closing the window keeps NetPulse in the system tray");
        String saveError = tracker.saveError();
        footerSaved.setText(saveError.isBlank() ? "Saved locally" : "History could not be saved");
        throughputChart.addPoint(paused ? 0 : receiveBitsRate, paused ? 0 : sentBitsRate);
        if (trayPauseItem != null) trayPauseItem.setLabel(paused ? "Resume tracking" : "Pause tracking");
    }

    private void setError(String message) {
        boolean visible = message != null && !message.isBlank();
        errorBanner.setText(visible ? message : "");
        errorBanner.setVisible(visible);
        if (visible) {
            stateDot.setForeground(new Color(255, 171, 102));
            stateLabel.setForeground(new Color(255, 171, 102));
            stateLabel.setText("RECONNECTING");
        }
        root.revalidate();
    }

    private void togglePause() {
        paused = !paused;
        pauseButton.setText(paused ? "Resume tracking" : "Pause tracking");
        render();
        poll();
    }

    private void confirmReset() {
        int answer = JOptionPane.showConfirmDialog(this,
                "Reset all tracked received and sent totals, including the daily history?\n\nWindows' adapter counters are not changed.",
                "Reset tracked data", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) return;
        if (inFlight) {
            JOptionPane.showMessageDialog(this, "NetPulse is reading the current adapter counters. Try again in a moment.",
                    "Please wait", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        inFlight = true;
        new SwingWorker<List<NetworkSample>, Void>() {
            @Override protected List<NetworkSample> doInBackground() throws Exception { return reader.read(); }
            @Override protected void done() {
                inFlight = false;
                try {
                    List<NetworkSample> latest = get();
                    tracker.update(latest, true);
                    tracker.reset(latest);
                    currentSamples.clear();
                    currentSamples.addAll(latest);
                    latestDeltas.clear();
                    throughputChart.clear();
                    setError("");
                    render();
                } catch (Exception exception) {
                    setError("Could not read the current counters, so tracked data was not reset.");
                }
            }
        }.execute();
    }

    private void exportCsv() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export NetPulse usage history");
        chooser.setSelectedFile(new java.io.File("NetPulse-usage.csv"));
        chooser.setFileFilter(new FileNameExtensionFilter("CSV files (*.csv)", "csv"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        java.io.File file = chooser.getSelectedFile();
        if (!file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".csv")) {
            file = new java.io.File(file.getParentFile(), file.getName() + ".csv");
        }
        try {
            StringBuilder csv = new StringBuilder("Date,Adapter,Received (bytes),Sent (bytes),Total (bits)\r\n");
            for (UsageTracker.ExportRow row : tracker.exportRows(selectedId)) {
                csv.append(csvCell(row.date())).append(',')
                        .append(csvCell(row.adapter())).append(',')
                        .append(row.receivedBytes()).append(',')
                        .append(row.sentBytes()).append(',')
                        .append(BigInteger.valueOf(row.receivedBytes()).add(BigInteger.valueOf(row.sentBytes())).multiply(BigInteger.valueOf(8)))
                        .append("\r\n");
            }
            Files.writeString(file.toPath(), csv, StandardCharsets.UTF_8);
            JOptionPane.showMessageDialog(this, "Usage history exported to:\n" + file.getAbsolutePath(),
                    "Export complete", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException exception) {
            JOptionPane.showMessageDialog(this, "The CSV file could not be saved.\n" + exception.getMessage(),
                    "Export failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static String csvCell(String text) {
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private void installTray() {
        if (!SystemTray.isSupported()) return;
        try {
            PopupMenu menu = new PopupMenu();
            MenuItem open = new MenuItem("Open NetPulse");
            open.addActionListener(event -> showWindow());
            trayPauseItem = new MenuItem("Pause tracking");
            trayPauseItem.addActionListener(event -> togglePause());
            MenuItem exit = new MenuItem("Exit NetPulse");
            exit.addActionListener(event -> exitApp());
            menu.add(open);
            menu.add(trayPauseItem);
            menu.addSeparator();
            menu.add(exit);
            trayIcon = new TrayIcon(createIcon(32), "NetPulse · network usage", menu);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(event -> showWindow());
            SystemTray.getSystemTray().add(trayIcon);
        } catch (Exception exception) {
            trayIcon = null;
            trayPauseItem = null;
        }
    }

    private void minimizeOrExit() {
        if (trayIcon != null) {
            setVisible(false);
            if (!trayNoticeShown) {
                trayNoticeShown = true;
                trayIcon.displayMessage("NetPulse is still tracking", "Open it from the notification area to view usage.", TrayIcon.MessageType.INFO);
            }
        } else {
            exitApp();
        }
    }

    private void showWindow() {
        SwingUtilities.invokeLater(() -> {
            setVisible(true);
            setExtendedState(JFrame.NORMAL);
            toFront();
            requestFocus();
        });
    }

    private void exitApp() {
        timer.stop();
        if (trayIcon != null) SystemTray.getSystemTray().remove(trayIcon);
        dispose();
        System.exit(0);
    }

    private static Image createIcon(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(29, 47, 63));
        g.fillRoundRect(1, 1, size - 2, size - 2, size / 4, size / 4);
        g.setColor(TEAL);
        g.setStroke(new BasicStroke(Math.max(2f, size / 17f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int x0 = size / 5, y0 = size * 3 / 5;
        int x1 = size * 2 / 5, y1 = size * 2 / 5;
        int x2 = size * 3 / 5, y2 = size * 11 / 20;
        int x3 = size * 4 / 5, y3 = size / 4;
        g.drawPolyline(new int[]{x0, x1, x2, x3}, new int[]{y0, y1, y2, y3}, 4);
        g.setColor(new Color(151, 126, 255));
        g.drawLine(size / 5, size * 3 / 4, size * 4 / 5, size * 3 / 4);
        g.dispose();
        return image;
    }

    private static final class AdapterChoice {
        final String id;
        final String label;
        AdapterChoice(String id, String label) { this.id = id; this.label = label; }
        boolean idEquals(AdapterChoice other) {
            return id == null ? other.id == null : id.equals(other.id);
        }
        @Override public String toString() { return label; }
    }

    private static final class MetricCard extends JPanel {
        private final JLabel title = new JLabel();
        private final JLabel value = new JLabel("0 bit");
        private final JLabel bytes = new JLabel("0 B");
        private final JLabel rate = new JLabel("● 0 bit/s");

        MetricCard(String initialTitle, Color accent) {
            setBackground(SURFACE);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(BORDER, 1, true), new EmptyBorder(15, 17, 13, 17)));
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            title.setText(initialTitle);
            title.setFont(new Font("Segoe UI", Font.BOLD, 10));
            title.setForeground(MUTED);
            value.setFont(new Font("Segoe UI", Font.BOLD, 26));
            value.setForeground(accent);
            bytes.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            bytes.setForeground(MUTED);
            rate.setFont(new Font("Segoe UI", Font.BOLD, 10));
            rate.setForeground(accent);
            add(title);
            add(Box.createVerticalStrut(14));
            add(value);
            add(Box.createVerticalStrut(4));
            add(bytes);
            add(Box.createVerticalGlue());
            add(rate);
        }

        void setTitle(String text) { title.setText(text); }
        void setValue(String bits, String byteText, String speed) {
            value.setText(bits);
            bytes.setText(byteText + " transferred");
            rate.setText("●  " + speed + " now");
        }
    }
}