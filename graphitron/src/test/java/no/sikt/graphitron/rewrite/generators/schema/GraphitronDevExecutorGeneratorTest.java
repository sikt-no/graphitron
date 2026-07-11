package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.ContextArgumentClassifier;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.ResolvedContextArg;
import no.sikt.graphitron.rewrite.session.SessionStateConfig;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tier tests for {@link GraphitronDevExecutorGenerator}: the JDK-only {@code execute}
 * signature (the reflection boundary the dev-loop host pins), the ROLLBACK_ONLY runtime wiring,
 * the sessionState-driven fail-loud arm, the alphabetical contextArgument binding, and the
 * federation emission gate.
 *
 * <p>Schemas with contextArguments are hand-built through the canonical record constructor (a
 * {@link ContextArgumentClassifier.Classification} injected directly) so the binding shape is
 * testable without the classifier; the classifier-driven path is covered by the sibling
 * {@code GraphitronDevExecutorGeneratorPipelineTest}.
 */
@UnitTier
class GraphitronDevExecutorGeneratorTest {

    private static GraphitronSchema emptySchema() {
        return new GraphitronSchema(Map.of(), Map.of());
    }

    private static GraphitronSchema schemaWithContextArgs(ResolvedContextArg... args) {
        var resolved = new LinkedHashMap<String, ResolvedContextArg>();
        for (var arg : args) {
            resolved.put(arg.name(), arg);
        }
        return new GraphitronSchema(Map.of(), Map.of(), Map.of(), Map.of(), List.of(),
            new ContextArgumentClassifier.Classification(resolved, List.of()), List.of());
    }

    private static TypeSpec generate(GraphitronSchema schema, SessionStateConfig sessionState) {
        return GraphitronDevExecutorGenerator.generate(schema, "com.example", sessionState, false).get(0);
    }

    private static MethodSpec executeMethod(TypeSpec spec) {
        return spec.methodSpecs().stream()
            .filter(m -> m.name().equals("execute"))
            .findFirst()
            .orElseThrow();
    }

    @Test
    void generate_returnsExactlyOneClassNamedGraphitronDevExecutor() {
        var result = GraphitronDevExecutorGenerator.generate(
            emptySchema(), "com.example", SessionStateConfig.none(), false);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("GraphitronDevExecutor");
        assertThat(result.get(0).modifiers()).contains(Modifier.PUBLIC, Modifier.FINAL);
    }

    @Test
    void federationSchema_emitsNothing() {
        // V0 targets the non-federation path: a federation subgraph builds through the two-arg
        // buildSchema and needs an entity fetcher, so its executor is a follow-on variant.
        var result = GraphitronDevExecutorGenerator.generate(
            emptySchema(), "com.example", SessionStateConfig.none(), true);
        assertThat(result).isEmpty();
    }

    @Test
    void execute_hasTheJdkOnlyReflectionBoundarySignature() {
        // The load-bearing R428 property: one public static method, parameter and return types
        // all JDK, so the host reflects it without sharing jOOQ or graphql-java types.
        var execute = executeMethod(generate(emptySchema(), SessionStateConfig.none()));
        assertThat(execute.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC);
        assertThat(execute.returnType().toString()).isEqualTo("java.lang.String");
        assertThat(execute.parameters()).extracting(p -> p.name())
            .containsExactly("connection", "dialect", "query", "variables", "claims", "contextArgs");
        assertThat(execute.parameters()).extracting(p -> p.type().toString())
            .containsExactly(
                "java.sql.Connection",
                "java.lang.String",
                "java.lang.String",
                "java.util.Map<java.lang.String, java.lang.Object>",
                "java.lang.String",
                "java.util.Map<java.lang.String, java.lang.Object>");
    }

