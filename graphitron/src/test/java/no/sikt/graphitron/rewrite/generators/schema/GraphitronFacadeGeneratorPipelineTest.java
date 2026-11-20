package no.sikt.graphitron.rewrite.generators.schema;

import com.palantir.javapoet.MethodSpec;
import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.emit.FillQuery;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.GraphitronSchema;
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
 * classified schema with the context keys the store states its calls read, read off shared fact
 * documents, and pin the factory parameter list (names + types + modifiers).
 *
 * <p>Sibling to the unit-tier {@code GraphitronFacadeGeneratorTest}, which uses an empty schema
 * to pin the empty-contextArgs collapse to {@code newExecutionInput(DSLContext)}.
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
     * The context keys the store states the calls of three shared scenarios read:
     * occurrence-call-parameter's, where only services read userId and label, call-context-key's, where a
     * service and a {@code @condition} both read userId and a service's pair reads viewerId, and occurrence-condition's, where only a
     * {@code @condition} does.
     */
    private static final class StoreKeys {
        static final List<FillQuery.ContextKey> SERVICE_ONLY = FACTS
            .read("scenarios/occurrence-call-parameter", graph -> FillQuery.contextKeys(graph.handle()));
        static final List<FillQuery.ContextKey> SHARED = FACTS
            .read("scenarios/call-context-key", graph -> FillQuery.contextKeys(graph.handle()));
        static final List<FillQuery.ContextKey> CONDITION_ONLY = FACTS
            .read("scenarios/occurrence-condition", graph -> FillQuery.contextKeys(graph.handle()));
    }

    /** The {@code @condition} reading userId in call-context-key and occurrence-condition. */
    private static final String CONDITION_SDL = """
        type Customer @table(name: "customer") { firstName: String @field(name: "first_name") }
        type Query {
            customersSeenByUser: [Customer!]! @condition(condition: {
                className: "no.sikt.graphitron.rewrite.test.conditions.InputFieldConditionFixtures",
                method: "customerSeenByUser"
            }, contextArguments: ["userId"])
        }
        """;

    private static GraphitronSchema schema(String sdl, List<FillQuery.ContextKey> keys) {
        return TestSchemaHelper.buildSchema(sdl, TestConfiguration.testContext(), Map.of(), Map.of(), keys);
    }

    private static MethodSpec method(GraphitronSchema schema, String name) {
        return GraphitronFacadeGenerator.generate(schema, "com.example").get(0).methodSpecs().stream()
            .filter(m -> m.name().equals(name))
            .findFirst()
            .orElseThrow();
    }

    private static MethodSpec newExecutionInput(String sdl, List<FillQuery.ContextKey> keys) {
        return method(schema(sdl, keys), "newExecutionInput");
    }

    @Test
    void factory_takesAContextKeyOnlyAServiceReads() {
        // The store owns the @service field whole, so no classified field carries its key: the
        // factory takes userId, and label, which a batched child reads, because the store states the
        // calls read them into String parameters.
        var factory = newExecutionInput("""
            type Query {
                callGreeting: String @service(service: {
                    className: "no.sikt.graphitron.rewrite.test.services.UserGreetingService",
                    method: "greet"}, contextArguments: ["userId"])
            }
            """, StoreKeys.SERVICE_ONLY);

        assertThat(factory.parameters()).extracting(p -> p.name())
            .containsExactly("defaultDsl", "label", "userId");
        assertThat(factory.parameters()).extracting(p -> p.type().toString())
            .containsExactly("org.jooq.DSLContext", "java.lang.String", "java.lang.String");
    }

    @Test
    void factory_takesAKeyAServiceAndAConditionBothReadOnce() {
        // The store states both calls' userId; the two agree on String and the factory takes the
        // key once, with both calls as its sites. The scenario's $context pair reads viewerId, which
        // follows it in the factory's alphabetical order.
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
            """, StoreKeys.SHARED);

        assertThat(factory.parameters()).extracting(p -> p.name())
            .containsExactly("defaultDsl", "userId", "viewerId");
        assertThat(factory.parameters()).extracting(p -> p.type().toString())
            .containsExactly("org.jooq.DSLContext", "java.lang.String", "java.lang.String");
        assertThat(schema(CONDITION_SDL, StoreKeys.SHARED).contextArguments().resolved().get("userId").sites())
            .extracting(site -> site.className())
            .containsExactlyInAnyOrder(
                "no.sikt.graphitron.rewrite.test.services.UserGreetingService",
                "no.sikt.graphitron.rewrite.test.conditions.InputFieldConditionFixtures");
    }

    @Test
    void factory_takesAContextKeyOnlyAConditionReads() {
        // No @service reads userId here: the factory takes it because the store states the
        // @condition's call reads it into a String parameter.
        assertThat(StoreKeys.CONDITION_ONLY)
            .extracting(key -> key.className() + "." + key.methodName())
            .containsOnly("no.sikt.graphitron.rewrite.test.conditions.InputFieldConditionFixtures.customerSeenByUser");
        var factory = newExecutionInput(CONDITION_SDL, StoreKeys.CONDITION_ONLY);

        assertThat(factory.parameters()).extracting(p -> p.name())
            .containsExactly("defaultDsl", "userId");
        assertThat(factory.parameters()).extracting(p -> p.type().toString())
            .containsExactly("org.jooq.DSLContext", "java.lang.String");
        assertThat(factory.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC);
    }

    @Test
    void ownedFactory_takesExactlyTheStatedContextKeys() {
        // The owned-connection factory carries only the context keys (a configured mount's
        // payload parameters would be ordinary entries in it). Distinct name from the escape-hatch
        // newExecutionInput, so a caller passing a DSLContext cannot silently reach the owned path.
        MethodSpec owned = method(schema(CONDITION_SDL, StoreKeys.CONDITION_ONLY), "newOwnedExecutionInput");

        assertThat(owned.parameters()).extracting(p -> p.name())
            .containsExactly("source", "userId");
        assertThat(owned.parameters()).extracting(p -> p.type().toString())
            .containsExactly("com.example.schema.ConnectionSource", "java.lang.String");
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
        var schema = schema(sdl, StoreKeys.CONDITION_ONLY);
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
