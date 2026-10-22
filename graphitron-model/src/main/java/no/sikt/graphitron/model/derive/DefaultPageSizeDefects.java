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

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CONNECTION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ELEMENT_ENTRY;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_DOMAIN;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_MAX_ALLOWED_PAGE_SIZE;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.selectOne;

/**
 * The build error for {@code DEFAULT_PAGE_SIZE_ABOVE_MAXIMUM}: a connection carrier whose default
 * page size is larger than the build's {@code <maxAllowedPageSize>}, so the schema advertises a
 * default no request can receive. The verdict is the stored {@code graphitron_entry_defect} row,
 * which the editor reads through the {@code diagnostic} view; what this class adds is the
 * population the build asks about and the message, which names the default and the maximum.
 *
 * <p>Every relation it names is a table, so it expands no rule on a read: the defect's position is
 * either the {@code @asConnection} application whose {@code defaultFirstValue:} set the default, or
 * the declaration of a {@code first} argument the carrier declares itself, and each arm reads the
 * entry it can sit at.
 *
 * <p>The population is the classification domain, on {@link UnlowerableOrderings}'s terms.
 */
public final class DefaultPageSizeDefects {

    private DefaultPageSizeDefects() {}

    /** The defect code this class reads. */
    public static final String CODE = "DEFAULT_PAGE_SIZE_ABOVE_MAXIMUM";

    /** Every relation this component's statements name. */
    public static final Set<Table<?>> READS = Set.of(GRAPHITRON_ENTRY_DEFECT,
        GRAPHITRON_CONNECTION_ENTRY, GRAPHQL_AST_ELEMENT_ENTRY, GRAPHITRON_ARGUMENT,
        STORE_GRAPH_MAX_ALLOWED_PAGE_SIZE, INTENT_TYPE_DOMAIN);

    /** Where the oversized default was written. */
    public enum WrittenAt {
        /** {@code @asConnection(defaultFirstValue:)}. */
        DEFAULT_FIRST_VALUE,
        /** The default of a {@code first} argument the author declared on the carrier. */
        FIRST_ARGUMENT
    }

    /** One carrier whose default page size exceeds the maximum, with what its message needs. */
    public record Oversized(String typeName, String fieldName, WrittenAt writtenAt,
                            String defaultValue, int maximum, SourceLocation location) {

        /** The coordinate the minted error attaches to. */
        public String coordinate() {
            return typeName + "." + fieldName;
        }
    }

    /** The detection pass's typed product, the rows kept beside the violations they mint. */
    public record Detection(List<Oversized> oversized) {

        public Detection {
            oversized = List.copyOf(oversized);
        }

        /** The empty detection, for callers running capture without the detection pass. */
        public static Detection empty() {
            return new Detection(List.of());
        }

        /** Every violation, in coordinate order. */
        public List<ValidationError> violations() {
            return oversized.stream()
                .map(o -> ValidationError.forField(o.coordinate(), rejectionOf(o), o.location()))
                .toList();
        }
    }

    /** The defect rows over {@code graphName}'s partition whose type is in the classification domain. */
    public static Detection detect(DSLContext dsl, String graphName) {
        return new Detection(read(dsl, graphName, true));
    }

    /** The defect rows over {@code graphName}'s partition, ungated. */
    public static List<Oversized> rows(DSLContext dsl, String graphName) {
        return read(dsl, graphName, false);
    }

    private static List<Oversized> read(DSLContext dsl, String graphName, boolean inDomain) {
        var mx = STORE_GRAPH_MAX_ALLOWED_PAGE_SIZE;
        var maximum = dsl.select(mx.MAX_PAGE_SIZE).from(mx)
            .where(mx.GRAPH_NAME.eq(graphName))
            .fetchOne(mx.MAX_PAGE_SIZE);
        if (maximum == null) {
            return List.of();
        }
        var out = new ArrayList<Oversized>();

        var c = GRAPHITRON_CONNECTION_ENTRY;
        dsl.select(c.TYPE_NAME, c.FIELD_NAME, c.DEFAULT_FIRST_VALUE, c.SOURCE_NAME, c.SOURCE_LINE,
                c.SOURCE_COLUMN)
            .from(c)
            .where(c.GRAPH_NAME.eq(graphName),
                defectAt(c.GRAPH_NAME, c.SOURCE_NAME, c.SOURCE_LINE, c.SOURCE_COLUMN),
                domain(graphName, c.TYPE_NAME, inDomain))
            .forEach(r -> out.add(new Oversized(r.value1(), r.value2(),
                WrittenAt.DEFAULT_FIRST_VALUE, String.valueOf(r.value3()), maximum,
                location(r.value4(), r.value5(), r.value6()))));

        var el = GRAPHQL_AST_ELEMENT_ENTRY;
        var a = GRAPHITRON_ARGUMENT;
        dsl.select(a.TYPE_NAME, a.FIELD_NAME, a.DEFAULT_VALUE_SDL, el.SOURCE_NAME, el.SOURCE_LINE,
                el.SOURCE_COLUMN)
            .from(el)
            .join(a).on(a.GRAPH_NAME.eq(el.GRAPH_NAME), a.COORDINATE.eq(el.COORDINATE))
            .where(el.GRAPH_NAME.eq(graphName),
                defectAt(el.GRAPH_NAME, el.SOURCE_NAME, el.SOURCE_LINE, el.SOURCE_COLUMN),
                domain(graphName, a.TYPE_NAME, inDomain))
            .forEach(r -> out.add(new Oversized(r.value1(), r.value2(),
                WrittenAt.FIRST_ARGUMENT, r.value3(), maximum,
                location(r.value4(), r.value5(), r.value6()))));

        out.sort(Comparator.comparing(Oversized::typeName).thenComparing(Oversized::fieldName)
            .thenComparing(Oversized::writtenAt));
        return out;
    }

    /** A defect of this code stands at the position the default was written at. */
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

    /**
     * The one mint of this defect's {@link Rejection}: an author error, the schema stating a default
     * the build's own configuration makes unreachable.
     */
    public static Rejection rejectionOf(Oversized row) {
        return Rejection.structural(message(row));
    }

    /** What was written, what it exceeds, and both remedies. */
    private static String message(Oversized row) {
        String written = switch (row.writtenAt()) {
            case DEFAULT_FIRST_VALUE -> "@asConnection(defaultFirstValue: " + row.defaultValue() + ")";
            case FIRST_ARGUMENT -> "the default of argument 'first' (" + row.defaultValue() + ")";
        };
        return written + " exceeds <maxAllowedPageSize> " + row.maximum()
            + "; no request can receive a page that large. Lower the default to at most "
            + row.maximum() + ", or raise <maxAllowedPageSize>.";
    }

    private static SourceLocation location(String sourceName, Integer line, Integer column) {
        if (line == null || column == null) {
            return null;
        }
        return new SourceLocation(line, column, sourceName);
    }
}
