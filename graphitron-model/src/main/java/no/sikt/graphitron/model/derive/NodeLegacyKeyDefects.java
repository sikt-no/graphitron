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
import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_LEGACY_KEY_DEFECT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLETYPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLE_ENTRY;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_DOMAIN;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_NODE_LEGACY_KEY_UNREADABLE;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.selectOne;

/**
 * The build errors for a legacy node key shape: the {@code NODE_LEGACY_*} codes, which say why a
 * type id gets no shape to convert old ids through. The verdicts are the stored
 * {@code graphitron_entry_defect} rows the editor reads through the {@code diagnostic} view; what this
 * class adds is the population the build asks about and a message naming each code's remedy.
 *
 * <p>The rules were evaluated by {@link NodeLegacyKeyShapes}, which wrote
 * {@code graphitron_node_legacy_key_defect}; this reads those rows back at the position the rule
 * view placed them, and the unreadable catalog constant beside them, so a defect fails the build
 * exactly where the editor shows it. The population is the classification domain, on
 * {@link DefaultPageSizeDefects}' terms.
 */
public final class NodeLegacyKeyDefects {

    private NodeLegacyKeyDefects() {}

    /** The code of an unreadable {@code __NODE_LEGACY_KEY_COLUMNS}, the one not written by the stage. */
    public static final String UNREADABLE = "NODE_LEGACY_METADATA_UNREADABLE";

    /** Every relation this component's statements name. */
    public static final Set<Table<?>> READS = Set.of(GRAPHITRON_ENTRY_DEFECT,
        GRAPHITRON_NODE_LEGACY_KEY_DEFECT, GRAPHITRON_NODE_ENTRY, GRAPHITRON_TABLE_ENTRY,
        GRAPHITRON_TABLETYPE, SQL_NODE_LEGACY_KEY_UNREADABLE, SQL_COLUMN, INTENT_TYPE_DOMAIN);

    /** One type whose legacy key shape breaks a rule, with what its message needs. */
    public record Broken(String typeName, String code, String detail, SourceLocation location,
                         List<String> columns) {

        public Broken {
            columns = List.copyOf(columns);
        }
    }

    /** The detection pass's typed product, the rows kept beside the violations they mint. */
    public record Detection(List<Broken> broken) {

        public Detection {
            broken = List.copyOf(broken);
        }

        /** The empty detection, for callers running capture without the detection pass. */
        public static Detection empty() {
            return new Detection(List.of());
        }

        /** Every violation, in type order. */
        public List<ValidationError> violations() {
            return broken.stream()
                .map(b -> ValidationError.forType(b.typeName(), rejectionOf(b), b.location()))
                .toList();
        }
    }

    /** The defect rows over {@code graphName}'s partition whose type is in the classification domain. */
    public static Detection detect(DSLContext dsl, String graphName) {
        return new Detection(read(dsl, graphName, true));
    }

    /** The defect rows over {@code graphName}'s partition, ungated. */
    public static List<Broken> rows(DSLContext dsl, String graphName) {
        return read(dsl, graphName, false);
    }

    private static List<Broken> read(DSLContext dsl, String graphName, boolean inDomain) {
        var out = new ArrayList<Broken>();
        var d = GRAPHITRON_NODE_LEGACY_KEY_DEFECT;
        var b = GRAPHITRON_TABLETYPE;

        var n = GRAPHITRON_NODE_ENTRY;
        dsl.select(d.TYPE_NAME, d.CODE, d.DETAIL, n.SOURCE_NAME, n.SOURCE_LINE, n.SOURCE_COLUMN,
                b.TABLE_SOURCE_NAME, b.TABLE_SCHEMA, b.TABLE_NAME)
            .from(d)
            .join(n).on(n.GRAPH_NAME.eq(d.GRAPH_NAME), n.TYPE_NAME.eq(d.TYPE_NAME))
            .join(b).on(b.GRAPH_NAME.eq(d.GRAPH_NAME), b.TYPE_NAME.eq(d.TYPE_NAME))
            .where(d.GRAPH_NAME.eq(graphName), d.SITE.eq(inline("NODE")),
                defectAt(d.GRAPH_NAME, n.SOURCE_NAME, n.SOURCE_LINE, n.SOURCE_COLUMN, d.CODE),
                domain(graphName, d.TYPE_NAME, inDomain))
            .forEach(r -> out.add(new Broken(r.value1(), r.value2(), r.value3(),
                location(r.value4(), r.value5(), r.value6()),
                columnsOf(dsl, r.value7(), r.value8(), r.value9()))));

        var t = GRAPHITRON_TABLE_ENTRY;
        dsl.select(d.TYPE_NAME, d.CODE, d.DETAIL, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN)
            .from(d)
            .join(t).on(t.GRAPH_NAME.eq(d.GRAPH_NAME), t.TYPE_NAME.eq(d.TYPE_NAME))
            .where(d.GRAPH_NAME.eq(graphName), d.SITE.eq(inline("TABLE")),
                defectAt(d.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, d.CODE),
                domain(graphName, d.TYPE_NAME, inDomain))
            .forEach(r -> out.add(new Broken(r.value1(), r.value2(), r.value3(),
                location(r.value4(), r.value5(), r.value6()), List.of())));

        var u = SQL_NODE_LEGACY_KEY_UNREADABLE;
        dsl.select(b.TYPE_NAME, b.TABLE_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN)
            .from(b)
            .join(t).on(t.GRAPH_NAME.eq(b.GRAPH_NAME), t.TYPE_NAME.eq(b.TYPE_NAME))
            .join(u).on(u.SOURCE_NAME.eq(b.TABLE_SOURCE_NAME), u.TABLE_SCHEMA.eq(b.TABLE_SCHEMA),
                u.TABLE_NAME.eq(b.TABLE_NAME))
            .where(b.GRAPH_NAME.eq(graphName),
                defectAt(b.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, inline(UNREADABLE)),
                domain(graphName, b.TYPE_NAME, inDomain))
            .forEach(r -> out.add(new Broken(r.value1(), UNREADABLE, r.value2(),
                location(r.value3(), r.value4(), r.value5()), List.of())));

        out.sort(Comparator.comparing(Broken::typeName).thenComparing(Broken::code));
        return out;
    }

