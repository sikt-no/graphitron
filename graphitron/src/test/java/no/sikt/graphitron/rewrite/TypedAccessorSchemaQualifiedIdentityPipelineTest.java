package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.Arity;
import no.sikt.graphitron.rewrite.model.KeyLift;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The typed-accessor match on a free-form DTO payload parent must compare jOOQ table identity
 * ({@code tableClass}), not the bare {@code @table} name. Driven over the multischema fixture,
 * whose two {@code event} tables ({@code multischema_a.event} / {@code multischema_b.event}) share
 * the bare SQL name {@code event}, so the payload element type's {@code @table} must be
 * schema-qualified to disambiguate.
 *
 * <p>The two sides' names never agree: the accessor-side
 * {@link no.sikt.graphitron.rewrite.model.TableRef} is resolved by record-class identity
 * ({@code ServiceCatalog.resolveTableByRecordClass}) and carries jOOQ's unqualified canonical name
 * {@code "event"}, while the element side carries the verbatim qualified echo
 * {@code "multischema_a.event"}. Only class identity can match them, and only class identity can
 * tell colliding schemas apart. Both directions are pinned below, each a real classifier outcome
 * reached through the full SDL-to-classify pipeline.
 */
@PipelineTier
class TypedAccessorSchemaQualifiedIdentityPipelineTest {

    private static final String MULTI_JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.multischemafixture";
    private static final String MULTI_OUTPUT_PACKAGE = "fake.code.generated.multischema";
    private static final String DUMMY_SERVICE = "no.sikt.graphitron.codereferences.dummyreferences.DummyService";

    private static final ClassName SCHEMA_A_EVENT =
        ClassName.get(MULTI_JOOQ_PACKAGE + ".multischema_a.tables", "Event");

    private static RewriteContext multiSchemaContext() {
        return new RewriteContext(
            List.of(),
            Path.of(""), "TypedAccessorSchemaQualifiedIdentityPipelineTest",
            Path.of(""),
            MULTI_OUTPUT_PACKAGE,
            MULTI_JOOQ_PACKAGE
        );
    }

    /**
     * The payload element type must be schema-qualified: bare {@code event} is ambiguous across
     * {@code multischema_a} / {@code multischema_b}, so {@code @table(name: "event")} would not
     * resolve. {@code producer} names the {@code DummyService} method whose return type binds the
     * {@code EventPayload} DTO backing class.
     */
    private static String sdl(String producer) {
        return """
            type Event @table(name: "multischema_a.event") {
                eventId: Int! @field(name: "event_id")
                name: String!
            }
            type EventPayload {
                events: [Event!]
            }
            type Query {
                payload: EventPayload @service(service: {className: "%s", method: "%s"})
            }
            """.formatted(DUMMY_SERVICE, producer);
    }

    @Test
    void qualifiedTableEcho_matchesAccessorByClassIdentity_classifiesBatchedTableField() {
        // SchemaAEventsPayload exposes `List<EventRecord> events()` where EventRecord is
        // multischema_a's. The element type's @table echo "multischema_a.event" never equals the
        // accessor table's bare canonical name "event"; only the tableClass identity compare matches.
        var schema = TestSchemaHelper.buildSchema(sdl("makeR441SchemaAEventsPayload"), multiSchemaContext());

        var field = schema.field("EventPayload", "events");
        assertThat(field)
            .as("EventPayload.events must classify green via the accessor-derived source")
            .isInstanceOf(ChildField.BatchedTableField.class);
        var btf = (ChildField.BatchedTableField) field;

        assertThat(btf.lift()).isInstanceOfSatisfying(KeyLift.Accessor.class, ac -> {
            assertThat(ac.accessor().methodName()).isEqualTo("events");
            assertThat(ac.arity()).isEqualTo(Arity.MANY);
        });
        // The source target is the qualified element table, pinned by class identity (its name()
        // is the verbatim "multischema_a.event" echo; tableClass is jOOQ's Event under schema A).
        assertThat(btf.returnType().table().tableClass()).isEqualTo(SCHEMA_A_EVENT);
    }

    @Test
    void differentSchemaRecord_doesNotMatchQualifiedEcho_fieldRejects() {
        // SchemaBEventsPayload's accessor returns multischema_b's EventRecord; the element type
        // stays @table(name: "multischema_a.event"). The accessor denotes a different table by
        // class identity, so it is dropped and the field rejects: a bare-name compare over
        // colliding schemas would bind the wrong schema's record.
        var schema = TestSchemaHelper.buildSchema(sdl("makeR441SchemaBEventsPayload"), multiSchemaContext());

        var field = schema.field("EventPayload", "events");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) field).rejection().message();
        assertThat(reason)
            .contains("BatchedTableField")
            .contains("requires a typed accessor or @sourceRow")
            .contains("no FK metadata for the parent class");
    }
}
