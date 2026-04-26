package no.sikt.graphitron.lsp.parsing;

import org.treesitter.TSNode;
import org.treesitter.TSPoint;
import org.treesitter.TSQuery;
import org.treesitter.TSQueryCapture;
import org.treesitter.TSQueryCursor;
import org.treesitter.TSQueryMatch;

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

    private static final TSQuery DIRECTIVES_QUERY =
        new TSQuery(GraphqlLanguage.get(), DIRECTIVES_QUERY_TEXT);

    private Directives() {}

    /**
     * Argument node within a directive: {@code name: value}.
     */
    public record Argument(TSNode full, TSNode key, TSNode value) {
        public boolean contains(TSPoint point) {
            return Nodes.contains(full, point);
        }
    }

    /**
     * Directive node: {@code @name(arg: value, ...)}.
     */
    public record Directive(TSNode outer, TSNode nameNode, List<Argument> arguments) {
        public boolean contains(TSPoint point) {
            return Nodes.contains(outer, point);
        }
    }

    /**
     * All directives reachable from {@code root}.
     */
    public static List<Directive> findAll(TSNode root) {
        var cursor = new TSQueryCursor();
        cursor.exec(DIRECTIVES_QUERY, root);

        var matches = new ArrayList<Directive>();
        TSQueryMatch match = new TSQueryMatch();
        while (cursor.nextMatch(match)) {
            collect(match).ifPresent(matches::add);
        }
        return matches;
    }

    /**
     * Innermost directive whose range contains {@code pos}, if any.
     * Mirrors {@code get_directive_node} in the Rust LSP.
     */
    public static Optional<Directive> findContaining(TSNode root, TSPoint pos) {
        return findAll(root).stream()
            .filter(d -> d.contains(pos))
            .findFirst();
    }

    private static Optional<Directive> collect(TSQueryMatch match) {
        TSNode nameNode = null;
        TSNode outer = null;
        var keys = new ArrayList<TSNode>();
        var values = new ArrayList<TSNode>();
        var fulls = new ArrayList<TSNode>();
        for (TSQueryCapture capture : match.getCaptures()) {
            String name = DIRECTIVES_QUERY.getCaptureNameForId(capture.getIndex());
            switch (name) {
                case "directive-name" -> nameNode = capture.getNode();
                case "outer" -> outer = capture.getNode();
                case "name" -> keys.add(capture.getNode());
                case "value" -> values.add(capture.getNode());
                case "argument-full" -> fulls.add(capture.getNode());
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
