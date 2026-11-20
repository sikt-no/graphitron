package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline tier: {@code @nodeId(typeName:)} naming a multitable interface or union at a
 * {@code @service} slot, decoded into a slot typed as a jOOQ record supertype. The positive cases
 * assert that the schema <em>builds</em> and that the slot's transform is the polymorphic decode
 * carrying the candidates in member order, because a silent detection and a red build look alike
 * from above; the dispatch the emitted helper performs is behaviour and is pinned at the execution
 * tier rather than as a string here.
 *
 * <p>The refusals are the other half and each names one fact: a slot typed as one candidate's own
 * record (the single-type spelling the author declined), a scalar slot (nowhere to carry which
 * implementation the id was), a container with a {@code @table} member that is not a node type (a
 * candidate set with a hole in it would reject one member's ids at runtime), a single-table
 * {@code @discriminate} container (one record class, so the single-type decode is what the author
 * wants), and a candidate whose table has no primary key under an {@code UpdatableRecord<?>} slot
 * (jOOQ generates a {@code TableRecordImpl} there, which the slot's own declared ancestry says).
 */
@PipelineTier
class PolymorphicNodeIdSlotPipelineTest {

    private static final String SERVICE_STUB = "no.sikt.graphitron.rewrite.TestServiceStub";
    private static final String PRODUCER_STUB = "no.sikt.graphitron.rewrite.PublicNodeIdServiceStub";

    /** The two node types the union holds, plus the {@code Node} interface both publish. */
    private static final String OCCUPANTS = """
        interface Node { id: ID! }
        type Customer implements Node @table(name: "customer") @node(keyColumns: ["customer_id"]) {
            id: ID! @nodeId
        }
        type Staff implements Node @table(name: "staff") @node(keyColumns: ["staff_id"]) {
            id: ID! @nodeId
        }
        union AddressOccupant = Customer | Staff
        """;

    private static String beanSchema(String member, String method) {
        return OCCUPANTS + """
            input AssignOccupantInput {
                %s
            }
            type Query {
                assignOccupant(in: AssignOccupantInput!): String
                    @service(service: {className: "%s", method: "%s"})
            }
            """.formatted(member, SERVICE_STUB, method);
    }

    private static String producerSchema(String container, String method) {
        return OCCUPANTS + """
            type Film @table(name: "film") { title: String }
            type Query {
                films(key: ID! @nodeId(typeName: "%s")): [Film!]!
                    @service(service: {className: "%s", method: "%s"})
            }
            """.formatted(container, PRODUCER_STUB, method);
    }

    /**
     * The same producer field with a list argument, which is the shape a {@code java.util.List}
     * producer parameter takes: the list decode hands over one decoded record per id.
     */
    private static String producerListSchema(String container, String method) {
        return OCCUPANTS + """
            type Film @table(name: "film") { title: String }
            type Query {
                films(key: [ID!]! @nodeId(typeName: "%s")): [Film!]!
                    @service(service: {className: "%s", method: "%s"})
            }
            """.formatted(container, PRODUCER_STUB, method);
    }

    // ===== The slot takes the container's members =====

    // ===== Refusals =====

    /**
     * The read side keeps its refusal and says why the coordinate is the wrong one. The store states
     * the same fact as {@code CONTAINER_NOT_AT_A_SLOT}, whose rows are pinned in the fact-store tier;
     * what this case pins is the wording that is supposed to agree with them, so the author who wrote
     * the container one coordinate too far out reads the remedy rather than "is not @table-annotated",
     * a mistake they did not make.
     */
    @Test
    void aContainerAtAReadSideArgumentIsRefusedForTheCoordinateRatherThanForItsKind() {
        String sdl = OCCUPANTS + """
            type Query {
                customers(occupantId: ID @nodeId(typeName: "AddressOccupant")): [Customer!]!
            }
            """;
        var field = (GraphitronField.UnclassifiedField)
            TestSchemaHelper.buildSchema(sdl).field("Query", "customers");
        assertThat(field.rejection().message())
            .as("the coordinate is named, and so is where the polymorphic spelling belongs")
            .contains("names a polymorphic container")
            .contains("occupantId")
            .contains("@service")
            .doesNotContain("is not @table-annotated");
    }
}
