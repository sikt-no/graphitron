package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R422 pipeline coverage: an {@code @reference} field whose <em>return</em> type carries a
 * schema-qualified {@code @table(name:)} (e.g. {@code "multischema_a.widget"}) must classify green
 * when its terminal hop lands on that table. The terminal-target verdict
 * ({@code BuildContext.computeTerminalTargetVerdict}) now compares jOOQ table-class identity
 * ({@code TableRef.denotesSameTableAs}), so the schema-qualified echo matches jOOQ's unqualified
 * canonical name instead of the pre-R422 bare {@code equalsIgnoreCase} spuriously reporting
 * {@code Mismatch} and demoting the field to {@link GraphitronField.UnclassifiedField}.
 *
 * <p>Sibling of R396's {@code QualifiedSourceReferencePipelineTest}, which closed the same bug class
 * on the <em>source</em> {@code @table} shape. This item is the return-type member. The fixture is
 * the multi-schema jOOQ codegen output ({@code multischema_a} / {@code multischema_b}); the
 * cross-schema FK {@code multischema_b.gadget -> multischema_a.widget}
 * ({@code gadget_widget_id_fkey}) gives the shape directly, so no new DDL is needed.
 *
 * <p>Assertions land at the classifier/model surface (the parent classifies as a
 * {@link GraphitronType.TableType} and the field as a {@link ChildField.TableField}, not an
 * {@link GraphitronField.UnclassifiedField}); no code-string assertions. A genuine mismatch over the
 * same fixture (return type bound to {@code multischema_b.event} while the hop lands on
 * {@code widget}) still rejects, pinning that R422 tightened the compare without disabling it.
 */
@PipelineTier
class QualifiedReturnTypeReferencePipelineTest {

    private static final String MULTI_JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.multischemafixture";
    private static final String MULTI_OUTPUT_PACKAGE = "fake.code.generated.multischema";

    private static RewriteContext multiSchemaContext() {
        return new RewriteContext(
            List.of(), Path.of(""), Path.of(""),
            MULTI_OUTPUT_PACKAGE, MULTI_JOOQ_PACKAGE, Map.of());
    }

    /**
     * A Gadget parent bound to {@code multischema_b.gadget} whose {@code widget} field carries the
     * FK-key reference to the return type, plus a Widget return type whose {@code @table(name:)} is
     * {@code returnTable} (the axis under test) and an Event type on {@code multischema_b.event} for
     * the mismatch pairing.
     */
    private static String sdl(String returnType, String returnTable) {
        return """
            type Gadget @table(name: "gadget") {
                gadgetId: Int! @field(name: "gadget_id")
                widget: %s @reference(path: [{key: "gadget_widget_id_fkey"}])
            }
            type Widget @table(name: "%s") {
                widgetId: Int! @field(name: "widget_id")
                name: String!
            }
            type Event @table(name: "multischema_b.event") {
                eventId: Int! @field(name: "event_id")
                name: String!
            }
            type Query { gadgets: [Gadget!]! }
            """.formatted(returnType, returnTable);
    }

    @Test
    void qualifiedReturnTable_terminalHopLandsThere_classifiesAsTableField() {
        // Return type Widget bound to the schema-qualified "multischema_a.widget"; the terminal hop
        // resolves to jOOQ's unqualified canonical "widget". Pre-R422 the verbatim-echo compare
        // rejected this as Mismatch → UnclassifiedField; the identity compare classifies it green.
        var schema = TestSchemaHelper.buildSchema(
            sdl("Widget", "multischema_a.widget"), multiSchemaContext());

        assertThat(schema.type("Gadget"))
            .as("parent must classify as a table type")
            .isInstanceOf(GraphitronType.TableType.class);

        var widgetField = schema.field("Gadget", "widget");
        assertThat(widgetField)
            .as("@reference field with schema-qualified return @table must resolve to a TableField, "
                + "not become UnclassifiedField on a spurious terminal Mismatch")
            .isInstanceOf(ChildField.TableField.class);

        // The terminal hop genuinely lands on widget: the classification is not vacuous.
        var terminal = ((ChildField.TableField) widgetField).joinPath().getLast();
        assertThat(TestFixtures.fkHop(terminal).targetTable().tableClass())
            .isEqualTo(no.sikt.graphitron.javapoet.ClassName.get(
                MULTI_JOOQ_PACKAGE + ".multischema_a.tables", "Widget"));
    }

    @Test
    void genuineMismatch_terminalHopLandsElsewhere_stillRejects() {
        // Return type Event (multischema_b.event), but the FK key lands the terminal hop on widget.
        // The tightened compare must still reject this as a terminal-target Mismatch.
        var schema = TestSchemaHelper.buildSchema(
            sdl("Event", "multischema_a.widget"), multiSchemaContext());

        var widgetField = schema.field("Gadget", "widget");
        assertThat(widgetField)
            .as("terminal hop landing on widget while the return type is bound to event must still "
                + "reject; R422 tightened the compare, it did not disable it")
            .isInstanceOf(GraphitronField.UnclassifiedField.class);
    }
}
