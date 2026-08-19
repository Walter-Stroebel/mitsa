/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.mitsa;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Layout and resolution for one app's cached jars under
 * {@code <configDir>/jars/<appId>/}. A plain "current.txt" pointer file
 * records which cached jar filename is current, rather than a symlink,
 * for portability across OSes.
 */
public class JarCache {

    private final String appId;

    public JarCache(String appId) {
        this.appId = appId;
    }

    private File currentPointer() {
        return new File(MitsaPaths.appJarsDir(appId), "current.txt");
    }

    public File resolveCurrent() throws IOException {
        File pointer = currentPointer();
        if (!pointer.exists()) {
            return null;
        }
        String fileName = new String(Files.readAllBytes(pointer.toPath()), StandardCharsets.UTF_8).trim();
        File jar = new File(MitsaPaths.appJarsDir(appId), fileName);
        return jar.exists() ? jar : null;
    }

    public String currentVersion() throws IOException {
        File pointer = currentPointer();
        if (!pointer.exists()) {
            return null;
        }
        String fileName = new String(Files.readAllBytes(pointer.toPath()), StandardCharsets.UTF_8).trim();
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    public File jarFileFor(String version) {
        return new File(MitsaPaths.appJarsDir(appId), version + ".jar");
    }

    public void setCurrent(String version) throws IOException {
        Files.write(currentPointer().toPath(), (version + ".jar").getBytes(StandardCharsets.UTF_8));
    }
}
