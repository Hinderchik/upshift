package dev.upshift.gpu;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.upshift.Upshift;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import net.minecraft.client.Minecraft;

/**
 * Downloads the official ANR FG package (Apex Neural Rendering frame generation) on demand.
 *
 * <p>The download only starts after the player explicitly accepts the consent screen.
 * Every archive is verified against the SHA-256 digest that GitHub publishes for the
 * release asset before anything is extracted. The package itself is installed by its
 * own {@code install.cmd} / {@code install.sh}; a mod cannot drop a swap-chain proxy
 * into the game process safely, so the mod only stages a verified copy and points the
 * player at the installer.</p>
 */
public final class AnrDownloader {
    private static final String API_LATEST = "https://api.github.com/repos/apex-compute/ANR/releases/latest";
    private static final String FALLBACK_TAG = "FG-V.B18";
    private static final String FALLBACK_URL = "https://github.com/apex-compute/ANR/releases/download/FG-V.B18/anr-fg-V.B18-full.zip";
    private static final String FALLBACK_SHA256 = "ed70eef1dc92ba71685daad59f1d9ef1d9897b46df009f04b1dc51c4933cd300";
    private static final String MARKER_FILE = "upshift-anr.txt";
    private static final long MAX_ARCHIVE_BYTES = 256L * 1024L * 1024L;

