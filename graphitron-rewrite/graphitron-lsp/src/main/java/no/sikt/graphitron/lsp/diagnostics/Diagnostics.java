package no.sikt.graphitron.lsp.diagnostics;

import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.Nodes;
import no.sikt.graphitron.lsp.parsing.Positions;
import no.sikt.graphitron.lsp.parsing.SchemaCoordinate;
import no.sikt.graphitron.lsp.parsing.TypeContext;
import no.sikt.graphitron.lsp.state.WorkspaceFile;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Range;
import io.github.treesitter.jtreesitter.Node;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Validates known directive coordinates against the catalog and emits LSP
 * diagnostics for values that do not resolve. Dispatch is coordinate-driven:
 * for each directive in the document, the {@link LspVocabulary} walks every
 * value-bearing leaf and the validator pattern-matches on the leaf's
 * {@link Behavior} arm.
 *
 * <p>Replaces the per-directive case switch ({@code "table"} / {@code "field"}
 * / {@code "reference"}) plus the {@code DirectiveDefinitions.argsByInputType}
 * walk for {@code ExternalCodeReference} sites. Unification has the side
 * effect of closing the R110 {@code @sourceRow} gap (its flat
 * {@code className:} / {@code method:} args are
 * {@link SchemaCoordinate.DirectiveArg} coordinates with the canonical
 * overlay's bindings) and unifies table-name validation across
 * {@code @table(name:)} and {@code @reference(path: [{table:}])}.
 */
public final class Diagnostics {

    private Diagnostics() {}

    private static final String SOURCE = "graphitron-lsp";

    /**
     * Directives whose {@code method} field is meaningful and should be
     * validated against the resolved class. Directives outside this set
     * (today: {@code @record} and {@code @enum}, where the binding wraps a
     * type rather than a method invocation) skip method validation. Codifies
     * the per-directive policy that the previous {@code VALIDATE_METHOD}
     * set carried.
     */
    private static final Set<String> METHOD_VALIDATING_DIRECTIVES = Set.of(
        "service", "condition", "externalField", "tableMethod", "reference", "sourceRow"
    );

    public static List<Diagnostic> compute(WorkspaceFile file, CompletionData catalog) {
        return compute(LspVocabulary.load(), file, catalog);
    }

    public static List<Diagnostic> compute(
        LspVocabulary vocabulary, WorkspaceFile file, CompletionData catalog
    ) {
        var out = new ArrayList<Diagnostic>();
        var directives = Directives.findAll(file.tree().getRootNode());
        for (var directive : directives) {
            var leaves = vocabulary.leafCoordinates(directive, file.source());
            for (var leaf : leaves) {
                dispatch(directive, leaf, vocabulary, file, catalog, out);
            }
            // The legacy `name:` arm fires once per ExternalCodeReference
            // object whose `className:` is empty/missing. Driven by leaves,
            // since every ECR-name slot is an InputField coordinate.
            validateLegacyNameLeaves(directive, leaves, file, catalog, out);
        }
        return out;
    }

    private static void dispatch(
        Directives.Directive directive, LspVocabulary.Leaf leaf, LspVocabulary vocabulary,
        WorkspaceFile file, CompletionData catalog, List<Diagnostic> out
    ) {
        var behavior = vocabulary.behaviorAt(leaf.coord()).orElse(null);
        if (behavior == null) return;
        switch (behavior) {
            case Behavior.CatalogTableBinding ignored ->
                validateCatalogTable(leaf.valueNode(), file, catalog, out);
            case Behavior.CatalogColumnBinding ignored ->
                validateCatalogColumn(directive, leaf.valueNode(), file, catalog, out);
            case Behavior.CatalogFkBinding ignored ->
                validateCatalogFk(leaf.valueNode(), file, catalog, out);
            case Behavior.ClassNameBinding ignored ->
                validateClassName(leaf.valueNode(), file, catalog, out);
            case Behavior.MethodNameBinding mnb ->
                validateMethod(directive, leaf, mnb, file, catalog, out);
            case Behavior.ArgMappingBinding ignored -> { /* sibling roadmap item */ }
        }
    }

    private static void validateCatalogTable(
        Node valueNode, WorkspaceFile file, CompletionData catalog, List<Diagnostic> out
    ) {
        String tableName = Nodes.unquote(Nodes.text(valueNode, file.source()));
        if (tableName.isEmpty()) return;
        if (catalog.getTable(tableName).isEmpty()) {
            out.add(diagnostic(file, valueNode,
                "Unknown table '" + tableName + "'. The jOOQ catalog does not contain a table with this name."));
        }
    }

    private static void validateCatalogColumn(
        Directives.Directive directive, Node valueNode,
        WorkspaceFile file, CompletionData catalog, List<Diagnostic> out
    ) {
        String columnName = Nodes.unquote(Nodes.text(valueNode, file.source()));
        if (columnName.isEmpty()) return;
        var typeDef = TypeContext.enclosingTypeDefinition(directive.outer());
        if (typeDef.isEmpty()) return;
        var tableName = TypeContext.tableNameOf(typeDef.get(), file.source());
        if (tableName.isEmpty()) return;
        var table = catalog.getTable(tableName.get());
        if (table.isEmpty()) {
            // The enclosing @table is itself a typo; the @table validation
            // already flagged it. Skip the duplicate here.
            return;
        }
        var matched = table.get().columns().stream()
            .filter(c -> c.name().equalsIgnoreCase(columnName))
            .findFirst();
        if (matched.isEmpty()) {
            out.add(diagnostic(file, valueNode, DiagnosticSeverity.Error,
                "Unknown column '" + columnName + "' on table '" + tableName.get() + "'."));
        }
    }

    private static void validateCatalogFk(
        Node valueNode, WorkspaceFile file, CompletionData catalog, List<Diagnostic> out
    ) {
        String fkName = Nodes.unquote(Nodes.text(valueNode, file.source()));
        if (fkName.isEmpty()) return;
        // Match case-insensitively to mirror JooqCatalog.findForeignKeyByName,
        // which the runtime resolver uses; the LSP must not flag names the
        // generator would accept. Path-step refinement (which step's table we
        // are on) is deferred along with path-aware completion.
        if (collectAllFkNames(catalog).stream().noneMatch(known -> known.equalsIgnoreCase(fkName))) {
            out.add(diagnostic(file, valueNode,
                "Unknown foreign key '" + fkName + "'. Not present in the jOOQ catalog."));
        }
    }

    private static void validateClassName(
        Node valueNode, WorkspaceFile file, CompletionData catalog, List<Diagnostic> out
    ) {
        // Empty `externalReferences` means the classpath scan saw nothing
        // (typically: consumer hasn't run `mvn compile` yet). Reporting
        // every reference as unknown in that state would be noise; defer
        // until the scan has at least one entry to match against.
        if (catalog.externalReferences().isEmpty()) return;
        String fqn = Nodes.unquote(Nodes.text(valueNode, file.source()));
        if (fqn.isEmpty()) return;
        var found = catalog.externalReferences().stream()
            .anyMatch(r -> r.className().equals(fqn));
        if (!found) {
            out.add(diagnostic(file, valueNode,
                "Unknown class '" + fqn + "'. Not found in compiled target/classes."));
        }
    }

    private static void validateMethod(
        Directives.Directive directive, LspVocabulary.Leaf leaf,
        Behavior.MethodNameBinding mnb,
        WorkspaceFile file, CompletionData catalog, List<Diagnostic> out
    ) {
        String enclosingDirective = Nodes.text(directive.nameNode(), file.source());
        // @record / @enum bind ExternalCodeReference but the method slot
        // wraps a type, not a method invocation; skip.
        if (!METHOD_VALIDATING_DIRECTIVES.contains(enclosingDirective)) return;
        if (catalog.externalReferences().isEmpty()) return;

        String methodName = Nodes.unquote(Nodes.text(leaf.valueNode(), file.source()));
        if (methodName.isEmpty()) return;

        Optional<String> classFqn = readSiblingValue(directive, leaf.valueNode(), mnb.classNameCoord(), file.source());
        if (classFqn.isEmpty()) return;

        var refOpt = catalog.externalReferences().stream()
            .filter(r -> r.className().equals(classFqn.get()))
            .findFirst();
        if (refOpt.isEmpty()) {
            // Sibling className itself unresolved; the className validator
            // already flagged it. Skip the duplicate here.
            return;
        }
        var methodOpt = refOpt.get().methods().stream()
            .filter(m -> m.name().equals(methodName))
            .findFirst();
        if (methodOpt.isEmpty()) {
            out.add(diagnostic(file, leaf.valueNode(),
                "Unknown method '" + methodName + "' on class '" + classFqn.get() + "'."));
            return;
        }
        // The method resolved. If it takes parameters but the consumer
        // compiled the class without -parameters, parameter names are
        // unknown (null on every Parameter record). Surface the same
        // warning the rewrite generator emits at build time
        // (ServiceCatalog.emitParametersWarning), but as a per-reference
        // warning so the schema author sees it inline next to the
        // affected directive.
        var method = methodOpt.get();
        if (!method.parameters().isEmpty()
                && method.parameters().stream().allMatch(p -> p.name() == null)) {
            out.add(diagnostic(file, leaf.valueNode(), DiagnosticSeverity.Warning,
                "Class '" + classFqn.get() + "' was compiled without `-parameters`; "
                + "parameter help on '" + methodName + "' is unavailable. "
                + "Set `<parameters>true</parameters>` on maven-compiler-plugin "
                + "to surface parameter names."));
        }
    }

    /**
     * Validates legacy {@code ExternalCodeReference.name} leaves: when
     * the sibling {@code className:} on the same ECR object is empty or
     * missing, the build-tier resolves {@code name:} via
     * {@code RewriteContext.namedReferences}. Surface unresolved names
     * here so the user sees the error before the build runs.
     */
    private static void validateLegacyNameLeaves(
        Directives.Directive directive, List<LspVocabulary.Leaf> leaves,
        WorkspaceFile file, CompletionData catalog, List<Diagnostic> out
    ) {
        for (var leaf : leaves) {
            if (!(leaf.coord() instanceof SchemaCoordinate.InputField f)) continue;
            if (!"ExternalCodeReference".equals(f.type()) || !"name".equals(f.field())) continue;
            // If the sibling className is set, the build-tier ignores name;
            // skip to avoid double-flagging.
            var siblingClassName = readSiblingObjectField(
                directive, leaf.valueNode(), "className", file.source());
            if (siblingClassName.isPresent()) continue;
            String legacyName = Nodes.unquote(Nodes.text(leaf.valueNode(), file.source()));
            if (legacyName.isEmpty()) continue;
            if (catalog.namedReferences().get(legacyName) != null) continue;
            out.add(diagnostic(file, leaf.valueNode(),
                "Unknown reference '" + legacyName + "'. Not present in `namedReferences` "
                + "config. Add an entry mapping '" + legacyName + "' to a fully-qualified "
                + "class name, or rewrite this site as `className: \"<FQN>\"` directly."));
        }
    }

    /**
     * Reads the string value at {@code siblingCoord}, scoped to the same
     * directive the leaf lives in. Same shape as
     * {@code MethodCompletions.readSiblingValue} — a separate copy until
     * the consumers stabilise enough to lift into a shared helper.
     */
    private static Optional<String> readSiblingValue(
        Directives.Directive directive, Node leafValue,
        SchemaCoordinate siblingCoord, byte[] source
    ) {
        return switch (siblingCoord) {
            case SchemaCoordinate.DirectiveArg da -> readDirectiveArgString(directive, da.arg(), source);
            case SchemaCoordinate.InputField f -> readSiblingObjectField(directive, leafValue, f.field(), source);
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
        Directives.Directive directive, Node leafValue, String fieldName, byte[] source
    ) {
        for (var arg : directive.arguments()) {
            Node objectValue = enclosingObjectValueOf(arg.value(), leafValue);
            if (objectValue == null) continue;
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
        }
        return Optional.empty();
    }

    /**
     * Returns the deepest {@code object_value} under {@code root} that
     * contains the leaf's value node. Used by the sibling-value reader
     * to scope sibling lookup to the same nested object the leaf sits in.
     */
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

    private static Set<String> collectAllFkNames(CompletionData catalog) {
        var names = new LinkedHashSet<String>();
        for (var table : catalog.tables()) {
            for (var ref : table.references()) {
                names.add(ref.keyName());
            }
        }
        return names;
    }

    private static Node childOfKind(Node parent, String kind) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            Node child = parent.getChild(i).orElse(null);
            if (child != null && kind.equals(child.getType())) return child;
        }
        return null;
    }

    private static Diagnostic diagnostic(WorkspaceFile file, Node node, DiagnosticSeverity severity, String message) {
        var start = Positions.toLspPosition(file.source(), node.getStartByte());
        var end = Positions.toLspPosition(file.source(), node.getEndByte());
        var d = new Diagnostic(new Range(start, end), message);
        d.setSeverity(severity);
        d.setSource(SOURCE);
        return d;
    }

    private static Diagnostic diagnostic(WorkspaceFile file, Node node, String message) {
        return diagnostic(file, node, DiagnosticSeverity.Error, message);
    }
}
