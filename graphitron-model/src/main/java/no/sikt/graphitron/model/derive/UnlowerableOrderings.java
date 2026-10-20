package no.sikt.graphitron.model.derive;

import graphql.language.SourceLocation;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_DEFAULT_ORDER_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TARGET;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ORDER_BY_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_RESOLVED_TYPE_BINDING;
import static no.sikt.graphitron.model.Tables.GRAPHQL_POLY_MEMBER;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_DOMAIN;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.selectOne;

/**
 * The build error for {@code ORDERING_ACROSS_PARTICIPANTS}: a declared ordering on a field read
 * once per participant, which nothing carries down onto a branch. The verdict is the stored
 * {@code graphitron_entry_defect} row, which the editor reads through the {@code diagnostic} view;
 * what this class adds is the population the build asks about and the message, which names the
 * container and its participants.
 *
 * <p>Read here until entry defects fail the build themselves, which is why the message is still
 * composed rather than taken from the defect's statement.
 *
 * <p>The population is the classification domain: only a coordinate the generator intends to
 * classify can fail a build, and a declaration outside that domain costs no emitted source.
 */
public final class UnlowerableOrderings {

    private UnlowerableOrderings() {}

    /** The defect code this class reads. */
    public static final String CODE = "ORDERING_ACROSS_PARTICIPANTS";

    /** Every relation this component's statements name. */
    public static final Set<Table<?>> READS = Set.of(GRAPHITRON_ENTRY_DEFECT,
        GRAPHITRON_DEFAULT_ORDER_ENTRY, GRAPHITRON_ORDER_BY_ENTRY, GRAPHITRON_FIELD_TARGET,
        GRAPHQL_POLY_MEMBER, GRAPHITRON_RESOLVED_TYPE_BINDING, INTENT_TYPE_DOMAIN, GRAPHQL_TYPE);

    /** Which directive declared the ordering. */
    public enum AvailableVia {
        /** A field-level {@code @defaultOrder}. */
        DEFAULT_ORDER,
        /** An {@code @orderBy} on one of the field's arguments. */
        ORDER_BY_ARGUMENT
    }

    /**
     * One declared ordering the field cannot honour, with what its message needs: the container
     * the field targets, its SDL kind, and the participants the read is one statement per.
     */
    public record Unlowered(String typeName, String fieldName, AvailableVia availableVia,
                            String argumentName, String containerName, String containerKind,
                            List<String> participants, SourceLocation location) {

        public Unlowered {
            participants = List.copyOf(participants);
        }

        /** The coordinate the minted error attaches to. */
        public String coordinate() {
            return typeName + "." + fieldName;
        }
    }

    /** The detection pass's typed product, the rows kept beside the violations they mint. */
    public record Detection(List<Unlowered> unlowered) {

        public Detection {
            unlowered = List.copyOf(unlowered);
        }

        /** The empty detection, for callers running capture without the detection pass. */
        public static Detection empty() {
            return new Detection(List.of());
        }

        /** Every violation, in coordinate-and-route order. */
        public List<ValidationError> violations() {
            return unlowered.stream()
                .map(u -> ValidationError.forField(u.coordinate(), rejectionOf(u), u.location()))
                .toList();
        }
    }

    /** The defect rows over {@code graphName}'s partition whose type is in the classification domain. */
    public static Detection detect(DSLContext dsl, String graphName) {
        return new Detection(read(dsl, graphName, true));
    }

    /** The defect rows over {@code graphName}'s partition, ungated. */
    public static List<Unlowered> rows(DSLContext dsl, String graphName) {
        return read(dsl, graphName, false);
    }

    private static List<Unlowered> read(DSLContext dsl, String graphName, boolean inDomain) {
        var d = GRAPHITRON_DEFAULT_ORDER_ENTRY;
        var ob = GRAPHITRON_ORDER_BY_ENTRY;
        var declared = new ArrayList<Declared>();
        dsl.select(d.TYPE_NAME, d.FIELD_NAME, d.SOURCE_NAME, d.SOURCE_LINE, d.SOURCE_COLUMN)
            .from(d)
            .where(d.GRAPH_NAME.eq(graphName),
                defectAt(d.GRAPH_NAME, d.SOURCE_NAME, d.SOURCE_LINE, d.SOURCE_COLUMN),
                domain(graphName, d.TYPE_NAME, inDomain))
            .forEach(r -> declared.add(new Declared(r.value1(), r.value2(),
                AvailableVia.DEFAULT_ORDER, null, location(r.value3(), r.value4(), r.value5()))));
        dsl.select(ob.TYPE_NAME, ob.FIELD_NAME, ob.ARGUMENT_NAME, ob.SOURCE_NAME, ob.SOURCE_LINE,
                ob.SOURCE_COLUMN)
            .from(ob)
            .where(ob.GRAPH_NAME.eq(graphName),
                defectAt(ob.GRAPH_NAME, ob.SOURCE_NAME, ob.SOURCE_LINE, ob.SOURCE_COLUMN),
                domain(graphName, ob.TYPE_NAME, inDomain))
            .forEach(r -> declared.add(new Declared(r.value1(), r.value2(),
                AvailableVia.ORDER_BY_ARGUMENT, r.value3(),
                location(r.value4(), r.value5(), r.value6()))));
        declared.sort(Comparator.comparing(Declared::typeName).thenComparing(Declared::fieldName)
            .thenComparing(Declared::availableVia)
            .thenComparing(Declared::argumentName,
                Comparator.nullsFirst(Comparator.naturalOrder())));

        var containers = new HashMap<String, Container>();
        var out = new ArrayList<Unlowered>();
        for (var row : declared) {
            var container = containers.computeIfAbsent(row.typeName() + "." + row.fieldName(),
                key -> containerOf(dsl, graphName, row.typeName(), row.fieldName()));
            out.add(new Unlowered(row.typeName(), row.fieldName(), row.availableVia(),
                row.argumentName(), container.name(), container.kind(),
                container.participants(), row.location()));
        }
        return out;
    }

