package no.sikt.graphitron.mcp.rag;

import no.sikt.graphitron.rewrite.catalog.CatalogFacts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * R386 — the pure descriptor composer for the {@code catalog.search} semantic index. Turns a
 * frozen R362 {@link CatalogFacts.Table} into one readable, embeddable descriptor string, and
 * normalizes SQL identifiers into readable words (R118 OQ3) so the embedder sees language rather
 * than {@code snake_case} tokens. Stateless and ONNX-free, so the retrieval-lift invariants pin in
 * a fast unit test.
 *
 * <p><strong>Each descriptor carries both halves.</strong> The raw SQL tokens stay in the text (so
 * BM25 still matches {@code film_actor} exactly), alongside the normalized words in parentheses (so
 * the embedder reads "film actor"). One composition feeds both the document text and the
 * invalidation hash, so the hashed thing and the embedded thing cannot drift: see
 * {@link #corpusHash(List)}.
 *
 * <pre>
 * Table film_actor (film actor)
 * Comment: join table linking films to actors
 * Columns: actor_id (actor id), film_id (film id), last_update (last update)
 * </pre>
 *
 * <p>When jOOQ captured no comments the {@code Comment:} line and the per-column comment
 * parentheticals are omitted; the descriptor degrades to names-only, still useful via normalization
 * (R118 OQ4).
 */
final class CatalogDescriptors {

    private CatalogDescriptors() {}

    /**
     * Composes one table's readable descriptor: the table name (raw + normalized), its comment when
     * present, and each column's name (raw + normalized) with its comment when present. This is the
     * exact string handed to {@link Embedder#embedDocuments(List)} for this table, and the exact
     * string folded into {@link #corpusHash(List)}.
     */
    static String descriptor(CatalogFacts.Table table) {
        var sb = new StringBuilder();
        sb.append("Table ").append(table.name()).append(" (").append(splitWords(table.name())).append(')');
        table.comment().ifPresent(c -> sb.append('\n').append("Comment: ").append(c));
        if (!table.columns().isEmpty()) {
            sb.append('\n').append("Columns: ");
            for (int i = 0; i < table.columns().size(); i++) {
                var col = table.columns().get(i);
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(col.sqlName()).append(" (").append(splitWords(col.sqlName())).append(')');
                col.comment().ifPresent(c -> sb.append(": ").append(c));
            }
        }
        return sb.toString();
    }

    /**
     * Splits a SQL identifier into lowercased, space-separated words: {@code snake_case} on
     * underscores, {@code camelCase} / {@code PascalCase} on case boundaries, and a digit run as its
     * own word ({@code customerID} -> "customer id", {@code film_actor} -> "film actor",
     * {@code lastUpdate} -> "last update", {@code address2} -> "address 2"). A single lowercase word
     * passes through unchanged. The normalized form is the model-agnostic retrieval lift R118 OQ3
     * calls for; it sits beside the raw token, never replacing it.
     */
    static String splitWords(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return "";
        }
        var words = new StringBuilder();
        var word = new StringBuilder();
        // A simple state machine over the original (case-preserving) characters; an underscore (or
        // any non-alphanumeric) closes the current word, and a case / digit-class change splits a
        // camelCase / mixed token without dropping any character. Boundary detection reads the raw
        // previous char (not the already-lowercased buffer), so an acronym run like "ID" stays whole.
        char prev = 0;
        for (int i = 0; i < identifier.length(); i++) {
            char c = identifier.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                flush(words, word);
                prev = 0;
                continue;
            }
            if (prev != 0 && boundaryBetween(prev, c, identifier, i)) {
                flush(words, word);
            }
            word.append(Character.toLowerCase(c));
            prev = c;
        }
        flush(words, word);
        return words.toString();
    }

    /**
     * A word boundary sits between two adjacent alphanumeric characters when the case or digit class
     * changes: lower/digit -> upper ({@code lastUpdate}, {@code address2Line}), letter -> digit
     * ({@code address2}), digit -> letter ({@code 2nd}), or an acronym/word seam (upper -> upper
     * followed by lower, as in {@code customerID} kept whole but {@code IDColumn} -> "id column").
     */
    private static boolean boundaryBetween(char prev, char cur, String s, int curIndex) {
        boolean prevLower = Character.isLowerCase(prev);
        boolean prevDigit = Character.isDigit(prev);
        boolean curUpper = Character.isUpperCase(cur);
        boolean curDigit = Character.isDigit(cur);
        boolean curLetter = Character.isLetter(cur);
        if ((prevLower || prevDigit) && curUpper) {
            return true;
        }
        if (Character.isLetter(prev) && curDigit) {
            return true;
        }
        if (prevDigit && curLetter) {
            return true;
        }
        // Acronym tail: in "IDColumn" the seam is before the C that starts "Column"; detect an
        // upper run ending where the next char is lower, splitting the last upper off as the new word.
        if (Character.isUpperCase(prev) && curUpper) {
            int next = curIndex + 1;
            return next < s.length() && Character.isLowerCase(s.charAt(next));
        }
        return false;
    }

    private static void flush(StringBuilder words, StringBuilder word) {
        if (word.length() > 0) {
            if (words.length() > 0) {
                words.append(' ');
            }
            words.append(word);
            word.setLength(0);
        }
    }

    /**
     * The invalidation key: a SHA-256 over the exact descriptor strings, in order, that are handed
     * to {@link Embedder#embedDocuments(List)}. Computed over the composer's output, never over
     * {@link CatalogFacts} fields directly, so the hashed thing and the embedded thing are the same
     * artifact and cannot drift when the descriptor format or normalization changes. A changed
     * column / comment changes a descriptor and so the hash; recomposing identical facts yields an
     * identical hash.
     */
    static String corpusHash(List<String> descriptors) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String descriptor : descriptors) {
                // Length-prefix each descriptor so two corpora cannot collide by re-segmenting the
                // same concatenated bytes (a descriptor boundary is part of the hashed identity).
                byte[] bytes = descriptor.getBytes(StandardCharsets.UTF_8);
                digest.update((bytes.length + ":").getBytes(StandardCharsets.UTF_8));
                digest.update(bytes);
            }
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS-referenced standard algorithms; its absence is a broken JRE.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String toHex(byte[] bytes) {
        var sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
