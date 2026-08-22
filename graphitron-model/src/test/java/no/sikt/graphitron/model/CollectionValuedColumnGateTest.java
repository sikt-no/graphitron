package no.sikt.graphitron.model;

import no.sikt.graphitron.model.test.FactStores;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate beside the schema's other authoring gates: no relation serializes a collection into one
 * scalar column.
 *
 * <p>The rule the gate defends is atomicity plus key-dependence, not a distaste for renders. A
 * column may carry an opaque value the store did not compose, provided nothing joins, groups or
 * filters on it, which is what the schema's own comments mean by "display material, never a
 * dimension". A column anything joins, groups or filters on must be atomic to the engine and a
 * function of its relation's own key. {@code intent_type_backing_conflict} carries the whole lesson
 * in one relation: {@code candidates}, a count over the contesting classes, passes; the same set
 * joined into one string would fail, because its element grain sits inside the value where no key,
 * constraint or join reaches it. A serialized set answers set equality and nothing else, where the
 * rows answer that and membership, for one join.
 *
 * <p>A denylist of named constructs rather than a heuristic, because the failure message can then
 * say what to write instead, and because "detect a rendering" is neither implementable nor the rule:
 * the schema holds renders that pass. {@code ARRAY_AGG} is on the list despite producing no string
 * at all, which is the discriminator working: the property denied is a collection in a scalar, not a
 * delimiter.
 *
 * <p><b>The disclosed gap.</b> This gate catches aggregates and nothing else, so it would not have
 * caught the {@code directory} column the diagnostics surface used to carry: a row-local
 * {@code REGEXP_REPLACE} truncating a path is a collection in a scalar with no aggregate anywhere,
 * a path being a sequence of segments of which the truncation keeps one. Detecting serialization
 * inside an arbitrary scalar expression is not mechanizable, so a green gate is not a clean schema;
 * for that half the enforcer is the fact model's own prose and a reader who has read it. What this
 * gate covers is the half that is mechanical.
 *
 * <p>The scan is lexically scoped and that is the part easy to get wrong. The schema file is mostly
 * {@code COMMENT ON} prose, and that prose discusses aggregation in English, so a naive grep over
 * the file fails a clean tree by matching a comment that explains why a construct is absent. The
 * splitter below blanks {@code --} line comments and the contents of string literals in one pass,
 * which leaves the {@code COMMENT ON} statement's keyword and drops its body, the same habitat
 * distinction the roadmap guard draws between comment regions and code. Quote state spans lines
 * because a comment literal does, and the doubled-quote escape needs no special case: two adjacent
 * quotes toggle through to the same state.
 *
 * <p>Reads the DDL text rather than a booted catalog on purpose. The point is to reject the
 * construct where it is authored, and a booted store has already lost the spelling: H2 stores a
 * view's expanded definition, and the aggregate that produced a column is gone by the time the
 * column has a type.
 */
class CollectionValuedColumnGateTest {

    /**
     * The denied constructs, each named so the failure can say which one fired. {@code WITHIN GROUP}
     * is here as the clause that introduces ordered-set aggregation, which is how the string
     * aggregates spell their element order and is worth refusing in its own right.
     */
    private static final List<Pattern> DENIED = List.of(
        Pattern.compile("\\bLISTAGG\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bSTRING_AGG\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bGROUP_CONCAT\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bARRAY_AGG\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bWITHIN\\s+GROUP\\b", Pattern.CASE_INSENSITIVE));

    @Test
    @DisplayName("no statement region serializes a collection into one scalar column")
    void noStatementRegionAggregatesACollectionIntoAScalar() {
        String ddl = FactStores.schemaText();
        // The floor against a vacuous pass: the scan must have seen the schema, not an empty read
        // or a file whose every line looked like prose.
        assertThat(statements(ddl)).as("statement text left after the prose is blanked")
            .contains("CREATE VIEW").contains("CREATE TABLE");
        assertThat(scan(statements(ddl)))
            .as("collection-valued columns in the fact schema. A column anything joins, groups or "
                + "filters on must be atomic to the engine and a function of its relation's own "
                + "key, so a set belongs in rows under that key and not in one value. Write what "
                + "intent_type_backing_conflict writes: the arity as a column, the members as the "
                + "rows of the relation they already sit on, and let the consumer join. A "
                + "consumer that wants one string joins the rows where it renders them.")
            .isEmpty();
    }

