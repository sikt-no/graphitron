package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins that the occurrence argument-consistency guard
 * ({@code SelectionOccurrences.requireConsistentArguments(...)}) is emitted into an inline switch
 * arm exactly when that arm's body reads runtime arguments off its canonical {@code SelectedField}
 * — the {@link InlineTableFieldEmitter#readsSelectedFieldArguments} predicate, which mirrors the
 * emitter's {@code ArgumentValueSource.FromSelectedField} consumption sites (filters, pagination,
 * routine-hop args), plus the lookup arm's unconditional {@code @lookupKey} read. Gating on the
 * predicate (rather than guarding universally) keeps an arm that consumes nothing off the
 * {@code SelectedField} from fail-louding on divergence in arguments nothing reads.
 *
 * <p>The universal name-consistency guard has no per-arm emission to pin here: it runs inside
 * {@code SelectionOccurrences.canonical(...)}, which every bucket routes through before the switch
 * dispatch. Behaviour for both guards is pinned at the execution tier.
 */
@PipelineTier
class OccurrenceArgumentGuardPipelineTest {

    // Default (Sakila) catalog shapes: a bare inline reference (no argument reads), a filtered one
    // (list column filter -> FromSelectedField read), a paginated one (`first` read), and a
    // junction-path @lookupKey field (the input-rows helper always reads the lookup argument).
    private static final String SDL = """
        type Customer @table(name: "customer") { customerId: Int @field(name: "customer_id") }
        type Actor @table(name: "actor") { actorId: Int @field(name: "actor_id") }
        type Film @table(name: "film") {
            filmId: Int @field(name: "film_id")
            actors(actor_id: [Int!] @lookupKey): [Actor!]! @reference(path: [
                {key: "film_actor_film_id_fkey"},
                {key: "film_actor_actor_id_fkey"}
            ])
        }
        type Store @table(name: "store") {
            storeId: Int @field(name: "store_id")
            customers: [Customer!]! @reference(path: [{key: "customer_store_id_fkey"}])
            customersFirstN(first: Int): [Customer!]!
                @reference(path: [{key: "customer_store_id_fkey"}])
            customersByStoreId(storeIds: [ID!] @field(name: "store_id")): [Customer!]!
                @reference(path: [{key: "customer_store_id_fkey"}])
        }
        type Query { store: Store film: Film }
        """;

    private static GraphitronSchema schema() {
        return TestSchemaHelper.buildSchema(SDL);
    }

    private static TypeSpec typeClass(GraphitronSchema schema, String name) {
        return TypeClassGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void predicate_tracksFromSelectedFieldConsumption() {
        var schema = schema();
        var bare = (ChildField.TableField) schema.field("Store", "customers");
        var paginated = (ChildField.TableField) schema.field("Store", "customersFirstN");
        var filtered = (ChildField.TableField) schema.field("Store", "customersByStoreId");

        assertThat(InlineTableFieldEmitter.readsSelectedFieldArguments(bare))
            .as("a bare FK-path reference reads nothing off its SelectedField")
            .isFalse();
        assertThat(InlineTableFieldEmitter.readsSelectedFieldArguments(paginated))
            .as("the `first` pagination limit is read off the SelectedField")
            .isTrue();
        assertThat(InlineTableFieldEmitter.readsSelectedFieldArguments(filtered))
            .as("filter call params are read off the SelectedField")
            .isTrue();
    }

    @Test
    void tableFieldArm_emitsGuardExactlyWhenPredicateHolds() {
        var store = typeClass(schema(), "Store");

        assertThat(TypeSpecAssertions.armGuardsArgumentConsistency(store, "customers"))
            .as("no FromSelectedField read in the arm -> no argument guard")
            .isFalse();
        assertThat(TypeSpecAssertions.armGuardsArgumentConsistency(store, "customersFirstN"))
            .as("pagination read -> argument guard")
            .isTrue();
        assertThat(TypeSpecAssertions.armGuardsArgumentConsistency(store, "customersByStoreId"))
            .as("filter read -> argument guard")
            .isTrue();
    }

    @Test
    void lookupTableFieldArm_alwaysEmitsGuard() {
        var film = typeClass(schema(), "Film");

        assertThat(schema().field("Film", "actors"))
            .as("fixture must classify as the lookup shape the unconditional guard covers")
            .isInstanceOf(ChildField.LookupTableField.class);
        assertThat(TypeSpecAssertions.armGuardsArgumentConsistency(film, "actors"))
            .as("the input-rows helper always reads the @lookupKey argument off the SelectedField")
            .isTrue();
    }
}
