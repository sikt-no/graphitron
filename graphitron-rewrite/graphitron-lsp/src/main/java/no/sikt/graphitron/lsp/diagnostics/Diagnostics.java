package no.sikt.graphitron.lsp.diagnostics;

import graphql.language.DirectiveDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.NonNullType;
import graphql.language.SourceLocation;
import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.DeclarationKind;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.Nodes;
import no.sikt.graphitron.lsp.parsing.Positions;
import no.sikt.graphitron.lsp.parsing.SchemaCoordinate;
import no.sikt.graphitron.lsp.parsing.TypeContext;
import no.sikt.graphitron.lsp.state.DirectiveResolution;
import no.sikt.graphitron.lsp.state.WorkspaceFile;
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
        String uri, WorkspaceFile file, CompletionData catalog, LspSchemaSnapshot snapshot,
        ValidationReport report
    ) {
        return compute(LspVocabulary.load(), uri, file, catalog, snapshot, report);
    }

    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "snapshot-built-implies-clean-parse",
        reliesOn = "warns under Built.Current + Unknown only; silences under Unavailable and "
            + "Built.Previous on the conservative principle 'do not punish the user for what we "
            + "cannot reliably see'."
    )
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "validation-report.canonical-uri",
        reliesOn = "filters validator errors and warnings against the open file via "
            + "ValidationReport.canonicalUri so producer and consumer share one canonical "
            + "file:// URI form."
    )
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "source-location.absolute-path-source-name",
        reliesOn = "treats SourceLocation.sourceName as a path that resolves to the same "
            + "canonical URI the LSP client opened; Maven callers populate it as absolute via "
            + "MultiSourceReader.trackData(true)."
    )
    public static List<Diagnostic> compute(
        LspVocabulary vocabulary, String uri, WorkspaceFile file, CompletionData catalog,
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
        String uri, WorkspaceFile file, LspSchemaSnapshot snapshot, ValidationReport report
    ) {
        return switch (snapshot) {
            case LspSchemaSnapshot.Unavailable ignored -> List.of();
            case LspSchemaSnapshot.Built.Previous ignored -> List.of();
            case LspSchemaSnapshot.Built.Current ignored -> validatorDiagnosticsForCurrent(uri, report);
        };
    }

    private static List<Diagnostic> validatorDiagnosticsForCurrent(String uri, ValidationReport report) {
        if (!report.sourceUris().contains(uri)) {
            return List.of();
        }
        var out = new ArrayList<Diagnostic>();
        for (ValidationError error : report.errors()) {
            var loc = error.location();
            if (!matchesOpenFile(uri, loc)) continue;
            out.add(validatorDiagnostic(loc, severityOf(error.rejection()), error.message()));
        }
        for (BuildWarning warning : report.warnings()) {
            var loc = warning.location();
            if (!matchesOpenFile(uri, loc)) continue;
            out.add(validatorDiagnostic(loc, DiagnosticSeverity.Warning, warning.message()));
        }
        return out;
    }

    private static boolean matchesOpenFile(String uri, SourceLocation loc) {
        if (loc == null || loc.getLine() <= 0) return false;
        String sourceName = loc.getSourceName();
        if (sourceName == null || sourceName.isEmpty()) return false;
        return uri.equals(ValidationReport.canonicalUri(sourceName));
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
        SourceLocation loc, DiagnosticSeverity severity, String message
    ) {
        var start = new Position(loc.getLine() - 1, Math.max(0, loc.getColumn() - 1));
        var end = new Position(loc.getLine() - 1, Integer.MAX_VALUE);
        var d = new Diagnostic(new Range(start, end), message);
        d.setSeverity(severity);
        d.setSource(VALIDATOR_SOURCE);
        return d;
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
        WorkspaceFile file, CompletionData catalog, LspSchemaSnapshot snapshot, List<Diagnostic> out
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
                validateClassName(leaf.valueNode(), file, catalog, out);
            case Behavior.MethodNameBinding mnb ->
                validateMethod(vocabulary, directive, leaf, mnb, file, catalog, out);
            case Behavior.ArgMappingBinding ignored -> { /* sibling roadmap item */ }
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
        Node valueNode, WorkspaceFile file, CompletionData catalog, List<Diagnostic> out
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

    /**
     * Validates a {@code @field(name:)} (or other {@code CatalogColumnBinding}
     * coordinate) against the enclosing SDL type's backing shape: column on a
     * table-bound type, component on a Java record, accessor on a POJO. The
     * dispatch reads {@link LspSchemaSnapshot.Built#typesByName} so the
     * classifier's projection of the enclosing type is the authoritative
     * answer.
     */
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "java-record-type-backs-record-class",
        reliesOn = "Treats RecordBacking.components as the authoritative member list for "
            + "@field(name:) validation under a @record-bound Java record parent; emits "
            + "\"Unknown component\" without re-checking that the backing class is a record."
    )
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "field-classification-payload-faithful",
        reliesOn = "For @reference path fields, consults FieldClassification.{ColumnReference,"
            + "CompositeColumnReference}.tableName() — projected through "
            + "CatalogBuilder.terminalTableName — as the authoritative target table for "
            + "@field(name:) column validation, rather than re-walking the path or falling back "
            + "to the enclosing type's @table. Mirrors the runtime's "
            + "ServiceCatalog.resolveColumnForReference terminal-table walk."
    )
    private static void validateFieldMember(
        Directives.Directive directive, Node valueNode,
        WorkspaceFile file, CompletionData catalog, LspSchemaSnapshot snapshot, List<Diagnostic> out
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
        // R224: prefer the field classification's tableName() over the enclosing type's @table.
        // For @reference path fields, the classifier projects the terminal table onto
        // FieldClassification.{Column,CompositeColumn}Reference; the enclosing type's @table
        // is the path origin, not the column's owner. Re-using the projection keeps the LSP's
        // dispatch aligned with the runtime's terminal-table walk
        // (ServiceCatalog.resolveColumnForReference). Snapshot-uncertainty (empty optional)
        // falls through to the existing backing-driven dispatch.
        if (fieldName != null) {
            var classification = built.fieldClassification(typeName.get(), fieldName);
            if (classification.isPresent()) {
                switch (classification.get()) {
                    case FieldClassification.Column c -> {
                        validateColumnOnTable(catalog, c.tableName(), memberName, valueNode, file, out);
                        return;
                    }
                    case FieldClassification.ColumnReference c -> {
                        validateColumnOnTable(catalog, c.tableName(), memberName, valueNode, file, out);
                        return;
                    }
                    case FieldClassification.CompositeColumn c -> {
                        validateColumnOnTable(catalog, c.tableName(), memberName, valueNode, file, out);
                        return;
                    }
                    case FieldClassification.CompositeColumnReference c -> {
                        validateColumnOnTable(catalog, c.tableName(), memberName, valueNode, file, out);
                        return;
                    }
                    case FieldClassification.InputUnbound ignored -> {
                        // The validator already emits a precise message via ValidationReport
                        // ("plain input type 'T': input field 'X': no column 'Y' reachable via
                        // @reference path"); a duplicate LSP diagnostic with the wrong table
                        // would be noise.
                        return;
                    }
                    case FieldClassification.Unclassified ignored -> { return; }
                    default -> { /* fall through to backing-driven dispatch */ }
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
        Node valueNode, WorkspaceFile file, List<Diagnostic> out
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
        String fqClassName, Node valueNode, WorkspaceFile file, List<Diagnostic> out
    ) {
        if (slots.isEmpty()) return;
        boolean matched = slots.stream().anyMatch(s -> s.name().equals(memberName));
        if (!matched) {
            out.add(diagnostic(file, valueNode, DiagnosticSeverity.Error,
                "Unknown " + kind + " '" + memberName + "' on backing class '" + fqClassName + "'."));
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
