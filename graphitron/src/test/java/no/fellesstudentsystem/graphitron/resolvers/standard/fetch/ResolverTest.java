package no.fellesstudentsystem.graphitron.resolvers.standard.fetch;

import no.fellesstudentsystem.graphitron.common.GeneratorTest;
import no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.fetch.FetchResolverClassGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent.*;

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
        assertGeneratedContentMatches("operation/inputDatatypes", DUMMY_INPUT, DUMMY_ENUM);
    }

    @Test
    @DisplayName("Root resolver that returns a list")
    void returningList() {
        assertGeneratedContentContains("operation/returningList", "public CompletableFuture<List<DummyType>> query(");
    }

    @Test
    @DisplayName("Root resolver that returns an optional list")
    void returningOptionalList() {
        assertGeneratedContentContains("operation/returningOptionalList", "public CompletableFuture<List<DummyType>> query(");
    }

    @Test
    @DisplayName("Root resolver that is not generated")
    void notGenerated() {
        assertGeneratedContentMatches("operation/notGenerated");
    }

    @Test
    @DisplayName("Root resolver that is generated as abstract")
    void notGeneratedAbstractResolver() {
        assertGeneratedContentContains("operation/notGeneratedAbstractResolver", "abstract class");
    }

    @Test
    @DisplayName("Basic resolver with no parameters")
    void splitQuery() {
        assertGeneratedContentMatches("splitquery/default", SPLIT_QUERY_WRAPPER);
    }

    @Test
    @DisplayName("Resolvers with various input data types")
    void splitQueryInputDatatypes() {
        assertGeneratedContentMatches("splitquery/inputDatatypes", DUMMY_INPUT, DUMMY_ENUM, SPLIT_QUERY_WRAPPER);
    }

    @Test
    @DisplayName("Resolver that returns a list")
    void splitQueryReturningList() {
        assertGeneratedContentContains("splitquery/returningList", Set.of(SPLIT_QUERY_WRAPPER), "public CompletableFuture<List<DummyType>> query(");
    }

    @Test
    @DisplayName("Resolver that returns an optional list")
    void splitQueryReturningOptionalList() {
        assertGeneratedContentContains("splitquery/returningOptionalList", Set.of(SPLIT_QUERY_WRAPPER), "public CompletableFuture<List<DummyType>> query(");
    }

    @Test
    @DisplayName("Resolver created from a type that inherits the table from another type")
    void splitQueryFromTypeWithoutTable() {
        assertGeneratedContentMatches("splitquery/fromTypeWithoutTable", CUSTOMER_TABLE);
    }

    @Test
    @DisplayName("Resolver that is not generated")
    void splitQueryNotGenerated() {
        assertGeneratedContentMatches("splitquery/notGenerated", SPLIT_QUERY_WRAPPER);
    }

    @Test
    @DisplayName("Resolver that is generated as abstract")
    void splitQueryNotGeneratedAbstractResolver() {
        assertGeneratedContentContains("splitquery/notGeneratedAbstractResolver", Set.of(SPLIT_QUERY_WRAPPER), "abstract class");
    }
}
