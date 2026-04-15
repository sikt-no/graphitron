package no.sikt.graphitron.datafetchers.standard.edit;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.datafetchers.operations.OperationClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_INPUT_TABLE;

public class UpsertAsStoreResolverTest extends GeneratorTest {

    @Override
    protected String getSubpath() {
        return "datafetchers/edit/standard/withBatching";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(CUSTOMER_INPUT_TABLE);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new OperationClassGenerator(schema));
    }

    @BeforeEach
    void setUp() {
        GeneratorConfig.setGenerateUpsertAsStore(true);
    }

    @AfterEach
    void tearDown() {
        GeneratorConfig.setGenerateUpsertAsStore(false);
    }

    @Test
    @DisplayName("Upsert as store")
    void withUpsertAsStore() {
        assertGeneratedContentContains("upsertAsStore",
                """
                    return _iv_env -> {
                        CustomerInputTable _mi_in = ResolverHelpers.transformDTO(_iv_env.getArgument("in"), CustomerInputTable.class);
                        var _iv_transform = new RecordTransformer(_iv_env);
                        var _mi_inRecordForFetchRecords = _iv_transform.customerInputTableToJOOQRecord(_mi_in, _iv_transform.getArgumentPresence().child("in"), "in");

                        var _iv_existingRecords = MutationDBQueries.fetchCustomerRecords(_iv_transform.getCtx(), List.of(_mi_inRecordForFetchRecords));

                        var _mi_inRecord = ResolverHelpers.prepareRecordsForStore(_iv_existingRecords, _mi_inRecordForFetchRecords);

                        MutationDBQueries.mutationForMutation(_iv_transform.getCtx(), _mi_inRecord);
                        return new DataFetcherHelper(_iv_env).load((_iv_ctx, _iv_selectionSet) -> MutationDBQueries.mutationForMutation(_iv_ctx, _mi_inRecord, _iv_selectionSet));
                    };
                """
        );
    }

    @Test
    @DisplayName("Upsert as store with listed input")
    void withUpsertAsStoreListed() {
        assertGeneratedContentContains("upsertAsStoreListed",
                """
                    return _iv_env -> {
                        List<CustomerInputTable> _mi_in = ResolverHelpers.transformDTOList(_iv_env.getArgument("in"), CustomerInputTable.class);
                        var _iv_transform = new RecordTransformer(_iv_env);
                        var _mi_inRecordListForFetchRecords = _iv_transform.customerInputTableToJOOQRecord(_mi_in, _iv_transform.getArgumentPresence().child("in"), "in");

                        var _iv_existingRecords = MutationDBQueries.fetchCustomerRecords(_iv_transform.getCtx(), _mi_inRecordListForFetchRecords);

                        var _mi_inRecordList = ResolverHelpers.prepareRecordsForStore(_iv_existingRecords, _mi_inRecordListForFetchRecords);

                        MutationDBQueries.mutationForMutation(_iv_transform.getCtx(), _mi_inRecordList);
                        return new DataFetcherHelper(_iv_env).load((_iv_ctx, _iv_selectionSet) -> MutationDBQueries.mutationForMutation(_iv_ctx, _mi_inRecordList, _iv_selectionSet));
                    };
                """
        );
    }
}
