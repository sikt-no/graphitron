package no.sikt.graphitron.model.capture.document;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.TableField;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_APPLIED_ARGUMENT_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_DIRECTIVE_ARGUMENT_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_DIRECTIVE_DEFINITION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_DIRECTIVE_LOCATION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ENUM_VALUE_DEFINITION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ENUM_VALUE_DIRECTIVE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_FIELD_ARGUMENT_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_FIELD_DEFINITION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_FIELD_DIRECTIVE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_IMPLEMENTS_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_INPUT_FIELD_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_INPUT_VALUE_DIRECTIVE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_OPERATION_TYPE_DEFINITION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_SCHEMA_DEFINITION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_SCHEMA_DIRECTIVE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_TYPE_DECLARATION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_TYPE_DIRECTIVE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_UNION_MEMBER_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_VALUE_ENTRY;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.val;

/**
 * Fills {@code graphql_ast_entry}: every written position the entry stratum holds, with the element
 * that encloses it.
 *
 * <p>Nineteen arms, written in the order a position's enclosing element becomes knowable. Five of
 * the entry relations carry a generated {@code coordinate} and are their own answer. The rest take
 * their parent's, which is why the arms run root first: an arm reads the rows an earlier arm wrote
 * rather than resolving a chain of its own, so the rule "the nearest ancestor that declares an
 * element" is applied once here and not nineteen times in nineteen shapes that could each drift.
 *
 * <p>Three of the nineteen name a parent whose relation is not fixed, a directive on an input value
 * sitting on any of three parents and an applied argument inside any of five applications. They are
 * exactly the arms that resolve against this relation rather than against a named one, which is what
 * it buys a reader. It buys the schema nothing: those parent columns still carry a position with no
 * foreign key, and cannot gain one here, because this is derived from the arms and written after
 * them, so the constraint would have to hold before the row it names existed.
 *
 * <p>Values are the one arm that nests inside itself to no fixed depth, so it runs as a fixpoint:
 * each pass inserts the values whose parent this relation already holds, and the pass that inserts
 * nothing ends it. Their enclosing element could have been read in one statement off
 * {@code holder_line}, which every value carries however deep it sits, but the parent edge would
 * then skip the values in between and this relation would state a tree the stratum does not have.
 */
final class AstEntries {

    private AstEntries() {}

