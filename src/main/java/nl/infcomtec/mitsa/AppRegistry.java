/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.mitsa;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import nl.infcomtec.jacksonwrap.JSON;

/**
 * The set of apps MITSA knows about, backed by apps.json in the config
 * directory.
 */
public class AppRegistry {

    public List<AppEntry> apps = new ArrayList<AppEntry>();

    public static AppRegistry load() throws IOException {
        File f = MitsaPaths.appsJsonFile();
        if (!f.exists()) {
            return new AppRegistry();
        }
        String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        AppRegistry reg = JSON.readValue(json, AppRegistry.class);
        return reg == null ? new AppRegistry() : reg;
    }

    public void save() throws IOException {
        String json = JSON.writeValueAsPretty(this);
        Files.write(MitsaPaths.appsJsonFile().toPath(), json.getBytes(StandardCharsets.UTF_8));
    }

    public AppEntry find(String id) {
        for (AppEntry a : apps) {
            if (a.id.equals(id)) {
                return a;
            }
        }
        return null;
    }
}
