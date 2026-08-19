/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.mitsa;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Headless fallback for the first-run onboarding picker: prints a
 * numbered list to stdout, reads a comma-separated selection (or "all",
 * or blank for none) from stdin.
 */
public class OnboardingTextPicker {

    public List<Participant> pick(List<Participant> participants) throws IOException {
        System.out.println("Known MITSA apps:");
        for (int i = 0; i < participants.size(); i++) {
            Participant p = participants.get(i);
            System.out.println("  " + (i + 1) + ") " + p.id + " - " + p.description);
        }
        System.out.println("Enter numbers to install (comma-separated), 'all', or blank for none:");

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String line = reader.readLine();
        List<Participant> chosen = new ArrayList<Participant>();
        if (line == null || line.trim().isEmpty()) {
            return chosen;
        }
        line = line.trim();
        if (line.equalsIgnoreCase("all")) {
            chosen.addAll(participants);
            return chosen;
        }
        for (String part : line.split(",")) {
            try {
                int idx = Integer.parseInt(part.trim()) - 1;
                if (idx >= 0 && idx < participants.size()) {
                    chosen.add(participants.get(idx));
                }
            } catch (NumberFormatException ex) {
                // ignore malformed entries
            }
        }
        return chosen;
    }
}
