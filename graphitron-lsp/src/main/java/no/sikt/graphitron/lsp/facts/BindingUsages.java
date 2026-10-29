package no.sikt.graphitron.lsp.facts;

import no.sikt.graphitron.model.read.StoreHandle;
import org.eclipse.lsp4j.Location;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Record3;
import org.jooq.Result;
import org.jooq.Table;
import org.jooq.TableField;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_METHOD_REFERENCE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_TABLE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_REFERENCE_KEY_STEP_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_REFERENCE_TABLE_STEP_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_VALUE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DECLARATION;
import static no.sikt.graphitron.model.Tables.INTENT_BOUND_TABLE;
import static no.sikt.graphitron.model.Tables.INTENT_COLUMN_MATCH_CLAIM;

/**
 * Every SDL coordinate that binds a given target: the population behind find-references when the
 * cursor sits inside a directive argument, and the reverse of the jump {@code Definitions} makes
 * from the same cursor. That one asks what this name denotes and leaves SDL to land on it; this asks
 * who else denotes the same thing and stays in SDL to list them.
 *
 * <p>The decoded {@code graphitron_} relations are the reverse index already: each is keyed by SDL
 * coordinate and carries the bound target as a payload column, so "who binds this?" is a filter
 * rather than a walk. Most of them carry their own {@code source_name} / {@code source_line} /
 * {@code source_column} too, so a site needs no join back to the generic directive census. A path
 * step is read off the transcription's step twins and located at the name it wrote.
 *
 * <p>Three matching rules appear here and the differences between them are deliberate, because a
 * reader deserves to know which of their answers is resolution-exact and which is name-exact.
 * Where a resolution view exists ({@code intent_bound_table} for a table binding,
 * {@code intent_column_match_claim} for a column) the match is on the resolved target, so
 * {@code @table(name: "film")} and {@code @table(name: "public.film")} find each other and neither
 * finds a same-named table in another schema. A reference path's hop has no resolution view, so it
 * matches on the name as written, case-insensitively, with a qualifier narrowing rather than
 * widening; that is the latitude {@link CatalogColumns} and {@link CatalogTables} already take with
 * a spelling. A class or method name has no resolution view either, and matches exactly: a Java
 * identifier is case-sensitive and its package is part of the name rather than a qualifier that
 * could narrow, so folding case here would report {@code com.example.Films} as a use of
 * {@code com.example.films}.
 */
public final class BindingUsages {

    private BindingUsages() {}

    /**
     * Every coordinate naming {@code fqn} as its class. Nine relations carry a class name with a
     * position: the five field- and type-level directives that name one directly, the two condition
     * families, and the three reference-step families through their parent reference row.
     *
     * <p>Two carriers are deliberately absent. The deprecated {@code @record(className:)} binds no
     * live class, so the store decodes it nowhere and listing it would show an author a site the
     * generator ignores; the definition surface carves the same directive out for the same reason.
     * {@code graphitron_error_handler_entry} names a class and records no position for it, so there
     * is nowhere to send an editor.
     */
    public static List<Location> ofClass(StoreHandle store, String fqn) {
        if (fqn == null || fqn.isEmpty()) return List.of();
        return sorted(locations(classSites(store, fqn, null)));
    }

    /**
     * Every coordinate naming {@code method} on {@code fqn}. The same nine relations, narrowed by
     * the method column, since a method reference is a class reference that also named a member.
     * Overloads are not told apart: the relations hold the name an author wrote, and an author
     * writes a name rather than a signature.
     */
    public static List<Location> ofMethod(StoreHandle store, String fqn, String method) {
        if (fqn == null || fqn.isEmpty() || method == null || method.isEmpty()) return List.of();
        return sorted(locations(classSites(store, fqn, method)));
    }

