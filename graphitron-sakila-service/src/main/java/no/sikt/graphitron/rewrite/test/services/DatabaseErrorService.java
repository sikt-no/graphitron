package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.Film;
import org.jooq.DSLContext;
import org.jooq.Record1;
import org.jooq.impl.DSL;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Database-failure fixture: a {@code @service}-backed leaf whose one statement always fails in
 * the database. Wired onto {@code Film} via the synthetic SDL field {@code databaseError}.
 *
 * <p>The statement binds every key of the batch and {@link #MARKER}, a truncated JSON literal, and
 * casts the marker to {@code json}. PostgreSQL refuses the cast with SQL state {@code 22P02}: the
 * first line of the driver's message names the failure
 * ({@code invalid input syntax for type json}) and a later {@code Where:} line quotes the marker.
 * So the exception carries a value on a detail line, the way a unique violation's {@code Detail:}
 * line carries the conflicting key, without writing anything.
 *
 * <p>Method shape matches the existing {@code @service} child-field convention
 * ({@code Set<Record1<Integer>>} keys, {@code DSLContext}, returns {@code Map}). The body never
 * reaches the Map construction; the statement throws.
 */
public final class DatabaseErrorService {

    /** The bound value the failing cast is given, which PostgreSQL quotes back on a {@code Where:} line. */
    public static final String MARKER = "{\"database-error-marker\": ";

    private DatabaseErrorService() {}

    public static Map<Record1<Integer>, String> castMarkerToJson(Set<Record1<Integer>> filmIds, DSLContext dsl) {
        List<Integer> ids = filmIds.stream().map(Record1::value1).toList();
        dsl.select(Film.FILM.FILM_ID, DSL.field("cast({0} as json)", Object.class, DSL.val(MARKER)))
            .from(Film.FILM)
            .where(Film.FILM.FILM_ID.in(ids))
            .fetch();
        throw new IllegalStateException("database-failure fixture: the json cast of the marker did not fail");
    }
}
