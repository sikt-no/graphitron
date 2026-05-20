package no.sikt.graphitron.lsp.hover;

import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Point;

/**
 * R160 — sealed family of SDL-declaration coordinates that the classification-hover arm
 * recognises. Sibling to {@link no.sikt.graphitron.lsp.parsing.Behavior}, which carries
 * the directive-argument-binding shapes; this family carries the orthogonal axis of
 * <em>declaration positions</em> (a field-definition name token, a type-definition name
 * token) where the LSP's classification hover surfaces the {@code FieldClassification} /
 * {@code TypeClassification} projection.
 *
 * <p>Two permits at filing — {@link FieldDeclarationHover} and {@link TypeDeclarationHover}.
 * Further permits join only if new SDL declaration coordinates need hover content. The
 * sealed family keeps the LSP module's exhaustive switch obligation explicit: adding a
 * third declaration coordinate fails the dispatch in {@link DeclarationHovers} to compile
 * until the permit and its hover content land together.
 */
public sealed interface DeclarationHover
    permits DeclarationHover.FieldDeclarationHover,
            DeclarationHover.TypeDeclarationHover {

    /**
     * The tree-sitter {@code name} node whose range anchors the rendered hover. The
     * hover's reported range matches this node so editors highlight the declaration's
     * own name when the user mouses over it.
     */
    Node nameNode();

    /**
     * The classified coordinate identifying which projection entry the renderer looks up.
     */
    String coordinate();

    /**
     * A field declaration ({@code field_definition} or {@code input_value_definition}).
     * {@code coordinate} is {@code "ParentType.fieldName"} for projection lookup.
     */
    record FieldDeclarationHover(Node nameNode, String parentTypeName, String fieldName)
        implements DeclarationHover {
        @Override public String coordinate() { return parentTypeName + "." + fieldName; }
    }

    /**
     * A type declaration ({@code object_type_definition} and friends). {@code coordinate}
     * is the type name for projection lookup.
     */
    record TypeDeclarationHover(Node nameNode, String typeName) implements DeclarationHover {
        @Override public String coordinate() { return typeName; }
    }

    /** Inclusive containment of {@code pos} in the anchor name node. */
    default boolean contains(Point pos) {
        return no.sikt.graphitron.lsp.parsing.Nodes.contains(nameNode(), pos);
    }
}
