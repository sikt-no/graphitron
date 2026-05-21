package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L4 pipeline tests for {@link GraphitronFacadeGenerator}: drive the generator against a real
 * classified schema with multiple {@code @service(contextArguments: [...])} sites and pin both
 * the alphabetical-sorted factory parameter list and the {@code graphQLContext} builder body.
 *
 * <p>Sibling to the unit-tier {@code GraphitronFacadeGeneratorTest}, which uses an empty schema
 * to pin the empty-contextArgs collapse to {@code newExecutionInput(DSLContext)}. This test
 * exercises the multi-arg shape end-to-end through the classifier — same code paths the
 * production generator runs.
 */
@PipelineTier
class GraphitronFacadeGeneratorPipelineTest {

    @Test
    void factory_reflectsContextArgumentsAlphabeticallyWithReflectedJavaTypes() {
        // Two @service sites with disjoint contextArguments (userId: String, fnr: Long). The
        // classifier walks both MethodRefs, collects them by name, and produces a sorted
        // (alphabetical) ResolvedContextArg map. The factory emitter pastes one parameter slot
        // per entry, in TreeMap iteration order.
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
        var spec = GraphitronFacadeGenerator.generate(schema, "com.example").get(0);

        MethodSpec newExecutionInput = spec.methodSpecs().stream()
            .filter(m -> m.name().equals("newExecutionInput"))
            .findFirst()
            .orElseThrow();

        // First parameter is always defaultDsl. Contextargs follow alphabetically (fnr before userId).
        assertThat(newExecutionInput.parameters()).extracting(p -> p.name())
            .containsExactly("defaultDsl", "fnr", "userId");
        assertThat(newExecutionInput.parameters()).extracting(p -> p.type().toString())
            .containsExactly("org.jooq.DSLContext", "java.lang.Long", "java.lang.String");
        assertThat(newExecutionInput.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC);
    }

    @Test
    void factory_bodyNullChecksEveryParameterAndPopulatesGraphQLContext() {
        // The body shape: requireNonNull on every parameter, then a graphQLContext builder lambda
        // that puts DSLContext.class, each contextArg under its string name, and the singleton
        // GraphitronContextImpl under GraphitronContext.class.
        String sdl = """
            type Film @table(name: "film") {
                rating: String @service(service: {
                    className: "no.sikt.graphitron.rewrite.TestServiceStub",
                    method: "getRatingByUser"
                }, contextArguments: ["userId"])
            }
            type Query { film: Film }
            """;
        var schema = TestSchemaHelper.buildSchema(sdl);
        var spec = GraphitronFacadeGenerator.generate(schema, "com.example").get(0);

        String body = spec.methodSpecs().stream()
            .filter(m -> m.name().equals("newExecutionInput"))
            .findFirst()
            .orElseThrow()
            .code()
            .toString();

        // Null-check tier: defaultDsl + each contextArg
        assertThat(body)
            .contains("java.util.Objects.requireNonNull(defaultDsl, \"defaultDsl\")")
            .contains("java.util.Objects.requireNonNull(userId, \"userId\")");

        // graphQLContext body: DSLContext.class typed key, string key per contextArg, singleton put
        assertThat(body)
            .contains("b.put(org.jooq.DSLContext.class, defaultDsl)")
            .contains("b.put(\"userId\", userId)")
            .contains("com.example.schema.GraphitronContext.GraphitronContextImpl.INSTANCE");

        // DataLoaderRegistry attached lazily
        assertThat(body).contains("new org.dataloader.DataLoaderRegistry()");
    }
}
