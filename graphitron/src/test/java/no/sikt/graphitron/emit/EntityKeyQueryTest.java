package no.sikt.graphitron.emit;

import no.sikt.graphitron.emit.EntityKeyQuery.Alternative;
import no.sikt.graphitron.emit.EntityKeyQuery.Column;
import no.sikt.graphitron.emit.EntityKeyQuery.Entity;
import no.sikt.graphitron.emit.EntityKeyQuery.Identity;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * {@link EntityKeyQuery}'s entities and the order of their key alternatives: over
 * {@code scenarios/entity-key-alternatives}, keys over columns, an authored id key, minted node keys
 * and a compound key beside one this subgraph does not resolve by; over
 * {@code scenarios/node-id-decode-record}, a graph with nodes and no federation, where every
 * resolvable node is an entity by its id; and over {@code scenarios/synthesized-node-type-id}, a
 * federation stub, which is no entity here.
 */
@PipelineTier
class EntityKeyQueryTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static List<Entity> federated;
    private static List<Entity> unfederated;
    private static List<Entity> withStub;

    @BeforeAll
    static void read() {
        federated = FACTS.read("scenarios/entity-key-alternatives", graph -> EntityKeyQuery.read(graph.handle()));
        unfederated = FACTS.read("scenarios/node-id-decode-record", graph -> EntityKeyQuery.read(graph.handle()));
        withStub = FACTS.read("scenarios/synthesized-node-type-id", graph -> EntityKeyQuery.read(graph.handle()));
    }

    private static Entity entity(List<Entity> entities, String typeName) {
        return entities.stream().filter(e -> e.typeName().equals(typeName)).findFirst().orElseThrow();
    }

    @Test
    void entitiesAreTheKeyedTypesAndTheResolvableNodesInTypeNameOrder() {
        assertThat(federated).extracting(Entity::typeName).containsExactly("Actor", "Customer", "Film", "Rental");
        assertThat(unfederated).extracting(Entity::typeName).containsExactly("Film", "Language", "Store");
    }

    @Test
    void aKeyOverColumnsPairsEachFieldWithItsColumnInKeyOrder() {
        var rental = entity(federated, "Rental");
        assertThat(rental.tableName()).isEqualTo("rental");
        assertThat(rental.alternatives()).extracting(Alternative::identity, Alternative::resolvable)
            .containsExactly(tuple(new Identity.Key(0), true), tuple(new Identity.Key(1), false));
        var compound = (Alternative.Direct) rental.alternatives().getFirst();
        assertThat(compound.fields()).extracting(f -> f.fieldName(), f -> f.column().columnName(),
                f -> f.column().javaName())
            .containsExactly(tuple("rentalId", "rental_id", "RENTAL_ID"),
                tuple("inventoryId", "inventory_id", "INVENTORY_ID"));
        assertThat(rental.resolvable()).isTrue();
    }

    @Test
    void theMintedNodeKeyComesAfterTheAuthoredOnes() {
        var film = entity(federated, "Film");
        assertThat(film.alternatives()).extracting(Alternative::identity)
            .containsExactly(new Identity.Key(0), new Identity.NodeId());
        assertThat(film.alternatives().get(1)).isEqualTo(new Alternative.NodeId("Film",
            List.of(new Column("film_id", "FILM_ID", Integer.class.getName(), Integer.class.getName())), true));
    }

    @Test
    void theNodesOwnIdIsItsOneAlternativeAuthoredOrMinted() {
        assertThat(nodeIdOnly(entity(federated, "Actor"))).isEqualTo("Actor");
        assertThat(nodeIdOnly(entity(federated, "Customer"))).isEqualTo("Customer");
    }

    @Test
    void aResolvableNodeNoKeyStatesIsResolvedByItsId() {
        assertThat(nodeIdOnly(entity(unfederated, "Film"))).isEqualTo("F");
        assertThat(((Alternative.NodeId) entity(unfederated, "Film").alternatives().getFirst()).keyColumns())
            .extracting(Column::columnName).containsExactly("film_id");
    }

    @Test
    void aStubIsNoEntityHere() {
        assertThat(withStub).extracting(Entity::typeName).containsExactly("Customer", "Film", "FilmActor");
    }

    /** The type id of the entity's one alternative, which must be a resolvable node id alternative. */
    private static String nodeIdOnly(Entity entity) {
        assertThat(entity.alternatives()).singleElement().isInstanceOf(Alternative.NodeId.class);
        var nodeId = (Alternative.NodeId) entity.alternatives().getFirst();
        assertThat(nodeId.resolvable()).isTrue();
        return nodeId.typeId();
    }
}
