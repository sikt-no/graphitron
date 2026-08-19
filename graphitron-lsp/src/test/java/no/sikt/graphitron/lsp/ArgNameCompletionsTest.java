package no.sikt.graphitron.lsp;

import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.completions.ArgNameCompletions;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.GraphqlLanguage;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.model.read.StoreHandle;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Argument-name completion off the SDL census. Pins the two cursor-position cases: top-level (cursor
 * inside a directive's parens but not on any argument) and nested (cursor inside a nested
 * {@code object_value} but not on any object_field).
 *
 * <p>The fixture captures graphitron's bundled directive vocabulary, which capture reads like any
 * schema file, alongside two directives of the schema's own. Both come back through one relation, so
 * the cases below are the same query with a different name in it, where the incumbent had a bundled
 * arm and a user arm that behaved differently.
 */
class ArgNameCompletionsTest {

    private static final LspVocabulary VOCAB = BundledVocabulary.get();

    /**
     * Two directives an author declared, one taking a scalar and one an input object, so the nested
     * descent has a non-bundled type to walk.
     */
    private static final String SDL = """
        directive @auth(role: String!, scope: String) on FIELD_DEFINITION
        directive @policy(rule: PolicyRule) on FIELD_DEFINITION

        input PolicyRule {
            subject: String
            effect: String
        }

        type Query { placeholder: Int }
        """;

    @TempDir
    static Path tmp;

    private static StoreFixture store;

    @BeforeAll
    static void capture() {
        store = StoreFixture.of(tmp, SDL);
    }

    @AfterAll
    static void closeStore() {
        store.close();
    }

    @Test
    void cursorAfterCommaCompletesRemainingArgNames() {
        // @service(service: {...}, |) — cursor after the trailing comma. Tree-sitter parses the
        // directive's outer node to include the trailing comma + closing paren when at least one
        // well-formed arg is present. The user-facing scenario: developer typed the first arg +
        // comma, expects completion on the next arg name.
        String source = """
            type Foo {
                bar: Int @service(service: {className: "x"}, )
            }
            """;
        var lines = source.split("\n");
        int line = 1;
        // Cursor on the space after the comma.
        int col = lines[line].indexOf(", ") + 2;

        // @service has two args: service (already present) + contextArguments.
        assertThat(labels(source, new Point(line, col))).contains("contextArguments");
    }

    @Test
    void cursorBetweenObjectBracesCompletesInputFieldNames() {
        // @reference(path: [{|}]) — cursor in the empty object_value; the input type at this nesting
        // level is ReferenceElement, so the completions are its three fields.
        String source = """
            type Foo {
                bar: Int @reference(path: [{}])
            }
            """;
        var lines = source.split("\n");
        int line = 1;
        int col = lines[line].indexOf("{}") + 1;

        assertThat(labels(source, new Point(line, col)))
            .containsExactlyInAnyOrder("condition", "key", "table");
    }

    @Test
    void cursorInsideNestedObjectCompletesEcrFieldNames() {
        // @reference(path: [{condition: {|}}]) — cursor in the inner ExternalCodeReference's
        // object_value, two descents down from the directive's own argument.
        String source = """
            type Foo {
                bar: Int @reference(path: [{condition: {}}])
            }
            """;
        var lines = source.split("\n");
        int line = 1;
        int col = lines[line].indexOf("condition: {") + "condition: {".length();

        assertThat(labels(source, new Point(line, col)))
            .containsExactlyInAnyOrder("className", "method", "argMapping");
    }

    @Test
    void cursorOnUnknownDirectiveReturnsEmpty() {
        // A fixture that parses cleanly (parens claimed because there is a well-formed arg) but names
        // a directive no capture read. Arg-name completion does not fire: the unknown-directive
        // diagnostic surfaces the typo.
        String source = """
            type Foo @notADirective(name: "x") { bar: Int }
            """;
        // Cursor on the closing paren, which is inside the outer node but outside the only arg.
        int col = source.indexOf(") ") + 1;

        assertThat(labels(source, new Point(0, col))).isEmpty();
    }

    @Test
    void cursorOnArgValueReturnsEmpty() {
        // Cursor on the value side of a known arg — value completers own this position.
        String source = """
            type Foo @table(name: "") { bar: Int }
            """;
        int col = source.indexOf("\"\"") + 1;

        assertThat(labels(source, new Point(0, col))).isEmpty();
    }

    /**
     * A directive the schema declares itself is one more row of the same relation, offered in
     * declaration order, where the incumbent read a projection of user directives that carried the
     * names and nothing else.
     */
    @Test
    void aUserDeclaredDirectivesArgumentsAreOfferedLikeAnyOthers() {
        String source = """
            type Query {
                customers: [String!]! @auth(role: "x", )
            }
            """;
        var lines = source.split("\n");
        int line = 1;
        int col = lines[line].indexOf(", ") + 2;

        assertThat(labels(source, new Point(line, col))).containsExactly("role", "scope");
    }

    /**
     * Nesting works for an author's own input type. The incumbent descended only under bundled
     * directives, because the user-directive projection held no input-object shapes; one relation for
     * both makes that asymmetry unrepresentable rather than fixed.
     */
    @Test
    void aUserDeclaredDirectiveNestsIntoItsInputType() {
        String source = """
            type Query {
                customers: [String!]! @policy(rule: { })
            }
            """;
        var lines = source.split("\n");
        int line = 1;
        int col = lines[line].indexOf("{ }") + 2;

        assertThat(labels(source, new Point(line, col))).containsExactly("subject", "effect");
    }

    /**
     * An object literal written on an argument whose type is a scalar has no fields to offer. The
     * kind check is what says so: {@code graphql_field} holds output fields under the same shape, so
     * only the join to the type's kind keeps the descent inside input objects.
     */
    @Test
    void anObjectLiteralOnAScalarArgumentReturnsEmpty() {
        String source = """
            type Query {
                customers: [String!]! @auth(role: { })
            }
            """;
        var lines = source.split("\n");
        int line = 1;
        int col = lines[line].indexOf("{ }") + 2;

        assertThat(labels(source, new Point(line, col))).isEmpty();
    }

    /**
     * The vocabulary is the graph's. A second graph in the same store that captured no SDL of its own
     * completes nothing, which is the same answer the pre-build state gave when there was no snapshot
     * to consult.
     */
    @Test
    void aGraphThatCapturedNothingCompletesNothing() {
        String source = """
            type Foo {
                bar: Int @service(service: {className: "x"}, )
            }
            """;
        var lines = source.split("\n");
        int line = 1;
        int col = lines[line].indexOf(", ") + 2;

        assertThat(labels(store.handleFor("nothing-captured-here"), source, new Point(line, col)))
            .isEmpty();
    }

    private static List<String> labels(String source, Point cursor) {
        return labels(store.handle(), source, cursor);
    }

    private static List<String> labels(StoreHandle handle, String source, Point cursor) {
        var parser = new Parser();
        parser.setLanguage(GraphqlLanguage.get());
        var bytes = source.getBytes(StandardCharsets.UTF_8);
        var tree = parser.parse(source).orElseThrow();
        var directive = Directives.findContaining(tree.getRootNode(), cursor)
            .orElseThrow(() -> new AssertionError("expected directive at cursor " + cursor));
        var lspPos = new Position(cursor.row(), cursor.column());
        return ArgNameCompletions.generate(VOCAB, handle, directive, cursor, lspPos, bytes)
            .stream().map(CompletionItem::getLabel).toList();
    }
}
