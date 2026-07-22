package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline coverage for the schema-qualified {@code @reference(path: [{key: "schema.constraint"}])}
 * form at the path-element author site. The multi-schema fixture holds a foreign key literally named
 * {@code note_event_fk} in <em>both</em> {@code multischema_a} and {@code multischema_b} (each
 * {@code note -> event} within its own schema), so the bare constraint name collides across schemas.
 *
 * <p>A {@code key:} value carrying a leading {@code schema.} qualifier scopes the FK lookup to that
 * schema, the sibling of the {@code schema.table} form on {@code @table(name:)}. The correct-schema
 * qualifier must resolve and land the join on that schema's FK; a wrong-schema qualifier resolves to
 * a real FK in the named schema that does not touch the source table, and must reject with the same
 * "does not connect" error a wrong bare key gets (the connection invariant, now reachable because a
 * qualified key can name an FK in a different schema than the source).
 *
 * @see QualifiedSourceReferencePipelineTest the sibling that drives the qualified <em>source</em>
 *      {@code @table} echo through the same three directive forms.
 */
@PipelineTier
class QualifiedReferenceKeyPipelineTest {

    private static final String MULTI_JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.multischemafixture";
    private static final String MULTI_OUTPUT_PACKAGE = "fake.code.generated.multischema";

    private static final ClassName NOTE_A =
        ClassName.get(MULTI_JOOQ_PACKAGE + ".multischema_a.tables", "Note");
    private static final ClassName EVENT_A =
        ClassName.get(MULTI_JOOQ_PACKAGE + ".multischema_a.tables", "Event");

    private static RewriteContext multiSchemaContext() {
        return new RewriteContext(
            List.of(), Path.of(""), Path.of(""),
            MULTI_OUTPUT_PACKAGE, MULTI_JOOQ_PACKAGE, Map.of());
    }

    /** A Note bound to multischema_a plus an Event carrying its own @table, with {@code fieldDecl}
     *  the @reference-bearing event field under test. */
    private static String sdl(String fieldDecl) {
        return """
            type Note @table(name: "multischema_a.note") {
                noteId: Int! @field(name: "note_id")
                %s
            }
            type Event @table(name: "multischema_a.event") {
                eventId: Int! @field(name: "event_id")
            }
            type Query { notes: [Note!]! }
            """.formatted(fieldDecl);
    }

    private static GraphitronField eventField(String fieldDecl) {
        return TestSchemaHelper.buildSchema(sdl(fieldDecl), multiSchemaContext()).field("Note", "event");
    }

    @Test
    void qualifiedKey_correctSchema_resolvesAndLandsOnThatSchemasFk() {
        var schema = TestSchemaHelper.buildSchema(
            sdl("event: Event @reference(path: [{key: \"multischema_a.note_event_fk\"}])"),
            multiSchemaContext());
        assertThat(schema.type("Note"))
            .as("qualified-key parent must classify as a table type")
            .isInstanceOf(GraphitronType.TableType.class);
        var field = schema.field("Note", "event");
        assertThat(field)
            .as("qualified @reference(key:) must resolve, not become UnclassifiedField")
            .isInstanceOf(ChildField.TableField.class);
        JoinStep.Hop hop = TestFixtures.fkHop(((ChildField.TableField) field).joinPath().get(0));
        assertThat(TestFixtures.fkRef((no.sikt.graphitron.rewrite.model.On.ColumnPairs) hop.on()).sqlName())
            .isEqualToIgnoringCase("note_event_fk");
        assertThat(hop.originTable().tableClass()).isEqualTo(NOTE_A);
        assertThat(hop.targetTable().tableClass()).isEqualTo(EVENT_A);
    }

    @Test
    void qualifiedKey_wrongSchema_rejectsWithDoesNotConnect() {
        // multischema_b.note_event_fk is a real FK in multischema_b, but it does not touch the
        // multischema_a.note source: the qualifier is hard (resolves to the b-schema FK), then the
        // connection invariant fires.
        var field = eventField(
            "event: Event @reference(path: [{key: \"multischema_b.note_event_fk\"}])");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) field).reason())
            .contains("does not connect to table 'multischema_a.note'");
    }

    @Test
    void qualifiedKey_schemaWithNoSuchFk_rejectsAsUnresolved() {
        // signal_widget_id_fkey exists only in multischema_a; qualifying it with multischema_b names
        // a schema that holds no such FK, so it is unresolved (not a silent fall-through).
        var field = eventField(
            "event: Event @reference(path: [{key: \"multischema_b.signal_widget_id_fkey\"}])");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) field).reason())
            .contains("multischema_b.signal_widget_id_fkey")
            .contains("could not be resolved in the jOOQ catalog");
    }
}