    @Test
    @DisplayName("the gate pins its acceptance line in both directions")
    void theGatePinsItsAcceptanceLine() {
        // Rejected: every denied construct, in the spellings two engines accept.
        assertThat(scan(statements(
            "CREATE VIEW v AS SELECT LISTAGG(x, ',') FROM t;"))).isNotEmpty();
        assertThat(scan(statements(
            "CREATE VIEW v AS SELECT STRING_AGG(x, ',' ORDER BY x) FROM t;"))).isNotEmpty();
        assertThat(scan(statements(
            "CREATE VIEW v AS SELECT GROUP_CONCAT(x) FROM t;"))).isNotEmpty();
        assertThat(scan(statements(
            "CREATE VIEW v AS SELECT ARRAY_AGG(x) FROM t;"))).isNotEmpty();
        assertThat(scan(statements(
            "CREATE VIEW v AS SELECT any_agg(x) WITHIN GROUP (ORDER BY x) FROM t;"))).isNotEmpty();

        // Accepted: prose that names the constructs while explaining why they are absent. This half
        // is what a naive scan gets wrong, and it is most of the file.
        assertThat(scan(statements(
            "COMMENT ON VIEW v IS 'no LISTAGG here: a STRING_AGG within group would serialize a "
                + "set, and ARRAY_AGG is no better for holding one';"))).isEmpty();
        assertThat(scan(statements(
            "-- the GROUP_CONCAT this file does not use\nCREATE VIEW v AS SELECT x FROM t;")))
            .isEmpty();
        // A doubled quote inside a literal must not end it, or the splitter mis-slices the rest of
        // the file and the prose after it starts reading as code.
        assertThat(scan(statements(
            "COMMENT ON VIEW v IS 'the relation''s own LISTAGG discussion';\n"
                + "CREATE VIEW w AS SELECT COUNT(*) FROM t;"))).isEmpty();

        // Accepted: the admissible shapes, each a function of the row's own key and atomic to the
        // engine. These are the schema's live counter-examples, not hypotheticals.
        assertThat(scan(statements(
            "CREATE VIEW v AS SELECT UPPER(type_name) FROM t;"))).isEmpty();
        assertThat(scan(statements(
            "CREATE VIEW v AS SELECT type_name || '.' || field_name FROM t;"))).isEmpty();
        assertThat(scan(statements(
            "CREATE VIEW v AS SELECT CAST(COUNT(DISTINCT class_name) AS INT) FROM t"
                + " GROUP BY graph_name HAVING COUNT(DISTINCT class_name) > 1;"))).isEmpty();
        assertThat(scan(statements(
            "CREATE VIEW v AS SELECT MAX(a), MIN(b), SUM(c) FROM t GROUP BY d;"))).isEmpty();
    }

    /** Every denied construct the statement regions carry, each finding naming the construct. */
    private static List<String> scan(String statements) {
        var findings = new ArrayList<String>();
        for (Pattern denied : DENIED) {
            var matcher = denied.matcher(statements);
            while (matcher.find()) {
                findings.add(matcher.group().toUpperCase(Locale.ROOT).replaceAll("\\s+", " "));
            }
        }
        return findings;
    }

    /**
     * The DDL's statement regions: the file with {@code --} line comments and the contents of every
     * string literal blanked, so prose about a construct cannot read as a use of one.
     *
     * <p>One linear pass rather than two regexes, because both would need the same quote state and
     * because the obvious literal pattern backtracks catastrophically on a file this size.
     */
    static String statements(String ddl) {
        var out = new StringBuilder(ddl.length());
        boolean quoted = false;
        int i = 0;
        while (i < ddl.length()) {
            char c = ddl.charAt(i);
            if (quoted) {
                if (c == '\'') {
                    quoted = false;
                    out.append(c);
                } else if (c == '\n') {
                    // Kept so a finding's line position still means something in a multi-line
                    // literal; every other character inside one is blanked.
                    out.append(c);
                } else {
                    out.append(' ');
                }
                i++;
                continue;
            }
            if (c == '\'') {
                quoted = true;
                out.append(c);
                i++;
                continue;
            }
            if (c == '-' && i + 1 < ddl.length() && ddl.charAt(i + 1) == '-') {
                while (i < ddl.length() && ddl.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
