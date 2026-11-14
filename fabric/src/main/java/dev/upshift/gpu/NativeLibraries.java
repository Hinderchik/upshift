package dev.upshift.gpu;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class NativeLibraries {
    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

    private NativeLibraries() {
    }

    public static boolean isWindows() {
        return OS.contains("win");
    }

    public static boolean isLinux() {
        return OS.contains("linux");
    }

    public static boolean isMac() {
        return OS.contains("mac");
    }

    public static boolean corePresent() {
        return NativeLibraries.files("upshift_core").stream().findFirst().isPresent();
    }

    public static Path corePath() {
        return NativeLibraries.files("upshift_core").stream().findFirst().orElse(null);
    }

    public static boolean ngxPresent() {
        return !NativeLibraries.files("nvngx").isEmpty();
    }

    public static boolean xessPresent() {
        return !NativeLibraries.files("xess").isEmpty();
    }

    public static boolean fidelityFxPresent() {
        return !NativeLibraries.files("ffx").isEmpty();
    }

    public static List<String> presentReport() {
        ArrayList<String> out = new ArrayList<String>();
        out.add("core=" + NativeLibraries.corePresent());
        out.add("ngx=" + NativeLibraries.ngxPresent());
        out.add("xess=" + NativeLibraries.xessPresent());
        out.add("ffx=" + NativeLibraries.fidelityFxPresent());
        return out;
    }

    private static List<Path> files(String needle) {
        ArrayList<Path> hits = new ArrayList<Path>();
        for (Path dir : NativeLibraries.searchDirs()) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(p -> {
                    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    return n.contains(needle) && (n.endsWith(".dll") || n.contains(".so") || n.endsWith(".dylib"));
                }).forEach(hits::add);
            }
            catch (Exception exception) {}
        }
        return hits;
    }

    private static List<Path> searchDirs() {
        ArrayList<Path> dirs = new ArrayList<Path>();
        // java.library.path is a list, not a single directory. Launchers that
        // leave the environment PATH in it (common in development runs) would
        // otherwise reach Path.of as one long string and throw.
        String libraryPath = System.getProperty("java.library.path", "");
        for (String part : libraryPath.split(File.pathSeparator)) {
            if (part.isBlank()) continue;
            try {
                dirs.add(Path.of(part, new String[0]));
            }
            catch (InvalidPathException invalidPathException) {
                // Skip malformed entries; the standard locations below still
                // cover the usual native directories.
            }
        }
        dirs.add(Path.of(".", new String[0]).toAbsolutePath().normalize());
        dirs.add(Path.of("natives", new String[0]).toAbsolutePath().normalize());
        if (NativeLibraries.isWindows()) {
            String root = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
            dirs.add(Path.of(root, "System32"));
            String programFiles = System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files");
            dirs.add(Path.of(programFiles, "NVIDIA Corporation"));
        }
        if (NativeLibraries.isLinux()) {
            dirs.add(Path.of("/usr/lib", new String[0]));
            dirs.add(Path.of("/usr/lib/x86_64-linux-gnu", new String[0]));
            dirs.add(Path.of("/usr/lib64", new String[0]));
            dirs.add(Path.of("/usr/local/lib", new String[0]));
            dirs.add(Path.of("/tmp/.X11-unix", new String[0]));
            String ld = System.getenv("LD_LIBRARY_PATH");
            if (ld != null) {
                for (String part : ld.split(":")) {
                    if (part.isBlank()) continue;
                    dirs.add(Path.of(part, new String[0]));
                }
            }
        }
        return dirs;
    }
}
