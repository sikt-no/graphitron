package no.sikt.graphitron.lsp.parsing;

import graphql.language.Description;
import graphql.language.DirectiveDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.StringValue;
import graphql.language.Type;
import graphql.language.TypeName;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The LSP's directive vocabulary, keyed by GraphQL schema coordinates.
 * Composed of a hand-coded {@link Behavior} overlay and the parsed
 * {@link TypeDefinitionRegistry} of the bundled {@code directives.graphqls}.
 *
 * <p>The parsed registry contributes the full directive surface: every
 * directive, every arg, every input type and field, plus their description
 * strings. The overlay declares semantics ("complete this as a class name",
 * "validate this against the catalog's table set") only for the subset the
 * LSP knows how to act on today. Filing semantics for a new directive
 * becomes an additive overlay entry; the parse already exposes the
 * coordinate.
 *
 * <p><b>Structural invariant.</b> Every coordinate in the overlay must
 * resolve against the parsed registry. The constructor enforces this and
 * throws {@link LspStartupException} on any unresolved coordinate; the
 * R110-style drift that motivated this Spec becomes a loud startup
 * failure before any IDE session ever runs.
 *
 * <p>The vocabulary is read once at LSP startup and never invalidated.
 * The bundled SDL ships with the LSP jar; it is shape, not state.
 */
