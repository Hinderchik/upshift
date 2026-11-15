package dev.upshift.gpu;

import dev.upshift.Upshift;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Handles on-demand download of ANR (AMD FidelityFX Frame Generation) native libraries.
 * Downloads happen only when user explicitly consents in settings.
 * Includes SHA-256 hash verification for security.
 */
public final class AnrDownloader {
    // ANR release info - update when new version available
    private static final String ANR_VERSION = "1.0.0";
    private static final String GITHUB_REPO = "GPUOpen-LibrariesAndSDKs/FidelityFX-FrameGeneration";
    private static final String BASE_URL = "https://github.com/" + GITHUB_REPO + "/releases/download/v" + ANR_VERSION + "/";

    // Platform-specific assets with SHA-256 hashes (update when version changes!)
    private static final String WINDOWS_X64 = "ffx-framegeneration-windows-x64.dll";
    private static final String WINDOWS_X64_SHA256 = "PLACEHOLDER_SHA256_HASH_FOR_WINDOWS_DLL";
    private static final String LINUX_X64 = "libffx-framegeneration-linux-x64.so";
    private static final String LINUX_X64_SHA256 = "PLACEHOLDER_SHA256_HASH_FOR_LINUX_SO";

    private static final ExecutorService DOWNLOAD_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "anr-downloader");
        t.setDaemon(true);
        return t;
    });

    private static volatile DownloadState state = DownloadState.NOT_STARTED;
    private static String lastError = "";
    private static Path installedLibPath = null;
    private static boolean userConsented = false;

    private AnrDownloader() {
    }

    public enum DownloadState {
        NOT_STARTED,
        AWAITING_CONSENT,
        DOWNLOADING,
        VERIFYING,
        COMPLETED,
        FAILED
    }

    /**
     * User must call this to give explicit consent before download starts.
     * Call from UI when user clicks "Download ANR libraries" button.
     */
    public static void giveConsent() {
        userConsented = true;
    }

    /**
     * Revokes consent (for retry or settings change).
     */
    public static void revokeConsent() {
        userConsented = false;
        reset();
    }

    /**
     * Starts the download if user consented and not already done.
     * Returns a future that completes when download finishes (success or failure).
     */
    public static CompletableFuture<Boolean> ensureDownloaded() {
        if (!userConsented) {
            state = DownloadState.AWAITING_CONSENT;
            return CompletableFuture.completedFuture(false);
        }

        if (state == DownloadState.COMPLETED && installedLibPath != null && Files.exists(installedLibPath)) {
            return CompletableFuture.completedFuture(true);
        }
        if (state == DownloadState.DOWNLOADING || state == DownloadState.VERIFYING) {
            return CompletableFuture.supplyAsync(() -> {
                while (state == DownloadState.DOWNLOADING || state == DownloadState.VERIFYING) {
                    try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
                }
                return state == DownloadState.COMPLETED;
            }, DOWNLOAD_EXECUTOR);
        }

        state = DownloadState.DOWNLOADING;
        lastError = "";

        return CompletableFuture.supplyAsync(() -> {
            try {
                Path libDir = getLibDirectory();
                Files.createDirectories(libDir);

                String libName = getPlatformLibraryName();
                String expectedHash = getExpectedHash();
                String downloadUrl = BASE_URL + libName;
                Path targetPath = libDir.resolve(libName);

                Upshift.LOG.info("Downloading ANR library from {}", downloadUrl);

                URL url = new URL(downloadUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                conn.setRequestProperty("User-Agent", "Upshift-ANR-Downloader/1.0");

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    throw new IOException("HTTP " + responseCode + " downloading ANR library");
                }

                state = DownloadState.VERIFYING;
                try (InputStream in = conn.getInputStream()) {
                    Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }

                // Verify SHA-256 hash
                String actualHash = computeSha256(targetPath);
                if (!actualHash.equalsIgnoreCase(expectedHash)) {
                    Files.deleteIfExists(targetPath);
                    throw new IOException("SHA-256 mismatch! Expected: " + expectedHash + ", got: " + actualHash);
                }

                // Verify file exists and has reasonable size
                if (!Files.exists(targetPath) || Files.size(targetPath) < 1_000_000) {
                    throw new IOException("Downloaded library is too small or missing");
                }

                installedLibPath = targetPath.toAbsolutePath();
                state = DownloadState.COMPLETED;
                Upshift.LOG.info("ANR library downloaded, verified, and installed to {}", installedLibPath);
                return true;

            } catch (Throwable t) {
                state = DownloadState.FAILED;
                lastError = t.getMessage();
                Upshift.LOG.error("ANR library download failed: {}", lastError, t);
                return false;
            }
        }, DOWNLOAD_EXECUTOR);
    }

    /**
     * Gets the path to the downloaded library, if available.
     */
    public static Path getLibraryPath() {
        return installedLibPath;
    }

    public static DownloadState getState() {
        return state;
    }

    public static String getLastError() {
        return lastError;
    }

    public static boolean hasUserConsented() {
        return userConsented;
    }

    /**
     * Resets the downloader state (for retry).
     */
    public static void reset() {
        state = DownloadState.NOT_STARTED;
        lastError = "";
        installedLibPath = null;
    }

    private static Path getLibDirectory() {
        String userHome = System.getProperty("user.home");
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return Path.of(userHome, "AppData", "Roaming", "Upshift", "anr");
        } else if (os.contains("mac")) {
            return Path.of(userHome, "Library", "Application Support", "Upshift", "anr");
        } else {
            return Path.of(userHome, ".config", "upshift", "anr");
        }
    }

    private static String getPlatformLibraryName() {
        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();
        if (os.contains("win") && (arch.contains("64") || arch.contains("x86_64") || arch.contains("amd64"))) {
            return WINDOWS_X64;
        } else if ((os.contains("linux") || os.contains("unix")) && (arch.contains("64") || arch.contains("x86_64") || arch.contains("amd64"))) {
            return LINUX_X64;
        } else {
            throw new UnsupportedOperationException("Unsupported platform: " + os + " " + arch);
        }
    }

    private static String getExpectedHash() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return WINDOWS_X64_SHA256;
        } else {
            return LINUX_X64_SHA256;
        }
    }

    private static String computeSha256(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream is = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        byte[] hash = digest.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static void shutdown() {
        DOWNLOAD_EXECUTOR.shutdown();
    }
}