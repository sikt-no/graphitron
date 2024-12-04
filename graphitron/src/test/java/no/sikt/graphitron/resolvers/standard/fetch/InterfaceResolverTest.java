package no.sikt.graphitron.resolvers.standard.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.resolvers.fetch.FetchResolverClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.NODE;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Interface resolvers - Resolvers for interfaces other than Node")
public class InterfaceResolverTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "resolvers/fetch/interfaces/standard";
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new FetchResolverClassGenerator(schema));
    }

    @Test
    @Disabled("Disablet inntil A51-371 er på plass")
    @DisplayName("No implementations")
    void noImplementations() {
        assertThatThrownBy(() -> generateFiles("noImplementations")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Field 'titled' returns interface 'Titled' which is not implemented by any types.");
    }

    @Test
    @Disabled("Disablet siden validering foreløpig stopper denne")
    @DisplayName("Returning list")
    void returningList() {
        assertGeneratedContentContains("returningList",
                "...");
    }

    @Test
    @Disabled("Disablet inntil A51-371 er på plass")
    @DisplayName("One implementation")
    void oneImplementation() {
        assertGeneratedContentMatches(
                "oneImplementation"
        );
    }

    @Test
    @Disabled("Disablet inntil A51-371 er på plass")
    @DisplayName("Many implementations")
    void manyImplementations() {
        assertGeneratedContentContains(
                "manyImplementations",
                "QueryDBQueries.titledForQuery("
        );
    }

    @Test
    @DisplayName("Type implements interface and Node")
    void doubleInterface() {
        assertGeneratedContentContains(
                "doubleInterface", Set.of(NODE),
                "FilmDBQueries.loadFilmByIdsAsNode(",
                "QueryDBQueries.titledForQuery("
        );
    }
}
