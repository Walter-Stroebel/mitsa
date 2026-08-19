/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.mitsa;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Writes the three one-line per-OS launch shims for a newly registered
 * app. Each shim just delegates to "mitsa run &lt;id&gt;" — no version
 * literal is ever written into a shim.
 */
public class ShimWriter {

    public static void writeShims(AppEntry app) throws IOException {
        File dir = new File(MitsaPaths.configDir(), "shims/" + app.id);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Could not create shim directory " + dir);
        }

        File sh = new File(dir, app.id + ".sh");
        Files.write(sh.toPath(), ("#!/bin/bash\nexec mitsa run " + app.id + " \"$@\"\n")
                .getBytes(StandardCharsets.UTF_8));
        Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwxr-xr-x");
        try {
            Files.setPosixFilePermissions(sh.toPath(), perms);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem (Windows) — not applicable there.
        }

        File command = new File(dir, app.id + ".command");
        Files.write(command.toPath(), ("#!/bin/bash\nexec mitsa run " + app.id + " \"$@\"\n")
                .getBytes(StandardCharsets.UTF_8));
        try {
            Files.setPosixFilePermissions(command.toPath(), perms);
        } catch (UnsupportedOperationException ignored) {
        }

        File bat = new File(dir, app.id + ".bat");
        Files.write(bat.toPath(), ("@echo off\r\nmitsa run " + app.id + " %*\r\n")
                .getBytes(StandardCharsets.UTF_8));

        System.out.println("Wrote shims to " + dir);
    }
}
