/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.mitsa;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Spawns an app's jar as a separate, detached OS process. Never call an
 * app's main() in-process: multiple Swing GUI apps sharing one JVM would
 * collide on the EDT and on static state, and one app crashing would take
 * down whichever MITSA process launched it.
 */
public class ProcessLauncher {

    public static Process launch(File jar, String[] extraArgs) throws IOException {
        List<String> cmd = new ArrayList<String>();
        cmd.add("java");
        cmd.add("-jar");
        cmd.add(jar.getAbsolutePath());
        for (String a : extraArgs) {
            cmd.add(a);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        return pb.start();
    }
}
