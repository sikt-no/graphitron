package no.sikt.graphitron.rewrite.lint;

import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.model.capture.document.SdlCapture;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.run.SubjectConfig;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
import no.sikt.graphitron.model.test.FactStores;
import no.sikt.graphitron.model.test.SeededStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import no.sikt.graphitron.model.diagnostics.BuildWarning;
import no.sikt.graphitron.rewrite.lint.rules.DeprecationsHaveAReasonVisitor;
import no.sikt.graphitron.rewrite.lint.rules.TypesAndFieldsHaveDescriptionsVisitor;
import no.sikt.graphitron.model.schema.SchemaLoader;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.model.lint.LintFix;
import no.sikt.graphitron.model.lint.LintRule;

/**
 * Per-rule behaviour for the built-in syntactic lint visitors: for each rule a positive case
 * (non-compliant SDL yields exactly one finding naming the rule), a negative case (compliant SDL
 * yields none), and, where the offending node has an unambiguous position, a range case (the
 * finding's {@code SourceLocation} points at the offending node). Findings are asserted on the typed
 * {@link LintRule} and {@link graphql.language.SourceLocation}, never on rendered diagnostic text
 * beyond the minimum identity check, per the design principles' code-string-assertion ban.
 */
@PipelineTier
class LintEngineTest {

    private static final String DIRECTIVES = SchemaLoader.directivesSdl();

    private static List<BuildWarning.LintFinding> findings(String sdl) {
        return run(new SchemaParser().parse(sdl), sdl);
    }

    private static List<BuildWarning.LintFinding> findingsWithDirectives(String sdl) {
        // The registry gets the vocabulary spelled out because this parse is standalone;
        // capture gets only the case's own text, reading the bundled file itself.
        return run(new SchemaParser().parse(DIRECTIVES + "\n" + sdl), sdl);
    }

    private static List<BuildWarning.LintFinding> run(TypeDefinitionRegistry registry) {
        return run(registry, "");
    }

    /**
     * The engine over a parsed registry and a store captured from the same SDL.
     *
     * <p>Two readings of one text, which is what the engine takes today: it still walks the parse
     * tree, and it reads the corpus for the questions a single node cannot answer. The store is
     * captured rather than stubbed because those questions are about rows: whether the corpus
     * deprecates a directive, and what type it declares an argument to be. Capture reads
     * graphitron's own directives.graphqls alongside whatever it is given, so the shipped
     * deprecations are present in every case's store without the case saying so.
     */
    private static List<BuildWarning.LintFinding> run(TypeDefinitionRegistry registry, String sdl) {
        Path directory = temporaryDirectory();
        try (var store = FactStores.inMemory()) {
            // The graph's anchor row is the caller's, not the SDL capture's: every row it
            // writes holds a foreign key into it, so a gatherer does not mint what two need.
            SeededStore.seedGraph(store.dsl(), GRAPH);
            SdlCapture.capture(store.dsl(), new GraphIdentity(GRAPH, directory),
                config(directory, sdl), LocalDateTime.now());
            return LintEngine.builtIn()
                .run(registry, new StoreHandle(store.dsl(), GRAPH)).stream()
                .map(BuildWarning.LintFinding.class::cast)
                .toList();
        }
    }

    /** A graph name of this class's own, so a case's rows cannot be another's. */
    private static final String GRAPH = "lint";