    /**
     * Every coordinate bound to one of {@code tables}: the types whose {@code @table} resolves
     * there, and the {@code @reference} path hops that navigate to it.
     *
     * <p>The two halves match differently, per the class note above. A type binding goes through
     * {@code intent_bound_table}, which has already resolved the author's spelling, so the match is
     * exact. A path hop carries only what was written, so it matches on the table's name
     * case-insensitively and, where the hop named a namespace, on that too; an unqualified hop
     * against a name two schemas share is ambiguous in the same way the generator finds it
     * ambiguous.
     */
    public static List<Location> ofTable(StoreHandle store, List<CatalogTable> tables) {
        if (tables.isEmpty()) return List.of();
        var sites = new ArrayList<Location>();
        for (var table : tables) {
            sites.addAll(locations(boundTypeSites(store, table)));
            sites.addAll(locations(hopSites(store, spelledTable(table))));
        }
        return sorted(sites);
    }

    /**
     * Every coordinate bound to {@code columnName} on {@code table}, read off the column-match
     * claim so the answer is the resolution's rather than a name comparison's.
     *
     * <p>The claim covers a field that binds the column with {@code @field(name:)} and a field whose
     * own name matches it with no directive at all, and both belong in the answer: an author asking
     * what else maps to this column is asking about the generator's reading, and the generator
     * reads them the same way.
     */
    public static List<Location> ofColumn(StoreHandle store, CatalogTable table, String columnName) {
        if (table == null || columnName == null || columnName.isEmpty()) return List.of();
        var rows = store.dsl()
            .select(INTENT_COLUMN_MATCH_CLAIM.SOURCE_NAME, INTENT_COLUMN_MATCH_CLAIM.SOURCE_LINE,
                INTENT_COLUMN_MATCH_CLAIM.SOURCE_COLUMN)
            .from(INTENT_COLUMN_MATCH_CLAIM)
            .where(INTENT_COLUMN_MATCH_CLAIM.GRAPH_NAME.eq(store.graphName()))
            .and(INTENT_COLUMN_MATCH_CLAIM.TABLE_SOURCE_NAME.eq(table.sourceName()))
            .and(INTENT_COLUMN_MATCH_CLAIM.TABLE_SCHEMA.eq(table.schema()))
            .and(INTENT_COLUMN_MATCH_CLAIM.TABLE_NAME.eq(table.tableName()))
            .and(INTENT_COLUMN_MATCH_CLAIM.COLUMN_NAME.eq(columnName))
            .fetch();
        return sorted(locations(rows));
    }

    /**
     * Every {@code @reference} path hop keyed on one of {@code keys}. Matched on the constraint name
     * as written, narrowed by namespace where the hop wrote one, which is the rule
     * {@link CatalogKeys#named} applies in the other direction.
     */
    public static List<Location> ofKey(StoreHandle store, List<CatalogKeys.Key> keys) {
        if (keys.isEmpty()) return List.of();
        var sites = new ArrayList<Location>();
        for (var key : keys) {
            // A hop writes one of the two spellings the resolver accepts, the SQL constraint name
            // or the generated constant, and which one is the author's choice rather than a
            // property of the key. Matching one alone answers half the population.
            sites.addAll(locations(hopSites(store,
                new Spelled(key.schema(), List.of(key.name(), key.constant()), true))));
        }
        return sorted(sites);
    }

    /**
     * The names a hop may have written for one target, with the namespace that scopes them. More
     * than one name because a foreign key answers to two spellings, the SQL constraint name and the
     * generated constant; a table answers to one.
     */
    private record Spelled(String namespace, List<String> names, boolean isKey) {}

    private static Spelled spelledTable(CatalogTable table) {
        return new Spelled(table.schema(), List.of(table.tableName()), false);
    }

    /**
     * Every site that names a class, as one scan. It used to be the nine class-naming relations
     * unioned by hand, three of the arms joining back to the owning application only to reach its
     * source position; the store now writes that fact once, positioned, so this is a scan of
     * {@code graphitron_method_reference_entry} with the class predicate on it.
     *
     * <p>The population widened slightly in the move and in the direction of correctness. The
     * hand-written union had nine arms against eleven sites, and the two it omitted were the
     * argument-site {@code @referenceFor} step, which the validator rejects today so no store has
     * one, and nothing else: the two condition sites share a relation, so both were always covered.
     * A class named inside a {@code @reference} path is as much a use of it as one named by
     * {@code @service}, which is why every site counts here and why the definition surface routes
     * those through its service half.
     */
    private static Result<Record3<String, Integer, Integer>> classSites(
        StoreHandle store, String fqn, String method
    ) {
        var m = GRAPHITRON_METHOD_REFERENCE_ENTRY;
        return store.dsl()
            .select(m.SOURCE_NAME, m.SOURCE_LINE, m.SOURCE_COLUMN)
            .from(m)
            .where(m.GRAPH_NAME.eq(store.graphName()))
            .and(named(m.CLASS_NAME, m.METHOD, fqn, method))
            .fetch();
    }

