package no.sikt.graphitron.model.catalog;

/**
 * Renders a schema coordinate the way the GraphQL specification spells one, for the rows
 * {@code graphql_coordinate} is keyed by.
 *
 * <p>One owner because a coordinate is written to two relations at once, the supertype and the
 * subtype anchor under it, and both take the same string from the same call. Stating the grammar
 * once here is what makes that true; a second statement of it, in the DDL or in a second writer,
 * would only create the disagreement a constraint would then have to rule out. It sits beside
 * {@link GrainSentence} for the same reason that does: a convention of the store's own text
 * belongs with the catalog it describes rather than in whichever walk happens to write it.
 *
 * <p>The type and enum-value forms are both {@code Type.name} and are not distinguishable by
 * their text; which one a row is comes from the kind column beside it, decided at the write.
 */
public final class SchemaCoordinate {

    private SchemaCoordinate() {}

    /** {@code Type}. */
    public static String ofType(String typeName) {
        return typeName;
    }

    /** {@code Type.field}. */
    public static String ofField(String typeName, String fieldName) {
        return typeName + "." + fieldName;
    }

    /** {@code Type.field(argument:)}, trailing colon included as the specification writes it. */
    public static String ofArgument(String typeName, String fieldName, String argumentName) {
        return typeName + "." + fieldName + "(" + argumentName + ":)";
    }

    /** {@code Type.value}, which is the spelling a field of the same name would take. */
    public static String ofEnumValue(String typeName, String valueName) {
        return typeName + "." + valueName;
    }
}
