package no.sikt.graphitron.model;

import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.model.derive.ViewReferences;
import no.sikt.graphitron.model.derive.ViewReferences.Position;
import no.sikt.graphitron.model.derive.ViewReferences.Reference;
import no.sikt.graphitron.model.test.FactStores;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The position classifier against view bodies whose answers are known by reading them, which is the
 * order this has to be established in: the classifier is pointed at the fact schema only once it
 * agrees with hand-derived answers on each shape it claims to tell apart.
 *
 * <p>Hand-written bodies over hand-written tables rather than the fact schema's own views. A case
 * over a real relation would pin the classifier to that relation's current spelling, so a rewrite
 * that changed nothing about position would fail it, and reading the expected answer would mean
 * reading a derivation twenty relations deep. These bodies are three lines each and the answer is
 * visible in them.
 *
 * <p>The bodies go through {@code CREATE VIEW} rather than being parsed as text, because the input
 * the classifier actually meets is H2's normalization of what an author wrote, not the authored
 * spelling. Round-tripping through the catalog is what makes these cases evidence about the walk
 * that runs in production rather than about a string.
 */
@DisplayName("ViewReferences reads a reference's position out of a stored definition")
class ViewReferencesTest {

    private GraphitronModelStore store;
    private DSLContext dsl;

    @BeforeEach
    void openStore() {
        store = FactStores.inMemory();
        dsl = store.dsl();
        dsl.execute("CREATE TABLE probe_base (a INT, b INT)");
        dsl.execute("CREATE TABLE probe_other (a INT, c INT)");
        dsl.execute("CREATE VIEW probe_leaf AS SELECT a, b FROM probe_base");
    }

    @AfterEach
    void closeStore() {
        store.close();
    }

    @Test
    @DisplayName("a plain read is one reference and nothing re-evaluates it")
    void plain() {
        view("probe_plain", "SELECT a FROM probe_leaf");

        assertThat(referencesTo("probe_plain", "probe_leaf"))
            .singleElement()
            .satisfies(reference -> {
                assertThat(reference.enclosing()).isEmpty();
                assertThat(reference.position()).isEmpty();
                assertThat(reference.reEvaluated()).isFalse();
            });
    }

    @Test
    @DisplayName("multiplicity survives: a rule named twice is two references, not one relation")
    void multiplicity() {
        view("probe_twice", "SELECT l.a FROM probe_leaf l JOIN probe_leaf m ON l.a = m.a");

        assertThat(referencesTo("probe_twice", "probe_leaf")).hasSize(2);
        assertThat(ViewReferences.relationsReadBy(dsl, "probe_twice")).containsExactly("probe_leaf");
    }

    @Test
    @DisplayName("the second operand of a join is the inner side and the first is not")
    void innerSideOfJoin() {
        view("probe_join", "SELECT o.a FROM probe_other o LEFT JOIN probe_leaf l ON o.a = l.a");

        assertThat(positionsIn("probe_join", "probe_other")).containsExactly(List.of());
        assertThat(positionsIn("probe_join", "probe_leaf"))
            .containsExactly(List.of(Position.INNER_SIDE));
    }

    @Test
    @DisplayName("a derived table on the inner side puts what it reads on the inner side too")
    void innerSideThroughDerivedTable() {
        view("probe_derived", "SELECT o.a FROM probe_other o "
            + "JOIN (SELECT a FROM probe_leaf) d ON o.a = d.a");

        assertThat(positionsIn("probe_derived", "probe_leaf"))
            .containsExactly(List.of(Position.INNER_SIDE));
    }

    @Test
    @DisplayName("a subquery naming an outer alias is correlated")
    void correlatedSubquery() {
        view("probe_correlated", "SELECT o.a FROM probe_other o "
            + "WHERE EXISTS (SELECT 1 FROM probe_leaf l WHERE l.a = o.a)");

        assertThat(positionsIn("probe_correlated", "probe_leaf"))
            .containsExactly(List.of(Position.CORRELATED));
    }

