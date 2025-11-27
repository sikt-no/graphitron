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
    @DisplayName("Default case with scalar field in jOOQ record input")
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }


    @Test
    @DisplayName("With node ID field in jOOQ record input")
    void nodeIdInput() {
        assertGeneratedContentContains("nodeIdInput",
                ".where(_iv_nodeIdStrategy.hasId(\"CustomerNode\", _mi_inRecord," +
                        "CUSTOMER.fields(CUSTOMER.getPrimaryKey().getFieldsArray())))"
        );
    }

    @Test
    @DisplayName("Non nullable input list")
    void listedInput() {
        assertGeneratedContentContains("listedInput",
                """
                .where(_mi_inRecordList.size() > 0 ?
                            DSL.row(CUSTOMER.CUSTOMER_ID).in(
                                IntStream.range(0, _mi_inRecordList.size()).mapToObj(_iv_it ->
                                    DSL.row(DSL.val(_mi_inRecordList.get(_iv_it).getCustomerId()))
                                ).toList()
                            ) : DSL.falseCondition()
                        )
                """
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
                ".and(_mi_inRecord.getFirstName() != null ? CUSTOMER.FIRST_NAME.eq(_mi_inRecord.getFirstName()) : DSL.noCondition())"
        );
    }

    @Test
    @DisplayName("Input list with node ID field")
    void inputListWithNodeId() {
        assertGeneratedContentContains("inputListWithNodeId",
                """
                        DSL.row(DSL.trueCondition()).in(
                            IntStream.range(0, _mi_inRecordList.size()).mapToObj(_iv_it ->
                                DSL.row(_iv_nodeIdStrategy.hasId("CustomerNode", _mi_inRecordList.get(_iv_it), CUSTOMER.fields(CUSTOMER.getPrimaryKey().getFieldsArray())))
                            ).toList()
                        )
                        """
        );
    }
}
