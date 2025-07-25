package no.sikt.graphitron.validation;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_NODE;

@DisplayName("Node directive input validation - Checks run when building the schema for types with node directive")
public class NodeIdInputTest extends ValidationTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "nodeId/input";
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
                "Argument 'id' on a field in type 'Query' has both the 'nodeId' and 'field' directives, which is not supported."
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
                () -> getProcessedSchema("foreignKeyReferencesWrongKey"),
                "Node ID field 'actorId' in jOOQ record input 'FilmActorFilter' uses foreign key " +
                        "'film_actor_actor_id_last_name_fkey' which does not reference the same primary/unique key used " +
                        "for type 'Actor's node ID. This is not supported."
        );
    }

    @Test
    @DisplayName("Foreign key overlaps with the primary key of the jOOQ record table")
    void foreignKeyOverlapsPrimaryKey() {
        assertErrorsContain(
                () -> getProcessedSchema("foreignKeyOverlapsPrimaryKey"),
                "Foreign key used for node ID field 'filmId' in jOOQ record input 'FilmActorFilter' overlaps with the primary key of the jOOQ record table. This is not supported."
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
}
