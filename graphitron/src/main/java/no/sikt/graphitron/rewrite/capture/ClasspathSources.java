package no.sikt.graphitron.rewrite.capture;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import static no.sikt.graphitron.model.Tables.STORE_SOURCE;

/**
 * Records the {@code store_source} row for each classpath entry the class census came from, and
 * stamps it.
 *
 * <p>The stamp is a content hash rather than the {@code (path, size, last-modified)} triple a
 * per-process cache could get away with. That triple is a heuristic, tolerable while a wrong answer
 * dies with the JVM and not tolerable once it survives a build: CI caches, container image layers
 * and reproducible-build normalisation all produce jars whose modification time is constant or
 * arbitrary. A hash is exact, and it is an order of magnitude cheaper than the classfile parse it
 * protects.
 *
 * <p>A directory root is deliberately unstamped. It changes on every compile, so hashing it would
 * buy an invalidation that always fires while paying for a full walk to decide that.
 */
final class ClasspathSources {

    /** {@code store_source.source_kind}'s classpath arms; the schema-file arm is the SDL walk's. */
    private static final String DIRECTORY = "DIRECTORY";
    private static final String JAR = "JAR";

    private final Map<String, String> stamps = new HashMap<>();

    /**
     * Claims the row for {@code sourceName} if this is the first class read from it, and returns
     * the name so the caller can hang its class row off it. A reference no scan produced carries no
     * entry; those are hand-built stand-ins, and the census they belong to is unpartitionable by
     * construction, so they are recorded against the empty source rather than dropped.
     */
    String record(FactSink sink, String sourceName) {
        String name = sourceName == null ? "" : sourceName;
        if (!sink.claim(STORE_SOURCE, name)) {
            return name;
        }
        Path path = name.isEmpty() ? null : Path.of(name);
        var row = sink.dsl().newRecord(STORE_SOURCE);
        row.setSourceName(name);
        row.setSourceKind(path != null && Files.isDirectory(path) ? DIRECTORY : JAR);
        row.setStamp(path != null && Files.isRegularFile(path) ? stamp(path) : null);
        sink.add(row);
        return name;
    }

    /**
     * The entry's content hash, memoised for the process so a classpath shared by several
     * generator passes is hashed once. This is the in-process degenerate case of the per-source
     * invalidation the recorded stamp buys; the recorded value is what survives the JVM.
     */
    String stamp(Path entry) {
        return stamps.computeIfAbsent(entry.toString(), ignored -> hash(entry));
    }

    private static String hash(Path entry) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];
            try (InputStream in = Files.newInputStream(entry)) {
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            // An entry that cannot be read now is one the scan already declined to open, so there
            // is nothing to invalidate against and an absent stamp is the honest answer.
            return null;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }
}
