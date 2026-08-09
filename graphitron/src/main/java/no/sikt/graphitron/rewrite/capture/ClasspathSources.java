package no.sikt.graphitron.rewrite.capture;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.STORE_SOURCE;

/**
 * Records the {@code store_source} row for each classpath entry the class census came from, and
 * stamps it; the SDL walk's schema-file sources share the stamping machinery through
 * {@link #noteRegularFile}.
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
 *
 * <p>The stamp is written after the rows it vouches for, not with them: {@link #record} upserts the
 * source unstamped and {@link #commitStamps} fills the hashes in once the load has flushed. A run
 * killed part-way therefore leaves a partition whose stamp is null, which no refresh will retain,
 * so the failure mode of a crash is repeated work rather than a partition that claims to hold rows
 * it never finished writing.
 *
 * <p>Source rows are written as immediate upserts rather than through the sink's buffered batch:
 * the store is shared between graphs and a source is store-global, so a row another graph already
 * wrote is refreshed in place ({@code last_seen} forward, stamp reset for a rewrite) instead of
 * colliding with it, and a row this run rewrites carries a null stamp from the moment its old
 * partition stops being trustworthy.
 */
final class ClasspathSources {

    /** {@code store_source.source_kind}'s classpath arms; the schema-file arm is the SDL walk's. */
    private static final String DIRECTORY = "DIRECTORY";
    private static final String JAR = "JAR";

    private final Map<String, String> stamps = new HashMap<>();
    private final Set<Path> recorded = new LinkedHashSet<>();

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
        upsert(sink.dsl(), name, path != null && Files.isDirectory(path) ? DIRECTORY : JAR);
        if (path != null && Files.isRegularFile(path)) {
            recorded.add(path);
        }
        return name;
    }

    /**
     * Writes or refreshes one {@code store_source} row now, ahead of the sink's buffered flush,
     * so the rows that reference it always find it and a shared store's existing row is taken
     * over rather than collided with. The stamp is deliberately reset to null: this run is about
     * to (re)write the source's partition, and the null is what keeps a killed run re-walked.
     */
    static void upsert(DSLContext dsl, String sourceName, String sourceKind) {
        var now = LocalDateTime.now();
        dsl.insertInto(STORE_SOURCE)
            .set(STORE_SOURCE.SOURCE_NAME, sourceName)
            .set(STORE_SOURCE.SOURCE_KIND, sourceKind)
            .set(STORE_SOURCE.STAMP, (String) null)
            .set(STORE_SOURCE.LAST_SEEN, now)
            .onDuplicateKeyUpdate()
            .set(STORE_SOURCE.SOURCE_KIND, sourceKind)
            .set(STORE_SOURCE.STAMP, (String) null)
            .set(STORE_SOURCE.LAST_SEEN, now)
            .execute();
    }

    /**
     * Adds a path to the set {@link #commitStamps} hashes, for sources whose rows another walk
     * writes: the SDL capture stamps each schema file that resolves to a regular file, so a
     * currency check can re-hash a cold graph's files without building its module.
     */
    void noteRegularFile(Path path) {
        recorded.add(path);
    }

    /**
     * Stamps every source this load wrote in full. Called after the flush, which is what makes the
     * stamp mean "these rows are all here" rather than "these rows were started".
     */
    void commitStamps(DSLContext dsl) {
        for (Path entry : recorded) {
            String stamp = stamp(entry);
            if (stamp != null) {
                dsl.update(STORE_SOURCE)
                    .set(STORE_SOURCE.STAMP, stamp)
                    .where(STORE_SOURCE.SOURCE_NAME.eq(entry.toString()))
                    .execute();
            }
        }
        recorded.clear();
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
