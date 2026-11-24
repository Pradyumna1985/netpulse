package com.netpulse;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.JComponent;

final class ThroughputChart extends JComponent {
    private static final Color RECEIVED = new Color(67, 211, 173);
    private static final Color SENT = new Color(151, 126, 255);
    private final ArrayDeque<double[]> points = new ArrayDeque<>();

    ThroughputChart() {
        setOpaque(false);
        setPreferredSize(new java.awt.Dimension(500, 205));
        setMinimumSize(new java.awt.Dimension(250, 160));
    }

    void addPoint(double receivedBitsPerSecond, double sentBitsPerSecond) {
        points.addLast(new double[]{Math.max(0, receivedBitsPerSecond), Math.max(0, sentBitsPerSecond)});
        while (points.size() > 60) points.removeFirst();
        repaint();
    }

    void clear() {
        points.clear();
        repaint();
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int left = 62, right = 15, top = 14, bottom = 27;
        int plotW = Math.max(1, getWidth() - left - right);
        int plotH = Math.max(1, getHeight() - top - bottom);
        List<double[]> data = new ArrayList<>(points);
        double maximum = 1;
        for (double[] p : data) maximum = Math.max(maximum, Math.max(p[0], p[1]));
        maximum *= 1.18;

        g.setFont(getFont().deriveFont(10f));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(new Color(61, 73, 91));
        g.setStroke(new BasicStroke(1f));
        for (int i = 0; i <= 4; i++) {
            int y = top + plotH - i * plotH / 4;
            g.drawLine(left, y, left + plotW, y);
            String label = UsageFormat.rate(maximum * i / 4);
            g.setColor(new Color(139, 153, 173));
            g.drawString(label, Math.max(0, left - fm.stringWidth(label) - 8), y + 4);
            g.setColor(new Color(48, 59, 75));
        }

        if (data.size() > 1) {
            drawSeries(g, data, 0, maximum, left, top, plotW, plotH, RECEIVED);
            drawSeries(g, data, 1, maximum, left, top, plotW, plotH, SENT);
        }
        g.dispose();
    }

    private static void drawSeries(Graphics2D g, List<double[]> data, int column, double maximum,
                                   int left, int top, int plotW, int plotH, Color color) {
        Path2D path = new Path2D.Double();
        for (int i = 0; i < data.size(); i++) {
            double x = left + i * (double) plotW / (data.size() - 1);
            double y = top + plotH - Math.min(1, data.get(i)[column] / maximum) * plotH;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        g.setColor(color);
        g.setStroke(new BasicStroke(2.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(path);
    }
}

final class DailyUsageChart extends JComponent {
    private static final Color RECEIVED = new Color(67, 211, 173);
    private static final Color SENT = new Color(151, 126, 255);
    private static final DateTimeFormatter LABEL = DateTimeFormatter.ofPattern("MMM d");
    private Map<LocalDate, UsageTracker.Totals> data = Map.of();

    DailyUsageChart() {
        setOpaque(false);
        setPreferredSize(new java.awt.Dimension(800, 170));
        setMinimumSize(new java.awt.Dimension(300, 140));
    }

    void setData(Map<LocalDate, UsageTracker.Totals> value) {
        data = value;
        repaint();
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int left = 54, right = 12, top = 10, bottom = 26;
        int plotW = Math.max(1, getWidth() - left - right);
        int plotH = Math.max(1, getHeight() - top - bottom);
        long maximum = 1;
        for (UsageTracker.Totals item : data.values()) {
            maximum = Math.max(maximum, UsageTracker.safeAdd(item.receivedBytes(), item.sentBytes()));
        }
        maximum = maximum > Long.MAX_VALUE / 8 ? Long.MAX_VALUE : maximum * 8;
        double maxBits = maximum * 1.12;

        g.setFont(getFont().deriveFont(10f));
        FontMetrics fm = g.getFontMetrics();
        g.setStroke(new BasicStroke(1f));
        for (int i = 0; i <= 2; i++) {
            int y = top + plotH - i * plotH / 2;
            g.setColor(new Color(48, 59, 75));
            g.drawLine(left, y, left + plotW, y);
            String label = UsageFormat.bits(maxBits * i / 2);
            g.setColor(new Color(139, 153, 173));
            g.drawString(label, Math.max(0, left - fm.stringWidth(label) - 8), y + 4);
        }

        double slot = plotW / 7.0;
        int barW = Math.max(8, Math.min(42, (int) (slot * 0.48)));
        LocalDate today = LocalDate.now();
        for (int i = 0; i < 7; i++) {
            LocalDate date = today.minusDays(6L - i);
            UsageTracker.Totals item = data.getOrDefault(date, new UsageTracker.Totals(0, 0));
            double rxBits = item.receivedBytes() * 8.0;
            double txBits = item.sentBytes() * 8.0;
            int rxH = (int) Math.round(rxBits / maxBits * plotH);
            int txH = (int) Math.round(txBits / maxBits * plotH);
            int x = left + (int) (i * slot + (slot - barW) / 2);
            int baseline = top + plotH;
            int rxY = baseline - rxH;
            int txY = rxY - txH;
            g.setColor(RECEIVED);
            g.fillRoundRect(x, rxY, barW, Math.max(rxH, rxBits > 0 ? 2 : 0), 5, 5);
            g.setColor(SENT);
            g.fillRoundRect(x, txY, barW, Math.max(txH, txBits > 0 ? 2 : 0), 5, 5);
            String label = date.format(LABEL);
            g.setColor(new Color(139, 153, 173));
            g.drawString(label, (int) (left + i * slot + (slot - fm.stringWidth(label)) / 2), getHeight() - 5);
        }
        g.dispose();
    }
}

final class UsageFormat {
    private static final String[] BIT_UNITS = {"bit", "kbit", "Mbit", "Gbit", "Tbit", "Pbit"};
    private static final String[] BYTE_UNITS = {"B", "KB", "MB", "GB", "TB", "PB"};

    static String bits(double bits) { return scaled(bits, BIT_UNITS); }
    static String bytes(double bytes) { return scaled(bytes, BYTE_UNITS); }
    static String rate(double bitsPerSecond) {
        return scaled(bitsPerSecond, new String[]{"bit/s", "kbit/s", "Mbit/s", "Gbit/s", "Tbit/s"});
    }

    static String paired(long bytes) {
        return bits(bytes * 8.0) + "  ·  " + UsageFormat.bytes(bytes);
    }

    private static String scaled(double value, String[] units) {
        double amount = Math.max(0, value);
        int unit = 0;
        while (amount >= 1000 && unit < units.length - 1) {
            amount /= 1000;
            unit++;
        }
        String format = amount >= 100 ? "%.0f" : amount >= 10 ? "%.1f" : "%.2f";
        return String.format(java.util.Locale.US, format, amount) + " " + units[unit];
    }

    private UsageFormat() {}
}