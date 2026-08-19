package no.sikt.graphitron.model.grammar;

/**
 * Splits a written reference on its first period, which is the whole of the qualifier grammar the
 * SDL admits wherever an author names a catalog object.
 *
 * <p>The split is unconditional and total: there is no fallback arm and no notion of a value being
 * too malformed to partition. {@code film} has no qualifier, so the namespace part is null and the
 * name part is the whole value. {@code public.film} has both. {@code film.} and {@code .film} each
 * wrote a period with one side empty, and the empty side is stored as the empty string, which joins
 * nothing; that non-match is the intended outcome, visible in the stored fact rather than produced
 * by a rule a reader has to know about. A value with several periods keeps everything after the
 * first in the name part, which then matches no catalog name, for the same reason.
 *
 * <p>Null is not a qualifier question: an absent reference has no parts, so both are null. That
 * keeps a row's parts null exactly when its written value is.
 *
 * <p>It lives in the module that declares the columns rather than in the one that fills them,
 * because two things fill them and they are in different modules: the generator's capture, walking
 * a real document, and this module's own seeding harness, stating rows directly. A private copy on
 * either side would be a second opinion about what a stored part means, and what a part means is
 * this schema's to say. The column comments carry the rest of that meaning; what the namespace half
 * <em>is</em> varies by what is being named and is deliberately not decided here.
 */
public final class QualifiedNameGrammar {

    private QualifiedNameGrammar() {
    }

    /**
     * The text left of {@code written}'s first period, null when it carries none, and null when
     * {@code written} is itself null.
     */
    public static String namespacePart(String written) {
        if (written == null) {
            return null;
        }
        int period = written.indexOf('.');
        return period < 0 ? null : written.substring(0, period);
    }

    /**
     * The text right of {@code written}'s first period, or the whole value when it carries none;
     * null when {@code written} is itself null.
     */
    public static String namePart(String written) {
        if (written == null) {
            return null;
        }
        int period = written.indexOf('.');
        return period < 0 ? written : written.substring(period + 1);
    }
}
