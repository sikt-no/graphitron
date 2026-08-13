package no.sikt.graphitron.rewrite.test.extensions;

import no.sikt.graphitron.rewrite.test.jooq.tables.Film;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;

/**
 * {@code @externalField} extension methods used by the {@code ChildField.ComputedField} execution-tier fixture.
 *
 * <p>Each method follows the {@code @externalField} contract:
 * <pre>
 *     public static Field&lt;X&gt; methodName(&lt;ParentTable&gt; table)
 * </pre>
 * The method is invoked at codegen time; its returned {@code Field<X>} is inlined aliased
 * into the parent {@code @table}'s {@code $project()} projection, and a {@code LightFetcher}-wrapped
 * read picks the result Record up by the alias at request time.
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

    /**
     * Widened form of the contract: the sole parameter is {@code Table<?>} rather than the
     * generated {@code Film}, which the parameter check accepts because it still takes the parent
     * table. A widened helper has no typed column accessors, so it addresses the column by name
     * off the table it is handed; that is the whole cost of widening, and the reason the concrete
     * form is the default.
     *
     * <p>Wired by {@code Film.titleByName}. Its purpose is the compilation-tier claim that the
     * widened form still emits a {@code $project()} body that compiles: this module's helper is
     * called from generated sources that {@code graphitron-sakila-example} compiles at
     * {@code release 17}, which is where a parameter-type mismatch would surface as javac error.
     */
    public static Field<String> titleByName(Table<?> table) {
        return table.field("title", String.class);
    }
}
