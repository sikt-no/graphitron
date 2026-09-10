package no.sikt.graphitron.model.capture.document;

import graphql.language.ArrayValue;
import graphql.language.BooleanValue;
import graphql.language.Directive;
import graphql.language.EnumValue;
import graphql.language.IntValue;
import graphql.language.ObjectField;
import graphql.language.ObjectValue;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.model.grammar.ConstantReferenceGrammar;
import org.jooq.DSLContext;
import org.jooq.Table;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static graphql.language.AstPrinter.printAstCompact;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_TABLE_ENTRY;

/**
 * Writes what a graphitron directive application meant, beside the row saying it was applied.
 *
 * <p>Keyed by the application's own position, which is the key of whichever
 * {@code graphql_ast_*_directive_entry} relation holds the site it was written at, so a row in this
 * family is the decode of exactly one row there. Which declaration the directive was written on,
 * and in which file, is one join away rather than a column.
 *
 * <p>One writer per site, because which kind of node an application sits on is settled while
 * walking the document and a key names one table. A directive the definition admits at more than
 * one site therefore decodes into more than one relation, and the relations are told apart by the
 * one they hang from rather than by a discriminator: {@code @condition} on an output field and on
 * an argument are different populations with different consumers, and the site is already the key.
 *
 * <p>Nothing here resolves, and nothing is quarantined. A written name is cut where its grammar
 * cuts it and kept as typed otherwise; a value of some other shape decodes to null, every argument
 * of every application already standing verbatim in {@code graphql_ast_applied_argument_entry}.
 * Whether the table exists, whether the class is on the classpath and which of two documents the
 * corpus honours are questions for the anchors.
 *
 * <p>This class is the site writers' shared vocabulary for reading an application: the arguments
 * an author wrote, in the shapes graphql-java hands them back. Nothing in it knows a relation.
 */
public final class GraphitronEntries {

    private GraphitronEntries() {}

    /**
     * Makes {@code source}'s decoded rows under {@code graph} be what {@code document}'s
     * applications now say.
     *
     * <p>Runs after the AST entries of the same reading, the rows here hanging off theirs by key.
     */
    public static void write(DSLContext dsl, String graph, String source,
                             TypeDefinitionRegistry document, LocalDateTime touchedAt) {
        GraphitronTypeEntries.write(dsl, graph, source,
            SdlEntries.directivesOnTypes(document), touchedAt);
        GraphitronFieldEntries.write(dsl, graph, source,
            SdlEntries.directivesOnFields(document), touchedAt);
    }

    /**
     * Deletes {@code graph}'s rows of {@code source} that this reading did not touch, across the
     * relations one site writer owns.
     *
     * <p>Listed by the caller rather than found by prefix: a relation a writer gained and did not
     * list would keep its stale rows silently. An application the author moved or deleted is swept
     * by the cascade from the directive row it hung on; what is left for this sweep is the
     * application whose position another directive now occupies.
     */
    static void sweep(DSLContext dsl, String graph, String source, LocalDateTime touchedAt,
                      List<Table<?>> tables) {
        // Table.field(Field) is a lookup by name returning the loop's own typed column, so one
        // relation's three name them on all of them.
        var named = GRAPHITRON_AST_TABLE_ENTRY;
        for (Table<?> table : tables) {
            dsl.deleteFrom(table)
                .where(table.field(named.GRAPH_NAME).eq(graph))
                .and(table.field(named.SOURCE_NAME).eq(source))
                .and(table.field(named.TOUCHED_AT).ne(touchedAt))
                .execute();
        }
    }

    // ------------------------------------------------------------------- reading an application

    /** The applications of one directive name, whatever the site the caller collected them at. */
    static List<Directive> applied(List<SdlEntries.Nested<Directive>> applications, String name) {
        return applications.stream().map(SdlEntries.Nested::node)
            .filter(application -> application.getName().equals(name)).toList();
    }

    /** One object literal of one application's list argument, at the index it was written at. */
    record Element(Directive application, int position, ObjectValue value) {}

