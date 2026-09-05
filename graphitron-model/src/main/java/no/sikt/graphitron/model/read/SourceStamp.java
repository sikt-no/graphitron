package no.sikt.graphitron.model.read;

import org.jooq.DSLContext;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

import static no.sikt.graphitron.model.Tables.STORE_SOURCE;

/**
 * The content stamp {@code store_source.stamp} holds: a source's identity as
 * {@code <scheme>:<hex>}, where the scheme names how the hex was arrived at.
 *
 * <p>One home for the algorithm because two sides compute it. Capture stamps a source it has read in
 * full, so an unchanged source is read once and a currency check can re-hash a cold graph's files
 * without building its module. A reader holding text of its own compares that text against the
 * recorded stamp to learn whether the rows describing the source describe what it is holding, which
 * is the question an editor has to answer before applying a stored position to a live buffer. Two
 * spellings of one hash would agree until one of them changed, and nothing would notice.
 *
 * <p>Two schemes, and the tag is what keeps them from being confused for one another. {@link #of}
 * and {@link #ofFile} read the bytes and produce {@code sha256:}. {@link #ofSha1} takes an identity
 * a resolver already established for an artifact it downloaded, which the Maven plugin reads off a
 * checksum sidecar, and produces {@code sha1:}: the value is not recomputed here and could not be,
 * the whole point of accepting it being that nothing reads the bytes. A column holding two
 * algorithms without saying which is exactly the trap the paragraph above describes, so the tag is
 * part of the stamp rather than something a reader has to infer from a length.
 *
 * <p>Comparison stays string equality throughout, which is what makes the tag load-bearing rather
 * than decorative: a {@code sha1:} value never compares equal to a {@code sha256:} one, so a source
 * whose scheme changed reads as changed and is re-walked. That is the conservative direction, and
 * it is what makes an existing store's untagged values migrate by one re-walk rather than by a
 * false match.
 *
 * <p>Comparing content rather than a timestamp is deliberate: an unsaved buffer identical to the
 * captured file matches, an edited buffer does not, and a file saved with no change matches. What is
 * being asked is whether the text is the same text, never whether a write happened.
 */
public final class SourceStamp {

    /** The scheme {@link #of} and {@link #ofFile} produce: this class reading the bytes itself. */
    private static final String SHA256 = "sha256:";

    /** The scheme {@link #ofSha1} produces: an identity a repository established at download. */
    private static final String SHA1 = "sha1:";

    /** How long a hex-encoded SHA-1 is, which is the only shape {@link #ofSha1} accepts. */
    private static final int SHA1_HEX_LENGTH = 40;

    private SourceStamp() {}

    /** The stamp of {@code content}: what a source holding exactly these bytes hashes to. */
    public static String of(byte[] content) {
        MessageDigest digest = digest();
        digest.update(content);
        return SHA256 + HexFormat.of().formatHex(digest.digest());
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
        return SHA256 + HexFormat.of().formatHex(digest.digest());
    }

    /**
     * The stamp for a SHA-1 identity someone else established, {@code hex} being the bare
     * hex-encoded digest. Null when it is not one: an unparseable value is not an identity, and
     * supplying none costs a hash rather than a wrong answer.
     *
     * <p>Nothing here verifies that {@code hex} describes the file it will be attached to. The
     * caller supplying it is asserting provenance, and a caller that cannot assert it supplies
     * nothing; this method's job is only that the second scheme is spelled where the first one is.
     */
    public static String ofSha1(String hex) {
        if (hex == null || hex.length() != SHA1_HEX_LENGTH) {
            return null;
        }
        String lowered = hex.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lowered.length(); i++) {
            char c = lowered.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return null;
            }
        }
        return SHA1 + lowered;
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
