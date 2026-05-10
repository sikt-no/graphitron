package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.JavaFile;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.generators.QueryConditionsGenerator;
import no.sikt.graphitron.rewrite.generators.TypeClassGenerator;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SDL → classified schema → generated {@code TypeSpec} pipeline coverage for the
 * multi-schema jOOQ fixture R78 introduced. The fixture's whole point is to make the
 * R78 bug class ("imports emitted as {@code <jooqPackage>.tables.X}, dropping the schema
 * segment") visible at the tier where it actually lives. Single-schema fixtures cannot
 * reproduce the bug because their generated jOOQ classes happen to live at the root
 * package; the multi-schema fixture distributes them across {@code multischema_a.tables.*}
 * and {@code multischema_b.tables.*}.
 *
 * <p>Three shape cases:
 * <ul>
 *   <li>The unique-per-schema case: a {@code @table(name: "widget")} type resolves to
 *       {@code multischema_a.tables.Widget} via the unqualified-and-unique branch.</li>
 *   <li>The shared-table case requiring qualification: a {@code @table(name: "multischema_a.event")}
 *       type resolves through the qualified branch (unqualified {@code "event"} is ambiguous).</li>
 *   <li>The cross-schema FK traversal: a {@code Gadget.widget} field whose
 *       {@code @reference(path:[{key:"gadget_widget_id_fkey"}])} routes through the
 *       FK-holder schema's {@code Keys} class ({@code multischema_b.Keys}), not the
 *       FK-target schema's.</li>
 * </ul>
 *
 * <p>Assertions land at the pipeline-tier shape: {@link no.sikt.graphitron.rewrite.model.TableRef#tableClass()}
 * for the table-class wiring, {@link no.sikt.graphitron.rewrite.model.ForeignKeyRef#keysClass()}
 * for the cross-schema FK, and the rendered {@link JavaFile} text for the structural
 * fingerprint of {@code TypeClass} / {@code QueryConditions} emission. The text-substring
 * search subsumes "imports list" inspection because JavaPoet inlines an FQN as a fully-
 * qualified reference whenever the simple class name collides with the enclosing TypeSpec's
 * own name (e.g. the {@code Widget} TypeSpec inlining {@code multischema_a.tables.Widget}
 * rather than importing it). What matters for the regression signal is that the schema
 * segment survives into emission somewhere; whether the survival path is import or inline
 * is a JavaPoet implementation detail and not load-bearing for R78. Body-content scanning
 * for behaviour is still banned at every tier (per {@code rewrite-design-principles.adoc}
 * "Pipeline tests are the primary behavioural tier"); these assertions are over the
 * structural FQN-shape fingerprint, not over generated-method behaviour.
 */
@PipelineTier
class MultiSchemaPipelineTest {

    private static final String MULTI_JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.multischemafixture";
    private static final String MULTI_OUTPUT_PACKAGE = "fake.code.generated.multischema";

    private static final String SDL = """
        type Widget @table(name: "widget") {
            widgetId: Int! @field(name: "widget_id")
            name: String!
        }

        type Event @table(name: "multischema_a.event") {
            eventId: Int! @field(name: "event_id")
            name: String!
        }

        type Gadget @table(name: "gadget") {
            gadgetId: Int! @field(name: "gadget_id")
            note: String
            widget: Widget @reference(path: [{key: "gadget_widget_id_fkey"}])
        }

        type Query {
            widgets: [Widget!]!
            events: [Event!]!
            gadgets: [Gadget!]!
        }
        """;

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

    private static GraphitronSchema buildSchema() {
        return TestSchemaHelper.buildSchema(SDL, multiSchemaContext());
    }

    // ---- TableRef shape: schema-segmented tableClass per resolution mode ----

    @Test
    void widgetTable_resolvesUnqualifiedToSchemaSegmentedFqn() {
        var schema = buildSchema();
        var widget = (GraphitronType.TableType) schema.type("Widget");
        assertThat(widget.table().tableClass())
            .isEqualTo(ClassName.get(MULTI_JOOQ_PACKAGE + ".multischema_a.tables", "Widget"));
        assertThat(widget.table().recordClass())
            .isEqualTo(ClassName.get(MULTI_JOOQ_PACKAGE + ".multischema_a.tables.records", "WidgetRecord"));
        assertThat(widget.table().constantsClass())
            .isEqualTo(ClassName.get(MULTI_JOOQ_PACKAGE + ".multischema_a", "Tables"));
    }

    @Test
    void eventTable_resolvesQualifiedToNamedSchema() {
        var schema = buildSchema();
        var event = (GraphitronType.TableType) schema.type("Event");
        // The unqualified "event" lookup would Ambiguous (collides between multischema_a and
        // multischema_b); the qualified form pins it to multischema_a.
        assertThat(event.table().tableClass())
            .isEqualTo(ClassName.get(MULTI_JOOQ_PACKAGE + ".multischema_a.tables", "Event"));
        assertThat(event.table().constantsClass())
            .isEqualTo(ClassName.get(MULTI_JOOQ_PACKAGE + ".multischema_a", "Tables"));
    }

    @Test
    void gadgetTable_resolvesUnqualifiedAcrossSchemaBoundary() {
        var schema = buildSchema();
        var gadget = (GraphitronType.TableType) schema.type("Gadget");
        // gadget is unique to multischema_b; the unqualified resolver lands on B without a
        // qualifier, so the FQN must follow the FK-holder's schema, not the default.
        assertThat(gadget.table().tableClass())
            .isEqualTo(ClassName.get(MULTI_JOOQ_PACKAGE + ".multischema_b.tables", "Gadget"));
        assertThat(gadget.table().constantsClass())
            .isEqualTo(ClassName.get(MULTI_JOOQ_PACKAGE + ".multischema_b", "Tables"));
    }

    // ---- ForeignKeyRef shape: cross-schema FK routes to FK-holder's Keys ----

    @Test
    void gadgetWidget_fkReferenceRoutesToFkHolderSchemaKeysClass() {
        var schema = buildSchema();
        var widgetField = (ChildField.TableField) schema.field("Gadget", "widget");
        var firstHop = (JoinStep.FkJoin) widgetField.joinPath().get(0);

        // The FK constraint is held on multischema_b (gadget's schema), targets multischema_a
        // (widget's schema). The Keys-class lookup routes to the FK-holder side (B), not the
        // target side (A) — this is the R78 bug case: a per-emit-site
        // ClassName.get(jooqPackage, "Keys") would compile to root.Keys (no Keys class in
        // root under multi-schema codegen).
        assertThat(firstHop.fk().keysClass())
            .isEqualTo(ClassName.get(MULTI_JOOQ_PACKAGE + ".multischema_b", "Keys"));
        // Stock JavaGenerator names the constant <TABLE>__<FK_NAME> uppercased; pin the
        // upper-cased SQL constraint name as the suffix to avoid coupling to the table prefix.
        assertThat(firstHop.fk().constantName()).endsWith("GADGET_WIDGET_ID_FKEY");
    }

    // ---- Structural fingerprint: schema-segmented FQNs survive into rendered emission ----

    @Test
    void typeClasses_renderedTextCarriesSchemaSegmentedTableClasses() {
        var schema = buildSchema();
        // Each TypeClass references its own backing jOOQ table as the second arg of the
        // generated `$fields` method; schema-segmented FQNs must appear in the rendered text
        // (whether as imports or as inline FQNs — JavaPoet inlines when the GraphQL TypeSpec
        // name collides with the jOOQ class simple name, e.g. `Widget` typeclass inlining
        // `<root>.multischema_a.tables.Widget`).
        assertThat(rendered(typeClass(schema, "Widget")))
            .contains(MULTI_JOOQ_PACKAGE + ".multischema_a.tables.Widget");
        assertThat(rendered(typeClass(schema, "Event")))
            .contains(MULTI_JOOQ_PACKAGE + ".multischema_a.tables.Event");
        assertThat(rendered(typeClass(schema, "Gadget")))
            .contains(MULTI_JOOQ_PACKAGE + ".multischema_b.tables.Gadget");
    }

    @Test
    void gadgetTypeClass_crossSchemaFkInlineProjectionCarriesBothSchemas() {
        // Gadget.$fields emits the `widget` field as an inline `multiset(select(...))`
        // referencing the FK target table (multischema_a.widget) plus the schema-A Tables
        // constants class. The cross-schema FK traversal is the case R78 fixes: a regression
        // that re-derives the ClassName from the bare jooqPackage emits the wrong segment
        // (root.tables.Widget / root.Tables) here.
        var rendered = rendered(typeClass(buildSchema(), "Gadget"));
        assertThat(rendered)
            .contains(MULTI_JOOQ_PACKAGE + ".multischema_b.tables.Gadget")
            .contains(MULTI_JOOQ_PACKAGE + ".multischema_a.tables.Widget")
            .contains(MULTI_JOOQ_PACKAGE + ".multischema_a.Tables");

        // Negative half of the fingerprint: nothing under the multi-schema fixture should
        // land at the root package. The R78 bug emitted `<root>.tables.Widget` here; the
        // assertions above pass when the FQN is correct, but we also pin the wrong shape's
        // absence so a future regression that adds the schema segment to an existing miss
        // is caught even if it leaves the right shape in place.
        assertThat(rendered)
            .doesNotContain(" " + MULTI_JOOQ_PACKAGE + ".tables.Widget")
            .doesNotContain(" " + MULTI_JOOQ_PACKAGE + ".tables.Gadget")
            .doesNotContain(" " + MULTI_JOOQ_PACKAGE + ".Tables ");
    }

    @Test
    void queryConditions_renderedImportsCarrySchemaSegmentedTableClassesForAllThreeTables() {
        var schema = buildSchema();
        var queryConditions = QueryConditionsGenerator.generate(schema, MULTI_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals("QueryConditions"))
            .findFirst()
            .orElseThrow();

        // QueryConditions has no name collision with the jOOQ table classes (its own simple
        // name is `QueryConditions`), so the three table classes import cleanly. This is the
        // classic "imports list as structural fingerprint" form: all three schema-segmented
        // FQNs land as `import` lines, one per Query-root return type.
        var imports = renderedImports(queryConditions);
        assertThat(imports)
            .contains(MULTI_JOOQ_PACKAGE + ".multischema_a.tables.Widget")
            .contains(MULTI_JOOQ_PACKAGE + ".multischema_a.tables.Event")
            .contains(MULTI_JOOQ_PACKAGE + ".multischema_b.tables.Gadget");
    }

    // ---- helpers ----

    private static TypeSpec typeClass(GraphitronSchema schema, String typeName) {
        return TypeClassGenerator.generate(schema, MULTI_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals(typeName))
            .findFirst()
            .orElseThrow(() -> new AssertionError("TypeClassGenerator did not emit " + typeName));
    }

    /**
     * Renders the {@link TypeSpec} as a fully-qualified Java source string under the same
     * {@code outputPackage} the production pipeline writes it to. The text contains both
     * import declarations and inline FQN references (where JavaPoet preferred inlining over
     * importing); pipeline assertions search the combined surface for schema-segmented FQNs.
     */
    private static String rendered(TypeSpec spec) {
        return JavaFile.builder(MULTI_OUTPUT_PACKAGE, spec).indent("    ").build().toString();
    }

    /**
     * Parses {@code import <fqn>;} lines out of a rendered {@link TypeSpec}. Useful when the
     * TypeSpec's own simple name is known not to collide with anything it references, so all
     * referenced classes import cleanly (e.g. {@code QueryConditions} as a simple name).
     */
    private static List<String> renderedImports(TypeSpec spec) {
        return rendered(spec).lines()
            .map(String::trim)
            .filter(l -> l.startsWith("import ") && l.endsWith(";"))
            .map(l -> l.substring("import ".length(), l.length() - 1))
            .map(l -> l.startsWith("static ") ? l.substring("static ".length()) : l)
            .toList();
    }
}
