package com.netpulse;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public final class NetPulseApp {
    private NetPulseApp() {}

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new NetPulseWindow().setVisible(true));
    }
}