/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.mitsa;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.regex.Pattern;
import nl.infcomtec.jacksonwrap.JSON;

/**
 * Checks GitHub's "latest release" for one app and refreshes the local
 * jar cache if a new tag with a matching asset is found. Plain
 * HttpURLConnection, no HTTP/2 client machinery needed for a handful of
 * GET requests. Unauthenticated REST calls only — fine for v1's
 * public-repo, low-volume use.
 */
public class GitHubReleaseFetcher {

    private static String readBody(HttpURLConnection conn) throws IOException {
        try (InputStream in = conn.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Hosts release assets are allowed to redirect to. GitHub's own API
     * always hands off asset downloads from api.github.com to its own
     * blob storage (objects.githubusercontent.com) with a signed,
     * time-limited URL — this is normal GitHub release-asset serving,
     * not a hijack. HttpURLConnection.setInstanceFollowRedirects does
     * not follow this cross-host redirect on its own, so it is followed
     * manually below, but only to hosts on this list — a redirect
     * anywhere else is treated as suspicious and rejected.
     */
    private static final java.util.Set<String> ALLOWED_REDIRECT_HOSTS = java.util.Set.of(
            "objects.githubusercontent.com", "github-releases.githubusercontent.com",
            "github-cloud.githubusercontent.com", "release-assets.githubusercontent.com");

    private static void downloadFollowingRedirects(String url, File target, int hopsLeft) throws IOException {
        if (hopsLeft <= 0) {
            throw new IOException("Too many redirects downloading " + url);
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(false);
        int status = conn.getResponseCode();
        if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
            String location = conn.getHeaderField("Location");
            conn.disconnect();
            if (location == null) {
                throw new IOException("Redirect with no Location header from " + url);
            }
            String host = new URL(location).getHost();
            if (!ALLOWED_REDIRECT_HOSTS.contains(host)) {
                throw new IOException("Refusing redirect to untrusted host: " + host);
            }
            downloadFollowingRedirects(location, target, hopsLeft - 1);
            return;
        }
        if (status != 200) {
            throw new IOException("Download of " + url + " returned " + status);
        }
        try (InputStream in = conn.getInputStream()) {
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        conn.disconnect();
    }

    /**
     * @return the tag name that ended up current, or null if nothing matched.
     */
    public String updateToLatest(AppEntry app) throws IOException {
        LatestRelease latest = fetchLatest(app.githubOwner, app.githubRepo, app.assetNamePattern);

        JarCache cache = new JarCache(app.id);
        String currentVersion = cache.currentVersion();
        if (latest.tag.equals(currentVersion)) {
            return latest.tag;
        }

        File target = cache.jarFileFor(latest.tag);
        downloadFollowingRedirects(latest.assetUrl, target, 5);

        cache.setCurrent(latest.tag);
        return latest.tag;
    }

    /**
     * Same "latest release" lookup as {@link #updateToLatest}, but for
     * MITSA's own repo rather than a managed {@link AppEntry} — used by
     * {@code mitsa self-update}, which downloads straight to a caller-given
     * target (its own fixed jar path) instead of a per-app JarCache.
     *
     * @return the tag name of the release downloaded.
     */
    public String updateSelf(File target) throws IOException {
        LatestRelease latest = fetchLatest("Walter-Stroebel", "mitsa", "mitsa-jar-with-dependencies\\.jar");
        downloadFollowingRedirects(latest.assetUrl, target, 5);
        return latest.tag;
    }

    private static final class LatestRelease {

        final String tag;
        final String assetUrl;

        LatestRelease(String tag, String assetUrl) {
            this.tag = tag;
            this.assetUrl = assetUrl;
        }
    }

    private LatestRelease fetchLatest(String githubOwner, String githubRepo, String assetNamePattern) throws IOException {
        String url = "https://api.github.com/repos/" + githubOwner + "/" + githubRepo + "/releases/latest";
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestMethod("GET");
        int status = conn.getResponseCode();
        if (status != 200) {
            throw new IOException("GitHub API returned " + status + " for " + url);
        }
        String body = readBody(conn);
        conn.disconnect();

        JsonNode root = JSON.getMapper().readTree(body);
        String tag = root.path("tag_name").asText();
        JsonNode assets = root.path("assets");
        Pattern pattern = Pattern.compile(assetNamePattern);
        String assetUrl = null;
        Iterator<JsonNode> it = assets.elements();
        while (it.hasNext()) {
            JsonNode asset = it.next();
            String name = asset.path("name").asText();
            if (pattern.matcher(name).matches()) {
                assetUrl = asset.path("browser_download_url").asText();
                break;
            }
        }
        if (assetUrl == null) {
            throw new IOException("No asset in latest release of " + githubOwner + "/" + githubRepo
                    + " matches pattern " + assetNamePattern);
        }
        return new LatestRelease(tag, assetUrl);
    }
}
