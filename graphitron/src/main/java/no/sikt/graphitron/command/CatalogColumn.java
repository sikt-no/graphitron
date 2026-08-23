package no.sikt.graphitron.command;

import java.util.Objects;

/**
 * A catalog column a command names, as captured facts: the SQL name, the field name it takes on
 * its table's generated class, and the Java type jOOQ binds it to, spelled as the name capture
 * recorded rather than as a decoded emit type.
 *
 * <p>The command tier's carrier for what the walk's {@code ColumnRef} carries, minus the emit
 * vocabulary. That ref holds a javapoet {@code TypeName} beside the type's name, decided at the
 * catalog boundary because the live {@code Class} is reachable only there; a row built from store
 * rows has no such boundary and needs none, the captured name being fully recoverable.
 *
 * @param sqlName  the column's own name in the database
 * @param javaName the field on the generated table class, e.g. {@code RENTAL_ID}
 * @param javaTypeName the column's bound Java type as jOOQ names it: a source-form class name for
 *                 a scalar ({@code java.lang.Integer}) and a JVM array descriptor for an array
 *                 column ({@code [Ljava.lang.Boolean;}). Carried in that form because it is the
 *                 form that survives both: a descriptor is decodable to the source form and not
 *                 the reverse, and an array column is an ordinary column in a consumer's database
 */
public record CatalogColumn(String sqlName, String javaName, String javaTypeName) {

    public CatalogColumn {
        Objects.requireNonNull(sqlName, "sqlName");
        Objects.requireNonNull(javaName, "javaName");
        Objects.requireNonNull(javaTypeName, "javaTypeName");
        if (javaName.isBlank() || javaTypeName.isBlank()) {
            throw new IllegalArgumentException(
                "a catalog column carries the generated field name and the bound type's name; a"
                + " blank one would emit as an unparseable reference rather than failing here");
        }
    }
}