    /**
     * Makes {@code graph}'s entry index be what its entry relations now say.
     *
     * <p>The instant is the caller's, on every other anchor's terms, and this runs after the element
     * anchors because every row it writes carries a foreign key into one.
     */
    static void write(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        // Roots: written inside nothing, and their own element or none.
        var d = GRAPHQL_AST_TYPE_DECLARATION_ENTRY;
        root(dsl, graph, touchedAt, d, EntryKind.TYPE_DECLARATION, d.COORDINATE);
        var dd = GRAPHQL_AST_DIRECTIVE_DEFINITION_ENTRY;
        root(dsl, graph, touchedAt, dd, EntryKind.DIRECTIVE_DEFINITION, inline((String) null));
        var sd = GRAPHQL_AST_SCHEMA_DEFINITION_ENTRY;
        root(dsl, graph, touchedAt, sd, EntryKind.SCHEMA_DEFINITION, inline((String) null));

        // Written inside a type declaration. The three that declare an element carry their own.
        var f = GRAPHQL_AST_FIELD_DEFINITION_ENTRY;
        child(dsl, graph, touchedAt, f, EntryKind.FIELD_DEFINITION, f.PARENT_LINE, f.PARENT_COLUMN, f.COORDINATE);
        var ev = GRAPHQL_AST_ENUM_VALUE_DEFINITION_ENTRY;
        child(dsl, graph, touchedAt, ev, EntryKind.ENUM_VALUE_DEFINITION, ev.PARENT_LINE, ev.PARENT_COLUMN,
            ev.COORDINATE);
        var inf = GRAPHQL_AST_INPUT_FIELD_ENTRY;
        child(dsl, graph, touchedAt, inf, EntryKind.INPUT_FIELD, inf.PARENT_LINE, inf.PARENT_COLUMN,
            inf.COORDINATE);
        var im = GRAPHQL_AST_IMPLEMENTS_ENTRY;
        inherited(dsl, graph, touchedAt, im, EntryKind.IMPLEMENTS, im.PARENT_LINE, im.PARENT_COLUMN);
        var um = GRAPHQL_AST_UNION_MEMBER_ENTRY;
        inherited(dsl, graph, touchedAt, um, EntryKind.UNION_MEMBER, um.PARENT_LINE, um.PARENT_COLUMN);
        var td = GRAPHQL_AST_TYPE_DIRECTIVE_ENTRY;
        inherited(dsl, graph, touchedAt, td, EntryKind.TYPE_DIRECTIVE, td.PARENT_LINE, td.PARENT_COLUMN);

        // Written inside a directive definition or the schema block, neither of which is an element.
        var dl = GRAPHQL_AST_DIRECTIVE_LOCATION_ENTRY;
        inherited(dsl, graph, touchedAt, dl, EntryKind.DIRECTIVE_LOCATION, dl.PARENT_LINE, dl.PARENT_COLUMN);
        var da = GRAPHQL_AST_DIRECTIVE_ARGUMENT_ENTRY;
        inherited(dsl, graph, touchedAt, da, EntryKind.DIRECTIVE_ARGUMENT, da.PARENT_LINE, da.PARENT_COLUMN);
        var op = GRAPHQL_AST_OPERATION_TYPE_DEFINITION_ENTRY;
        inherited(dsl, graph, touchedAt, op, EntryKind.OPERATION_TYPE_DEFINITION, op.PARENT_LINE,
            op.PARENT_COLUMN);
        var sdir = GRAPHQL_AST_SCHEMA_DIRECTIVE_ENTRY;
        inherited(dsl, graph, touchedAt, sdir, EntryKind.SCHEMA_DIRECTIVE, sdir.PARENT_LINE,
            sdir.PARENT_COLUMN);

        // Written inside a field or an enum value.
        var fa = GRAPHQL_AST_FIELD_ARGUMENT_ENTRY;
        child(dsl, graph, touchedAt, fa, EntryKind.FIELD_ARGUMENT, fa.PARENT_LINE, fa.PARENT_COLUMN,
            fa.COORDINATE);
        var fd = GRAPHQL_AST_FIELD_DIRECTIVE_ENTRY;
        inherited(dsl, graph, touchedAt, fd, EntryKind.FIELD_DIRECTIVE, fd.PARENT_LINE, fd.PARENT_COLUMN);
        var evd = GRAPHQL_AST_ENUM_VALUE_DIRECTIVE_ENTRY;
        inherited(dsl, graph, touchedAt, evd, EntryKind.ENUM_VALUE_DIRECTIVE, evd.PARENT_LINE,
            evd.PARENT_COLUMN);

        // The two arms whose parent relation is not fixed, resolved against what is already here.
        var ivd = GRAPHQL_AST_INPUT_VALUE_DIRECTIVE_ENTRY;
        inherited(dsl, graph, touchedAt, ivd, EntryKind.INPUT_VALUE_DIRECTIVE, ivd.PARENT_LINE,
            ivd.PARENT_COLUMN);
        var aa = GRAPHQL_AST_APPLIED_ARGUMENT_ENTRY;
        inherited(dsl, graph, touchedAt, aa, EntryKind.APPLIED_ARGUMENT, aa.PARENT_LINE, aa.PARENT_COLUMN);

        values(dsl, graph, touchedAt);
    }

