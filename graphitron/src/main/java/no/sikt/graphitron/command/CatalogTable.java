package no.sikt.graphitron.command;

import java.util.Objects;

/**
 * A catalog table a command names, as captured facts: the SQL name, the field name it takes in
 * its schema's generated constants class, and the two generated classes a renderer spells to
 * declare it. Every component is a string the fact store already holds, so a row carrying one
 * needs no live catalog and no emit library to be built.
 *
 * <p>This is the command tier's own carrier and deliberately not the walk's {@code TableRef}.
 * That ref holds javapoet {@code ClassName}s, which is the emit vocabulary, and holding it in a
 * command would mean the plan decides how a name is spelled as well as which name it is. The line
 * this record draws is the one the package states: a command carries the captured string, and the
 * lift to a javapoet type happens in the renderer, where the emit library is already the
 * package's business.
 *
 * <p>It also carries less. {@code TableRef} additionally holds the record class, the primary key
 * and every column, because classification asks those questions of it; an emission asks none of
 * them of a table it is merely declaring and joining. A command carries what its renderer reads,
 * so a component here is evidence that something emits it.
 *
 * @param sqlName        the table's own name in the database, which a renderer spells only inside
 *                       a string literal (an alias, a field lookup) and never as an identifier
 * @param javaFieldName  the field on the constants class, e.g. {@code RENTAL} in
 *                       {@code Tables.RENTAL}
 * @param tableClassName the generated table class's fully qualified name, the declared type of a
 *                       local holding an alias of this table
 * @param constantsClassName the schema's {@code Tables} class's fully qualified name. Per schema
 *                       rather than derivable from the table class: under a multi-schema layout
 *                       each schema publishes its own, which is why capture records it
 */
public record CatalogTable(String sqlName, String javaFieldName, String tableClassName,
                           String constantsClassName) {

    public CatalogTable {
        requireNamed(sqlName, "sqlName");
        requireNamed(javaFieldName, "javaFieldName");
        requireNamed(tableClassName, "tableClassName");
        requireNamed(constantsClassName, "constantsClassName");
    }

    private static void requireNamed(String value, String component) {
        Objects.requireNonNull(value, component);
        if (value.isBlank()) {
            throw new IllegalArgumentException(
                "a catalog table's " + component + " is a captured name; a blank one would emit as"
                + " an unparseable reference rather than failing here");
        }
    }
}
