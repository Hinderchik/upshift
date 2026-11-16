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
 * Downloads and installs the official ANR FG package (Apex Neural Rendering frame generation)
 * as a user-level Vulkan implicit layer. No install.cmd needed, no admin rights required.
 *
 * <p>The layer is registered in {@code %APPDATA%/vulkan/implicit_layer.d/} with an executable
 * filter matching the actual javaw.exe used by the launcher (ElyPrismLauncher, PrismLauncher,
 * MultiMC, vanilla, etc.). This works per-user and takes effect on next game launch.</p>
 */
public final class AnrDownloader {
    private static final String API_LATEST = "https://api.github.com/repos/apex-compute/ANR/releases/latest";
    private static final String FALLBACK_TAG = "FG-V.B18";
    private static final String FALLBACK_URL = "https://github.com/apex-compute/ANR/releases/download/FG-V.B18/anr-fg-V.B18-full.zip";
    private static final String FALLBACK_SHA256 = "ed70eef1dc92ba71685daad59f1d9ef1d9897b46df009f04b1dc51c4933cd300";
    private static final String MARKER_FILE = "upshift-anr.txt";
    private static final String LAYER_MANIFEST_NAME = "vk_layer_anr_fg.json";
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
    private static volatile boolean layerInstalled = false;
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
        NOT_STARTED,
        AWAITING_CONSENT,
        RESOLVING,
        DOWNLOADING,
        VERIFYING,
        EXTRACTING,
        INSTALLING_LAYER,
        COMPLETED,
        FAILED;

