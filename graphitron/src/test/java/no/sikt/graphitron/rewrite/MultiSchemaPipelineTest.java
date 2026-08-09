package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.ArrayTypeName;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.JavaFile;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SDL → classified schema → generated {@code TypeSpec} pipeline coverage for the
 * multi-schema jOOQ fixture. The fixture makes one bug class visible: imports emitted as
 * {@code <jooqPackage>.tables.X}, dropping the schema segment. Single-schema fixtures
 * cannot reproduce it because their generated jOOQ classes live at the root package; this
 * fixture distributes them across {@code multischema_a.tables.*} and
 * {@code multischema_b.tables.*}.
 *
 * <p>Three shape cases: unqualified-and-unique resolution ({@code Widget}), qualified
 * resolution of a table name present in both schemas ({@code Event}), and a cross-schema
 * FK traversal ({@code Gadget.widget}) that must route through the FK-holder schema's
 * {@code Keys} class.
 *
 * <p>Assertions land at two typed surfaces: model-level ({@link no.sikt.graphitron.rewrite.model.TableRef}
 * / {@link no.sikt.graphitron.rewrite.model.ForeignKeyRef} / the FK-derived {@link JoinStep.Hop}),
 * the slots every emitted {@code ClassName} flows from, so model correctness propagates to
 * emit correctness by construction; and structural emit-side, walked through JavaPoet's typed
 * graph plus the parsed import list. The pipeline tier bans code-string assertions over
 * rendered method bodies (per {@code development-principles.adoc}), and a substring scan over
 * {@code JavaFile.toString()} would be that ban dressed up as FQN inspection.
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
            widgets(name: String @field(name: "name")): [Widget!]!
            events(name: String @field(name: "name")): [Event!]!
            gadgets(note: String @field(name: "note")): [Gadget!]!
        }
        """;

    private static RewriteContext multiSchemaContext() {
        return new RewriteContext(
            List.of(),
            Path.of(""), "MultiSchemaPipelineTest",
            Path.of(""),
            MULTI_OUTPUT_PACKAGE,
            MULTI_JOOQ_PACKAGE
        );
    }

    private static GraphitronSchema buildSchema() {
        return TestSchemaHelper.buildSchema(SDL, multiSchemaContext());
    }

    // ---- Model-level: schema-segmented TableRef.tableClass per resolution mode ----

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
        // Unqualified "event" is ambiguous (exists in both schemas); the qualified form
        // pins it to multischema_a.
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

    // ---- Model-level: cross-schema FK routes to FK-holder Keys, target lands on FK-target schema ----

    @Test
    void gadgetWidget_fkReferenceRoutesToFkHolderKeysAndFkTargetTableClass() {
        var schema = buildSchema();
        var widgetField = (ChildField.TableField) schema.field("Gadget", "widget");
        var firstHop = TestFixtures.fkHop(widgetField.joinPath().get(0));
        var firstPairs = TestFixtures.fkPairs(widgetField.joinPath().get(0));

        // The FK constraint is held on multischema_b (gadget's schema); the lookup routes
        // to the FK-holder side (B), not the target side (A). The bug shape is a
        // per-emit-site `ClassName.get(jooqPackage, "Keys")` compiling to root.Keys,
        // a class which does not exist under multi-schema codegen.
        assertThat(TestFixtures.fkRef(firstPairs).keysClass())
            .isEqualTo(ClassName.get(MULTI_JOOQ_PACKAGE + ".multischema_b", "Keys"));
        // Stock JavaGenerator names the constant <TABLE>__<FK_NAME> uppercased; pin the
        // upper-cased SQL constraint name as the suffix to avoid coupling to the table prefix.
        assertThat(TestFixtures.fkRef(firstPairs).constantName()).endsWith("GADGET_WIDGET_ID_FKEY");

        // Every emitter that traverses the FK reads firstHop.targetTable().tableClass()
        // to bind the joined-table alias; a regression that re-derives it from the bare
        // jooqPackage emits root.tables.Widget here.
        assertThat(firstHop.targetTable().tableClass())
            .isEqualTo(ClassName.get(MULTI_JOOQ_PACKAGE + ".multischema_a.tables", "Widget"));
    }

    // ---- Typed-graph emit walk: every fixture-bound ClassName lives under a schema sub-package ----

    @Test
    void everyFixtureBoundClassNameInEmittedTypeSpecsLivesUnderASchemaSubPackage() {
        var schema = buildSchema();

        for (TypeSpec spec : allEmittedTypeSpecs(schema)) {
            for (ClassName cn : referencedClassNames(spec)) {
                if (!cn.canonicalName().startsWith(MULTI_JOOQ_PACKAGE + ".")) continue;
                // Every multi-schema fixture reference must be under multischema_a.* or
                // multischema_b.*; the bare-root forms (jooqPackage.tables.X, jooqPackage.Keys,
                // jooqPackage.Tables) are exactly the bug shape and must never appear.
                assertThat(cn.packageName())
                    .as("ClassName %s in TypeSpec %s lives at %s; expected a multischema_a / "
                        + "multischema_b sub-package",
                        cn.canonicalName(), spec.name(), cn.packageName())
                    .startsWith(MULTI_JOOQ_PACKAGE + ".multischema_");
            }
        }
    }

    // ---- Typed-graph emit walk: each shape case lands its expected ClassName somewhere ----

    @Test
    void typeClasses_emitTheirBackingTableClassAsAReachableClassName() {
        var schema = buildSchema();
        // The backing jOOQ table's ClassName is reachable from the TypeSpec graph whether
        // JavaPoet imports it or inlines it (simple-name collision with the GraphQL type,
        // e.g. the Widget typeclass referencing the jOOQ Widget).
        assertThat(referencedClassNames(typeClass(schema, "Widget")))
            .contains(ClassName.get(MULTI_JOOQ_PACKAGE + ".multischema_a.tables", "Widget"));
        assertThat(referencedClassNames(typeClass(schema, "Event")))
            .contains(ClassName.get(MULTI_JOOQ_PACKAGE + ".multischema_a.tables", "Event"));
        assertThat(referencedClassNames(typeClass(schema, "Gadget")))
            .contains(ClassName.get(MULTI_JOOQ_PACKAGE + ".multischema_b.tables", "Gadget"));
    }

    @Test
    void queryConditions_emitsAllThreeBackingTableClassesAsReachableClassNames() {
        var schema = buildSchema();
        var queryConditions = ConditionRenderTestSupport.renderCommittedConditions(schema, MULTI_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals("QueryConditions"))
            .findFirst()
            .orElseThrow();

        // The three Query roots span both schemas, so all three schema-segmented backing
        // tables surface as method parameter types, picked up by the typed walk (no string
        // scanning, no JavaPoet import-vs-inline coin-flip).
        assertThat(referencedClassNames(queryConditions))
            .contains(
                ClassName.get(MULTI_JOOQ_PACKAGE + ".multischema_a.tables", "Widget"),
                ClassName.get(MULTI_JOOQ_PACKAGE + ".multischema_a.tables", "Event"),
                ClassName.get(MULTI_JOOQ_PACKAGE + ".multischema_b.tables", "Gadget"));
    }

    // ---- helpers ----

    private static TypeSpec typeClass(GraphitronSchema schema, String typeName) {
        return ProjectionRenderTestSupport.renderProjections(schema, MULTI_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals(typeName))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no projection unit named " + typeName + " was rendered"));
    }

    /**
     * Every {@link TypeSpec} the fixture SDL produces across the two emit surfaces covered
     * here (TypeClass + QueryConditions).
     */
    private static List<TypeSpec> allEmittedTypeSpecs(GraphitronSchema schema) {
        var out = new java.util.ArrayList<TypeSpec>();
        out.addAll(ProjectionRenderTestSupport.renderProjections(schema, MULTI_OUTPUT_PACKAGE));
        out.addAll(ConditionRenderTestSupport.renderCommittedConditions(schema, MULTI_OUTPUT_PACKAGE));
        return out;
    }

    /**
     * Every {@link ClassName} reachable from the TypeSpec's structurally-typed surfaces
     * (return, parameter, exception, and field types) plus the parsed import list of the
     * rendered {@link JavaFile}. ClassNames appearing only inline inside CodeBlocks are not
     * reachable here, but every such reference flows from a typed model slot
     * ({@link no.sikt.graphitron.rewrite.model.TableRef#tableClass()},
     * {@link no.sikt.graphitron.rewrite.model.ForeignKeyRef#keysClass()},
     * {@link JoinStep.Hop#targetTable()}) pinned by the model-level assertions above.
     */
    private static Set<ClassName> referencedClassNames(TypeSpec spec) {
        var out = new LinkedHashSet<ClassName>();
        for (var method : spec.methodSpecs()) {
            collectClassNames(method.returnType(), out);
            for (var param : method.parameters()) {
                collectClassNames(param.type(), out);
            }
            for (var ex : method.exceptions()) {
                collectClassNames(ex, out);
            }
        }
        for (var field : spec.fieldSpecs()) {
            collectClassNames(field.type(), out);
        }
        out.addAll(parsedImports(spec));
        return out;
    }

    /** Recursively unpacks a {@link TypeName} into the concrete {@link ClassName}s it mentions. */
    private static void collectClassNames(TypeName type, Set<ClassName> out) {
        if (type == null) return;
        switch (type) {
            case ClassName cn -> out.add(cn);
            case ParameterizedTypeName pt -> {
                out.add(pt.rawType());
                for (var arg : pt.typeArguments()) collectClassNames(arg, out);
            }
            case ArrayTypeName at -> collectClassNames(at.componentType(), out);
            case WildcardTypeName wt -> {
                for (var b : wt.upperBounds()) collectClassNames(b, out);
                for (var b : wt.lowerBounds()) collectClassNames(b, out);
            }
            default -> {
                // TypeVariableName / primitive / void: no concrete ClassName to surface.
            }
        }
    }

    /**
     * Parses {@code import <fqn>;} lines out of the rendered {@link JavaFile} into typed
     * {@link ClassName} values. Imports are a structurally-typed surface (one FQN per line,
     * no body content), so parsing them stays inside the tier's no-body-string rule.
     */
    private static List<ClassName> parsedImports(TypeSpec spec) {
        String rendered = JavaFile.builder(MULTI_OUTPUT_PACKAGE, spec).indent("    ").build().toString();
        return rendered.lines()
            .map(String::trim)
            .filter(l -> l.startsWith("import ") && l.endsWith(";"))
            .map(l -> l.substring("import ".length(), l.length() - 1))
            .map(l -> l.startsWith("static ") ? l.substring("static ".length()) : l)
            .map(MultiSchemaPipelineTest::classNameFromCanonical)
            .toList();
    }

    /**
     * Rebuilds a canonical FQN as a {@link ClassName}. The {@code static } prefix is
     * stripped upstream, so the input is always a top-level type name.
     */
    private static ClassName classNameFromCanonical(String canonical) {
        int dot = canonical.lastIndexOf('.');
        if (dot < 0) return ClassName.get("", canonical);
        return ClassName.get(canonical.substring(0, dot), canonical.substring(dot + 1));
    }
}
