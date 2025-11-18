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
                "var _iv_id = _iv_nodeIdStrategy.unpackIdValues(\"CustomerNode\", in.getId(), CUSTOMER.fields(",
                "insertInto(CUSTOMER, CUSTOMER.CUSTOMER_ID)" +
                        ".values(DSL.val(_iv_nodeIdStrategy.getFieldValue(CUSTOMER.CUSTOMER_ID, _iv_id[0])))"
        );
    }

    @Test
    @DisplayName("With custom node ID field")
    void customNodeId() {
        assertGeneratedContentContains("customNodeId",
                "_iv_id = _iv_nodeIdStrategy.unpackIdValues(\"C\", in.getId(), CUSTOMER.CUSTOMER_ID)",
                "insertInto(CUSTOMER, CUSTOMER.CUSTOMER_ID)" +
                        ".values(DSL.val(_iv_nodeIdStrategy.getFieldValue(CUSTOMER.CUSTOMER_ID, _iv_id[0])))"
        );
    }

    @Test
    @DisplayName("With default node ID field with multiple key fields")
    void nodeIdWithMultipleFields() {
        assertGeneratedContentContains("nodeIdWithMultipleFields",
                "_iv_nodeIdStrategy.unpackIdValues(\"VacationDestination\", in.getId(), VACATION_DESTINATION.fields",
                "insertInto(VACATION_DESTINATION, VACATION_DESTINATION.DESTINATION_ID, VACATION_DESTINATION.COUNTRY_NAME)",
                """
                    .values(
                        DSL.val(_iv_nodeIdStrategy.getFieldValue(VACATION_DESTINATION.DESTINATION_ID, _iv_id[0])),
                        DSL.val(_iv_nodeIdStrategy.getFieldValue(VACATION_DESTINATION.COUNTRY_NAME, _iv_id[1]))
                     )
                """
        );
    }

    @Test
    @DisplayName("With custom node ID field with multiple key fields")
    void customNodeIdWithMultipleFields() {
        assertGeneratedContentContains("customNodeIdWithMultipleFields",
                "_iv_nodeIdStrategy.unpackIdValues(\"V\", in.getId(), VACATION_DESTINATION.COUNTRY_NAME, VACATION_DESTINATION.DESTINATION_ID)",
                "insertInto(VACATION_DESTINATION, VACATION_DESTINATION.COUNTRY_NAME, VACATION_DESTINATION.DESTINATION_ID)",
                """
                    .values(
                        DSL.val(_iv_nodeIdStrategy.getFieldValue(VACATION_DESTINATION.COUNTRY_NAME, _iv_id[0])),
                        DSL.val(_iv_nodeIdStrategy.getFieldValue(VACATION_DESTINATION.DESTINATION_ID, _iv_id[1]))
                    )
                """
        );
    }

    @Test
    @DisplayName("With default, implicit reference node ID field")
    void referenceNodeId() {
        assertGeneratedContentContains("referenceNodeId",
                "_iv_addressId = _iv_nodeIdStrategy.unpackIdValues(\"Address\", in.getAddressId(), ADDRESS.fields(",
                "insertInto(CUSTOMER, CUSTOMER.ADDRESS_ID)" +
                        ".values(DSL.val(_iv_nodeIdStrategy.getFieldValue(CUSTOMER.ADDRESS_ID, _iv_addressId[0])))"
        );
    }

    @Test
    @DisplayName("With custom reference node ID field")
    void customReferenceNodeId() {
        assertGeneratedContentContains("customReferenceNodeId",
                "_iv_addressId = _iv_nodeIdStrategy.unpackIdValues(\"A\", in.getAddressId(), ADDRESS.ADDRESS_ID)",
                "insertInto(CUSTOMER, CUSTOMER.ADDRESS_ID)" +
                        ".values(DSL.val(_iv_nodeIdStrategy.getFieldValue(CUSTOMER.ADDRESS_ID, _iv_addressId[0])))"
        );
    }

    @Test
    @DisplayName("Map node ID reference to correct column names")
    void referenceNodeIdWithColumnNameMismatch() {
        assertGeneratedContentContains("referenceNodeIdWithColumnNameMismatch",
                "insertInto(FILM, FILM.ORIGINAL_LANGUAGE_ID)",
                "unpackIdValues(\"L\", in.getOriginalLanguageId(), LANGUAGE.LANGUAGE_ID)",
                ".values(in.getOriginalLanguageId() != null ? DSL.val(_iv_nodeIdStrategy.getFieldValue(FILM.ORIGINAL_LANGUAGE_ID, _iv_originalLanguageId[0])) : DSL.defaultValue(FILM.ORIGINAL_LANGUAGE_ID))"
        );
    }

    @Test
    @DisplayName("With multiple fields including node ID")
    void multipleFields() {
        assertGeneratedContentContains("multipleFields",
                "insertInto(CUSTOMER, CUSTOMER.ADDRESS_ID, CUSTOMER.CUSTOMER_ID, CUSTOMER.FIRST_NAME, CUSTOMER.LAST_NAME)",
                """
                    .values(
                        DSL.val(in.getAddressId()),
                        DSL.val(_iv_nodeIdStrategy.getFieldValue(CUSTOMER.CUSTOMER_ID, _iv_id[0])),
                        DSL.val(in.getFirstName()),
                        DSL.val(in.getLastName())
                    )
                """
        );
    }

    @Test
    @DisplayName("With nullable field")
    void nullableField() {
        assertGeneratedContentContains("nullableField",
                "insertInto(CUSTOMER, CUSTOMER.ADDRESS_ID)" +
                        ".values(in.getAddressId() != null ? DSL.val(in.getAddressId()) : DSL.defaultValue(CUSTOMER.ADDRESS_ID))"
        );
    }

    @Test
    @DisplayName("With nullable node ID field")
    void nullableNodeId() {
        assertGeneratedContentContains("nullableNodeId",
                "_iv_id = in.getId() != null ? _iv_nodeIdStrategy.unpackIdValues(\"V_D\", in.getId(), VACATION_DESTINATION.COUNTRY_NAME, VACATION_DESTINATION.DESTINATION_ID) : null;",
                "insertInto(VACATION_DESTINATION, VACATION_DESTINATION.COUNTRY_NAME, VACATION_DESTINATION.DESTINATION_ID)",
                """
                    .values(
                        in.getId() != null ? DSL.val(_iv_nodeIdStrategy.getFieldValue(VACATION_DESTINATION.COUNTRY_NAME, _iv_id[0])) : DSL.defaultValue(VACATION_DESTINATION.COUNTRY_NAME),
                        in.getId() != null ? DSL.val(_iv_nodeIdStrategy.getFieldValue(VACATION_DESTINATION.DESTINATION_ID, _iv_id[1])) : DSL.defaultValue(VACATION_DESTINATION.DESTINATION_ID)
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
                    .valuesOfRows(in.stream().map(_iv_it -> {
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
                    .valuesOfRows(in.stream().map(_iv_it -> {
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
                "{ return", // Make sure helper variables for node IDs is not declared outside query
                ".insertInto(VACATION_DESTINATION, VACATION_DESTINATION.DESTINATION_ID, VACATION_DESTINATION.COUNTRY_NAME)",
                """
                    .valuesOfRows(in.stream().map(_iv_it -> {
                                var _iv_id = _iv_nodeIdStrategy.unpackIdValues("V_D", _iv_it.getId(), VACATION_DESTINATION.DESTINATION_ID, VACATION_DESTINATION.COUNTRY_NAME);
                                return DSL.row(
                                    DSL.val(_iv_nodeIdStrategy.getFieldValue(VACATION_DESTINATION.DESTINATION_ID, _iv_id[0])),
                                    DSL.val(_iv_nodeIdStrategy.getFieldValue(VACATION_DESTINATION.COUNTRY_NAME, _iv_id[1]))
                                );
                            }).toList()
                    )
                """
        );
    }
}
