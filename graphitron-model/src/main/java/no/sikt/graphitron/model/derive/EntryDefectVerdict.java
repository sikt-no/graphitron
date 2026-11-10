package no.sikt.graphitron.model.derive;

import graphql.language.SourceLocation;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import org.jooq.DSLContext;
import org.jooq.Table;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_PAIR_DEFECT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DEFECT_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_SITE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_SESSION_HANDLE_MEMBER;

/**
 * The store's verdict on a graph: every error-severity entry defect, each at the position it was
 * written. A build reads it before anything else judges the schema, so a graph the store refuses
 * stops here and is not classified at all; a warning-severity defect is no part of it.
 *
 * <p>The message is what the editor shows for the same row: the code's statement, the row's detail
 * after it, and the code itself, so the build and the editor never say two things about one defect.
 */
public final class EntryDefectVerdict {

    private EntryDefectVerdict() {}

    /** Every relation this component's statements name. */
    public static final Set<Table<?>> READS = Set.of(GRAPHITRON_ENTRY_DEFECT_SITE, GRAPHITRON_DEFECT_TYPE,
        GRAPHITRON_ARGMAPPING_PAIR_DEFECT, GRAPHITRON_SESSION_HANDLE_MEMBER);

    /** One stored defect with what its message and position need. */
    public record Row(String code, String statement, String detail, String coordinate,
                      String typeName, String fieldName, SourceLocation location) {}

    /**
     * The graph's error-severity defects, in document and position order. A defect on an
     * {@code argMapping} string states no detail of its own; its offending entries are rows under
     * it, joined here in written order as the detail the message quotes.
     */
    public static List<Row> rows(DSLContext dsl, String graphName) {
        var d = GRAPHITRON_ENTRY_DEFECT_SITE;
        var t = GRAPHITRON_DEFECT_TYPE;
        var e = GRAPHITRON_ARGMAPPING_PAIR_DEFECT;
        var entries = dsl.select(e.SOURCE_NAME, e.SOURCE_LINE, e.SOURCE_COLUMN, e.CODE, e.DETAIL)
            .from(e)
            .where(e.GRAPH_NAME.eq(graphName), e.DETAIL.isNotNull())
            .orderBy(e.POSITION)
            .fetch()
            .stream()
            .collect(Collectors.groupingBy(
                r -> List.of(r.value1(), r.value2(), r.value3(), r.value4()),
                Collectors.mapping(r -> r.value5(), Collectors.joining("; "))));
        return dsl.select(d.CODE, t.STATEMENT, d.DETAIL, d.COORDINATE, d.TYPE_NAME, d.FIELD_NAME,
                d.SOURCE_NAME, d.SOURCE_LINE, d.SOURCE_COLUMN)
            .from(d)
            .join(t).on(t.CODE.eq(d.CODE))
            .where(d.GRAPH_NAME.eq(graphName), t.SEVERITY.eq("error"))
            .orderBy(d.SOURCE_NAME, d.SOURCE_LINE, d.SOURCE_COLUMN, d.CODE)
            .fetch(r -> new Row(r.value1(), r.value2(),
                r.value3() != null || r.value7() == null || r.value8() == null || r.value9() == null
                    ? r.value3()
                    : entries.get(List.of(r.value7(), r.value8(), r.value9(), r.value1())),
                r.value4(), r.value5(),
                r.value6(), r.value8() == null || r.value9() == null
                    ? null
                    : new SourceLocation(r.value8(), r.value9(), r.value7())));
    }

    /**
     * What an unknown {@code $session} member's message adds: the members the graph's handle does
     * offer, so the author can pick one.
     */
    public static String offeredMembers(DSLContext dsl, String graphName) {
        var m = GRAPHITRON_SESSION_HANDLE_MEMBER;
        var offered = dsl.selectDistinct(m.MEMBER_NAME)
            .from(m)
            .where(m.GRAPH_NAME.eq(graphName))
            .orderBy(m.MEMBER_NAME)
            .fetch(m.MEMBER_NAME);
        return "The handle offers " + (offered.isEmpty() ? "no members." : String.join(", ", offered) + ".");
    }

    /**
     * What an unresolved projection's message adds: the key columns it could have meant, the rows of
     * {@code graphitron_entry_defect_key_column} under the defect, in key order.
     */
    public static String keyColumns(DSLContext dsl, String graphName, Row row) {
        var k = no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_KEY_COLUMN;
        var n = no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID;
        if (row.location() == null) {
            return null;
        }
        var columns = dsl.selectDistinct(n.BOUND, n.POSITION, n.COLUMN_NAME)
            .from(k)
            .join(n).on(n.GRAPH_NAME.eq(k.GRAPH_NAME), n.COORDINATE.eq(k.LANDING_COORDINATE),
                n.BOUND.eq(k.BOUND), n.POSITION.eq(k.POSITION))
            .where(k.GRAPH_NAME.eq(graphName), k.CODE.eq(row.code()),
                k.SOURCE_NAME.eq(row.location().getSourceName()), k.SOURCE_LINE.eq(row.location().getLine()),
                k.SOURCE_COLUMN.eq(row.location().getColumn()))
            .orderBy(n.BOUND, n.POSITION)
            .fetch(n.COLUMN_NAME);
        return columns.isEmpty() ? null : "open it with one of them: " + String.join(", ", columns);
    }

    /** The build error one defect becomes, with nothing more to say about it. */
    public static ValidationError error(Row row) {
        return error(row, null);
    }

    /**
     * The build error one defect becomes: the statement, the detail in parentheses where there is
     * one, an explanation the caller found out about the detail where it has one, and the code in
     * brackets, prefixed as the walk prefixes a field's or a type's error, or attached to the schema
     * where the defect is about neither.
     */
    public static ValidationError error(Row row, String explanation) {
        var rejection = Rejection.structural((row.detail() == null
            ? row.statement()
            : row.statement() + " (" + row.detail() + ")")
            + (explanation == null ? "" : " " + explanation) + " [" + row.code() + "]");
        if (row.fieldName() != null) {
            return ValidationError.forField(row.coordinate(), rejection, row.location());
        }
        if (row.typeName() != null) {
            return ValidationError.forType(row.typeName(), rejection, row.location());
        }
        return new ValidationError("<schema>", rejection, row.location());
    }
}