    /**
     * Every object literal in {@code argumentName} across {@code applications}, each with the index
     * it was written at. An element that is not an object literal takes its index and contributes
     * no row, so the indices of the ones after it are the ones the author would count.
     */
    static List<Element> elementsOf(List<Directive> applications, String argumentName) {
        var elements = new ArrayList<Element>();
        for (Directive application : applications) {
            int position = 0;
            for (Object written : list(application, argumentName)) {
                if (written instanceof ObjectValue object) {
                    elements.add(new Element(application, position, object));
                }
                position++;
            }
        }
        return elements;
    }

    /** One string of one application's list argument, at the index it was written at. */
    record Written(Directive application, int position, String value) {}

    /**
     * Every string in {@code argumentName} across {@code applications}, numbered the way
     * {@link #elementsOf} numbers object literals: an element of some other shape takes its index
     * and contributes no row.
     */
    static List<Written> writtenIn(List<Directive> applications, String argumentName) {
        var written = new ArrayList<Written>();
        for (Directive application : applications) {
            int position = 0;
            for (Object element : list(application, argumentName)) {
                if (element instanceof StringValue string) {
                    written.add(new Written(application, position, string.getValue()));
                }
                position++;
            }
        }
        return written;
    }

    /**
     * The elements whose {@code handler} field names one kind. An element whose token is none of
     * the kinds the directive declares lands in no relation, its list position spoken for and its
     * text standing in the verbatim argument row.
     */
    static List<Element> ofKind(List<Element> elements, String kind) {
        return elements.stream()
            .filter(element -> kind.equals(tokenOf(inside(element.value(), "handler")))).toList();
    }

    // ------------------------------------------------------------------- reading an argument

    /** The argument's value, or null where the author wrote no such argument. */
    private static Value<?> argument(Directive application, String name) {
        var written = application.getArgument(name);
        return written == null ? null : written.getValue();
    }

    static String string(Directive application, String name) {
        return stringOf(argument(application, name));
    }

    /** A written enum token of an argument, or null where the author wrote none. */
    static String token(Directive application, String name) {
        return tokenOf(argument(application, name));
    }

    /** A written boolean, or null where the argument is absent or of any other shape. */
    static Boolean bool(Directive application, String name) {
        return argument(application, name) instanceof BooleanValue flag ? flag.isValue() : null;
    }

    /** A written integer, or null where the argument is absent or of any other shape. */
    static Integer integer(Directive application, String name) {
        return argument(application, name) instanceof IntValue number ? number.getValue().intValue() : null;
    }

    /** A field of the object literal an argument holds, or null where either is absent. */
    static String inside(Directive application, String argumentName, String fieldName) {
        return argument(application, argumentName) instanceof ObjectValue object
            ? stringOf(inside(object, fieldName)) : null;
    }

    static Value<?> inside(ObjectValue object, String fieldName) {
        for (ObjectField written : object.getObjectFields()) {
            if (written.getName().equals(fieldName)) {
                return written.getValue();
            }
        }
        return null;
    }

    /** A field of an object literal nested inside another, or null where any level is absent. */
    static String inside(ObjectValue object, String fieldName, String nestedName) {
        return inside(object, fieldName) instanceof ObjectValue nested
            ? stringOf(inside(nested, nestedName)) : null;
    }

    /**
     * The elements of an argument written as a list, or none where it was written as anything else.
     * Wildcarded because graphql-java hands them back raw and nothing here needs the element type.
     */
    private static List<?> list(Directive application, String name) {
        return argument(application, name) instanceof ArrayValue array ? array.getValues() : List.of();
    }

    /** A written string, or null where the value is any other shape. */
    static String stringOf(Value<?> value) {
        return value instanceof StringValue written ? written.getValue() : null;
    }

    /** A written enum token or string, either being how an author spells one of a fixed set. */
    static String tokenOf(Value<?> value) {
        return switch (value) {
            case null -> null;
            case EnumValue token -> token.getName();
            case StringValue written -> written.getValue();
            default -> printAstCompact(value);
        };
    }

    static String classPart(String written) {
        return ConstantReferenceGrammar.split(written)
            instanceof ConstantReferenceGrammar.Reference.Parsed parsed ? parsed.classFqn() : null;
    }

    static String fieldPart(String written) {
        return ConstantReferenceGrammar.split(written)
            instanceof ConstantReferenceGrammar.Reference.Parsed parsed ? parsed.fieldName() : null;
    }
}
