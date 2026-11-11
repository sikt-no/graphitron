package no.sikt.graphitron.rewrite.schema.federation;

import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.model.EntityResolution;
import no.sikt.graphitron.rewrite.model.EntityResolutions;
import no.sikt.graphitron.rewrite.model.KeyAlternative;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The entities {@link StoreEntityResolutions} reads off a captured schema: each table-bound type with
 * a key and each resolvable node, its alternatives in key order, a direct key's fields paired with the
 * columns they bind, and the node id alternative where the node's id is a key, authored, minted after
 * the authored ones, or added first where no key states it. Read over the
 * {@code scenarios/entity-key-alternatives} fact document, and for a graph with no federation over
 * {@code scenarios/node-id-decode-record}.
 */
@PipelineTier
class StoreEntityResolutionsTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static EntityResolutions federated;
    private static EntityResolutions unfederated;

    @BeforeAll
    static void read() {
        federated = FACTS.read("scenarios/entity-key-alternatives",
            graph -> StoreEntityResolutions.read(graph.handle()));
        unfederated = FACTS.read("scenarios/node-id-decode-record",
            graph -> StoreEntityResolutions.read(graph.handle()));
    }

    @Test
    void entitiesAreTheKeyedTypesAndTheResolvableNodes() {
        assertThat(federated.byType()).containsOnlyKeys("Actor", "Customer", "Film", "Rental");
        assertThat(unfederated.byType()).containsOnlyKeys("Film", "Language", "Store");
    }

    @Test
    void anAuthoredKeyOverColumnsPairsEachFieldWithItsColumn() {
        var rental = federated.byType().get("Rental");
        assertThat(rental.table().javaFieldName()).isEqualTo("RENTAL");
        assertThat(rental.alternatives()).extracting(KeyAlternative::requiredFields, KeyAlternative::resolvable)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(List.of("rentalId", "inventoryId"), true),
                org.assertj.core.groups.Tuple.tuple(List.of("rentalId"), false));
        assertThat(rental.alternatives().getFirst().columns()).extracting(ColumnRef::sqlName, ColumnRef::javaName)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("rental_id", "RENTAL_ID"),
                org.assertj.core.groups.Tuple.tuple("inventory_id", "INVENTORY_ID"));
        assertThat(rental.resolvable()).isTrue();
    }

    @Test
    void theMintedNodeKeyComesAfterTheAuthoredOnes() {
        var film = federated.byType().get("Film");
        assertThat(film.alternatives()).hasSize(2);
        assertThat(film.alternatives().get(0)).isInstanceOf(KeyAlternative.Direct.class);
        assertThat(film.alternatives().get(0).requiredFields()).containsExactly("languageId");
        assertThat(film.alternatives().get(1)).isEqualTo(
            new KeyAlternative.NodeId("Film", List.of(column("film_id", "FILM_ID")), true));
    }

    @Test
    void theNodesOwnIdIsTheNodeIdAlternativeAuthoredOrMinted() {
        assertThat(nodeIdOnly(federated.byType().get("Actor"))).isEqualTo("Actor");
        assertThat(nodeIdOnly(federated.byType().get("Customer"))).isEqualTo("Customer");
    }

    @Test
    void aNodeNoKeyStatesIsResolvedByItsId() {
        assertThat(nodeIdOnly(unfederated.byType().get("Film"))).isEqualTo("F");
        assertThat(unfederated.byType().get("Film").alternatives().getFirst().columns())
            .extracting(ColumnRef::sqlName).containsExactly("film_id");
    }

    /** The type id of the entity's one alternative, which must be a resolvable node id alternative. */
    private static String nodeIdOnly(EntityResolution entity) {
        assertThat(entity.alternatives()).hasSize(1);
        var alternative = entity.alternatives().getFirst();
        assertThat(alternative).isInstanceOf(KeyAlternative.NodeId.class);
        assertThat(alternative.resolvable()).isTrue();
        return ((KeyAlternative.NodeId) alternative).expectedTypeId();
    }

    private static ColumnRef column(String sqlName, String javaName) {
        var column = federated.byType().get("Film").table().column(sqlName).orElseThrow();
        assertThat(column.javaName()).isEqualTo(javaName);
        return column;
    }
}
