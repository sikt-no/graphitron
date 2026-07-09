package no.sikt.graphitron.lsp.code_action;

import io.github.treesitter.jtreesitter.Node;
import no.sikt.graphitron.lsp.code_action.SdlAction.RewriteResult;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.Nodes;
import no.sikt.graphitron.lsp.parsing.Positions;
import no.sikt.graphitron.lsp.parsing.SchemaCoordinate;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.NAME;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.OBJECT_FIELD;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.OBJECT_VALUE;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.VALUE;

/**
 * Registry of every {@link SdlAction} the LSP knows how to apply. A quick-fix
 * action is registered here explicitly; it is never divined from a deprecation's
 * prose reason (R398). Deprecation comments and quick-fix actions are independent:
 * a deprecation may carry no action, and an action need not correspond to a
 * deprecation. {@code SdlActionDriftTest} keeps the one remaining coupling honest,
 * an action that <em>does</em> target a deprecation must target a real one, so a
 * renamed or removed marker cannot leave a stale action behind.
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
            Set.of(new SchemaCoordinate.InputField("ExternalCodeReference", "name")),
            SdlActions::detectLegacyNameSites,
            (file, match) -> rewriteNameToClassName(file, match, catalog.namedReferences())
        );
    }

    /**
     * Detects every legacy {@code name:} object_field inside an
     * {@code ExternalCodeReference} literal in the file. The legacy
     * shape is: an ECR object containing a {@code name:} field but no
     * {@code className:} field. Yields the {@code name:} object_field
     * node so the rewrite slot has the full range to replace.
     *
     * <p>Driven by {@link LspVocabulary#leafCoordinates}: every
     * {@code InputField("ExternalCodeReference", "name")} leaf is a
     * candidate; the same-object className-absence check rejects
     * already-migrated sites.
     */
    static Stream<Node> detectLegacyNameSites(FileSnapshot file) {
        var vocab = LspVocabulary.load();
        var matches = new java.util.ArrayList<Node>();
        for (var directive : Directives.findAll(file.tree().getRootNode())) {
            for (var leaf : vocab.leafCoordinates(directive, file.source())) {
                if (!(leaf.coord() instanceof SchemaCoordinate.InputField f)) continue;
                if (!"ExternalCodeReference".equals(f.type()) || !"name".equals(f.field())) continue;
                Node nameField = enclosingObjectField(leaf.valueNode());
                if (nameField == null) continue;
                Node parentObject = enclosingObjectValue(nameField.getParent().orElse(null));
                if (parentObject == null) continue;
                if (hasObjectField(parentObject, "className", file.source())) continue;
                matches.add(nameField);
            }
        }
        return matches.stream();
    }

    private static Node enclosingObjectField(Node node) {
        Node cur = node;
        while (cur != null) {
            if (OBJECT_FIELD.matches(cur)) return cur;
            cur = cur.getParent().orElse(null);
        }
        return null;
    }

    private static Node enclosingObjectValue(Node node) {
        Node cur = node;
        while (cur != null) {
            if (OBJECT_VALUE.matches(cur)) return cur;
            cur = cur.getParent().orElse(null);
        }
        return null;
    }

    private static boolean hasObjectField(Node objectValue, String fieldName, byte[] source) {
        for (int i = 0; i < objectValue.getChildCount(); i++) {
            Node child = objectValue.getChild(i).orElse(null);
            if (child == null || !OBJECT_FIELD.matches(child)) continue;
            Node fname = Nodes.childOfKind(child, NAME);
            if (fname != null && fieldName.equals(Nodes.text(fname, source))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Replaces a {@code name: "X"} object_field with
     * {@code className: "<FQN>"} when {@code "X"} resolves in
     * {@code namedReferences}. Returns {@link RewriteResult.Skip}
     * carrying the unresolved name otherwise.
     */
    static RewriteResult rewriteNameToClassName(
        FileSnapshot file, Node nameField, Map<String, String> namedReferences
    ) {
        Node valueNode = Nodes.childOfKind(nameField, VALUE);
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
}
