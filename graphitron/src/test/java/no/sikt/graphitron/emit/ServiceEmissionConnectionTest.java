package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.generators.util.NodeIdEncoderClassGenerator;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which connection {@link ServiceEmission#emit} lets a service's frame run on where the store states
 * none for it: the request's own in a graph whose connection key has no part, and nothing at all in a
 * graph with a tenant column, where a service with no stated connection is one the store refuses.
 * The emitter fails there rather than emit a fetcher on a connection nothing chose. Read over
 * {@code defects/unrouted-child}, whose child services no route reaches, with
 * {@code scenarios/tenant-routes}, {@code scenarios/voucher-accept-subgraph} and
 * {@code scenarios/field-key-row} as the graphs that emit.
 */
@PipelineTier
class ServiceEmissionConnectionTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static final String PACKAGE = "routed";

    private static ClassName generated(String simpleName) {
        return ClassName.get(PACKAGE, simpleName);
    }

    private static final ServiceEmission.Runtime RUNTIME = new ServiceEmission.Runtime(
        new FrameEmitter.Runtime(generated("ErrorRouter"), generated("Outcome"), generated("ErrorMappings"),
            type -> type.toUpperCase(), env -> CodeBlock.of("dsl($L)", env)),
        new FillEmitter.Runtime(
            key -> CodeBlock.of("env.getGraphQlContext().get($S)", key),
            coordinate -> CodeBlock.of("null"),
            (member, coordinate) -> CodeBlock.of("null"),
            new NodeIdDecodeEmitter.Runtime(
                ClassName.get(PACKAGE + ".util", NodeIdEncoderClassGenerator.CLASS_NAME), PACKAGE)),
        new ValidationEmitter.Runtime(generated("Outcome"), generated("ConstraintViolations"),
            env -> CodeBlock.of("validator($L)", env), generated("OwnedConnections")),
        generated("Fetchers"), generated("SelectionOccurrences"), type -> ClassName.get(PACKAGE + ".types", type),
        new FrameConnections.Routing(generated("OwnedConnections"), generated("ConnectionKey"),
            generated("ClientException"), generated("NodeIdEncoder")));

    private static Optional<ServiceEmission.Emitted> emit(String document, String typeName, String fieldName) {
        return FACTS.read(document, graph -> ServiceEmission.read(graph.handle())
            .emit(typeName + "." + fieldName, fieldName, RUNTIME));
    }

    @Test
    void aChildServiceNoRouteReachesUnderATenantColumnIsNotEmitted() {
        assertThatThrownBy(() -> emit("defects/unrouted-child", "Language", "films"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Language.films")
            .hasMessageContaining("no stored connection");
    }

    @Test
    void aTenantFanOutMarkerDoesNotStandInForAServicesConnection() {
        assertThatThrownBy(() -> emit("defects/unrouted-child", "Language", "servedFannedFilms"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Language.servedFannedFilms")
            .hasMessageContaining("no stored connection");
    }

    @Test
    void aServiceTheStoreStatesAConnectionForUnderATenantColumnIsEmitted() {
        assertThat(emit("scenarios/tenant-routes", "Language", "servedBy")).isPresent();
        assertThat(emit("scenarios/tenant-routes", "Film", "servedBy")).isPresent();
    }

    @Test
    void aRootServiceOfAGraphAcceptingVouchersIsEmittedThoughTheStoreStatesItNoConnection() {
        assertThat(emit("scenarios/voucher-accept-subgraph", "Query", "resultCount")).isPresent();
        assertThat(emit("scenarios/voucher-accept-subgraph", "VgsResultat", "servicedDetails")).isPresent();
    }

    @Test
    void aServiceOfAGraphWhoseConnectionKeyHasNoPartRunsOnTheRequestsOwnConnection() {
        assertThat(emit("scenarios/field-key-row", "Film", "titleUppercase")).isPresent();
    }
}
