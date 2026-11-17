package no.sikt.graphitron.model.boot;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * What the store's stamp says about the code when no release version can: a hash of this module's
 * own classes and resources, so a store whose rows older code derived is not opened by newer code.
 *
 * <p>A release stamps its version, read off the jar's manifest. A snapshot's version names no code,
 * and a classes directory carries no manifest at all, so for either the stamp is the hash: every
 * build that changes a class of this module opens a fresh store, and every build that does not opens
 * the one it left. The same code hashes the same whether it is seen as a classes directory or as the
 * jar built from it, the hash being over the entries' names and bytes in name order and not over
 * the container; the jar's own bookkeeping, its manifest and Maven's metadata under
 * {@code META-INF/maven/}, is left out, a classes directory having none.
 *
 * <p>Two limits, stated rather than widened. The hash covers this module's bytes and not those of
 * the libraries it runs on, so a dependency changed under a snapshot build keeps the store; a release
 * covers that through its version. And each new hash is a new store file beside the old ones under
 * the store's home, which nothing prunes.
 */
final class CodeStamp {

    private CodeStamp() {}

    /** Stands in where the module's code cannot be found on disk to hash. */
    static final String UNKNOWN = "dev";

    /** This JVM's stamp, computed once: every store open reads it. */
    static String current() {
        return Holder.CURRENT;
    }

    private static final class Holder {
        static final String CURRENT = compute();
    }

    private static String compute() {
        var source = CodeStamp.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            return UNKNOWN;
        }
        try {
            var location = Path.of(source.getLocation().toURI());
            return Files.exists(location) ? of(location) : UNKNOWN;
        } catch (URISyntaxException | IllegalArgumentException e) {
            return UNKNOWN;
        }
    }

    /**
     * The stamp of the code at {@code location}, a classes directory or a jar: sixteen hex digits
     * of a hash over each entry's name and bytes, in name order.
     */
    static String of(Path location) {
        var entries = Files.isDirectory(location) ? fromDirectory(location) : fromJar(location);
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            entries.forEach((name, bytes) -> {
                digest.update(name.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(bytes);
            });
            return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }

    private static boolean bookkeeping(String name) {
        return name.equals("META-INF/MANIFEST.MF") || name.startsWith("META-INF/maven/");
    }

    private static Map<String, byte[]> fromDirectory(Path root) {
        var entries = new TreeMap<String, byte[]>();
        try (Stream<Path> files = Files.walk(root)) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                String name = root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
                if (!bookkeeping(name)) {
                    entries.put(name, Files.readAllBytes(file));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return entries;
    }

    private static Map<String, byte[]> fromJar(Path jar) {
        var entries = new TreeMap<String, byte[]>();
        try (var file = new JarFile(jar.toFile())) {
            for (var entry : java.util.Collections.list(file.entries())) {
                if (entry.isDirectory() || bookkeeping(entry.getName())) {
                    continue;
                }
                try (InputStream in = file.getInputStream(entry)) {
                    entries.put(entry.getName(), in.readAllBytes());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return entries;
    }
}
