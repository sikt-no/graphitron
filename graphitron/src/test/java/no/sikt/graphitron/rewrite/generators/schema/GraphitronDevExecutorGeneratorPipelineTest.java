package no.sikt.graphitron.rewrite.generators.schema;

import com.palantir.javapoet.MethodSpec;
import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.emit.FillQuery;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.emit.SessionIdentityRows;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L4 pipeline tests for {@link GraphitronDevExecutorGenerator}: drive the generator against a real
 * classified schema whose calls the store states read a context key and pin that the
 * executor emits with the frozen JDK-only signature regardless of how many context keys the
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

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    /** The context keys the store states occurrence-condition's {@code @condition} calls read. */
    private static final class StoreKeys {
        static final List<FillQuery.ContextKey> CONDITION_ONLY = FACTS
            .read("scenarios/occurrence-condition", graph -> FillQuery.contextKeys(graph.handle()));
    }

    @Test
    void executeSignature_staysJdkOnlyWhenTheClassifierResolvesContextArguments() {
        // A @condition reading userId, as the store states it. The facade's factory grows a typed
        // parameter; the executor's signature must not (the host reflects one fixed shape).
        String sdl = """
            type Customer @table(name: "customer") { firstName: String @field(name: "first_name") }
            type Query {
                customersSeenByUser: [Customer!]! @condition(condition: {
                    className: "no.sikt.graphitron.rewrite.test.conditions.InputFieldConditionFixtures",
                    method: "customerSeenByUser"
                }, contextArguments: ["userId"])
            }
            """;
        var schema = TestSchemaHelper.buildSchema(sdl, TestConfiguration.testContext(), Map.of(), Map.of(),
            StoreKeys.CONDITION_ONLY);
        var result = GraphitronDevExecutorGenerator.generate(
            schema, "com.example", SessionIdentityRows.empty(), false);
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
                "java.util.function.Function<javax.sql.DataSource, java.lang.Object>",
                "java.util.Map<java.lang.String, java.lang.Object>");

        // The classifier-resolved binding lands in the body helper, not the signature: the helper
        // method is emitted exactly when contextArguments exist.
        assertThat(result.get(0).methodSpecs()).extracting(m -> m.name())
            .contains("requiredContextArg");
    }
}
