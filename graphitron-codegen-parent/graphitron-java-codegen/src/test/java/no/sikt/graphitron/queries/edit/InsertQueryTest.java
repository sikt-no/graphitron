package no.sikt.graphitron.queries.edit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Mutation queries - Query and conditions for inserting data")
public class InsertQueryTest extends MutationQueryTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "insert";
    }

    @Test
    @DisplayName("Default case with jOOQ record input")
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("With default node ID field")
    void nodeId() {
        assertGeneratedContentContains("nodeId",
                "insertInto(CUSTOMER, CUSTOMER.CUSTOMER_ID)",
                        ".values(DSL.val(_mi_inRecord.getCustomerId()))"
        );
    }

    @Test
    @DisplayName("With custom node ID field")
    void customNodeId() {
        assertGeneratedContentContains("customNodeId",
                "insertInto(CUSTOMER, CUSTOMER.CUSTOMER_ID)",
                ".values(DSL.val(_mi_inRecord.getCustomerId()))"
        );
    }

    @Test
    @DisplayName("With default node ID field with multiple key fields")
    void nodeIdWithMultipleFields() {
        assertGeneratedContentContains("nodeIdWithMultipleFields",
                "insertInto(VACATION_DESTINATION, VACATION_DESTINATION.DESTINATION_ID, VACATION_DESTINATION.COUNTRY_NAME)",
                """
                    .values(
                        DSL.val(_mi_inRecord.getDestinationId()),
                        DSL.val(_mi_inRecord.getCountryName())
                     )
                """
        );
    }

    @Test
    @DisplayName("With custom node ID field with multiple key fields")
    void customNodeIdWithMultipleFields() {
        assertGeneratedContentContains("customNodeIdWithMultipleFields",
                "insertInto(VACATION_DESTINATION, VACATION_DESTINATION.COUNTRY_NAME, VACATION_DESTINATION.DESTINATION_ID)",
                """
                    .values(
                        DSL.val(_mi_inRecord.getCountryName()),
                        DSL.val(_mi_inRecord.getDestinationId())
                    )
                """
        );
    }

    @Test
    @DisplayName("With default, implicit reference node ID field")
    void referenceNodeId() {
        assertGeneratedContentContains("referenceNodeId",
                "insertInto(CUSTOMER, CUSTOMER.ADDRESS_ID)" +
                        ".values(DSL.val(_mi_inRecord.getAddressId()))"
        );
    }

    @Test
    @DisplayName("With custom reference node ID field")
    void customReferenceNodeId() {
        assertGeneratedContentContains("customReferenceNodeId",
                "insertInto(CUSTOMER, CUSTOMER.ADDRESS_ID)" +
                        ".values(DSL.val(_mi_inRecord.getAddressId()))"
        );
    }

    @Test
    @DisplayName("Map node ID reference to correct column names")
    void referenceNodeIdWithColumnNameMismatch() {
        assertGeneratedContentContains("referenceNodeIdWithColumnNameMismatch",
                "insertInto(FILM, FILM.ORIGINAL_LANGUAGE_ID)",
                ".values(_mi_inRecord.getOriginalLanguageId() != null ? DSL.val(_mi_inRecord.getOriginalLanguageId()) : DSL.defaultValue(FILM.ORIGINAL_LANGUAGE_ID))"
        );
    }

    @Test
    @DisplayName("With multiple fields including node ID")
    void multipleFields() {
        assertGeneratedContentContains("multipleFields",
                "insertInto(CUSTOMER, CUSTOMER.ADDRESS_ID, CUSTOMER.CUSTOMER_ID, CUSTOMER.FIRST_NAME, CUSTOMER.LAST_NAME)",
                """
                    .values(
                        DSL.val(_mi_inRecord.getAddressId()),
                        DSL.val(_mi_inRecord.getCustomerId()),
                        DSL.val(_mi_inRecord.getFirstName()),
                        DSL.val(_mi_inRecord.getLastName())
                    )
                """
        );
    }

    @Test
    @DisplayName("With nullable field")
    void nullableField() {
        assertGeneratedContentContains("nullableField",
                "insertInto(CUSTOMER, CUSTOMER.ADDRESS_ID)" +
                        ".values(_mi_inRecord.getAddressId() != null ? DSL.val(_mi_inRecord.getAddressId()) : DSL.defaultValue(CUSTOMER.ADDRESS_ID))"
        );
    }

    @Test
    @DisplayName("With nullable node ID field")
    void nullableNodeId() {
        assertGeneratedContentContains("nullableNodeId",
                "insertInto(VACATION_DESTINATION, VACATION_DESTINATION.COUNTRY_NAME, VACATION_DESTINATION.DESTINATION_ID)",
                """
                    .values(
                        _mi_inRecord.getId() != null ? DSL.val(_mi_inRecord.getCountryName()) : DSL.defaultValue(VACATION_DESTINATION.COUNTRY_NAME),
                        _mi_inRecord.getId() != null ? DSL.val(_mi_inRecord.getDestinationId()) : DSL.defaultValue(VACATION_DESTINATION.DESTINATION_ID)
                    )
                """
        );
    }

    @Test
    @DisplayName("Default case with listed input")
    void listedInput() {
        assertGeneratedContentContains("listedInput",
                ".insertInto(CUSTOMER, CUSTOMER.CUSTOMER_ID)",
                """
                    .valuesOfRows(_mi_inRecordList.stream().map(_iv_it -> {
                                return DSL.row(
                                    DSL.val(_iv_it.getCustomerId())
                                );
                            }).toList()
                    )
                """
        );
    }

    @Test
    @DisplayName("Default case with listed input with nullable field")
    void listedInputWithNullableField() {
        assertGeneratedContentContains("listedInputWithNullableField",
                ".insertInto(CUSTOMER, CUSTOMER.CUSTOMER_ID)",
                """
                    .valuesOfRows(_mi_inRecordList.stream().map(_iv_it -> {
                                return DSL.row(
                                    _iv_it.getCustomerId() != null ? DSL.val(_iv_it.getCustomerId()) : DSL.defaultValue(CUSTOMER.CUSTOMER_ID)
                                );
                            }).toList()
                    )
                """
        );
    }

    @Test
    @DisplayName("Default case with listed input with node ID field")
    void listedInputWithNodeIdField() {
        assertGeneratedContentContains("listedInputWithNodeIdField",
                ".insertInto(VACATION_DESTINATION, VACATION_DESTINATION.DESTINATION_ID, VACATION_DESTINATION.COUNTRY_NAME)",
                """
                    .valuesOfRows(_mi_inRecordList.stream().map(_iv_it -> {
                                return DSL.row(
                                    DSL.val(_iv_it.getDestinationId()),
                                    DSL.val(_iv_it.getCountryName())
                                );
                            }).toList()
                    )
                """
        );
    }
}