    /** The SDL on disk, where capture finds its inputs. */
    private static SubjectConfig config(Path directory, String sdl) {
        try {
            Files.writeString(directory.resolve("schema.graphqls"), sdl, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return SubjectConfig.of(new SchemaRecipe(directory.resolve("pom.xml"),
            List.of(SchemaRecipe.Binding.pattern("*.graphqls")), List.of("graphqls")));
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("lint-engine-test");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<BuildWarning.LintFinding> forRule(List<BuildWarning.LintFinding> all, LintRule rule) {
        return all.stream().filter(f -> f.rule() == rule).toList();
    }

    private static LintFix fixOf(BuildWarning.LintFinding finding) {
        assertThat(finding.fix()).as("finding carries a suggested fix").isPresent();
        return finding.fix().get();
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

    // --- what the target carries ---

    /**
     * The description column has to be total over the kinds the engine dispatches, or a rule that
     * asks a kind nobody has asked yet reads null and calls the node undocumented. It was not: the
     * reading listed seven node classes and answered null for the rest, while the traversal also
     * reaches input fields, field arguments and enum values, every one of which carries a
     * description in the SDL grammar. Nothing was red, both readers of the column guarding by kind
     * first, which is exactly why this case exists rather than a rule discovering it later.
     */
    @Test
    void everyDispatchedKindThatCanCarryADescriptionCarriesItOnTheTarget() {
        var seen = new EnumMap<LintNodeKind, String>(LintNodeKind.class);
        var capture = new LintVisitor() {
            @Override public LintRule rule() { return LintRule.TYPES_AND_FIELDS_HAVE_DESCRIPTIONS; }
            @Override public Set<LintNodeKind> kinds() { return EnumSet.allOf(LintNodeKind.class); }
            @Override public void inspect(LintTarget target, LintContext ctx) {
                seen.merge(target.kind(), String.valueOf(target.description()),
                    (first, next) -> first);
            }
        };

        try (var store = FactStores.inMemory()) {
            SeededStore.seedGraph(store.dsl(), GRAPH);
            new LintEngine(List.of(capture)).run(new SchemaParser().parse("""
            "a described object"
            type Widget {
              "a described field"
              size("a described argument" unit: String): String
            }
            "a described input"
            input WidgetFilter {
              "a described input field"
              name: String
            }
            "a described enum"
            enum WidgetKind {
              "a described enum value"
              ROUND
            }
            """), new StoreHandle(store.dsl(), GRAPH));
        }

        assertThat(seen)
            .as("every description-bearing position the traversal reaches reports its description")
            .containsEntry(LintNodeKind.OBJECT_TYPE, "a described object")
            .containsEntry(LintNodeKind.FIELD_DEFINITION, "a described field")
            .containsEntry(LintNodeKind.ARGUMENT_DEFINITION, "a described argument")
            .containsEntry(LintNodeKind.INPUT_OBJECT_TYPE, "a described input")
            .containsEntry(LintNodeKind.INPUT_FIELD_DEFINITION, "a described input field")
            .containsEntry(LintNodeKind.ENUM_TYPE, "a described enum")
            .containsEntry(LintNodeKind.ENUM_VALUE_DEFINITION, "a described enum value");
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

    /**
     * An argument written as something other than a string. The author passed something, so the
     * insert-a-reason fix would land inside an argument list that already exists; the rule reports
     * and offers nothing. {@code LintTarget} holds that as a null value under the argument's name,
     * which is why its arguments map is not built by {@code Map.copyOf}.
     */
    @Test
    void deprecationsHaveAReason_flagsANonStringReasonAndOffersNoFix() {
        var found = forRule(findings("type Widget { old: String @deprecated(reason: 5) }"),
            LintRule.DEPRECATIONS_HAVE_A_REASON);
        assertThat(found).hasSize(1);
        assertThat(found.getFirst().fix()).isEmpty();
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

    // --- suggested fixes: assert the LintFix edit ranges and replacement text ---

    @Test
    void fieldNamesCamelCase_offersRenameFix() {
        var found = forRule(findings("type Widget { created_at: String }"), LintRule.FIELD_NAMES_CAMEL_CASE);
        var fix = fixOf(found.getFirst());
        assertThat(fix.edits()).singleElement().satisfies(e -> {
            assertThat(e.replacement()).isEqualTo("createdAt");
            // 'created_at' begins at column 15 on the single line and spans its 10 characters.
            assertThat(e.start().getLine()).isEqualTo(1);
            assertThat(e.start().getColumn()).isEqualTo(15);
            assertThat(e.end().getColumn()).isEqualTo(25);
        });
    }

    @Test
    void fieldNamesCamelCase_describedFieldOffersNoFix() {
        // graphql-java reports a described node's location at the description, so the name token range
        // cannot be derived: the finding still fires, but without a fix.
        var found = forRule(findings("""
            type Widget {
              \"\"\"the timestamp\"\"\"
              created_at: String
            }"""), LintRule.FIELD_NAMES_CAMEL_CASE);
        assertThat(found).singleElement().satisfies(f -> assertThat(f.fix()).isEmpty());
    }

    @Test
    void noTypenamePrefix_offersDropPrefixFix() {
        var found = forRule(findings("type User { userName: String }"), LintRule.NO_TYPENAME_PREFIX);
        var fix = fixOf(found.getFirst());
        assertThat(fix.edits()).singleElement().satisfies(e -> {
            assertThat(e.replacement()).isEqualTo("name");
            // 'userName' begins at column 13 and spans its 8 characters.
            assertThat(e.start().getColumn()).isEqualTo(13);
            assertThat(e.end().getColumn()).isEqualTo(21);
        });
    }

    @Test
    void deprecationsHaveAReason_offersAdditiveReasonFix() {
        var found = forRule(findings("type Widget { old: String @deprecated }"), LintRule.DEPRECATIONS_HAVE_A_REASON);
        var fix = fixOf(found.getFirst());
        assertThat(fix.edits()).singleElement().satisfies(e -> {
            // Zero-width insertion right after '@deprecated' (the '@' is at column 27).
            assertThat(e.start()).isEqualTo(e.end());
            assertThat(e.start().getColumn()).isEqualTo(38);
            assertThat(e.replacement()).isEqualTo(DeprecationsHaveAReasonVisitor.REASON_PLACEHOLDER);
        });
    }

    @Test
    void typesAndFieldsHaveDescriptions_offersAdditiveDescriptionFix() {
        var found = forRule(findings("type Query { widgets: String }"),
            LintRule.TYPES_AND_FIELDS_HAVE_DESCRIPTIONS);
        assertThat(found).allSatisfy(f -> assertThat(f.fix()).isPresent());
        var typeFinding = found.stream().filter(f -> f.message().contains("Query")).findFirst().orElseThrow();
        var fix = fixOf(typeFinding);
        assertThat(fix.edits()).singleElement().satisfies(e -> {
            assertThat(e.start()).isEqualTo(e.end());
            assertThat(e.start().getColumn()).isEqualTo(1);
            assertThat(e.replacement())
                .startsWith(TypesAndFieldsHaveDescriptionsVisitor.DESCRIPTION_PLACEHOLDER)
                .endsWith("\n");
        });
    }

    @Test
    void noDeprecatedDirectiveUsage_offersNoFix() {
        // A quick fix for a deprecated directive must be registered explicitly, not divined from the
        // deprecation's prose reason; the finding reports without a fix and points at the description.
        var found = forRule(findings("""
            \"\"\"Connect this enum value to an index. @deprecated use @order(index:) instead\"\"\"
            directive @index(name: String) on ENUM_VALUE
            directive @order(index: Int) on ENUM_VALUE
            enum Color { RED @index(name: "r") }"""),
            LintRule.NO_DEPRECATED_DIRECTIVE_USAGE);
        assertThat(found).singleElement().satisfies(f -> assertThat(f.fix()).isEmpty());
    }

    // --- no-fix rules offer none (rename ripples to references, or awaits confirmation) ---

    @Test
    void typeNamesPascalCase_offersNoFix() {
        var found = forRule(findings("type widget { id: ID }"), LintRule.TYPE_NAMES_PASCAL_CASE);
        assertThat(found).singleElement().satisfies(f -> assertThat(f.fix()).isEmpty());
    }

    @Test
    void inputObjectNameSuffix_offersNoFix() {
        var found = forRule(findings("input WidgetData { id: ID }"), LintRule.INPUT_OBJECT_NAME_SUFFIX);
        assertThat(found).singleElement().satisfies(f -> assertThat(f.fix()).isEmpty());
    }

    @Test
    void enumValuesScreamingSnakeCase_offersNoFix() {
        var found = forRule(findings("enum Color { red }"), LintRule.ENUM_VALUES_SCREAMING_SNAKE_CASE);
        assertThat(found).singleElement().satisfies(f -> assertThat(f.fix()).isEmpty());
    }

    @Test
    void inputAndArgumentNamesCamelCase_offersNoFix() {
        var found = forRule(findings("input WidgetInput { bad_field: String }"),
            LintRule.INPUT_AND_ARGUMENT_NAMES_CAMEL_CASE);
        assertThat(found).singleElement().satisfies(f -> assertThat(f.fix()).isEmpty());
    }

    // --- graphitron's own bundled directive surface is never linted ---

    @Test
    void bundledGraphitronTypesAreNotLinted() {
        // Parsing the bundled directive surface alone yields no findings: ExternalCodeReference (no
        // Input suffix), directive support types, etc. are graphitron's surface, not author input.
        assertThat(run(new SchemaParser().parse(DIRECTIVES))).isEmpty();
    }
}
