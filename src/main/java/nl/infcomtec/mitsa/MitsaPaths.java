/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.mitsa;

import java.io.File;

/**
 * Resolves MITSA's per-OS config root, following each platform's own
 * convention rather than a flat dotfile in $HOME.
 */
public class MitsaPaths {

    public static File configDir() {
        String os = System.getProperty("os.name").toLowerCase();
        File dir;
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            dir = new File(appData, "mitsa");
        } else if (os.contains("mac")) {
            dir = new File(System.getProperty("user.home"), "Library/Application Support/mitsa");
        } else {
            String xdg = System.getenv("XDG_CONFIG_HOME");
            File base = (xdg != null && !xdg.isEmpty())
                    ? new File(xdg) : new File(System.getProperty("user.home"), ".config");
            dir = new File(base, "mitsa");
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw new RuntimeException("Could not create config directory " + dir);
        }
        return dir;
    }

    public static File jarsDir() {
        File dir = new File(configDir(), "jars");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new RuntimeException("Could not create jars directory " + dir);
        }
        return dir;
    }

    public static File appJarsDir(String appId) {
        File dir = new File(jarsDir(), appId);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new RuntimeException("Could not create jars directory " + dir);
        }
        return dir;
    }

    public static File appsJsonFile() {
        return new File(configDir(), "apps.json");
    }

    public static File lockFile() {
        return new File(configDir(), "tray.lock");
    }
}
