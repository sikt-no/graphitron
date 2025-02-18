package no.sikt.graphitron.resolvers.datafetchers.standard.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.resolvers.datafetchers.fetch.FetchClassGenerator;
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
        return "resolvers/datafetchers/fetch/standard";
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
        return List.of(new FetchClassGenerator(schema));
    }

    @Test
    @DisplayName("Root-level input Java records")
    void inputJavaRecord() {
        assertGeneratedContentContains(
                "operation/inputJavaRecord", Set.of(DUMMY_INPUT_RECORD),
                "in = ResolverHelpers.transformDTO(_args.get(\"in\"), DummyInputRecord.class)",
                "transform = new RecordTransformer(env)",
                "inRecord = transform.dummyInputRecordToJavaRecord(in, \"in\")",
                "queryForQuery(ctx, inRecord,"
        );
    }

    @Test
    @DisplayName("Listed input Java records")
    void listedInputJavaRecord() {
        assertGeneratedContentContains(
                "operation/listedInputJavaRecord", Set.of(DUMMY_INPUT_RECORD),
                "in = ResolverHelpers.transformDTOList(_args.get(\"in\"), DummyInputRecord.class)",
                "inRecordList = transform.dummyInputRecordToJavaRecord(in, \"in\")",
                "queryForQuery(ctx, inRecordList,"
        );
    }

    @Test
    @DisplayName("Root-level input jOOQ records")
    void inputJOOQRecord() {
        assertGeneratedContentContains(
                "operation/inputJOOQRecord", Set.of(CUSTOMER_INPUT_TABLE),
                "in = ResolverHelpers.transformDTO(_args.get(\"in\"), CustomerInputTable.class)",
                "transform = new RecordTransformer(env)",
                "inRecord = transform.customerInputTableToJOOQRecord(in, \"in\")",
                "queryForQuery(ctx, inRecord,"
        );
    }

    @Test
    @DisplayName("Listed input jOOQ records")
    void listedInputJOOQRecord() {
        assertGeneratedContentContains(
                "operation/listedInputJOOQRecord", Set.of(CUSTOMER_INPUT_TABLE),
                "in = ResolverHelpers.transformDTOList(_args.get(\"in\"), CustomerInputTable.class)",
                "inRecordList = transform.customerInputTableToJOOQRecord(in, \"in\")",
                "queryForQuery(ctx, inRecordList,"
        );
    }

    @Test
    @DisplayName("Input Java records")
    void splitQueryInputJavaRecord() {
        assertGeneratedContentContains(
                "splitquery/inputJavaRecord", Set.of(SPLIT_QUERY_WRAPPER, DUMMY_INPUT_RECORD),
                "queryForWrapper(ctx, ids, inRecord,"
        );
    }

    @Test
    @DisplayName("Input jOOQ records")
    void splitQueryInputJOOQRecord() {
        assertGeneratedContentContains(
                "splitquery/inputJOOQRecord", Set.of(SPLIT_QUERY_WRAPPER, CUSTOMER_INPUT_TABLE),
                "queryForWrapper(ctx, ids, inRecord,"
        );
    }

    @Test
    @DisplayName("JOOQ input record containing another input jOOQ record")
    void nestedInputRecord() {
        assertGeneratedContentContains(
                "operation/nestedInputRecord", Set.of(CUSTOMER_INPUT_TABLE),
                "in0Record = transform.",
                "in1Record = new CustomerRecord();in1Record.attach(transform.getCtx().configuration()",
                "if (in0 != null) {var in1 = in0.getIn1();in1Record = transform.customerInputTableToJOOQRecord(in1, \"in0/in1\")",
                ".queryForQuery(ctx, in0Record, in1Record,"
        );
    }
}