        public boolean busy() {
            return this == RESOLVING || this == DOWNLOADING || this == VERIFYING || this == EXTRACTING || this == INSTALLING_LAYER;
        }
    }

    public static void giveConsent() {
        userConsented = true;
        if (state == DownloadState.AWAITING_CONSENT) {
            state = DownloadState.NOT_STARTED;
        }
    }

    public static void revokeConsent() {
        userConsented = false;
        // Also uninstall the layer if present
        uninstallLayer();
        reset();
    }

    public static boolean hasUserConsented() {
        return userConsented;
    }

    public static boolean isLayerInstalled() {
        return layerInstalled;
    }

    public static CompletableFuture<Boolean> ensureDownloaded() {
        if (!userConsented) {
            state = DownloadState.AWAITING_CONSENT;
            return CompletableFuture.completedFuture(false);
        }
        if (isInstalled() && isLayerInstalled()) {
            return CompletableFuture.completedFuture(true);
        }
        synchronized (LOCK) {
            if (inFlight != null && !inFlight.isDone()) {
                return inFlight;
            }
            state = DownloadState.RESOLVING;
            lastError = "";
            inFlight = CompletableFuture.supplyAsync(AnrDownloader::runDownloadAndInstall, EXECUTOR);
            return inFlight;
        }
    }

    private static boolean runDownloadAndInstall() {
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

            // Install as Vulkan implicit layer
            state = DownloadState.INSTALLING_LAYER;
            installVulkanLayer(packageDir, release.tag());

            Files.writeString(packageDir.resolve(MARKER_FILE), release.tag() + System.lineSeparator(),
                StandardCharsets.UTF_8);
            installedVersion = release.tag();
            installedRoot = packageDir;
            layerInstalled = true;
            state = DownloadState.COMPLETED;
            Upshift.LOG.info("ANR FG: package {} installed as Vulkan layer", release.tag());
            return true;
        } catch (Throwable t) {
            state = DownloadState.FAILED;
            lastError = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            Upshift.LOG.error("ANR FG download/install failed: {}", lastError, t);
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

    /**
     * Installs the ANR FG package as a user-level Vulkan implicit layer.
     * Copies dxgi.dll (the OptiScaler Vulkan layer) + shaders/config to a staging folder,
     * and writes a vk_layer_anr_fg.json manifest in %APPDATA%/vulkan/implicit_layer.d/
     * with an executable filter for the detected javaw.exe.
     */
    private static void installVulkanLayer(Path packageDir, String version) throws IOException {
        // 1. Locate the layer DLL (dxgi.dll in ANR FG package)
        Path sourceDll = packageDir.resolve("game").resolve("dxgi.dll");
        if (!Files.exists(sourceDll)) {
            // Fallback: maybe in OptiScaler/custom/Apex Neural Rendering FG/dlssg/
            sourceDll = packageDir.resolve("game")
                .resolve("OptiScaler")
                .resolve("custom")
                .resolve("Apex Neural Rendering FG")
                .resolve("dlssg")
                .resolve("nvngx_dlssg.dll");
        }
        if (!Files.exists(sourceDll)) {
            throw new IOException("Could not find ANR FG layer DLL in package");
        }

        // 2. Determine target staging directory for the layer
        Path layerStagingDir = getBaseDirectory().resolve("layer").resolve(version);
        Files.createDirectories(layerStagingDir);

        // 3. Copy the layer DLL
        Path targetDll = layerStagingDir.resolve("anr_fg_layer.dll");
        Files.copy(sourceDll, targetDll, StandardCopyOption.REPLACE_EXISTING);
        Upshift.LOG.info("ANR FG: copied layer DLL to {}", targetDll);

        // 4. Copy shaders/config folder (OptiScaler/custom/Apex Neural Rendering FG)
        Path sourceConfig = packageDir.resolve("game")
            .resolve("OptiScaler")
            .resolve("custom")
            .resolve("Apex Neural Rendering FG");
        if (Files.exists(sourceConfig)) {
            Path targetConfig = layerStagingDir.resolve("config");
            copyDirectory(sourceConfig, targetConfig);
            Upshift.LOG.info("ANR FG: copied config to {}", targetConfig);
        }

        // 5. Detect the javaw.exe used by the launcher
        String javawPath = detectJavawPath();
        Upshift.LOG.info("ANR FG: detected javaw.exe for layer filter: {}", javawPath);

        // 6. Write the implicit layer manifest
        Path implicitLayerDir = getImplicitLayerDir();
        Files.createDirectories(implicitLayerDir);
        Path manifestPath = implicitLayerDir.resolve(LAYER_MANIFEST_NAME);
        writeLayerManifest(manifestPath, targetDll.toString(), layerStagingDir.toString(), javawPath, version);
        Upshift.LOG.info("ANR FG: wrote Vulkan implicit layer manifest to {}", manifestPath);
    }

    private static String detectJavawPath() {
        // 1. Try java.home (most reliable for current JVM)
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            Path candidate = Path.of(javaHome, "bin", "javaw.exe");
            if (Files.exists(candidate)) {
                return candidate.toAbsolutePath().toString();
            }
        }

        // 2. Try Minecraft's game directory + common launcher structures
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.gameDirectory != null) {
                Path gameDir = mc.gameDirectory.toPath();

                // ElyPrismLauncher: java/java-runtime-*/bin/javaw.exe
                Path elyJava = gameDir.getParent().resolve("java");
                if (Files.exists(elyJava)) {
                    try (var stream = Files.walk(elyJava, 4)) {
                        for (Path p : stream.toList()) {
                            if (p.getFileName().toString().equalsIgnoreCase("javaw.exe")) {
                                return p.toAbsolutePath().toString();
                            }
                        }
                    }
                }

                // PrismLauncher / MultiMC: same structure
                // Vanilla: no embedded java, fall back to java.home
            }
        } catch (Throwable ignored) {
        }

        // 3. Fallback: just "javaw.exe" (will match any javaw.exe in PATH)
        return "javaw.exe";
    }

    private static Path getImplicitLayerDir() {
        String appData = System.getenv("APPDATA");
        if (appData != null) {
            return Path.of(appData, "vulkan", "implicit_layer.d");
        }
        // Linux/macOS fallback (not really used for Minecraft on Windows)
        String home = System.getProperty("user.home");
        if (home != null) {
            return Path.of(home, ".local", "share", "vulkan", "implicit_layer.d");
        }
        return Path.of(".", "vulkan", "implicit_layer.d");
    }

    private static void writeLayerManifest(Path manifestPath, String dllPath, String configDir, String javawExe, String version) throws IOException {
        // Escape backslashes for JSON
        String dllEscaped = dllPath.replace("\\", "\\\\");
        String configEscaped = configDir.replace("\\", "\\\\");
        String javawEscaped = javawPathForFilter(javawExe);

        String json = """
            {
              "file_format_version" : "1.0.0",
              "layer" : {
                "name" : "VK_LAYER_ANR_FG",
                "type" : "GLOBAL",
                "library_path" : "%s",
                "api_version" : "1.3.0",
                "implementation_version" : "1",
                "description" : "Apex Neural Rendering Frame Generation (ANR FG) via OptiScaler",
                "enable_environment" : {
                  "ANR_FG_CONFIG_DIR" : "%s"
                },
                "disable_environment" : {
                  "DISABLE_ANR_FG" : "1"
                },
                "filter" : {
                  "executable" : "%s"
                }
              }
            }
            """.formatted(dllEscaped, configEscaped, javawEscaped);

        Files.writeString(manifestPath, json, StandardCharsets.UTF_8);
    }

    private static String javawPathForFilter(String javawPath) {
        // For the filter, we want just the executable name if it's a standard javaw.exe
        // or the full path if it's a custom launcher java
        if (javawPath == null || javawPath.equals("javaw.exe")) {
            return "javaw.exe";
        }
        // Use full path with escaped backslashes
        return javawPath.replace("\\", "\\\\");
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        Files.walk(source).forEach(src -> {
            try {
                Path dest = target.resolve(source.relativize(src));
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dest);
                } else {
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** Uninstalls the Vulkan implicit layer manifest. */
    public static void uninstallLayer() {
        try {
            Path manifestPath = getImplicitLayerDir().resolve(LAYER_MANIFEST_NAME);
            if (Files.exists(manifestPath)) {
                Files.delete(manifestPath);
                Upshift.LOG.info("ANR FG: removed Vulkan implicit layer manifest");
            }
        } catch (IOException e) {
            Upshift.LOG.warn("ANR FG: failed to remove layer manifest", e);
        }
        layerInstalled = false;
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
        // Check if layer manifest exists
        Path manifestPath = getImplicitLayerDir().resolve(LAYER_MANIFEST_NAME);
        layerInstalled = Files.exists(manifestPath);
    }

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
        if (isInstalled() && isLayerInstalled()) {
            return installedVersion == null ? "installed" : installedVersion + " (layer active)";
        }
        if (isInstalled() && !isLayerInstalled()) {
            return installedVersion + " (layer pending)";
        }
        if (current == DownloadState.FAILED) {
            return "failed: " + lastError;
        }
        if (current == DownloadState.AWAITING_CONSENT) {
            return "awaiting consent";
        }
        return "not downloaded";
    }

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