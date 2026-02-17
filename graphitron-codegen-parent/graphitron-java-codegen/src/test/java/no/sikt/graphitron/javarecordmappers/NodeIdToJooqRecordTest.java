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

import static no.sikt.graphitron.common.configuration.ReferencedEntry.*;
import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("@nodeId fields producing jOOQ records in Java record inputs")
public class NodeIdToJooqRecordTest extends GeneratorTest {

    @Override
    protected String getSubpath() {
        return "javamappers/torecord/nodeId";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(DUMMY_SERVICE, NODEID_INPUT_JAVA_RECORD, RENTAL_INPUT_JAVA_RECORD, COMPOSITE_KEY_INPUT_JAVA_RECORD,
                FILM_ACTOR_INPUT_JAVA_RECORD, FILM_JAVA_RECORD);
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
    void nodeIdToJooqRecord() {
        assertGeneratedContentMatches(
                "nodeIdToJooqRecord", CUSTOMER_NODE);
    }

    @Test
    @DisplayName("Multiple @nodeId reference fields merge into single jOOQ record")
    void nodeIdMerging() {
        assertGeneratedContentContains("nodeIdMerging", Set.of(CUSTOMER_NODE, INVENTORY_NODE),
                "var _mi_rental = new RentalRecord()",
                "setReferenceId(_mi_rental, _iv_nodeIdValue, \"CustomerNode\", Rental.RENTAL.CUSTOMER_ID)",
                "setReferenceId(_mi_rental, _iv_nodeIdValue, \"InventoryNode\", Rental.RENTAL.INVENTORY_ID)"
        );
    }

    @Test
    @DisplayName("@nodeId with composite key creates jOOQ record with multiple key columns")
    void nodeIdCompositeKey() {
        assertGeneratedContentContains("nodeIdCompositeKey", Set.of(FILM_ACTOR_NODE),
                "var _mi_filmActor = new FilmActorRecord()",
                "setId(_mi_filmActor, _iv_nodeIdValue, \"FilmActorNode\", FilmActor.FILM_ACTOR.ACTOR_ID, FilmActor.FILM_ACTOR.FILM_ID)"
        );
    }

    @Test
    @DisplayName("Reference and non-reference @nodeId fields merge into single jOOQ record with overlapping column")
    void nodeIdOverlappingColumn() {
        assertGeneratedContentContains("nodeIdOverlappingColumn", Set.of(FILM_ACTOR_NODE),
                "if (_iv_nodeIdValue != null) {",
                "MapperHelper.validateOverlappingNodeIdColumns(_iv_nodeIdStrategy, _iv_nodeIdValue, _mi_filmActor, \"FilmActorNode\", \"ACTOR_ID\", (_iv_it) -> _iv_it.getActorId(), FilmActor.FILM_ACTOR.ACTOR_ID, FilmActor.FILM_ACTOR.FILM_ID);",
                "MapperHelper.validateOverlappingNodeIdColumns(_iv_nodeIdStrategy, _iv_nodeIdValue, _mi_filmActor, \"Actor\", \"ACTOR_ID\", (_iv_it) -> _iv_it.getActorId(), FilmActor.FILM_ACTOR.ACTOR_ID);");
    }


    @Test
    @DisplayName("Reference that has different column names in source and target tables")
    void nodeIdDifferentColumnNames() {
        assertGeneratedContentContains("nodeIdReferenceDifferentColumnNames", Set.of(NODE),
                "_iv_nodeIdStrategy.setReferenceId(_mi_film, _iv_nodeIdValue, \"Language\", Film.FILM.ORIGINAL_LANGUAGE_ID);");
    }

}
