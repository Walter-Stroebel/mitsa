/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.mitsa;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import nl.infcomtec.mitsa.tray.MitsaTray;
import nl.infcomtec.mitsa.tray.OnboardingDialog;

/**
 * Entry point: run/update/list/add/tray verbs.
 */
public class MitsaCli {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            AppRegistry reg = AppRegistry.load();
            if (reg.apps.isEmpty()) {
                cmdOnboard();
                return;
            }
            printUsage();
            System.exit(1);
        }
        String verb = args[0];
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        if (verb.equals("run")) {
            cmdRun(rest);
        } else if (verb.equals("update")) {
            cmdUpdate(rest);
        } else if (verb.equals("list")) {
            cmdList();
        } else if (verb.equals("add")) {
            cmdAdd(rest);
        } else if (verb.equals("tray")) {
            MitsaTray.main(rest);
        } else {
            printUsage();
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.err.println("Usage: mitsa <run|update|list|add|tray> ...");
        System.err.println("  mitsa run <id> [args...]");
        System.err.println("  mitsa update [id]");
        System.err.println("  mitsa list");
        System.err.println("  mitsa add <id> <githubOwner> <githubRepo> <assetNamePattern>");
        System.err.println("  mitsa tray");
    }

    private static void cmdRun(String[] rest) throws IOException {
        if (rest.length < 1) {
            System.err.println("Usage: mitsa run <id> [args...]");
            System.exit(1);
        }
        String id = rest[0];
        String[] appArgs = Arrays.copyOfRange(rest, 1, rest.length);
        JarCache cache = new JarCache(id);
        File jar = cache.resolveCurrent();
        if (jar == null) {
            System.err.println("No cached jar for '" + id + "'. Run: mitsa update " + id);
            System.exit(1);
        }
        ProcessLauncher.launch(jar, appArgs);
    }

    private static void cmdUpdate(String[] rest) throws IOException, InterruptedException {
        AppRegistry reg = AppRegistry.load();
        if (rest.length == 1) {
            AppEntry app = reg.find(rest[0]);
            if (app == null) {
                System.err.println("No such app '" + rest[0] + "'");
                System.exit(1);
            }
            updateOne(app);
        } else {
            for (AppEntry app : reg.apps) {
                updateOne(app);
            }
        }
    }

    private static void updateOne(AppEntry app) throws IOException, InterruptedException {
        GitHubReleaseFetcher fetcher = new GitHubReleaseFetcher();
        String tag = fetcher.updateToLatest(app);
        System.out.println(app.id + " -> " + tag);
    }

    private static void cmdList() throws IOException {
        AppRegistry reg = AppRegistry.load();
        for (AppEntry app : reg.apps) {
            JarCache cache = new JarCache(app.id);
            String version = cache.currentVersion();
            System.out.println(app.id + "\t" + app.githubOwner + "/" + app.githubRepo
                    + "\t" + (version == null ? "(not cached)" : version));
        }
    }

    private static void cmdOnboard() throws IOException {
        System.out.println("Welcome to MITSA! No apps registered yet.");
        List<Participant> participants = new ParticipantsFetcher().fetch();
        if (participants.isEmpty()) {
            System.out.println("No known apps available right now. Use 'mitsa add' to register one manually.");
            return;
        }

        List<Participant> chosen;
        if (GraphicsEnvironment.isHeadless()) {
            chosen = new OnboardingTextPicker().pick(participants);
        } else {
            chosen = OnboardingDialog.pick(participants);
        }

        if (chosen.isEmpty()) {
            System.out.println("Nothing selected. Run 'mitsa' again anytime to pick apps, or use 'mitsa add'.");
            return;
        }

        AppRegistry reg = AppRegistry.load();
        for (Participant p : chosen) {
            AppEntry app = p.toAppEntry();
            reg.apps.add(app);
            ShimWriter.writeShims(app);
        }
        reg.save();

        GitHubReleaseFetcher fetcher = new GitHubReleaseFetcher();
        for (Participant p : chosen) {
            try {
                String tag = fetcher.updateToLatest(reg.find(p.id));
                System.out.println(p.id + " -> " + tag);
            } catch (IOException ex) {
                System.err.println(p.id + " FAILED: " + ex.getMessage());
            }
        }
        System.out.println("Done. Run 'mitsa list' to see what's installed, or 'mitsa run <id>' to launch one.");
    }

    private static void cmdAdd(String[] rest) throws IOException {
        if (rest.length < 4) {
            System.err.println("Usage: mitsa add <id> <githubOwner> <githubRepo> <assetNamePattern>");
            System.exit(1);
        }
        AppRegistry reg = AppRegistry.load();
        if (reg.find(rest[0]) != null) {
            System.err.println("App '" + rest[0] + "' already registered");
            System.exit(1);
        }
        AppEntry app = new AppEntry(rest[0], rest[1], rest[2], rest[3]);
        reg.apps.add(app);
        reg.save();
        ShimWriter.writeShims(app);
        System.out.println("Registered " + app.id + ". Run: mitsa update " + app.id);
    }
}
