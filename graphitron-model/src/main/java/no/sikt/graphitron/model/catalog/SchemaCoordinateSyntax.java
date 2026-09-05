package no.sikt.graphitron.model.catalog;

import org.jooq.Field;

import static org.jooq.impl.DSL.concat;
import static org.jooq.impl.DSL.val;

/**
 * The GraphQL specification's schema-coordinate grammar, as the one place that spells it.
 *
 * <p>The specification admits five forms and this states each once:
 * <ul>
 *   <li>{@code Name} for a named type</li>
 *   <li>{@code Name.Name} for a field of one, and equally for an enum value of one</li>
 *   <li>{@code Name.Name(Name:)} for an argument of a field</li>
 *   <li>{@code @Name} for a directive</li>
 *   <li>{@code @Name(Name:)} for an argument of a directive</li>
 * </ul>
 *
 * <p>One owner because the spelling is stated in more than one population and is one rule in all of
 * them. {@code graphql_element} is keyed by it over the author's own graph, and the LSP keys its
 * directive vocabulary by it over graphitron's directive schema; those are different documents and
 * the grammar is the specification's either way. A second statement of it would be a rule two
 * modules agree on until one of them changes.
 *
 * <p>The field and enum-value forms are one form. The specification gives {@code Name . Name} both
 * readings and leaves the parent type's kind to tell them apart, so this offers two names for the
 * one spelling rather than two spellings: a caller says which population it is in, and the text it
 * gets is the same text.
 *
 * <p>It sits beside {@link GrainSentence} for the same reason that does: a convention of the
 * store's own text belongs with the catalog it describes rather than in whichever walk writes it.
 *
 * <p>Each form is spelled twice, once over strings a walk holds and once as an expression over
 * columns, because both callers exist: a walk writing one row at a time renders the text in Java,
 * and a derivation filling an anchor from a relation renders it in the statement. The pairs sit
 * beside each other here for the reason the class exists at all, the alternative being one of them
 * written out at whichever call site needed it.
 */
public final class SchemaCoordinateSyntax {

    private SchemaCoordinateSyntax() {}

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

    /** {@code Type.value}, which the specification spells the way it spells a field. */
    public static String ofEnumValue(String typeName, String valueName) {
        return ofField(typeName, valueName);
    }

    /** {@code @directive}. */
    public static String ofDirective(String directiveName) {
        return "@" + directiveName;
    }

    /** {@code @directive(argument:)}, on {@link #ofArgument}'s terms. */
    public static String ofDirectiveArgument(String directiveName, String argumentName) {
        return "@" + directiveName + "(" + argumentName + ":)";
    }

    /** {@link #ofType} over a column: the type's own name, so the column stands as it is. */
    public static Field<String> typeCoordinate(Field<String> typeName) {
        return typeName;
    }

    /** {@link #ofField} over columns. */
    public static Field<String> fieldCoordinate(Field<String> typeName, Field<String> fieldName) {
        return concat(typeName, val("."), fieldName);
    }

    /** {@link #ofArgument} over columns. */
    public static Field<String> argumentCoordinate(Field<String> typeName, Field<String> fieldName,
                                                   Field<String> argumentName) {
        return concat(typeName, val("."), fieldName, val("("), argumentName, val(":)"));
    }
}
