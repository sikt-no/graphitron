package no.sikt.graphitron.rewrite.generators.schema;

import com.palantir.javapoet.MethodSpec;
import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.emit.FillQuery;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L4 pipeline tests for {@link GraphitronFacadeGenerator}: drive the generator against a real
 * classified schema with multiple {@code @condition(contextArguments: [...])} sites and pin the
 * alphabetical-sorted factory parameter list (names + types + modifiers).
 *
 * <p>Sibling to the unit-tier {@code GraphitronFacadeGeneratorTest}, which uses an empty schema
 * to pin the empty-contextArgs collapse to {@code newExecutionInput(DSLContext)}. This test
 * exercises the multi-arg shape end-to-end through the classifier, the same code paths the
 * production generator runs.
 *
 * <p>Body-shape behaviour (null-checks, GraphQLContext put pattern, DataLoaderRegistry attach) is
 * covered by the L5 compile gate plus the L6 round-trip in {@code FilmContextArgumentRoundTripTest};
 * body-string assertions are intentionally absent here per the no-code-string-assertion rule.
 */
@PipelineTier
class GraphitronFacadeGeneratorPipelineTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    /**
     * The context keys the store states the {@code @service} calls of two shared scenarios read:
     * occurrence-call-parameter's, where only services read userId, and call-context-key's, where a
     * service and a {@code @condition} both read it.
     */
    private static final class ServiceKeys {
        static final List<FillQuery.ContextKey> SERVICE_ONLY = FACTS
            .read("scenarios/occurrence-call-parameter", graph -> FillQuery.contextKeys(graph.handle()));
        static final List<FillQuery.ContextKey> SHARED = FACTS
            .read("scenarios/call-context-key", graph -> FillQuery.contextKeys(graph.handle()));
    }

    private static MethodSpec newExecutionInput(String sdl, List<FillQuery.ContextKey> serviceKeys) {
        var schema = TestSchemaHelper.buildSchema(sdl, TestConfiguration.testContext(), Map.of(), Map.of(),
            serviceKeys);
        return GraphitronFacadeGenerator.generate(schema, "com.example").get(0).methodSpecs().stream()
            .filter(m -> m.name().equals("newExecutionInput"))
            .findFirst()
            .orElseThrow();
    }

    @Test
    void factory_takesAContextKeyOnlyAServiceReads() {
        // The store owns the @service field whole, so no classified field carries its key: the
        // factory takes userId because the store states the call reads it into a String parameter.
        var factory = newExecutionInput("""
            type Query {
                callGreeting: String @service(service: {
                    className: "no.sikt.graphitron.rewrite.test.services.UserGreetingService",
                    method: "greet"}, contextArguments: ["userId"])
            }
            """, ServiceKeys.SERVICE_ONLY);

        assertThat(factory.parameters()).extracting(p -> p.name())
            .containsExactly("defaultDsl", "userId");
        assertThat(factory.parameters()).extracting(p -> p.type().toString())
            .containsExactly("org.jooq.DSLContext", "java.lang.String");
    }

    @Test
    void factory_takesAKeyAServiceAndAConditionBothReadOnce() {
        // The walk harvests the @condition's userId and the store states the @service's; the two
        // agree on String and the factory takes the key once.
        var factory = newExecutionInput("""
            type Customer @table(name: "customer") { firstName: String @field(name: "first_name") }
            type Query {
                greetingByUser: String @service(service: {
                    className: "no.sikt.graphitron.rewrite.test.services.UserGreetingService",
                    method: "greet"
                }, contextArguments: ["userId"])
                customersSeenByUser: [Customer!]! @condition(condition: {
                    className: "no.sikt.graphitron.rewrite.test.conditions.InputFieldConditionFixtures",
                    method: "customerSeenByUser"}, contextArguments: ["userId"])
            }
            """, ServiceKeys.SHARED);

        assertThat(factory.parameters()).extracting(p -> p.name())
            .containsExactly("defaultDsl", "userId");
        assertThat(factory.parameters()).extracting(p -> p.type().toString())
            .containsExactly("org.jooq.DSLContext", "java.lang.String");
    }

    @Test
    void factory_reflectsContextArgumentsAlphabeticallyWithReflectedJavaTypes() {
        // Two root @condition sites with disjoint contextArguments (userId: String, brukerId: Long).
        // The classifier walks both MethodRefs, collects them by name, and produces a sorted
        // (alphabetical) ResolvedContextArg map. The factory emitter pastes one parameter slot
        // per entry, in TreeMap iteration order.
        String sdl = """
            type Film @table(name: "film") { title: String }
            type Customer @table(name: "customer") { firstName: String @field(name: "first_name") }
            type Query {
                customersSeenByUser: [Customer!]! @condition(condition: {
                    className: "no.sikt.graphitron.rewrite.test.conditions.InputFieldConditionFixtures",
                    method: "customerSeenByUser"
                }, contextArguments: ["userId"])
                filmsSeenByBruker: [Film!]! @condition(condition: {
                    className: "no.sikt.graphitron.rewrite.TestConditionStub",
                    method: "sessionBoxed"
                }, contextArguments: ["brukerId"])
            }
            """;
        var schema = TestSchemaHelper.buildSchema(sdl);
        var spec = GraphitronFacadeGenerator.generate(schema, "com.example").get(0);

        MethodSpec newExecutionInput = spec.methodSpecs().stream()
            .filter(m -> m.name().equals("newExecutionInput"))
            .findFirst()
            .orElseThrow();

        // First parameter is always defaultDsl. Contextargs follow alphabetically (brukerId before userId).
        assertThat(newExecutionInput.parameters()).extracting(p -> p.name())
            .containsExactly("defaultDsl", "brukerId", "userId");
        assertThat(newExecutionInput.parameters()).extracting(p -> p.type().toString())
            .containsExactly("org.jooq.DSLContext", "java.lang.Long", "java.lang.String");
        assertThat(newExecutionInput.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC);
    }

    @Test
    void ownedFactory_takesExactlyTheAlphabeticalContextArguments() {
        // The owned-connection factory carries only the alphabetical contextArgument list (a
        // configured mount's payload parameters would be ordinary entries in it). Distinct name
        // from the escape-hatch newExecutionInput, so a caller passing a DSLContext cannot
        // silently reach the owned path.
        String sdl = """
            type Film @table(name: "film") { title: String }
            type Customer @table(name: "customer") { firstName: String @field(name: "first_name") }
            type Query {
                customersSeenByUser: [Customer!]! @condition(condition: {
                    className: "no.sikt.graphitron.rewrite.test.conditions.InputFieldConditionFixtures",
                    method: "customerSeenByUser"
                }, contextArguments: ["userId"])
                filmsSeenByBruker: [Film!]! @condition(condition: {
                    className: "no.sikt.graphitron.rewrite.TestConditionStub",
                    method: "sessionBoxed"
                }, contextArguments: ["brukerId"])
            }
            """;
        var schema = TestSchemaHelper.buildSchema(sdl);
        var spec = GraphitronFacadeGenerator.generate(schema, "com.example").get(0);

        MethodSpec owned = spec.methodSpecs().stream()
            .filter(m -> m.name().equals("newOwnedExecutionInput"))
            .findFirst()
            .orElseThrow();

        assertThat(owned.parameters()).extracting(p -> p.name())
            .containsExactly("source", "brukerId", "userId");
        assertThat(owned.parameters()).extracting(p -> p.type().toString())
            .containsExactly("com.example.schema.ConnectionSource", "java.lang.Long", "java.lang.String");
        assertThat(owned.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC);
    }

    @Test
    void ownedFactory_takesTheConnectionSourceBeforeTheDeclaredContextArguments() {
        // Where the graph supplies session identity the owned factory takes the request's
        // ConnectionSource, as in every build, and only the schema's declared contextArguments
        // follow it, alphabetical as before: identity contributes none, since its inputs are
        // captured by the source.
        String sdl = """
            type Customer @table(name: "customer") { firstName: String @field(name: "first_name") }
            type Query {
                customersSeenByUser: [Customer!]! @condition(condition: {
                    className: "no.sikt.graphitron.rewrite.test.conditions.InputFieldConditionFixtures",
                    method: "customerSeenByUser"
                }, contextArguments: ["userId"])
            }
            """;
        var schema = TestSchemaHelper.buildSchema(sdl);
        var spec = GraphitronFacadeGenerator.generate(schema, "com.example", false, false,
            no.sikt.graphitron.rewrite.session.SessionIdentityFixtures.handleLess(), false, true).get(0);

        MethodSpec owned = spec.methodSpecs().stream()
            .filter(m -> m.name().equals("newOwnedExecutionInput"))
            .findFirst()
            .orElseThrow();
        assertThat(owned.parameters()).extracting(p -> p.name())
            .containsExactly("source", "userId");
        assertThat(owned.parameters()).extracting(p -> p.type().toString())
            .containsExactly("com.example.schema.ConnectionSource", "java.lang.String");
        MethodSpec escapeHatch = spec.methodSpecs().stream()
            .filter(m -> m.name().equals("newExecutionInput"))
            .findFirst()
            .orElseThrow();
        assertThat(escapeHatch.parameters()).extracting(p -> p.name())
            .as("the escape hatch's caller owns identity, so it takes no connection source")
            .containsExactly("defaultDsl", "userId");
    }

    @Test
    void sessionBoundParameter_growsNoFactorySlot() {
        // The $session binding is per-connection state read at the call site, never
        // caller-supplied: the owned factory's parameter list carries the connection source only,
        // no slot for the bound `brukerId` parameter. (That the emitted read goes through the
        // resolved connection's configuration rather than graphQLContext is behavioral and
        // execution-pinned, per the no-code-string-assertion rule.)
        String sdl = """
            type Film @table(name: "film") { title: String }
            type Query {
                films: [Film!]! @condition(condition: {
                    className: "no.sikt.graphitron.rewrite.TestConditionStub",
                    method: "sessionBoxed",
                    argMapping: "brukerId: $session.brukerId"
                })
            }
            """;
        var schema = TestSchemaHelper.buildSchema(sdl);
        var spec = GraphitronFacadeGenerator.generate(schema, "com.example", false, false,
            no.sikt.graphitron.rewrite.session.SessionIdentityFixtures.handled(
                com.palantir.javapoet.ClassName.get(String.class)), false, true).get(0);

        MethodSpec owned = spec.methodSpecs().stream()
            .filter(m -> m.name().equals("newOwnedExecutionInput"))
            .findFirst()
            .orElseThrow();
        assertThat(owned.parameters()).extracting(p -> p.name())
            .as("the handle is bound at the consuming site, not supplied by the caller")
            .containsExactly("source");
    }
}
