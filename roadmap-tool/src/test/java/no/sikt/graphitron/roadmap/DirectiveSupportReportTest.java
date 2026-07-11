package no.sikt.graphitron.roadmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void renderMigrationWithholdsNotInUseAndRejectedDirectivesFromSupported() {
        // A rewrite that declares a genuinely-supported directive (table), the withheld-but-declared
        // ones (tableMethod, sourceRow, experimental_constructType), the hard-rejected retirements
        // (notGenerated, multitableReference), and @record (deprecated + silently ignored, kept
        // as-is for v1, NOT dropped).
        java.util.function.Function<String, DirectiveSupportReport.Directive> d =
            name -> new DirectiveSupportReport.Directive(name, List.of(), List.of("FIELD_DEFINITION"));
        var declared = List.of(
            d.apply("table"),
            d.apply("tableMethod"),
            d.apply("sourceRow"),
            d.apply("experimental_constructType"),
            d.apply("notGenerated"),
            d.apply("multitableReference"),
            d.apply("record"));

        String fragment = DirectiveSupportReport.renderMigration(declared, declared, java.util.Set.of());

        // Genuinely supported directive is advertised.
        assertThat(fragment).contains("=== Supported directives").contains("`@table`");
        // Withheld and rejected directives never appear under "Supported".
        var supportedSection = fragment.substring(
            fragment.indexOf("=== Supported directives"),
            fragment.indexOf("=== Removed / rejected directives"));
        assertThat(supportedSection)
            .doesNotContain("`@tableMethod`")
            .doesNotContain("`@sourceRow`")
            .doesNotContain("`@experimental_constructType`")
            .doesNotContain("`@notGenerated`")
            .doesNotContain("`@multitableReference`");
        // @record is kept as-is (deprecated + silently ignored, not dropped): it is neither
        // withheld nor moved to the rejected list, so it stays where it was — under Supported.
        assertThat(supportedSection).contains("`@record`");
        // The two hard-rejected retirements are surfaced under "Removed / rejected" so migrating
        // consumers are told to delete them; withheld-but-usable directives and @record are not.
        var rejectedSection = fragment.substring(fragment.indexOf("=== Removed / rejected directives"));
        assertThat(rejectedSection)
            .contains("`@notGenerated`")
            .contains("`@multitableReference`")
            .doesNotContain("`@tableMethod`")
            .doesNotContain("`@experimental_constructType`")
            .doesNotContain("`@record`");
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

    @Test
    void renderMigrationSupportedParagraphDropsUnbackedFixtureClaim() {
        // R346 decision 2: the Supported paragraph must not assert per-directive fixture
        // coverage (nothing pins that invariant in migration mode); it states the real
        // criterion (declared + supported) and keeps only the test-backed "documented" clause.
        var d = new DirectiveSupportReport.Directive("table",
            List.of(new DirectiveSupportReport.Arg("name", "String!", null)),
            List.of("OBJECT"));
        String fragment = DirectiveSupportReport.renderMigration(
            List.of(d), List.of(d), java.util.Set.of());
        assertThat(fragment)
            .doesNotContain("exercised by at least one execution-tier or")
            .doesNotContain("pipeline-tier test fixture");
        assertThat(fragment)
            .contains("The rewrite generator declares and supports the following directives.")
            .contains("Each is documented in the architecture chapter:");
    }

    /**
     * A minimal legacy/rewrite pair whose migration diff exercises all three arm shapes
     * (legacy-only directive, rewrite-only arg, legacy-only arg), so the round-trip below
     * verifies against non-trivial content rather than an empty fragment.
     */
    private static void writeDirectiveFixtures(Path legacy, Path rewrite) throws Exception {
        Files.writeString(legacy, """
            directive @field(name: String!, javaName: String) on FIELD_DEFINITION
            directive @experimental_procedureCall on FIELD_DEFINITION
            """);
        Files.writeString(rewrite, """
            directive @field(name: String!) on FIELD_DEFINITION
            """);
    }

    @Test
    void verifyReturnsZeroWhenOutputMatchesRegeneratedContent(@TempDir Path dir) throws Exception {
        Path legacy = dir.resolve("legacy.graphqls");
        Path rewrite = dir.resolve("rewrite.graphqls");
        Path out = dir.resolve("fragment.adoc");
        writeDirectiveFixtures(legacy, rewrite);

        // Generate the fragment, then verify it against itself: an up-to-date file passes.
        int gen = DirectiveSupportReport.run(List.of(
            legacy.toString(), rewrite.toString(), dir.toString(),
            "--mode=migration", "--output=" + out));
        assertThat(gen).isZero();

        int verify = DirectiveSupportReport.run(List.of(
            legacy.toString(), rewrite.toString(), dir.toString(),
            "--mode=migration", "--output=" + out, "--verify"));
        assertThat(verify).isZero();
    }

    @Test
    void verifyFailsWhenOutputDriftsFromRegeneratedContent(@TempDir Path dir) throws Exception {
        Path legacy = dir.resolve("legacy.graphqls");
        Path rewrite = dir.resolve("rewrite.graphqls");
        Path out = dir.resolve("fragment.adoc");
        writeDirectiveFixtures(legacy, rewrite);

        Files.writeString(out, "// stale hand-edited content that no longer matches the generator\n");

        // Drift throws BuildFailure (not a non-zero return) so exec-maven-plugin surfaces a clean
        // BUILD FAILURE rather than a bare System.exit; this is the guard that would have caught
        // the @routine / @scalarType drift that motivated R346.
        assertThatThrownBy(() -> DirectiveSupportReport.run(List.of(
            legacy.toString(), rewrite.toString(), dir.toString(),
            "--mode=migration", "--output=" + out, "--verify")))
            .isInstanceOf(BuildFailure.class);
    }

    @Test
    void verifyRequiresOutputPath(@TempDir Path dir) throws Exception {
        Path legacy = dir.resolve("legacy.graphqls");
        Path rewrite = dir.resolve("rewrite.graphqls");
        writeDirectiveFixtures(legacy, rewrite);

        int rc = DirectiveSupportReport.run(List.of(
            legacy.toString(), rewrite.toString(), dir.toString(),
            "--mode=migration", "--verify"));
        assertThat(rc).isEqualTo(64);
    }
}