    @Test
    void execute_wiresTheRuntimeEngineWithRollbackOnlyCommitPolicy() {
        var body = executeMethod(generate(emptySchema(), SessionStateConfig.none())).code().toString();
        assertThat(body)
            .contains("new com.example.schema.GraphitronRuntime(new SingleConnectionDataSource(connection), org.jooq.SQLDialect.valueOf(dialect))")
            .contains("graphql.GraphQL.newGraphQL(com.example.Graphitron.buildSchema(builder -> {}))")
            .contains("new com.example.schema.GraphitronConnectionInstrumentation(runtime, com.example.schema.GraphitronTransactionProvider.CommitPolicy.ROLLBACK_ONLY)");
    }

    @Test
    void execute_buildsOwnedInputAndReturnsToSpecificationJson() {
        var body = executeMethod(generate(emptySchema(), SessionStateConfig.none())).code().toString();
        assertThat(body)
            .contains("com.example.Graphitron.newOwnedExecutionInput(claimsPayload)")
            .contains(".query(query)")
            .contains(".variables(variables == null ? java.util.Map.of() : variables)")
            .contains("engine.execute(input)")
            .contains("return org.jooq.tools.json.JSONValue.toJSONString(result.toSpecification())");
    }

    @Test
    void noSessionState_normalizesNullClaimsInsteadOfFailing() {
        var body = executeMethod(generate(emptySchema(), SessionStateConfig.none())).code().toString();
        assertThat(body)
            .contains("claims == null ? \"\" : claims")
            .doesNotContain("IllegalStateException");
    }

    @Test
    void sessionStateConfigured_failsLoudOnMissingClaims() {
        // Fail loud, never skip: running without the connect hook would execute under a different
        // security posture than production. The message points at the config knob.
        var sessionState = new SessionStateConfig.Variables(
            List.of(new SessionStateConfig.Variable("app.user_id", "sub")));
        var body = executeMethod(generate(emptySchema(), sessionState)).code().toString();
        assertThat(body)
            .contains("if (claims == null || claims.isBlank())")
            .contains("IllegalStateException")
            .contains("GRAPHITRON_DEV_CLAIMS");
    }

    @Test
    void contextArguments_bindAlphabeticallyIntoTheOwnedFactoryCall() {
        // Same resolved() iteration order as the facade's factory parameters (fnr before userId),
        // so position in this call always matches the factory's parameter list.
        var schema = schemaWithContextArgs(
            new ResolvedContextArg("fnr", ClassName.get(Long.class), List.of()),
            new ResolvedContextArg("userId", ClassName.get(String.class), List.of()));
        var body = executeMethod(generate(schema, SessionStateConfig.none())).code().toString();
        String fnrBinding = "(java.lang.Long) requiredContextArg(contextArgs, \"fnr\", java.lang.Long.class)";
        String userIdBinding = "(java.lang.String) requiredContextArg(contextArgs, \"userId\", java.lang.String.class)";
        assertThat(body)
            .contains("com.example.Graphitron.newOwnedExecutionInput(claimsPayload,")
            .contains(fnrBinding)
            .contains(userIdBinding);
        assertThat(body.indexOf(fnrBinding)).isLessThan(body.indexOf(userIdBinding));
    }

    @Test
    void contextArgHelper_emittedOnlyWhenTheSchemaDeclaresContextArguments() {
        var without = generate(emptySchema(), SessionStateConfig.none());
        assertThat(without.methodSpecs()).extracting(m -> m.name())
            .doesNotContain("requiredContextArg");

        var with = generate(
            schemaWithContextArgs(new ResolvedContextArg("userId", ClassName.get(String.class), List.of())),
            SessionStateConfig.none());
        assertThat(with.methodSpecs()).extracting(m -> m.name())
            .contains("requiredContextArg");
    }

    @Test
    void nestedSingleConnectionDataSource_wrapsTheHostConnection() {
        var spec = generate(emptySchema(), SessionStateConfig.none());
        var wrapper = spec.typeSpecs().stream()
            .filter(t -> t.name().equals("SingleConnectionDataSource"))
            .findFirst()
            .orElseThrow();
        assertThat(wrapper.modifiers()).contains(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);
        assertThat(wrapper.superinterfaces()).extracting(Object::toString)
            .containsExactly("javax.sql.DataSource");
    }
}