    @Test
    @DisplayName("a correlated scalar subquery in the select list counts the same way")
    void correlatedScalarSubquery() {
        view("probe_scalar", "SELECT o.a, (SELECT MIN(l.b) FROM probe_leaf l WHERE l.a = o.a) m "
            + "FROM probe_other o");

        assertThat(positionsIn("probe_scalar", "probe_leaf"))
            .containsExactly(List.of(Position.CORRELATED));
    }

    @Test
    @DisplayName("a subquery binding everything it names is not correlated, and this is the case "
        + "that separates correlation from merely sitting in a subquery")
    void uncorrelatedSubqueryIsNotPerRow() {
        view("probe_uncorrelated", "SELECT o.a FROM probe_other o "
            + "WHERE o.a IN (SELECT l.a FROM probe_leaf l)");

        assertThat(positionsIn("probe_uncorrelated", "probe_leaf")).containsExactly(List.of());
    }

    @Test
    @DisplayName("both terms of a self-referencing expression are recursive, the anchor included")
    void recursiveTermAndAnchor() {
        view("probe_recursive", "WITH RECURSIVE walk (a, b) AS ("
            + "  SELECT a, b FROM probe_leaf "
            + "  UNION ALL "
            + "  SELECT l.a, l.b FROM walk w JOIN probe_leaf l ON l.a = w.b"
            + ") SELECT a FROM walk");

        assertThat(positionsIn("probe_recursive", "probe_leaf")).containsExactlyInAnyOrder(
            List.of(Position.RECURSIVE),
            List.of(Position.RECURSIVE, Position.INNER_SIDE));
    }

    @Test
    @DisplayName("a common table expression that does not name itself is not recursive, which is "
        + "what keeps the strongest position from being claimed for every WITH")
    void plainCommonTableExpressionIsNotRecursive() {
        view("probe_plaincte", "WITH c (a) AS (SELECT a FROM probe_leaf) "
            + "SELECT c.a FROM c JOIN probe_other o ON o.a = c.a");

        assertThat(positionsIn("probe_plaincte", "probe_leaf")).containsExactly(List.of());
    }

    @Test
    @DisplayName("positions nest, and the chain keeps both rather than the strongest alone")
    void positionsNest() {
        view("probe_nested", "SELECT o.a FROM probe_other o "
            + "WHERE EXISTS (SELECT 1 FROM probe_base b "
            + "              JOIN probe_leaf l ON l.a = b.a WHERE b.a = o.a)");

        assertThat(positionsIn("probe_nested", "probe_leaf"))
            .containsExactly(List.of(Position.CORRELATED, Position.INNER_SIDE));
        assertThat(referencesTo("probe_nested", "probe_leaf"))
            .singleElement()
            .satisfies(reference ->
                assertThat(reference.position()).contains(Position.CORRELATED));
    }

    @Test
    @DisplayName("an alias sharing a relation's name is not a read of it")
    void aliasIsNotARead() {
        view("probe_alias", "SELECT probe_leaf.a FROM probe_base probe_leaf");

        assertThat(ViewReferences.relationsReadBy(dsl, "probe_alias"))
            .containsExactly("probe_base");
    }

    @Test
    @DisplayName("a base table is a reference like any other, its position read the same way")
    void baseTablesAreReferencesToo() {
        view("probe_base_read", "SELECT o.a FROM probe_other o LEFT JOIN probe_base b ON o.a = b.a");

        assertThat(positionsIn("probe_base_read", "probe_base"))
            .containsExactly(List.of(Position.INNER_SIDE));
    }

    @Test
    @DisplayName("a view the catalog does not hold is a defect, named as one")
    void missingViewIsLoud() {
        assertThatThrownBy(() -> ViewReferences.readBy(dsl, "probe_absent"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("probe_absent");
    }

    private void view(String name, String body) {
        dsl.execute("CREATE VIEW " + name + " AS " + body);
    }

    private List<Reference> referencesTo(String view, String relation) {
        return ViewReferences.readBy(dsl, view).stream()
            .filter(reference -> reference.relation().equals(relation))
            .toList();
    }

    private List<List<Position>> positionsIn(String view, String relation) {
        return referencesTo(view, relation).stream().map(Reference::enclosing).toList();
    }
}
