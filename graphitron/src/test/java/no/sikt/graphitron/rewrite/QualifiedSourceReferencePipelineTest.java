package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.ClassName;
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
 * R396 pipeline coverage: a {@code @reference} field on a type whose {@code @table(name:)} carries
 * a schema prefix (and/or a case difference from the real lowercase catalog name) must classify
 * to a {@link JoinStep.FkJoin} with the correct origin/target identity and slot orientation, and
 * must <em>not</em> reject at schema-validation time.
 *
 * <p>The fixture is the multi-schema jOOQ codegen output ({@code multischema_a} / {@code multischema_b}).
 * {@code signal} and {@code widget} both live in {@code multischema_a}; the FK
 * {@code signal_widget_id_fkey} sits on {@code signal.widget_id → widget.widget_id}. jOOQ renders
 * both endpoint names unqualified, so the pre-R396 bare {@code equalsIgnoreCase} comparison of the
 * verbatim {@code @table} echo ({@code "multischema_a.signal"}) against jOOQ's {@code "signal"}
 * rejected the FK as "does not connect", and — where a partial fix let it through — silently
 * mis-oriented the join. This test drives all three {@code @reference} directive forms
 * ({@code {key:}}, {@code {table:}}, and empty inference) plus the schema-qualified-and-upper-case
 * spelling through the classifier and pins the resulting model.
 *
 * <p>Assertions land at the model surface the emitter consumes: {@link JoinStep.FkJoin#originTable()}
 * / {@link JoinStep.FkJoin#targetTable()} identity and {@link JoinStep.FkJoin#sourceSideColumns()} /
 * {@link JoinStep.FkJoin#targetSideColumns()} orientation. The parent type must classify as a
 * {@link GraphitronType.TableType} and the field as a {@link ChildField.TableField} (not a
 * {@link GraphitronField.UnclassifiedField}) — the "no author error" half of the invariant.
 */
@PipelineTier
class QualifiedSourceReferencePipelineTest {

    private static final String MULTI_JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.multischemafixture";
    private static final String MULTI_OUTPUT_PACKAGE = "fake.code.generated.multischema";

    private static final ClassName SIGNAL =
        ClassName.get(MULTI_JOOQ_PACKAGE + ".multischema_a.tables", "Signal");
    private static final ClassName WIDGET =
        ClassName.get(MULTI_JOOQ_PACKAGE + ".multischema_a.tables", "Widget");

    private static RewriteContext multiSchemaContext() {
        return new RewriteContext(
            List.of(), Path.of(""), Path.of(""),
            MULTI_OUTPUT_PACKAGE, MULTI_JOOQ_PACKAGE, Map.of());
    }

    /** A Signal type bound to a schema-qualified @table plus a Widget carrying its own @table, with
     *  {@code fieldDecl} the @reference-bearing widget field under test. */
    private static String sdl(String signalTable, String fieldDecl) {
        return """
            type Signal @table(name: "%s") {
                signalId: Int! @field(name: "signal_id")
                %s
            }
            type Widget @table(name: "widget") {
                widgetId: Int! @field(name: "widget_id")
                name: String!
            }
            type Query { signals: [Signal!]! }
            """.formatted(signalTable, fieldDecl);
    }

    private static JoinStep.FkJoin firstHop(String signalTable, String fieldDecl) {
        var schema = TestSchemaHelper.buildSchema(sdl(signalTable, fieldDecl), multiSchemaContext());
        // No author error: the parent classifies as a table type and the field as a table field.
        assertThat(schema.type("Signal"))
            .as("schema-qualified @table parent must not fail classification")
            .isInstanceOf(GraphitronType.TableType.class);
        var widgetField = schema.field("Signal", "widget");
        assertThat(widgetField)
            .as("@reference field must resolve, not become UnclassifiedField")
            .isInstanceOf(ChildField.TableField.class);
        return (JoinStep.FkJoin) ((ChildField.TableField) widgetField).joinPath().get(0);
    }

    private static void assertOrientedSignalToWidget(JoinStep.FkJoin hop) {
        assertThat(hop.fk().sqlName()).isEqualToIgnoringCase("signal_widget_id_fkey");
        assertThat(hop.originTable().tableClass()).isEqualTo(SIGNAL);
        assertThat(hop.targetTable().tableClass()).isEqualTo(WIDGET);
        assertThat(hop.sourceSideColumns()).extracting(c -> c.sqlName()).containsExactly("widget_id");
        assertThat(hop.targetSideColumns()).extracting(c -> c.sqlName()).containsExactly("widget_id");
    }

    // ---- Phase 1: the {key:} form (the reported author-error path) ----

    @Test
    void keyForm_qualifiedSource_resolvesAndOrients() {
        assertOrientedSignalToWidget(firstHop(
            "multischema_a.signal",
            "widget: Widget @reference(path: [{key: \"signal_widget_id_fkey\"}])"));
    }

    @Test
    void keyForm_qualifiedUpperCaseSource_resolvesAndOrients() {
        // Schema-qualified + upper-case, the R395 execution fixture's originally-specified spelling.
        assertOrientedSignalToWidget(firstHop(
            "multischema_a.SIGNAL",
            "widget: Widget @reference(path: [{key: \"signal_widget_id_fkey\"}])"));
    }

    // ---- Phase 2: the {table:} form and empty inference (same author shape, shared primitive) ----

    @Test
    void tableForm_qualifiedSource_resolvesAndOrients() {
        assertOrientedSignalToWidget(firstHop(
            "multischema_a.signal",
            "widget: Widget @reference(path: [{table: \"widget\"}])"));
    }

    @Test
    void emptyInference_qualifiedSource_resolvesAndOrients() {
        // Empty path: the directive is present but names no element, so parsePath infers the
        // single FK between the (schema-qualified) parent table and the return type's table.
        assertOrientedSignalToWidget(firstHop(
            "multischema_a.signal",
            "widget: Widget @reference(path: [])"));
    }
}
