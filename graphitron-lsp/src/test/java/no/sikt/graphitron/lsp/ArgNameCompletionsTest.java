package no.sikt.graphitron.lsp;

import java.util.Map;
import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.completions.ArgNameCompletions;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.GraphqlLanguage;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Argument-name completion driven off the parsed registry. Pins the two
 * cursor-position cases the spec calls out: top-level (cursor inside a
 * directive's parens but not on any argument) and nested (cursor inside
 * a nested {@code object_value} but not on any object_field).
 */
class ArgNameCompletionsTest {

    private static final LspVocabulary VOCAB = LspVocabulary.load();

    @Test
    void cursorAfterCommaCompletesRemainingArgNames() {
        // @service(service: {...}, |) — cursor after the trailing comma.
        // Tree-sitter parses the directive's outer node to include the
        // trailing comma + closing paren when at least one well-formed
        // arg is present. The user-facing scenario: developer typed the
        // first arg + comma, expects completion on the next arg name.
        String source = """
            type Foo {
                bar: Int @service(service: {className: "x"}, )
            }
            """;
        var lines = source.split("\n");
        int line = 1;
        // Cursor on the space after the comma.
        int col = lines[line].indexOf(", ") + 2;

        var items = run(source, new Point(line, col));

        // @service has two args: service (already present) + contextArguments.
        assertThat(items).extracting(i -> i.getLabel())
            .contains("contextArguments");
    }

    @Test
    void cursorBetweenObjectBracesCompletesInputFieldNames() {
        // @reference(path: [{|}]) — cursor in the empty object_value;
        // the input type at this nesting level is ReferenceElement, so
        // the completions are its three fields.
        String source = """
            type Foo {
                bar: Int @reference(path: [{}])
            }
            """;
        var lines = source.split("\n");
        int line = 1;
        int col = lines[line].indexOf("{}") + 1;

        var items = run(source, new Point(line, col));

        assertThat(items).extracting(i -> i.getLabel())
            .containsExactlyInAnyOrder("condition", "key", "table");
    }

    @Test
    void cursorInsideNestedObjectCompletesEcrFieldNames() {
        // @reference(path: [{condition: {|}}]) — cursor in the inner
        // ExternalCodeReference's object_value.
        String source = """
            type Foo {
                bar: Int @reference(path: [{condition: {}}])
            }
            """;
        var lines = source.split("\n");
        int line = 1;
        int col = lines[line].indexOf("condition: {") + "condition: {".length();

        var items = run(source, new Point(line, col));

        assertThat(items).extracting(i -> i.getLabel())
            .containsExactlyInAnyOrder("className", "method", "name", "argMapping");
    }

    @Test
    void cursorOnUnknownDirectiveReturnsEmpty() {
        // Use a fixture that parses cleanly (parens claimed because there's
        // a well-formed arg) but names a directive the registry doesn't
        // declare. Arg-name completion should not fire for unknown
        // directives — the unknown-directive diagnostic surfaces the typo.
        String source = """
            type Foo @notADirective(name: "x") { bar: Int }
            """;
        // Cursor inside the outer parens but on whitespace just before the arg
        // — wait, that position is inside `name`'s range. Land cursor on the
        // closing paren instead, which is inside outer but outside the only arg.
        int col = source.indexOf(") ") + 1;

        var items = run(source, new Point(0, col));

        assertThat(items).isEmpty();
    }

    @Test
    void cursorOnArgValueReturnsEmpty() {
        // Cursor on the value side of a known arg — value completers own
        // this position, not the arg-name completer.
        String source = """
            type Foo @table(name: "") { bar: Int }
            """;
        int col = source.indexOf("\"\"") + 1;

        var items = run(source, new Point(0, col));

        assertThat(items).isEmpty();
    }

    // ---- Phase 2: user-declared directives via the snapshot. ----

    @Test
    void userDirectiveTopLevelArgCompletion_emitsSnapshotArgs() {
        // @auth(role: "x", <cursor>) on a snapshot carrying @auth(role:, scope:).
        // Same fixture shape as `cursorAfterCommaCompletesRemainingArgNames`:
        // tree-sitter claims the outer parens once one well-formed arg is
        // present. Cursor between comma and `)` falls outside any arg, so
        // top-level completion lists the directive's args.
        String source = """
            type Query {
                customers: [String!]! @auth(role: "x", )
            }
            """;
        var lines = source.split("\n");
        int line = 1;
        int col = lines[line].indexOf(", ") + 2;

        var items = runWithSnapshot(source, new Point(line, col),
            new LspSchemaSnapshot.Built.Current(java.util.List.of(authShapeWithScope()), Map.of(), Map.of()));

        assertThat(items).extracting(i -> i.getLabel())
            .containsExactlyInAnyOrder("role", "scope");
    }

