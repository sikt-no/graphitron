package no.sikt.graphitron.model.read;

import org.jooq.DSLContext;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

import static no.sikt.graphitron.model.Tables.STORE_SOURCE;

/**
 * The content stamp {@code store_source.stamp} holds: the SHA-256 of a source's bytes, hex-encoded.
 *
 * <p>One home for the algorithm because two sides compute it. Capture stamps a source it has read in
 * full, so an unchanged source is read once and a currency check can re-hash a cold graph's files
 * without building its module. A reader holding text of its own compares that text against the
 * recorded stamp to learn whether the rows describing the source describe what it is holding, which
 * is the question an editor has to answer before applying a stored position to a live buffer. Two
 * spellings of one hash would agree until one of them changed, and nothing would notice.
 *
 * <p>Comparing content rather than a timestamp is deliberate: an unsaved buffer identical to the
 * captured file matches, an edited buffer does not, and a file saved with no change matches. What is
 * being asked is whether the text is the same text, never whether a write happened.
 */
public final class SourceStamp {

    private SourceStamp() {}

    /** The stamp of {@code content}: what a source holding exactly these bytes hashes to. */
    public static String of(byte[] content) {
        MessageDigest digest = digest();
        digest.update(content);
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * The stamp of the file at {@code path}, streamed rather than read whole. Null when the file
     * cannot be read: a source nothing can open has nothing to invalidate against, and an absent
     * stamp is the honest answer.
     */
    public static String ofFile(Path path) {
        MessageDigest digest = digest();
        byte[] buffer = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(path)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        } catch (IOException e) {
            return null;
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Whether the store's record of {@code sourceName} was written against exactly
     * {@code content}. False when the source has never been captured, and false when its stamp is
     * null: a source with nothing recorded to compare against cannot be shown to still match, and
     * the reader that asks is about to do something that is only safe if it does.
     *
     * <p>Ask inside the same read transaction as the rows the answer vouches for. A match read from
     * one snapshot and used against another vouches for rows a later capture has already replaced.
     */
    public static boolean recordedMatches(DSLContext dsl, String sourceName, byte[] content) {
        Objects.requireNonNull(dsl, "dsl");
        if (sourceName == null || content == null) {
            return false;
        }
        String recorded = dsl.select(STORE_SOURCE.STAMP)
            .from(STORE_SOURCE)
            .where(STORE_SOURCE.SOURCE_NAME.eq(sourceName))
            .fetchOne(STORE_SOURCE.STAMP);
        return recorded != null && recorded.equals(of(content));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the platform, so its absence is a broken JRE rather than a
            // condition a caller could handle.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
