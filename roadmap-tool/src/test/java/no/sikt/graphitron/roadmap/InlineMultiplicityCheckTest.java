package no.sikt.graphitron.roadmap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the multiplicity metric to the arithmetic it claims, and to the two parsing mistakes that
 * made an earlier hand-rolled version of it wrong.
 *
 * <p>Synthetic schemas throughout. The real DDL is what the report runs against, but a test over it
 * would assert a number that changes with every derivation anyone adds, which is a test of the
 * schema rather than of the metric.
 */
class InlineMultiplicityCheckTest {

    @Test
    @DisplayName("a view's cost is its own plus each child's, once per naming")
    void multiplicitiesCompoundDownTheTree() {
        var schema = InlineMultiplicityCheck.parse("""
            CREATE TABLE base (x VARCHAR);
            CREATE VIEW leaf (x) AS SELECT x FROM base;
            CREATE VIEW middle (x) AS SELECT l.x FROM leaf l JOIN leaf r ON l.x = r.x;
            CREATE VIEW top (x) AS SELECT m.x FROM middle m JOIN middle n ON m.x = n.x
              UNION ALL SELECT x FROM leaf;
            """);

        assertThat(subtree(schema, "base")).as("a table is itself").isEqualTo(1);
        assertThat(subtree(schema, "leaf")).as("itself plus one base").isEqualTo(2);
        assertThat(subtree(schema, "middle")).as("itself plus two leaves at 2").isEqualTo(5);
        assertThat(subtree(schema, "top")).as("itself plus two middles at 5 plus one leaf at 2")
            .isEqualTo(13);
    }

    @Test
    @DisplayName("a materialized relation drops out by construction rather than by exemption")
    void aTableCostsOneHoweverDeepItsRuleWas() {
        var asView = InlineMultiplicityCheck.parse("""
            CREATE TABLE base (x VARCHAR);
            CREATE VIEW rule (x) AS SELECT x FROM base;
            CREATE VIEW reader (x) AS SELECT a.x FROM rule a JOIN rule b ON a.x = b.x;
            """);
        var materialized = InlineMultiplicityCheck.parse("""
            CREATE TABLE base (x VARCHAR);
            CREATE VIEW rule_live (x) AS SELECT x FROM base;
            CREATE TABLE rule (x VARCHAR);
            CREATE VIEW reader (x) AS SELECT a.x FROM rule a JOIN rule b ON a.x = b.x;
            """);

        assertThat(subtree(asView, "reader")).isEqualTo(5);
        assertThat(subtree(materialized, "reader"))
            .as("the same reader over a target, which has no subtree to inline")
            .isEqualTo(3);
    }

    /**
     * The mistake that inflated the first hand-rolled run of this metric by 83 instantiations and
     * invented two direct children: the schema's prose section headers are {@code --} line
     * comments, and a scan that strips only {@code COMMENT ON} statements attributes every relation
     * name in a header to whichever view's block precedes it.
     */
    @Test
    @DisplayName("a relation named in a line comment is not a reference")
    void lineCommentsAreNotBodies() {
        var schema = InlineMultiplicityCheck.parse("""
            CREATE TABLE base (x VARCHAR);
            CREATE TABLE decoy (x VARCHAR);
            -- ==== A section header naming decoy, decoy and decoy again ====
            CREATE VIEW reader (x) AS SELECT x FROM base; -- and a trailing note about decoy
            """);

        assertThat(references(schema, "reader")).containsOnlyKeys("base");
        assertThat(subtree(schema, "reader")).isEqualTo(2);
    }

    /** The same for {@code COMMENT ON} prose, which is where most of the schema's words live. */
    @Test
    @DisplayName("a relation named in comment prose is not a reference")
    void commentLiteralsAreNotBodies() {
        var schema = InlineMultiplicityCheck.parse("""
            CREATE TABLE base (x VARCHAR);
            CREATE TABLE decoy (x VARCHAR);
            CREATE VIEW reader (x) AS SELECT x FROM base;
            COMMENT ON VIEW reader IS 'Reads base. Deliberately not decoy, whose rows are the
              consumer''s and not this graph''s; a reader wanting decoy joins it itself.';
            """);

        assertThat(references(schema, "reader")).containsOnlyKeys("base");
    }

    @Test
    @DisplayName("a column named after a relation is not a reference to it")
    void aQualifiedColumnIsNotARelation() {
        var schema = InlineMultiplicityCheck.parse("""
            CREATE TABLE base (x VARCHAR);
            CREATE TABLE graph_name (x VARCHAR);
            CREATE VIEW reader (x) AS SELECT b.graph_name FROM base b;
            """);

        assertThat(references(schema, "reader"))
            .as("b.graph_name is a column, and the leading dot is what says so")
            .containsOnlyKeys("base");
    }

    @Test
    @DisplayName("a cycle contributes itself rather than recursing")
    void aCycleTerminates() {
        var schema = InlineMultiplicityCheck.parse("""
            CREATE VIEW a (x) AS SELECT x FROM b;
            CREATE VIEW b (x) AS SELECT x FROM a;
            """);

        assertThat(subtree(schema, "a"))
            .as("itself, plus b, plus the cut where b names a again")
            .isEqualTo(3);
    }

    private static int subtree(InlineMultiplicityCheck.Schema schema, String relation) {
        return InlineMultiplicityCheck.subtree(schema, relation, new java.util.HashMap<>(),
            new LinkedHashSet<>());
    }

    private static java.util.Map<String, Integer> references(
            InlineMultiplicityCheck.Schema schema, String view) {
        return InlineMultiplicityCheck.references(schema, view, schema.views().get(view));
    }
}
