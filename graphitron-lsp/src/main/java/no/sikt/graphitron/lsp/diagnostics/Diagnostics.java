package no.sikt.graphitron.lsp.diagnostics;

import graphql.language.DirectiveDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.NonNullType;
import graphql.language.SourceLocation;
import no.sikt.graphitron.lsp.parsing.ArgMapping;
import no.sikt.graphitron.lsp.parsing.ArgMappingSupport;
import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.DeclarationKind;
import no.sikt.graphitron.lsp.parsing.DirectivePolicy;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.Nodes;
import no.sikt.graphitron.lsp.parsing.Positions;
import no.sikt.graphitron.lsp.parsing.SchemaCoordinate;
import no.sikt.graphitron.lsp.parsing.TypeContext;
import no.sikt.graphitron.lsp.state.DirectiveResolution;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.rewrite.BuildWarning;
import no.sikt.graphitron.rewrite.ScalarTypeResolver;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.ValidationReport;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.DirectiveShape;
import no.sikt.graphitron.rewrite.catalog.FieldClassification;
import no.sikt.graphitron.rewrite.catalog.TypeBackingShape;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.model.Rejection;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import io.github.treesitter.jtreesitter.Node;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.DESCRIPTION;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.ENUM_VALUE;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.NAME;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.OBJECT_FIELD;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.STRING_VALUE;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.VALUE;

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
    private static final String VALIDATOR_SOURCE = "graphitron-validator";

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
        String uri, FileSnapshot file, CompletionData catalog, LspSchemaSnapshot snapshot,
        ValidationReport report
    ) {
        return compute(LspVocabulary.load(), uri, file, catalog, snapshot, report);
    }

    public static List<Diagnostic> compute(
        LspVocabulary vocabulary, String uri, FileSnapshot file, CompletionData catalog,
        LspSchemaSnapshot snapshot, ValidationReport report
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
                    dispatch(directive, leaf, vocabulary, file, catalog, snapshot, out);
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
        out.addAll(validatorDiagnostics(uri, file, snapshot, report));
        return out;
    }

    /**
     * Maps {@link ValidationReport} entries for {@code uri} into LSP diagnostics. Silent under
     * {@link LspSchemaSnapshot.Unavailable} (no build yet) and {@link LspSchemaSnapshot.Built.Previous}
     * (stale snapshot after a parse failure), mirroring the R139 freshness-aware silence policy:
     * the validator's last output may not reflect the buffer the user is editing, and a stale
     * red squiggle the developer cannot fix by rewriting their schema is the noise we are trying
     * to avoid. Short-circuits when the open file has no entries in {@link ValidationReport#sourceUris}.
     *
     * <p>{@code ValidationError} with a null or {@code (0, 0)} location is dropped silently in
     * v1: every error in the current rule set carries a usable location, and a console / watch
     * formatter already covers any future no-location producer. Warnings without location are
     * dropped for the same reason.
     *
     * <p>The hook for a schema-wide surface lives here: when the first real producer of a
     * no-location error lands, it ships in the same commit as an LSP
     * {@code window/showMessage} notification of {@link org.eclipse.lsp4j.MessageType#Error}
     * (or {@code Warning} for {@link BuildWarning}), rate-limited to at most one per recalculate
     * cycle so a burst of schema-wide errors does not flood the client. The notification carries
     * the error message verbatim and no file URI; the contract is "show this to the developer
     * somewhere visible" rather than "highlight this position". Until that producer lands the
     * console / watch-mode formatter path is the sole surface for schema-wide errors.
     */
    private static List<Diagnostic> validatorDiagnostics(
        String uri, FileSnapshot file, LspSchemaSnapshot snapshot, ValidationReport report
    ) {
        return switch (snapshot) {
            case LspSchemaSnapshot.Unavailable ignored -> List.of();
            case LspSchemaSnapshot.Built.Previous ignored -> List.of();
            case LspSchemaSnapshot.Built.Current ignored -> validatorDiagnosticsForCurrent(uri, file, report);
        };
    }

    private static List<Diagnostic> validatorDiagnosticsForCurrent(String uri, FileSnapshot file, ValidationReport report) {
        if (!report.sourceUris().contains(uri)) {
            return List.of();
        }
        var out = new ArrayList<Diagnostic>();
        for (ValidationError error : report.errors()) {
            var loc = error.location();
            if (!matchesOpenFile(uri, loc)) continue;
            out.add(validatorDiagnostic(file, loc, severityOf(error.rejection()), error.message(),
                lspCodeOf(error.rejection())));
        }
        for (BuildWarning warning : report.warnings()) {
            var loc = warning.location();
            if (!matchesOpenFile(uri, loc)) continue;
            out.add(validatorDiagnostic(file, loc, DiagnosticSeverity.Warning, warning.message(), null));
        }
        return out;
    }

    private static boolean matchesOpenFile(String uri, SourceLocation loc) {
        if (loc == null || loc.getLine() <= 0) return false;
        String sourceName = loc.getSourceName();
        if (sourceName == null || sourceName.isEmpty()) return false;
        return uri.equals(ValidationReport.canonicalUri(sourceName));
    }

    /**
     * Reads the stable wire code for typed AuthorError arms that publish one. R238's
     * {@link no.sikt.graphitron.rewrite.model.ServiceMethodCallError} arms expose
     * {@code lspCode()} under the {@code graphitron.service-method-call.} namespace; the
     * LSP projector forwards the code into the lsp4j {@link Diagnostic#setCode} field so
     * editor extensions can key off the arm without parsing the prose message. Returns
     * {@code null} for rejection arms that don't publish a code; the lsp4j Diagnostic just
     * omits the field in that case.
     */
    private static String lspCodeOf(Rejection rejection) {
        if (rejection instanceof no.sikt.graphitron.rewrite.model.ServiceMethodCallError sce) {
            return sce.lspCode();
        }
        if (rejection instanceof no.sikt.graphitron.rewrite.model.ReflectionError re) {
            return re.lspCode();
        }
        if (rejection instanceof no.sikt.graphitron.rewrite.model.UpdateRowsError ure) {
            return ure.lspCode();
        }
        if (rejection instanceof no.sikt.graphitron.rewrite.model.DeleteRowsError dre) {
            return dre.lspCode();
        }
        if (rejection instanceof no.sikt.graphitron.rewrite.model.ErrorChannelWalkerError ecwe) {
            return ecwe.lspCode();
        }
        if (rejection instanceof no.sikt.graphitron.rewrite.model.WireCoercionError wce) {
            return wce.lspCode();
        }
        return null;
    }

    private static DiagnosticSeverity severityOf(Rejection rejection) {
        // Every Rejection variant fails the build via ValidationFailedException
        // (GraphQLRewriteGenerator throws on any non-empty error list, regardless
        // of arm); the editor must surface the same finality so the developer
        // sees one consistent signal across the LSP and `mvn graphitron:dev`.
        // R147 originally softened Deferred to Warning on "not author-actionable"
        // grounds; R225 reverts that — the actionable hint is the roadmap slug
        // carried by the rejection, not the severity.
        return switch (rejection) {
            case Rejection.AuthorError ignored -> DiagnosticSeverity.Error;
            case Rejection.InvalidSchema ignored -> DiagnosticSeverity.Error;
            case Rejection.Deferred ignored -> DiagnosticSeverity.Error;
        };
    }

    /**
     * Builds an LSP diagnostic for a validator error or warning. {@code SourceLocation.getLine()}
     * and {@code getColumn()} are 1-based; LSP {@code Position} is 0-based. End column is
     * {@link Integer#MAX_VALUE} — gcc/AsciiDoctor convention, clamped by the LSP client to the
     * actual line end. A zero-width range at column 1 (the common case for type-level errors that
     * point at the type's declaration) is too subtle to find in editors; column-to-EOL hits the
     * right balance.
     */
    private static Diagnostic validatorDiagnostic(
        FileSnapshot file, SourceLocation loc, DiagnosticSeverity severity, String message, String code
    ) {
        var d = new Diagnostic(signatureRange(file, loc), message);
        d.setSeverity(severity);
        d.setSource(VALIDATOR_SOURCE);
        if (code != null) {
            d.setCode(code);
        }
        return d;
    }

    /**
     * The range to highlight for a validator finding at {@code loc}. graphql-java anchors a
     * <em>described</em> definition's {@code getSourceLocation()} at the opening delimiter of its
     * documentation block, not the type/field name, because the description is the AST node's first
     * token. An error on a documented definition would otherwise underline the doc block rather than
     * the declaration the author must fix. When {@code loc} lands inside a tree-sitter
     * {@code description} node we re-anchor to the enclosing definition's name; otherwise (the common
     * no-doc case, or any tree shape we don't recognise) we fall back to the column-to-end-of-line
     * range straight from {@code loc}.
     *
     * <p>The tree-sitter walk is exact for every documentation style: single-line {@code "..."},
     * inline block {@code """..."""}, and multi-line block. It needs no line arithmetic over the
     * graphql-java description content, which cannot distinguish an inline block from an own-line
     * one (both report {@code multiLine=true} with no interior newlines) and is the dominant style
     * in this codebase's directive schema.
     */
    private static Range signatureRange(FileSnapshot file, SourceLocation loc) {
        var reanchored = descriptionNameRange(file, loc);
        if (reanchored != null) {
            return reanchored;
        }
        var start = new Position(loc.getLine() - 1, Math.max(0, loc.getColumn() - 1));
        var end = new Position(loc.getLine() - 1, Integer.MAX_VALUE);
        return new Range(start, end);
    }

    /**
     * Range of the name of the definition documented by the {@code description} node containing
     * {@code loc}, or {@code null} when {@code loc} is not inside a description (or the file has no
     * usable tree). The enclosing definition is the description node's parent; its identifying child
     * is a {@code name} for every definition kind except {@code enum_value_definition}, which carries
     * an {@code enum_value} instead.
     */
    private static Range descriptionNameRange(FileSnapshot file, SourceLocation loc) {
        if (file == null || file.tree() == null) {
            return null;
        }
        var resolved = Positions.resolve(file.source(), loc.getLine() - 1, Math.max(0, loc.getColumn() - 1));
        Node leaf = file.tree().getRootNode().getDescendant(resolved.tsPoint(), resolved.tsPoint()).orElse(null);
        Node description = enclosingDescription(leaf);
        if (description == null) {
            return null;
        }
        Node def = description.getParent().orElse(null);
        if (def == null) {
            return null;
        }
        Node name = Nodes.childOfKind(def, NAME);
        if (name == null) {
            name = Nodes.childOfKind(def, ENUM_VALUE);
        }
        if (name == null) {
            return null;
        }
        return new Range(
            Positions.toLspPosition(file.source(), name.getStartByte()),
            Positions.toLspPosition(file.source(), name.getEndByte()));
    }

    /** Nearest {@code description} ancestor of {@code node} (inclusive), or {@code null}. */
    private static Node enclosingDescription(Node node) {
        while (node != null) {
            if (DESCRIPTION.matches(node)) {
                return node;
            }
            node = node.getParent().orElse(null);
        }
        return null;
    }

    /**
     * Walks every argument the user wrote on {@code directive} and warns on
     * any name (top-level or inside a nested object literal) that does not
     * resolve in the parsed registry. Top-level miss = unknown directive
     * arg; nested miss = unknown field on the enclosing input type.
     */
    private static void validateUnknownArgs(
        Directives.Directive directive, DirectiveDefinition dirDef,
        LspVocabulary vocabulary, FileSnapshot file, List<Diagnostic> out
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
        LspVocabulary vocabulary, FileSnapshot file, List<Diagnostic> out
    ) {
        if (node == null) return;
        if (OBJECT_FIELD.matches(node)) {
            Node nameNode = Nodes.childOfKind(node, NAME);
            Node valueNode = Nodes.childOfKind(node, VALUE);
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
        FileSnapshot file, List<Diagnostic> out
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
        FileSnapshot file, List<Diagnostic> out
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
        FileSnapshot file, List<Diagnostic> out
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


    private static void dispatch(
        Directives.Directive directive, LspVocabulary.Leaf leaf, LspVocabulary vocabulary,
        FileSnapshot file, CompletionData catalog, LspSchemaSnapshot snapshot, List<Diagnostic> out
    ) {
        var behavior = vocabulary.behaviorAt(leaf.coord()).orElse(null);
        if (behavior == null) return;
        switch (behavior) {
            case Behavior.CatalogTableBinding ignored ->
                validateCatalogTable(leaf.valueNode(), file, catalog, out);
            case Behavior.CatalogColumnBinding ignored ->
                validateFieldMember(directive, leaf.valueNode(), file, catalog, snapshot, out);
            case Behavior.CatalogFkBinding ignored ->
                validateCatalogFk(leaf.valueNode(), file, catalog, out);
            case Behavior.ClassNameBinding ignored ->
                validateClassName(directive, leaf.valueNode(), file, catalog, out);
            case Behavior.MethodNameBinding mnb ->
                validateMethod(vocabulary, directive, leaf, mnb, file, catalog, out);
            case Behavior.ArgMappingBinding ignored ->
                validateArgMapping(vocabulary, directive, leaf, file, catalog, out);
            case Behavior.ScalarTypeBinding ignored ->
                validateScalarType(leaf.valueNode(), file, catalog, out);
            case Behavior.NodeTypeBinding ignored ->
                validateNodeType(leaf.valueNode(), file, catalog, out);
        }
    }

    /**
     * Validates {@code @nodeId(typeName: "X")}: the named type must exist in the
     * catalog and must carry {@code @node}. Mirrors the two classifier rejections
     * that {@link no.sikt.graphitron.rewrite.FieldBuilder} produces for the same
     * coordinate: {@code Rejection.unknownTypeName} when no such type exists,
     * {@code Rejection.structural} when the type exists without {@code @node}.
     */
    private static void validateNodeType(
        Node valueNode, FileSnapshot file, CompletionData catalog, List<Diagnostic> out
    ) {
        String typeName = Nodes.unquote(Nodes.text(valueNode, file.source()));
        if (typeName.isEmpty()) return;
        if (catalog.nodeMetadata().containsKey(typeName)) return;
        if (catalog.nodeMetadata().isEmpty()) {
            // No @node-bearing types known to the catalog yet (pre-build state,
            // or a schema that uses @nodeId only for its argument-resolution
            // side effect). Defer to the build-tier rejection.
            return;
        }
        out.add(diagnostic(file, valueNode,
            "Unknown @node type '" + typeName + "' on @nodeId(typeName:). The type must be "
            + "declared in the schema and carry the @node directive."));
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
        Node valueNode, FileSnapshot file, CompletionData catalog, List<Diagnostic> out
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
        Node valueNode, FileSnapshot file, CompletionData catalog, List<Diagnostic> out
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
        Node valueNode, FileSnapshot file, CompletionData catalog, List<Diagnostic> out
    ) {
        String tableName = Nodes.unquote(Nodes.text(valueNode, file.source()));
        if (tableName.isEmpty()) return;
        if (catalog.getTable(tableName).isEmpty()) {
            out.add(diagnostic(file, valueNode,
                "Unknown table '" + tableName + "'. The jOOQ catalog does not contain a table with this name."));
        }
    }

    /**
     * Validates a {@code @field(name:)} (or other {@code CatalogColumnBinding}
     * coordinate) against the enclosing SDL type's backing shape: column on a
     * table-bound type, component on a Java record, accessor on a POJO. The
     * dispatch reads {@link LspSchemaSnapshot.Built#typesByName} so the
     * classifier's projection of the enclosing type is the authoritative
     * answer.
     */
    private static void validateFieldMember(
        Directives.Directive directive, Node valueNode,
        FileSnapshot file, CompletionData catalog, LspSchemaSnapshot snapshot, List<Diagnostic> out
    ) {
        String memberName = Nodes.unquote(Nodes.text(valueNode, file.source()));
        if (memberName.isEmpty()) return;
        if (!(snapshot instanceof LspSchemaSnapshot.Built built)) return;
        var typeDecl = DeclarationKind.enclosing(directive.outer());
        if (typeDecl.isEmpty()) return;
        var typeName = TypeContext.declaredNameOf(typeDecl.get(), file.source());
        if (typeName.isEmpty()) return;
        var fieldName = TypeContext.enclosingFieldOrInputValueDefinition(directive.outer())
            .flatMap(fd -> TypeContext.fieldNameOf(fd, file.source()))
            .orElse(null);
        // R159: if the value is the $source sigil, the diagnostic shape is sigil-aware. The
        // snapshot owns the (typeName, fieldName) -> SiteContext classification through
        // siteContext(); we route the predicate through sourceSigilDefinedAt rather than reading
        // the underlying projection ourselves. At an admitted carrier-data-field site, the
        // sigil is valid — no diagnostic. Anywhere else, emit the canonical
        // FieldSourceSigil.sourceSigilNotDefinedHereMessage(). Snapshot-uncertainty: when the
        // parent type has no entry in the type-backing projection at all (mid-edit / not-yet-
        // classified), stay silent so we don't punish the user for a shape we cannot resolve.
        if (no.sikt.graphitron.rewrite.FieldSourceSigil.UPSTREAM_ROOT_LITERAL.equals(memberName)) {
            boolean isPayloadDataField = fieldName != null
                && no.sikt.graphitron.rewrite.FieldSourceSigil.sourceSigilDefinedAt(
                    built.siteContext(typeName.get(), fieldName));
            if (!isPayloadDataField && built.typesByName().containsKey(typeName.get())) {
                out.add(diagnostic(file, valueNode, DiagnosticSeverity.Error,
                    no.sikt.graphitron.rewrite.FieldSourceSigil.sourceSigilNotDefinedHereMessage()));
            }
            return;
        }
        // R224 / R233: prefer the field classification's projected terminal table over the
        // enclosing type's @table. FieldClassification.lspColumnDispatch() collapses the 30
        // sealed permits onto three audience-specific arms: Resolve(tableName) carries the
        // projected terminal table for the four column-bearing permits; Silent suppresses the
        // diagnostic for InputUnbound / Unclassified (the validator already emits a precise
        // message via ValidationReport, and a duplicate LSP diagnostic with the wrong table
        // would be noise); FallThrough routes back to the existing backing-driven dispatch.
        // Snapshot-uncertainty (empty optional) also falls through.
        if (fieldName != null) {
            var classification = built.fieldClassification(typeName.get(), fieldName);
            if (classification.isPresent()) {
                switch (classification.get().lspColumnDispatch()) {
                    case FieldClassification.LspColumnDispatch.Resolve(var tableName) -> {
                        validateColumnOnTable(catalog, tableName, memberName, valueNode, file, out);
                        return;
                    }
                    case FieldClassification.LspColumnDispatch.Silent ignored -> { return; }
                    case FieldClassification.LspColumnDispatch.FallThrough ignored -> { /* fall through */ }
                }
            }
        }
        var backing = built.typesByName().get(typeName.get());
        if (backing == null) return;
        switch (backing) {
            case TypeBackingShape.RecordBacking r ->
                validateMemberSlot(r.components(), memberName, "component", r.fqClassName(), valueNode, file, out);
            case TypeBackingShape.PojoBacking p ->
                validateMemberSlot(p.accessors(), memberName, "property", p.fqClassName(), valueNode, file, out);
            case TypeBackingShape.JooqRecordBacking.WithTable j ->
                validateColumnOnTable(catalog, j.tableName(), memberName, valueNode, file, out);
            case TypeBackingShape.JooqRecordBacking.Standalone ignored -> { /* no actionable diagnostic */ }
            case TypeBackingShape.TableBacking t ->
                validateColumnOnTable(catalog, t.tableName(), memberName, valueNode, file, out);
            case TypeBackingShape.NoBacking ignored -> { /* no actionable diagnostic */ }
        }
    }

    private static void validateColumnOnTable(
        CompletionData catalog, String tableName, String columnName,
        Node valueNode, FileSnapshot file, List<Diagnostic> out
    ) {
        var table = catalog.getTable(tableName);
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
                "Unknown column '" + columnName + "' on table '" + tableName + "'."));
        }
    }

    private static void validateMemberSlot(
        List<TypeBackingShape.MemberSlot> slots, String memberName, String kind,
        String fqClassName, Node valueNode, FileSnapshot file, List<Diagnostic> out
    ) {
        if (slots.isEmpty()) return;
        boolean matched = slots.stream().anyMatch(s -> s.name().equals(memberName));
        if (!matched) {
            out.add(diagnostic(file, valueNode, DiagnosticSeverity.Error,
                "Unknown " + kind + " '" + memberName + "' on backing class '" + fqClassName + "'."));
        }
    }

    private static void validateCatalogFk(
        Node valueNode, FileSnapshot file, CompletionData catalog, List<Diagnostic> out
    ) {
        String fkName = Nodes.unquote(Nodes.text(valueNode, file.source()));
        if (fkName.isEmpty()) return;
        // Match case-insensitively to mirror JooqCatalog.findForeignKey(name, source),
        // which the runtime resolver uses; the LSP must not flag names the
        // generator would accept. Path-step refinement (which step's table we
        // are on) is deferred along with path-aware completion.
        if (collectAllFkNames(catalog).stream().noneMatch(known -> known.equalsIgnoreCase(fkName))) {
            out.add(diagnostic(file, valueNode,
                "Unknown foreign key '" + fkName + "'. Not present in the jOOQ catalog."));
        }
    }

    private static void validateClassName(
        Directives.Directive directive, Node valueNode, FileSnapshot file, CompletionData catalog, List<Diagnostic> out
    ) {
        // R307 carve-out: @record is deprecated/ignored, so its className slot binds no class
        // and an unknown-class diagnostic would be noise. The ExternalCodeReference.className
        // coordinate is shared with @enum, so the carve-out keys on the directive name, not
        // the coordinate (see DirectivePolicy).
        if (!DirectivePolicy.bindsLiveClass(Nodes.text(directive.nameNode(), file.source()))) return;
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
        FileSnapshot file, CompletionData catalog, List<Diagnostic> out
    ) {
        // @record / @enum bind ExternalCodeReference but the method slot
        // wraps a type, not a method invocation; skip (see DirectivePolicy).
        if (!DirectivePolicy.bindsLiveMethod(Nodes.text(directive.nameNode(), file.source()))) return;
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
        FileSnapshot file, CompletionData catalog, List<Diagnostic> out
    ) {
        // R307 carve-out: @record is deprecated/ignored — it binds no class, so the legacy
        // ExternalCodeReference.name → className alias nudge is dead tooling for it. Keys on the
        // enclosing directive name (see DirectivePolicy).
        if (!DirectivePolicy.bindsLiveClass(Nodes.text(directive.nameNode(), file.source()))) return;
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

    /**
     * Validates an {@code argMapping} string ({@code "javaParam: graphqlArg, ..."}):
     *
     * <ul>
     *   <li>Structural: empty entry / stray comma, dangling {@code :} (missing
     *       Java parameter or GraphQL argument), and an entry missing its
     *       {@code :} altogether.</li>
     *   <li>Left: a duplicate Java parameter, and a Java parameter that is not a
     *       parameter of the resolved method (suppressed when the method's
     *       parameter names are unavailable, i.e. compiled without
     *       {@code -parameters}).</li>
     *   <li>Right: a GraphQL argument whose first path segment is not an argument
     *       of the enclosing field. Deeper R84 dot-path segments are not
     *       validated (the LSP carries no projection of nested input-type
     *       fields); only the head segment is checked.</li>
     * </ul>
     */
    private static void validateArgMapping(
        LspVocabulary vocabulary, Directives.Directive directive, LspVocabulary.Leaf leaf,
        FileSnapshot file, CompletionData catalog, List<Diagnostic> out
    ) {
        Node valueNode = stringValueOf(leaf.valueNode());
        if (valueNode == null) return;
        byte[] source = file.source();
        String raw = Nodes.text(valueNode, source);
        int quote = raw.length() >= 6 && raw.startsWith("\"\"\"") && raw.endsWith("\"\"\"") ? 3 : 1;
        if (raw.length() < quote * 2) return;
        String content = raw.substring(quote, raw.length() - quote);
        int contentStart = valueNode.getStartByte() + quote;

        var entries = ArgMapping.parse(content);
        if (entries.isEmpty()) return; // blank content is identity for every parameter

        Set<String> paramNames = resolveParameterNames(vocabulary, directive, valueNode, leaf.coord(), catalog, source);
        List<String> fieldArgs = TypeContext.enclosingFieldDefinition(directive.outer())
            .map(fd -> TypeContext.fieldArgumentNames(fd, source))
            .orElse(List.of());

        var seenJava = new LinkedHashSet<String>();
        for (var entry : entries) {
            if (!entry.hasColon() && entry.isBlank()) {
                out.add(diagnostic(file, valueNode, DiagnosticSeverity.Warning,
                    "Empty argMapping entry (stray comma)."));
                continue;
            }
            if (!entry.hasColon()) {
                out.add(byteDiagnostic(file, contentStart + entry.rawStart(), contentStart + entry.rawEnd(),
                    DiagnosticSeverity.Warning, "Expected 'javaParam: graphqlArg' in argMapping entry."));
                continue;
            }
            if (entry.java().isEmpty()) {
                out.add(byteDiagnostic(file, contentStart + entry.rawStart(), contentStart + entry.rawEnd(),
                    DiagnosticSeverity.Warning, "Missing Java parameter before ':' in argMapping."));
            } else {
                validateArgMappingJavaParam(entry.java(), contentStart, paramNames, seenJava, file, out);
            }
            if (entry.graphql().isEmpty()) {
                out.add(byteDiagnostic(file, contentStart + entry.rawStart(), contentStart + entry.rawEnd(),
                    DiagnosticSeverity.Warning, "Missing GraphQL argument after ':' in argMapping."));
            } else {
                validateArgMappingGraphqlArg(entry.graphql(), contentStart, fieldArgs, file, out);
            }
        }
    }

    private static void validateArgMappingJavaParam(
        ArgMapping.Segment java, int contentStart, Set<String> paramNames,
        Set<String> seenJava, FileSnapshot file, List<Diagnostic> out
    ) {
        String name = java.text();
        if (!seenJava.add(name)) {
            out.add(byteDiagnostic(file, contentStart + java.start(), contentStart + java.end(),
                DiagnosticSeverity.Warning, "Duplicate Java parameter '" + name + "' in argMapping."));
            return;
        }
        if (paramNames != null && !paramNames.contains(name)) {
            out.add(byteDiagnostic(file, contentStart + java.start(), contentStart + java.end(),
                DiagnosticSeverity.Warning,
                "Unknown Java parameter '" + name + "'; not a parameter of the referenced method."));
        }
    }

    private static void validateArgMappingGraphqlArg(
        ArgMapping.Segment graphql, int contentStart, List<String> fieldArgs,
        FileSnapshot file, List<Diagnostic> out
    ) {
        if (fieldArgs.isEmpty()) return; // no field args known (pre-build or argument-less field)
        String value = graphql.text();
        int dot = value.indexOf('.');
        String head = dot >= 0 ? value.substring(0, dot) : value;
        if (head.isEmpty() || fieldArgs.contains(head)) return;
        // Flag only the head segment span so a valid dot-path with a typo'd
        // first step underlines the offending step, not the whole path.
        int headEnd = graphql.start() + head.length();
        out.add(byteDiagnostic(file, contentStart + graphql.start(), contentStart + headEnd,
            DiagnosticSeverity.Warning,
            "Unknown GraphQL argument '" + head + "' on the enclosing field."));
    }

    /**
     * Parameter-name set for the {@code argMapping}'s resolved method, or
     * {@code null} when the unknown-parameter check must be suppressed: the
     * method does not resolve, or its parameter names are unavailable (compiled
     * without {@code -parameters}). An empty set means the method resolves with
     * zero (named) parameters, so any mapping entry is unknown.
     */
    private static Set<String> resolveParameterNames(
        LspVocabulary vocabulary, Directives.Directive directive, Node anchor,
        SchemaCoordinate argMappingCoord, CompletionData catalog, byte[] source
    ) {
        var method = ArgMappingSupport.resolveMethod(vocabulary, directive, anchor, argMappingCoord, catalog, source);
        if (method.isEmpty()) return null;
        var params = method.get().parameters();
        if (params.stream().anyMatch(p -> p.name() == null)) return null;
        var names = new LinkedHashSet<String>();
        for (var p : params) names.add(p.name());
        return names;
    }

    /**
     * Unwraps the grammar's {@code value} wrapper (emitted by the leaf walk) to
     * the inner {@code string_value} token an {@code argMapping} carries, or
     * returns {@code null} when the value is not a string (e.g. a half-typed
     * unterminated literal that parses as an error node).
     */
    private static Node stringValueOf(Node node) {
        if (node == null) return null;
        if (STRING_VALUE.matches(node)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            Node found = stringValueOf(node.getChild(i).orElse(null));
            if (found != null) return found;
        }
        return null;
    }

    private static Diagnostic byteDiagnostic(
        FileSnapshot file, int startByte, int endByte, DiagnosticSeverity severity, String message
    ) {
        var start = Positions.toLspPosition(file.source(), startByte);
        var end = Positions.toLspPosition(file.source(), endByte);
        var d = new Diagnostic(new Range(start, end), message);
        d.setSeverity(severity);
        d.setSource(SOURCE);
        return d;
    }

    private static Diagnostic diagnostic(FileSnapshot file, Node node, DiagnosticSeverity severity, String message) {
        var start = Positions.toLspPosition(file.source(), node.getStartByte());
        var end = Positions.toLspPosition(file.source(), node.getEndByte());
        var d = new Diagnostic(new Range(start, end), message);
        d.setSeverity(severity);
        d.setSource(SOURCE);
        return d;
    }

    private static Diagnostic diagnostic(FileSnapshot file, Node node, String message) {
        return diagnostic(file, node, DiagnosticSeverity.Error, message);
    }
}
