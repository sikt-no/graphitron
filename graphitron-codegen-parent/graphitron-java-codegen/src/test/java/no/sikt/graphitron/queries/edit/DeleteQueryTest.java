package no.sikt.graphitron.queries.edit;

import no.sikt.graphitron.configuration.GeneratorConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Mutation queries - Query and conditions for deleting data")
public class DeleteQueryTest extends MutationQueryTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "delete";
    }

    @BeforeAll
    static void setUp() {
        GeneratorConfig.setUseJdbcBatchingForDeletes(false);
    }

    @AfterAll
    static void tearDown() {
        GeneratorConfig.setUseJdbcBatchingForDeletes(true);
    }


    @Test
    @DisplayName("Default case with node ID in jOOQ record input")
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }


    @Test
    @DisplayName("Field in jOOQ record input")
    void nonNullableInput() {
        assertGeneratedContentContains("nonNullableInput",
                ".where(CUSTOMER.CUSTOMER_ID.eq(in.getCustomerId())"
        );
    }

    @Test
    @DisplayName("Nullable jOOQ record input")
    void nullableInput() {
        assertGeneratedContentContains("nullableInput",
                ".where(in != null ? CUSTOMER.CUSTOMER_ID.eq(in.getId()) : DSL.falseCondition())"
        );
    }

    @Test
    @DisplayName("Non nullable input list")
    void nonNullableListedInput() {
        assertGeneratedContentContains("nonNullableListedInput",
                """
                .where(in.size() > 0 ?
                            DSL.row(CUSTOMER.CUSTOMER_ID).in(
                                IntStream.range(0, in.size()).mapToObj(_iv_it ->
                                    DSL.row(DSL.val(in.get(_iv_it).getId()))
                                ).toList()
                            ) : DSL.falseCondition()
                        )
                """
        );
    }

    @Test
    @DisplayName("Nullable jOOQ record input list")
    void nullableListedInput() {
        assertGeneratedContentContains("nullableListedInput",
                ".where(in != null && in.size() > 0 ?",
                ".toList()) : DSL.falseCondition()"
        );
    }

    @Test
    @DisplayName("Nullable field in input")
    void nullableFieldInInput() {
        /*
        * Note that we validate that the input either has a non-nullable ID or non-nullable fields matching a PK/UK
        * Therefore, it's safe to fall back to DSL.noCondition on nullable input fields.
        * */
        assertGeneratedContentContains("nullableFieldInInput",
                ".and(in.getFirstName() != null ? CUSTOMER.FIRST_NAME.eq(in.getFirstName()) : DSL.noCondition())"
        );
    }

    @Test
    @DisplayName("Input list with node ID field")
    void inputListWithNodeId() {
        assertGeneratedContentContains("inputListWithNodeId",
                "DSL.row(_iv_nodeIdStrategy.hasId(\"CustomerNode\", in.get(_iv_it).getId(), CUSTOMER"
        );
    }

    @Test
    @DisplayName("Wrapped output without table")
    void wrappedOutput() {
        assertGeneratedContentContains("wrappedOutput",
                "deleteFrom(CUSTOMER)",
                ".where(_iv_nodeIdStrategy.hasId(\"CustomerNode\", in.getId(), CUSTOMER."
        );
    }

    @Test
    @DisplayName("Listed wrapped output")
    void wrappedOutputListed() {
        assertGeneratedContentContains("wrappedOutputListed",
                "deleteFrom(CUSTOMER)",
                ".where(in.size() > 0 ?",
                "DSL.row(_iv_nodeIdStrategy.hasId(\"CustomerNode\", in.get(_iv_it).getId(), CUSTOMER."
        );
    }

    @Test
    @DisplayName("With wrapped scalar as output")
    void wrappedScalar() {
        assertGeneratedContentContains("wrappedScalar",
                "deleteFrom(CUSTOMER)",
                ".where(_iv_nodeIdStrategy.hasId(\"CustomerNode\", in.getId(), CUSTOMER.fields"
        );
    }

    @Test
    @DisplayName("With wrapped, listed scalar as output")
    void wrappedScalarListed() {
        assertGeneratedContentContains("wrappedScalarListed",
                "deleteFrom(CUSTOMER)",
                ".where(in.size() > 0",
                "hasId(\"CustomerNode\", in.get(_iv_it).getId(), CUSTOMER"
        );
    }
}
