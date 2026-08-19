/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.mitsa.tray;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import nl.infcomtec.mitsa.AppEntry;
import nl.infcomtec.mitsa.AppRegistry;
import nl.infcomtec.mitsa.GitHubReleaseFetcher;
import nl.infcomtec.mitsa.JarCache;
import nl.infcomtec.mitsa.MitsaPaths;
import nl.infcomtec.mitsa.ProcessLauncher;
import nl.infcomtec.mitsa.tray.dbus.DBusConnection;
import nl.infcomtec.mitsa.tray.dbus.StatusNotifierItem;

/**
 * One long-lived tray process for all registered MITSA apps, replacing
 * N separate per-app tray icons. Each menu click launches the app as a
 * separate OS process (see ProcessLauncher) — never in-process, to avoid
 * multiple Swing EDTs/static state colliding in one JVM.
 */
public class MitsaTray {

    private static FileLock lock;
    private static FileChannel lockChannel;
    private TrayIcon trayIcon;
    private TrayIconPainter painter;
    private Dimension trayIconSize;
    private volatile boolean usingDBusTray;

    public static void main(String[] args) throws Exception {
        if (!acquireSingleInstanceLock()) {
            System.err.println("MITSA tray is already running.");
            System.exit(1);
        }
        try {
            javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // stay on Swing's cross-platform default rather than fail the whole tray over this
        }
        MitsaTray tray = new MitsaTray();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                try {
                    tray.createAndShowGUI();
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(null, "Failed to start MITSA tray: " + ex.getMessage());
                    System.exit(1);
                }
            }
        });
        tray.startControlChannel();
        if (tray.usingDBusTray) {
            // The D-Bus backend has no AWT peer to keep a non-daemon event
            // thread alive (unlike the SystemTray path, where adding a
            // TrayIcon implicitly starts one) - park the main thread instead.
            Thread.currentThread().join();
        }
    }

    private static boolean acquireSingleInstanceLock() throws IOException {
        File f = MitsaPaths.lockFile();
        lockChannel = new RandomAccessFile(f, "rw").getChannel();
        lock = lockChannel.tryLock();
        return lock != null;
    }

    /**
     * Fixed loopback port a running tray listens on for short text commands
     * from another MITSA invocation on the same machine - deliberately
     * cross-platform (plain UDP, not D-Bus, which only exists on Linux).
     * FileLock above only detects a second instance starting; this is the
     * separate, different job of talking to the one already running - e.g.
     * "mitsa update" telling a live tray to reload its app list, or one day
     * "MITSA just released a new version of itself" telling it to relaunch.
     */
    private static final int CONTROL_PORT = 0x4D1A; // "MI" in hex-ish, arbitrary fixed port

    public static final String COMMAND_REFRESH = "refresh";

    /** Sends a short command to an already-running tray's control channel, if one is listening. Silently does nothing if no tray is up - callers don't need to know whether a tray happens to be running. */
    public static void notifyRunningTray(String command) {
        try (DatagramSocket sock = new DatagramSocket()) {
            byte[] buf = command.getBytes();
            DatagramPacket pkt = new DatagramPacket(buf, buf.length, InetAddress.getLoopbackAddress(), CONTROL_PORT);
            sock.send(pkt);
        } catch (IOException ignored) {
            // no tray listening, or transient local network stack issue - not worth surfacing
        }
    }

    private void startControlChannel() {
        Thread listener = new Thread(new ControlChannelListener(), "mitsa-tray-control");
        listener.setDaemon(true);
        listener.start();
    }

    private class ControlChannelListener implements Runnable {

        @Override
        public void run() {
            try (DatagramSocket sock = new DatagramSocket(CONTROL_PORT, InetAddress.getLoopbackAddress())) {
                byte[] buf = new byte[64];
                while (true) {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    sock.receive(pkt);
                    String command = new String(pkt.getData(), 0, pkt.getLength());
                    handleCommand(command);
                }
            } catch (IOException ex) {
                // another process already holds the control port (a second tray
                // instance racing before its own FileLock check, or something
                // unrelated squatting the port) - not fatal, refresh just won't work
            }
        }

        private void handleCommand(String command) {
            if (COMMAND_REFRESH.equals(command) && !usingDBusTray) {
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            buildMenu();
                        } catch (IOException ignored) {
                        }
                    }
                });
            }
            // the D-Bus backend already rebuilds its menu fresh on every click,
            // so "refresh" is a no-op there - nothing stale to replace
        }
    }

    private void createAndShowGUI() throws IOException {
        if (SystemTray.isSupported()) {
            createAwtTray();
            return;
        }
        if (createDBusTray()) {
            return;
        }
        JOptionPane.showMessageDialog(null, "No access to SystemTray");
        System.exit(1);
    }

    private void createAwtTray() throws IOException {
        painter = new TrayIconPainter();
        trayIconSize = SystemTray.getSystemTray().getTrayIconSize();

        trayIcon = new TrayIcon(painter.paint(trayIconSize, new Color(0xF4F5F6)));
        trayIcon.setToolTip("MITSA");
        buildMenu();

        try {
            SystemTray.getSystemTray().add(trayIcon);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "Could not add tray icon: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Fallback for desktops where AWT's SystemTray reports unsupported
     * because it only speaks the legacy XEmbed tray protocol - Linux/Mint
     * Cinnamon among them, confirmed 2026-08-19. Registers a real icon via
     * org.kde.StatusNotifierItem over D-Bus instead. Returns false (not an
     * exception) if no session bus is reachable either, so the caller falls
     * through to the same "No access to SystemTray" message as before.
     */
    private boolean createDBusTray() throws IOException {
        DBusConnection conn = DBusConnection.connectSessionBus();
        if (conn == null) {
            return false;
        }
        if (!conn.nameHasOwner("org.kde.StatusNotifierWatcher")) {
            conn.close();
            return false;
        }
        painter = new TrayIconPainter();
        trayIconSize = new Dimension(24, 24);

        StatusNotifierItem sni = new StatusNotifierItem(conn, "mitsa", "MITSA");
        sni.setIcon(painter.paint(trayIconSize, new Color(0xF4F5F6)), trayIconSize, new Color(0xF4F5F6));
        sni.setActivateListener(new ActivateHandler());
        sni.register();
        usingDBusTray = true;
        return true;
    }

    private class ActivateHandler implements StatusNotifierItem.ActivateListener {

        @Override
        public void onActivate(int x, int y) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    showDBusMenu(x, y);
                }
            });
        }
    }

    private void showDBusMenu(int x, int y) {
        try {
            JPopupMenu menu = new JPopupMenu();
            AppRegistry reg = AppRegistry.load();
            for (final AppEntry app : reg.apps) {
                JMenuItem item = new JMenuItem(app.id);
                item.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        launch(app);
                    }
                });
                menu.add(item);
            }
            menu.addSeparator();

            JMenuItem updateAll = new JMenuItem("Update All");
            updateAll.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    updateAll();
                }
            });
            menu.add(updateAll);

            JMenuItem exitItem = new JMenuItem("Exit");
            exitItem.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    System.exit(0);
                }
            });
            menu.add(exitItem);

            menu.setInvoker(menu);
            menu.setLocation(x, y);
            menu.setVisible(true);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(null, "Failed to build tray menu: " + ex.getMessage());
        }
    }

    private void buildMenu() throws IOException {
        PopupMenu popup = new PopupMenu();
        AppRegistry reg = AppRegistry.load();
        for (final AppEntry app : reg.apps) {
            MenuItem item = new MenuItem(app.id);
            item.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    launch(app);
                }
            });
            popup.add(item);
        }
        popup.addSeparator();

        MenuItem updateAll = new MenuItem("Update All");
        updateAll.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                updateAll();
            }
        });
        popup.add(updateAll);

        MenuItem exitItem = new MenuItem("Exit");
        exitItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                System.exit(0);
            }
        });
        popup.add(exitItem);

        trayIcon.setPopupMenu(popup);
    }

    private void launch(AppEntry app) {
        try {
            JarCache cache = new JarCache(app.id);
            File jar = cache.resolveCurrent();
            if (jar == null) {
                JOptionPane.showMessageDialog(null, "No cached jar for '" + app.id + "'. Run: mitsa update " + app.id);
                return;
            }
            ProcessLauncher.launch(jar, new String[0]);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(null, "Failed to launch " + app.id + ": " + ex.getMessage());
        }
    }

    private void updateAll() {
        try {
            AppRegistry reg = AppRegistry.load();
            GitHubReleaseFetcher fetcher = new GitHubReleaseFetcher();
            StringBuilder result = new StringBuilder();
            for (AppEntry app : reg.apps) {
                try {
                    String tag = fetcher.updateToLatest(app);
                    result.append(app.id).append(" -> ").append(tag).append("\n");
                } catch (IOException ex) {
                    result.append(app.id).append(" FAILED: ").append(ex.getMessage()).append("\n");
                }
            }
            JOptionPane.showMessageDialog(null, result.toString());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(null, "Update failed: " + ex.getMessage());
        }
    }
}