    @Test
    void userDirectiveNestedArgCompletion_returnsEmpty() {
        // Cursor inside an arg-value object literal on a user-declared
        // directive: phase 2 explicitly deferred nested completion until
        // the snapshot ships input-object shapes. Returns empty.
        String source = """
            type Query {
                customers: [String!]! @auth(role: { })
            }
            """;
        var lines = source.split("\n");
        int line = 1;
        int col = lines[line].indexOf("{ }") + 2;

        var items = runWithSnapshot(source, new Point(line, col),
            new LspSchemaSnapshot.Built.Current(java.util.List.of(authShapeWithScope()), Map.of(), Map.of()));

        assertThat(items).isEmpty();
    }

    @Test
    void userDirectiveUnderUnavailableSnapshot_returnsEmpty() {
        // Pre-build state: no snapshot to consult. Returns empty,
        // preserving the pre-phase-2 behaviour on unknown directives.
        String source = """
            type Query {
                customers: [String!]! @auth(role: "x", )
            }
            """;
        var lines = source.split("\n");
        int line = 1;
        int col = lines[line].indexOf(", ") + 2;

        var items = runWithSnapshot(source, new Point(line, col), LspSchemaSnapshot.unavailable());

        assertThat(items).isEmpty();
    }

    @Test
    void bundledDirectiveShadowedBySnapshot_routesThroughBundledPath() {
        // Bundled shadows snapshot. The user
        // accidentally redeclares @table with a different arg set, and
        // typing `@table(<cursor>)` must offer the bundled args, not the
        // shadow's. Pins the precedence on the completion side, symmetric
        // with the Hovers and Diagnostics shadow guards.
        var shadow = new no.sikt.graphitron.rewrite.catalog.DirectiveShape(
            "table",
            java.util.List.of(new no.sikt.graphitron.rewrite.catalog.InputValueShape(
                "extraArg",
                new no.sikt.graphitron.rewrite.catalog.TypeShape.Named("String", true),
                java.util.Optional.empty())),
            java.util.Optional.empty());
        String source = """
            type Foo @table(name: "film", ) {
                bar: Int
            }
            """;
        var lines = source.split("\n");
        int line = 0;
        int col = lines[line].indexOf(", ") + 2;

        var items = runWithSnapshot(source, new Point(line, col),
            new LspSchemaSnapshot.Built.Current(java.util.List.of(shadow), Map.of(), Map.of()));

        // Bundled @table args are `name` (no `extraArg`); the shadow's
        // `extraArg` must not appear.
        assertThat(items).extracting(i -> i.getLabel())
            .contains("name")
            .doesNotContain("extraArg");
    }

    @Test
    void userDirectiveUnderPreviousSnapshot_emitsArgs() {
        // Stale snapshot: completions still fire. Suggestion beats silence
        // even when the snapshot is post-parse-failure.
        String source = """
            type Query {
                customers: [String!]! @auth(role: "x", )
            }
            """;
        var lines = source.split("\n");
        int line = 1;
        int col = lines[line].indexOf(", ") + 2;

        var items = runWithSnapshot(source, new Point(line, col),
            new LspSchemaSnapshot.Built.Previous(java.util.List.of(authShapeWithScope()), Map.of(), Map.of()));

        assertThat(items).extracting(i -> i.getLabel())
            .containsExactlyInAnyOrder("role", "scope");
    }

    private static no.sikt.graphitron.rewrite.catalog.DirectiveShape authShapeWithScope() {
        return new no.sikt.graphitron.rewrite.catalog.DirectiveShape(
            "auth",
            java.util.List.of(
                new no.sikt.graphitron.rewrite.catalog.InputValueShape(
                    "role",
                    new no.sikt.graphitron.rewrite.catalog.TypeShape.Named("String", true),
                    java.util.Optional.empty()),
                new no.sikt.graphitron.rewrite.catalog.InputValueShape(
                    "scope",
                    new no.sikt.graphitron.rewrite.catalog.TypeShape.Named("String", false),
                    java.util.Optional.empty())),
            java.util.Optional.empty());
    }

    private static java.util.List<org.eclipse.lsp4j.CompletionItem> run(String source, Point cursor) {
        return runWithSnapshot(source, cursor, LspSchemaSnapshot.unavailable());
    }

    private static java.util.List<org.eclipse.lsp4j.CompletionItem> runWithSnapshot(
        String source, Point cursor, LspSchemaSnapshot snapshot
    ) {
        var parser = new Parser();
        parser.setLanguage(GraphqlLanguage.get());
        var bytes = source.getBytes(StandardCharsets.UTF_8);
        var tree = parser.parse(source).orElseThrow();
        var directive = Directives.findContaining(tree.getRootNode(), cursor)
            .orElseThrow(() -> new AssertionError("expected directive at cursor " + cursor));
        var lspPos = new org.eclipse.lsp4j.Position(cursor.row(), cursor.column());
        return ArgNameCompletions.generate(VOCAB, snapshot, directive, cursor, lspPos, bytes);
    }
}