public record LspVocabulary(
    Map<SchemaCoordinate, Behavior> overlay,
    TypeDefinitionRegistry registry
) {

    /**
     * Javadoc-style {@code @deprecated} token in a description string,
     * applied to the parsed description text rather than raw SDL bytes.
     * The negative lookbehind avoids matching mid-word occurrences such
     * as {@code my@deprecated}.
     */
    private static final Pattern DESCRIPTION_DEPRECATED_TOKEN =
        Pattern.compile("(?<![A-Za-z0-9])@deprecated\\b");

    public LspVocabulary {
        overlay = Map.copyOf(overlay);
        for (var coord : overlay.keySet()) {
            if (!resolves(coord, registry)) {
                throw new LspStartupException(
                    "Schema coordinate " + coord + " does not resolve against "
                        + "directives.graphqls. Either update the overlay or "
                        + "check directive surface.");
            }
        }
    }

    /**
     * Production factory: parses the bundled {@code directives.graphqls}
     * and applies the canonical overlay declared at
     * {@link CanonicalOverlay#overlay()}.
     */
    public static LspVocabulary load() {
        return load(CanonicalOverlay.overlay());
    }

    /**
     * Test / dev factory: parses the bundled SDL and applies a caller-supplied
     * overlay. The structural invariant fires on construction.
     */
    public static LspVocabulary load(Map<SchemaCoordinate, Behavior> overlay) {
        return new LspVocabulary(overlay, parseDirectivesSdl());
    }

    /**
     * Parses an arbitrary SDL fixture instead of the bundled resource.
     * Used by unit tests that want to assert vocabulary behavior against
     * a synthetic directive surface.
     */
    public static LspVocabulary load(Map<SchemaCoordinate, Behavior> overlay, String sdl) {
        return new LspVocabulary(overlay, new SchemaParser().parse(sdl));
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
     *
     * <p>Replaces the per-consumer {@code readSiblingValue} +
     * {@code readSiblingObjectField} helpers that lived in
     * {@code MethodCompletions}, {@code Hovers}, and {@code Diagnostics}.
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
            if (child == null || !"object_field".equals(child.getType())) continue;
            Node nameNode = childOfKind(child, "name");
            Node valueNode = childOfKind(child, "value");
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
        Node best = "object_value".equals(node.getType()) ? node : null;
        for (int i = 0; i < node.getChildCount(); i++) {
            Node descendant = enclosingObjectValue(node.getChild(i).orElse(null), pos);
            if (descendant != null) best = descendant;
        }
        return best;
    }

    private static Node enclosingObjectValueOf(Node root, Node leafValue) {
        if (root == null) return null;
        if (!nodeContains(root, leafValue)) return null;
        Node best = "object_value".equals(root.getType()) ? root : null;
        for (int i = 0; i < root.getChildCount(); i++) {
            Node descendant = enclosingObjectValueOf(root.getChild(i).orElse(null), leafValue);
            if (descendant != null) best = descendant;
        }
        return best;
    }

    private static boolean nodeContains(Node parent, Node child) {
        return parent.getStartByte() <= child.getStartByte()
            && parent.getEndByte() >= child.getEndByte();
    }

    /**
     * Walks every coordinate-bearing leaf inside {@code directive} and
     * returns each as a {@link Leaf} pair (coordinate plus its tree-sitter
     * value node). Used by document-wide consumers (today: {@code Diagnostics})
     * that need to dispatch validators at every coordinate the document
     * carries, not just one cursor position.
     *
     * <p>Single walk replaces the per-consumer
     * {@code DirectiveDefinitions.argsByInputType} + nested-object-field
     * collection idiom. The traversal mirrors {@link #coordinateAt}
     * structurally — same registry-driven type chain, same input-type
     * field tree — but emits every leaf rather than the one under the
     * cursor.
     */
    public List<Leaf> leafCoordinates(Directives.Directive directive, byte[] source) {
        String directiveName = Nodes.text(directive.nameNode(), source);
        var dirDef = registry.getDirectiveDefinition(directiveName);
        if (dirDef.isEmpty()) return List.of();
        var out = new ArrayList<Leaf>();
        for (var arg : directive.arguments()) {
            String argName = Nodes.text(arg.key(), source);
            var argDef = findInputValue(dirDef.get().getInputValueDefinitions(), argName);
            if (argDef.isEmpty()) continue;
            var argCoord = new SchemaCoordinate.DirectiveArg(directiveName, argName);
            emitLeaf(argCoord, arg.value(), out);
            String argType = unwrapToInputTypeName(argDef.get().getType());
            if (argType != null) {
                descendLeaves(arg.value(), argType, source, out);
            }
        }
        return out;
    }

    private void descendLeaves(Node node, String currentType, byte[] source, List<Leaf> out) {
        if (node == null) return;
        if ("object_field".equals(node.getType())) {
            Node nameNode = childOfKind(node, "name");
            Node valueNode = childOfKind(node, "value");
            if (nameNode != null && valueNode != null) {
                String fieldName = Nodes.text(nameNode, source);
                var fieldCoord = new SchemaCoordinate.InputField(currentType, fieldName);
                emitLeaf(fieldCoord, valueNode, out);
                var inputType = registry.getTypeOrNull(currentType, InputObjectTypeDefinition.class);
                if (inputType != null) {
                    var fieldDef = findInputValue(inputType.getInputValueDefinitions(), fieldName);
                    if (fieldDef.isPresent()) {
                        String nextType = unwrapToInputTypeName(fieldDef.get().getType());
                        if (nextType != null) {
                            descendLeaves(valueNode, nextType, source, out);
                            return;
                        }
                    }
                }
            }
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            descendLeaves(node.getChild(i).orElse(null), currentType, source, out);
        }
    }

    /**
     * Emits one {@link Leaf} for {@code valueNode} under {@code coord}.
     * For a {@code list_value} wrapper the leaf is fanned out into one per
     * element, all keyed on the same outer coordinate. The contract this
     * pins: {@link Leaf#valueNode()} is the scalar value node, never an
     * enclosing {@code list_value}. Consumers ({@code Diagnostics},
     * {@code Hovers}) treat the leaf's value as a single scalar, so a
     * list-shaped value must decompose at emit time or the consumers
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
            // Nested lists are transparently fanned out so any future
            // list-of-list directive arg falls in for free.
            emitLeaf(coord, child, out);
        }
    }

    /** Returns the {@code list_value} reached from {@code node} via at most one wrapper, else null. */
    private static Node listValueOf(Node node) {
        if (node == null) return null;
        if ("list_value".equals(node.getType())) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            Node child = node.getChild(i).orElse(null);
            if (child != null && "list_value".equals(child.getType())) return child;
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
     */
    public record CursorLocation(SchemaCoordinate coordinate, Node leafNode) {}

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
        var dirDef = registry.getDirectiveDefinition(directiveName);
        if (dirDef.isEmpty()) {
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
            var argDef = findInputValue(dirDef.get().getInputValueDefinitions(), argName);
            if (argDef.isEmpty()) return Optional.empty();
            String currentType = unwrapToInputTypeName(argDef.get().getType());
            if (currentType == null) return Optional.empty();

            // Walk every level except the leaf; the leaf's name plus the
            // enclosing input type is the coordinate.
            for (int i = 0; i < fieldChain.size() - 1; i++) {
                var inputType = registry.getTypeOrNull(currentType, InputObjectTypeDefinition.class);
                if (inputType == null) return Optional.empty();
                var stepField = findInputValue(inputType.getInputValueDefinitions(), fieldChain.get(i));
                if (stepField.isEmpty()) return Optional.empty();
                String next = unwrapToInputTypeName(stepField.get().getType());
                if (next == null) return Optional.empty();
                currentType = next;
            }
            coord = new SchemaCoordinate.InputField(
                currentType, fieldChain.get(fieldChain.size() - 1));
        }
        return Optional.of(new CursorLocation(coord, leaf));
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
        String type = node.getType();
        if ("string_value".equals(type)) return node;
        Node best = isLeafKind(type) ? node : null;
        for (int i = 0; i < node.getChildCount(); i++) {
            Node descendant = innermostLeafAt(node.getChild(i).orElse(null), pos);
            if (descendant != null) best = descendant;
        }
        return best;
    }

    private static boolean isLeafKind(String type) {
        return "enum_value".equals(type) || "name".equals(type);
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
        if ("object_field".equals(node.getType())) {
            Node nameNode = childOfKind(node, "name");
            Node valueNode = childOfKind(node, "value");
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

    private static Node childOfKind(Node parent, String kind) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            Node child = parent.getChild(i).orElse(null);
            if (child != null && kind.equals(child.getType())) return child;
        }
        return null;
    }

    /**
     * Unwraps {@code !} and {@code []} wrappers to expose the base
     * {@link TypeName}. Returns null if the type does not bottom out at
     * a named type (which never happens for valid SDL).
     */
    public static String unwrapToInputTypeName(Type<?> type) {
        Type<?> current = type;
        while (current != null) {
            if (current instanceof TypeName tn) return tn.getName();
            if (current instanceof NonNullType nn) {
                current = nn.getType();
                continue;
            }
            if (current instanceof ListType lt) {
                current = lt.getType();
                continue;
            }
            return null;
        }
        return null;
    }

    /**
     * Returns the SDL docstring (description) on the parsed registry's
     * definition of {@code coord}. Empty if the coordinate has no
     * description; whitespace-only descriptions remain present (callers
     * filter as needed).
     *
     * <p>Used by {@code Hovers} as the default hover content for any
     * coordinate without a richer behavior arm: every directive,
     * argument, input type, and input field carries description prose
     * in the parsed registry, so editing the SDL is the authoring path
     * for hover content.
     */
    public Optional<String> descriptionOf(SchemaCoordinate coord) {
        return switch (coord) {
            case SchemaCoordinate.Directive d ->
                findDirective(d.name()).flatMap(x -> descriptionText(x.getDescription()));
            case SchemaCoordinate.DirectiveArg da ->
                findDirective(da.directive())
                    .flatMap(x -> findInputValue(x.getInputValueDefinitions(), da.arg()))
                    .flatMap(v -> descriptionText(v.getDescription()));
            case SchemaCoordinate.InputType t ->
                findInputType(t.name()).flatMap(x -> descriptionText(x.getDescription()));
            case SchemaCoordinate.InputField f ->
                findInputType(f.type())
                    .flatMap(x -> findInputValue(x.getInputValueDefinitions(), f.field()))
                    .flatMap(v -> descriptionText(v.getDescription()));
        };
    }

    /**
     * Returns every coordinate the parsed registry marks deprecated, in
     * either the native {@code @deprecated(reason:)} form (member-level)
     * or the docstring {@code @deprecated} convention (whole-directive).
     *
     * <p>Walks the directive surface twice: every directive plus its args
     * for native deprecations and docstring conventions, every input type
     * plus its fields for native deprecations. Used by
     * {@code SdlActionDriftTest} to assert that {@link
     * no.sikt.graphitron.lsp.code_action.SdlActions} stays in sync with
     * the SDL — every action targets a real marker, every marker is
     * covered by an action or the manual-migration allow-list.
     */
    public java.util.Set<SchemaCoordinate> deprecatedCoordinates() {
        var out = new java.util.LinkedHashSet<SchemaCoordinate>();
        for (var directive : registry.getDirectiveDefinitions().values()) {
            var dCoord = new SchemaCoordinate.Directive(directive.getName());
            if (deprecationOf(dCoord).isPresent()) {
                out.add(dCoord);
            }
            for (var arg : directive.getInputValueDefinitions()) {
                var aCoord = new SchemaCoordinate.DirectiveArg(directive.getName(), arg.getName());
                if (deprecationOf(aCoord).isPresent()) {
                    out.add(aCoord);
                }
            }
        }
        for (var inputType : registry.getTypes(InputObjectTypeDefinition.class)) {
            for (var field : inputType.getInputValueDefinitions()) {
                var fCoord = new SchemaCoordinate.InputField(inputType.getName(), field.getName());
                if (deprecationOf(fCoord).isPresent()) {
                    out.add(fCoord);
                }
            }
        }
        return out;
    }

    /**
     * Returns deprecation info for {@code coord}, in either the native
     * {@code @deprecated(reason:)} form (member-level) or the docstring
     * {@code @deprecated} convention (whole-directive). Empty if the
     * coordinate is not deprecated.
     */
    public Optional<DeprecationInfo> deprecationOf(SchemaCoordinate coord) {
        return switch (coord) {
            case SchemaCoordinate.Directive d -> directiveDocstringDeprecation(d.name());
            case SchemaCoordinate.DirectiveArg da -> directiveArgNativeDeprecation(da.directive(), da.arg());
            case SchemaCoordinate.InputField f -> inputFieldNativeDeprecation(f.type(), f.field());
            case SchemaCoordinate.InputType ignored -> Optional.empty();
        };
    }

    private Optional<DeprecationInfo> directiveDocstringDeprecation(String name) {
        return findDirective(name)
            .flatMap(d -> descriptionText(d.getDescription()))
            .filter(text -> DESCRIPTION_DEPRECATED_TOKEN.matcher(text).find())
            .map(DeprecationInfo::docstring);
    }

    private Optional<DeprecationInfo> directiveArgNativeDeprecation(String directive, String arg) {
        return findDirective(directive)
            .flatMap(d -> findInputValue(d.getInputValueDefinitions(), arg))
            .flatMap(LspVocabulary::nativeDeprecationReason)
            .map(DeprecationInfo::native_);
    }

    private Optional<DeprecationInfo> inputFieldNativeDeprecation(String type, String field) {
        return findInputType(type)
            .flatMap(t -> findInputValue(t.getInputValueDefinitions(), field))
            .flatMap(LspVocabulary::nativeDeprecationReason)
            .map(DeprecationInfo::native_);
    }

    private Optional<DirectiveDefinition> findDirective(String name) {
        for (var d : registry.getDirectiveDefinitions().values()) {
            if (d.getName().equals(name)) return Optional.of(d);
        }
        return Optional.empty();
    }

    private Optional<InputObjectTypeDefinition> findInputType(String name) {
        return Optional.ofNullable(registry.getTypeOrNull(name, InputObjectTypeDefinition.class));
    }

    /**
     * Linear lookup for {@link InputValueDefinition} by name in a list. Used
     * in three call sites internal to {@link LspVocabulary} plus
     * {@code Diagnostics} (unknown-arg + required-arg validation) and
     * {@code ArgNameCompletions} (arg-name completion). The graphql-java API
     * exposes the lists but no name-keyed accessor; this helper avoids
     * duplicating the loop in every consumer.
     */
    public static Optional<InputValueDefinition> findInputValue(
        java.util.List<InputValueDefinition> values, String name) {
        for (var v : values) {
            if (v.getName().equals(name)) return Optional.of(v);
        }
        return Optional.empty();
    }

    private static Optional<String> nativeDeprecationReason(InputValueDefinition v) {
        for (var dir : v.getDirectives("deprecated")) {
            for (var arg : dir.getArguments()) {
                if (arg.getName().equals("reason") && arg.getValue() instanceof StringValue s) {
                    return Optional.of(s.getValue());
                }
            }
            return Optional.of("");
        }
        return Optional.empty();
    }

    private static Optional<String> descriptionText(Description description) {
        return Optional.ofNullable(description).map(Description::getContent);
    }

    private static boolean resolves(SchemaCoordinate coord, TypeDefinitionRegistry registry) {
        return switch (coord) {
            case SchemaCoordinate.Directive d ->
                registry.getDirectiveDefinitions().containsKey(d.name());
            case SchemaCoordinate.DirectiveArg da ->
                registry.getDirectiveDefinition(da.directive())
                    .map(DirectiveDefinition::getInputValueDefinitions)
                    .map(args -> args.stream().anyMatch(a -> a.getName().equals(da.arg())))
                    .orElse(false);
            case SchemaCoordinate.InputType t ->
                registry.getTypeOrNull(t.name(), InputObjectTypeDefinition.class) != null;
            case SchemaCoordinate.InputField f ->
                Optional.ofNullable(registry.getTypeOrNull(f.type(), InputObjectTypeDefinition.class))
                    .map(InputObjectTypeDefinition::getInputValueDefinitions)
                    .map(fs -> fs.stream().anyMatch(v -> v.getName().equals(f.field())))
                    .orElse(false);
        };
    }

    private static TypeDefinitionRegistry parseDirectivesSdl() {
        return new SchemaParser().parse(RewriteSchemaLoader.directivesSdl());
    }

    /**
     * Carrier for deprecation info, agnostic to whether the marker came
     * from native {@code @deprecated(reason:)} or graphitron's docstring
     * {@code @deprecated} convention. Consumers query
     * {@link LspVocabulary#deprecationOf} without caring which shape declared it.
     *
     * @param reason     the replacement-hint text. For native deprecation,
     *                   the {@code reason:} arg's value (empty string when
     *                   {@code @deprecated} carries no reason). For
     *                   docstring deprecation, the whole description text
     *                   (the recogniser-side parse of "what to use instead"
     *                   lives in the consumer that surfaces it; this
     *                   record only declares "marker present" plus context).
     * @param shape      whether the marker came from the native form or
     *                   the docstring convention; informs auto-migration
     *                   policy in {@code SdlActions}.
     */
    public record DeprecationInfo(String reason, Shape shape) {
        public enum Shape { NATIVE, DOCSTRING }

        static DeprecationInfo native_(String reason) {
            return new DeprecationInfo(reason, Shape.NATIVE);
        }

        static DeprecationInfo docstring(String description) {
            return new DeprecationInfo(description, Shape.DOCSTRING);
        }
    }

    /**
     * Thrown from the {@link LspVocabulary} constructor when an overlay
     * coordinate fails to resolve against the parsed registry. R110-style
     * drift becomes a loud startup failure rather than a silent
     * unknown-directive at request time.
     */
    public static final class LspStartupException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public LspStartupException(String message) {
            super(message);
        }
    }

    /**
     * The canonical overlay shipped with the LSP. The full set of
     * coordinates the LSP knows how to act on today; mirrors the table
     * in the R119 spec body.
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
            // R43: @tableMethod is flat (className, method, argMapping directly on the directive),
            // mirroring @sourceRow rather than wrapping an ExternalCodeReference.
            var tableMethodClassName = new SchemaCoordinate.DirectiveArg("tableMethod", "className");
            out.put(tableMethodClassName, new Behavior.ClassNameBinding());
            out.put(new SchemaCoordinate.DirectiveArg("tableMethod", "method"),
                new Behavior.MethodNameBinding(tableMethodClassName));
            out.put(new SchemaCoordinate.DirectiveArg("tableMethod", "argMapping"),
                new Behavior.ArgMappingBinding());
            out.put(new SchemaCoordinate.DirectiveArg("table", "name"),
                new Behavior.CatalogTableBinding());
            out.put(new SchemaCoordinate.DirectiveArg("field", "name"),
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
