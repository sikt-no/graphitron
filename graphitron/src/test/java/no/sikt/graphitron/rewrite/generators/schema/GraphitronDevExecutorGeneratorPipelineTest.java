package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.session.SessionStateConfig;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L4 pipeline tests for {@link GraphitronDevExecutorGenerator}: drive the generator against a real
 * classified schema with multiple {@code @service(contextArguments: [...])} sites and pin that the
 * executor emits with the frozen JDK-only signature regardless of how many contextArguments the
 * classifier resolved (the schema-varying binding is absorbed into the body, never the signature).
 *
 * <p>Sibling to the unit-tier {@code GraphitronDevExecutorGeneratorTest}, which hand-builds the
 * classification to pin the body's binding order and the sessionState fail-loud arm. Body-shape
 * behaviour beyond that is covered by the L5 compile gate (the executor compiles against the
 * facade's typed factory, so a wrong argument order with distinct types is a compile error);
 * body-string assertions are intentionally absent here per the no-code-string-assertion rule.
 */
@PipelineTier
class GraphitronDevExecutorGeneratorPipelineTest {

    @Test
    void executeSignature_staysJdkOnlyWhenTheClassifierResolvesContextArguments() {
        // Two @service sites with disjoint contextArguments (userId: String, fnr: Long), the same
        // corpus GraphitronFacadeGeneratorPipelineTest uses. The facade's factory grows typed
        // parameters; the executor's signature must not (the host reflects one fixed shape).
        String sdl = """
            type Film @table(name: "film") {
                ratingByUser: String @service(service: {
                    className: "no.sikt.graphitron.rewrite.TestServiceStub",
                    method: "getRatingByUser"
                }, contextArguments: ["userId"])
                ratingByFnr: String @service(service: {
                    className: "no.sikt.graphitron.rewrite.TestServiceStub",
                    method: "getRatingByFnr"
                }, contextArguments: ["fnr"])
            }
            type Query { film: Film }
            """;
        var schema = TestSchemaHelper.buildSchema(sdl);
        var result = GraphitronDevExecutorGenerator.generate(
            schema, "com.example", SessionStateConfig.none(), false);
        assertThat(result).hasSize(1);

        MethodSpec execute = result.get(0).methodSpecs().stream()
            .filter(m -> m.name().equals("execute"))
            .findFirst()
            .orElseThrow();
        assertThat(execute.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC);
        assertThat(execute.returnType().toString()).isEqualTo("java.lang.String");
        assertThat(execute.parameters()).extracting(p -> p.type().toString())
            .containsExactly(
                "java.sql.Connection",
                "java.lang.String",
                "java.lang.String",
                "java.util.Map<java.lang.String, java.lang.Object>",
                "java.lang.String",
                "java.util.Map<java.lang.String, java.lang.Object>");

        // The classifier-resolved binding lands in the body helper, not the signature: the helper
        // method is emitted exactly when contextArguments exist.
        assertThat(result.get(0).methodSpecs()).extracting(m -> m.name())
            .contains("requiredContextArg");
    }
}
