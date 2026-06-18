package no.sikt.graphitron.rewrite.test.conditions;

import no.sikt.graphitron.rewrite.test.jooq.tables.Address;
import no.sikt.graphitron.rewrite.test.jooq.tables.Customer;
import no.sikt.graphitron.rewrite.test.jooq.tables.Film;
import org.jooq.Condition;
import org.jooq.Table;
import org.jooq.impl.DSL;

import java.util.Map;

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

    /**
     * Outer field-level {@code @condition(override: true)} method. Reads the enclosing
     * {@code filter} input argument as a Map and produces {@code film.film_id >= 2}. Paired
     * with an inner {@code filmIdCondition} method via {@code filter.filmId = "1"}, the combined
     * WHERE is {@code (film_id >= 2) AND (film_id = 1)} which matches zero rows, pinning the
     * rewrite's override semantics (inner explicit methods survive across an outer override)
     * against the legacy "outer owns everything" rule that would drop the inner predicate and
     * return films 2..5.
     */
    public static Condition outerOverrideMethod(Table<?> table, Map<String, Object> filter) {
        return table.field(Film.FILM.FILM_ID).ge(2);
    }

    /**
     * R330: FK-target {@code @nodeId} {@code @condition} method. The declared first parameter is
     * the concrete FK-target table {@link Address} (not the input's own {@code customer} table),
     * so the generated condition only compiles if {@code QueryConditionsGenerator} hands it an
     * aliased {@code Address} from the correlated {@code EXISTS} rather than the root
     * {@code customer} local. Filters on the FK-target table to prove the alias is bound there:
     * customers whose address is in district {@code Alberta}. {@code addressId} is unused (the
     * {@code override} drops the decoded predicate); it is present so the binding shape mirrors a
     * real consumer method that receives the decoded id alongside the table.
     */
    public static Condition addressDistrictAlberta(Address address, String addressId) {
        return address.DISTRICT.eq("Alberta");
    }

    /**
     * R330: field-level {@code @condition(override: true)} paired with an FK-target {@code @nodeId}
     * input field, mirroring the opptak {@code soknadsmangeltyperCondition} shim shape. The declared
     * first parameter is the concrete root table {@link Customer} (the table the query selects from),
     * so the generated shim only compiles if it hands this method the root {@code table} local while
     * still wrapping the sibling FK-target term in a correlated EXISTS. Restricts to active
     * customers; combined with {@code addressDistrictAlberta} the shim is
     * {@code (customer.activebool = true) AND EXISTS(address district = 'Alberta')}.
     */
    public static Condition customersActiveOnly(Customer table, Map<String, Object> filter) {
        return table.ACTIVEBOOL.eq(true);
    }

}