    /** The class match, with the method narrowing folded in where one was asked for. */
    private static Condition named(
        TableField<?, String> classColumn, TableField<?, String> methodColumn,
        String fqn, String method
    ) {
        Condition match = classColumn.eq(fqn);
        return method == null ? match : match.and(methodColumn.eq(method));
    }

    /**
     * The types whose {@code @table} binding resolves to this table, positioned by their site.
     *
     * <p>Two relations at two keys, joined rather than conflated. Which types resolved to the table
     * is a coordinate fact and {@code intent_bound_table} states it; where the binding was written
     * is a position, and the entry is keyed there. The coordinate is not a column of the entry and
     * is not meant to be: a transcription says what stands at a position and references the
     * {@code graphql_} entry it decorates, so the type name is reached through that reference, to
     * the application's entry holding its declaration's position in {@code parent_line} and
     * {@code parent_column}, and from there to the declaration carrying the name. The reading this
     * replaces took the type name off the binding row itself, which is why that row had to resolve
     * while the document was still being read.
     *
     * <p>One row per site and not per type, which is the keying showing through: a type extended
     * across two sources and annotated on both has two sites and is two rows here.
     */
    private static Result<Record3<String, Integer, Integer>> boundTypeSites(
        StoreHandle store, CatalogTable table
    ) {
        var e = GRAPHITRON_AST_TABLE_ENTRY;
        var d = GRAPHQL_AST_ENTRY;
        var m = GRAPHQL_TYPE_DECLARATION;
        return store.dsl()
            .select(e.SOURCE_NAME, e.SOURCE_LINE, e.SOURCE_COLUMN)
            .from(INTENT_BOUND_TABLE)
            .join(m).on(m.GRAPH_NAME.eq(INTENT_BOUND_TABLE.GRAPH_NAME))
                .and(m.TYPE_NAME.eq(INTENT_BOUND_TABLE.TYPE_NAME))
            .join(d).on(d.GRAPH_NAME.eq(m.GRAPH_NAME))
                .and(d.SOURCE_NAME.eq(m.SOURCE_NAME))
                .and(d.PARENT_LINE.eq(m.SOURCE_LINE))
                .and(d.PARENT_COLUMN.eq(m.SOURCE_COLUMN))
            .join(e).on(e.GRAPH_NAME.eq(d.GRAPH_NAME))
                .and(e.SOURCE_NAME.eq(d.SOURCE_NAME))
                .and(e.SOURCE_LINE.eq(d.SOURCE_LINE))
                .and(e.SOURCE_COLUMN.eq(d.SOURCE_COLUMN))
            .where(INTENT_BOUND_TABLE.GRAPH_NAME.eq(store.graphName()))
            .and(INTENT_BOUND_TABLE.TABLE_SOURCE_NAME.eq(table.sourceName()))
            .and(INTENT_BOUND_TABLE.TABLE_SCHEMA.eq(table.schema()))
            .and(INTENT_BOUND_TABLE.TABLE_NAME.eq(table.tableName()))
            .fetch();
    }

