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
 * signature (the reflection boundary the dev-loop host pins), the federation emission gate, the
 * contextArgs-helper emission rule, and the nested single-connection {@code DataSource} shape.
 * Structural {@code MethodSpec}/{@code TypeSpec} assertions only; code-string assertions on
 * generated method bodies are banned at every tier (development-principles.adoc,
 * review-enforced), so body behaviour is pinned where it runs instead:
 *
 * <ul>
 *   <li>ROLLBACK_ONLY engine wiring, the observable-write/no-trace mutation, variables binding,
 *       the fail-loud missing-claims arm, and verbatim connect-hook errors:
 *       {@code DevExecuteExecutionTest} (execution tier, real Postgres).</li>
 *   <li>The deferred observe-then-discard transaction topology:
 *       {@code GraphitronTransactionProviderGeneratorTest} (compiled and driven).</li>
 *   <li>Alphabetical contextArgument binding: the executor's call into the facade's typed
 *       {@code newOwnedExecutionInput} is type-checked by the L5 compile gate over the
 *       sakila-example schemas (a mis-ordered binding with distinct types fails that compile),
 *       with the signature-stability half pinned by the sibling
 *       {@code GraphitronDevExecutorGeneratorPipelineTest}.</li>
 *   <li>The no-sessionState normalize arm (null claims accepted when no hook is configured):
 *       compiled by the same L5 gate via sakila-example's multischema variant, which configures
 *       no {@code <sessionState>}.</li>
 * </ul>
 *
 * <p>Schemas with contextArguments are hand-built through the canonical record constructor (a
 * {@link ContextArgumentClassifier.Classification} injected directly) so signature stability under
 * declared contextArguments is testable without the classifier.
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
    void executeSignature_isIdenticalRegardlessOfSessionStateAndContextArguments() {
        // The schema-varying facts (sessionState fail-loud arm, contextArgument binding) are
        // absorbed into the body; the host reflects one fixed shape. Compare the two extremes
        // structurally: same parameter names and types either way.
        var plain = executeMethod(generate(emptySchema(), SessionStateConfig.none()));
        var loaded = executeMethod(generate(
            schemaWithContextArgs(
                new ResolvedContextArg("fnr", ClassName.get(Long.class), List.of()),
                new ResolvedContextArg("userId", ClassName.get(String.class), List.of())),
            new SessionStateConfig.Variables(
                List.of(new SessionStateConfig.Variable("app.user_id", "sub")))));
        assertThat(loaded.parameters()).extracting(p -> p.name())
            .isEqualTo(plain.parameters().stream().map(p -> p.name()).toList());
        assertThat(loaded.parameters()).extracting(p -> p.type().toString())
            .isEqualTo(plain.parameters().stream().map(p -> p.type().toString()).toList());
        assertThat(loaded.returnType()).isEqualTo(plain.returnType());
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
