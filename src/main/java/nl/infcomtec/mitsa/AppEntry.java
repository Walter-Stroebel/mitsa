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

    public AppEntry() {
    }

    public AppEntry(String id, String githubOwner, String githubRepo, String assetNamePattern) {
        this.id = id;
        this.githubOwner = githubOwner;
        this.githubRepo = githubRepo;
        this.assetNamePattern = assetNamePattern;
    }
}
