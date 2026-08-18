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
import no.sikt.graphitron.lsp.parsing.TypeContext;
import no.sikt.graphitron.lsp.state.DirectiveResolution;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.lsp.facts.ClassMemberSlots;
import no.sikt.graphitron.lsp.facts.TypeMemberScope;
import no.sikt.graphitron.lsp.trace.LspTrace;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.rewrite.BuildWarning;
import no.sikt.graphitron.rewrite.ScalarTypeResolver;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.ValidationReport;
import no.sikt.graphitron.rewrite.catalog.DirectiveShape;
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
 * Validates known directive coordinates against the fact store's catalog, classpath and SDL censuses,
 * and emits LSP diagnostics for values that do not resolve. Dispatch is coordinate-driven: for each
 * directive in the document, the {@link LspVocabulary} walks every value-bearing leaf and this class
 * pattern-matches on the leaf's {@link Behavior} arm.
 *
 * <h2>Collect, resolve, judge</h2>
 *
 * <p>The pass runs in three stages, and the split is what makes a whole document one statement. The
 * walk reads nothing: it settles the checks the tree alone answers and records a {@link Finding} for
 * every check a census must answer, putting the value it needs resolved into a
 * {@link DiagnosticFacts.Questions}. {@link DiagnosticFacts} then answers the whole document at once.
 * Then each finding is judged in the order the walk found it, so an editor sees what it always saw.
 *
 * <p>This is the shape a declaration hover has at a coordinate, at a document's grain. The reason it
 * transfers is that the questions are independent: a table name, a foreign key, a class, a method, a
 * {@code @node} reference and a member name are resolved by relations sharing no key, so no answer
 * decides what to ask next, and the walk can therefore collect all of them before any of them is
 * resolved. The count is held by {@code DiagnosticsStatementCountTest}, which pins that it does not
 * grow with the file: a ten-field type used to cost thirty-one statements, resolving each name where
 * it was found.
 *
 * <h2>What silence means</h2>
 *
 * <p>Every value arm reads the store, and what each defers on is stated where it defers. The shape is
 * one rule: a census holding nothing about a family is a consumer who has not built or compiled yet,
 * and a schema full of red names is the wrong thing to show them. That guard used to be an
 * {@code isEmpty()} test per surface against a projection that could be half-populated, and it is one
 * three-valued answer per census now, so no arm can hold the two questions in the wrong order.
 *
 * <p>What is left of the classifier's projection here is one thing, and it is not a binding: whether a
 * site admits the {@code $source} sigil, which is the carrier classification. It needs no store, so it
 * is settled in the walk.
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

    /**
     * One thing the walk found, either already decided or waiting on a census. The arms are the
     * censuses rather than the directives: what separates two findings is which relation resolves the
     * value, and a coordinate's directive is only how the walk got there.
     *
     * <p>Findings exist so the walk can read nothing. A check that emitted straight into the output
     * had to resolve its value on the spot, which is what made the surface cost a statement per value
     * an author wrote; deferring the verdict is what lets one statement answer the whole document.
     */
    private sealed interface Finding {

        /** A diagnostic the tree alone settled, carried so document order survives the split. */
        record Ready(Diagnostic diagnostic) implements Finding {}

        /** A {@code @table(name:)} value, against the table census. */
        record TableName(Node node, String spelling) implements Finding {}

        /** A {@code @reference(key:)} value, against the key census. */
        record ForeignKeyName(Node node, String spelling) implements Finding {}

        /** A class FQN a directive binds, against the classpath census. */
        record ClassName(Node node, String fqn) implements Finding {}

        /**
         * The class half of a {@code @scalarType} reference. Its own arm because the message names the
         * directive: the value is a field reference rather than a class name, and an author reading
         * "unknown class" about something they wrote as a dotted path needs to know which half is wrong.
         */
        record ScalarClassName(Node node, String fqn) implements Finding {}

        /** A method name, against the overloads its sibling class declares. */
        record MethodName(Node node, String classFqn, String methodName) implements Finding {}

        /** A {@code @nodeId(typeName:)} value, against the graph's {@code @node} declarations. */
        record NodeTypeName(Node node, String typeName) implements Finding {}

        /**
         * A member name written at a site, against whatever that site's scope resolves against. The
         * field name may be absent, the type's own scope answering for a name written outside any field.
         */
        record MemberName(Node node, String typeName, String fieldName, String memberName)
            implements Finding {}

        /**
         * A parsed {@code argMapping} string, judged whole. One finding for the entire value rather
         * than one per entry because the checks interleave: an entry's structure and its Java
         * parameter are reported in that order, and only the second needs the census.
         *
         * @param classFqn the sibling-named class, or absent where the siblings name no method; the
         *                 unknown-parameter check is suppressed then, having nothing to check against
         */
        record ArgMappingValue(
            Node node, int contentStart, List<ArgMapping.Entry> entries, String classFqn,
            String methodName, List<String> fieldArgs
        ) implements Finding {}
    }

    /**
     * Where the walk puts a diagnostic it settled itself. A sink rather than the output list because
     * the output list does not exist yet: everything the walk settles has to keep its place among the
     * verdicts the store has not answered, so it goes into the finding sequence instead.
     */
    private record Settled(List<Finding> findings) {

        void add(Diagnostic diagnostic) {
            findings.add(new Finding.Ready(diagnostic));
        }
    }

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
     * The three stages, in order. Split from {@link #compute(LspVocabulary, String, FileSnapshot,
     * LspSchemaSnapshot, ValidationReport)} so the trace span can attach the directive count and the
     * validator-projection cost measured inside it without threading a span through every stage.
     *
     * <p>An absent store is answered rather than branched on: {@link DiagnosticFacts#none} is the same
     * value an empty census gives, so every arm defers on it for the reason it defers on any empty
     * census, and no arm carries a case for the store's absence.
     */
    private static List<Diagnostic> computeTraced(
        LspVocabulary vocabulary, String uri, FileSnapshot file,
        LspSchemaSnapshot snapshot, ValidationReport report, Optional<StoreHandle> store,
        LspTrace.Span span
    ) {
        var findings = new ArrayList<Finding>();
        var questions = new DiagnosticFacts.Questions();
        walk(vocabulary, file, snapshot, findings, questions, span);
        var answers = store.map(handle -> DiagnosticFacts.of(handle, questions))
            .orElseGet(DiagnosticFacts::none);
        var out = new ArrayList<Diagnostic>(findings.size());
        for (var finding : findings) {
            judge(finding, answers, file, out);
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
     * The document walk, which reads nothing. Every check the tree alone settles becomes a
     * {@link Finding.Ready}; every check a census must answer becomes a finding naming what it needs,
     * and the value it needs resolved goes into {@code questions}. Findings accumulate in document
     * order and are judged in that order, so splitting the pass does not reorder what an editor shows.
     */
    private static void walk(
        LspVocabulary vocabulary, FileSnapshot file, LspSchemaSnapshot snapshot,
        List<Finding> findings, DiagnosticFacts.Questions questions, LspTrace.Span span
    ) {
        var out = new Settled(findings);
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
                    collect(directive, leaf, vocabulary, file, snapshot, findings, questions, out);
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
        LspVocabulary vocabulary, FileSnapshot file, Settled out
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
        LspVocabulary vocabulary, FileSnapshot file, Settled out
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
        FileSnapshot file, Settled out
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
        FileSnapshot file, Settled out
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
        FileSnapshot file, Settled out
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
     * The per-leaf collection. Each arm records what it will need to know instead of looking it up, so
     * the walk over a document reads nothing at all. What an arm can settle without a census it still
     * settles here: a scalar reference with no dot in it will not resolve whatever the classpath holds,
     * and saying so before the first build costs nothing.
     */
    private static void collect(
        Directives.Directive directive, LspVocabulary.Leaf leaf, LspVocabulary vocabulary,
        FileSnapshot file, LspSchemaSnapshot snapshot, List<Finding> findings,
        DiagnosticFacts.Questions questions, Settled out
    ) {
        var behavior = vocabulary.behaviorAt(leaf.coord()).orElse(null);
        if (behavior == null) return;
        switch (behavior) {
            case Behavior.CatalogTableBinding ignored -> {
                String spelling = value(leaf.valueNode(), file);
                if (spelling.isEmpty()) return;
                questions.tableName(spelling);
                findings.add(new Finding.TableName(leaf.valueNode(), spelling));
            }
            case Behavior.CatalogColumnBinding ignored ->
                collectMemberName(directive, leaf.valueNode(), file, snapshot, findings, questions, out);
            case Behavior.CatalogFkBinding ignored -> {
                String spelling = value(leaf.valueNode(), file);
                if (spelling.isEmpty()) return;
                questions.foreignKeyName(spelling);
                findings.add(new Finding.ForeignKeyName(leaf.valueNode(), spelling));
            }
            case Behavior.ClassNameBinding ignored -> {
                // @record carve-out: @record is deprecated/ignored, so its className slot binds no
                // class and an unknown-class diagnostic would be noise. The
                // ExternalCodeReference.className coordinate is shared with @enum, so the carve-out
                // keys on the directive name, not the coordinate (see DirectivePolicy).
                if (!DirectivePolicy.bindsLiveClass(Nodes.text(directive.nameNode(), file.source()))) {
                    return;
                }
                String fqn = value(leaf.valueNode(), file);
                if (fqn.isEmpty()) return;
                questions.className(fqn);
                findings.add(new Finding.ClassName(leaf.valueNode(), fqn));
            }
            case Behavior.MethodNameBinding mnb ->
                collectMethodName(vocabulary, directive, leaf, mnb, file, findings, questions);
            case Behavior.ArgMappingBinding ignored ->
                collectArgMapping(vocabulary, directive, leaf, file, findings, questions);
            case Behavior.ScalarTypeBinding ignored ->
                collectScalarType(leaf.valueNode(), file, findings, questions, out);
            case Behavior.NodeTypeBinding ignored -> {
                String typeName = value(leaf.valueNode(), file);
                if (typeName.isEmpty()) return;
                questions.nodeTypeName(typeName);
                findings.add(new Finding.NodeTypeName(leaf.valueNode(), typeName));
            }
        }
    }

    /** An author-written value with its quotes off, which is what every census is asked about. */
    private static String value(Node valueNode, FileSnapshot file) {
        return Nodes.unquote(Nodes.text(valueNode, file.source()));
    }

    /**
     * Collects a {@code @field(name:)} (or other {@code CatalogColumnBinding}) coordinate, and settles
     * the one thing here the store has no part in.
     *
     * <p>The snapshot still answers one question at this coordinate, and it is not the backing: whether
     * the site admits the {@code $source} sigil, which is the carrier classification rather than
     * anything about a table or a class. If the value is the sigil, the diagnostic shape is sigil-aware.
     * The snapshot owns the (typeName, fieldName) to SiteContext classification through siteContext();
     * we route the predicate through sourceSigilDefinedAt rather than reading the underlying projection
     * ourselves. At an admitted carrier-data-field site the sigil is valid, so no diagnostic. Anywhere
     * else, emit the canonical FieldSourceSigil.sourceSigilNotDefinedHereMessage().
     * Snapshot-uncertainty: when the parent type has no entry in the type-backing projection at all
     * (mid-edit, or not yet classified), stay silent so we do not punish the user for a shape we cannot
     * resolve.
     */
    private static void collectMemberName(
        Directives.Directive directive, Node valueNode, FileSnapshot file, LspSchemaSnapshot snapshot,
        List<Finding> findings, DiagnosticFacts.Questions questions, Settled out
    ) {
        String memberName = value(valueNode, file);
        if (memberName.isEmpty()) return;
        var typeDecl = DeclarationKind.enclosing(directive.outer());
        if (typeDecl.isEmpty()) return;
        var typeName = TypeContext.declaredNameOf(typeDecl.get(), file.source());
        if (typeName.isEmpty()) return;
        var fieldName = TypeContext.enclosingFieldOrInputValueDefinition(directive.outer())
            .flatMap(fd -> TypeContext.fieldNameOf(fd, file.source()))
            .orElse(null);
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
        questions.memberSite(typeName.get(), fieldName);
        findings.add(new Finding.MemberName(valueNode, typeName.get(), fieldName, memberName));
    }

    private static void collectMethodName(
        LspVocabulary vocabulary, Directives.Directive directive, LspVocabulary.Leaf leaf,
        Behavior.MethodNameBinding mnb, FileSnapshot file, List<Finding> findings,
        DiagnosticFacts.Questions questions
    ) {
        // @record / @enum bind ExternalCodeReference but the method slot
        // wraps a type, not a method invocation; skip (see DirectivePolicy).
        if (!DirectivePolicy.bindsLiveMethod(Nodes.text(directive.nameNode(), file.source()))) return;
        String methodName = value(leaf.valueNode(), file);
        if (methodName.isEmpty()) return;
        Optional<String> classFqn = vocabulary.siblingStringAt(
            directive, leaf.valueNode(), mnb.classNameCoord(), file.source());
        if (classFqn.isEmpty()) return;
        questions.method(classFqn.get(), methodName);
        findings.add(new Finding.MethodName(leaf.valueNode(), classFqn.get(), methodName));
    }

    /**
     * Collects {@code @scalarType(scalar: "fully.qualified.Class.FIELD")}. The LSP has the
     * compile-classpath scan but not a live classloader, so it cannot run the resolver's full
     * reflection path; it surfaces the two checks the catalog can answer:
     *
     * <ul>
     *   <li>Shape: the value must split at the last dot into a class FQN + field name. A value
     *       with no dot cannot be resolved at codegen and is flagged here.</li>
     *   <li>Classpath: the class part must be one the census holds. Skipped when the census holds
     *       nothing (pre-compile state); the build-tier resolver produces the precise rejection
     *       arm then.</li>
     * </ul>
     *
     * <p>The malformed-value diagnostic is the one thing here that needs no census, which is why it is
     * settled in the walk: a value with no dot cannot be resolved at codegen whatever the classpath
     * holds, and saying so before the first build costs nothing.
     *
     * <p>Field-level validation ({@code FieldNotFound}, {@code NotAScalarType},
     * {@code CoercingErased}) requires reflection on the actual class and lives in the
     * build-tier {@link no.sikt.graphitron.rewrite.ScalarTypeResolver}; the LSP surfaces those
     * errors via the build pipeline's diagnostics, not inline.
     */
    private static void collectScalarType(
        Node valueNode, FileSnapshot file, List<Finding> findings,
        DiagnosticFacts.Questions questions, Settled out
    ) {
        String fqn = value(valueNode, file);
        if (fqn.isEmpty()) return;
        switch (ScalarTypeResolver.parseDirectiveValue(fqn)) {
            case ScalarTypeResolver.ParsedDirectiveValue.Malformed m ->
                out.add(diagnostic(file, valueNode,
                    "Invalid scalar reference '" + m.value() + "'. Expected a fully-qualified "
                    + "field reference of the form 'fully.qualified.Class.FIELD' pointing at a "
                    + "public static final GraphQLScalarType."));
            case ScalarTypeResolver.ParsedDirectiveValue.Parsed p -> {
                questions.className(p.classFqn());
                findings.add(new Finding.ScalarClassName(valueNode, p.classFqn()));
            }
        }
    }

    /**
     * Collects an {@code argMapping} string. The parse, the sibling method the entries are checked
     * against and the enclosing field's arguments are all the tree's, so all of them happen here; only
     * the parameter names are the census's, so the entries are judged rather than validated.
     */
    private static void collectArgMapping(
        LspVocabulary vocabulary, Directives.Directive directive, LspVocabulary.Leaf leaf,
        FileSnapshot file, List<Finding> findings, DiagnosticFacts.Questions questions
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

        var target = ArgMappingSupport.siblingMethodTarget(
            vocabulary, directive, valueNode, leaf.coord(), source);
        String classFqn = target.map(ArgMappingSupport.MethodTarget::className).orElse(null);
        String methodName = target.map(ArgMappingSupport.MethodTarget::methodName).orElse(null);
        if (classFqn != null) {
            questions.method(classFqn, methodName);
        }
        List<String> fieldArgs = TypeContext.enclosingFieldDefinition(directive.outer())
            .map(fd -> TypeContext.fieldArgumentNames(fd, source))
            .orElse(List.of());
        findings.add(new Finding.ArgMappingValue(
            valueNode, contentStart, entries, classFqn, methodName, fieldArgs));
    }

    /**
     * One finding's verdict, over answers the store has already given. Every arm that stays silent does
     * so for a reason the census told it, and the reasons are one rule: a name a populated census does
     * not hold will not resolve at codegen either, and a census holding nothing is a consumer who has
     * not built or compiled yet, whose schema is not full of wrong names.
     */
    private static void judge(
        Finding finding, DiagnosticFacts.Answers answers, FileSnapshot file, List<Diagnostic> out
    ) {
        switch (finding) {
            case Finding.Ready(var diagnostic) -> out.add(diagnostic);
            case Finding.TableName(var node, var spelling) -> {
                if (answers.tableName(spelling) == DiagnosticFacts.Resolution.UNKNOWN) {
                    out.add(diagnostic(file, node, "Unknown table '" + spelling
                        + "'. The jOOQ catalog does not contain a table with this name."));
                }
            }
            case Finding.ForeignKeyName(var node, var spelling) -> {
                if (answers.foreignKeyName(spelling) == DiagnosticFacts.Resolution.UNKNOWN) {
                    out.add(diagnostic(file, node, "Unknown foreign key '" + spelling
                        + "'. Not present in the jOOQ catalog."));
                }
            }
            case Finding.ClassName(var node, var fqn) -> {
                if (answers.className(fqn) == DiagnosticFacts.Resolution.UNKNOWN) {
                    out.add(diagnostic(file, node, "Unknown class '" + fqn
                        + "'. Not found on the compile classpath."));
                }
            }
            case Finding.ScalarClassName(var node, var fqn) -> {
                if (answers.className(fqn) == DiagnosticFacts.Resolution.UNKNOWN) {
                    out.add(diagnostic(file, node, "Unknown class '" + fqn
                        + "' on @scalarType. Not found on the compile classpath."));
                }
            }
            case Finding.NodeTypeName(var node, var typeName) ->
                judgeNodeTypeName(answers, file, node, typeName, out);
            case Finding.MethodName(var node, var classFqn, var methodName) ->
                judgeMethodName(answers, file, node, classFqn, methodName, out);
            case Finding.MemberName(var node, var typeName, var fieldName, var memberName) ->
                judgeMemberName(answers, file, node, typeName, fieldName, memberName, out);
            case Finding.ArgMappingValue value -> judgeArgMapping(answers, file, value, out);
        }
    }

    /**
     * Validates {@code @nodeId(typeName: "X")}: the named type must exist in the catalog and must carry
     * {@code @node}. Mirrors the two classifier rejections that
     * {@link no.sikt.graphitron.rewrite.FieldBuilder} produces for the same coordinate:
     * {@code Rejection.unknownTypeName} when no such type exists, {@code Rejection.structural} when the
     * type exists without {@code @node}.
     *
     * <p>Graph-keyed, so the scope is the relation's own {@code graph_name} rather than a membership
     * join, as the completion arm on the same coordinate has it: a {@code @node} declaration is a fact
     * about one graph's SDL however much of a store its module shares.
     *
     * <p>The empty-population guard the projection needed retired with it. A graph declaring no
     * {@code @node} type was indistinguishable from a projection nobody had built yet, so the arm
     * deferred on both; a store answers only after a capture, and a capture writes every {@code @node}
     * in the graph, so no rows means the schema declares none and the build will reject the reference.
     * The deferral that remains is the one this arm cannot see past: no store answered at all, which is
     * a session before its first build.
     */
    private static void judgeNodeTypeName(
        DiagnosticFacts.Answers answers, FileSnapshot file, Node node, String typeName,
        List<Diagnostic> out
    ) {
        if (answers.nodeTypeName(typeName) != DiagnosticFacts.Resolution.UNKNOWN) return;
        out.add(diagnostic(file, node,
            "Unknown @node type '" + typeName + "' on @nodeId(typeName:). The type must be "
            + "declared in the schema and carry the @node directive."));
    }

    private static void judgeMethodName(
        DiagnosticFacts.Answers answers, FileSnapshot file, Node node, String classFqn,
        String methodName, List<Diagnostic> out
    ) {
        // Sibling className unresolved, or a census with nothing in it: the className arm has the
        // first case and defers on the second, so this arm has nothing to add to either.
        if (answers.className(classFqn) != DiagnosticFacts.Resolution.RESOLVES) return;
        var overloads = answers.overloads(classFqn, methodName);
        if (overloads.isEmpty()) {
            out.add(diagnostic(file, node,
                "Unknown method '" + methodName + "' on class '" + classFqn + "'."));
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
        if (overloads.stream().allMatch(DiagnosticFacts.Overload::isNameless)) {
            out.add(diagnostic(file, node, DiagnosticSeverity.Warning,
                "Class '" + classFqn + "' was compiled without `-parameters`; "
                + "parameter help on '" + methodName + "' is unavailable. "
                + "Set `<parameters>true</parameters>` on maven-compiler-plugin "
                + "to surface parameter names."));
        }
    }

    /**
     * The member name must be one the site's scope offers, and the word the message uses for it is the
     * scope's own. A scope the store resolves nothing for, or resolves to a population the census holds
     * nothing in, is silence rather than a verdict: it is not a table with no columns or a class with no
     * members but a model nobody has generated yet, which is no grounds for calling a name unknown.
     *
     * <p>Which scope answers is {@link TypeMemberScope}'s rule, applied inside the read. What is left
     * here is the sentence, and it differs by arm because the author's mistake does: a column name and
     * a member name are wrong about different things.
     */
    private static void judgeMemberName(
        DiagnosticFacts.Answers answers, FileSnapshot file, Node node, String typeName,
        String fieldName, String memberName, List<Diagnostic> out
    ) {
        var scope = answers.memberScope(typeName, fieldName).orElse(null);
        if (scope == null || scope.offers(memberName)) return;
        out.add(diagnostic(file, node, DiagnosticSeverity.Error, switch (scope) {
            case DiagnosticFacts.MemberScope.Columns columns ->
                "Unknown column '" + memberName + "' on table '" + columns.tableName() + "'.";
            case DiagnosticFacts.MemberScope.Slots slots ->
                "Unknown " + memberWord(slots.origin()) + " '" + memberName
                    + "' on backing class '" + slots.className() + "'.";
        }));
    }

    /** What a message calls a member, which the relation decides by the class's declared form. */
    private static String memberWord(ClassMemberSlots.Origin origin) {
        return switch (origin) {
            case RECORD_COMPONENT -> "component";
            case BEAN_ACCESSOR -> "property";
        };
    }

    /**
     * Judges an {@code argMapping} string ({@code "javaParam: graphqlArg, ..."}). Three of the four
     * checks need no census and are here anyway, because the four interleave: an entry's structure, its
     * Java parameter and its GraphQL argument are reported in that order per entry, so an author reading
     * the squiggles reads them in the order they wrote them.
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
    private static void judgeArgMapping(
        DiagnosticFacts.Answers answers, FileSnapshot file, Finding.ArgMappingValue value,
        List<Diagnostic> out
    ) {
        Node valueNode = value.node();
        int contentStart = value.contentStart();
        Set<String> paramNames = parameterNames(answers, value.classFqn(), value.methodName());
        List<String> fieldArgs = value.fieldArgs();

        var seenJava = new LinkedHashSet<String>();
        for (var entry : value.entries()) {
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
                judgeArgMappingJavaParam(entry.java(), contentStart, paramNames, seenJava, file, out);
            }
            if (entry.graphql().isEmpty()) {
                out.add(byteDiagnostic(file, contentStart + entry.rawStart(), contentStart + entry.rawEnd(),
                    DiagnosticSeverity.Warning, "Missing GraphQL argument after ':' in argMapping."));
            } else {
                judgeArgMappingGraphqlArg(entry.graphql(), contentStart, fieldArgs, file, out);
            }
        }
    }

    private static void judgeArgMappingJavaParam(
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

    private static void judgeArgMappingGraphqlArg(
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
     * the unknown-parameter check must be suppressed: siblings that name no method, a method that does
     * not resolve, or parameter names unavailable (compiled without {@code -parameters}). An empty set
     * means the method resolves with zero named parameters, so any mapping entry is unknown. A session
     * with no store lands on the second of those, the census answering no overload for any name, so the
     * suppression needs no case of its own for it.
     *
     * <p>Across overloads rather than on one of them. SDL names a method by name alone, so which
     * overload codegen binds is not something this arm can know, and a name that is a parameter of
     * some overload is one the author may correctly have written; the union is the set that cannot
     * produce a false positive. The projection answered from whichever overload it held first, which
     * was the same guess made silently.
     */
    private static Set<String> parameterNames(
        DiagnosticFacts.Answers answers, String classFqn, String methodName
    ) {
        if (classFqn == null) return null;
        var overloads = answers.overloads(classFqn, methodName);
        if (overloads.isEmpty()) return null;
        if (overloads.stream().anyMatch(DiagnosticFacts.Overload::hasUnnamedParameters)) return null;
        var names = new LinkedHashSet<String>();
        for (var overload : overloads) {
            names.addAll(overload.parameterNames());
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
