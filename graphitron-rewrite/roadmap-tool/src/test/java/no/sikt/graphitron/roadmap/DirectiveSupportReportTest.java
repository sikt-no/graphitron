package no.sikt.graphitron.roadmap;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the SDL directive parser. The fixtures here cover the surface
 * actually used by the two {@code directives.graphqls} files we compare:
 * single-line and multi-line directive declarations, optional argument
 * lists, list/non-null type modifiers, default values, inline
 * {@code @deprecated} on arguments, and block-string / line-comment
 * stripping. Nothing in here exercises the full GraphQL surface; if the
 * directive files later grow shapes we don't cover, that's a parser
 * extension and a new test.
 */
class DirectiveSupportReportTest {

    @Test
    void parsesArgumentlessDirective() {
        var ds = DirectiveSupportReport.parseDirectives("directive @splitQuery on FIELD_DEFINITION");
        assertThat(ds).hasSize(1);
        assertThat(ds.get(0).name()).isEqualTo("splitQuery");
        assertThat(ds.get(0).args()).isEmpty();
        assertThat(ds.get(0).locations()).containsExactly("FIELD_DEFINITION");
    }

    @Test
    void parsesMultipleLocations() {
        var ds = DirectiveSupportReport.parseDirectives(
            "directive @notGenerated on FIELD_DEFINITION | ARGUMENT_DEFINITION | INPUT_FIELD_DEFINITION | INTERFACE | UNION");
        assertThat(ds.get(0).locations()).containsExactly(
            "FIELD_DEFINITION", "ARGUMENT_DEFINITION", "INPUT_FIELD_DEFINITION", "INTERFACE", "UNION");
    }

    @Test
    void parsesSingleLineArguments() {
        var ds = DirectiveSupportReport.parseDirectives(
            "directive @field(name: String!, javaName: String) on FIELD_DEFINITION");
        var args = ds.get(0).args();
        assertThat(args).extracting(DirectiveSupportReport.Arg::name).containsExactly("name", "javaName");
        assertThat(args).extracting(DirectiveSupportReport.Arg::type).containsExactly("String!", "String");
    }

    @Test
    void parsesNewlineSeparatedArgumentsWithDefaults() {
        var src = """
            directive @order(
              index: String
              fields: [FieldSort!]
              primaryKey: Boolean = false
            ) on ENUM_VALUE
            """;
        var ds = DirectiveSupportReport.parseDirectives(src);
        var args = ds.get(0).args();
        assertThat(args).extracting(DirectiveSupportReport.Arg::name)
            .containsExactly("index", "fields", "primaryKey");
        assertThat(args).extracting(DirectiveSupportReport.Arg::type)
            .containsExactly("String", "[FieldSort!]", "Boolean");
        assertThat(args).extracting(DirectiveSupportReport.Arg::defaultValue)
            .containsExactly(null, null, "false");
    }

    @Test
    void stripsInlineDeprecatedFromArguments() {
        var src = """
            directive @asConnection(
              defaultFirstValue: Int = 100
              connectionName: String @deprecated(reason: "use field-owned types")
            ) on FIELD_DEFINITION
            """;
        var ds = DirectiveSupportReport.parseDirectives(src);
        var args = ds.get(0).args();
        assertThat(args).extracting(DirectiveSupportReport.Arg::name)
            .containsExactly("defaultFirstValue", "connectionName");
        assertThat(args).extracting(DirectiveSupportReport.Arg::type)
            .containsExactly("Int", "String");
        assertThat(args).extracting(DirectiveSupportReport.Arg::defaultValue)
            .containsExactly("100", null);
    }

    @Test
    void blockStringDescriptionsDoNotConfuseTheScanner() {
        var src = """
            "Block-string with @at and (parens) and a fake `directive @impostor on UNION` mention."
            \"\"\"
            directive @notReal(arg: String) on FIELD_DEFINITION
            \"\"\"
            directive @real(arg: String!) on FIELD_DEFINITION
            """;
        var ds = DirectiveSupportReport.parseDirectives(src);
        assertThat(ds).hasSize(1);
        assertThat(ds.get(0).name()).isEqualTo("real");
        assertThat(ds.get(0).args()).extracting(DirectiveSupportReport.Arg::type).containsExactly("String!");
    }

