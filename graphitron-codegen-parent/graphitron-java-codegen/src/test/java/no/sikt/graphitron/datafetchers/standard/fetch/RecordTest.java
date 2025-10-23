package no.sikt.graphitron.datafetchers.standard.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.datafetchers.operations.OperationClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.DUMMY_SERVICE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("Fetch resolvers - Resolvers with input records")
public class RecordTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "datafetchers/fetch/standard";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(DUMMY_SERVICE);
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(DUMMY_TYPE);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new OperationClassGenerator(schema));
    }

    @Test
    @DisplayName("Input name for a Java record type starts with a capital letter")
    void wrongCapitalisationJavaRecord() {
        assertGeneratedContentContains(
                "operation/wrongCapitalisationJavaRecord",
                Set.of(DUMMY_INPUT_RECORD),
                "iN =", ".getArgument(\"IN\")", "iNRecord", "(iN, \"IN\")", ", iNRecord,"
        );
    }

    @Test
    @DisplayName("Input name for a jOOQ record starts with a capital letter")
    void wrongCapitalisationJOOQRecord() {
        assertGeneratedContentContains(
                "operation/wrongCapitalisationJOOQRecord",
                Set.of(CUSTOMER_INPUT_TABLE),
                "iN =", ".getArgument(\"IN\")", "iNRecord", "(iN, \"IN\")", ", iNRecord,"
        );
    }

    @Test
    @DisplayName("Root-level input Java records")
    void inputJavaRecord() {
        assertGeneratedContentContains(
                "operation/inputJavaRecord", Set.of(DUMMY_INPUT_RECORD),
                "in = ResolverHelpers.transformDTO(_iv_env.getArgument(\"in\"), DummyInputRecord.class)",
                "transform = new RecordTransformer(_iv_env)",
                "inRecord = _iv_transform.dummyInputRecordToJavaRecord(in, \"in\")",
                "queryForQuery(_iv_ctx, inRecord,"
        );
    }

    @Test
    @DisplayName("Listed input Java records")
    void listedInputJavaRecord() {
        assertGeneratedContentContains(
                "operation/listedInputJavaRecord", Set.of(DUMMY_INPUT_RECORD),
                "in = ResolverHelpers.transformDTOList(_iv_env.getArgument(\"in\"), DummyInputRecord.class)",
                "inRecordList = _iv_transform.dummyInputRecordToJavaRecord(in, \"in\")",
                "queryForQuery(_iv_ctx, inRecordList,"
        );
    }

    @Test
    @DisplayName("Root-level input jOOQ records")
    void inputJOOQRecord() {
        assertGeneratedContentContains(
                "operation/inputJOOQRecord", Set.of(CUSTOMER_INPUT_TABLE),
                "in = ResolverHelpers.transformDTO(_iv_env.getArgument(\"in\"), CustomerInputTable.class)",
                "transform = new RecordTransformer(_iv_env)",
                "inRecord = _iv_transform.customerInputTableToJOOQRecord(in, \"in\")",
                "queryForQuery(_iv_ctx, inRecord,"
        );
    }

    @Test
    @DisplayName("Listed input jOOQ records")
    void listedInputJOOQRecord() {
        assertGeneratedContentContains(
                "operation/listedInputJOOQRecord", Set.of(CUSTOMER_INPUT_TABLE),
                "in = ResolverHelpers.transformDTOList(_iv_env.getArgument(\"in\"), CustomerInputTable.class)",
                "inRecordList = _iv_transform.customerInputTableToJOOQRecord(in, \"in\")",
                "queryForQuery(_iv_ctx, inRecordList,"
        );
    }

    @Test
    @DisplayName("Input Java records")
    void splitQueryInputJavaRecord() {
        assertGeneratedContentContains(
                "splitquery/inputJavaRecord", Set.of(SPLIT_QUERY_WRAPPER, DUMMY_INPUT_RECORD),
                "queryForWrapper(_iv_ctx, _iv_keys, inRecord,"
        );
    }

    @Test
    @DisplayName("Input jOOQ records")
    void splitQueryInputJOOQRecord() {
        assertGeneratedContentContains(
                "splitquery/inputJOOQRecord", Set.of(SPLIT_QUERY_WRAPPER, CUSTOMER_INPUT_TABLE),
                "queryForWrapper(_iv_ctx, _iv_keys, inRecord,"
        );
    }

    @Test
    @DisplayName("JOOQ input record containing another input jOOQ record")
    void nestedInputRecord() {
        assertGeneratedContentContains(
                "operation/nestedInputRecord", Set.of(CUSTOMER_INPUT_TABLE),
                "in0Record = _iv_transform.",
                "in1Record = new CustomerRecord();in1Record.attach(_iv_transform.getCtx().configuration()",
                "if (in0 != null) {var in1 = in0.getIn1();in1Record = _iv_transform.customerInputTableToJOOQRecord(in1, \"in0/in1\")",
                ".queryForQuery(_iv_ctx, in0Record, in1Record,"
        );
    }
}
