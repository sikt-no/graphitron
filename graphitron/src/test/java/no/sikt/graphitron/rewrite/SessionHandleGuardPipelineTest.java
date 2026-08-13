package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator;
import no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SDL → classified schema → generated {@code TypeSpec} pipeline coverage for the {@code $session}
 * extraction: the one session-handle failure that is runtime rather than build-time. The build
 * cannot see which factory an operation was built with, so a {@code $session}-bound parameter's
 * read must route through the carrier's guarded accessor (which throws located, naming the field
 * coordinate, the sigil, and the owned entry points, when graphitron never mounted the
 * connection), never through a bare {@code configuration().data(...)} cast that would silently
 * bind null on an escape-hatch operation. One row per emission path: the root service fetcher
 * ({@code ServiceMethodCallEmitter}) and the batched-child rows method ({@code ArgCallEmitter}).
 * The accessor's own throw/return behaviour is driven compiled in
 * {@code TenantConnectionsGeneratorTest}.
 */
@PipelineTier
class SessionHandleGuardPipelineTest {

    @Test
    void sessionBoundServiceRoot_readsTheHandleThroughTheGuardedAccessor() {
        var schema = TestSchemaHelper.buildSchema("""
            type Query {
                sessionPrincipal: String @service(service: {
                    className: "no.sikt.graphitron.rewrite.TestServiceStub",
                    method: "principalOf",
                    argMapping: "identity: $session"
                })
            }
            """);

        var queryFetchers = TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals("QueryFetchers"))
            .findFirst()
            .orElseThrow();

        assertThat(TypeSpecAssertions.readsSessionHandleThroughGuard(queryFetchers, "Query.sessionPrincipal"))
            .as("the root service fetcher reads the handle through the guarded accessor, coordinate baked in")
            .isTrue();
    }

    @Test
    void sessionBoundServiceChild_rowsMethodReadsTheHandleThroughTheGuardedAccessor() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") {
                title: String
                mountedPrincipal: String @service(service: {
                    className: "no.sikt.graphitron.rewrite.TestServiceStub",
                    method: "principalOfBatch",
                    argMapping: "identity: $session"
                })
            }
            type Query { films: [Film!]! }
            """);

        var filmFetchers = TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals("FilmFetchers"))
            .findFirst()
            .orElseThrow();

        assertThat(TypeSpecAssertions.readsSessionHandleThroughGuard(filmFetchers, "Film.mountedPrincipal"))
            .as("the batched-child rows method reads the handle through the guarded accessor, coordinate baked in")
            .isTrue();
    }
}
