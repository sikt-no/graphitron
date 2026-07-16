package no.sikt.graphitron.rewrite.test.querydb;

import no.sikt.graphitron.generated.Graphitron;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;

/**
 * Gate guard: the runtime {@code OneOfDirectiveSdl} helper is emitted only on the federation
 * arm. {@code GraphQLRewriteGenerator} gates its emission on {@code federationLink && usesOneOf};
 * the federation-only runtime/file assertions in {@link FederationBuildSmokeTest} and
 * {@code SchemaSdlEmitterTest} would still pass if the {@code federationLink} conjunct were
 * dropped, so this test pins it: a non-federation schema that uses {@code @oneOf} must emit
 * <em>no</em> {@code <outputPackage>.util.OneOfDirectiveSdl} (the dead-helper case the gate
 * prevents), while its on-disk plain SDL still carries the {@code @oneOf} definition because
 * graphql-java's {@code SchemaPrinter} prints spec-built-in directive definitions.
 *
 * <p>Both fixtures carry a {@code @oneOf} input ({@code FilmOneOfFilter} on
 * {@code federated-schema.graphqls} and on the shared {@code schema.graphqls}); the federated arm
 * emits the helper, the shared arm does not.
 */
@PipelineTier
class OneOfDirectiveGateTest {

    private static final String FEDERATED_HELPER = "no.sikt.graphitron.generated.federated.util.OneOfDirectiveSdl";
    private static final String NON_FEDERATED_HELPER = "no.sikt.graphitron.generated.util.OneOfDirectiveSdl";

    @Test
    void federationOneOfSchemaEmitsRuntimeHelper() {
        assertThatCode(() -> Class.forName(FEDERATED_HELPER))
            .as("a federation schema using @oneOf must emit the runtime _Service.sdl helper")
            .doesNotThrowAnyException();
    }

    @Test
    void nonFederationOneOfSchemaEmitsNoRuntimeHelper() {
        assertThatThrownBy(() -> Class.forName(NON_FEDERATED_HELPER))
            .as("a non-federation schema using @oneOf must not emit the dead runtime helper "
                + "(the federationLink conjunct on the emission gate)")
            .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void nonFederationPlainSdlCarriesOneOfDefinition() throws Exception {
        String sdl;
        try (var in = Graphitron.class.getResourceAsStream("schema.graphqls")) {
            assertThat(in).as("emitted non-federation schema.graphqls on classpath").isNotNull();
            sdl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(sdl)
            .as("the on-disk plain SDL carries the @oneOf application")
            .containsPattern("input\\s+FilmOneOfFilter\\b[^{]*@oneOf")
            .as("graphql-java's SchemaPrinter emits the @oneOf definition on the plain arm")
            .contains("directive @oneOf on INPUT_OBJECT");
    }
}
