package no.sikt.graphitron.rewrite.test.conditions;

import no.sikt.graphitron.rewrite.test.jooq.tables.Film;
import org.jooq.Condition;
import org.jooq.Table;
import org.jooq.impl.DSL;

/**
 * Condition-method stubs for Phase 4 ({@code @condition} on {@code INPUT_FIELD_DEFINITION}) spec fixtures.
 *
 * <p>Phase 4 limitation: the generator emits {@code env.getArgument(<fieldName>)} for nested input
 * fields, which returns {@code null} because the field is not a top-level argument. Methods here
 * return {@code DSL.noCondition()} (a no-op filter) so execution tests pass while verifying that
 * the condition method is reflected, wired up, and called without errors.
 */
public final class InputFieldConditionFixtures {

    @SuppressWarnings("unused")
    private static final Table<?> FILM_CATALOG_GUARD = Film.FILM;

    private InputFieldConditionFixtures() {}

    /**
     * Input-field {@code @condition} for a {@code filmId} field. Used on {@code FilmConditionInput}
     * (a {@code @table} input) and {@code PlainFilmIdInput} (a plain input). At runtime {@code filmId}
     * is {@code null} because nested-arg access is deferred; returns {@code noCondition()} to let
     * all rows through.
     */
    public static Condition filmIdCondition(Table<?> table, String filmId) {
        return DSL.noCondition();
    }
}
