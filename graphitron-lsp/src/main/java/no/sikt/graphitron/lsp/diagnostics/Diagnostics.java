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
import no.sikt.graphitron.lsp.facts.CatalogColumns;
import no.sikt.graphitron.lsp.facts.CatalogKeys;
import no.sikt.graphitron.lsp.facts.CatalogTable;
import no.sikt.graphitron.lsp.facts.CatalogTables;
import no.sikt.graphitron.lsp.facts.ClassMemberSlots;
import no.sikt.graphitron.lsp.facts.ClasspathClasses;
import no.sikt.graphitron.lsp.facts.ClasspathMethods;
import no.sikt.graphitron.lsp.facts.FieldColumnScope;
import no.sikt.graphitron.lsp.trace.LspTrace;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.rewrite.BuildWarning;
import no.sikt.graphitron.rewrite.ScalarTypeResolver;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.ValidationReport;
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

import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE;
import static no.sikt.graphitron.model.Tables.SQL_REFERENTIAL_CONSTRAINT;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.DESCRIPTION;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.ENUM_VALUE;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.NAME;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.OBJECT_FIELD;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.STRING_VALUE;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.VALUE;

/**
 * Validates known directive coordinates against the fact store's catalog, classpath and SDL
 * censuses, and emits LSP diagnostics for values that do not resolve. Dispatch is coordinate-driven:
 * for each directive in the document, the {@link LspVocabulary} walks every
 * value-bearing leaf and the validator pattern-matches on the leaf's
 * {@link Behavior} arm.
 *
 * <p>Every value arm reads the store. What each of them defers on is stated where it defers, and the
 * shape is one rule: a census holding nothing about a family is a consumer who has not built or
 * compiled yet, and a schema full of red names is the wrong thing to show them. That guard used to
 * be an {@code isEmpty()} test per surface against a projection that could be half-populated; the
 * readers answer it in the same read that answers the name now.
 *
 * <p>What is left of the classifier's projection here is one thing: which class or table backs an SDL
 * type, which the column arm needs and no relation reproduces yet.
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
        String uri, FileSnapshot file, LspSchemaSnapshot snapshot, ValidationReport report
    ) {
        return compute(LspVocabulary.load(), uri, file, snapshot, report);
    }

    /**
     * The store-free form: every arm that resolves a value against the catalog or the classpath
     * answers as if the census were unavailable, which is silence. That is the same policy the class
     * takes on a snapshot it cannot trust, and it is what a session before its first build sees.
     */
    public static List<Diagnostic> compute(
        LspVocabulary vocabulary, String uri, FileSnapshot file,
        LspSchemaSnapshot snapshot, ValidationReport report
    ) {
        return compute(vocabulary, uri, file, snapshot, report, Optional.empty());
    }

    public static List<Diagnostic> compute(
        LspVocabulary vocabulary, String uri, FileSnapshot file,
        LspSchemaSnapshot snapshot, ValidationReport report, Optional<StoreHandle> store
    ) {
        try (var span = LspTrace.span("diagnostics.compute")) {
            span.detail("uri", uri);
            var result = computeTraced(vocabulary, uri, file, snapshot, report, store, span);
            span.detail("diagnostics", result.size());
            return result;
        }
    }

    /**
     * The document walk itself. Split from {@link #compute(LspVocabulary, String, FileSnapshot,
     * LspSchemaSnapshot, ValidationReport)} so the trace span can attach the
     * directive count and the validator-projection cost measured inside it without threading a
     * span through every private validator.
     */
    private static List<Diagnostic> computeTraced(
        LspVocabulary vocabulary, String uri, FileSnapshot file,
        LspSchemaSnapshot snapshot, ValidationReport report, Optional<StoreHandle> store,
        LspTrace.Span span
    ) {
        var out = new ArrayList<Diagnostic>();
        var directives = Directives.findAll(file.tree().getRootNode());
        span.detail("directives", directives.size());
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
                    dispatch(directive, leaf, vocabulary, file, snapshot, store, out);
                }
                continue;
            }
            // Freshness-aware silence policy: only Built.Current warns.
            // Unavailable (pre-build) and Built.Previous (stale after parse
            // failure) silence the warn arms to avoid punishing the user
            // for what we cannot reliably see.
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
        // Timed separately from the directive walk: this projection scans the whole
        // ValidationReport to pick out the entries for one URI, so its cost tracks the
        // report's total size rather than anything about the file being diagnosed. On a
        // whole-workspace recalculation it is paid once per open file.
        try (var reportSpan = LspTrace.span("diagnostics.validatorReport")) {
            reportSpan.detail("errors", report.errors().size())
                .detail("warnings", report.warnings().size());
            out.addAll(validatorDiagnostics(uri, file, snapshot, report));
        }
        return out;
    }

    /**
     * Maps {@link ValidationReport} entries for {@code uri} into LSP diagnostics. Silent under
     * {@link LspSchemaSnapshot.Unavailable} (no build yet) and {@link LspSchemaSnapshot.Built.Previous}
     * (stale snapshot after a parse failure), mirroring the freshness-aware silence policy:
     * the validator's last output may not reflect the buffer the user is editing, and a stale
     * red squiggle the developer cannot fix by rewriting their schema is the noise we are trying
     * to avoid. Short-circuits when the open file has no entries in {@link ValidationReport#sourceUris}.
     *
     * <p>{@code ValidationError} with a null or {@code (0, 0)} location is dropped silently:
     * every error in the current rule set carries a usable location, and the console / watch
     * formatter is the surface for no-location (schema-wide) errors. Warnings without location
     * are dropped for the same reason.
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
     * Reads the stable wire code for typed AuthorError arms that publish one. The
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
        if (rejection instanceof no.sikt.graphitron.rewrite.model.MutationTableArgError mtae) {
            return mtae.lspCode();
        }
        if (rejection instanceof no.sikt.graphitron.rewrite.model.ErrorChannelWalkerError ecwe) {
            return ecwe.lspCode();
        }
        if (rejection instanceof no.sikt.graphitron.rewrite.model.WireCoercionError wce) {
            return wce.lspCode();
        }
        if (rejection instanceof no.sikt.graphitron.rewrite.model.ServiceCarrierShapeError scse) {
            return scse.lspCode();
        }
        if (rejection instanceof no.sikt.graphitron.rewrite.model.PivotError pe) {
            return pe.lspCode();
        }
        return null;
    }

    private static DiagnosticSeverity severityOf(Rejection rejection) {
        // Every Rejection variant fails the build via ValidationFailedException
        // (GraphQLRewriteGenerator throws on any non-empty error list, regardless
        // of arm); the editor must surface the same finality so the developer
        // sees one consistent signal across the LSP and `mvn graphitron:dev`.
        // Deferred is Error rather than Warning: the actionable hint is the
        // rejection's message, not the severity.
        return switch (rejection) {
            case Rejection.AuthorError ignored -> DiagnosticSeverity.Error;
            case Rejection.InvalidSchema ignored -> DiagnosticSeverity.Error;
            case Rejection.Deferred ignored -> DiagnosticSeverity.Error;
        };
    }

    /**
     * Builds an LSP diagnostic for a validator error or warning. {@code SourceLocation.getLine()}
     * and {@code getColumn()} are 1-based; LSP {@code Position} is 0-based. End column is
     * {@link Integer#MAX_VALUE} (gcc/AsciiDoctor convention, clamped by the LSP client to the
     * actual line end): a zero-width range at column 1 (the common case for type-level errors that
     * point at the type's declaration) is too subtle to find in editors.
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
     * of scope: the snapshot carries no input-object shapes.
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
     * user's call. {@link no.sikt.graphitron.rewrite.catalog.TypeShape#nonNull()} lives on the sealed
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


    /**
     * The per-leaf dispatch. How each arm takes the store says what it requires: the arms whose whole
     * subject is a census value run only where there is a census to read, and the two that have
     * something to say without one take the option and decide for themselves. Nothing here reads a
     * store-free model any more, so a session before its first build is silent on every value.
     */
    private static void dispatch(
        Directives.Directive directive, LspVocabulary.Leaf leaf, LspVocabulary vocabulary,
        FileSnapshot file, LspSchemaSnapshot snapshot,
        Optional<StoreHandle> store, List<Diagnostic> out
    ) {
        var behavior = vocabulary.behaviorAt(leaf.coord()).orElse(null);
        if (behavior == null) return;
        switch (behavior) {
            case Behavior.CatalogTableBinding ignored ->
                store.ifPresent(handle -> validateCatalogTable(handle, leaf.valueNode(), file, out));
            case Behavior.CatalogColumnBinding ignored ->
                validateFieldMember(directive, leaf.valueNode(), file, snapshot, store, out);
            case Behavior.CatalogFkBinding ignored ->
                store.ifPresent(handle -> validateCatalogFk(handle, leaf.valueNode(), file, out));
            case Behavior.ClassNameBinding ignored ->
                store.ifPresent(handle ->
                    validateClassName(handle, directive, leaf.valueNode(), file, out));
            case Behavior.MethodNameBinding mnb ->
                store.ifPresent(handle ->
                    validateMethod(handle, vocabulary, directive, leaf, mnb, file, out));
            case Behavior.ArgMappingBinding ignored ->
                validateArgMapping(vocabulary, directive, leaf, file, store, out);
            case Behavior.ScalarTypeBinding ignored ->
                validateScalarType(store, leaf.valueNode(), file, out);
            case Behavior.NodeTypeBinding ignored ->
                store.ifPresent(handle -> validateNodeType(handle, leaf.valueNode(), file, out));
        }
    }

    /**
     * Validates {@code @nodeId(typeName: "X")}: the named type must exist in the
     * catalog and must carry {@code @node}. Mirrors the two classifier rejections
     * that {@link no.sikt.graphitron.rewrite.FieldBuilder} produces for the same
     * coordinate: {@code Rejection.unknownTypeName} when no such type exists,
     * {@code Rejection.structural} when the type exists without {@code @node}.
     *
     * <p>Graph-keyed, so the scope is the relation's own {@code graph_name} rather than a membership
     * join, as the completion arm on the same coordinate has it: a {@code @node} declaration is a
     * fact about one graph's SDL however much of a store its module shares.
     *
     * <p>The empty-population guard the projection needed retires with it. A graph declaring no
     * {@code @node} type was indistinguishable from a projection nobody had built yet, so the arm
     * deferred on both; a store answers only after a capture, and a capture writes every
     * {@code @node} in the graph, so no rows now means the schema declares none and the build will
     * reject the reference. Silence before the first build is the absent store, decided one level up
     * in the dispatch.
     */
    private static void validateNodeType(
        StoreHandle store, Node valueNode, FileSnapshot file, List<Diagnostic> out
    ) {
        String typeName = Nodes.unquote(Nodes.text(valueNode, file.source()));
        if (typeName.isEmpty()) return;
        if (store.dsl().fetchExists(GRAPHITRON_NODE,
                GRAPHITRON_NODE.GRAPH_NAME.eq(store.graphName())
                    .and(GRAPHITRON_NODE.TYPE_NAME.eq(typeName)))) {
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
     *   <li>Classpath: the class part must be one the census holds
     *       (mirrors {@link #validateClassName}). Skipped when the census holds nothing (pre-compile
     *       state); the build-tier resolver produces the precise rejection arm then.</li>
     * </ul>
     *
     * <p>The malformed-value diagnostic is the one thing here that needs no census, which is why this
     * arm takes the store as an option: a value with no dot cannot be resolved at codegen whatever
     * the classpath holds, and saying so before the first build costs nothing.
     *
     * <p>Field-level validation ({@code FieldNotFound}, {@code NotAScalarType},
     * {@code CoercingErased}) requires reflection on the actual class and lives in the
     * build-tier {@link no.sikt.graphitron.rewrite.ScalarTypeResolver}; the LSP surfaces those
     * errors via the build pipeline's diagnostics, not inline.
     */
    private static void validateScalarType(
        Optional<StoreHandle> store, Node valueNode, FileSnapshot file, List<Diagnostic> out
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
                store.ifPresent(handle -> validateScalarTypeClasspath(handle, p, valueNode, file, out));
        }
    }

    /**
     * Classpath half of {@link #validateScalarType}. The census is the compile classpath, which is
     * what the codegen loader resolves against, so a library constant such as
     * {@code graphql.scalars.ExtendedScalars.Date} is found here exactly when codegen would bind
     * it. Deferred when the census holds no class at all (pre-{@code mvn compile} state).
     */
    private static void validateScalarTypeClasspath(
        StoreHandle store, ScalarTypeResolver.ParsedDirectiveValue.Parsed parsed,
        Node valueNode, FileSnapshot file, List<Diagnostic> out
    ) {
        if (ClasspathClasses.presenceOf(store, parsed.classFqn()) != ClasspathClasses.Presence.UNKNOWN) {
            return;
        }
        out.add(diagnostic(file, valueNode,
            "Unknown class '" + parsed.classFqn() + "' on @scalarType. Not found on "
            + "the compile classpath."));
    }

    /**
     * Validates {@code @table(name:)} against the catalog census, case-insensitively and across every
     * schema, which is how the generator's own resolver matches: an editor must not flag a name the
     * build accepts, and which of two schemas an unqualified name means is a resolution question the
     * census leaves open.
     *
     * <p>A census holding no table at all defers, for the reason the class arm defers: a consumer
     * whose generated model is not there yet has written no wrong names, and turning every
     * {@code @table} in their schema red while they wait for codegen is noise.
     */
    private static void validateCatalogTable(
        StoreHandle store, Node valueNode, FileSnapshot file, List<Diagnostic> out
    ) {
        String tableName = Nodes.unquote(Nodes.text(valueNode, file.source()));
        if (tableName.isEmpty()) return;
        switch (CatalogTables.named(store, tableName)) {
            case CatalogTables.Match.Tables ignored -> { /* the name resolves */ }
            case CatalogTables.Match.NoCensus ignored -> { /* nothing generated yet */ }
            case CatalogTables.Match.Unknown ignored ->
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
        Directives.Directive directive, Node valueNode, FileSnapshot file,
        LspSchemaSnapshot snapshot, Optional<StoreHandle> store, List<Diagnostic> out
    ) {
        String memberName = Nodes.unquote(Nodes.text(valueNode, file.source()));
        if (memberName.isEmpty()) return;
        var typeDecl = DeclarationKind.enclosing(directive.outer());
        if (typeDecl.isEmpty()) return;
        var typeName = TypeContext.declaredNameOf(typeDecl.get(), file.source());
        if (typeName.isEmpty()) return;
        var fieldName = TypeContext.enclosingFieldOrInputValueDefinition(directive.outer())
            .flatMap(fd -> TypeContext.fieldNameOf(fd, file.source()))
            .orElse(null);
        // If the value is the $source sigil, the diagnostic shape is sigil-aware. The
        // snapshot owns the (typeName, fieldName) -> SiteContext classification through
        // siteContext(); we route the predicate through sourceSigilDefinedAt rather than reading
        // the underlying projection ourselves. At an admitted carrier-data-field site, the
        // sigil is valid — no diagnostic. Anywhere else, emit the canonical
        // FieldSourceSigil.sourceSigilNotDefinedHereMessage(). Snapshot-uncertainty: when the
        // parent type has no entry in the type-backing projection at all (mid-edit / not-yet-
        // classified), stay silent so we don't punish the user for a shape we cannot resolve.
        if (no.sikt.graphitron.rewrite.FieldSourceSigil.UPSTREAM_ROOT_LITERAL.equals(memberName)) {
            if (!(snapshot instanceof LspSchemaSnapshot.Built sigilSnapshot)) return;
            boolean isPayloadDataField = fieldName != null
                && no.sikt.graphitron.rewrite.FieldSourceSigil.sourceSigilDefinedAt(
                    sigilSnapshot.siteContext(typeName.get(), fieldName));
            if (!isPayloadDataField && sigilSnapshot.typesByName().containsKey(typeName.get())) {
                out.add(diagnostic(file, valueNode, DiagnosticSeverity.Error,
                    no.sikt.graphitron.rewrite.FieldSourceSigil.sourceSigilNotDefinedHereMessage()));
            }
            return;
        }
        // Prefer the site's own resolved scope over the enclosing type's @table: a @reference
        // path's terminal table, or the table the named type is itself bound to, is where the
        // column named here lives, and validating against the parent's table would report a
        // column that is missing from the wrong end of a join. A Silent scope emits nothing: while
        // the author's claims disagree or their path reaches no table, the report that names the
        // real problem is the one to leave standing. No row at all means the parent's own scope
        // answers, which is the dispatch below.
        if (fieldName != null) {
            var scope = store.flatMap(handle ->
                FieldColumnScope.of(handle, typeName.get(), fieldName));
            if (scope.isPresent()) {
                switch (scope.get()) {
                    case FieldColumnScope.Scope.Resolved(var table) -> {
                        validateColumnOnResolvedTable(
                            store.orElseThrow(), table, memberName, valueNode, file, out);
                        return;
                    }
                    case FieldColumnScope.Scope.Silent ignored -> { return; }
                }
            }
        }
        // The parent's own scope. What the parent is backed by is still the projection's to answer,
        // the binding being a reflective walk no relation reproduces yet; what a backing then offers
        // is the store's, whether that is a table's columns or a class's member slots.
        if (!(snapshot instanceof LspSchemaSnapshot.Built built)) return;
        var backing = built.typesByName().get(typeName.get());
        if (backing == null) return;
        switch (backing) {
            case TypeBackingShape.RecordBacking r ->
                validateMemberSlot(store, r.fqClassName(), memberName, valueNode, file, out);
            case TypeBackingShape.PojoBacking p ->
                validateMemberSlot(store, p.fqClassName(), memberName, valueNode, file, out);
            case TypeBackingShape.JooqRecordBacking.WithTable j ->
                validateColumnOnTable(store, j.tableName(), memberName, valueNode, file, out);
            case TypeBackingShape.JooqRecordBacking.Standalone ignored -> { /* no actionable diagnostic */ }
            case TypeBackingShape.TableBacking t ->
                validateColumnOnTable(store, t.tableName(), memberName, valueNode, file, out);
            case TypeBackingShape.NoBacking ignored -> { /* no actionable diagnostic */ }
        }
    }

    /**
     * The same check against a table a resolution already picked, read from the census rather than
     * from the projection. Both of the column's names count, as the spelling-keyed arm below
     * already accepts: the author may write the SQL name or the one generated code spells.
     */
    private static void validateColumnOnResolvedTable(
        StoreHandle store, CatalogTable table, String columnName,
        Node valueNode, FileSnapshot file, List<Diagnostic> out
    ) {
        var columns = CatalogColumns.of(store, table);
        if (columns.isEmpty()) {
            // The resolved table carries no captured columns, so there is nothing to check it
            // against and no basis for calling the name unknown.
            return;
        }
        if (columns.stream().noneMatch(column -> column.isNamed(columnName))) {
            out.add(diagnostic(file, valueNode, DiagnosticSeverity.Error,
                "Unknown column '" + columnName + "' on table '" + table.tableName() + "'."));
        }
    }

    /**
     * The same check against a table named by a spelling rather than resolved: the parent's
     * {@code @table} argument as the classifier's projection carried it. A name two schemas both
     * declare contributes both column lists, which is what the census says and what makes this the
     * looser of the two reads; the resolved-key form above is the one an author's own site resolves
     * to.
     */
    private static void validateColumnOnTable(
        Optional<StoreHandle> store, String tableName, String columnName,
        Node valueNode, FileSnapshot file, List<Diagnostic> out
    ) {
        if (store.isEmpty()) return;
        var columns = CatalogColumns.of(store.get(), tableName);
        if (columns.isEmpty()) {
            // Either the enclosing @table is itself a typo, which the @table validation already
            // flagged, or the census has no columns for it and there is nothing to check against.
            return;
        }
        if (columns.stream().noneMatch(column -> column.isNamed(columnName))) {
            out.add(diagnostic(file, valueNode, DiagnosticSeverity.Error,
                "Unknown column '" + columnName + "' on table '" + tableName + "'."));
        }
    }

    /**
     * The member name must be one the backing class offers. A class the census holds nothing for is
     * not a class with no members: it is a class nobody has compiled yet, so the arm stays silent
     * rather than calling every name unknown mid-build.
     *
     * <p>The word the message uses for the member is the slots' own origin, not the permit that
     * routed the arm here. Every slot of one class shares it, the relation choosing its arm by the
     * class's declared form, so the first slot speaks for the class.
     */
    private static void validateMemberSlot(
        Optional<StoreHandle> store, String fqClassName, String memberName,
        Node valueNode, FileSnapshot file, List<Diagnostic> out
    ) {
        if (store.isEmpty()) return;
        var slots = ClassMemberSlots.of(store.get(), fqClassName);
        if (slots.isEmpty()) return;
        if (slots.stream().anyMatch(slot -> slot.name().equals(memberName))) return;
        String kind = switch (slots.getFirst().origin()) {
            case RECORD_COMPONENT -> "component";
            case BEAN_ACCESSOR -> "property";
        };
        out.add(diagnostic(file, valueNode, DiagnosticSeverity.Error,
            "Unknown " + kind + " '" + memberName + "' on backing class '" + fqClassName + "'."));
    }

    /**
     * Validates {@code @reference(key:)} against the key census, matched the way the generator's own
     * resolver matches so the editor cannot flag a name the build accepts: either namespace, the SQL
     * constraint name or the generated {@code Keys} constant, case-insensitively, with a leading
     * {@code schema.} qualifier scoping rather than widening. {@link CatalogKeys#named} owns that
     * whole rule, which is the point of asking it: the accepted set is one spelling of one
     * resolution instead of two that agree until one changes.
     *
     * <p>Wider than the projection's answer was, and deliberately. The projection carried only the
     * generated constant, so a plain SQL constraint name flagged even though the generator resolves
     * it and the completion arm on this coordinate offers it. A qualified name under a wrong schema
     * is flagged now rather than waved through, the census carrying the declaring schema the
     * projection had nowhere to put. Path-step refinement (which step's table the cursor is on) is
     * still not validated.
     */
    private static void validateCatalogFk(
        StoreHandle store, Node valueNode, FileSnapshot file, List<Diagnostic> out
    ) {
        String rawFk = Nodes.unquote(Nodes.text(valueNode, file.source()));
        if (rawFk.isEmpty()) return;
        if (!CatalogKeys.named(store, rawFk).isEmpty()) return;
        // No key of this name, in either namespace. Defer while the census holds no key at all: a
        // generated model that is not there yet has no names to be wrong about.
        if (!store.dsl().fetchExists(SQL_REFERENTIAL_CONSTRAINT,
                store.reads(SQL_REFERENTIAL_CONSTRAINT.SOURCE_NAME))) {
            return;
        }
        out.add(diagnostic(file, valueNode,
            "Unknown foreign key '" + rawFk + "'. Not present in the jOOQ catalog."));
    }

    private static void validateClassName(
        StoreHandle store, Directives.Directive directive, Node valueNode,
        FileSnapshot file, List<Diagnostic> out
    ) {
        // @record carve-out: @record is deprecated/ignored, so its className slot binds no class
        // and an unknown-class diagnostic would be noise. The ExternalCodeReference.className
        // coordinate is shared with @enum, so the carve-out keys on the directive name, not
        // the coordinate (see DirectivePolicy).
        if (!DirectivePolicy.bindsLiveClass(Nodes.text(directive.nameNode(), file.source()))) return;
        String fqn = Nodes.unquote(Nodes.text(valueNode, file.source()));
        if (fqn.isEmpty()) return;
        // A census holding nothing is a consumer who has not run `mvn compile` yet, not a schema full
        // of wrong names, so it defers; the reader answers the two apart in one read.
        if (ClasspathClasses.presenceOf(store, fqn) != ClasspathClasses.Presence.UNKNOWN) return;
        out.add(diagnostic(file, valueNode,
            "Unknown class '" + fqn + "'. Not found on the compile classpath."));
    }

    private static void validateMethod(
        StoreHandle store, LspVocabulary vocabulary,
        Directives.Directive directive, LspVocabulary.Leaf leaf,
        Behavior.MethodNameBinding mnb,
        FileSnapshot file, List<Diagnostic> out
    ) {
        // @record / @enum bind ExternalCodeReference but the method slot
        // wraps a type, not a method invocation; skip (see DirectivePolicy).
        if (!DirectivePolicy.bindsLiveMethod(Nodes.text(directive.nameNode(), file.source()))) return;

        String methodName = Nodes.unquote(Nodes.text(leaf.valueNode(), file.source()));
        if (methodName.isEmpty()) return;

        Optional<String> classFqn = vocabulary.siblingStringAt(
            directive, leaf.valueNode(), mnb.classNameCoord(), file.source());
        if (classFqn.isEmpty()) return;

        // Sibling className unresolved, or a census with nothing in it: the className validator has
        // the first case and defers on the second, so this arm has nothing to add to either.
        if (ClasspathClasses.presenceOf(store, classFqn.get()) != ClasspathClasses.Presence.KNOWN) {
            return;
        }
        var overloads = ClasspathMethods.named(store, classFqn.get(), methodName);
        if (overloads.isEmpty()) {
            out.add(diagnostic(file, leaf.valueNode(),
                "Unknown method '" + methodName + "' on class '" + classFqn.get() + "'."));
            return;
        }
        // The method resolved. If it takes parameters but the consumer
        // compiled the class without -parameters, parameter names are
        // unknown on every one of them. Surface the same
        // warning the rewrite generator emits at build time
        // (ServiceCatalog.emitParametersWarning), but as a per-reference
        // warning so the schema author sees it inline next to the
        // affected directive. Every overload of the name has to be nameless for it: one that carries
        // names is one the author may have meant, and the message is about the name they wrote.
        boolean noNames = overloads.stream()
            .allMatch(m -> !m.parameters().isEmpty()
                && m.parameters().stream().allMatch(p -> p.name() == null));
        if (noNames) {
            out.add(diagnostic(file, leaf.valueNode(), DiagnosticSeverity.Warning,
                "Class '" + classFqn.get() + "' was compiled without `-parameters`; "
                + "parameter help on '" + methodName + "' is unavailable. "
                + "Set `<parameters>true</parameters>` on maven-compiler-plugin "
                + "to surface parameter names."));
        }
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
     *       of the enclosing field. Deeper dot-path segments are not
     *       validated (the LSP carries no projection of nested input-type
     *       fields); only the head segment is checked.</li>
     * </ul>
     */
    private static void validateArgMapping(
        LspVocabulary vocabulary, Directives.Directive directive, LspVocabulary.Leaf leaf,
        FileSnapshot file, Optional<StoreHandle> store, List<Diagnostic> out
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

        Set<String> paramNames = resolveParameterNames(
            vocabulary, directive, valueNode, leaf.coord(), store, source);
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
     * Parameter-name set for the method the {@code argMapping}'s siblings name, or {@code null} when
     * the unknown-parameter check must be suppressed: no store to ask, a method that does not
     * resolve, or parameter names unavailable (compiled without {@code -parameters}). An empty set
     * means the method resolves with zero named parameters, so any mapping entry is unknown.
     *
     * <p>Across overloads rather than on one of them. SDL names a method by name alone, so which
     * overload codegen binds is not something this arm can know, and a name that is a parameter of
     * some overload is one the author may correctly have written; the union is the set that cannot
     * produce a false positive. The projection answered from whichever overload it held first, which
     * was the same guess made silently.
     */
    private static Set<String> resolveParameterNames(
        LspVocabulary vocabulary, Directives.Directive directive, Node anchor,
        SchemaCoordinate argMappingCoord, Optional<StoreHandle> store, byte[] source
    ) {
        if (store.isEmpty()) return null;
        var target = ArgMappingSupport.siblingMethodTarget(
            vocabulary, directive, anchor, argMappingCoord, source);
        if (target.isEmpty()) return null;
        var overloads = ClasspathMethods.named(
            store.get(), target.get().className(), target.get().methodName());
        if (overloads.isEmpty()) return null;
        if (overloads.stream().anyMatch(ClasspathMethods.Method::hasUnnamedParameters)) return null;
        var names = new LinkedHashSet<String>();
        for (var method : overloads) {
            for (var parameter : method.parameters()) names.add(parameter.name());
        }
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
