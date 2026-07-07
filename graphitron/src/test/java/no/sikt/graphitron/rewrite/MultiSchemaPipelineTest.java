package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.ArrayTypeName;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.JavaFile;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.generators.QueryConditionsGenerator;
import no.sikt.graphitron.rewrite.generators.TypeClassGenerator;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 *       FK-target schema's, and whose target table reference is
 *       {@code multischema_a.tables.Widget}.</li>
 * </ul>
 *
 * <p>Assertions land at two typed surfaces. First: model-level ({@link no.sikt.graphitron.rewrite.model.TableRef}
 * / {@link no.sikt.graphitron.rewrite.model.ForeignKeyRef} / {@link JoinStep.FkJoin}), which
 * is the value the emitter consumes — every {@code ClassName} the generator emits flows from
 * one of these slots, so model correctness propagates to emit correctness by construction.
 * Second: structural emit-side ({@code TypeSpec.methodSpecs[].parameters[].type},
 * {@code returnType}, {@code fieldSpecs[].type}, plus the parsed import list), walked through
 * JavaPoet's typed graph. The pipeline tier bans code-string assertions over rendered method
 * bodies (per {@code development-principles.adoc} "Behaviour is pinned at the pipeline
 * tier and above"); a substring scan over {@code JavaFile.toString()} would be that ban
 * dressed up as FQN inspection. The typed-graph walk and the model assertions together cover
 * what a regression that re-derives a {@code ClassName} from the bare {@code jooqPackage}
 * could break, without crossing the line.
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

    // ---- Model-level: cross-schema FK routes to FK-holder Keys, target lands on FK-target schema ----

    @Test
    void gadgetWidget_fkReferenceRoutesToFkHolderKeysAndFkTargetTableClass() {
        var schema = buildSchema();
        var widgetField = (ChildField.TableField) schema.field("Gadget", "widget");
        var firstHop = TestFixtures.fkHop(widgetField.joinPath().get(0));
        var firstPairs = TestFixtures.fkPairs(widgetField.joinPath().get(0));

        // Keys class: FK constraint is held on multischema_b (gadget's schema); the lookup
        // routes to the FK-holder side (B), not the target side (A). The R78 bug case is a
        // per-emit-site `ClassName.get(jooqPackage, "Keys")` that compiles to root.Keys —
        // a class which does not exist under multi-schema codegen.
        assertThat(firstPairs.fk().keysClass())
            .isEqualTo(ClassName.get(MULTI_JOOQ_PACKAGE + ".multischema_b", "Keys"));
        // Stock JavaGenerator names the constant <TABLE>__<FK_NAME> uppercased; pin the
        // upper-cased SQL constraint name as the suffix to avoid coupling to the table prefix.
        assertThat(firstPairs.fk().constantName()).endsWith("GADGET_WIDGET_ID_FKEY");

        // Target-table class: every emitter that traverses the FK reads
        // firstHop.targetTable().tableClass() to bind the joined-table alias. R78's bug
        // class includes this surface: a regression that re-derives the target table's
        // ClassName from the bare jooqPackage emits root.tables.Widget here.
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
                // jooqPackage.Tables) are exactly the R78 bug shape and must never appear.
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
        // Each TypeClass takes its backing jOOQ table as the second arg of `$fields`; the
        // ClassName is reachable from the TypeSpec graph regardless of whether JavaPoet
        // imports it (no name collision, e.g. QueryConditions) or inlines it (collision with
        // the GraphQL type's own simple name, e.g. the Widget typeclass referencing the jOOQ
        // Widget). Either way the typed walk finds the FQN.
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
        var queryConditions = QueryConditionsGenerator.generate(schema, MULTI_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals("QueryConditions"))
            .findFirst()
            .orElseThrow();

        // QueryConditions has one method per Query-root that returns a table-bound type; each
        // method takes that type's backing jOOQ table as a parameter. The three Query roots
        // (widgets / events / gadgets) span both schemas, so all three schema-segmented
        // ClassNames surface as parameter types — picked up by the typed walk over method
        // parameters (no string scanning, no JavaPoet import-vs-inline coin-flip).
        assertThat(referencedClassNames(queryConditions))
            .contains(
                ClassName.get(MULTI_JOOQ_PACKAGE + ".multischema_a.tables", "Widget"),
                ClassName.get(MULTI_JOOQ_PACKAGE + ".multischema_a.tables", "Event"),
                ClassName.get(MULTI_JOOQ_PACKAGE + ".multischema_b.tables", "Gadget"));
    }

    // ---- helpers ----

    private static TypeSpec typeClass(GraphitronSchema schema, String typeName) {
        return TypeClassGenerator.generate(schema, MULTI_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals(typeName))
            .findFirst()
            .orElseThrow(() -> new AssertionError("TypeClassGenerator did not emit " + typeName));
    }

    /**
     * Every {@link TypeSpec} the multischema-fixture SDL produces across the three emit
     * surfaces R83 covers (TypeClass + QueryConditions). The pipeline test's negative-form
     * sweep walks every spec; positive-form tests reach in for specific ones.
     */
    private static List<TypeSpec> allEmittedTypeSpecs(GraphitronSchema schema) {
        var out = new java.util.ArrayList<TypeSpec>();
        out.addAll(TypeClassGenerator.generate(schema, MULTI_OUTPUT_PACKAGE));
        out.addAll(QueryConditionsGenerator.generate(schema, MULTI_OUTPUT_PACKAGE));
        return out;
    }

    /**
     * Every {@link ClassName} reachable from the TypeSpec's structurally-typed surfaces
     * (method return types, parameter types, declared exceptions, field types) plus the
     * parsed import list of the rendered {@link JavaFile}. The structurally-typed half
     * covers parameters and field declarations regardless of JavaPoet's import-vs-inline
     * decision; the imports half captures classes referenced only inside method bodies
     * (CodeBlock {@code $T} substitutions) when JavaPoet imported them. ClassNames that
     * appear only inline inside CodeBlocks are not directly reachable here, but every
     * such reference flows from a typed model slot ({@link no.sikt.graphitron.rewrite.model.TableRef#tableClass()},
     * {@link no.sikt.graphitron.rewrite.model.ForeignKeyRef#keysClass()},
     * {@link JoinStep.FkJoin#targetTable()}) whose correctness is pinned by the model-level
     * assertions above; correctness propagates from the model into the emit by construction.
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

    /**
     * Recursively unpacks a {@link TypeName} into the concrete {@link ClassName}s it
     * mentions: parameterised type's raw + arguments, array's component type, wildcard's
     * upper and lower bounds. Primitive and void TypeNames are silently skipped (they
     * carry no FQN of interest to the multi-schema sweep).
     */
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
                // TypeVariableName / primitive / void — no concrete ClassName to surface.
            }
        }
    }

    /**
     * Parses {@code import <fqn>;} lines out of the rendered {@link JavaFile} into typed
     * {@link ClassName} values. Imports are a structurally-typed surface (one FQN per
     * line, no body content), so parsing them is principle-aligned even though the carrier
     * is a string.
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
     * Splits a canonical FQN into its package and simple-name halves and rebuilds it as a
     * {@link ClassName}. Static imports of nested members (the {@code static }-prefixed
     * import form) have already been stripped upstream, so the input is always a top-level
     * type name.
     */
    private static ClassName classNameFromCanonical(String canonical) {
        int dot = canonical.lastIndexOf('.');
        if (dot < 0) return ClassName.get("", canonical);
        return ClassName.get(canonical.substring(0, dot), canonical.substring(dot + 1));
    }
}
