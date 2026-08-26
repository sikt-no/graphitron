package no.sikt.graphitron.rewrite.classifieddsl;

import graphql.language.StringValue;
import graphql.parser.Parser;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.RowN;
import org.jooq.Table;
import org.jooq.impl.DSL;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The corpus's fact expectations: what each document says its own graph's rows are, read out of the
 * store rather than off the AST.
 *
 * <p>A document states an expectation by applying the prelude's {@code @expectEquals} directive to the
 * schema, once per asserted relation:
 *
 * <pre>{@code
 * extend schema @expectEquals(relation: "intent_resolved_field_claim", rows: """
 *   type_name, field_name, classifier,   tier
 *   Country,   name,       COLUMN_MATCH, INFERRED
 *   """)
 * }</pre>
 *
 * <p><b>The block is a relation literal.</b> The header names columns, each line is a row, and the
 * coordinate is a column like any other, so a use-keyed relation a coordinate-level directive
 * structurally cannot key is assertable on the same mechanism. {@code graph_name} is the one column a
 * document never spells: it is the document's own identity, supplied here.
 *
 * <p><b>Nothing here owns a CSV parser.</b> Capture writes the application as rows
 * ({@code graphql_schema_directive} plus its {@code _arg} child, the argument value in
 * {@code AstPrinter} form), {@link Parser#parseValue} recovers the block's text, and
 * {@link DSLContext#fetchFromCSV} turns it into a result whose field names are the header. Quoting,
 * embedded separators and escaping are graphql-java's and jOOQ's problem. Cells and headers are
 * trimmed, so a document may pad its columns for legibility.
 *
 * <p><b>The comparison is set equality per document and relation</b>, and a failure is a row. The
 * union of a document's blocks for one relation equals that relation's rows in that document's graph,
 * projected onto the named columns, which is what makes a coordinate that silently starts producing a
 * row fail. It runs as one query per relation and column list: an anti-join in both directions
 * between the expectation rows, standing in the query as a {@code VALUES} table, and the relation's
 * own rows.
 */
public final class CorpusExpectations {

    private CorpusExpectations() {}

    /** The directive a document applies to the schema to state an expectation. */
    public static final String DIRECTIVE = "expectEquals";

    /** The column every expectation carries and no document spells: the document's own identity. */
    public static final String GRAPH_COLUMN = "graph_name";

    /**
     * The store's identifiers are unquoted DDL, so the engine folds them upward and a quoted
     * lower-case name finds nothing. Every name a document writes goes through here, which is also
     * what lets a document spell a relation the way its own DDL and its comments do.
     */
    private static org.jooq.Name id(String... parts) {
        return DSL.name(java.util.Arrays.stream(parts)
            .map(part -> part.toUpperCase(java.util.Locale.ROOT))
            .toArray(String[]::new));
    }

    /**
     * One {@code @expectEquals} application: the graph that wrote it, its relation, its header and its
     * rows, plus any line whose cell count disagrees with the header. A ragged line is carried rather
     * than thrown on, so the well-formedness floor reports it as the defect it is.
     */
    public record Block(String graph, String relation, List<String> columns, List<List<String>> rows,
                        List<String> raggedLines) {}

    /**
     * One divergence between a document's expectation and its graph's rows, which is the failure
     * shape: the side that is missing the row names which of the two it is.
     */
    public record Divergence(String relation, String graph, Side side, List<String> values) {

        /** Which side lacks the row: the store produced it and no block declared it, or the reverse. */
        public enum Side {
            /** A block declares the row and the relation does not hold it. */
            NOT_PRODUCED,
            /** The relation holds the row and no block declares it. */
            NOT_DECLARED
        }

        @Override
        public String toString() {
            return "%-13s %-40s %s %s".formatted(side, relation, graph, values);
        }
    }

    /** Every {@code @expectEquals} application in the store, in graph and application order. */
    public static List<Block> blocks(DSLContext dsl) {
        var arguments = dsl
            .select(DSL.field(id("d", "graph_name"), String.class),
                DSL.field(id("d", "ordinal"), Integer.class),
                DSL.field(id("a", "directive_argument_name"), String.class),
                DSL.field(id("a", "value_sdl"), String.class))
            .from(DSL.table(id("graphql_schema_directive")).as("D"))
            .join(DSL.table(id("graphql_schema_directive_arg")).as("A"))
            .on(DSL.field(id("a", "graph_name"), String.class)
                .eq(DSL.field(id("d", "graph_name"), String.class)))
            .and(DSL.field(id("a", "directive_name"), String.class)
                .eq(DSL.field(id("d", "directive_name"), String.class)))
            .and(DSL.field(id("a", "ordinal"), Integer.class)
                .eq(DSL.field(id("d", "ordinal"), Integer.class)))
            .where(DSL.field(id("d", "directive_name"), String.class).eq(DIRECTIVE))
            .orderBy(DSL.field(id("d", "graph_name")), DSL.field(id("d", "ordinal")))
            .fetch();

        record Application(String graph, int ordinal) {}
        var byApplication = new LinkedHashMap<Application, Map<String, String>>();
        for (var row : arguments) {
            byApplication
                .computeIfAbsent(new Application(row.value1(), row.value2()), k -> new LinkedHashMap<>())
                .put(row.value3(), text(row.value4()));
        }

        var blocks = new ArrayList<Block>();
        for (var entry : byApplication.entrySet()) {
            String relation = entry.getValue().get("relation");
            String csv = entry.getValue().get("rows");
            if (relation == null || csv == null) {
                throw new AssertionError("document " + entry.getKey().graph() + " applies @"
                    + DIRECTIVE + " without both arguments, which the directive's own signature "
                    + "makes impossible: " + entry.getValue().keySet());
            }
            blocks.add(decode(dsl, entry.getKey().graph(), relation.strip(), csv));
        }
        return List.copyOf(blocks);
    }

    /**
     * The divergences between every block in the store and the relations it names, one query per
     * relation and column list. An empty list is agreement.
     */
    public static List<Divergence> divergences(DSLContext dsl, List<Block> blocks) {
        record Group(String relation, List<String> columns) {}
        var grouped = new LinkedHashMap<Group, List<Block>>();
        for (Block block : blocks) {
            grouped.computeIfAbsent(new Group(block.relation(), block.columns()), k -> new ArrayList<>())
                .add(block);
        }
        var divergences = new ArrayList<Divergence>();
        for (var entry : grouped.entrySet()) {
            divergences.addAll(compare(dsl, entry.getKey().relation(), entry.getKey().columns(),
                entry.getValue()));
        }
        return List.copyOf(divergences);
    }

    private static List<Divergence> compare(DSLContext dsl, String relation, List<String> columns,
                                            List<Block> blocks) {
        var graphs = new LinkedHashSet<String>();
        var expectedRows = new LinkedHashSet<List<String>>();
        for (Block block : blocks) {
            graphs.add(block.graph());
            for (List<String> row : block.rows()) {
                var keyed = new ArrayList<String>(row.size() + 1);
                keyed.add(block.graph());
                keyed.addAll(row);
                expectedRows.add(java.util.Collections.unmodifiableList(keyed));
            }
        }

        List<String> allColumns = new ArrayList<>();
        allColumns.add(GRAPH_COLUMN);
        allColumns.addAll(columns);

        Table<Record> produced = DSL.table(id(relation)).as("PRODUCED");
        List<Field<String>> producedFields = allColumns.stream()
            .map(column -> DSL.field(id("produced", column)).cast(String.class))
            .toList();

        var divergences = new ArrayList<Divergence>();
        if (!expectedRows.isEmpty()) {
            Table<Record> expected = DSL.values(expectedRows.stream()
                    .map(row -> DSL.row(row.stream()
                        .map(cell -> DSL.val(cell, String.class))
                        .toArray(Field[]::new)))
                    .toArray(RowN[]::new))
                .as("EXPECTED", allColumns.stream().map(c -> c.toUpperCase(java.util.Locale.ROOT)).toArray(String[]::new));
            List<Field<String>> expectedFields = allColumns.stream()
                .map(column -> DSL.field(id("expected", column), String.class))
                .toList();

            dsl.select(expectedFields)
                .from(expected)
                .whereNotExists(dsl.selectOne().from(produced).where(match(producedFields, expectedFields)))
                .fetch()
                .forEach(row -> divergences.add(divergence(relation, row, Divergence.Side.NOT_PRODUCED)));

            dsl.select(producedFields)
                .from(produced)
                .where(DSL.field(id("produced", GRAPH_COLUMN), String.class).in(graphs))
                .andNotExists(dsl.selectOne().from(expected).where(match(producedFields, expectedFields)))
                .fetch()
                .forEach(row -> divergences.add(divergence(relation, row, Divergence.Side.NOT_DECLARED)));
        } else {
            dsl.select(producedFields)
                .from(produced)
                .where(DSL.field(id("produced", GRAPH_COLUMN), String.class).in(graphs))
                .fetch()
                .forEach(row -> divergences.add(divergence(relation, row, Divergence.Side.NOT_DECLARED)));
        }
        return divergences;
    }

    /**
     * The row-equality predicate, column by column, on {@code IS NOT DISTINCT FROM} rather than
     * {@code =} so a NULL on both sides matches: an empty cell is how a document spells one.
     */
    private static Condition match(List<Field<String>> left, List<Field<String>> right) {
        Condition condition = DSL.noCondition();
        for (int i = 0; i < left.size(); i++) {
            condition = condition.and(left.get(i).isNotDistinctFrom(right.get(i)));
        }
        return condition;
    }

    private static Divergence divergence(String relation, Record row, Divergence.Side side) {
        var values = new ArrayList<String>(row.size());
        for (int i = 0; i < row.size(); i++) {
            values.add((String) row.get(i));
        }
        return new Divergence(relation, values.getFirst(), side,
            java.util.Collections.unmodifiableList(new ArrayList<>(values.subList(1, values.size()))));
    }

    /** Decodes one block's CSV into a header and its rows, every cell trimmed. */
    static Block decode(DSLContext dsl, String graph, String relation, String csv) {
        List<String> lines = csv.lines().filter(line -> !line.isBlank()).toList();
        List<String> columns = lines.isEmpty() ? List.of()
            : java.util.Arrays.stream(lines.getFirst().split(",", -1)).map(String::strip).toList();
        List<String> ragged = lines.stream().skip(1)
            .filter(line -> !line.contains("\"") && line.split(",", -1).length != columns.size())
            .toList();

        var rows = new ArrayList<List<String>>();
        if (ragged.isEmpty()) {
            for (Record record : dsl.fetchFromCSV(csv, true, ',')) {
                var row = new ArrayList<String>(columns.size());
                for (int i = 0; i < record.size(); i++) {
                    Object value = record.get(i);
                    String cell = value == null ? null : value.toString().strip();
                    row.add(cell == null || cell.isEmpty() ? null : cell);
                }
                rows.add(java.util.Collections.unmodifiableList(row));
            }
        }
        return new Block(graph, relation, columns, java.util.Collections.unmodifiableList(rows), ragged);
    }

    /** The text of a stored argument value, recovered from its printed form. */
    private static String text(String valueSdl) {
        var value = Parser.parseValue(valueSdl);
        if (!(value instanceof StringValue string)) {
            throw new AssertionError("@" + DIRECTIVE + " takes String arguments; the store holds "
                + valueSdl);
        }
        return string.getValue();
    }

    /**
     * The names a block gets wrong: its relation, or a header cell the relation does not have.
     * Resolution is against the store's own catalog, passed in as columns by relation, so a
     * misspelling fails loudly instead of comparing nothing.
     */
    public static List<String> unresolvedNames(Block block, Map<String, Set<String>> columnsByRelation) {
        var columns = columnsByRelation.get(block.relation().toLowerCase(java.util.Locale.ROOT));
        if (columns == null) {
            return List.of(block.graph() + ": no relation named '" + block.relation() + "'");
        }
        return block.columns().stream()
            .filter(column -> !columns.contains(column.toLowerCase(java.util.Locale.ROOT)))
            .map(column -> block.graph() + ": " + block.relation() + " has no column '" + column + "'")
            .toList();
    }

    /**
     * The ways a block is malformed: a repeated header cell, a spelled {@link #GRAPH_COLUMN}, a row
     * whose cell count disagrees with the header, or an empty block naming a relation whose comment
     * does not say what its silence means. That last one is the fact model's own rule that "not
     * reached" is not "resolves to nothing": a relation that does not own its silence cannot be
     * asserted empty, so the document says nothing instead.
     */
    public static List<String> defects(Block block, boolean relationOwnsItsSilence) {
        String where = block.graph() + " -> " + block.relation() + ": ";
        var defects = new ArrayList<String>();
        if (block.columns().size() != new LinkedHashSet<>(block.columns()).size()) {
            defects.add(where + "the header repeats a column: " + block.columns());
        }
        if (block.columns().stream().anyMatch(c -> c.equalsIgnoreCase(GRAPH_COLUMN))) {
            defects.add(where + "spells " + GRAPH_COLUMN + ", which is the document's own identity "
                + "and never a column of the block");
        }
        for (String line : block.raggedLines()) {
            defects.add(where + "this row does not have " + block.columns().size() + " cells: " + line);
        }
        if (block.rows().isEmpty() && block.raggedLines().isEmpty() && !relationOwnsItsSilence) {
            defects.add(where + "asserts the relation holds nothing, but the relation's own comment "
                + "does not say what its silence means, and 'not reached' is not 'resolves to nothing'");
        }
        return List.copyOf(defects);
    }

    /**
     * The declared values outside a column's closed set, where the store closes one. Only a base
     * table carries a {@code CHECK (x IN (...))}; a view column has none, and its vocabulary lives in
     * the reading side's decode, so a typo there surfaces as a row mismatch and the report's own
     * "no row in any document carries this value" line is what reads it back as a typo.
     */
    public static List<String> membershipViolations(Block block, Map<String, Set<String>> vocabularies) {
        var violations = new ArrayList<String>();
        for (int i = 0; i < block.columns().size(); i++) {
            var admitted = vocabularies.get(block.columns().get(i).toLowerCase(java.util.Locale.ROOT));
            if (admitted == null) {
                continue;
            }
            for (List<String> row : block.rows()) {
                String value = i < row.size() ? row.get(i) : null;
                if (value != null && !admitted.contains(value)) {
                    violations.add(block.graph() + " -> " + block.relation() + "."
                        + block.columns().get(i) + ": '" + value + "' is outside the column's CHECK "
                        + "vocabulary " + admitted);
                }
            }
        }
        return List.copyOf(violations);
    }

    /** The relations every block in {@code blocks} names, for the floors that resolve them. */
    public static Set<String> relations(List<Block> blocks) {
        return blocks.stream().map(Block::relation)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }
}
