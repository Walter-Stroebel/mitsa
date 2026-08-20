/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.mitsa;

/**
 * One registered app in MITSA's apps.json.
 */
public class AppEntry {

    public String id;
    public String githubOwner;
    public String githubRepo;
    public String mainClassHint;
    public String assetNamePattern;
    /**
     * Extra args always passed to this app's jar on launch (e.g.
     * {@code --config-file path}), for a variant of an app registered
     * under its own id but sharing the same jar/lineage as another
     * entry — see {@code MitsaCli}'s {@code add ... --like} form. Null
     * or empty for a plain app with no fixed launch args.
     */
    public String[] launchArgs;

    public AppEntry() {
    }

    public AppEntry(String id, String githubOwner, String githubRepo, String assetNamePattern) {
        this.id = id;
        this.githubOwner = githubOwner;
        this.githubRepo = githubRepo;
        this.assetNamePattern = assetNamePattern;
    }

    public AppEntry(String id, String githubOwner, String githubRepo, String assetNamePattern, String[] launchArgs) {
        this(id, githubOwner, githubRepo, assetNamePattern);
        this.launchArgs = launchArgs;
    }
}
