package no.fellesstudentsystem.graphitron_newtestorder.resolvers.fetch;

import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.fetch.FetchResolverClassGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent.*;

@DisplayName("Fetch resolvers - Resolvers for queries")
public class ResolverTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "resolvers/fetch/standard";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(DUMMY_TYPE);
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new FetchResolverClassGenerator(schema));
    }

    @Test
    @DisplayName("Basic root resolver with no parameters")
    void defaultCase() {
        assertGeneratedContentMatches("operation/default");
    }

    @Test
    @DisplayName("Root resolvers with various input data types")
    void inputDatatypes() {
        assertGeneratedContentMatches("operation/inputDatatypes", DUMMY_INPUT);
    }

    @Test
    @DisplayName("Root resolver that returns a list")
    void returningList() {
        assertGeneratedContentMatches("operation/returningList");
    }

    @Test
    @DisplayName("Root resolver that is not generated")
    void notGenerated() {
        assertGeneratedContentMatches("operation/notGenerated");
    }

    @Test
    @DisplayName("Root resolver that is generated as abstract")
    void notGeneratedAbstractResolver() {
        assertGeneratedContentMatches("operation/notGeneratedAbstractResolver");
    }

    @Test
    @DisplayName("Basic resolver with no parameters")
    void splitQuery() {
        assertGeneratedContentMatches("splitquery/default", SPLIT_QUERY_WRAPPER);
    }

    @Test
    @DisplayName("Resolvers with various input data types")
    void splitQueryInputDatatypes() {
        assertGeneratedContentMatches("splitquery/inputDatatypes", DUMMY_INPUT, SPLIT_QUERY_WRAPPER);
    }

    @Test
    @DisplayName("Resolver that returns a list")
    void splitQueryReturningList() {
        assertGeneratedContentMatches("splitquery/returningList", SPLIT_QUERY_WRAPPER);
    }

    @Test
    @DisplayName("Resolver that is not generated")
    void splitQueryNotGenerated() {
        assertGeneratedContentMatches("splitquery/notGenerated", SPLIT_QUERY_WRAPPER);
    }

    @Test
    @DisplayName("Resolver that is generated as abstract")
    void splitQueryNotGeneratedAbstractResolver() {
        assertGeneratedContentMatches("splitquery/notGeneratedAbstractResolver", SPLIT_QUERY_WRAPPER);
    }
}
