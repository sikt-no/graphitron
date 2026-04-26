package no.sikt.graphitron.lsp.parsing;

import org.treesitter.TreeSitterGraphql;

/**
 * Singleton holder for the tree-sitter-graphql language binding.
 *
 * <p>Centralised here so the eventual migration from
 * {@code io.github.bonede:tree-sitter-graphql} (JNI, JDK 21) to
 * {@code io.github.tree-sitter:jtreesitter} + a separately packaged
 * tree-sitter-graphql grammar (FFM, JDK 25+) only touches one file.
 * Every other module that needs the language looks it up here.
 *
 * <p>Construction is cheap and thread-safe (the underlying bonede class
 * is stateless), but we hold one instance to avoid the allocation cost
 * on hot paths.
 */
public final class GraphqlLanguage {

    private static final TreeSitterGraphql INSTANCE = new TreeSitterGraphql();

    private GraphqlLanguage() {}

    public static TreeSitterGraphql get() {
        return INSTANCE;
    }
}