    /** The bound table's columns, the candidates an unresolved spelling is offered. */
    private static List<String> columnsOf(DSLContext dsl, String source, String schema, String table) {
        var c = SQL_COLUMN;
        return dsl.select(c.COLUMN_NAME).from(c)
            .where(c.SOURCE_NAME.eq(source), c.TABLE_SCHEMA.eq(schema), c.TABLE_NAME.eq(table))
            .orderBy(c.ORDINAL)
            .fetch(c.COLUMN_NAME);
    }

    /** A defect of the code stands at the position the rule view placed it. */
    private static Condition defectAt(Field<String> graph, Field<String> sourceName,
                                      Field<Integer> line, Field<Integer> column, Field<String> code) {
        var e = GRAPHITRON_ENTRY_DEFECT;
        return exists(selectOne().from(e)
            .where(e.GRAPH_NAME.eq(graph), e.SOURCE_NAME.eq(sourceName), e.SOURCE_LINE.eq(line),
                e.SOURCE_COLUMN.eq(column), e.CODE.eq(code)));
    }

    private static Condition domain(String graphName, Field<String> typeName, boolean inDomain) {
        if (!inDomain) {
            return noCondition();
        }
        var d = INTENT_TYPE_DOMAIN;
        return exists(selectOne().from(d)
            .where(d.GRAPH_NAME.eq(graphName), d.TYPE_NAME.eq(typeName)));
    }

    /**
     * The one mint of these defects' {@link Rejection}s: an unknown name for the unresolved spelling,
     * offered the bound table's columns, and an author error naming the remedy for the rest.
     */
    public static Rejection rejectionOf(Broken row) {
        if (row.code().equals("NODE_LEGACY_KEY_COLUMN_UNRESOLVED")) {
            return Rejection.unknownNodeIdKeyColumn(
                "@node(legacyKeyColumns:) names '" + row.detail() + "', which is not a column of the"
                    + " table '" + row.typeName() + "' is bound to",
                row.detail(), row.columns());
        }
        return Rejection.structural(message(row));
    }

    private static String message(Broken row) {
        return switch (row.code()) {
            case "NODE_LEGACY_KEY_MISSES_KEY_COLUMN" -> "the legacy key shape of '" + row.typeName()
                + "' leaves out current key column '" + row.detail() + "', so an old id cannot be"
                + " converted without reading the row; list every current key column in it";
            case "NODE_LEGACY_KEY_REORDERS_KEY" -> "the legacy key shape of '" + row.typeName()
                + "' (" + row.detail() + ") holds exactly the current key's columns, so an old id and"
                + " a current one cannot be told apart; a legacy shape is the current key plus at"
                + " least one more column";
            case "NODE_LEGACY_KEY_REPEATS_COLUMN" -> "the legacy key shape of '" + row.typeName()
                + "' names column '" + row.detail() + "' twice";
            case "NODE_LEGACY_KEY_DISAGREES_WITH_METADATA" -> "@node(legacyKeyColumns:) on '"
                + row.typeName() + "' differs from the table class's __NODE_LEGACY_KEY_COLUMNS ("
                + row.detail() + "); state the same columns in the same order, or only one of them";
            case "NODE_LEGACY_TYPE_ID_SHARED" -> "type id '" + row.detail() + "' of '" + row.typeName()
                + "' has a legacy key shape and is answered to by types over different tables or"
                + " with different current keys, so converting its ids would rewrite ids another type"
                + " owns; give the types their own type ids";
            case UNREADABLE -> "the generated class of table '" + row.detail() + "', which '"
                + row.typeName() + "' is bound to, declares __NODE_LEGACY_KEY_COLUMNS graphitron"
                + " cannot use; fix the generator configuration that writes it";
            default -> throw new IllegalStateException("not a legacy key defect: " + row.code());
        };
    }

    private static SourceLocation location(String sourceName, Integer line, Integer column) {
        if (line == null || column == null) {
            return null;
        }
        return new SourceLocation(line, column, sourceName);
    }
}
