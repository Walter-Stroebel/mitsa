/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.mitsa;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import nl.infcomtec.jacksonwrap.JSON;

/**
 * Fetches the curated list of known MITSA-compatible apps, tried first
 * from MITSA's own GitHub repo (a project author joins by PR-ing
 * themselves into participants.json there — no backend, no account,
 * just a GitHub raw file, same trust model as the rest of MITSA), then
 * falling back to the copy bundled inside this jar if that fetch fails
 * for any reason (network, 404, repo not pushed yet).
 */
public class ParticipantsFetcher {

    private static final String RAW_URL
            = "https://raw.githubusercontent.com/Walter-Stroebel/mitsa/main/participants.json";

    private static class ParticipantsList {

        public List<Participant> participants = new ArrayList<Participant>();
    }

    public List<Participant> fetch() {
        List<Participant> remote = tryFetchRemote();
        if (remote != null) {
            return remote;
        }
        return fetchBundled();
    }

    private List<Participant> tryFetchRemote() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(RAW_URL).openConnection();
            conn.setRequestMethod("GET");
            int status = conn.getResponseCode();
            if (status != 200) {
                return null;
            }
            String body;
            try (InputStream in = conn.getInputStream()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            conn.disconnect();
            ParticipantsList list = JSON.readValue(body, ParticipantsList.class);
            return list == null ? null : list.participants;
        } catch (IOException ex) {
            return null;
        }
    }

    private List<Participant> fetchBundled() {
        try (InputStream in = getClass().getResourceAsStream("/nl/infcomtec/mitsa/participants.json")) {
            if (in == null) {
                return new ArrayList<Participant>();
            }
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            ParticipantsList list = JSON.readValue(body, ParticipantsList.class);
            return list == null ? new ArrayList<Participant>() : list.participants;
        } catch (IOException ex) {
            return new ArrayList<Participant>();
        }
    }
}
