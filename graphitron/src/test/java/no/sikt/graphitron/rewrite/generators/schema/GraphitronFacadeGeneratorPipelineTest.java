package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L4 pipeline tests for {@link GraphitronFacadeGenerator}: drive the generator against a real
 * classified schema with multiple {@code @service(contextArguments: [...])} sites and pin the
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

    @Test
    void factory_reflectsContextArgumentsAlphabeticallyWithReflectedJavaTypes() {
        // Two root @service sites with disjoint contextArguments (userId: String, fnr: Long). The
        // classifier walks both MethodRefs, collects them by name, and produces a sorted
        // (alphabetical) ResolvedContextArg map. The factory emitter pastes one parameter slot
        // per entry, in TreeMap iteration order.
        String sdl = """
            type Film @table(name: "film") { title: String }
            type Query {
                film: Film
                ratingByUser: String @service(service: {
                    className: "no.sikt.graphitron.rewrite.TestServiceStub",
                    method: "getRatingByUser"
                }, contextArguments: ["userId"])
                ratingByFnr: String @service(service: {
                    className: "no.sikt.graphitron.rewrite.TestServiceStub",
                    method: "getRatingByFnr"
                }, contextArguments: ["fnr"])
            }
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
    void ownedFactory_takesExactlyTheAlphabeticalContextArguments() {
        // The owned-connection factory carries only the alphabetical contextArgument list (a
        // configured mount's payload parameters would be ordinary entries in it). Distinct name
        // from the escape-hatch newExecutionInput, so a caller passing a DSLContext cannot
        // silently reach the owned path.
        String sdl = """
            type Film @table(name: "film") { title: String }
            type Query {
                film: Film
                ratingByUser: String @service(service: {
                    className: "no.sikt.graphitron.rewrite.TestServiceStub",
                    method: "getRatingByUser"
                }, contextArguments: ["userId"])
                ratingByFnr: String @service(service: {
                    className: "no.sikt.graphitron.rewrite.TestServiceStub",
                    method: "getRatingByFnr"
                }, contextArguments: ["fnr"])
            }
            """;
        var schema = TestSchemaHelper.buildSchema(sdl);
        var spec = GraphitronFacadeGenerator.generate(schema, "com.example").get(0);

        MethodSpec owned = spec.methodSpecs().stream()
            .filter(m -> m.name().equals("newOwnedExecutionInput"))
            .findFirst()
            .orElseThrow();

        assertThat(owned.parameters()).extracting(p -> p.name())
            .containsExactly("fnr", "userId");
        assertThat(owned.parameters()).extracting(p -> p.type().toString())
            .containsExactly("java.lang.Long", "java.lang.String");
        assertThat(owned.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC);
    }

    @Test
    void ownedFactory_mountPayloadSlotSortsAlphabeticallyAmongDeclaredContextArguments() {
        // A configured mount's payload parameter is an ordinary contextArgument slot: one
        // population, sorted alphabetically by name, mount-derived and @service-declared alike
        // ("claims" from the mount before "userId" from the directive). The call into the mount
        // itself preserves the method's own declaration order instead; that half is pinned by
        // SessionHookImplGeneratorTest.
        String sdl = """
            type Query {
                rating: String @service(service: {
                    className: "no.sikt.graphitron.rewrite.TestServiceStub",
                    method: "getRatingByUser"
                }, contextArguments: ["userId"])
            }
            """;
        var schema = no.sikt.graphitron.rewrite.session.SessionHooksFixtures.withHooks(
            TestSchemaHelper.buildSchema(sdl),
            no.sikt.graphitron.rewrite.session.SessionHooksFixtures.handled(
                no.sikt.graphitron.javapoet.ClassName.get(String.class),
                no.sikt.graphitron.rewrite.session.SessionHooksFixtures.stringPayload("claims")));
        var spec = GraphitronFacadeGenerator.generate(schema, "com.example").get(0);

        MethodSpec owned = spec.methodSpecs().stream()
            .filter(m -> m.name().equals("newOwnedExecutionInput"))
            .findFirst()
            .orElseThrow();
        assertThat(owned.parameters()).extracting(p -> p.name())
            .containsExactly("claims", "userId");
        assertThat(owned.parameters()).extracting(p -> p.type().toString())
            .containsExactly("java.lang.String", "java.lang.String");
    }

    @Test
    void sessionBoundParameter_growsNoFactorySlot() {
        // The $session binding is per-connection state read at the call site, never
        // caller-supplied: the owned factory's parameter list carries the mount's payload only,
        // no slot for the bound `identity` parameter. (That the emitted read goes through the
        // resolved connection's configuration rather than graphQLContext is behavioral and
        // execution-pinned, per the no-code-string-assertion rule.)
        String sdl = """
            type Query {
                sessionPrincipal: String @service(service: {
                    className: "no.sikt.graphitron.rewrite.TestServiceStub",
                    method: "principalOf",
                    argMapping: "identity: $session"
                })
            }
            """;
        var schema = no.sikt.graphitron.rewrite.session.SessionHooksFixtures.withHooks(
            TestSchemaHelper.buildSchema(sdl),
            no.sikt.graphitron.rewrite.session.SessionHooksFixtures.handled(
                no.sikt.graphitron.javapoet.ClassName.get(String.class),
                no.sikt.graphitron.rewrite.session.SessionHooksFixtures.stringPayload("claims")));
        var spec = GraphitronFacadeGenerator.generate(schema, "com.example").get(0);

        MethodSpec owned = spec.methodSpecs().stream()
            .filter(m -> m.name().equals("newOwnedExecutionInput"))
            .findFirst()
            .orElseThrow();
        assertThat(owned.parameters()).extracting(p -> p.name())
            .as("the handle is bound at the consuming site, not supplied by the caller")
            .containsExactly("claims");
    }
}