    /** An entry written inside nothing: its own coordinate where it declares one, else none. */
    private static void root(DSLContext dsl, String graph, LocalDateTime touchedAt,
                             Table<?> entry, EntryKind kind, Field<String> coordinate) {
        var e = GRAPHQL_AST_ENTRY;
        var source = entry.field(GRAPHQL_AST_ENTRY.SOURCE_NAME);
        var line = entry.field(GRAPHQL_AST_ENTRY.SOURCE_LINE);
        var column = entry.field(GRAPHQL_AST_ENTRY.SOURCE_COLUMN);
        dsl.insertInto(e)
            .columns(e.GRAPH_NAME, e.SOURCE_NAME, e.SOURCE_LINE, e.SOURCE_COLUMN, e.ENTRY_KIND,
                e.PARENT_LINE, e.PARENT_COLUMN, e.ELEMENT_COORDINATE, e.TOUCHED_AT)
            .select(dsl
                .select(val(graph, e.GRAPH_NAME), source, line, column, inline(kind, e.ENTRY_KIND),
                    inline((Integer) null), inline((Integer) null), coordinate,
                    val(touchedAt, e.TOUCHED_AT))
                .from(entry)
                .where(entry.field(GRAPHQL_AST_ENTRY.GRAPH_NAME).eq(graph)))
            .onDuplicateKeyUpdate()
            .set(e.ENTRY_KIND, excluded(e.ENTRY_KIND))
            .set(e.PARENT_LINE, excluded(e.PARENT_LINE))
            .set(e.PARENT_COLUMN, excluded(e.PARENT_COLUMN))
            .set(e.ELEMENT_COORDINATE, excluded(e.ELEMENT_COORDINATE))
            .set(e.TOUCHED_AT, excluded(e.TOUCHED_AT))
            .execute();
    }

    /** An entry that declares an element of its own, written inside another entry. */
    private static void child(DSLContext dsl, String graph, LocalDateTime touchedAt,
                              Table<?> entry, EntryKind kind, TableField<?, Integer> parentLine,
                              TableField<?, Integer> parentColumn, Field<String> coordinate) {
        insert(dsl, graph, touchedAt, entry, kind, parentLine, parentColumn, coordinate);
    }

    /**
     * An entry that declares no element, taking the one its parent sits in. Null where the parent
     * has none, which is what the schema block and the directive definitions leave behind.
     */
    private static void inherited(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                  Table<?> entry, EntryKind kind, TableField<?, Integer> parentLine,
                                  TableField<?, Integer> parentColumn) {
        insert(dsl, graph, touchedAt, entry, kind, parentLine, parentColumn, null);
    }

    /**
     * One arm. {@code coordinate} null means the arm inherits its parent's, which is read off this
     * relation rather than off the parent's own table: three arms have no fixed parent relation, and
     * reading the index for all of them is one rule rather than a special case beside a general one.
     *
     * <p>The parent is matched on this reading's instant as well as its position, so an arm resolves
     * against what this reading wrote rather than against a row the sweep is about to remove.
     */
    private static void insert(DSLContext dsl, String graph, LocalDateTime touchedAt,
                               Table<?> entry, EntryKind kind, TableField<?, Integer> parentLine,
                               TableField<?, Integer> parentColumn, Field<String> coordinate) {
        var e = GRAPHQL_AST_ENTRY;
        var p = GRAPHQL_AST_ENTRY.as("parent");
        var source = entry.field(GRAPHQL_AST_ENTRY.SOURCE_NAME);
        var line = entry.field(GRAPHQL_AST_ENTRY.SOURCE_LINE);
        var column = entry.field(GRAPHQL_AST_ENTRY.SOURCE_COLUMN);
        var step = dsl
            .select(val(graph, e.GRAPH_NAME), source, line, column, inline(kind, e.ENTRY_KIND),
                parentLine, parentColumn,
                coordinate == null ? p.ELEMENT_COORDINATE : coordinate,
                val(touchedAt, e.TOUCHED_AT))
            .from(entry)
            .join(p).on(p.GRAPH_NAME.eq(val(graph, e.GRAPH_NAME)),
                p.SOURCE_NAME.eq(source), p.SOURCE_LINE.eq(parentLine),
                p.SOURCE_COLUMN.eq(parentColumn), p.TOUCHED_AT.eq(val(touchedAt, e.TOUCHED_AT)))
            .where(entry.field(GRAPHQL_AST_ENTRY.GRAPH_NAME).eq(graph));
        dsl.insertInto(e)
            .columns(e.GRAPH_NAME, e.SOURCE_NAME, e.SOURCE_LINE, e.SOURCE_COLUMN, e.ENTRY_KIND,
                e.PARENT_LINE, e.PARENT_COLUMN, e.ELEMENT_COORDINATE, e.TOUCHED_AT)
            .select(step)
            .onDuplicateKeyUpdate()
            .set(e.ENTRY_KIND, excluded(e.ENTRY_KIND))
            .set(e.PARENT_LINE, excluded(e.PARENT_LINE))
            .set(e.PARENT_COLUMN, excluded(e.PARENT_COLUMN))
            .set(e.ELEMENT_COORDINATE, excluded(e.ELEMENT_COORDINATE))
            .set(e.TOUCHED_AT, excluded(e.TOUCHED_AT))
            .execute();
    }

