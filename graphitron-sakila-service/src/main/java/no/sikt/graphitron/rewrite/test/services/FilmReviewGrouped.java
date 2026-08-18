package no.sikt.graphitron.rewrite.test.services;

/**
 * Fixture: consumer-authored input bean that is flat where its SDL input clusters fields under
 * nested input objects. On the wire {@code rating} is declared under {@code FilmReviewAssessmentInput}
 * and {@code comment} one level deeper under {@code FilmReviewRemarkInput}, but both are ordinary
 * components here, so they flatten onto this record; {@code headline} names a component and therefore
 * keeps binding as a nested bean.
 *
 * <p>Sibling to {@link FilmReviewDetails}, which mirrors its SDL input one-to-one at a single level.
 * This is the compilation-tier witness that a flattened group produces a well-formed canonical
 * constructor call against a real consumer record at {@code <release>17</release>}, and that the
 * emitted descent declares each group's local before the local nested inside it: the two-level
 * {@code comment} path makes a child-before-parent emission order a compile error here.
 *
 * <p>{@code headline} carries a second load: it is a <em>singular</em> nested-bean member, the emit
 * path that reaches the wire map through an {@code instanceof} pattern rather than a cast. This
 * module compiles the emitted sources under {@code -Xlint:all -Werror}, so a regression to an
 * unchecked cast there fails the build without any test having to read the emitted text.
 */
public record FilmReviewGrouped(
    Integer filmId,
    Integer rating,
    String comment,
    FilmReviewTag headline
) {}
