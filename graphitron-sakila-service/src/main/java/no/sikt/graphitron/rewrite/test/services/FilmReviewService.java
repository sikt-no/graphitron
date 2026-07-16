package no.sikt.graphitron.rewrite.test.services;

/**
 * Fixture: mutation-side {@code @error} end-to-end. The {@code submit} method drives the
 * {@code MutationServiceRecordField} emit path that broke in production (a
 * {@code @service}-backed mutation returning a {@code @record} payload with an {@code errors}
 * slot) through compile-spec and execute-spec, mirroring the query-side
 * {@link FilmLookupService} but on the mutation pillar so any future regression in
 * {@code MutationServiceRecordField}'s try/catch wrapper or {@code ErrorRouter.dispatch} arm
 * lands as a build failure rather than a production schema break.
 *
 * <p>Three branches by input:
 * <ul>
 *   <li>{@code rating} outside [1,10] — throws {@link FilmReviewBadRatingException}.</li>
 *   <li>{@code filmId == 999} — throws {@link FilmReviewMissingFilmException}.</li>
 *   <li>otherwise — happy path; returns a populated {@link FilmReviewPayload}.</li>
 * </ul>
 *
 * <p>No DB round-trip — the body is hand-rolled to keep the test deterministic and to keep the
 * scope to the schema-emit codepath we're protecting.
 */
public final class FilmReviewService {

    private FilmReviewService() {}

    public static FilmReviewPayload submit(Integer filmId, Integer rating) {
        if (filmId == null || rating == null) {
            throw new FilmReviewBadRatingException("filmId and rating are required");
        }
        if (rating < 1 || rating > 10) {
            throw new FilmReviewBadRatingException("rating must be in [1, 10]; got " + rating);
        }
        if (filmId == 999) {
            throw new FilmReviewMissingFilmException("film " + filmId + " not found");
        }
        return new FilmReviewPayload(rating * 10000 + filmId, java.util.List.of());
    }

    /**
     * Fixture: takes a consumer-authored input bean. The fetcher generator emits a
     * {@code createFilmReviewDetails(Map<String, Object>)} helper that walks the SDL field map
     * and instantiates this record positionally. The body delegates to {@link #submit} for the
     * actual review logic; the new surface is the bean instantiation seam, not the service
     * behaviour.
     */
    public static FilmReviewPayload submitWithDetails(FilmReviewDetails details) {
        if (details == null) {
            throw new FilmReviewBadRatingException("details required");
        }
        return submit(details.filmId(), details.rating());
    }

    /**
     * Fixture: takes a {@link FilmReviewSummary} whose record components ({@code film},
     * {@code stars}) diverge from the SDL input field names ({@code filmId}, {@code rating}), bridged
     * by {@code @field(name:)}. The body reads the typed bean's components and delegates to
     * {@link #submit}; the surface under test is the {@code @field}-driven member binding in the
     * generated {@code createFilmReviewSummary} helper, not the service behaviour.
     */
    public static FilmReviewPayload submitSummary(FilmReviewSummary summary) {
        if (summary == null) {
            throw new FilmReviewBadRatingException("summary required");
        }
        return submit(summary.film(), summary.stars());
    }

    /**
     * Fixture: takes a {@link FilmRecordAssignment} whose member is a jOOQ
     * {@code FilmRecord} decoded from an {@code ID! @nodeId(typeName: "Film")} input-bean field.
     * The generated fetcher decodes the wire id into the record before calling this method; the
     * body simply reads back the populated key column to prove the decode-and-materialize seam.
     */
    public static String assignFilmRecord(FilmRecordAssignment in) {
        if (in == null || in.film() == null) {
            return "none";
        }
        return "film:" + in.film().getFilmId();
    }

    /**
     * Composite-key fixture: takes a {@link FilmActorRecordAssignment} whose member is a jOOQ
     * {@code FilmActorRecord} (composite PK {@code actor_id, film_id}) decoded from a single
     * {@code FilmActor} NodeId. The body reads both populated key columns back to prove the
     * composite per-column {@code set} fills every key column.
     */
    public static String assignFilmActorRecord(FilmActorRecordAssignment in) {
        if (in == null || in.filmActor() == null) {
            return "none";
        }
        return "filmActor:" + in.filmActor().getActorId() + ":" + in.filmActor().getFilmId();
    }

    /**
     * List fixture: takes a {@link FilmRecordListAssignment} whose member is a
     * {@code List<FilmRecord>} decoded from a list of {@code Film} NodeIds. The body reads each
     * populated {@code film_id} back to prove the list variant materialises one record per element.
     */
    public static String assignFilmRecordList(FilmRecordListAssignment in) {
        if (in == null || in.films() == null) {
            return "none";
        }
        return "films:" + in.films().stream()
            .map(f -> String.valueOf(f.getFilmId()))
            .collect(java.util.stream.Collectors.joining(","));
    }

    /**
     * Both-dimensions fixture: takes a {@link FilmActorRecordListAssignment} whose member is a
     * {@code List<FilmActorRecord>} decoded from a list of {@code FilmActor} NodeIds. The body reads
     * each element's composite key back to prove the list variant wraps the composite per-element
     * decode.
     */
    public static String assignFilmActorRecordList(FilmActorRecordListAssignment in) {
        if (in == null || in.filmActors() == null) {
            return "none";
        }
        return "filmActors:" + in.filmActors().stream()
            .map(fa -> fa.getActorId() + ":" + fa.getFilmId())
            .collect(java.util.stream.Collectors.joining(","));
    }

    /**
     * Fixture: identical branching to {@link #submit} but returns the setter-shape sibling
     * payload class. Drives the {@code MutationServiceRecordField} emit through the
     * mutable-bean construction shape (no-arg ctor + setters) end-to-end through the execution
     * tier.
     */
    public static SetterShapeFilmReviewPayload submitSetterShape(Integer filmId, Integer rating) {
        if (filmId == null || rating == null) {
            throw new FilmReviewBadRatingException("filmId and rating are required");
        }
        if (rating < 1 || rating > 10) {
            throw new FilmReviewBadRatingException("rating must be in [1, 10]; got " + rating);
        }
        if (filmId == 999) {
            throw new FilmReviewMissingFilmException("film " + filmId + " not found");
        }
        var out = new SetterShapeFilmReviewPayload();
        out.setReviewId(rating * 10000 + filmId);
        out.setErrors(java.util.List.of());
        return out;
    }

    /**
     * Fixture: identical branching to {@link #submitSetterShape} but returns the
     * {@code @field(name:)}-renamed setter-shape payload. Drives the {@code MutationServiceRecordField}
     * emit through the mutable-bean construction shape whose setter names are resolved from
     * {@code @field(name:)} rather than the SDL field names, end-to-end through the execution tier.
     */
    public static FieldRenamedSetterShapeFilmReviewPayload submitFieldRenamedSetterShape(
            Integer filmId, Integer rating) {
        if (filmId == null || rating == null) {
            throw new FilmReviewBadRatingException("filmId and rating are required");
        }
        if (rating < 1 || rating > 10) {
            throw new FilmReviewBadRatingException("rating must be in [1, 10]; got " + rating);
        }
        if (filmId == 999) {
            throw new FilmReviewMissingFilmException("film " + filmId + " not found");
        }
        var out = new FieldRenamedSetterShapeFilmReviewPayload();
        out.setReviewIdentifier(rating * 10000 + filmId);
        out.setProblems(java.util.List.of());
        return out;
    }
}
