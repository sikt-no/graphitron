package no.sikt.graphitron.rewrite.test.extensions;

import no.sikt.graphitron.rewrite.test.jooq.tables.Film;
import org.jooq.Field;
import org.jooq.impl.DSL;

/**
 * {@code @externalField} extension methods used by the R48 ComputedField execution-tier fixture.
 *
 * <p>Each method follows the {@code @externalField} contract:
 * <pre>
 *     public static Field&lt;X&gt; methodName(&lt;ParentTable&gt; table)
 * </pre>
 * The method is invoked at codegen time; its returned {@code Field<X>} is inlined aliased
 * into the parent {@code @table}'s {@code $fields()} projection, and a {@code ColumnFetcher}
 * reads the result Record by the alias at request time.
 */
public final class FilmExtensions {

    private FilmExtensions() {}

    /**
     * Returns a boolean-valued SQL expression: {@code true} when the film's language is
     * English ({@code language_id = 1} per the Sakila seed), {@code false} otherwise.
     *
     * <p>Wired by {@code Film.isEnglish: Boolean @externalField(reference: ...)}.
     */
    public static Field<Boolean> isEnglish(Film table) {
        return DSL.field(table.LANGUAGE_ID.eq(1));
    }
}
