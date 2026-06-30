package no.sikt.graphitron.rewrite.lint;

import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.rewrite.BuildWarning;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-rule behaviour for the built-in syntactic lint visitors (R398): for each rule a positive case
 * (non-compliant SDL yields exactly one finding naming the rule), a negative case (compliant SDL
 * yields none), and, where the offending node has an unambiguous position, a range case (the
 * finding's {@code SourceLocation} points at the offending node). Findings are asserted on the typed
 * {@link LintRule} and {@link graphql.language.SourceLocation}, never on rendered diagnostic text
 * beyond the minimum identity check, per the design principles' code-string-assertion ban.
 */
@PipelineTier
class LintEngineTest {

    private static final String DIRECTIVES = RewriteSchemaLoader.directivesSdl();

    private static List<BuildWarning.LintFinding> findings(String sdl) {
        return run(new SchemaParser().parse(sdl));
    }

    private static List<BuildWarning.LintFinding> findingsWithDirectives(String sdl) {
        return run(new SchemaParser().parse(DIRECTIVES + "\n" + sdl));
    }

    private static List<BuildWarning.LintFinding> run(TypeDefinitionRegistry registry) {
        return LintEngine.builtIn().run(registry).stream()
            .map(BuildWarning.LintFinding.class::cast)
            .toList();
    }

    private static List<BuildWarning.LintFinding> forRule(List<BuildWarning.LintFinding> all, LintRule rule) {
        return all.stream().filter(f -> f.rule() == rule).toList();
    }

    // --- type-names-pascal-case ---

    @Test
    void typeNamesPascalCase_flagsLowercaseType() {
        var found = forRule(findings("type widget { id: ID }"), LintRule.TYPE_NAMES_PASCAL_CASE);
        assertThat(found).singleElement()
            .satisfies(f -> assertThat(f.message()).contains("widget"));
    }

    @Test
    void typeNamesPascalCase_silentOnPascalType() {
        assertThat(forRule(findings("type Widget { id: ID }"), LintRule.TYPE_NAMES_PASCAL_CASE)).isEmpty();
    }

    @Test
    void typeNamesPascalCase_rangePointsAtTheType() {
        var found = forRule(findings("type widget { id: ID }"), LintRule.TYPE_NAMES_PASCAL_CASE);
        assertThat(found).singleElement()
            .satisfies(f -> assertThat(f.location().getLine()).isEqualTo(1));
    }

    // --- field-names-camel-case ---

    @Test
    void fieldNamesCamelCase_flagsSnakeField() {
        var found = forRule(findings("type Widget { created_at: String }"), LintRule.FIELD_NAMES_CAMEL_CASE);
        assertThat(found).singleElement().satisfies(f -> assertThat(f.message()).contains("created_at"));
    }

    @Test
    void fieldNamesCamelCase_silentOnCamelField() {
        assertThat(forRule(findings("type Widget { createdAt: String }"), LintRule.FIELD_NAMES_CAMEL_CASE)).isEmpty();
    }

    @Test
    void fieldNamesCamelCase_rangePointsAtTheField() {
        var found = forRule(findings("""
            type Widget {
              created_at: String
            }"""), LintRule.FIELD_NAMES_CAMEL_CASE);
        assertThat(found).singleElement().satisfies(f -> assertThat(f.location().getLine()).isEqualTo(2));
    }

    // --- input-and-argument-names-camel-case ---

    @Test
    void inputAndArgumentNamesCamelCase_flagsInputFieldAndArgument() {
        var found = forRule(findings("""
            input WidgetInput { bad_field: String }
            type Query { widgets(bad_arg: String): String }
            """), LintRule.INPUT_AND_ARGUMENT_NAMES_CAMEL_CASE);
        assertThat(found).hasSize(2);
    }

    @Test
    void inputAndArgumentNamesCamelCase_silentWhenCamel() {
        assertThat(forRule(findings("""
            input WidgetInput { goodField: String }
            type Query { widgets(goodArg: String): String }
            """), LintRule.INPUT_AND_ARGUMENT_NAMES_CAMEL_CASE)).isEmpty();
    }

    // --- enum-values-screaming-snake-case ---

    @Test
    void enumValuesScreamingSnakeCase_flagsLowercaseValue() {
        var found = forRule(findings("enum Color { red }"), LintRule.ENUM_VALUES_SCREAMING_SNAKE_CASE);
        assertThat(found).singleElement().satisfies(f -> assertThat(f.message()).contains("red"));
    }

    @Test
    void enumValuesScreamingSnakeCase_silentOnScreamingSnake() {
        assertThat(forRule(findings("enum Color { DARK_RED }"), LintRule.ENUM_VALUES_SCREAMING_SNAKE_CASE)).isEmpty();
    }

    // --- deprecations-have-a-reason ---

    @Test
    void deprecationsHaveAReason_flagsReasonlessDeprecation() {
        var found = forRule(findings("type Widget { old: String @deprecated }"), LintRule.DEPRECATIONS_HAVE_A_REASON);
        assertThat(found).hasSize(1);
    }

    @Test
    void deprecationsHaveAReason_silentWhenReasonGiven() {
        assertThat(forRule(findings("type Widget { old: String @deprecated(reason: \"use new\") }"),
            LintRule.DEPRECATIONS_HAVE_A_REASON)).isEmpty();
    }

    // --- types-and-fields-have-descriptions ---

    @Test
    void typesAndFieldsHaveDescriptions_flagsUndescribedTypeAndRootField() {
        var found = forRule(findings("type Query { widgets: String }"),
            LintRule.TYPES_AND_FIELDS_HAVE_DESCRIPTIONS);
        // The Query type and its root-operation field both lack a description.
        assertThat(found).hasSize(2);
    }

    @Test
    void typesAndFieldsHaveDescriptions_silentWhenDescribed() {
        assertThat(forRule(findings("""
            \"\"\"The root query.\"\"\"
            type Query {
              \"\"\"All widgets.\"\"\"
              widgets: String
            }"""), LintRule.TYPES_AND_FIELDS_HAVE_DESCRIPTIONS)).isEmpty();
    }

    @Test
    void typesAndFieldsHaveDescriptions_nonRootFieldsAreNotRequired() {
        // A non-root type's fields are out of default scope; only the type itself is flagged.
        var found = forRule(findings("""
            \"\"\"A widget.\"\"\"
            type Widget { name: String }"""), LintRule.TYPES_AND_FIELDS_HAVE_DESCRIPTIONS);
        assertThat(found).isEmpty();
    }

    // --- input-object-name-suffix ---

    @Test
    void inputObjectNameSuffix_flagsMissingSuffix() {
        var found = forRule(findings("input WidgetData { id: ID }"), LintRule.INPUT_OBJECT_NAME_SUFFIX);
        assertThat(found).singleElement().satisfies(f -> assertThat(f.message()).contains("WidgetData"));
    }

    @Test
    void inputObjectNameSuffix_silentWhenSuffixed() {
        assertThat(forRule(findings("input WidgetInput { id: ID }"), LintRule.INPUT_OBJECT_NAME_SUFFIX)).isEmpty();
    }

    // --- no-typename-prefix ---

    @Test
    void noTypenamePrefix_flagsPrefixedField() {
        var found = forRule(findings("type User { userName: String }"), LintRule.NO_TYPENAME_PREFIX);
        assertThat(found).singleElement().satisfies(f -> assertThat(f.message()).contains("User.userName"));
    }

    @Test
    void noTypenamePrefix_silentOnUnprefixedField() {
        assertThat(forRule(findings("type User { name: String }"), LintRule.NO_TYPENAME_PREFIX)).isEmpty();
    }

    // --- no-deprecated-directive-usage (needs the bundled directive surface) ---

    @Test
    void noDeprecatedDirectiveUsage_flagsDeprecatedDirective() {
        var found = forRule(findingsWithDirectives("enum Color { RED @index(name: \"r\") }"),
            LintRule.NO_DEPRECATED_DIRECTIVE_USAGE);
        assertThat(found).singleElement().satisfies(f -> assertThat(f.message()).contains("index"));
    }

    @Test
    void noDeprecatedDirectiveUsage_flagsDeprecatedDirectiveArgument() {
        var found = forRule(findingsWithDirectives(
            "type Query { films: String @asConnection(connectionName: \"X\") }"),
            LintRule.NO_DEPRECATED_DIRECTIVE_USAGE);
        assertThat(found).singleElement().satisfies(f -> assertThat(f.message()).contains("connectionName"));
    }

    @Test
    void noDeprecatedDirectiveUsage_silentWhenNoDeprecatedUsage() {
        assertThat(forRule(findingsWithDirectives("type Query { films: String }"),
            LintRule.NO_DEPRECATED_DIRECTIVE_USAGE)).isEmpty();
    }

    // --- graphitron's own bundled directive surface is never linted ---

    @Test
    void bundledGraphitronTypesAreNotLinted() {
        // Parsing the bundled directive surface alone yields no findings: ExternalCodeReference (no
        // Input suffix), directive support types, etc. are graphitron's surface, not author input.
        assertThat(run(new SchemaParser().parse(DIRECTIVES))).isEmpty();
    }
}
