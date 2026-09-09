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
     * Fixture: takes a {@link FilmReviewGrouped} whose {@code rating} component is declared under a
     * nested grouping input on the SDL side and whose {@code comment} component is declared one level
     * deeper still, while {@code headline} names a component and stays a nested bean. Renders the bean
     * back as a string so the round-trip can observe each half independently: a present group
     * populating its leaves, an absent group (outer or inner) leaving them null, and the
     * matching-member group still arriving as a nested object.
     */
    public static String submitGroupedReview(FilmReviewGrouped in) {
        if (in == null) {
            return "none";
        }
        return "filmId=" + in.filmId()
            + ",rating=" + in.rating()
            + ",comment=" + in.comment()
            + ",headline=" + (in.headline() == null ? null : in.headline().name());
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
     * Polymorphic fixture: takes an {@link OccupantRecordAssignment} whose member is typed
     * {@code UpdatableRecord<?>} and backed by an {@code ID! @nodeId(typeName: "AddressOccupant")}
     * field. The generated fetcher peeks the wire id's type prefix and decodes it into whichever
     * member's record it names, so the body dispatches on the record's runtime class exactly as a
     * consumer would; reporting the class and the loaded key back is what says the type survived the
     * decode rather than the key alone arriving.
     */
    public static String assignOccupantRecord(OccupantRecordAssignment in) {
        if (in == null || in.occupant() == null) {
            return "none";
        }
        return "occupant:" + describeOccupant(in.occupant());
    }

    /**
     * The list shape of {@link #assignOccupantRecord}: one request carrying ids of both members, each
     * element decoded on its own prefix.
     */
    public static String assignOccupantRecordList(OccupantRecordListAssignment in) {
        if (in == null || in.occupants() == null) {
            return "none";
        }
        return "occupants:" + in.occupants().stream()
            .map(FilmReviewService::describeOccupant)
            .collect(java.util.stream.Collectors.joining(","));
    }

    /**
     * The producer-parameter twin of {@link #assignOccupantRecord}: no bean, the parameter named for
     * the argument receives the decoded record directly. Same helper, a different slot kind reaching
     * it, which is what says the two slot kinds agree.
     */
    public static String assignOccupantByArgument(org.jooq.UpdatableRecord<?> occupant) {
        if (occupant == null) {
            return "none";
        }
        return "occupant:" + describeOccupant(occupant);
    }

    /**
     * One decoded occupant as the fixtures report it: the record's runtime class, then its loaded key
     * column. Dispatching on {@code instanceof} rather than reading the key generically is the point
     * of the shape being tested: what the polymorphic decode delivers is a typed record, so a service
     * can reach each member's own accessors.
     */
    private static String describeOccupant(org.jooq.Record occupant) {
        if (occupant instanceof no.sikt.graphitron.rewrite.test.jooq.tables.records.CustomerRecord c) {
            return "Customer:" + c.getCustomerId();
        }
        if (occupant instanceof no.sikt.graphitron.rewrite.test.jooq.tables.records.StaffRecord st) {
            return "Staff:" + st.getStaffId();
        }
        return "unexpected:" + occupant.getClass().getSimpleName();
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
