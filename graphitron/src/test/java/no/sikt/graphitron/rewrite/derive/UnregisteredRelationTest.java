package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.model.boot.ReadBudget;
import no.sikt.graphitron.model.boot.StoreAnswer;
import no.sikt.graphitron.model.derive.Materializations;
import no.sikt.graphitron.model.test.UnregisteredRelation;
import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The claim every figure {@link DerivedReadCostTest} reports rests on: reversing a registration
 * changes what a read <em>costs</em> and not what it <em>answers</em>. A helper that silently changed
 * an answer would make every number taken through it wrong in the same direction, and nothing in the
 * cost gate could notice, both of its sides having gone through this swap.
 *
 * <p>Two assertions rather than one, because the swap has two consumers with different exposure. A
 * direct read of the canonical name is the easy half. A read through a <em>view that names</em> the
 * canonical name is the half that could have failed for a reason outside this helper's control: H2
 * resolves a view's table references per session and keeps that resolution, so the question of whether
 * a dependent view follows the rename or re-resolves to the view installed over it is the engine's
 * answer and not ours. It re-resolves for any session that did not already compile the view, which is
 * what makes a reader minted after the swap the contract {@link UnregisteredRelation} states.
 *
 * <p>The relation under test is {@code intent_resolved_type_binding}, chosen because it is named from
 * thirteen view bodies, so the dependent-view half of the claim is exercised over a real reader rather
 * than a fixture written to have one.
 *
 * <p>{@code RunawayRelation}, the helper this one follows, has no case of its own in the model tree at
 * all: it is a test-jar helper exercised only through the fixtures that install it. This case lives
 * with its reader rather than repeating that gap.
 */
@PipelineTier
class UnregisteredRelationTest {

    @TempDir
    Path tmp;

    private static final String TARGET = "intent_resolved_type_binding";

    /** A view naming the target, so the swap is exercised through a reader and not only directly. */
    private static final String DEPENDENT = "intent_resolved_node_type_id";

    @Test
    void reversingARegistrationKeepsBothTheRelationsAnswerAndItsReadersAnswer() {
        var ctx = TestConfiguration.testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        String sdl = sdl();

        List<String> registeredTarget;
        List<String> registeredDependent;
        try (var store = CapturedStore.ofCatalog(tmp.resolve("registered"), sdl, jooq)) {
            registeredTarget = rows(store, TARGET);
            registeredDependent = rows(store, DEPENDENT);
        }
        // Without these the comparisons below would hold over two empty lists, which is the way a
        // fixture too thin to populate the relation it prices passes while asserting nothing.
        assertThat(registeredTarget).as("the fixture populates the swapped relation").isNotEmpty();
        assertThat(registeredDependent).as("and the view that names it").isNotEmpty();

        try (var store = CapturedStore.ofCatalog(tmp.resolve("unregistered"), sdl, jooq)) {
            var registration = Materializations.registrations(store.dsl()).stream()
                .filter(r -> r.targetTableName().equals(TARGET))
                .findFirst().orElseThrow();
            UnregisteredRelation.install(store.dsl(), registration);

            assertThat(kindOf(store, TARGET))
                .as("the canonical name is a view once the swap is installed, so a read of it"
                    + " evaluates the rule instead of scanning the table")
                .isEqualTo("VIEW");
            assertThat(rows(store, TARGET))
                .as("the unregistered shape answers exactly what the registered one did; this"
                    + " helper is meant to change cost and nothing else")
                .containsExactlyElementsOf(registeredTarget);
            assertThat(rows(store, DEPENDENT))
                .as("and so does a view that names the swapped relation, which is what makes a"
                    + " measurement taken through a reader meaningful")
                .containsExactlyElementsOf(registeredDependent);
        }
    }

    /**
     * The rows a relation answers, rendered and sorted, read through a minted reader for the reason
     * {@link UnregisteredRelation} gives: the writer surface that installed the swap is the one
     * session that would not see it.
     */
    private static List<String> rows(CapturedStore store, String relation) {
        try (var reader = store.reader(new ReadBudget.Unbounded())) {
            StoreAnswer<List<String>> answer = reader.read(dsl -> dsl
                .fetch("SELECT * FROM " + relation)
                .stream().map(record -> record.valuesRow().toString()).sorted().toList());
            assertThat(answer).isInstanceOf(StoreAnswer.Answered.class);
            return ((StoreAnswer.Answered<List<String>>) answer).value();
        }
    }

    private static String kindOf(CapturedStore store, String relation) {
        return store.dsl().fetchOne(
                "SELECT TABLE_TYPE FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'"
                    + " AND TABLE_NAME = ?", relation.toUpperCase(java.util.Locale.ROOT))
            .get(0, String.class);
    }

    /** Enough schema for the binding and a view over it to hold rows. */
    private static String sdl() {
        return """
            interface Node { id: ID! }
            type Film implements Node @table(name: "film") @node(keyColumns: ["film_id"]) {
              id: ID! @nodeId
              title: String
              language: Language @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Language implements Node @table(name: "language") @node(keyColumns: ["language_id"]) {
              id: ID! @nodeId
              name: String
            }
            type Query {
              films: [Film!]!
              film(id: ID! @nodeId(typeName: "Film")): Film
            }
            """;
    }
}
