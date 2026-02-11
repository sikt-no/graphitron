package no.sikt.graphitron.validation;

import no.sikt.graphitron.configuration.GeneratorConfig;
import org.junit.jupiter.api.*;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_NODE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.NODE;

@DisplayName("Node directive input validation - Checks run when building the schema for types with node directive")
public class NodeIdInputTest extends ValidationTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "nodeId/input";
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
    @DisplayName("Type with given name does not exist")
    void typeDoesNotExist() {
        assertErrorsContain(
                () -> getProcessedSchema("typeDoesNotExist", Set.of(CUSTOMER_NODE)),
                "Type with name 'Address' referenced in the nodeId directive for argument 'addressId' on a field in type 'Query' does not exist."
        );
    }

    @Test
    @DisplayName("Type provided in jOOQ record input field does not exist")
    void typeDoesNotExistInJooqRecordInput() {
        assertErrorsContain(
                () -> getProcessedSchema("typeDoesNotExistInJooqRecordInput", Set.of(CUSTOMER_NODE)),
                "Type with name 'DoesNotExist' referenced in the nodeId directive for field 'CustomerInput.someId' does not exist."
        );
    }

    @Test
    @DisplayName("Type does not have the @node directive")
    void typeDoesNotHaveNodeDirective() {
        assertErrorsContain(
                () -> getProcessedSchema("typeDoesNotHaveNodeDirective", Set.of(CUSTOMER_NODE)),
                "Referenced type 'Address' referenced in the nodeId directive for argument 'addressId' on a field in type 'Query' is missing the necessary node directive."
        );
    }

    @Test
    @DisplayName("@nodeId on non-ID/string field")
    void nonIdOrStringField() {
        assertErrorsContain(
                () -> getProcessedSchema("nonIdOrStringField", Set.of(CUSTOMER_NODE)),
                "Argument 'id' on a field in type 'Query' has nodeId directive, but is not an ID or String field"
        );
    }

    @Test
    @DisplayName("@nodeId on string field")
    void onStringField() {
        getProcessedSchema("onStringField", Set.of(CUSTOMER_NODE));
    }

    @Test
    @DisplayName("nodeId combined with field directive")
    void withField() {
        assertErrorsContain(
                () -> getProcessedSchema("withField", Set.of(CUSTOMER_NODE)),
                "Argument 'id' on a field in type 'Query' has both the 'nodeId' and 'field' directives, which is only supported for node ID fields in Java Record inputs."
        );
    }

    @Test
    @DisplayName("nodeId combined with externalField directive")
    void withExternalField() {
        assertErrorsContain(
                () -> getProcessedSchema("withExternalField", Set.of(CUSTOMER_NODE)),
                "Argument 'id' on a field in type 'Query' has both the 'nodeId' and 'externalField' directives, which is not supported."
        );
    }

    @Test
    @DisplayName("Ambiguous reference")
    void inJooqRecordInputWithAmbiguousReference() {
        assertErrorsContain(
                () -> getProcessedSchema("inJooqRecordInputWithAmbiguousReference", Set.of(CUSTOMER_NODE)),
                "Cannot find foreign key for node ID field 'languageId' in jOOQ record input 'FilmFilter'."
        );
    }

    @Test
    @DisplayName("Inverse key reference in jOOQ input")
    void inJooqRecordInputWithInverseForeignKey() {
        assertErrorsContain(
                () -> getProcessedSchema("inJooqRecordInputWithInverseForeignKey", Set.of(CUSTOMER_NODE)),
                "Node ID field 'paymentId' in jOOQ record input 'CustomerFilter' references a table with an inverse key which is not supported."
        );
    }

    @Test
    @DisplayName("Node Id in jOOQ record input with reference via table")
    void inJooqRecordInputWithReferenceViaTable() {
        assertErrorsContain(
                () -> getProcessedSchema("inJooqRecordInputWithReferenceViaTable", Set.of(CUSTOMER_NODE)),
                "Node ID field 'cityId' in jOOQ record input 'CustomerFilter' has a reference via table(s) which is not supported on jOOQ record inputs."
        );
    }

    @Test
    @DisplayName("Foreign key does not reference the same key used for the target node type")
    void foreignKeyReferencesWrongKey() {
        assertErrorsContain(
                () -> getProcessedSchema("foreignKeyReferencesWrongKey", Set.of(NODE)),
                "Node ID field 'actorId' in jOOQ record input 'FilmActorFilter' uses foreign key " +
                        "'film_actor_actor_id_last_name_fkey' which does not reference the same primary/unique key used " +
                        "for type 'Actor's node ID. This is not supported."
        );
    }

    @Test
    @DisplayName("Non-mutation input when foreign key overlaps with the primary key of the jOOQ record table should be allowed")
    void foreignKeyOverlapsPrimaryKeyNonMutation() {
        getProcessedSchema("fkOverlapsPk/nonMutation");
    }

    @Test
    @DisplayName("Insert when foreign key overlaps with the primary key of the jOOQ record table should be allowed")
    void foreignKeyOverlapsPrimaryKeyInsertMutation() {
        getProcessedSchema("fkOverlapsPk/insertMutation");
    }

    @Test
    @DisplayName("Update when the foreign key overlaps with the primary key of the jOOQ record table should not be allowed")
    void foreignKeyOverlapsPrimaryKeyUpdateMutation() {
        assertErrorsContain(
                () -> getProcessedSchema("fkOverlapsPk/updateMutation", Set.of(NODE)),
                "Foreign key used for node ID field 'filmId' in jOOQ record input 'FilmActorInput' overlaps with the primary key of the jOOQ record table. This is not supported for update/upsert mutations."
        );
    }

    @Test
    @Disabled("Disabled until GGG-209")
    @DisplayName("nodeID with invalid reference")
    void invalidReference() {
        assertErrorsContain(
                () -> getProcessedSchema("invalidReference"),
                "...."
        );
    }

    @Test
    @Disabled("Disabled until GGG-209")
    @DisplayName("nodeID with invalid self-reference")
    void invalidSelfReference() {
        assertErrorsContain(
                () -> getProcessedSchema("invalidSelfReference", Set.of(CUSTOMER_NODE)),
                "...."
        );
    }

    @Test
    @DisplayName("Should log error when node ID field has ambiguous implicit node type")
    void ambiguousImplicitNodeType() {
        assertErrorsContain("ambiguousImplicitNodeType",
                "Cannot automatically deduce node type for node ID field 'CustomerFilter.id'. " +
                        "Please specify the node type with the typeName parameter"
        );
    }
}
