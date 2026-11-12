package no.sikt.graphitron.model;

import no.sikt.graphitron.model.test.FactStores;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate beside {@link CollectionValuedColumnGateTest}: no relation holds a column to an
 * expression over its own row's other columns with a {@code CHECK}. Such a column is generated
 * instead, so the store states the rendering once and no writer spells it; the fact model's key
 * discipline carries the rule and its exception.
 *
 * <p>The exception is the twin, {@code x_ref IS NULL OR x_ref = x}: a column a foreign key's
 * {@code ON DELETE SET NULL} must be able to clear, which a generated column cannot be. It is let
 * through by its form, not by being a disjunction, because an {@code OR} around a rendering would
 * otherwise hide it. One conditional equality is pinned beside it by its text, an equation that
 * holds only in the rows where a slot method is absent and so renders nothing.
 *
 * <p>A rendering that sits in its own relation's primary key is the case the ruling leaves open,
 * and those are exempt by (table, column) under a ceiling. Exempting by table would let a
 * converted column reintroduced on an exempt table through.
 *
 * <p>Reads the authored DDL through {@link CollectionValuedColumnGateTest#statements}, for that
 * gate's reason: the authored {@code CHECK} is the construct refused, and the splitter already
 * blanks comments and string-literal contents, so prose about a check cannot read as one. Blanking a
 * literal leaves its quotes, which is enough: a literal names no column. What the gate cannot see is
 * a rendering a writer spells into a column no check holds; that one the review catches.
 */
class RenderedColumnGateTest {

    /** Renderings in a primary key, open under the ruling; each entry is {@code table.column}. */
    private static final Set<String> EXEMPT = Set.of(
        "graphitron_occurrence_node_id.bound",
        "graphitron_occurrence_column.bound",
        "graphitron_occurrence_binding_table.bound");

    /** Lowered when an exemption leaves; raised only on a ruling. */
    private static final int EXEMPTION_CEILING = 3;

    /** The conditional equality let through by its text, normalized as {@link #normalize} does. */
    private static final Set<String> PINNED = Set.of(
        "graphitron_occurrence_binding_value: slot_method_name IS NOT NULL OR value_class = source_class");

    private static final Pattern TABLE = Pattern.compile(
        "(?m)^CREATE TABLE (\\w+) \\((.*?)^\\);", Pattern.DOTALL);
    private static final Pattern ALTER = Pattern.compile(
        "\\bALTER TABLE (\\w+)\\b([^;]*);", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHECK = Pattern.compile("\\bCHECK\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern TWIN = Pattern.compile(
        "(\\w+_ref) IS NULL OR \\1 = \\w+", Pattern.CASE_INSENSITIVE);
    private static final Pattern IDENTIFIER = Pattern.compile("\\b[a-z_][a-z0-9_]*\\b");
    private static final Set<String> CONSTRAINT_HEADS =
        Set.of("PRIMARY", "UNIQUE", "FOREIGN", "CHECK", "CONSTRAINT");

    @Test
    @DisplayName("no CHECK holds a column to a rendering of its row's other columns")
    void noCheckHoldsAColumnToARenderingOfItsRow() {
        String statements = CollectionValuedColumnGateTest.statements(FactStores.schemaText());
        var tables = TABLE.matcher(statements).results().count();
        // The floor against a vacuous pass: the scan must have seen the schema's tables.
        assertThat(tables).as("tables the scan read").isGreaterThan(100);

        var findings = scan(statements);
        var used = new LinkedHashSet<String>();
        var refused = new ArrayList<String>();
        for (var finding : findings) {
            if (EXEMPT.contains(finding.column())) {
                used.add(finding.column());
            } else {
                refused.add(finding.column() + ": CHECK (" + finding.check() + ")");
            }
        }
        assertThat(refused)
            .as("A column held by a CHECK to an expression over its row's other columns is "
                + "generated instead: declare it GENERATED ALWAYS AS (<expression>), drop the "
                + "CHECK, and take it out of every writer. The fact model's key discipline states "
                + "the rule; the x_ref twin a foreign-key action clears is its only exception.")
            .isEmpty();
        assertThat(EXEMPT).as("exemptions no longer used are removed, lowering the ceiling")
            .isEqualTo(used);
        assertThat(EXEMPT.size()).as("exemptions, under the ceiling a ruling sets")
            .isLessThanOrEqualTo(EXEMPTION_CEILING);
    }

    @Test
    @DisplayName("the gate pins its acceptance line in both directions")
    void theGatePinsItsAcceptanceLine() {
        String head = "CREATE TABLE t (\n  type_name VARCHAR NOT NULL,\n  field_name VARCHAR NOT NULL,\n"
            + "  coordinate VARCHAR NOT NULL,\n  coordinate_ref VARCHAR,\n  kind VARCHAR NOT NULL,\n"
            + "  source_name VARCHAR,\n  PRIMARY KEY (type_name, field_name),\n";

        // Refused: the rendering, either way round, and wrapped in an unrelated OR.
        assertThat(scan(statements(head + "  CHECK (coordinate = type_name || '.' || field_name)\n);")))
            .isNotEmpty();
        assertThat(scan(statements(head + "  CHECK (type_name || '.' || field_name = coordinate)\n);")))
            .isNotEmpty();
        assertThat(scan(statements(head
            + "  CHECK (kind = 'X' OR coordinate = type_name || '.' || field_name)\n);"))).isNotEmpty();
        assertThat(scan(statements(head
            + "  CHECK (kind = 'X' OR (kind = 'Y' AND coordinate = type_name))\n);"))).isNotEmpty();

        // Refused: the same rendering by every other route a CHECK can be written by.
        assertThat(scan(statements(head.replace("coordinate VARCHAR NOT NULL,",
            "coordinate VARCHAR NOT NULL CHECK (coordinate = type_name || '.' || field_name),") + "  kind2 INT\n);")))
            .isNotEmpty();
        assertThat(scan(statements(head
            + "  CONSTRAINT spelled CHECK (coordinate = type_name || '.' || field_name)\n);"))).isNotEmpty();
        assertThat(scan(statements(head + "  kind2 INT\n);\n"
            + "ALTER TABLE t ADD CONSTRAINT spelled CHECK (coordinate = type_name || '.' || field_name);")))
            .isNotEmpty();
        assertThat(scan(statements(head
            + "  CHECK (kind = 'X' or coordinate = type_name || '.' || field_name)\n);"))).isNotEmpty();
        // Accepted: an inline CHECK that compares to a literal.
        assertThat(scan(statements(head.replace("kind VARCHAR NOT NULL,",
            "kind VARCHAR NOT NULL CHECK (kind IN ('A', 'B')),") + "  kind2 INT\n);"))).isEmpty();

        // Accepted: the twin, a biconditional that ties presence to a kind, and comparisons to
        // literals, which render nothing.
        assertThat(scan(statements(head
            + "  CHECK (coordinate_ref IS NULL OR coordinate_ref = coordinate)\n);"))).isEmpty();
        assertThat(scan(statements(head
            + "  CHECK ((kind = 'AUTHORED') = (source_name IS NOT NULL))\n);"))).isEmpty();
        assertThat(scan(statements(head + "  CHECK (kind = 'COLUMN'),\n  CHECK (kind <> 'X' OR "
            + "type_name >= field_name)\n);"))).isEmpty();
        // Accepted: a generated column states the rendering once and no check repeats it.
        assertThat(scan(statements(head.replace("coordinate VARCHAR NOT NULL",
            "coordinate VARCHAR GENERATED ALWAYS AS (type_name || '.' || field_name) NOT NULL")
            + "  CHECK (kind = 'COLUMN')\n);"))).isEmpty();

        // The pinned conditional equality passes by its text and table, and nowhere else.
        String binding = "CREATE TABLE graphitron_occurrence_binding_value (\n"
            + "  slot_method_name VARCHAR,\n  value_class VARCHAR NOT NULL,\n"
            + "  source_class VARCHAR NOT NULL,\n"
            + "  CHECK (slot_method_name IS NOT NULL OR value_class = source_class)\n);";
        assertThat(scan(statements(binding))).isEmpty();
        assertThat(scan(statements(binding.replace("graphitron_occurrence_binding_value", "other"))))
            .isNotEmpty();

        // An exemption names its table and column: the same table's coordinate is still refused.
        String keyed = "CREATE TABLE graphitron_occurrence_column (\n  table_schema VARCHAR NOT NULL,\n"
            + "  table_name VARCHAR NOT NULL,\n  bound VARCHAR NOT NULL,\n  type_name VARCHAR NOT NULL,\n"
            + "  field_name VARCHAR NOT NULL,\n  coordinate VARCHAR NOT NULL,\n"
            + "  CHECK (bound = table_schema || '.' || table_name),\n"
            + "  CHECK (coordinate = type_name || '.' || field_name)\n);";
        assertThat(scan(statements(keyed))).extracting(Finding::column)
            .containsExactly("graphitron_occurrence_column.bound", "graphitron_occurrence_column.coordinate")
            .filteredOn(EXEMPT::contains).containsExactly("graphitron_occurrence_column.bound");
    }

    private static String statements(String ddl) {
        return CollectionValuedColumnGateTest.statements(ddl);
    }

    /** One check equating a column of its table with an expression over that table's other columns. */
    record Finding(String column, String check) {}

    /**
     * Every rendering a {@code CHECK} holds, in the order the DDL declares them. Every {@code CHECK}
     * is read wherever it is written: as a table constraint, named or not, inline on a column, or
     * added by an {@code ALTER TABLE}, so no syntactic route around the gate is left open.
     */
    static List<Finding> scan(String statements) {
        var findings = new ArrayList<Finding>();
        var columnsOf = new java.util.HashMap<String, Set<String>>();
        Matcher table = TABLE.matcher(statements);
        while (table.find()) {
            String name = table.group(1);
            var columns = new LinkedHashSet<String>();
            for (String element : split(table.group(2), ",")) {
                String text = normalize(element);
                String first = text.isEmpty() ? "" : text.split("[\\s(]", 2)[0];
                if (!first.isEmpty() && !CONSTRAINT_HEADS.contains(first.toUpperCase(java.util.Locale.ROOT))) {
                    columns.add(first);
                }
            }
            columnsOf.put(name, columns);
            findings.addAll(judge(name, columns, checks(table.group(2))));
        }
        Matcher alter = ALTER.matcher(statements);
        while (alter.find()) {
            String name = alter.group(1);
            findings.addAll(judge(name, columnsOf.getOrDefault(name, Set.of()), checks(alter.group(2))));
        }
        return findings;
    }

    /** The condition of every {@code CHECK} in {@code text}, normalized and unwrapped. */
    private static List<String> checks(String text) {
        var conditions = new ArrayList<String>();
        Matcher check = CHECK.matcher(text);
        while (check.find()) {
            int open = check.end() - 1;
            int depth = 0;
            for (int i = open; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')' && --depth == 0) {
                    conditions.add(unwrap(normalize(text.substring(open, i + 1))));
                    break;
                }
            }
        }
        return conditions;
    }

    private static List<Finding> judge(String table, Set<String> columns, List<String> checks) {
        var findings = new ArrayList<Finding>();
        for (String check : checks) {
            if (TWIN.matcher(check).matches() || PINNED.contains(table + ": " + check)) {
                continue;
            }
            var seen = new LinkedHashSet<String>();
            for (String atom : atoms(check)) {
                String column = renderedColumn(atom, columns);
                if (column != null && seen.add(column)) {
                    findings.add(new Finding(table + "." + column, check));
                }
            }
        }
        return findings;
    }

    /**
     * The column an atom holds to a rendering: one side a bare column of the table, the other an
     * expression naming another column of it. Null for any other atom, a comparison to a literal or
     * a biconditional among them.
     */
    private static String renderedColumn(String atom, Set<String> columns) {
        var sides = split(atom, "=");
        if (sides.size() != 2) {
            return null;
        }
        for (int bare = 0; bare < 2; bare++) {
            String column = sides.get(bare).trim();
            String other = sides.get(1 - bare);
            if (columns.contains(column) && namesAnother(other, column, columns)) {
                return column;
            }
        }
        return null;
    }

    private static boolean namesAnother(String expression, String self, Set<String> columns) {
        Matcher identifier = IDENTIFIER.matcher(expression);
        while (identifier.find()) {
            if (!identifier.group().equals(self) && columns.contains(identifier.group())) {
                return true;
            }
        }
        return false;
    }

    /** The conjuncts and disjuncts of a condition, recursively, each with its own parentheses off. */
    private static List<String> atoms(String condition) {
        String text = unwrap(condition.trim());
        var parts = new ArrayList<String>();
        for (String disjunct : split(text, " OR ")) {
            parts.addAll(split(disjunct, " AND "));
        }
        if (parts.size() == 1) {
            return List.of(text);
        }
        var atoms = new ArrayList<String>();
        parts.forEach(part -> atoms.addAll(atoms(part)));
        return atoms;
    }

    /** {@code text} cut at every top-level {@code separator}; an {@code =} inside {@code <>}, {@code >=}, {@code <=} or {@code !=} is not one. */
    private static List<String> split(String text, String separator) {
        var parts = new ArrayList<String>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && text.regionMatches(true, i, separator, 0, separator.length())
                    && isSeparator(text, i, separator)) {
                parts.add(text.substring(start, i));
                start = i + separator.length();
                i = start - 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    private static boolean isSeparator(String text, int at, String separator) {
        if (!separator.equals("=")) {
            return true;
        }
        char before = at > 0 ? text.charAt(at - 1) : ' ';
        return before != '<' && before != '>' && before != '!';
    }

    /** The condition without parentheses that enclose all of it. */
    private static String unwrap(String text) {
        String current = text.trim();
        while (current.startsWith("(") && current.endsWith(")") && closesAtEnd(current)) {
            current = current.substring(1, current.length() - 1).trim();
        }
        return current;
    }

    private static boolean closesAtEnd(String text) {
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0 && i < text.length() - 1) {
                    return false;
                }
            }
        }
        return true;
    }

    private static String normalize(String text) {
        return text.trim().replaceAll("\\s+", " ");
    }
}