    /**
     * The {@code @reference} and {@code @referenceFor} path steps naming this table or key, at every
     * site a path is written, each located at the name itself: the string the step wrote under
     * {@code table:} or {@code key:}, which is the reference an editor would change. A path naming
     * one table at two steps is two uses, and a step several lines into a path is found where it
     * was written rather than at the application holding the path.
     */
    private static Result<Record3<String, Integer, Integer>> hopSites(
        StoreHandle store, Spelled spelled
    ) {
        // A step is keyed by the path element it sits in, whichever directive and site wrote it;
        // the name is the value written inside that element at the field the step spells.
        var name = GRAPHQL_AST_VALUE_ENTRY;
        String fieldName = spelled.isKey() ? "key" : "table";
        var step = spelled.isKey() ? KEY_STEP : TABLE_STEP;
        return store.dsl()
            .select(name.SOURCE_NAME, name.SOURCE_LINE, name.SOURCE_COLUMN)
            .from(step.table())
            .join(name).on(name.GRAPH_NAME.eq(step.graphName()),
                name.SOURCE_NAME.eq(step.sourceName()), name.PARENT_LINE.eq(step.sourceLine()),
                name.PARENT_COLUMN.eq(step.sourceColumn()),
                name.OBJECT_FIELD_NAME.eq(fieldName))
            .where(step.graphName().eq(store.graphName()))
            .and(hopNames(spelled, step.nameUpper(), step.namespaceUpper()))
            .fetch();
    }

    /** One step relation's key and the two halves of the name it spells. */
    private record StepRelation(Table<?> table, Field<String> graphName, Field<String> sourceName,
                            Field<Integer> sourceLine, Field<Integer> sourceColumn,
                            Field<String> nameUpper, Field<String> namespaceUpper) {

        static StepRelation of(Table<?> table, String spelled) {
            return new StepRelation(table, column(table, "graph_name", String.class),
                column(table, "source_name", String.class),
                column(table, "source_line", Integer.class),
                column(table, "source_column", Integer.class),
                column(table, spelled + "_name_part_upper", String.class),
                column(table, spelled + "_namespace_part_upper", String.class));
        }

        private static <T> Field<T> column(Table<?> table, String name, Class<T> type) {
            return java.util.Objects.requireNonNull(
                table.field(name.toUpperCase(Locale.ROOT), type),
                () -> table.getName() + " has no column " + name);
        }
    }

    private static final StepRelation KEY_STEP =
        StepRelation.of(GRAPHITRON_AST_REFERENCE_KEY_STEP_ENTRY, "key_ref");

    private static final StepRelation TABLE_STEP =
        StepRelation.of(GRAPHITRON_AST_REFERENCE_TABLE_STEP_ENTRY, "table_ref");

    /**
     * The hop-matching rule: the name matches case-insensitively, and a namespace the hop wrote must
     * match too. A hop that wrote none is left in, because an unqualified name resolves against
     * whatever the catalog offers, which is the latitude the generator itself takes.
     */
    private static Condition hopNames(
        Spelled spelled, Field<String> nameUpper, Field<String> namespaceUpper
    ) {
        Condition match = nameUpper.in(spelled.names().stream()
            .filter(name -> name != null && !name.isEmpty())
            .map(name -> name.toUpperCase(Locale.ROOT))
            .toList());
        return match.and(namespaceUpper.isNull()
            .or(namespaceUpper.eq(spelled.namespace().toUpperCase(Locale.ROOT))));
    }

    private static List<Location> locations(Result<Record3<String, Integer, Integer>> rows) {
        var sites = new ArrayList<Location>(rows.size());
        for (var row : rows) {
            SdlDeclarations.location(row.value1(), row.value2(), row.value3()).ifPresent(sites::add);
        }
        return sites;
    }

    /**
     * File, then position, with duplicates collapsed: two arms reaching one written position are one
     * site, and two identical entries in a usage list read as a bug rather than as a detail of how
     * the answer was gathered.
     */
    static List<Location> sorted(List<Location> sites) {
        return sites.stream()
            .distinct()
            .sorted(BindingUsages::byFileThenPosition)
            .toList();
    }

    private static int byFileThenPosition(Location left, Location right) {
        int byFile = left.getUri().compareTo(right.getUri());
        if (byFile != 0) return byFile;
        int byLine = Integer.compare(
            left.getRange().getStart().getLine(), right.getRange().getStart().getLine());
        if (byLine != 0) return byLine;
        return Integer.compare(
            left.getRange().getStart().getCharacter(), right.getRange().getStart().getCharacter());
    }
}