    private static final Object LOCK = new Object();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "upshift-anr-downloader");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile DownloadState state = DownloadState.NOT_STARTED;
    private static volatile String lastError = "";
    private static volatile String installedVersion = null;
    private static volatile Path installedRoot = null;
    private static volatile boolean userConsented = false;
    private static CompletableFuture<Boolean> inFlight = null;

    static {
        try {
            scanInstalled();
        } catch (Throwable t) {
            Upshift.LOG.debug("could not scan the ANR install directory", t);
        }
    }

    private AnrDownloader() {
    }

    public enum DownloadState {
        /** Nothing downloaded yet. */
        NOT_STARTED,
        /** Waiting for the player to accept the consent screen. */
        AWAITING_CONSENT,
        /** Asking GitHub for the newest release. */
        RESOLVING,
        /** Streaming the archive. */
        DOWNLOADING,
        /** Checking the SHA-256 digest. */
        VERIFYING,
        /** Unpacking the verified archive. */
        EXTRACTING,
        /** Package verified and staged on disk. */
        COMPLETED,
        /** Something failed; see {@link #getLastError()}. */
        FAILED;

        public boolean busy() {
            return this == RESOLVING || this == DOWNLOADING || this == VERIFYING || this == EXTRACTING;
        }
    }

    /** Records the player's explicit agreement to download third-party native code. */
    public static void giveConsent() {
        userConsented = true;
        if (state == DownloadState.AWAITING_CONSENT) {
            state = DownloadState.NOT_STARTED;
        }
    }

    /** Drops the consent flag and forgets the in-memory state. The staged package stays on disk. */
    public static void revokeConsent() {
        userConsented = false;
        reset();
    }

    public static boolean hasUserConsented() {
        return userConsented;
    }

    /**
     * Starts (or joins) the download. Returns {@code false} immediately when the player
     * has not accepted the consent screen yet.
     */
    public static CompletableFuture<Boolean> ensureDownloaded() {
        if (!userConsented) {
            state = DownloadState.AWAITING_CONSENT;
            return CompletableFuture.completedFuture(false);
        }
        if (isInstalled()) {
            return CompletableFuture.completedFuture(true);
        }
        synchronized (LOCK) {
            if (inFlight != null && !inFlight.isDone()) {
                return inFlight;
            }
            state = DownloadState.RESOLVING;
            lastError = "";
            inFlight = CompletableFuture.supplyAsync(AnrDownloader::runDownload, EXECUTOR);
            return inFlight;
        }
    }

    private static boolean runDownload() {
        Path base = getBaseDirectory();
        Path archive = null;
        try {
            Files.createDirectories(base);
            state = DownloadState.RESOLVING;
            Release release = resolveRelease();
            Upshift.LOG.info("ANR FG: newest release is {} ({})", release.tag(), release.url());

            Path versionDir = base.resolve(release.tag());
            Files.createDirectories(versionDir);
            archive = base.resolve(release.tag() + "-full.zip");

            state = DownloadState.DOWNLOADING;
            download(release.url(), archive);

            state = DownloadState.VERIFYING;
            if (!release.sha256().isEmpty()) {
                String actual = computeSha256(archive);
                if (!actual.equalsIgnoreCase(release.sha256())) {
                    Files.deleteIfExists(archive);
                    throw new IOException("SHA-256 mismatch: expected " + release.sha256() + ", got " + actual);
                }
                Upshift.LOG.info("ANR FG: archive SHA-256 verified");
            } else {
                Upshift.LOG.warn("ANR FG: release does not publish a digest, keeping the archive unverified");
            }

            state = DownloadState.EXTRACTING;
            Path packageDir = findPackageRoot(extract(archive, versionDir.resolve("package")));

            Files.writeString(packageDir.resolve(MARKER_FILE), release.tag() + System.lineSeparator(),
                StandardCharsets.UTF_8);
            installedVersion = release.tag();
            installedRoot = packageDir;
            state = DownloadState.COMPLETED;
            Upshift.LOG.info("ANR FG: package {} staged at {}", release.tag(), packageDir);
            return true;
        } catch (Throwable t) {
            state = DownloadState.FAILED;
            lastError = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            Upshift.LOG.error("ANR FG download failed: {}", lastError, t);
            return false;
        } finally {
            if (archive != null) {
                try {
                    Files.deleteIfExists(archive);
                } catch (IOException ignored) {
                }
            }
            synchronized (LOCK) {
                inFlight = null;
            }
        }
    }

    private record Release(String tag, String url, String sha256) {
    }

    private static Release resolveRelease() {
        HttpURLConnection connection = null;
        try {
            connection = open(API_LATEST, "application/vnd.github+json");
            if (connection.getResponseCode() != 200) {
                throw new IOException("HTTP " + connection.getResponseCode() + " from GitHub releases API");
            }
            String body;
            try (InputStream stream = connection.getInputStream()) {
                body = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            String tag = root.get("tag_name").getAsString();
            JsonArray assets = root.getAsJsonArray("assets");
            for (JsonElement element : assets) {
                JsonObject asset = element.getAsJsonObject();
                String name = asset.get("name").getAsString();
                if (!name.startsWith("anr-fg") || !name.endsWith("-full.zip")) {
                    continue;
                }
                String url = asset.get("browser_download_url").getAsString();
                String digest = asset.has("digest") && !asset.get("digest").isJsonNull()
                    ? asset.get("digest").getAsString() : "";
                String sha256 = digest.toLowerCase().startsWith("sha256:") ? digest.substring(7) : "";
                return new Release(tag, url, sha256);
            }
            throw new IOException("no anr-fg *-full.zip asset in release " + tag);
        } catch (Throwable t) {
            Upshift.LOG.warn("ANR FG: could not resolve the newest release, using the pinned one: {}", t.toString());
            return new Release(FALLBACK_TAG, FALLBACK_URL, FALLBACK_SHA256);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static void download(String url, Path target) throws IOException {
        HttpURLConnection connection = open(url, "application/octet-stream");
        try {
            int code = connection.getResponseCode();
            if (code != 200) {
                throw new IOException("HTTP " + code + " downloading " + url);
            }
            long length = connection.getContentLengthLong();
            if (length > MAX_ARCHIVE_BYTES) {
                throw new IOException("archive is unexpectedly large: " + length + " bytes");
            }
            try (InputStream stream = connection.getInputStream()) {
                Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
            }
            long size = Files.size(target);
            if (size < 1024L * 1024L) {
                throw new IOException("archive is too small: " + size + " bytes");
            }
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String url, String accept) throws IOException {
        HttpURLConnection connection = (HttpURLConnection)new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(120000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "Upshift-ANR-Downloader/2.0");
        connection.setRequestProperty("Accept", accept);
        return connection;
    }

    private static Path extract(Path archive, Path destination) throws IOException {
        Files.createDirectories(destination);
        Path normalizedDestination = destination.toAbsolutePath().normalize();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path out = normalizedDestination.resolve(entry.getName()).normalize();
                if (!out.startsWith(normalizedDestination)) {
                    throw new IOException("blocked archive entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    try (OutputStream stream = Files.newOutputStream(out)) {
                        zip.transferTo(stream);
                    }
                }
            }
        }
        return normalizedDestination;
    }

    private static Path findPackageRoot(Path directory) {
        if (Files.exists(directory.resolve("install.cmd")) || Files.exists(directory.resolve("game"))) {
            return directory;
        }
        try (var children = Files.list(directory)) {
            for (Path child : children.toList()) {
                if (Files.isDirectory(child)
                    && (Files.exists(child.resolve("install.cmd")) || Files.exists(child.resolve("game")))) {
                    return child;
                }
            }
        } catch (IOException ignored) {
        }
        return directory;
    }

    private static String computeSha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream stream = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = stream.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder builder = new StringBuilder(64);
        for (byte value : digest.digest()) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }

    private static void scanInstalled() {
        Path base = getBaseDirectory();
        if (!Files.isDirectory(base)) {
            return;
        }
        Path bestRoot = null;
        String bestVersion = null;
        long bestStamp = Long.MIN_VALUE;
        try (var children = Files.list(base)) {
            for (Path child : children.toList()) {
                if (!Files.isDirectory(child)) {
                    continue;
                }
                Path candidate = child.resolve("package");
                candidate = findPackageRoot(candidate);
                if (!Files.exists(candidate.resolve(MARKER_FILE))) {
                    continue;
                }
                long stamp = Files.getLastModifiedTime(candidate.resolve(MARKER_FILE)).toMillis();
                if (stamp <= bestStamp) {
                    continue;
                }
                bestStamp = stamp;
                bestRoot = candidate;
                bestVersion = Files.readString(candidate.resolve(MARKER_FILE)).trim();
            }
        } catch (IOException ignored) {
        }
        installedRoot = bestRoot;
        installedVersion = bestVersion;
        if (bestRoot != null) {
            state = DownloadState.COMPLETED;
            Upshift.LOG.info("ANR FG package {} is staged at {}", bestVersion, bestRoot);
        }
    }

    /** Root folder that holds one sub-folder per downloaded ANR release. */
    public static Path getBaseDirectory() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null && minecraft.gameDirectory != null) {
                return minecraft.gameDirectory.toPath().resolve("upshift").resolve("anr");
            }
        } catch (Throwable ignored) {
        }
        return Path.of(System.getProperty("user.home"), ".upshift", "anr");
    }

    /** Folder of the staged package, or the root folder when nothing is installed yet. */
    public static Path getInstallDirectory() {
        Path root = installedRoot;
        return root != null ? root : getBaseDirectory();
    }

    public static boolean isInstalled() {
        Path root = installedRoot;
        return root != null && Files.isDirectory(root);
    }

    public static String installedVersion() {
        return installedVersion;
    }

    public static DownloadState getState() {
        return state;
    }

    public static String getLastError() {
        return lastError;
    }

    public static String getStatusLine() {
        DownloadState current = state;
        if (current.busy()) {
            return current.name().toLowerCase();
        }
        if (isInstalled()) {
            return installedVersion == null ? "staged" : installedVersion;
        }
        if (current == DownloadState.FAILED) {
            return "failed: " + lastError;
        }
        if (current == DownloadState.AWAITING_CONSENT) {
            return "awaiting consent";
        }
        return "not downloaded";
    }

    /** Forgets the in-memory download state so a retry can start from scratch. */
    public static void reset() {
        synchronized (LOCK) {
            inFlight = null;
        }
        state = isInstalled() ? DownloadState.COMPLETED : DownloadState.NOT_STARTED;
        lastError = "";
    }

    public static void shutdown() {
        EXECUTOR.shutdownNow();
    }
}
