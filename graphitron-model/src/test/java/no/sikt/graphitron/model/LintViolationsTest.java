package no.sikt.graphitron.model;

import no.sikt.graphitron.model.capture.document.SdlCapture;
import no.sikt.graphitron.model.lint.LintViolations;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.run.SubjectConfig;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.LINT_VIOLATION;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_LINT_EXCLUDED_TYPE;
import static no.sikt.graphitron.model.test.SeededStore.SEEDED_READING;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The findings relation as a maintained thing rather than a filled one, which is the property a
 * list of findings never had.
 *
 * <p>Three ways a finding goes away, and they are not the same mechanism. The author deletes the
 * declaration, and the entry it was written at goes with it, carrying the row off by cascade. The
 * author fixes what the rule objected to, leaving the declaration exactly where it was, and only
 * the sweep can tell that this reading no longer draws the row. The author asks for the type to be
 * left alone, and the statement never draws it in the first place.
 */
class LintViolationsTest {

    private static final String GRAPH = "lint-violations";

    private static final String OFFENDING = """
        input WidgetFilter { namePrefix: String }
        type Query { widgets(filter: WidgetFilter): String }
        """;

    private static final String COMPLIANT = """
        input WidgetFilterInput { namePrefix: String }
        type Query { widgets(filter: WidgetFilterInput): String }
        """;

    @Test
    @DisplayName("an input object whose name lacks the suffix draws a row at its declaration")
    void theRuleFiresAtTheWrittenPosition() {
        withSeededStore(GRAPH, dsl -> {
            var directory = read(dsl, OFFENDING);
            assertThat(rulesAt(dsl, "input-object-name-suffix"))
                .as("the rule fired once, at the line the declaration was written on")
                .containsExactly(1);
            assertThat(directory).isNotNull();
        });
    }

    @Test
    @DisplayName("a compliant input object draws nothing")
    void theRuleIsSilentOnACompliantName() {
        withSeededStore(GRAPH, dsl -> {
            read(dsl, COMPLIANT);
            assertThat(rulesAt(dsl, "input-object-name-suffix")).isEmpty();
        });
    }

    /**
     * The property the walk could not have: an author who fixes the name leaves the declaration at
     * the same position, so nothing is deleted and nothing cascades. The row goes because this
     * reading did not restamp it.
     */
    @Test
    @DisplayName("the row goes when the author fixes the name in place")
    void theSweepRemovesAFixedViolation() {
        withSeededStore(GRAPH, dsl -> {
            Path directory = read(dsl, OFFENDING);
            assertThat(rulesAt(dsl, "input-object-name-suffix"))
                .as("the finding is there to begin with").isNotEmpty();

            write(directory, COMPLIANT);
            reread(dsl, directory);

            assertThat(rulesAt(dsl, "input-object-name-suffix"))
                .as("the declaration still exists at the same position, and the finding does not")
                .isEmpty();
        });
    }

    /**
     * The consumer's own exclusion, applied in the statement rather than after the fetch, so the
     * relation holds no row a reader would have to know to ignore.
     */
    @Test
    @DisplayName("a type the consumer excluded draws no row")
    void anExcludedTypeIsNeverDrawn() {
        withSeededStore(GRAPH, dsl -> {
            dsl.insertInto(STORE_GRAPH_LINT_EXCLUDED_TYPE)
                .columns(STORE_GRAPH_LINT_EXCLUDED_TYPE.GRAPH_NAME,
                    STORE_GRAPH_LINT_EXCLUDED_TYPE.ORDINAL,
                    STORE_GRAPH_LINT_EXCLUDED_TYPE.TYPE_PATTERN,
                    STORE_GRAPH_LINT_EXCLUDED_TYPE.TOUCHED_AT)
                .values(GRAPH, 0, "Widget*", SEEDED_READING)
                .execute();

            read(dsl, OFFENDING);

            assertThat(rulesAt(dsl, "input-object-name-suffix"))
                .as("the glob covers the offending type, so the statement never drew it")
                .isEmpty();
        });
    }

    /**
     * The three name shapes at their own grains, in one reading, each offending name drawing one
     * row at the line it was written on. Together they also pin what the shapes do not object to,
     * the compliant siblings beside them in the same fixture drawing nothing.
     */
    @Test
    @DisplayName("each name shape fires at its own grain and nowhere else")
    void theNameShapesFireAtTheirOwnGrains() {
        withSeededStore(GRAPH, dsl -> {
            read(dsl, """
                type widget {
                  Name: String
                  ok(Locale: String, fine: String): String
                }
                enum WidgetKind { small LARGE }
                input WidgetFilterInput { Prefix: String, suffix: String }
                type Query { widgets: String }
                """);

            assertThat(rulesAt(dsl, "type-names-pascal-case"))
                .as("the lowercase type, and not the three compliant ones beside it")
                .containsExactly(1);
            assertThat(rulesAt(dsl, "enum-values-screaming-snake-case"))
                .as("the lowercase enum value, not its compliant sibling")
                .containsExactly(5);
            assertThat(rulesAt(dsl, "input-and-argument-names-camel-case"))
                .as("the capitalised argument and the capitalised input field, in written order")
                .containsExactly(3, 6);
        });
    }

