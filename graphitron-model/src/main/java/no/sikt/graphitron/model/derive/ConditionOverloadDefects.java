package no.sikt.graphitron.model.derive;

import graphql.language.SourceLocation;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import org.jooq.DSLContext;
import org.jooq.Table;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_FIELD_PATH_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_REFERENCE_CONDITION_STEP_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ELEMENT_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_ELEMENT;

/**
 * The build errors for {@code CONDITION_NO_OVERLOAD_FOR_PAIR} and {@code CONDITION_OVERLOAD_TIE}: a
 * path element's condition method that the generated call {@code method(sourceAlias, targetAlias)}
 * cannot resolve to one declaration for the step's two tables, either because no declaration takes
 * both or because several do with none more specific. The verdict is the stored
 * {@code graphitron_entry_defect} row at the {@code {condition:}} element, which the editor reads
 * through the {@code diagnostic} view; what this class adds is the field the element belongs to and
 * the message, which names the method and quotes the row's detail. {@link FieldPaths} writes the
 * rows, in the pass that picks each step's method.
 *
 * <p>Every relation it names is a table, so it expands no rule on a read. Every row is read: a row
 * sits at a step of a field's path, which the walk took only for a field it reaches.
 */
public final class ConditionOverloadDefects {

    private ConditionOverloadDefects() {}

    /** The defect code for a step no declaration of the method takes. */
    public static final String NO_OVERLOAD = FieldPaths.NO_OVERLOAD_FOR_PAIR;

    /** The defect code for a step several declarations take with none more specific. */
    public static final String TIE = FieldPaths.OVERLOAD_TIE;

    /** Every relation this component's statements name. */
    public static final Set<Table<?>> READS = Set.of(GRAPHITRON_ENTRY_DEFECT,
        GRAPHITRON_AST_FIELD_PATH_ENTRY, GRAPHITRON_AST_REFERENCE_CONDITION_STEP_ENTRY,
        GRAPHQL_AST_ELEMENT_ENTRY, GRAPHQL_FIELD_ELEMENT);

    /** Which of the two rules the element breaks. */
    public enum Kind {
        /** No two-parameter declaration takes the step's departing and arriving tables. */
        NO_OVERLOAD,
        /** Several take them and none is more specific than every other. */
        TIE
    }

    /** One condition element whose call resolves to no single declaration, with what its message needs. */
    public record Unresolvable(String typeName, String fieldName, Kind kind, String className,
                               String methodName, String detail, SourceLocation location) {

        /** The coordinate the minted error attaches to. */
        public String coordinate() {
            return typeName + "." + fieldName;
        }
    }

    /** The detection pass's typed product, the rows kept beside the violations they mint. */
    public record Detection(List<Unresolvable> unresolvable) {

        public Detection {
            unresolvable = List.copyOf(unresolvable);
        }

        /** The empty detection, for callers running capture without the detection pass. */
        public static Detection empty() {
            return new Detection(List.of());
        }

        /** Every violation, in coordinate order. */
        public List<ValidationError> violations() {
            return unresolvable.stream()
                .map(u -> ValidationError.forField(u.coordinate(), rejectionOf(u), u.location()))
                .toList();
        }
    }

    /** The defect rows over {@code graphName}'s partition. */
    public static Detection detect(DSLContext dsl, String graphName) {
        return new Detection(rows(dsl, graphName));
    }

    /** The defect rows over {@code graphName}'s partition, in coordinate order. */
    public static List<Unresolvable> rows(DSLContext dsl, String graphName) {
        var d = GRAPHITRON_ENTRY_DEFECT;
        var w = GRAPHITRON_AST_FIELD_PATH_ENTRY;
        var c = GRAPHITRON_AST_REFERENCE_CONDITION_STEP_ENTRY;
        var el = GRAPHQL_AST_ELEMENT_ENTRY;
        var fe = GRAPHQL_FIELD_ELEMENT;
        return dsl.select(fe.TYPE_NAME, fe.FIELD_NAME, d.CODE, c.CLASS_NAME, c.METHOD, d.DETAIL,
                d.SOURCE_NAME, d.SOURCE_LINE, d.SOURCE_COLUMN)
            .from(d)
            .join(c).on(c.GRAPH_NAME.eq(d.GRAPH_NAME), c.SOURCE_NAME.eq(d.SOURCE_NAME),
                c.SOURCE_LINE.eq(d.SOURCE_LINE), c.SOURCE_COLUMN.eq(d.SOURCE_COLUMN))
            .join(w).on(w.GRAPH_NAME.eq(d.GRAPH_NAME), w.SOURCE_NAME.eq(d.SOURCE_NAME),
                w.ELEMENT_LINE.eq(d.SOURCE_LINE), w.ELEMENT_COLUMN.eq(d.SOURCE_COLUMN))
            .join(el).on(el.GRAPH_NAME.eq(w.GRAPH_NAME), el.SOURCE_NAME.eq(w.SOURCE_NAME),
                el.SOURCE_LINE.eq(w.FIELD_LINE), el.SOURCE_COLUMN.eq(w.FIELD_COLUMN))
            .join(fe).on(fe.GRAPH_NAME.eq(el.GRAPH_NAME), fe.COORDINATE.eq(el.COORDINATE))
            .where(d.GRAPH_NAME.eq(graphName), d.CODE.in(NO_OVERLOAD, TIE))
            .fetch(r -> new Unresolvable(r.value1(), r.value2(),
                NO_OVERLOAD.equals(r.value3()) ? Kind.NO_OVERLOAD : Kind.TIE,
                r.value4(), r.value5(), r.value6(),
                location(r.value7(), r.value8(), r.value9())))
            .stream()
            .sorted(Comparator.comparing(Unresolvable::typeName)
                .thenComparing(Unresolvable::fieldName).thenComparing(Unresolvable::kind))
            .toList();
    }

    /**
     * The one mint of these defects' {@link Rejection}: an author error, the schema naming a
     * condition method whose declarations leave the emitted call without exactly one target.
     */
    public static Rejection rejectionOf(Unresolvable row) {
        return Rejection.structural(message(row));
    }

    /** The method, the pair or pairs, which declarations compete where they tie, and the remedy. */
    private static String message(Unresolvable row) {
        String method = "condition method '" + row.className() + "." + row.methodName() + "'";
        String call = "the generated code calls it as " + row.methodName()
            + "(sourceAlias, targetAlias) with the table the step departs and the one it arrives at";
        return switch (row.kind()) {
            case NO_OVERLOAD -> method + ": no overload takes " + row.detail() + "; " + call
                + ". Declare an overload taking that pair, or widen a parameter to Table<?>.";
            case TIE -> method + ": overloads tie for " + row.detail() + ", none more specific than"
                + " the others; " + call + ", so javac cannot choose. Declare an overload exact at"
                + " both ends, or remove one of the competing overloads.";
        };
    }

    private static SourceLocation location(String sourceName, Integer line, Integer column) {
        if (line == null || column == null) {
            return null;
        }
        return new SourceLocation(line, column, sourceName);
    }
}