    private record Declared(String typeName, String fieldName, AvailableVia availableVia,
                            String argumentName, SourceLocation location) {}

    private record Container(String name, String kind, List<String> participants) {}

    /** A defect of this code stands at the declaring directive's position. */
    private static Condition defectAt(Field<String> graph, Field<String> sourceName,
                                      Field<Integer> line, Field<Integer> column) {
        var e = GRAPHITRON_ENTRY_DEFECT;
        return exists(selectOne().from(e)
            .where(e.GRAPH_NAME.eq(graph), e.SOURCE_NAME.eq(sourceName), e.SOURCE_LINE.eq(line),
                e.SOURCE_COLUMN.eq(column), e.CODE.eq(CODE)));
    }

    private static Condition domain(String graphName, Field<String> typeName, boolean inDomain) {
        if (!inDomain) {
            return noCondition();
        }
        var d = INTENT_TYPE_DOMAIN;
        return exists(selectOne().from(d)
            .where(d.GRAPH_NAME.eq(graphName), d.TYPE_NAME.eq(typeName)));
    }

    /** The field's target, its kind, and its table-bound members in name order. */
    private static Container containerOf(DSLContext dsl, String graphName, String typeName,
                                         String fieldName) {
        var ft = GRAPHITRON_FIELD_TARGET;
        var t = GRAPHQL_TYPE;
        var target = dsl.select(ft.TARGET_TYPE_NAME, t.KIND)
            .from(ft)
            .leftJoin(t).on(t.GRAPH_NAME.eq(ft.GRAPH_NAME), t.TYPE_NAME.eq(ft.TARGET_TYPE_NAME))
            .where(ft.GRAPH_NAME.eq(graphName), ft.TYPE_NAME.eq(typeName),
                ft.FIELD_NAME.eq(fieldName))
            .fetchOne();
        if (target == null) {
            return new Container(null, null, List.of());
        }
        var m = GRAPHQL_POLY_MEMBER;
        var bt = GRAPHITRON_RESOLVED_TYPE_BINDING;
        var participants = dsl.selectDistinct(m.MEMBER_TYPE_NAME)
            .from(m)
            .join(bt).on(bt.GRAPH_NAME.eq(m.GRAPH_NAME), bt.TYPE_NAME.eq(m.MEMBER_TYPE_NAME),
                bt.CANDIDATES.eq(1))
            .where(m.GRAPH_NAME.eq(graphName), m.CONTAINER_NAME.eq(target.value1()))
            .orderBy(m.MEMBER_TYPE_NAME)
            .fetch(org.jooq.Record1::value1);
        return new Container(target.value1(), target.value2(), participants);
    }

    /**
     * The one mint of this defect's {@link Rejection}: deferred, because the schema is well formed,
     * the directive is real, and the remedy is to drop the declaration or to wait for the lowering.
     */
    public static Rejection rejectionOf(Unlowered row) {
        return Rejection.deferred(fanOutMessage(row));
    }

    /**
     * What the author declared, why the read cannot carry it, and both remedies. The participants
     * are named because the sentence has to say what "one statement per participant" means here.
     */
    private static String fanOutMessage(Unlowered row) {
        String container = "'" + row.containerName() + "'";
        String kindWord = "UNION".equals(row.containerKind()) ? "union" : "interface";
        String read = "A field returning the multitable " + kindWord + " " + container
            + " is read as one statement per participant ("
            + String.join(", ", row.participants())
            + ") and the results are combined on their primary keys, so ";
        return switch (row.availableVia()) {
            case DEFAULT_ORDER -> "@defaultOrder declares an ordering this field cannot honour. "
                + read + "the declared columns are not applied and rows arrive in participant"
                + " primary-key order. Remove the declaration, or return a single @table type.";
            case ORDER_BY_ARGUMENT -> "argument '" + row.argumentName() + "' carries @orderBy,"
                + " which asks for an ordering this field cannot honour. " + read
                + "a client-supplied order is not applied and rows arrive in participant"
                + " primary-key order. Remove the argument's @orderBy, or return a single @table"
                + " type.";
        };
    }

    private static SourceLocation location(String sourceName, Integer line, Integer column) {
        if (line == null || column == null) {
            return null;
        }
        return new SourceLocation(line, column, sourceName);
    }
}
