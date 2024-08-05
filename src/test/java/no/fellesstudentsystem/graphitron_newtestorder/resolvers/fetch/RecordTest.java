package no.fellesstudentsystem.graphitron_newtestorder.resolvers.fetch;

import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalReference;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.fetch.FetchResolverClassGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent;
import no.fellesstudentsystem.graphitron_newtestorder.reducedgenerators.dummygenerators.DummyTransformerClassGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.fellesstudentsystem.graphitron_newtestorder.ReferencedEntry.DUMMY_SERVICE;
import static no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent.*;

@DisplayName("Fetch resolvers - Resolvers with input records")
public class RecordTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "resolvers/fetch/standard";
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
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new FetchResolverClassGenerator(schema), new DummyTransformerClassGenerator(schema));
    }

    @Test
    @DisplayName("Query root resolver with input Java records")
    void withInputJavaRecord() {
        assertGeneratedContentMatches("operation/withInputJavaRecord", DUMMY_INPUT_RECORD);
    }

    @Test
    @DisplayName("Query root resolver with input jOOQ records")
    void withInputJOOQRecord() {
        assertGeneratedContentMatches("operation/withInputJOOQRecord", CUSTOMER_INPUT_TABLE);
    }

    @Test
    @DisplayName("Query resolver with input Java records")
    void splitQueryWithInputJavaRecord() {
        assertGeneratedContentMatches("splitquery/withInputJavaRecord", SPLIT_QUERY_WRAPPER, DUMMY_INPUT_RECORD);
    }

    @Test
    @DisplayName("Query resolver with input jOOQ records")
    void splitQueryWithInputJOOQRecord() {
        assertGeneratedContentMatches("splitquery/withInputJOOQRecord", SPLIT_QUERY_WRAPPER, CUSTOMER_INPUT_TABLE);
    }
}
