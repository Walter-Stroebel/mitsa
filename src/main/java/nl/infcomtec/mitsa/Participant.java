/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.mitsa;

/**
 * One entry in MITSA's curated participants.json — a known
 * MITSA-compatible app a first-run user can pick during onboarding.
 */
public class Participant {

    public String id;
    public String githubOwner;
    public String githubRepo;
    public String assetNamePattern;
    public String description;

    public Participant() {
    }

    public AppEntry toAppEntry() {
        return new AppEntry(id, githubOwner, githubRepo, assetNamePattern);
    }
}
