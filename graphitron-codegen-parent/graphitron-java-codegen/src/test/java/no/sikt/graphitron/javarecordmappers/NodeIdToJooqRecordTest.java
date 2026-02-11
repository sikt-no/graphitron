package no.sikt.graphitron.javarecordmappers;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.mapping.JavaRecordMapperClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.COMPOSITE_KEY_INPUT_JAVA_RECORD;
import static no.sikt.graphitron.common.configuration.ReferencedEntry.DUMMY_SERVICE;
import static no.sikt.graphitron.common.configuration.ReferencedEntry.NODEID_INPUT_JAVA_RECORD;
import static no.sikt.graphitron.common.configuration.ReferencedEntry.RENTAL_INPUT_JAVA_RECORD;
import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_NODE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.FILM_ACTOR_NODE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.INVENTORY_NODE;

@DisplayName("@nodeId fields producing jOOQ records in Java record inputs")
public class NodeIdToJooqRecordTest extends GeneratorTest {

    @Override
    protected String getSubpath() {
        return "javamappers/torecord";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(DUMMY_SERVICE, NODEID_INPUT_JAVA_RECORD, RENTAL_INPUT_JAVA_RECORD, COMPOSITE_KEY_INPUT_JAVA_RECORD);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new JavaRecordMapperClassGenerator(schema, true));
    }

    @BeforeAll
    static void setUp() {
        GeneratorConfig.setNodeStrategy(true);
    }

    @AfterAll
    static void tearDown() {
        GeneratorConfig.setNodeStrategy(false);
    }

    @Test
    @DisplayName("Single @nodeId field creates jOOQ record")
    void singleNodeIdCreatesJooqRecord() {
        assertGeneratedContentContains("nodeIdToJooqRecord", Set.of(CUSTOMER_NODE),
                "CustomerRecord _mi_customer = new CustomerRecord()",
                "_iv_nodeIdStrategy.setReferenceId(_mi_customer"
        );
    }

    @Test
    @DisplayName("Multiple @nodeId fields merge into single jOOQ record")
    void multipleNodeIdsMergeIntoSingleRecord() {
        assertGeneratedContentContains("nodeIdMerging", Set.of(CUSTOMER_NODE, INVENTORY_NODE),
                "RentalRecord _mi_rental = new RentalRecord()",
                "_iv_nodeIdStrategy.setReferenceId(_mi_rental, nodeIdValue, \"CustomerNode\", Rental.RENTAL.CUSTOMER_ID);",
                "_iv_nodeIdStrategy.setReferenceId(_mi_rental, nodeIdValue, \"InventoryNode\", Rental.RENTAL.INVENTORY_ID);"
        );
    }

    @Test
    @DisplayName("@nodeId with composite key creates jOOQ record with multiple key columns")
    void compositeKeyNodeIdCreatesJooqRecord() {
        assertGeneratedContentContains("nodeIdCompositeKey", Set.of(FILM_ACTOR_NODE),
                "FilmActorRecord _mi_filmActor = new FilmActorRecord()",
                "_mo_compositeKeyInputJavaRecord.setFilmActor(_mi_filmActor_hasValue ? _mi_filmActor : null);",
                "_iv_nodeIdStrategy.setReferenceId(_mi_filmActor, nodeIdValue, \"FilmActorNode\", FilmActor.FILM_ACTOR.ACTOR_ID, FilmActor.FILM_ACTOR.FILM_ID);"
        );
    }
}
