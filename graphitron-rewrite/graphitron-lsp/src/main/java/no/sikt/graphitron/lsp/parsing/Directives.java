package no.sikt.graphitron.lsp.parsing;

import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Point;
import io.github.treesitter.jtreesitter.Query;
import io.github.treesitter.jtreesitter.QueryCapture;
import io.github.treesitter.jtreesitter.QueryCursor;
import io.github.treesitter.jtreesitter.QueryMatch;

import java.lang.foreign.Arena;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Tree-sitter queries against {@code tree-sitter-graphql} for directives.
 *
 * <p>Mirrors {@code parsing/directives.rs} in the Rust LSP. The tree-sitter
 * query language is grammar-agnostic, so the query string itself ports
 * verbatim from the Rust source.
 */
public final class Directives {

    private static final String DIRECTIVES_QUERY_TEXT = """
        (directive (name)@directive-name
            (arguments
              (argument (name)@name (value)@value) @argument-full
              (
                (comma)?
                (argument (name)@name (value)@value)  @argument-full
              )*
            )?@arguments-full
          )@outer
        """;

    private static final Query DIRECTIVES_QUERY =
        new Query(GraphqlLanguage.get(), DIRECTIVES_QUERY_TEXT);

    private Directives() {}

    /**
     * Argument node within a directive: {@code name: value}.
     */
    public record Argument(Node full, Node key, Node value) {
        public boolean contains(Point point) {
            return Nodes.contains(full, point);
        }
    }

    /**
     * Directive node: {@code @name(arg: value, ...)}.
     */
    public record Directive(Node outer, Node nameNode, List<Argument> arguments) {
        public boolean contains(Point point) {
            return Nodes.contains(outer, point);
        }
    }

    /**
     * All directives reachable from {@code root}.
     */
    public static List<Directive> findAll(Node root) {
        // jtreesitter ties a match's captured Nodes to the SegmentAllocator passed
        // into findMatches. Closing the cursor frees its internal arena, so we
        // route captures into Arena.ofAuto() — backed by a Cleaner — which
        // outlives the cursor and stays reachable as long as the returned
        // Directive records do.
        Arena captureArena = Arena.ofAuto();
        try (var cursor = new QueryCursor(DIRECTIVES_QUERY)) {
            return cursor.findMatches(root, captureArena, null)
                .map(Directives::collect)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
        }
    }

    /**
     * Innermost directive whose range contains {@code pos}, if any.
     * Mirrors {@code get_directive_node} in the Rust LSP.
     */
    public static Optional<Directive> findContaining(Node root, Point pos) {
        return findAll(root).stream()
            .filter(d -> d.contains(pos))
            .findFirst();
    }

    private static Optional<Directive> collect(QueryMatch match) {
        Node nameNode = null;
        Node outer = null;
        var keys = new ArrayList<Node>();
        var values = new ArrayList<Node>();
        var fulls = new ArrayList<Node>();
        for (QueryCapture capture : match.captures()) {
            switch (capture.name()) {
                case "directive-name" -> nameNode = capture.node();
                case "outer" -> outer = capture.node();
                case "name" -> keys.add(capture.node());
                case "value" -> values.add(capture.node());
                case "argument-full" -> fulls.add(capture.node());
                default -> { /* ignore arguments-full */ }
            }
        }
        if (nameNode == null || outer == null) {
            return Optional.empty();
        }
        var args = new ArrayList<Argument>();
        for (int i = 0; i < keys.size() && i < values.size() && i < fulls.size(); i++) {
            args.add(new Argument(fulls.get(i), keys.get(i), values.get(i)));
        }
        return Optional.of(new Directive(outer, nameNode, args));
    }
}
