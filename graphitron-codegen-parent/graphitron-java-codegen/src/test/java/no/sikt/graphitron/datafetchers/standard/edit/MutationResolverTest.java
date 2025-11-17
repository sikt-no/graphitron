package no.sikt.graphitron.datafetchers.standard.edit;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.configuration.RecordValidation;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.datafetchers.operations.OperationClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_INPUT_TABLE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_TABLE;

@DisplayName("Mutation resolvers - Resolvers for delete mutations (will be expanded to include all generated resolvers)")
public class MutationResolverTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "datafetchers/edit/standard/delete";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(CUSTOMER_INPUT_TABLE);
    }

    @BeforeAll
    static void setUp() {
        GeneratorConfig.setUseJdbcBatchingForDeletes(false);
    }

    @AfterAll
    static void tearDown() {
        GeneratorConfig.setUseJdbcBatchingForDeletes(true);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new OperationClassGenerator(schema));
    }

    @Test
    @DisplayName("Single input")
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("Returning wrapped output")
    void wrappedOutput() {
        assertGeneratedContentContains("wrappedOutput",
                "loadWrapped(" +
                        "(_iv_ctx, _iv_selectionSet) -> MutationDBQueries.mutationForMutation(_iv_ctx, in, _iv_selectionSet)," +
                        "(_iv_result) -> new Wrapper(_iv_result));"
                );
    }

    @Test
    @DisplayName("Map to jOOQ record for validation, but pass graph type to DB query")
    void validation() {
        GeneratorConfig.setRecordValidation(new RecordValidation(true, null));
        assertGeneratedContentContains("default",
                "var _iv_transform = new RecordTransformer(_iv_env);",
                "inRecord = _iv_transform.customerInputTableToJOOQRecord(in, \"in\", \"in\");",
                "_iv_transform.validate(); return new DataFetcherHelper(_iv_env).load(",
                "mutationForMutation(_iv_ctx, in,"
        );
        GeneratorConfig.setRecordValidation(new RecordValidation(false, null));
    }
}
