package no.sikt.graphitron.lsp.diagnostics;

import graphql.language.DirectiveDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.NonNullType;
import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.Nodes;
import no.sikt.graphitron.lsp.parsing.Positions;
import no.sikt.graphitron.lsp.parsing.SchemaCoordinate;
import no.sikt.graphitron.lsp.parsing.TypeContext;
import no.sikt.graphitron.lsp.state.DirectiveResolution;
import no.sikt.graphitron.lsp.state.WorkspaceFile;
import no.sikt.graphitron.rewrite.ScalarTypeResolver;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.DirectiveShape;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
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

    /**
     * GraphQL spec built-in directives: present in user schemas, absent from
     * graphitron's bundled {@code directives.graphqls}. Skipped by the
     * unknown-directive validator so {@code @deprecated} on a user-authored
     * field doesn't surface as a graphitron-LSP false positive.
     */
    private static final Set<String> SPEC_BUILTIN_DIRECTIVES = Set.of(
        "skip", "include", "deprecated", "specifiedBy", "oneOf"
    );

    public static List<Diagnostic> compute(
        WorkspaceFile file, CompletionData catalog, LspSchemaSnapshot snapshot
    ) {
        return compute(LspVocabulary.load(), file, catalog, snapshot);
    }

    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "snapshot-built-implies-clean-parse",
        reliesOn = "warns under Built.Current + Unknown only; silences under Unavailable and "
            + "Built.Previous on the conservative principle 'do not punish the user for what we "
            + "cannot reliably see'."
    )
    public static List<Diagnostic> compute(
        LspVocabulary vocabulary, WorkspaceFile file, CompletionData catalog, LspSchemaSnapshot snapshot
    ) {
        var out = new ArrayList<Diagnostic>();
        var directives = Directives.findAll(file.tree().getRootNode());
        for (var directive : directives) {
            String directiveName = Nodes.text(directive.nameNode(), file.source());
            if (SPEC_BUILTIN_DIRECTIVES.contains(directiveName)) {
                continue;
            }
            var resolution = DirectiveResolution.resolve(vocabulary, snapshot, directiveName);
            if (resolution instanceof DirectiveResolution.Bundled bundled) {
                var dirDef = bundled.def();
                validateUnknownArgs(directive, dirDef, vocabulary, file, out);
                validateRequiredArgs(directive, dirDef, file, out);
                var leaves = vocabulary.leafCoordinates(directive, file.source());
                for (var leaf : leaves) {
                    dispatch(directive, leaf, vocabulary, file, catalog, out);
                }
                // The legacy `name:` arm fires once per ExternalCodeReference
                // object whose `className:` is empty/missing. Driven by leaves,
                // since every ECR-name slot is an InputField coordinate.
                validateLegacyNameLeaves(vocabulary, directive, leaves, file, catalog, out);
                continue;
            }
            // Freshness-aware silence policy: only Built.Current warns.
            // Unavailable (pre-build) and Built.Previous (stale after parse
            // failure) silence the warn arms to avoid punishing the user
            // for what we cannot reliably see. The two warn arms split on
            // the snapshot's view of the directive name: Unknown -> the
            // R139 unknown-directive arm, User -> phase 2's arg validation
            // against the snapshot's directive shape.
            switch (snapshot) {
                case LspSchemaSnapshot.Unavailable ignored -> { /* pre-build silence */ }
                case LspSchemaSnapshot.Built.Previous ignored -> { /* stale-snapshot silence */ }
                case LspSchemaSnapshot.Built.Current ignored -> {
                    switch (resolution) {
                        case DirectiveResolution.Bundled ignoredBundled -> { /* handled above */ }
                        case DirectiveResolution.User user -> {
                            validateUnknownArgsAgainstSnapshot(directive, user.shape(), file, out);
                            validateRequiredArgsAgainstSnapshot(directive, user.shape(), file, out);
                        }
                        case DirectiveResolution.Unknown ignoredUnknown ->
                            out.add(diagnostic(file, directive.nameNode(), DiagnosticSeverity.Warning,
                                "Unknown directive '@" + directiveName
                                    + "'. Not declared in any directive definition reachable from the parsed schema."));
                    }
                }
            }
        }
        return out;
    }

    /**
     * Walks every argument the user wrote on {@code directive} and warns on
     * any name (top-level or inside a nested object literal) that does not
     * resolve in the parsed registry. Top-level miss = unknown directive
     * arg; nested miss = unknown field on the enclosing input type.
     */
    private static void validateUnknownArgs(
        Directives.Directive directive, DirectiveDefinition dirDef,
        LspVocabulary vocabulary, WorkspaceFile file, List<Diagnostic> out
    ) {
        for (var arg : directive.arguments()) {
            String argName = Nodes.text(arg.key(), file.source());
            var argDef = LspVocabulary.findInputValue(dirDef.getInputValueDefinitions(), argName);
            if (argDef.isEmpty()) {
                out.add(diagnostic(file, arg.key(), DiagnosticSeverity.Warning,
                    "Unknown argument '" + argName + "' on @" + dirDef.getName() + "."));
                continue;
            }
            String argType = LspVocabulary.unwrapToInputTypeName(argDef.get().getType());
            if (argType != null) {
                descendUnknownArgs(arg.value(), argType, vocabulary, file, out);
            }
        }
    }

    private static void descendUnknownArgs(
        Node node, String currentType,
        LspVocabulary vocabulary, WorkspaceFile file, List<Diagnostic> out
    ) {
        if (node == null) return;
        if ("object_field".equals(node.getType())) {
            Node nameNode = childOfKind(node, "name");
            Node valueNode = childOfKind(node, "value");
            if (nameNode == null || valueNode == null) return;
            String fieldName = Nodes.text(nameNode, file.source());
            var inputType = vocabulary.registry().getTypeOrNull(currentType, InputObjectTypeDefinition.class);
            if (inputType == null) return;
            var fieldDef = LspVocabulary.findInputValue(inputType.getInputValueDefinitions(), fieldName);
            if (fieldDef.isEmpty()) {
                out.add(diagnostic(file, nameNode, DiagnosticSeverity.Warning,
                    "Unknown field '" + fieldName + "' on input type '" + currentType + "'."));
                return;
            }
            String nextType = LspVocabulary.unwrapToInputTypeName(fieldDef.get().getType());
            if (nextType != null) {
                descendUnknownArgs(valueNode, nextType, vocabulary, file, out);
            }
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            descendUnknownArgs(node.getChild(i).orElse(null), currentType, vocabulary, file, out);
        }
    }

    /**
     * Warns when a {@code NonNullType} arg on {@code directive} is missing
     * from the user's call. Nested required input-fields are out of scope —
     * they require a present-vs-absent distinction on the enclosing input
     * object that the top-level rule does not need.
     */
    private static void validateRequiredArgs(
        Directives.Directive directive, DirectiveDefinition dirDef,
        WorkspaceFile file, List<Diagnostic> out
    ) {
        var presentNames = new LinkedHashSet<String>();
        for (var arg : directive.arguments()) {
            presentNames.add(Nodes.text(arg.key(), file.source()));
        }
        for (var argDef : dirDef.getInputValueDefinitions()) {
            if (!(argDef.getType() instanceof NonNullType)) continue;
            if (presentNames.contains(argDef.getName())) continue;
            out.add(diagnostic(file, directive.nameNode(), DiagnosticSeverity.Warning,
                "Missing required argument '" + argDef.getName() + "' on @" + dirDef.getName() + "."));
        }
    }

    /**
     * Snapshot-driven counterpart to {@link #validateUnknownArgs}. Walks
     * every top-level arg the user wrote on a user-declared directive and
     * warns on any name not declared in the snapshot's projection of that
     * directive. Nested validation (`@foo(x: {misspelled: ...})`) is out
     * of scope until the snapshot carries input-object shapes.
     */
    private static void validateUnknownArgsAgainstSnapshot(
        Directives.Directive directive, DirectiveShape shape,
        WorkspaceFile file, List<Diagnostic> out
    ) {
        for (var arg : directive.arguments()) {
            String argName = Nodes.text(arg.key(), file.source());
            boolean known = shape.args().stream().anyMatch(a -> a.name().equals(argName));
            if (!known) {
                out.add(diagnostic(file, arg.key(), DiagnosticSeverity.Warning,
                    "Unknown argument '" + argName + "' on @" + shape.name() + "."));
            }
        }
    }

    /**
     * Snapshot-driven counterpart to {@link #validateRequiredArgs}. Warns
     * when an arg whose declared type is non-null is missing from the
     * user's call. {@link TypeShape#nonNull()} lives on the sealed
     * interface so the non-null check is one method call regardless of
     * named-vs-list shape.
     */
    private static void validateRequiredArgsAgainstSnapshot(
        Directives.Directive directive, DirectiveShape shape,
        WorkspaceFile file, List<Diagnostic> out
    ) {
        var presentNames = new LinkedHashSet<String>();
        for (var arg : directive.arguments()) {
            presentNames.add(Nodes.text(arg.key(), file.source()));
        }
        for (var argShape : shape.args()) {
            if (!argShape.type().nonNull()) continue;
            if (presentNames.contains(argShape.name())) continue;
            out.add(diagnostic(file, directive.nameNode(), DiagnosticSeverity.Warning,
                "Missing required argument '" + argShape.name() + "' on @" + shape.name() + "."));
        }
    }

    private static Node childOfKind(Node parent, String kind) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            Node child = parent.getChild(i).orElse(null);
            if (child != null && kind.equals(child.getType())) return child;
        }
        return null;
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
                validateMethod(vocabulary, directive, leaf, mnb, file, catalog, out);
            case Behavior.ArgMappingBinding ignored -> { /* sibling roadmap item */ }
            case Behavior.ScalarTypeBinding ignored ->
                validateScalarType(leaf.valueNode(), file, catalog, out);
        }
    }

    /**
     * Validates {@code @scalarType(scalar: "fully.qualified.Class.FIELD")}. The LSP has the
     * compile-classpath scan but not a live classloader, so it cannot run the resolver's full
     * reflection path; it surfaces the two checks the catalog can answer:
     *
     * <ul>
     *   <li>Shape: the value must split at the last dot into a class FQN + field name. A value
     *       with no dot cannot be resolved at codegen and is flagged here.</li>
     *   <li>Classpath: the class part must be present in the catalog's external-reference scan
     *       (mirrors {@link #validateClassName}). Skipped when the scan is empty (pre-compile
     *       state); the build-tier resolver produces the precise rejection arm then.</li>
     * </ul>
     *
     * <p>Field-level validation ({@code FieldNotFound}, {@code NotAScalarType},
     * {@code CoercingErased}) requires reflection on the actual class and lives in the
     * build-tier {@link no.sikt.graphitron.rewrite.ScalarTypeResolver}; the LSP surfaces those
     * errors via the build pipeline's diagnostics, not inline.
     */
    private static void validateScalarType(
        Node valueNode, WorkspaceFile file, CompletionData catalog, List<Diagnostic> out
    ) {
        String fqn = Nodes.unquote(Nodes.text(valueNode, file.source()));
        if (fqn.isEmpty()) return;
        switch (ScalarTypeResolver.parseDirectiveValue(fqn)) {
            case ScalarTypeResolver.ParsedDirectiveValue.Malformed m ->
                out.add(diagnostic(file, valueNode,
                    "Invalid scalar reference '" + m.value() + "'. Expected a fully-qualified "
                    + "field reference of the form 'fully.qualified.Class.FIELD' pointing at a "
                    + "public static final GraphQLScalarType."));
            case ScalarTypeResolver.ParsedDirectiveValue.Parsed p ->
                validateScalarTypeClasspath(p, valueNode, file, catalog, out);
        }
    }

    /**
     * Classpath half of {@link #validateScalarType}. Skipped when the catalog's reference scan
     * is empty (pre-`mvn compile` state). The structural / malformed-FQN diagnostic fires
     * regardless; only the unknown-class check defers until the scan has at least one entry.
     */
    private static void validateScalarTypeClasspath(
        ScalarTypeResolver.ParsedDirectiveValue.Parsed parsed,
        Node valueNode, WorkspaceFile file, CompletionData catalog, List<Diagnostic> out
    ) {
        if (catalog.externalReferences().isEmpty()) return;
        boolean found = catalog.externalReferences().stream()
            .anyMatch(r -> r.className().equals(parsed.classFqn()));
        if (!found) {
            out.add(diagnostic(file, valueNode,
                "Unknown class '" + parsed.classFqn() + "' on @scalarType. Not found in "
                + "compiled target/classes."));
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
        LspVocabulary vocabulary,
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

        Optional<String> classFqn = vocabulary.siblingStringAt(
            directive, leaf.valueNode(), mnb.classNameCoord(), file.source());
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
        LspVocabulary vocabulary,
        Directives.Directive directive, List<LspVocabulary.Leaf> leaves,
        WorkspaceFile file, CompletionData catalog, List<Diagnostic> out
    ) {
        var classNameCoord = new SchemaCoordinate.InputField("ExternalCodeReference", "className");
        for (var leaf : leaves) {
            if (!(leaf.coord() instanceof SchemaCoordinate.InputField f)) continue;
            if (!"ExternalCodeReference".equals(f.type()) || !"name".equals(f.field())) continue;
            // If the sibling className is set, the build-tier ignores name;
            // skip to avoid double-flagging.
            if (vocabulary.siblingStringAt(directive, leaf.valueNode(), classNameCoord, file.source())
                    .isPresent()) {
                continue;
            }
            String legacyName = Nodes.unquote(Nodes.text(leaf.valueNode(), file.source()));
            if (legacyName.isEmpty()) continue;
            if (catalog.namedReferences().get(legacyName) != null) continue;
            out.add(diagnostic(file, leaf.valueNode(),
                "Unknown reference '" + legacyName + "'. Not present in `namedReferences` "
                + "config. Add an entry mapping '" + legacyName + "' to a fully-qualified "
                + "class name, or rewrite this site as `className: \"<FQN>\"` directly."));
        }
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