    @Test
    void lineCommentsAreStripped() {
        var src = """
            # directive @commented(arg: String) on FIELD_DEFINITION
            directive @actual on FIELD_DEFINITION
            """;
        var ds = DirectiveSupportReport.parseDirectives(src);
        assertThat(ds).hasSize(1);
        assertThat(ds.get(0).name()).isEqualTo("actual");
    }

    @Test
    void argShapeDiffFlagsAddedRemovedAndChangedArguments() {
        var legacy = new DirectiveSupportReport.Directive(
            "field",
            List.of(
                new DirectiveSupportReport.Arg("name", "String!", null),
                new DirectiveSupportReport.Arg("javaName", "String", null)),
            List.of("FIELD_DEFINITION"));
        var rewrite = new DirectiveSupportReport.Directive(
            "field",
            List.of(new DirectiveSupportReport.Arg("name", "String!", null)),
            List.of("FIELD_DEFINITION"));
        var diff = DirectiveSupportReport.argShapeDiff(legacy, rewrite);
        assertThat(diff).hasSize(1);
        assertThat(diff.get(0)).contains("javaName").contains("legacy only");
    }

    @Test
    void argShapeDiffEmptyWhenIdentical() {
        var d = new DirectiveSupportReport.Directive(
            "service",
            List.of(
                new DirectiveSupportReport.Arg("service", "ExternalCodeReference!", null),
                new DirectiveSupportReport.Arg("contextArguments", "[String!]", null)),
            List.of("FIELD_DEFINITION"));
        assertThat(DirectiveSupportReport.argShapeDiff(d, d)).isEmpty();
    }

    @Test
    void renderMigrationListsSupported_legacyOnly_andShapeChanges() {
        var legacy = List.of(
            new DirectiveSupportReport.Directive("table",
                List.of(new DirectiveSupportReport.Arg("name", "String", null)),
                List.of("OBJECT")),
            new DirectiveSupportReport.Directive("notGenerated",
                List.of(), List.of("FIELD_DEFINITION")));
        var rewrite = List.of(
            new DirectiveSupportReport.Directive("table",
                List.of(new DirectiveSupportReport.Arg("name", "String!", null)),
                List.of("OBJECT")),
            new DirectiveSupportReport.Directive("nodeId",
                List.of(new DirectiveSupportReport.Arg("typeName", "String!", null)),
                List.of("FIELD_DEFINITION")));
        var fixtureUses = java.util.Set.of("table", "nodeId");

        String fragment = DirectiveSupportReport.renderMigration(legacy, rewrite, fixtureUses);

        // Header marker pin so the docs build can detect the file is generated.
        assertThat(fragment).startsWith("// Generated by");
        // Supported section: every directive declared in rewrite, regardless of shape change.
        assertThat(fragment).contains("=== Supported directives")
            .contains("`@table`")
            .contains("`@nodeId`");
        // Legacy-only: dropped directives surface as a removal note.
        assertThat(fragment).contains("=== Legacy-only directives")
            .contains("`@notGenerated`");
        // Argument-shape changes: @table widened name from String to String!
        assertThat(fragment).contains("=== Argument-shape changes")
            .contains("==== `@table`")
            .contains("name");
    }

    @Test
    void renderMigrationStatesNoChanges_whenLegacyAndRewriteAgree() {
        var d = new DirectiveSupportReport.Directive("table",
            List.of(new DirectiveSupportReport.Arg("name", "String!", null)),
            List.of("OBJECT"));
        String fragment = DirectiveSupportReport.renderMigration(
            List.of(d), List.of(d), java.util.Set.of("table"));
        // The "no changes" copy fires when nothing is legacy-only and no shapes diverge.
        assertThat(fragment)
            .contains("None. Every directive declared in legacy graphitron")
            .contains("No directive shared with legacy has changed argument shape");
    }
}
