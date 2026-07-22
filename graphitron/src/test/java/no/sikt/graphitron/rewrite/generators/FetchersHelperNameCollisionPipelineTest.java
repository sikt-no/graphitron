package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline tier for the cross-schema helper-name collision: two schemas each carry an {@code event}
 * table, so jOOQ generates two {@code EventRecord} classes with one simple name but distinct
 * packages ({@code multischema_a.…records.EventRecord} vs {@code multischema_b.…records.EventRecord}).
 * A schema that declares {@code @service} mutations against both binds two {@code create<Record>}
 * helpers on one {@code QueryFetchers} class; a resolver keying the stem on {@code simpleName()}
 * alone names both {@code createEventRecord} and the class fails to compile. The
 * {@link FetchersHelperNames} resolver disambiguates them by the pascal-cased schema segment:
 * {@code createMultischemaAEventRecord} / {@code createMultischemaBEventRecord}.
 *
 * <p>Backed by the real multischemafixture catalog (built by earlier multi-schema roadmap work) and
 * the {@code TestServiceStub.modifyMultischema*Event} methods. The record params bind on their
 * {@code @nodeId} identity (each event table's {@code event_id} PK); {@code @field} column binding on
 * a jOOQ-record {@code @service} param resolves the backing table by bare name, which is a separate
 * multi-schema concern outside this item's helper-naming scope, so these fixtures bind on the node
 * key alone. Assertions are structural (helper names present on the one class, call sites routing to
 * their own helper), plus a byte-for-byte pin that a single-schema class keeps the bare
 * {@code createEventRecord} name (no churn), plus the collision-times-contention composition
 * (cross-class disambiguation orthogonal to shape ordinals).
 */
@PipelineTier
class FetchersHelperNameCollisionPipelineTest {

    private static final String MULTI_JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.multischemafixture";
    private static final String MULTI_OUTPUT_PACKAGE = "fake.code.generated.multischema";

    private static final String SVC = "no.sikt.graphitron.rewrite.TestServiceStub";

    private static final String CROSS_SCHEMA_SDL = """
        type EventA implements Node @table(name: "multischema_a.event") @node { id: ID! }
        type EventB implements Node @table(name: "multischema_b.event") @node { id: ID! }
        input ModifyEventAInput { eventId: ID! @nodeId(typeName: "EventA") }
        input ModifyEventBInput { eventId: ID! @nodeId(typeName: "EventB") }
        type Query {
            modifyEventA(in: ModifyEventAInput!): String
                @service(service: {className: "%s", method: "modifyMultischemaAEvent"})
            modifyEventB(in: ModifyEventBInput!): String
                @service(service: {className: "%s", method: "modifyMultischemaBEvent"})
        }
        """.formatted(SVC, SVC);

    @Test
    void twoSchemaEventRecords_emitDistinctDisambiguatedHelpers_onOneClass() {
        var fetchers = findSpec("QueryFetchers", CROSS_SCHEMA_SDL);
        assertThat(helperNames(fetchers))
            .as("each schema's EventRecord gets its own schema-segment-prefixed create<Record> helper")
            .contains("createMultischemaAEventRecord", "createMultischemaBEventRecord")
            .as("the colliding bare name never appears; it was the compile break this fixes")
            .doesNotContain("createEventRecord");
    }

    @Test
    void eachMutation_routesToItsOwnSchemaHelper() {
        var fetchers = findSpec("QueryFetchers", CROSS_SCHEMA_SDL);
        assertThat(methodBody(fetchers, "modifyEventA"))
            .contains("createMultischemaAEventRecord(env.getArgument(")
            .doesNotContain("createMultischemaBEventRecord(env.getArgument(");
        assertThat(methodBody(fetchers, "modifyEventB"))
            .contains("createMultischemaBEventRecord(env.getArgument(")
            .doesNotContain("createMultischemaAEventRecord(env.getArgument(");
    }

    private static final String SINGLE_SCHEMA_SDL = """
        type EventA implements Node @table(name: "multischema_a.event") @node { id: ID! }
        input ModifyEventAInput { eventId: ID! @nodeId(typeName: "EventA") }
        type Query {
            modifyEventA(in: ModifyEventAInput!): String
                @service(service: {className: "%s", method: "modifyMultischemaAEvent"})
        }
        """.formatted(SVC);

    @Test
    void singleSchemaClass_keepsBareHelperName_noChurn() {
        // With only one EventRecord on the class the simple name is unique, so the byte-for-byte
        // legacy name survives: no disambiguation prefix, no churn for the common case.
        var fetchers = findSpec("QueryFetchers", SINGLE_SCHEMA_SDL);
        assertThat(helperNames(fetchers))
            .contains("createEventRecord")
            .doesNotContain("createMultischemaAEventRecord");
    }

    private static final String COLLISION_TIMES_CONTENTION_SDL = """
        type EventA implements Node @table(name: "multischema_a.event") @node { id: ID! }
        type EventB implements Node @table(name: "multischema_b.event") @node { id: ID! }
        input RegisterEventAInput { eventId: ID! @nodeId(typeName: "EventA") }
        input TouchEventAInput {
            eventId: ID! @nodeId(typeName: "EventA")
            altId: ID! @nodeId(typeName: "EventA")
        }
        input ModifyEventBInput { eventId: ID! @nodeId(typeName: "EventB") }
        type Query {
            registerEventA(in: RegisterEventAInput!): String
                @service(service: {className: "%s", method: "modifyMultischemaAEvent"})
            touchEventA(in: TouchEventAInput!): String
                @service(service: {className: "%s", method: "modifyMultischemaAEvent"})
            modifyEventB(in: ModifyEventBInput!): String
                @service(service: {className: "%s", method: "modifyMultischemaBEvent"})
        }
        """.formatted(SVC, SVC, SVC);

    @Test
    void collisionTimesContention_composesCrossClassPrefixWithShapeOrdinals() {
        // The A-schema EventRecord is bound through two distinct input shapes (register decodes one
        // @nodeId, touch decodes two) → shape contention → ordinal suffixes; the B-schema EventRecord
        // is bound once → uncontended. The two axes compose: cross-class prefix (which class) times
        // shape ordinal (which binding shape), yielding createMultischemaAEventRecord1/2 alongside the
        // uncontended createMultischemaBEventRecord.
        var fetchers = findSpec("QueryFetchers", COLLISION_TIMES_CONTENTION_SDL);
        assertThat(helperNames(fetchers).stream()
                .filter(n -> n.startsWith("createMultischemaAEventRecord") && !n.endsWith("List")).toList())
            .as("the A-schema record's two shapes are ordinal-suffixed on the disambiguated stem")
            .containsExactlyInAnyOrder("createMultischemaAEventRecord1", "createMultischemaAEventRecord2");
        assertThat(helperNames(fetchers))
            .as("the uncontended B-schema record keeps the bare (no-ordinal) disambiguated stem")
            .contains("createMultischemaBEventRecord")
            .doesNotContain("createMultischemaBEventRecord1", "createEventRecord");
    }

    // ===== helpers =====

    private static List<String> helperNames(TypeSpec fetchers) {
        return fetchers.methodSpecs().stream().map(MethodSpec::name).toList();
    }

    private static String methodBody(TypeSpec spec, String methodName) {
        return spec.methodSpecs().stream()
            .filter(m -> m.name().equals(methodName))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no method " + methodName))
            .code().toString();
    }

    private static RewriteContext multiSchemaContext() {
        return new RewriteContext(List.of(), Path.of(""), Path.of(""),
            MULTI_OUTPUT_PACKAGE, MULTI_JOOQ_PACKAGE, Map.of());
    }

    private static TypeSpec findSpec(String className, String sdl) {
        GraphitronSchema schema = TestSchemaHelper.buildSchema(sdl, multiSchemaContext());
        return TypeFetcherGenerator.generate(schema, MULTI_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals(className))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Class not found: " + className));
    }
}