    /**
     * Values, as a fixpoint. A value is written inside the value that encloses it where there is
     * one and inside the node holding the whole expression otherwise, so each pass reaches one level
     * further and the pass that inserts nothing has reached them all. Bounded by the nesting the
     * author wrote, which no schema makes deep.
     */
    private static void values(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var e = GRAPHQL_AST_ENTRY;
        var v = GRAPHQL_AST_VALUE_ENTRY;
        var p = GRAPHQL_AST_ENTRY.as("parent");
        var parentLine = coalesce(v.PARENT_LINE, v.HOLDER_LINE);
        var parentColumn = coalesce(v.PARENT_COLUMN, v.HOLDER_COLUMN);
        int inserted;
        do {
            inserted = dsl.insertInto(e)
                .columns(e.GRAPH_NAME, e.SOURCE_NAME, e.SOURCE_LINE, e.SOURCE_COLUMN, e.ENTRY_KIND,
                    e.PARENT_LINE, e.PARENT_COLUMN, e.ELEMENT_COORDINATE, e.TOUCHED_AT)
                .select(dsl
                    .select(val(graph, e.GRAPH_NAME), v.SOURCE_NAME, v.SOURCE_LINE, v.SOURCE_COLUMN,
                        inline(EntryKind.VALUE, e.ENTRY_KIND), parentLine, parentColumn, p.ELEMENT_COORDINATE,
                        val(touchedAt, e.TOUCHED_AT))
                    .from(v)
                    .join(p).on(p.GRAPH_NAME.eq(val(graph, e.GRAPH_NAME)),
                        p.SOURCE_NAME.eq(v.SOURCE_NAME), p.SOURCE_LINE.eq(parentLine),
                        p.SOURCE_COLUMN.eq(parentColumn),
                        p.TOUCHED_AT.eq(val(touchedAt, e.TOUCHED_AT)))
                    .where(v.GRAPH_NAME.eq(graph))
                    .andNotExists(dsl.selectOne().from(e)
                        .where(e.GRAPH_NAME.eq(graph), e.SOURCE_NAME.eq(v.SOURCE_NAME),
                            e.SOURCE_LINE.eq(v.SOURCE_LINE), e.SOURCE_COLUMN.eq(v.SOURCE_COLUMN),
                            e.TOUCHED_AT.eq(touchedAt))))
                .onDuplicateKeyUpdate()
                .set(e.ENTRY_KIND, excluded(e.ENTRY_KIND))
                .set(e.PARENT_LINE, excluded(e.PARENT_LINE))
                .set(e.PARENT_COLUMN, excluded(e.PARENT_COLUMN))
                .set(e.ELEMENT_COORDINATE, excluded(e.ELEMENT_COORDINATE))
                .set(e.TOUCHED_AT, excluded(e.TOUCHED_AT))
                .execute();
        } while (inserted > 0);
    }
}
