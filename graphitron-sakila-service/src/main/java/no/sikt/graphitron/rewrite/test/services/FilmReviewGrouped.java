package no.sikt.graphitron.rewrite.test.services;

/**
 * Fixture: consumer-authored input bean that is flat where its SDL input clusters fields under a
 * nested input object. {@code rating} and {@code comment} are declared under
 * {@code FilmReviewAssessmentInput} on the wire but are ordinary components here, so they flatten
 * onto this record; {@code headline} names a component and therefore keeps binding as a nested bean.
 *
 * <p>Sibling to {@link FilmReviewDetails}, which mirrors its SDL input one-to-one at a single level.
 * This is the compilation-tier witness that a flattened group produces a well-formed canonical
 * constructor call against a real consumer record at {@code <release>17</release>}.
 */
public record FilmReviewGrouped(
    Integer filmId,
    Integer rating,
    String comment,
    FilmReviewTag headline
) {}
