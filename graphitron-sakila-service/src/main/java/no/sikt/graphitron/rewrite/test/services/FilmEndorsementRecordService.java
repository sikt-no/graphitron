package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.Tables;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmEndorsementRecord;
import org.jooq.DSLContext;

/**
 * Compilation / execution-tier fixtures: a jOOQ {@link FilmEndorsementRecord} bound directly as a
 * {@code @service} input param, populated from an <em>FK-reference</em> {@code @nodeId(typeName: "Film")}.
 * The generated {@code createFilmEndorsementRecord} helper decodes the Film NodeId and loads it onto the
 * renamed FK child column {@code endorsed_film} (resolved through the {@code film_endorsement → film}
 * foreign key, not a same-named {@code film_id}), plus a plain {@code @field} column {@code note}.
 *
 * <p>{@link #endorseFilm} proves the value lands on the FK child column end-to-end: it INSERTs the
 * constructed record (the serial PK {@code endorsement_id} is never in the input, so it stays unset /
 * {@code changed=false} and the database assigns it — the service-owned-insert path) and reads
 * {@code endorsed_film} back from the persisted row. {@link #describeEndorsement} reports the record's
 * jOOQ {@code changed}-flags without inserting, so the execution tier can observe the D4 null-semantics
 * contract (omitted → {@code changed=false}, explicit null → {@code NULL}, value → loaded) on both the
 * nullable FK reference and the nullable plain column.
 */
public final class FilmEndorsementRecordService {

    private FilmEndorsementRecordService() {}

    /**
     * INSERTs the constructed endorsement and returns the persisted {@code endorsed_film}, read back from
     * the database by the generated serial PK. The decoded Film id landing on {@code endorsed_film} (not
     * a same-named PK column) is the end-to-end proof of FK-constraint-driven target resolution.
     */
    public static String endorseFilm(FilmEndorsementRecord in, DSLContext dsl) {
        in.attach(dsl.configuration());
        in.insert();
        Integer persisted = dsl
            .select(Tables.FILM_ENDORSEMENT.ENDORSED_FILM)
            .from(Tables.FILM_ENDORSEMENT)
            .where(Tables.FILM_ENDORSEMENT.ENDORSEMENT_ID.eq(in.getEndorsementId()))
            .fetchOne(Tables.FILM_ENDORSEMENT.ENDORSED_FILM);
        return "endorsed_film=" + persisted;
    }

    /**
     * Reports the constructed record's {@code changed}-flag state for the nullable FK reference
     * ({@code endorsed_film}) and the nullable plain column ({@code note}) without inserting — the only
     * tier that can observe {@code changed=false} exclusion.
     */
    public static String describeEndorsement(FilmEndorsementRecord in) {
        var t = Tables.FILM_ENDORSEMENT;
        // touched(Field) is jOOQ 3.20's non-deprecated name for the per-column changed flag.
        return "endorsedFilm[changed=" + in.touched(t.ENDORSED_FILM) + ",val=" + in.getEndorsedFilm() + "]"
            + " note[changed=" + in.touched(t.NOTE) + ",val=" + in.getNote() + "]";
    }
}
