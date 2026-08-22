package no.sikt.graphitron.model.catalog;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts a relation's grain statement from its {@code COMMENT ON} text: the first sentence.
 *
 * <p>"The first sentence of a relation comment is its grain statement" is a convention of the
 * store's own prose, held by the comments themselves, so the extractor lives beside
 * {@link StoreCatalog} rather than in whichever consumer wants a one-line summary. The catalog
 * reader's records stay verbatim from the engine, which is what keeps this a sibling helper a
 * consumer calls rather than a column the reader mints.
 *
 * <p>A sentence ends at {@code .}, {@code ?} or {@code !} followed by whitespace, with one
 * closing quote or bracket allowed in between. Requiring the whitespace is the whole of the
 * hazard handling, and it is enough for the hazards the corpus actually holds: a dotted
 * coordinate ({@code Type.field}), a package-qualified class name, a version string and a leading
 * {@code .java} all carry a dot with no space behind it, so none of them ends a sentence. A
 * comment with no terminator at all yields the whole text; the store's gates require every
 * relation comment to hold one, so that case is a floor rather than a path.
 */
public final class GrainSentence {

    /** A terminator, one optional closer, then whitespace: the only place a sentence ends. */
    private static final Pattern SENTENCE_END = Pattern.compile("[.?!][\"')\\]`]?\\s");

    private GrainSentence() {}

    /**
     * The first sentence of the comment, trimmed, terminator included. Returns the whole trimmed
     * text when it holds no sentence end, and the empty string for null or blank input.
     */
    public static String of(String comment) {
        if (comment == null || comment.isBlank()) {
            return "";
        }
        String text = comment.strip();
        Matcher end = SENTENCE_END.matcher(text);
        return end.find() ? text.substring(0, end.end()).strip() : text;
    }
}
