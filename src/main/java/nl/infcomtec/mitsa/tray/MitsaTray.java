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
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import nl.infcomtec.mitsa.AppEntry;
import nl.infcomtec.mitsa.AppRegistry;
import nl.infcomtec.mitsa.GitHubReleaseFetcher;
import nl.infcomtec.mitsa.JarCache;
import nl.infcomtec.mitsa.MitsaPaths;
import nl.infcomtec.mitsa.ProcessLauncher;

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

    public static void main(String[] args) throws Exception {
        if (!acquireSingleInstanceLock()) {
            System.err.println("MITSA tray is already running.");
            System.exit(1);
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
    }

    private static boolean acquireSingleInstanceLock() throws IOException {
        File f = MitsaPaths.lockFile();
        lockChannel = new RandomAccessFile(f, "rw").getChannel();
        lock = lockChannel.tryLock();
        return lock != null;
    }

    private void createAndShowGUI() throws IOException {
        if (!SystemTray.isSupported()) {
            JOptionPane.showMessageDialog(null, "No access to SystemTray");
            System.exit(1);
        }
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