    /**
     * The prefix rule against the name it must not mistake for one: Userland repeats User and is
     * not a prefixed field, the character after the repeat continuing a word rather than starting
     * one.
     */
    @Test
    @DisplayName("a field repeating its type's name fires, and a longer word that merely starts with it does not")
    void thePrefixRuleNeedsAWordBoundary() {
        withSeededStore(GRAPH, dsl -> {
            read(dsl, """
                type User {
                  userName: String
                  userland: String
                  Username: String
                  UserName: String
                  name: String
                }
                type Query { users: String }
                """);

            assertThat(rulesAt(dsl, "no-typename-prefix"))
                .as("userName and UserName repeat the type and then start a word; userland"
                    + " continues one, and Username's own boundary is lower case, so neither is a"
                    + " prefixed field however much of the type name it happens to spell")
                .containsExactly(2, 5);
        });
    }

    /**
     * The description rule's two populations. An extension has no description slot at all, so a
     * row against one would assert a defect the author cannot fix; a field declared inside that
     * same extension can be documented perfectly well and is linted like any other.
     */
    @Test
    @DisplayName("a type extension is not an undocumented type, though its fields are ordinary fields")
    void theDescriptionRuleSkipsExtensionsAndNotTheirFields() {
        withSeededStore(GRAPH, dsl -> {
            read(dsl, """
                "A query."
                type Query {
                  "Documented."
                  documented: String
                  bare: String
                }
                extend type Query {
                  "Also documented."
                  more: String
                  alsoBare: String
                }
                """);

            assertThat(rulesAt(dsl, "types-and-fields-have-descriptions"))
                .as("the two undocumented root fields, one of them declared inside the extension."
                    + " Neither the documented type nor the extension itself draws a row: the type"
                    + " because it is documented, the extension because a description is not a"
                    + " thing it could carry")
                .containsExactly(5, 10);
        });
    }

    /**
     * The deprecation rule against the four things that are not a reason: an omitted argument, a
     * blank one, one written as something other than a string, and, at the other end, a real one
     * that must draw nothing.
     */
    @Test
    @DisplayName("a deprecation says why, or draws a row")
    void deprecationsNeedAWrittenReason() {
        withSeededStore(GRAPH, dsl -> {
            read(dsl, """
                type Widget {
                  bare: String @deprecated
                  blank: String @deprecated(reason: "   ")
                  numeric: String @deprecated(reason: 3)
                  proper: String @deprecated(reason: "use name")
                }
                type Query { widgets: String }
                """);

            assertThat(rulesAt(dsl, "deprecations-have-a-reason"))
                .as("the omitted, the blank and the non-string draw rows at their own @ tokens;"
                    + " the one that says why does not")
                .containsExactly(2, 3, 4);
        });
    }

    /**
     * The two sites the walk has no arm for, which the entry index excludes by their enclosing no
     * element rather than by a list naming them.
     */
    @Test
    @DisplayName("a deprecation on the schema block or a directive's own argument is not linted")
    void applicationsOutsideAnElementAreNotLinted() {
        withSeededStore(GRAPH, dsl -> {
            read(dsl, """
                directive @vintage(era: String @deprecated) on FIELD_DEFINITION
                extend schema @deprecated
                type Query { widgets: String }
                """);

            assertThat(rulesAt(dsl, "deprecations-have-a-reason"))
                .as("neither encloses a schema element, so neither is the author's to be told about"
                    + " by this rule")
                .isEmpty();
        });
    }

    /** The lines one rule drew a row at, in source order. */
    private static List<Integer> rulesAt(DSLContext dsl, String rule) {
        return dsl.select(LINT_VIOLATION.SOURCE_LINE).from(LINT_VIOLATION)
            .where(LINT_VIOLATION.GRAPH_NAME.eq(GRAPH), LINT_VIOLATION.LINT_RULE.eq(rule))
            .orderBy(LINT_VIOLATION.SOURCE_LINE)
            .fetch(LINT_VIOLATION.SOURCE_LINE);
    }

    private static List<String> rules(DSLContext dsl) {
        return dsl.select(LINT_VIOLATION.LINT_RULE).from(LINT_VIOLATION)
            .where(LINT_VIOLATION.GRAPH_NAME.eq(GRAPH))
            .fetch(LINT_VIOLATION.LINT_RULE);
    }

    /** Captures the SDL and runs the rules over it, which is what a reading does. */
    private static Path read(DSLContext dsl, String sdl) {
        Path directory = temporaryDirectory();
        write(directory, sdl);
        reread(dsl, directory);
        return directory;
    }

    private static void reread(DSLContext dsl, Path directory) {
        var readAt = LocalDateTime.now();
        SdlCapture.captureFacts(dsl, new GraphIdentity(GRAPH, directory),
            SubjectConfig.of(new SchemaRecipe(directory.resolve("pom.xml"),
                List.of(SchemaRecipe.Binding.pattern("*.graphqls")), List.of("graphqls"))),
            readAt);
        LintViolations.write(dsl, GRAPH, readAt);
    }

    private static void write(Path directory, String sdl) {
        try {
            Files.writeString(directory.resolve("schema.graphqls"), sdl, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("lint-violations");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
