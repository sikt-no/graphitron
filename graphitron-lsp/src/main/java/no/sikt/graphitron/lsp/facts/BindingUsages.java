package no.sikt.graphitron.lsp.facts;

import no.sikt.graphitron.model.read.StoreHandle;
import org.eclipse.lsp4j.Location;
import org.jooq.Condition;
import org.jooq.Record3;
import org.jooq.Result;
import org.jooq.TableField;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT_CONDITION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT_REFERENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT_REFERENCE_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENUM;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_EXTERNAL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_CONDITION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_REFERENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_REFERENCE_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_REFERENCE_FOR;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_REFERENCE_FOR_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_SERVICE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_SOURCE_ROW;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLE;
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
 * {@code source_column} too, so a site needs no join back to the generic directive census; the three
 * reference-step families are the exception, and they take their position from the reference row
 * they belong to.
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
     * <p>Two carriers are deliberately absent. {@code graphitron_record}'s class name is the
     * deprecated {@code @record(className:)}, which binds no live class, so listing it as a use
     * would show an author a site the generator ignores; the definition surface carves the same
     * directive out for the same reason. {@code graphitron_error_handler} names a class and records
     * no position for it, so there is nowhere to send an editor.
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
     * The nine class-naming relations as one statement. The condition and reference-step families
     * are here because a class named inside a {@code @reference} path is as much a use of it as one
     * named by {@code @service}; the definition surface routes those through its service half for
     * the same reason.
     */
    private static Result<Record3<String, Integer, Integer>> classSites(
        StoreHandle store, String fqn, String method
    ) {
        String graph = store.graphName();
        return store.dsl()
            .select(GRAPHITRON_SERVICE.SOURCE_NAME, GRAPHITRON_SERVICE.SOURCE_LINE,
                GRAPHITRON_SERVICE.SOURCE_COLUMN)
            .from(GRAPHITRON_SERVICE)
            .where(GRAPHITRON_SERVICE.GRAPH_NAME.eq(graph))
            .and(named(GRAPHITRON_SERVICE.CLASS_NAME, GRAPHITRON_SERVICE.METHOD, fqn, method))
            .unionAll(store.dsl()
                .select(GRAPHITRON_EXTERNAL_FIELD.SOURCE_NAME, GRAPHITRON_EXTERNAL_FIELD.SOURCE_LINE,
                    GRAPHITRON_EXTERNAL_FIELD.SOURCE_COLUMN)
                .from(GRAPHITRON_EXTERNAL_FIELD)
                .where(GRAPHITRON_EXTERNAL_FIELD.GRAPH_NAME.eq(graph))
                .and(named(GRAPHITRON_EXTERNAL_FIELD.CLASS_NAME, GRAPHITRON_EXTERNAL_FIELD.METHOD,
                    fqn, method)))
            .unionAll(store.dsl()
                .select(GRAPHITRON_ENUM.SOURCE_NAME, GRAPHITRON_ENUM.SOURCE_LINE,
                    GRAPHITRON_ENUM.SOURCE_COLUMN)
                .from(GRAPHITRON_ENUM)
                .where(GRAPHITRON_ENUM.GRAPH_NAME.eq(graph))
                .and(named(GRAPHITRON_ENUM.CLASS_NAME, GRAPHITRON_ENUM.METHOD, fqn, method)))
            .unionAll(store.dsl()
                .select(GRAPHITRON_SOURCE_ROW.SOURCE_NAME, GRAPHITRON_SOURCE_ROW.SOURCE_LINE,
                    GRAPHITRON_SOURCE_ROW.SOURCE_COLUMN)
                .from(GRAPHITRON_SOURCE_ROW)
                .where(GRAPHITRON_SOURCE_ROW.GRAPH_NAME.eq(graph))
                .and(named(GRAPHITRON_SOURCE_ROW.CLASS_NAME, GRAPHITRON_SOURCE_ROW.METHOD,
                    fqn, method)))
            .unionAll(store.dsl()
                .select(GRAPHITRON_FIELD_CONDITION.SOURCE_NAME, GRAPHITRON_FIELD_CONDITION.SOURCE_LINE,
                    GRAPHITRON_FIELD_CONDITION.SOURCE_COLUMN)
                .from(GRAPHITRON_FIELD_CONDITION)
                .where(GRAPHITRON_FIELD_CONDITION.GRAPH_NAME.eq(graph))
                .and(named(GRAPHITRON_FIELD_CONDITION.CLASS_NAME, GRAPHITRON_FIELD_CONDITION.METHOD,
                    fqn, method)))
            .unionAll(store.dsl()
                .select(GRAPHITRON_ARGUMENT_CONDITION.SOURCE_NAME,
                    GRAPHITRON_ARGUMENT_CONDITION.SOURCE_LINE,
                    GRAPHITRON_ARGUMENT_CONDITION.SOURCE_COLUMN)
                .from(GRAPHITRON_ARGUMENT_CONDITION)
                .where(GRAPHITRON_ARGUMENT_CONDITION.GRAPH_NAME.eq(graph))
                .and(named(GRAPHITRON_ARGUMENT_CONDITION.CLASS_NAME,
                    GRAPHITRON_ARGUMENT_CONDITION.METHOD, fqn, method)))
            .unionAll(store.dsl()
                .select(GRAPHITRON_FIELD_REFERENCE.SOURCE_NAME, GRAPHITRON_FIELD_REFERENCE.SOURCE_LINE,
                    GRAPHITRON_FIELD_REFERENCE.SOURCE_COLUMN)
                .from(GRAPHITRON_FIELD_REFERENCE_STEP)
                .join(GRAPHITRON_FIELD_REFERENCE)
                .on(GRAPHITRON_FIELD_REFERENCE.GRAPH_NAME.eq(GRAPHITRON_FIELD_REFERENCE_STEP.GRAPH_NAME))
                .and(GRAPHITRON_FIELD_REFERENCE.TYPE_NAME.eq(GRAPHITRON_FIELD_REFERENCE_STEP.TYPE_NAME))
                .and(GRAPHITRON_FIELD_REFERENCE.FIELD_NAME.eq(GRAPHITRON_FIELD_REFERENCE_STEP.FIELD_NAME))
                .and(GRAPHITRON_FIELD_REFERENCE.ORDINAL.eq(GRAPHITRON_FIELD_REFERENCE_STEP.ORDINAL))
                .where(GRAPHITRON_FIELD_REFERENCE_STEP.GRAPH_NAME.eq(graph))
                .and(named(GRAPHITRON_FIELD_REFERENCE_STEP.CLASS_NAME,
                    GRAPHITRON_FIELD_REFERENCE_STEP.METHOD, fqn, method)))
            .unionAll(store.dsl()
                .select(GRAPHITRON_ARGUMENT_REFERENCE.SOURCE_NAME,
                    GRAPHITRON_ARGUMENT_REFERENCE.SOURCE_LINE,
                    GRAPHITRON_ARGUMENT_REFERENCE.SOURCE_COLUMN)
                .from(GRAPHITRON_ARGUMENT_REFERENCE_STEP)
                .join(GRAPHITRON_ARGUMENT_REFERENCE)
                .on(GRAPHITRON_ARGUMENT_REFERENCE.GRAPH_NAME
                    .eq(GRAPHITRON_ARGUMENT_REFERENCE_STEP.GRAPH_NAME))
                .and(GRAPHITRON_ARGUMENT_REFERENCE.TYPE_NAME
                    .eq(GRAPHITRON_ARGUMENT_REFERENCE_STEP.TYPE_NAME))
                .and(GRAPHITRON_ARGUMENT_REFERENCE.FIELD_NAME
                    .eq(GRAPHITRON_ARGUMENT_REFERENCE_STEP.FIELD_NAME))
                .and(GRAPHITRON_ARGUMENT_REFERENCE.ARGUMENT_NAME
                    .eq(GRAPHITRON_ARGUMENT_REFERENCE_STEP.ARGUMENT_NAME))
                .and(GRAPHITRON_ARGUMENT_REFERENCE.ORDINAL
                    .eq(GRAPHITRON_ARGUMENT_REFERENCE_STEP.ORDINAL))
                .where(GRAPHITRON_ARGUMENT_REFERENCE_STEP.GRAPH_NAME.eq(graph))
                .and(named(GRAPHITRON_ARGUMENT_REFERENCE_STEP.CLASS_NAME,
                    GRAPHITRON_ARGUMENT_REFERENCE_STEP.METHOD, fqn, method)))
            .unionAll(store.dsl()
                .select(GRAPHITRON_REFERENCE_FOR.SOURCE_NAME, GRAPHITRON_REFERENCE_FOR.SOURCE_LINE,
                    GRAPHITRON_REFERENCE_FOR.SOURCE_COLUMN)
                .from(GRAPHITRON_REFERENCE_FOR_STEP)
                .join(GRAPHITRON_REFERENCE_FOR)
                .on(GRAPHITRON_REFERENCE_FOR.GRAPH_NAME.eq(GRAPHITRON_REFERENCE_FOR_STEP.GRAPH_NAME))
                .and(GRAPHITRON_REFERENCE_FOR.TYPE_NAME.eq(GRAPHITRON_REFERENCE_FOR_STEP.TYPE_NAME))
                .and(GRAPHITRON_REFERENCE_FOR.FIELD_NAME.eq(GRAPHITRON_REFERENCE_FOR_STEP.FIELD_NAME))
                .and(GRAPHITRON_REFERENCE_FOR.ORDINAL.eq(GRAPHITRON_REFERENCE_FOR_STEP.ORDINAL))
                .where(GRAPHITRON_REFERENCE_FOR_STEP.GRAPH_NAME.eq(graph))
                .and(named(GRAPHITRON_REFERENCE_FOR_STEP.CLASS_NAME,
                    GRAPHITRON_REFERENCE_FOR_STEP.METHOD, fqn, method)))
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

    /** The types whose {@code @table} binding resolves to this table, positioned by their site. */
    private static Result<Record3<String, Integer, Integer>> boundTypeSites(
        StoreHandle store, CatalogTable table
    ) {
        return store.dsl()
            .select(GRAPHITRON_TABLE.SOURCE_NAME, GRAPHITRON_TABLE.SOURCE_LINE,
                GRAPHITRON_TABLE.SOURCE_COLUMN)
            .from(INTENT_BOUND_TABLE)
            .join(GRAPHITRON_TABLE)
            .on(GRAPHITRON_TABLE.GRAPH_NAME.eq(INTENT_BOUND_TABLE.GRAPH_NAME))
            .and(GRAPHITRON_TABLE.TYPE_NAME.eq(INTENT_BOUND_TABLE.TYPE_NAME))
            .where(INTENT_BOUND_TABLE.GRAPH_NAME.eq(store.graphName()))
            .and(INTENT_BOUND_TABLE.TABLE_SOURCE_NAME.eq(table.sourceName()))
            .and(INTENT_BOUND_TABLE.TABLE_SCHEMA.eq(table.schema()))
            .and(INTENT_BOUND_TABLE.TABLE_NAME.eq(table.tableName()))
            .fetch();
    }

    /**
     * The {@code @reference} path hops naming this table or key, across the three step families,
     * each positioned by the reference row it belongs to. A hop is a use of the name even though
     * the store positions the whole path rather than the hop, so the site is the path's.
     */
    private static Result<Record3<String, Integer, Integer>> hopSites(
        StoreHandle store, Spelled spelled
    ) {
        String graph = store.graphName();
        return store.dsl()
            .select(GRAPHITRON_FIELD_REFERENCE.SOURCE_NAME, GRAPHITRON_FIELD_REFERENCE.SOURCE_LINE,
                GRAPHITRON_FIELD_REFERENCE.SOURCE_COLUMN)
            .from(GRAPHITRON_FIELD_REFERENCE_STEP)
            .join(GRAPHITRON_FIELD_REFERENCE)
            .on(GRAPHITRON_FIELD_REFERENCE.GRAPH_NAME.eq(GRAPHITRON_FIELD_REFERENCE_STEP.GRAPH_NAME))
            .and(GRAPHITRON_FIELD_REFERENCE.TYPE_NAME.eq(GRAPHITRON_FIELD_REFERENCE_STEP.TYPE_NAME))
            .and(GRAPHITRON_FIELD_REFERENCE.FIELD_NAME.eq(GRAPHITRON_FIELD_REFERENCE_STEP.FIELD_NAME))
            .and(GRAPHITRON_FIELD_REFERENCE.ORDINAL.eq(GRAPHITRON_FIELD_REFERENCE_STEP.ORDINAL))
            .where(GRAPHITRON_FIELD_REFERENCE_STEP.GRAPH_NAME.eq(graph))
            .and(spelled.isKey()
                ? hopNames(spelled, GRAPHITRON_FIELD_REFERENCE_STEP.KEY_REF_NAME_PART_UPPER,
                    GRAPHITRON_FIELD_REFERENCE_STEP.KEY_REF_NAMESPACE_PART_UPPER)
                : hopNames(spelled, GRAPHITRON_FIELD_REFERENCE_STEP.TABLE_REF_NAME_PART_UPPER,
                    GRAPHITRON_FIELD_REFERENCE_STEP.TABLE_REF_NAMESPACE_PART_UPPER))
            .unionAll(store.dsl()
                .select(GRAPHITRON_ARGUMENT_REFERENCE.SOURCE_NAME,
                    GRAPHITRON_ARGUMENT_REFERENCE.SOURCE_LINE,
                    GRAPHITRON_ARGUMENT_REFERENCE.SOURCE_COLUMN)
                .from(GRAPHITRON_ARGUMENT_REFERENCE_STEP)
                .join(GRAPHITRON_ARGUMENT_REFERENCE)
                .on(GRAPHITRON_ARGUMENT_REFERENCE.GRAPH_NAME
                    .eq(GRAPHITRON_ARGUMENT_REFERENCE_STEP.GRAPH_NAME))
                .and(GRAPHITRON_ARGUMENT_REFERENCE.TYPE_NAME
                    .eq(GRAPHITRON_ARGUMENT_REFERENCE_STEP.TYPE_NAME))
                .and(GRAPHITRON_ARGUMENT_REFERENCE.FIELD_NAME
                    .eq(GRAPHITRON_ARGUMENT_REFERENCE_STEP.FIELD_NAME))
                .and(GRAPHITRON_ARGUMENT_REFERENCE.ARGUMENT_NAME
                    .eq(GRAPHITRON_ARGUMENT_REFERENCE_STEP.ARGUMENT_NAME))
                .and(GRAPHITRON_ARGUMENT_REFERENCE.ORDINAL
                    .eq(GRAPHITRON_ARGUMENT_REFERENCE_STEP.ORDINAL))
                .where(GRAPHITRON_ARGUMENT_REFERENCE_STEP.GRAPH_NAME.eq(graph))
                .and(spelled.isKey()
                    ? hopNames(spelled, GRAPHITRON_ARGUMENT_REFERENCE_STEP.KEY_REF_NAME_PART_UPPER,
                        GRAPHITRON_ARGUMENT_REFERENCE_STEP.KEY_REF_NAMESPACE_PART_UPPER)
                    : hopNames(spelled, GRAPHITRON_ARGUMENT_REFERENCE_STEP.TABLE_REF_NAME_PART_UPPER,
                        GRAPHITRON_ARGUMENT_REFERENCE_STEP.TABLE_REF_NAMESPACE_PART_UPPER)))
            .unionAll(store.dsl()
                .select(GRAPHITRON_REFERENCE_FOR.SOURCE_NAME, GRAPHITRON_REFERENCE_FOR.SOURCE_LINE,
                    GRAPHITRON_REFERENCE_FOR.SOURCE_COLUMN)
                .from(GRAPHITRON_REFERENCE_FOR_STEP)
                .join(GRAPHITRON_REFERENCE_FOR)
                .on(GRAPHITRON_REFERENCE_FOR.GRAPH_NAME.eq(GRAPHITRON_REFERENCE_FOR_STEP.GRAPH_NAME))
                .and(GRAPHITRON_REFERENCE_FOR.TYPE_NAME.eq(GRAPHITRON_REFERENCE_FOR_STEP.TYPE_NAME))
                .and(GRAPHITRON_REFERENCE_FOR.FIELD_NAME.eq(GRAPHITRON_REFERENCE_FOR_STEP.FIELD_NAME))
                .and(GRAPHITRON_REFERENCE_FOR.ORDINAL.eq(GRAPHITRON_REFERENCE_FOR_STEP.ORDINAL))
                .where(GRAPHITRON_REFERENCE_FOR_STEP.GRAPH_NAME.eq(graph))
                .and(spelled.isKey()
                    ? hopNames(spelled, GRAPHITRON_REFERENCE_FOR_STEP.KEY_REF_NAME_PART_UPPER,
                        GRAPHITRON_REFERENCE_FOR_STEP.KEY_REF_NAMESPACE_PART_UPPER)
                    : hopNames(spelled, GRAPHITRON_REFERENCE_FOR_STEP.TABLE_REF_NAME_PART_UPPER,
                        GRAPHITRON_REFERENCE_FOR_STEP.TABLE_REF_NAMESPACE_PART_UPPER)))
            .fetch();
    }

    /**
     * The hop-matching rule: the name matches case-insensitively, and a namespace the hop wrote must
     * match too. A hop that wrote none is left in, because an unqualified name resolves against
     * whatever the catalog offers, which is the latitude the generator itself takes.
     */
    private static Condition hopNames(
        Spelled spelled, TableField<?, String> nameUpper, TableField<?, String> namespaceUpper
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
     * File, then position, with duplicates collapsed. A path that names the same table at two hops
     * is one site as far as an editor is concerned, and two identical entries in a usage list read
     * as a bug rather than as a detail about paths.
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
