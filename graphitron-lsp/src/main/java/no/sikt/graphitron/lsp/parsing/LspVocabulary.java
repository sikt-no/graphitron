package no.sikt.graphitron.lsp.parsing;

import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.facts.DirectiveSurface;
import no.sikt.graphitron.lsp.trace.LspTrace;
import no.sikt.graphitron.model.read.StoreHandle;

import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.ENUM_VALUE;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.LIST_VALUE;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.NAME;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.OBJECT_FIELD;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.OBJECT_VALUE;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.STRING_VALUE;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.VALUE;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The LSP's directive vocabulary, keyed by GraphQL schema coordinates. Composed of a hand-coded
 * {@link Behavior} overlay and the graph's own {@link DirectiveSurface}.
 *
 * <p>The surface contributes the shape: every directive, every argument, every input type and field,
 * which is what makes a cursor position resolvable to a coordinate. The overlay declares semantics
 * ("complete this as a class name", "validate this against the catalog's table set") only for the
 * subset the LSP knows how to act on. Filing semantics for a new directive is an additive overlay
 * entry; the surface already exposes the coordinate.
 *
 * <p><b>Everything here comes from the store.</b> The language server parses one thing, the buffer
 * the author is editing, with tree-sitter, and reads everything else as rows. The vocabulary was the
 * last holdout: it kept a graphql-java {@code TypeDefinitionRegistry} of graphitron's bundled
 * {@code directives.graphqls} and resolved coordinates against that. Capture parses the bundled file
 * like any other schema file, so the registry was a second reading of a document the store already
 * held, and two readings of one document are two opinions about it. What a coordinate means in prose
 * is a row too, read through {@link no.sikt.graphitron.lsp.facts.SdlDescriptions}.
 *
 * <p>Held rather than re-read, on {@link DirectiveSurface}'s terms: the diagnostics walk resolves
 * coordinates while reading nothing, so the surface is loaded once and carried. A vocabulary built
 * from {@link DirectiveSurface#empty()} answers no coordinate at all, which is what a session with
 * no store has to say about a document.
 *
 * <p>The overlay is not checked against the surface here. Every coordinate it names must resolve, but
 * that is a claim about the SDL graphitron ships rather than about whichever graph a session happens
 * to have open, so it is asserted by a test that captures the shipped file and reads the surface
 * back. A running session that finds an overlay coordinate missing has an uncaptured or partial
 * graph, and refusing to start over that would take the editor down for a condition the next capture
 * fixes.
 */
public record LspVocabulary(
    Map<SchemaCoordinate, Behavior> overlay,
    DirectiveSurface surface
) {

    public LspVocabulary {
        overlay = Map.copyOf(overlay);
    }

    /** The canonical overlay over {@code store}'s graph, which is what a session runs on. */
    public static LspVocabulary load(StoreHandle store) {
        return load(CanonicalOverlay.overlay(), store);
    }

    /** The same, with a caller-supplied overlay, for the tests whose subject is the overlay itself. */
    public static LspVocabulary load(Map<SchemaCoordinate, Behavior> overlay, StoreHandle store) {
        // Traced because this is not as cheap as its call sites assume: it reads the graph's whole
        // directive surface. A trace showing one of these per request identifies a hot path that
        // should be holding the workspace's cached instance instead.
        try (var _ = LspTrace.span("vocabulary.load")) {
            return new LspVocabulary(overlay, DirectiveSurface.load(store));
        }
    }

    /**
     * The vocabulary of a session with no store: the canonical overlay over an empty surface, which
     * resolves no cursor to any coordinate.
     */
    public static LspVocabulary empty() {
        return new LspVocabulary(CanonicalOverlay.overlay(), DirectiveSurface.empty());
    }

    /**
     * Reads the string value at {@code siblingCoord}, scoped to the same
     * directive (and, for an {@link SchemaCoordinate.InputField} sibling,
     * the same enclosing object_value as {@code anchor}).
     *
     * <p>Two anchor shapes overload this method: {@link Point} (cursor
     * position, used by completion / hover paths) and {@link Node}
     * (a leaf's value node, used by the diagnostics document walk).
     * Both delegate to the same containment-based walk; the byte-range
     * vs. point-range distinction is the only thing that varies.
     */
    public Optional<String> siblingStringAt(
        Directives.Directive directive, Point pos,
        SchemaCoordinate siblingCoord, byte[] source
    ) {
        return switch (siblingCoord) {
            case SchemaCoordinate.DirectiveArg da -> readDirectiveArgString(directive, da.arg(), source);
            case SchemaCoordinate.InputField f ->
                readSiblingObjectField(directive, pos, f.field(), source);
            case SchemaCoordinate.Directive ignored -> Optional.empty();
            case SchemaCoordinate.InputType ignored -> Optional.empty();
        };
    }

    /** Node-anchored overload; see {@link #siblingStringAt(Directives.Directive, Point, SchemaCoordinate, byte[])}. */
    public Optional<String> siblingStringAt(
        Directives.Directive directive, Node anchor,
        SchemaCoordinate siblingCoord, byte[] source
    ) {
        return switch (siblingCoord) {
            case SchemaCoordinate.DirectiveArg da -> readDirectiveArgString(directive, da.arg(), source);
            case SchemaCoordinate.InputField f ->
                readSiblingObjectField(directive, anchor, f.field(), source);
            case SchemaCoordinate.Directive ignored -> Optional.empty();
            case SchemaCoordinate.InputType ignored -> Optional.empty();
        };
    }

    private static Optional<String> readDirectiveArgString(
        Directives.Directive directive, String argName, byte[] source
    ) {
        for (var arg : directive.arguments()) {
            if (argName.equals(Nodes.text(arg.key(), source))) {
                String raw = Nodes.unquote(Nodes.text(arg.value(), source));
                return raw.isEmpty() ? Optional.empty() : Optional.of(raw);
            }
        }
        return Optional.empty();
    }

    private static Optional<String> readSiblingObjectField(
        Directives.Directive directive, Point pos, String fieldName, byte[] source
    ) {
        for (var arg : directive.arguments()) {
            if (!arg.contains(pos)) continue;
            Node objectValue = enclosingObjectValue(arg.value(), pos);
            return readSiblingFromObject(objectValue, fieldName, source);
        }
        return Optional.empty();
    }

    private static Optional<String> readSiblingObjectField(
        Directives.Directive directive, Node anchor, String fieldName, byte[] source
    ) {
        for (var arg : directive.arguments()) {
            Node objectValue = enclosingObjectValueOf(arg.value(), anchor);
            if (objectValue == null) continue;
            return readSiblingFromObject(objectValue, fieldName, source);
        }
        return Optional.empty();
    }

    private static Optional<String> readSiblingFromObject(
        Node objectValue, String fieldName, byte[] source
    ) {
        if (objectValue == null) return Optional.empty();
        for (int i = 0; i < objectValue.getChildCount(); i++) {
            Node child = objectValue.getChild(i).orElse(null);
            if (child == null || !OBJECT_FIELD.matches(child)) continue;
            Node nameNode = Nodes.childOfKind(child, NAME);
            Node valueNode = Nodes.childOfKind(child, VALUE);
            if (nameNode == null || valueNode == null) continue;
            if (fieldName.equals(Nodes.text(nameNode, source))) {
                String raw = Nodes.unquote(Nodes.text(valueNode, source));
                return raw.isEmpty() ? Optional.empty() : Optional.of(raw);
            }
        }
        return Optional.empty();
    }

    private static Node enclosingObjectValue(Node node, Point pos) {
        if (node == null || !Nodes.contains(node, pos)) return null;
        Node best = OBJECT_VALUE.matches(node) ? node : null;
        for (int i = 0; i < node.getChildCount(); i++) {
            Node descendant = enclosingObjectValue(node.getChild(i).orElse(null), pos);
            if (descendant != null) best = descendant;
        }
        return best;
    }

    private static Node enclosingObjectValueOf(Node root, Node leafValue) {
        if (root == null) return null;
        if (!Nodes.nodeContains(root, leafValue)) return null;
        Node best = OBJECT_VALUE.matches(root) ? root : null;
        for (int i = 0; i < root.getChildCount(); i++) {
            Node descendant = enclosingObjectValueOf(root.getChild(i).orElse(null), leafValue);
            if (descendant != null) best = descendant;
        }
        return best;
    }

    /**
     * Walks every coordinate-bearing leaf inside {@code directive} and
     * returns each as a {@link Leaf} pair (coordinate plus its tree-sitter
     * value node). Used by document-wide consumers ({@code Diagnostics})
     * that need to dispatch validators at every coordinate the document
     * carries, not just one cursor position.
     *
     * <p>The traversal mirrors {@link #coordinateAt} structurally (same
     * registry-driven type chain, same input-type field tree) but emits
     * every leaf rather than the one under the cursor.
     */
    public List<Leaf> leafCoordinates(Directives.Directive directive, byte[] source) {
        String directiveName = Nodes.text(directive.nameNode(), source);
        if (!surface.declaresDirective(directiveName)) return List.of();
        var out = new ArrayList<Leaf>();
        for (var arg : directive.arguments()) {
            String argName = Nodes.text(arg.key(), source);
            var argType = surface.argumentNamedType(directiveName, argName);
            if (argType.isEmpty()) continue;
            var argCoord = new SchemaCoordinate.DirectiveArg(directiveName, argName);
            emitLeaf(argCoord, arg.value(), out);
            descendLeaves(arg.value(), argType.get(), source, out);
        }
        return out;
    }

    private void descendLeaves(Node node, String currentType, byte[] source, List<Leaf> out) {
        if (node == null) return;
        if (OBJECT_FIELD.matches(node)) {
            Node nameNode = Nodes.childOfKind(node, NAME);
            Node valueNode = Nodes.childOfKind(node, VALUE);
            if (nameNode != null && valueNode != null) {
                String fieldName = Nodes.text(nameNode, source);
                var fieldCoord = new SchemaCoordinate.InputField(currentType, fieldName);
                emitLeaf(fieldCoord, valueNode, out);
                var nextType = surface.inputFieldNamedType(currentType, fieldName);
                if (nextType.isPresent()) {
                    descendLeaves(valueNode, nextType.get(), source, out);
                    return;
                }
            }
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            descendLeaves(node.getChild(i).orElse(null), currentType, source, out);
        }
    }

    /**
     * Emits one {@link Leaf} for {@code valueNode} under {@code coord},
     * fanning a {@code list_value} wrapper out into one leaf per element,
     * all keyed on the same outer coordinate (the scalar-value contract
     * on {@link Leaf#valueNode()}). Consumers treat a leaf's value as a
     * single scalar, so a list-shaped value must decompose here or they
     * would read the whole list as one mangled token.
     */
    private static void emitLeaf(SchemaCoordinate coord, Node valueNode, List<Leaf> out) {
        Node listValue = listValueOf(valueNode);
        if (listValue == null) {
            out.add(new Leaf(coord, valueNode));
            return;
        }
        for (int i = 0; i < listValue.getChildCount(); i++) {
            Node child = listValue.getChild(i).orElse(null);
            if (child == null) continue;
            String type = child.getType();
            // Skip syntactic tokens ('[', ']', ',') and stray newlines.
            if ("[".equals(type) || "]".equals(type) || ",".equals(type) || "comma".equals(type)) continue;
            // Nested lists fan out recursively.
            emitLeaf(coord, child, out);
        }
    }

    /** Returns the {@code list_value} reached from {@code node} via at most one wrapper, else null. */
    private static Node listValueOf(Node node) {
        if (node == null) return null;
        if (LIST_VALUE.matches(node)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            Node child = node.getChild(i).orElse(null);
            if (LIST_VALUE.matches(child)) return child;
        }
        return null;
    }

    /**
     * Coordinate-bearing leaf inside a directive: the coordinate that
     * keys the leaf's behavior plus the tree-sitter node carrying the
     * leaf's value. {@code valueNode} is always a scalar value node
     * (string literal, int literal, object literal, etc.), never an
     * enclosing {@code list_value}; list-shaped directive args fan out
     * into one {@code Leaf} per element at emit time.
     */
    public record Leaf(SchemaCoordinate coord, Node valueNode) {}

    /** Returns the {@link Behavior} the overlay declares for {@code coord}, if any. */
    public Optional<Behavior> behaviorAt(SchemaCoordinate coord) {
        return Optional.ofNullable(overlay.get(coord));
    }

    /**
     * Cursor location resolved against the directive surface: the schema
     * coordinate keyed by the position plus the tree-sitter leaf node
     * (value or identifier) the cursor sits inside. The leaf is one of
     * {@code string_value}, {@code enum_value}, or {@code name}, the
     * kinds the completion-range helper knows how to slice; consumers
     * that only need the coordinate use {@link #coordinateAt} instead.
     *
     * <p>Also carries the enclosing directive name. Coordinates such
     * as {@code InputField("ExternalCodeReference", "className")} are shared
     * across directives ({@code @record} and {@code @enum} both reference
     * {@code ExternalCodeReference}), so a value provider that must
     * discriminate by directive (e.g. the {@code @record} className carve-out)
     * cannot derive it from the coordinate alone and reads this field.
     */
    public record CursorLocation(SchemaCoordinate coordinate, Node leafNode, String directiveName) {}

    /**
     * Coordinate-only view of {@link #locateAt}: the schema coordinate
     * at the cursor inside {@code directive}, if any. Wraps
     * {@code locateAt} and discards the leaf node; non-completion callers
     * ({@code Hovers}) consume this shape.
     *
     * <p>Cases:
     * <ul>
     *   <li><b>Cursor on a directive arg's value (no nesting).</b>
     *       Returns {@link SchemaCoordinate.DirectiveArg}, e.g. cursor on
     *       {@code @table(name: "x|")} returns {@code @table(name:)}.</li>
     *   <li><b>Cursor inside a nested {@code object_field}.</b> Returns
     *       {@link SchemaCoordinate.InputField} keyed on the leaf's
     *       parent input type. {@code @reference(path: [{table: "x|"}])}
     *       returns {@code ReferenceElement.table};
     *       {@code @reference(path: [{condition: {className: "x|"}}])}
     *       returns {@code ExternalCodeReference.className}.</li>
     *   <li><b>Cursor outside any arg's value, on an unknown directive,
     *       or on whitespace between fields.</b> Empty.</li>
     * </ul>
     */
    public Optional<SchemaCoordinate> coordinateAt(
        Directives.Directive directive,
        Point pos,
        byte[] source
    ) {
        return locateAt(directive, pos, source).map(CursorLocation::coordinate);
    }

    /**
     * Computes the {@link CursorLocation} at the cursor position inside
     * {@code directive}: the {@link SchemaCoordinate} keyed by the
     * cursor's position in the directive's argument tree, plus the
     * tree-sitter leaf node (value or identifier) the cursor sits inside.
     *
     * <p>The leaf walk descends through the directive's argument-list
     * tree the same way as the coordinate walk, then returns the deepest
     * leaf-kind node ({@code string_value}, {@code enum_value}, or bare
     * {@code name}) containing {@code pos}. Used by the completion
     * dispatch site to compute an explicit replace-range for each
     * {@link org.eclipse.lsp4j.TextEdit}, sidestepping client
     * word-boundary heuristics that would otherwise concatenate the
     * candidate with a partial prefix.
     *
     * <p>Returns empty when the cursor is outside any directive arg's
     * value (whitespace inside the directive's parens, on the arg-name
     * side of an arg, or inside an {@code object_value} but outside
     * every {@code object_field}'s value); those positions flow into
     * the {@code ArgNameCompletions} fallback at the dispatch site,
     * which has its own walk for partial arg-name identifiers.
     */
    public Optional<CursorLocation> locateAt(
        Directives.Directive directive,
        Point pos,
        byte[] source
    ) {
        String directiveName = Nodes.text(directive.nameNode(), source);
        if (!surface.declaresDirective(directiveName)) {
            return Optional.empty();
        }
        Directives.Argument enclosing = null;
        for (var arg : directive.arguments()) {
            if (arg.contains(pos)) {
                enclosing = arg;
                break;
            }
        }
        if (enclosing == null) return Optional.empty();
        if (!Nodes.contains(enclosing.value(), pos)) return Optional.empty();

        Node leaf = innermostLeafAt(enclosing.value(), pos);
        if (leaf == null) return Optional.empty();

        String argName = Nodes.text(enclosing.key(), source);
        var fieldChain = collectObjectFieldChain(enclosing.value(), pos, source);

        SchemaCoordinate coord;
        if (fieldChain.isEmpty()) {
            coord = new SchemaCoordinate.DirectiveArg(directiveName, argName);
        } else {
            var argType = surface.argumentNamedType(directiveName, argName);
            if (argType.isEmpty()) return Optional.empty();
            String currentType = argType.get();

            // Walk every level except the leaf; the leaf's name plus the
            // enclosing input type is the coordinate.
            for (int i = 0; i < fieldChain.size() - 1; i++) {
                var next = surface.inputFieldNamedType(currentType, fieldChain.get(i));
                if (next.isEmpty()) return Optional.empty();
                currentType = next.get();
            }
            coord = new SchemaCoordinate.InputField(
                currentType, fieldChain.get(fieldChain.size() - 1));
        }
        return Optional.of(new CursorLocation(coord, leaf, directiveName));
    }

    /**
     * Deepest tree-sitter node containing {@code pos} whose kind is one
     * of {@code string_value}, {@code enum_value}, or {@code name}.
     * Descent stops at {@code string_value} so the anonymous
     * delimiter / content tokens inside a string literal never surface
     * as the leaf.
     */
    private static Node innermostLeafAt(Node node, Point pos) {
        if (node == null || !Nodes.contains(node, pos)) return null;
        if (STRING_VALUE.matches(node)) return node;
        Node best = isLeafKind(node) ? node : null;
        for (int i = 0; i < node.getChildCount(); i++) {
            Node descendant = innermostLeafAt(node.getChild(i).orElse(null), pos);
            if (descendant != null) best = descendant;
        }
        return best;
    }

    private static boolean isLeafKind(Node node) {
        return ENUM_VALUE.matches(node) || NAME.matches(node);
    }

    /**
     * Outermost-first list of {@code object_field} names along the path
     * from {@code argRoot} to {@code pos}. Empty when the cursor is on
     * the arg value itself rather than inside any nested object literal.
     */
    private static List<String> collectObjectFieldChain(Node argRoot, Point pos, byte[] source) {
        var out = new ArrayList<String>();
        descend(argRoot, pos, source, out);
        return out;
    }

    private static void descend(Node node, Point pos, byte[] source, List<String> out) {
        if (node == null || !Nodes.contains(node, pos)) return;
        if (OBJECT_FIELD.matches(node)) {
            Node nameNode = Nodes.childOfKind(node, NAME);
            Node valueNode = Nodes.childOfKind(node, VALUE);
            // Only treat the cursor as on this field if it sits inside the
            // value, not the name. Cursor on the name is a separate case
            // (arg-name completion territory) and must not key as a
            // value-bearing coordinate.
            if (nameNode == null || valueNode == null
                || !Nodes.contains(valueNode, pos)) {
                return;
            }
            out.add(Nodes.text(nameNode, source));
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            descend(node.getChild(i).orElse(null), pos, source, out);
        }
    }

    /**
     * The canonical overlay shipped with the LSP: the full set of
     * coordinates the LSP knows how to act on.
     */
    public static final class CanonicalOverlay {
        private CanonicalOverlay() {}

        public static Map<SchemaCoordinate, Behavior> overlay() {
            var ecrClassName = new SchemaCoordinate.InputField("ExternalCodeReference", "className");
            var sourceRowClassName = new SchemaCoordinate.DirectiveArg("sourceRow", "className");
            var out = new LinkedHashMap<SchemaCoordinate, Behavior>();
            out.put(ecrClassName, new Behavior.ClassNameBinding());
            out.put(new SchemaCoordinate.InputField("ExternalCodeReference", "method"),
                new Behavior.MethodNameBinding(ecrClassName));
            out.put(new SchemaCoordinate.InputField("ExternalCodeReference", "argMapping"),
                new Behavior.ArgMappingBinding());
            out.put(sourceRowClassName, new Behavior.ClassNameBinding());
            out.put(new SchemaCoordinate.DirectiveArg("sourceRow", "method"),
                new Behavior.MethodNameBinding(sourceRowClassName));
            out.put(new SchemaCoordinate.DirectiveArg("table", "name"),
                new Behavior.CatalogTableBinding());
            out.put(new SchemaCoordinate.DirectiveArg("field", "name"),
                new Behavior.CatalogColumnBinding());
            // @defaultOrder(fields: [{name: ...}]) names a column on the list/connection
            // field's target (element-type) table. The FieldSort.name coordinate binds to the
            // same column behavior; which table that is comes from the site's own resolved scope
            // ({@link no.sikt.graphitron.lsp.facts.FieldColumnTable}, whose named-type rule
            // answers the element table) rather than the enclosing type's backing.
            out.put(new SchemaCoordinate.InputField("FieldSort", "name"),
                new Behavior.CatalogColumnBinding());
            out.put(new SchemaCoordinate.InputField("ReferenceElement", "key"),
                new Behavior.CatalogFkBinding());
            out.put(new SchemaCoordinate.InputField("ReferenceElement", "table"),
                new Behavior.CatalogTableBinding());
            out.put(new SchemaCoordinate.DirectiveArg("scalarType", "scalar"),
                new Behavior.ScalarTypeBinding());
            out.put(new SchemaCoordinate.DirectiveArg("node", "keyColumns"),
                new Behavior.CatalogColumnBinding());
            out.put(new SchemaCoordinate.DirectiveArg("nodeId", "typeName"),
                new Behavior.NodeTypeBinding());
            return out;
        }
    }
}
