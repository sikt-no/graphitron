package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Check-2 concrete-condition-param check must match by jOOQ class identity, not by a
 * bare-vs-qualified name string compare. Over the multi-schema fixture whose {@code event} table
 * collides across {@code multischema_a} / {@code multischema_b}, a concrete condition-method table
 * parameter typed with the <em>correct</em> generated jOOQ class must classify green even when the
 * hop's table name is schema-qualified, and a parameter typed with the same-named class in the
 * <em>wrong</em> schema must still be rejected.
 *
 * <p>The single-schema (sakila) sibling {@link ReferencePathConditionParamTest} pins the same check
 * where identity and name compares agree; this class adds the discriminating shape only a
 * bare-name collision can produce. Because every operand here is a catalog-built {@code TableRef},
 * a genuine-mismatch case that still rejects doubles as the enforcer that the identity arm of
 * {@link no.sikt.graphitron.rewrite.model.TableRef#denotesSameTableAs} (not the name fallback)
 * decided the compare.
 */
@PipelineTier
class MultiSchemaConditionParamTest {

    private static final String STUB = "no.sikt.graphitron.rewrite.MultiSchemaConditionStub";
    private static final String MULTI_JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.multischemafixture";
    private static final String MULTI_OUTPUT_PACKAGE = "fake.code.generated.multischema";

    private static RewriteContext multiSchemaContext() {
        return new RewriteContext(
            List.of(),
            Path.of(""),
            Path.of(""),
            MULTI_OUTPUT_PACKAGE,
            MULTI_JOOQ_PACKAGE,
            Map.of()
        );
    }

    private static GraphitronField field(String sdl, String type, String name) {
        return TestSchemaHelper.buildSchema(sdl, multiSchemaContext()).field(type, name);
    }

    // ===== Terminal ON-clause condition: source operand carries the qualified @table echo =====

    @Test
    void terminalConditionSource_correctSchemaClass_classifiesGreen() {
        // Parent EvA is @table(name: "multischema_a.event") (the qualification is forced: bare
        // "event" is ambiguous across the two schemas). The terminal condition joins to Widget;
        // parameter 0 (source) is typed multischema_a.tables.Event. Previously the check compared
        // bare "event" against the qualified "multischema_a.event" echo and false-rejected; the
        // author's only workaround was widening to Table<?>. Identity comparison classifies it green.
        var f = field("""
            type Widget @table(name: "widget") { widgetId: Int! @field(name: "widget_id") }
            type EvA @table(name: "multischema_a.event") {
                eventId: Int! @field(name: "event_id")
                w: Widget @reference(path: [
                    {condition: {className: "%s", method: "sourceMultiA"}}
                ])
            }
            type Query { eva: EvA }
            """.formatted(STUB), "EvA", "w");
        assertThat(f).isNotInstanceOf(UnclassifiedField.class);
    }

    @Test
    void terminalConditionSource_wrongSchemaClass_isRejectedByIdentity() {
        // Same hop, but parameter 0 is typed multischema_b.tables.Event: identical bare name,
        // wrong schema. Must still be an author error. Both operands are catalog-built, so a pass
        // here would mean the name fallback (not identity) decided — this is the fallback enforcer.
        var f = field("""
            type Widget @table(name: "widget") { widgetId: Int! @field(name: "widget_id") }
            type EvA @table(name: "multischema_a.event") {
                eventId: Int! @field(name: "event_id")
                w: Widget @reference(path: [
                    {condition: {className: "%s", method: "sourceMultiB"}}
                ])
            }
            type Query { eva: EvA }
            """.formatted(STUB), "EvA", "w");
        assertThat(f).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) f).reason())
            .contains("parameter 0")
            .contains("source")
            .contains("multischema_b.event")
            .contains("multischema_a.event");
    }

    // ===== Terminal ON-clause condition: target operand carries the qualified @table echo =====
    //
    // A terminal condition hop resolves its target from the return type's @table echo
    // (resolveConditionJoinTarget's terminal branch), so the target operand carries the qualified
    // "multischema_a.event" name independently of the method parameter. The where-filter path
    // cannot supply target-side coverage here: its FK-derived target endpoint resolves through
    // synthesizeFkJoin's bare-name catalog lookup, which is ambiguous for the colliding "event"
    // table; resolving that bare endpoint by jOOQ class identity is a separate concern from this
    // concrete-param check. The where-filter path's qualified *source* operand is covered below.

    @Test
    void terminalConditionTarget_correctSchemaClass_classifiesGreen() {
        // Parent Widget joins by condition to EvA, whose return @table is the qualified
        // "multischema_a.event". Parameter 1 (target) is typed multischema_a.tables.Event; source
        // is wildcarded so only the target check fires. Previously the check compared bare "event"
        // against "multischema_a.event" and false-rejected.
        var f = field("""
            type EvA @table(name: "multischema_a.event") {
                eventId: Int! @field(name: "event_id")
                name: String!
            }
            type Widget @table(name: "widget") {
                widgetId: Int! @field(name: "widget_id")
                ev: EvA @reference(path: [
                    {condition: {className: "%s", method: "targetMultiA"}}
                ])
            }
            type Query { widget: Widget }
            """.formatted(STUB), "Widget", "ev");
        assertThat(f).isNotInstanceOf(UnclassifiedField.class);
    }

    @Test
    void terminalConditionTarget_wrongSchemaClass_isRejectedByIdentity() {
        // Same hop, parameter 1 typed multischema_b.tables.Event: same bare name, wrong schema.
        // Both operands are catalog-built, so a pass here would mean the name fallback decided.
        var f = field("""
            type EvA @table(name: "multischema_a.event") {
                eventId: Int! @field(name: "event_id")
                name: String!
            }
            type Widget @table(name: "widget") {
                widgetId: Int! @field(name: "widget_id")
                ev: EvA @reference(path: [
                    {condition: {className: "%s", method: "targetMultiB"}}
                ])
            }
            type Query { widget: Widget }
            """.formatted(STUB), "Widget", "ev");
        assertThat(f).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) f).reason())
            .contains("parameter 1")
            .contains("target")
            .contains("multischema_b.event")
            .contains("multischema_a.event");
    }

    @Test
    void whereFilterSource_qualifiedParentEcho_classifiesGreen() {
        // Reversed shape: parent EvA is @table(name: "multischema_a.event"), the hop targets
        // event_log (reverse FK, one-to-many). The where-filter's source operand carries the
        // qualified parent echo; filter parameter 0 (source) is typed multischema_a.tables.Event.
        var f = field("""
            type EventLog @table(name: "event_log") {
                logId: Int! @field(name: "event_log_id")
                note: String
            }
            type EvA @table(name: "multischema_a.event") {
                eventId: Int! @field(name: "event_id")
                logs: [EventLog!]! @reference(path: [
                    {table: "event_log", condition: {className: "%s", method: "sourceMultiA"}}
                ]) @defaultOrder(primaryKey: true)
            }
            type Query { eva: EvA }
            """.formatted(STUB), "EvA", "logs");
        assertThat(f).isNotInstanceOf(UnclassifiedField.class);
    }
}
