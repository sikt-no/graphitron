package no.sikt.graphitron.lsp.code_action;

import io.github.treesitter.jtreesitter.Node;
import no.sikt.graphitron.lsp.code_action.SdlAction.DeprecationTarget;
import no.sikt.graphitron.lsp.code_action.SdlAction.RewriteResult;
import no.sikt.graphitron.lsp.parsing.DirectiveDefinitions;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.Nodes;
import no.sikt.graphitron.lsp.parsing.Positions;
import no.sikt.graphitron.lsp.state.WorkspaceFile;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Registry of every {@link SdlAction} the LSP knows how to apply, plus
 * the allow-list for deprecations whose migration is intentionally
 * manual. {@code SdlActionDriftTest} asserts bidirectional coverage:
 * every action targets an existing deprecation marker in
 * {@code directives.graphqls}, and every marker is covered by either
 * an action or {@link #MANUAL_MIGRATION_DEPRECATIONS}.
 *
 * <p>{@code SdlAction} instances are bound to a
 * {@link CompletionData} catalog so the rewrite slot can read the
 * consumer's {@code namedReferences} map. The catalog is rebuilt by
 * the dev mojo when the consumer's classpath changes; the LSP fetches
 * a fresh action list per request.
 */
public final class SdlActions {

    private SdlActions() {}

    /**
     * Deprecations the LSP intentionally does not auto-migrate.
     * Drift-test invariant: every entry here must match a real
     * deprecation marker in {@code directives.graphqls}; orphan
     * entries fail the build the same way orphan deprecations do.
     */
    public static final Set<DeprecationTarget> MANUAL_MIGRATION_DEPRECATIONS = Set.of(
        // Per-field semantics differ across instances (the override
        // exists as a transition mechanism for legacy schemas); no
        // mechanical rewrite is correct.
        new DeprecationTarget.Member("@asConnection", "connectionName"),
        // The directive itself is deprecated, superseded by
        // @order(index:), but the migration shape is a per-call-site
        // rewrite from @index(name: "X") on an enum value to
        // @order(index: "X"); tractable as a future SdlAction but
        // not in R93's scope.
        new DeprecationTarget.WholeDirective("index")
    );

    /**
     * Returns every {@link SdlAction} bound to {@code catalog}. R93
     * ships one: the {@code ExternalCodeReference.name → className}
     * migration. Future deprecation migrations or directive renames
     * extend the list.
     */
    public static List<SdlAction> all(CompletionData catalog) {
        return List.of(
            externalCodeReferenceNameToClassName(catalog)
        );
    }

    /**
     * The R93 migration: rewrites
     * {@code ExternalCodeReference {name: "X"}} to
     * {@code ExternalCodeReference {className: "<resolved-FQN>"}}
     * when {@code "X"} resolves in the consumer's
     * {@code namedReferences} map. Sites whose name does not resolve
     * yield {@link RewriteResult.Skip} carrying the unresolved name.
     */
    public static SdlAction externalCodeReferenceNameToClassName(CompletionData catalog) {
        return new SdlAction(
            "Migrate `name:` to `className:`",
            Set.of(new DeprecationTarget.Member("ExternalCodeReference", "name")),
            SdlActions::detectLegacyNameSites,
            (file, match) -> rewriteNameToClassName(file, match, catalog.namedReferences())
        );
    }

    /**
     * Detects every legacy {@code name:} object_field inside an
     * {@code ExternalCodeReference} literal in the file. The legacy
     * shape is: a directive with an {@code ExternalCodeReference}
     * binding whose object value contains a {@code name:} field but
     * no {@code className:} field. Yields the {@code name:}
     * object_field node so the rewrite slot has the full range to
     * replace.
     */
    static Stream<Node> detectLegacyNameSites(WorkspaceFile file) {
        var ecrBindings = DirectiveDefinitions.argsByInputType("ExternalCodeReference");
        var matches = new java.util.ArrayList<Node>();
        for (var directive : Directives.findAll(file.tree().getRootNode())) {
            String name = Nodes.text(directive.nameNode(), file.source());
            for (var binding : ecrBindings) {
                if (!binding.directive().equals(name)) continue;
                collectLegacyNameSitesUnderDirective(directive, binding, file.source(), matches);
            }
        }
        return matches.stream();
    }

    private static void collectLegacyNameSitesUnderDirective(
        Directives.Directive directive,
        DirectiveDefinitions.InputTypeBinding binding,
        byte[] source,
        List<Node> out
    ) {
        if (binding.nestedPath()) {
            for (var arg : directive.arguments()) {
                collectInsideObjects(arg.value(), binding.argName(), source, out);
            }
            return;
        }
        for (var arg : directive.arguments()) {
            if (!binding.argName().equals(Nodes.text(arg.key(), source))) continue;
            Node legacy = legacyNameField(arg.value(), source);
            if (legacy != null) out.add(legacy);
        }
    }

    /**
     * Walks {@code root} looking for {@code object_field}s whose name
     * is {@code containerFieldName}; for each, descends into its
     * object value and emits the {@code name:} field if present and
     * unaccompanied by {@code className:}.
     */
    private static void collectInsideObjects(
        Node root, String containerFieldName, byte[] source, List<Node> out
    ) {
        if (root == null) return;
        if ("object_field".equals(root.getType())) {
            Node nameNode = childOfKind(root, "name");
            Node valueNode = childOfKind(root, "value");
            if (nameNode != null && valueNode != null
                && containerFieldName.equals(Nodes.text(nameNode, source))) {
                Node legacy = legacyNameField(valueNode, source);
                if (legacy != null) out.add(legacy);
            }
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            collectInsideObjects(root.getChild(i).orElse(null), containerFieldName, source, out);
        }
    }

    /**
     * Returns the {@code name:} object_field inside {@code objectValue}
     * if present and {@code className:} is not also present; otherwise
     * {@code null}. Tied to ExternalCodeReference's slots: the
     * legacy migration only fires when the consumer is using the old
     * form alone.
     */
    private static Node legacyNameField(Node objectValue, byte[] source) {
        Node nameField = null;
        boolean hasClassName = false;
        if (objectValue == null) return null;
        // Walk only the immediate object_value's object_fields.
        Node objectNode = unwrapToObject(objectValue);
        if (objectNode == null) return null;
        for (int i = 0; i < objectNode.getChildCount(); i++) {
            Node child = objectNode.getChild(i).orElse(null);
            if (child == null || !"object_field".equals(child.getType())) continue;
            Node fname = childOfKind(child, "name");
            if (fname == null) continue;
            String key = Nodes.text(fname, source);
            if ("name".equals(key)) nameField = child;
            else if ("className".equals(key)) hasClassName = true;
        }
        return hasClassName ? null : nameField;
    }

    private static Node unwrapToObject(Node n) {
        if (n == null) return null;
        if ("object_value".equals(n.getType())) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            Node child = n.getChild(i).orElse(null);
            if (child == null) continue;
            Node found = unwrapToObject(child);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * Replaces a {@code name: "X"} object_field with
     * {@code className: "<FQN>"} when {@code "X"} resolves in
     * {@code namedReferences}. Returns {@link RewriteResult.Skip}
     * carrying the unresolved name otherwise.
     */
    static RewriteResult rewriteNameToClassName(
        WorkspaceFile file, Node nameField, Map<String, String> namedReferences
    ) {
        Node valueNode = childOfKind(nameField, "value");
        if (valueNode == null) {
            return new RewriteResult.Skip("malformed name field (no value)");
        }
        String rawName = Nodes.unquote(Nodes.text(valueNode, file.source()));
        if (rawName.isEmpty()) {
            return new RewriteResult.Skip("empty name value");
        }
        String resolved = namedReferences.get(rawName);
        if (resolved == null) {
            return new RewriteResult.Skip(rawName);
        }
        Position start = Positions.toLspPosition(file.source(), nameField.getStartByte());
        Position end = Positions.toLspPosition(file.source(), nameField.getEndByte());
        String replacement = "className: \"" + resolved + "\"";
        return new RewriteResult.Edit(new TextEdit(new Range(start, end), replacement));
    }

    private static Node childOfKind(Node parent, String kind) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            Node child = parent.getChild(i).orElse(null);
            if (child != null && kind.equals(child.getType())) return child;
        }
        return null;
    }
}
