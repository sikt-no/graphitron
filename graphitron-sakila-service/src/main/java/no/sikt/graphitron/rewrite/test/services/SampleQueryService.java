package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.Tables;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;
import org.jooq.DSLContext;
import org.jooq.Result;

import java.util.List;

/**
 * Fixture for service-backed root fetchers:
 *
 * <ul>
 *   <li>{@link #filmsByService} — returns {@code Result<FilmRecord>} for {@code @service}
 *       with a {@code @table}-bound return type, populating the primary key and nothing else.
 *       The framework reads the records as key carriers and re-selects the requested fields
 *       from the table, which is what makes the key-only shape sufficient.</li>
 *   <li>{@link #filmCount} — returns a scalar for {@code @service} with a non-table return.</li>
 * </ul>
 *
 * <p>Lives in {@code graphitron-sakila-service} (not {@code graphitron-sakila-example}) because
 * the rewrite generator runs during {@code generate-sources} of the example module and needs
 * this class on its classpath; the service module compiles first.
 */
public final class SampleQueryService {

    private SampleQueryService() {}

    /**
     * Selects the primary key and nothing else: the deliberately minimal shape a
     * {@code @table}-bound {@code @service} return is allowed to be. Every other selected field
     * resolves because the framework re-selects it from {@code film} keyed on the {@code FILM_ID}
     * it reads off each returned record. Its siblings below stay full-select on purpose, so the
     * tier covers both the key-only contract and the already-full records the old contract
     * asked authors to write.
     *
     * <p>{@code FILM_ID} is populated through a coerced {@code FilmRecord}, not through
     * {@code selectFrom}, which is the whole point: a record whose other columns were never
     * fetched is still a complete answer.
     */
    public static Result<FilmRecord> filmsByService(DSLContext dsl, List<Integer> ids) {
        return dsl.select(Tables.FILM.FILM_ID)
            .from(Tables.FILM)
            .where(Tables.FILM.FILM_ID.in(ids))
            .orderBy(Tables.FILM.FILM_ID)
            .fetchInto(Tables.FILM);
    }

    /**
     * The key carrier taken literally: one {@code FilmRecord} per requested id, with the key set
     * and no query run at all. Two things this pins that {@link #filmsByService} cannot. A
     * service need not touch the database to answer a {@code @table}-bound field, which is the
     * strongest form of "populate the key columns and Graphitron fetches the rest". And a key the
     * table has no row for has to go somewhere: it drops from the result, the same contract the
     * discriminated service return already carries.
     */
    public static Result<FilmRecord> filmsByServiceUnchecked(DSLContext dsl, List<Integer> ids) {
        Result<FilmRecord> carriers = dsl.newResult(Tables.FILM);
        for (Integer id : ids) {
            FilmRecord carrier = dsl.newRecord(Tables.FILM);
            carrier.setFilmId(id);
            carriers.add(carrier);
        }
        return carriers;
    }

    /**
     * The single-cardinality shape of the same contract: one key carrier rather than a container
     * of them, with no query run. Its own arm in the generated fetcher, which is why it is a
     * fixture and not a variation on {@link #filmsByServiceUnchecked}: the lift builds a bare
     * {@code RecordN} instead of a {@code Result}, and a missing row nulls the field instead of
     * dropping an element. Both ways of arriving at that null are reachable from here, a key the
     * table has no row for and a service that returns nothing at all.
     *
     * @param id the key to carry, or {@code null} to return no record at all
     */
    public static FilmRecord filmByServiceUnchecked(DSLContext dsl, Integer id) {
        if (id == null) {
            return null;
        }
        FilmRecord carrier = dsl.newRecord(Tables.FILM);
        carrier.setFilmId(id);
        return carrier;
    }

    /**
     * Returns a scalar {@code Integer} — graphql-java coerces to the GraphQL {@code Int!}.
     */
    public static Integer filmCount(DSLContext dsl) {
        return dsl.fetchCount(Tables.FILM);
    }

    /**
     * Fixture: same logic as {@link #filmsByService} but with the Java parameter named
     * {@code filmIds}, demonstrating {@code @field(name: "filmIds")} on a GraphQL argument named
     * {@code ids} that binds to a differently-named Java parameter.
     */
    public static Result<FilmRecord> filmsByServiceRenamed(DSLContext dsl, List<Integer> filmIds) {
        return dsl.selectFrom(Tables.FILM)
            .where(Tables.FILM.FILM_ID.in(filmIds))
            .orderBy(Tables.FILM.FILM_ID)
            .fetch();
    }

    /**
     * Fixture: same logic as {@link #filmsByServiceRenamed} but the GraphQL argument is a
     * Relay-style wrapper input (`FilmsByPathInput { ids }`) and the binding uses a path
     * expression (`filmIds: input.ids`). The Java signature stays GraphQL-input-shape-agnostic;
     * the generator emits the Map traversal from `env.getArgument("input")` to the leaf `ids`
     * with intermediate-null short-circuit.
     */
    public static Result<FilmRecord> filmsByPath(DSLContext dsl, List<Integer> filmIds) {
        return dsl.selectFrom(Tables.FILM)
            .where(Tables.FILM.FILM_ID.in(filmIds))
            .orderBy(Tables.FILM.FILM_ID)
            .fetch();
    }

    /**
     * List-segment fixture: argMapping {@code filmIds: input.items.id} walks through an
     * intermediate list segment ({@code items: [FilmIdItem!]!}) and projects each item's
     * {@code id} to produce the {@code List<Integer>} expected here. The Java signature is
     * identical to {@link #filmsByPath} on purpose; the difference lives entirely on the
     * schema and argMapping side.
     */
    public static Result<FilmRecord> filmsByListPath(DSLContext dsl, List<Integer> filmIds) {
        return dsl.selectFrom(Tables.FILM)
            .where(Tables.FILM.FILM_ID.in(filmIds))
            .orderBy(Tables.FILM.FILM_ID)
            .fetch();
    }

    /**
     * List-segment fixture (two-list-deep): argMapping
     * {@code filmIdGroups: input.groups.items.id} walks two intermediate list segments
     * ({@code groups: [FilmIdGroup!]!} then {@code items: [FilmIdItem!]!}) and projects each
     * item's {@code id}, yielding a {@code List<List<Integer>>}. The service flattens the
     * outer list before the SQL predicate; the test asserts the same films come back
     * regardless of how the ids are grouped on the wire.
     */
    public static Result<FilmRecord> filmsByNestedListPath(DSLContext dsl, List<List<Integer>> filmIdGroups) {
        var ids = filmIdGroups.stream().flatMap(List::stream).toList();
        return dsl.selectFrom(Tables.FILM)
            .where(Tables.FILM.FILM_ID.in(ids))
            .orderBy(Tables.FILM.FILM_ID)
            .fetch();
    }

}
