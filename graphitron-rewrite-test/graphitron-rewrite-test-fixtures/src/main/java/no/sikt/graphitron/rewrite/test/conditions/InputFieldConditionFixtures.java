package no.sikt.graphitron.rewrite.test.conditions;

import no.sikt.graphitron.rewrite.test.jooq.tables.Film;
import org.jooq.Condition;
import org.jooq.Table;
import org.jooq.impl.DSL;

/**
 * Condition-method stubs for Phase 4 ({@code @condition} on {@code INPUT_FIELD_DEFINITION}) spec fixtures.
 *
 * <p>Phase 4b: nested-arg extraction is wired, so {@code filmId} arrives as the actual value
 * passed in the GraphQL input. Methods here return real jOOQ predicates so execution tests can
 * assert filtering behavior. A {@code null} input still maps to {@code noCondition()} to match
 * the "absent value == unconstrained" semantics of optional GraphQL input fields.
 */
public final class InputFieldConditionFixtures {

    @SuppressWarnings("unused")
    private static final Table<?> FILM_CATALOG_GUARD = Film.FILM;

    private InputFieldConditionFixtures() {}

    /**
     * Input-field {@code @condition} for a {@code filmId} field. Used on {@code FilmConditionInput}
     * (a {@code @table} input) and {@code PlainFilmIdInput} (a plain input). Produces
     * {@code filmTable.film_id = ?} for non-null {@code filmId}, {@code noCondition()} otherwise.
     * Resolving through {@code table.field(Film.FILM.FILM_ID)} keeps the predicate anchored in
     * the caller's aliased table so jOOQ renders the WHERE against the aliased FROM correctly.
     */
    public static Condition filmIdCondition(Table<?> table, String filmId) {
        if (filmId == null) {
            return DSL.noCondition();
        }
        return table.field(Film.FILM.FILM_ID).eq(Integer.parseInt(filmId));
    }
}
