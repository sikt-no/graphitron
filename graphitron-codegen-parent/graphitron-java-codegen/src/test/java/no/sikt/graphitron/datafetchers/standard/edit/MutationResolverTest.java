package no.sikt.graphitron.datafetchers.standard.edit;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.configuration.RecordValidation;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.datafetchers.operations.OperationClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_INPUT_TABLE;

@DisplayName("Mutation resolvers - Resolvers for delete and insert mutations (will be expanded to include all generated mutations)")
public class MutationResolverTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "datafetchers/edit/standard/withReturning";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(CUSTOMER_INPUT_TABLE);
    }

    @BeforeAll
    static void setUp() {
        GeneratorConfig.setUseJdbcBatchingForAllMutations(false);
    }

    @AfterAll
    static void tearDown() {
        GeneratorConfig.setUseJdbcBatchingForAllMutations(true);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new OperationClassGenerator(schema));
    }

    @Test
    @DisplayName("Default case for delete")
    void delete() {
        assertGeneratedContentMatches("delete");
    }

    @Test
    @DisplayName("Default case for insert")
    void insert() {
        assertGeneratedContentContains("insert",
                """
                    CustomerInputTable _mi_in = ResolverHelpers.transformDTO(_iv_env.getArgument("in"), CustomerInputTable.class);
                    var _iv_transform = new RecordTransformer(_iv_env);
                    var _mi_inRecord = _iv_transform.customerInputTableToJOOQRecord(_mi_in, "in");
                    return new DataFetcherHelper(_iv_env).load(
                            (_iv_ctx, _iv_selectionSet) -> MutationDBQueries.mutationForMutation(_iv_ctx, _mi_inRecord, _iv_selectionSet)
                    );
                """
        );
    }

    @Test
    @DisplayName("Returning wrapped output for delete mutation")
    void wrappedOutputForDelete() {
        assertGeneratedContentContains("wrappedOutputForDelete",
                "loadWrapped(" +
                        "(_iv_ctx, _iv_selectionSet) -> MutationDBQueries.mutationForMutation(_iv_ctx, _mi_in, _iv_selectionSet)," +
                        "(_iv_result) -> new Wrapper(_iv_result));"
                );
    }

    @Test
    @DisplayName("Returning wrapped output for insert mutation")
    void wrappedOutputForInsert() {
        assertGeneratedContentContains("wrappedOutputForInsert",
                "loadWrapped(" +
                        "(_iv_ctx, _iv_selectionSet) -> MutationDBQueries.mutationForMutation(_iv_ctx, _mi_inRecord, _iv_selectionSet)," +
                        "(_iv_result) -> new Wrapper(_iv_result));"
        );
    }

    @Test
    @DisplayName("Map to jOOQ record for validation, but pass graph type to DB query")
    void validation() { // This test will be removed when delete mutations start using jOOQ record input
        GeneratorConfig.setRecordValidation(new RecordValidation(true, null));
        assertGeneratedContentContains("delete",
                "var _iv_transform = new RecordTransformer(_iv_env);",
                "inRecord = _iv_transform.customerInputTableToJOOQRecord(_mi_in, \"in\", \"in\");",
                "_iv_transform.validate(); return new DataFetcherHelper(_iv_env).load(",
                "mutationForMutation(_iv_ctx, _mi_in,"
        );
        GeneratorConfig.setRecordValidation(new RecordValidation(false, null));
    }
}
