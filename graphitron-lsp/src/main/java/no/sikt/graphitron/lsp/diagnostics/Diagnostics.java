package no.sikt.graphitron.lsp.diagnostics;

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
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.lsp.facts.ClassMemberSlots;
import no.sikt.graphitron.lsp.facts.TypeMemberScope;
import no.sikt.graphitron.lsp.trace.LspTrace;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.grammar.ConstantReferenceGrammar;
import no.sikt.graphitron.model.grammar.FieldSourceSigilGrammar;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import io.github.treesitter.jtreesitter.Node;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

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
 * <p>The pass runs in three stages, and the split is what makes a whole recalculation one statement. The
 * walk reads nothing: it settles the checks the tree alone answers and records a {@link Finding} for
 * every check a census must answer, putting the value it needs resolved into a
 * {@link DiagnosticFacts.Questions}. {@link DiagnosticFacts} then answers all of it at once. Then each
 * finding is judged in the order the walk found it, so an editor sees what it always saw.
 *
 * <p>This is the shape a declaration hover has at a coordinate. The reason it transfers is that the
 * questions are independent: a table name, a foreign key, a class, a method, a {@code @node} reference
 * and a member name are resolved by relations sharing no key, so no answer decides what to ask next, and
 * the walk can therefore collect all of them before any of them is resolved.
 *
 * <p>The build's own findings arrive the same way. What the last build said about a document is a row
 * set keyed on the file, read in the same statement as everything else and replayed after the
 * document's own verdicts. Nothing gates it on freshness: a buffer the author has since edited shows
 * what the schema said when it was last captured, which is this class's posture everywhere.
 *
 * <p>Three units meet here and only one of them owns the statement. A <em>file</em> is what an editor is
 * told about, {@code publishDiagnostics} being per-URI. A <em>graph</em> is what the facts are keyed on.
 * A <em>recalculation</em> is the unit of work, being what a capture triggers, and it is where the read
 * belongs; {@link Batch} is that unit, and a single document is the batch of one.
 * {@code DiagnosticsStatementCountTest} holds the numbers: one statement per graph per drain, and inside
 * one document a count that does not grow with it, where a ten-field type used to cost thirty-one.
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

        /**
         * A directive an author applied, against the definitions the graph captured. One finding for
         * two verdicts, both of which are about the definition rather than about any value: whether
         * the name is declared at all, and which of the arguments it requires went unwritten.
         */
        record DirectiveApplication(Range range, String directiveName, List<String> writtenArgs)
            implements Finding {}

        /**
         * One argument name an author wrote, with the names enclosing it. A single element is a
         * directive argument; a longer path descends an object literal, each name resolving against
         * the input type the one before it named. The path is what a finding can carry across the
         * stage boundary where the tree it was read from cannot.
         */
        record ArgumentPath(Range range, String directiveName, List<String> path)
            implements Finding {}

        /** A {@code @table(name:)} value, against the table census. */
        record TableName(Range range, String spelling) implements Finding {}

        /** A {@code @reference(key:)} value, against the key census. */
        record ForeignKeyName(Range range, String spelling) implements Finding {}

        /** A class FQN a directive binds, against the classpath census. */
        record ClassName(Range range, String fqn) implements Finding {}

        /**
         * The class half of a {@code @scalarType} reference. Its own arm because the message names the
         * directive: the value is a field reference rather than a class name, and an author reading
         * "unknown class" about something they wrote as a dotted path needs to know which half is wrong.
         */
        record ScalarClassName(Range range, String fqn) implements Finding {}

        /** A method name, against the overloads its sibling class declares. */
        record MethodName(Range range, String classFqn, String methodName) implements Finding {}

        /** A {@code @nodeId(typeName:)} value, against the graph's {@code @node} declarations. */
        record NodeTypeName(Range range, String typeName) implements Finding {}

        /**
         * A member name written at a site, against whatever that site's scope resolves against. The
         * field name may be absent, the type's own scope answering for a name written outside any field.
         */
        record MemberName(Range range, String typeName, String fieldName, String memberName)
            implements Finding {}

        /**
         * A written {@code $source}, against the coordinates a mutation payload's data arrives at.
         * Its own arm rather than a member name because the sigil resolves against nothing a scope
         * offers: what admits it is the site being a carrier's data channel, which is a fact about
         * the enclosing type's role rather than about any table or class.
         */
        record SourceSigil(Range range, String typeName, String fieldName) implements Finding {}

        /**
         * A parsed {@code argMapping} string, judged whole. One finding for the entire value rather
         * than one per entry because the checks interleave: an entry's structure and its Java
         * parameter are reported in that order, and only the second needs the census.
         *
         * @param classFqn the sibling-named class, or absent where the siblings name no method; the
         *                 unknown-parameter check is suppressed then, having nothing to check against
         */
        record ArgMappingValue(
            Range range, int contentStart, List<ArgMapping.Entry> entries, String classFqn,
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

    public static List<Diagnostic> compute(String uri, FileSnapshot file) {
        return compute(LspVocabulary.load(), uri, file);
    }

    /**
     * The store-free form: every arm that resolves a value against the catalog or the classpath
     * answers as if the census were unavailable, which is silence, and the build's own verdict on the
     * document is unreadable for the same reason. That is what a session before its first build sees.
     */
    public static List<Diagnostic> compute(LspVocabulary vocabulary, String uri, FileSnapshot file) {
        return compute(vocabulary, uri, file, Optional.empty());
    }

    public static List<Diagnostic> compute(
        LspVocabulary vocabulary, String uri, FileSnapshot file, Optional<StoreHandle> store
    ) {
        try (var span = LspTrace.span("diagnostics.compute")) {
            span.detail("uri", uri);
            var batch = new Batch(vocabulary);
            batch.add(uri, file, span);
            var result = batch.judgeAll(ignored -> store).getOrDefault(uri, List.of());
            span.detail("diagnostics", result.size());
            return result;
        }
    }

    /**
     * A set of documents diagnosed together, which is what a recalculation actually is. The three stages
     * hoisted one level: every document is walked, then the whole set's questions are resolved, then each
     * document is judged and published on its own.
     *
     * <p>The unit of work is the drain rather than the file. A file is only the unit an editor is told
     * about, {@code publishDiagnostics} being per-URI, and the facts are the graph's, so a per-document
     * read sat at neither of the two grains that exist: a drain of forty files issued forty
     * near-identical statements about one graph, each keyed on the coordinates that one file happened to
     * mention. Collecting first and resolving once is the same correction this class already applied
     * inside one document, applied to the set.
     *
     * <p>Grouped by graph, because the questions are keyed on one. A drain can span graphs, a session's
     * files not all belonging to the same capture, so the statement count is one per graph the drain
     * touched rather than a flat one; for the ordinary single-project session that is one.
     *
     * <p>Not thread-safe, and not meant to be: a batch is built and consumed inside one drain.
     */
    public static final class Batch {

        private final LspVocabulary vocabulary;
        private final List<Document> documents = new ArrayList<>();

        public Batch(LspVocabulary vocabulary) {
            this.vocabulary = vocabulary;
        }

        /** Walks one document into the batch, reading nothing. */
        public void add(String uri, FileSnapshot file) {
            try (var span = LspTrace.span("diagnostics.walk")) {
                span.detail("uri", uri);
                add(uri, file, span);
            }
        }

        private void add(String uri, FileSnapshot file, LspTrace.Span span) {
            var findings = new ArrayList<Finding>();
            var questions = new DiagnosticFacts.Questions();
            walk(vocabulary, file, findings, questions, span);
            questions.replayFile(uri);
            // The replay's own tree-side input, read here because here is where the tree is alive:
            // where each documented definition's description block sits, and what name it documents.
            var described = new ArrayList<DescribedDefinition>();
            collectDescribedDefinitions(file, file.tree().getRootNode(), described);
            documents.add(new Document(uri, file.source(), findings, questions, described));
        }

        /**
         * Resolves every document's questions, one statement per graph, and judges each document against
         * the answers for its own graph. Keyed by URI, carrying an entry for every document added.
         *
         * <p>{@code handleForUri} is a lookup into one read transaction and is called once per document
         * before anything is read, so the grouping and the reads it drives all sit inside that
         * transaction and no two documents can be judged from two sides of a capture.
         */
        public Map<String, List<Diagnostic>> judgeAll(
            Function<String, Optional<StoreHandle>> handleForUri
        ) {
            var handleByGraph = new LinkedHashMap<String, StoreHandle>();
            var questionsByGraph = new LinkedHashMap<String, DiagnosticFacts.Questions>();
            var graphByUri = new LinkedHashMap<String, String>();
            for (var document : documents) {
                var handle = handleForUri.apply(document.uri()).orElse(null);
                if (handle == null) continue;
                String graph = handle.graphName();
                graphByUri.put(document.uri(), graph);
                handleByGraph.putIfAbsent(graph, handle);
                questionsByGraph
                    .computeIfAbsent(graph, ignored -> new DiagnosticFacts.Questions())
                    .addAll(document.questions());
            }
            var answersByGraph = new LinkedHashMap<String, DiagnosticFacts.Answers>();
            for (var entry : questionsByGraph.entrySet()) {
                answersByGraph.put(entry.getKey(),
                    DiagnosticFacts.of(handleByGraph.get(entry.getKey()), entry.getValue()));
            }
            var out = new LinkedHashMap<String, List<Diagnostic>>();
            for (var document : documents) {
                var answers = answersByGraph.getOrDefault(
                    graphByUri.get(document.uri()), DiagnosticFacts.none());
                out.put(document.uri(), judged(document, answers));
            }
            return out;
        }

        private List<Diagnostic> judged(Document document, DiagnosticFacts.Answers answers) {
            var out = new ArrayList<Diagnostic>(document.findings().size());
            for (var finding : document.findings()) {
                judge(finding, answers, document.source(), out);
            }
            // Appended after the document's own verdicts, which is where the build's were emitted
            // before: what the walk found is about the buffer as it stands, and what the build found
            // is about the last capture of it.
            replay(document, answers, out);
            return out;
        }

        /**
         * One walked document: what it found, and what it still has to ask.
         *
         * <p>Its source bytes rather than its {@link FileSnapshot}. A snapshot's tree is a native
         * resource whose lifetime is the file lock the walk ran under, so a finding that carried a
         * tree-sitter node would be reading freed memory by the time the store answered. Every finding
         * therefore carries the span it will squiggle rather than the node it came from, and the only
         * thing crossing the stage boundary is plain data. The bytes are needed because an
         * {@code argMapping} entry's span is an offset into the string's content and is turned into a
         * position at judgement.
         */
        private record Document(
            String uri, byte[] source, List<Finding> findings, DiagnosticFacts.Questions questions,
            List<DescribedDefinition> describedDefinitions
        ) {}
    }

    /**
     * The document walk, which reads nothing. Every check the tree alone settles becomes a
     * {@link Finding.Ready}; every check a census must answer becomes a finding naming what it needs,
     * and the value it needs resolved goes into {@code questions}. Findings accumulate in document
     * order and are judged in that order, so splitting the pass does not reorder what an editor shows.
     */
    private static void walk(
        LspVocabulary vocabulary, FileSnapshot file,
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
            questions.directive(directiveName);
            collectArgumentPaths(directive, directiveName, file, findings, questions);
            findings.add(new Finding.DirectiveApplication(rangeOf(file, directive.nameNode()),
                directiveName, writtenArgumentNames(directive, file)));
            // Every leaf whose value carries graphitron's own semantics, which is what the bundled
            // vocabulary declares: an author's own directive has arguments and no behaviour, so
            // leafCoordinates answers with nothing for it and no value arm collects.
            for (var leaf : vocabulary.leafCoordinates(directive, file.source())) {
                collect(directive, leaf, vocabulary, file, findings, questions, out);
            }
        }
    }

    /** The top-level argument names an author wrote, which is what a required argument is missing from. */
    private static List<String> writtenArgumentNames(
        Directives.Directive directive, FileSnapshot file
    ) {
        var names = new ArrayList<String>(directive.arguments().size());
        for (var arg : directive.arguments()) {
            names.add(Nodes.text(arg.key(), file.source()));
        }
        return names;
    }

    /**
     * Every argument name written on one directive, each with the path of names that reaches it. A
     * top-level argument is a path of one; a name inside an object literal extends its enclosing
     * path, so the judgement can resolve it a level at a time without holding the tree it was read
     * from.
     */
    private static void collectArgumentPaths(
        Directives.Directive directive, String directiveName, FileSnapshot file,
        List<Finding> findings, DiagnosticFacts.Questions questions
    ) {
        for (var arg : directive.arguments()) {
            var path = List.of(Nodes.text(arg.key(), file.source()));
            findings.add(new Finding.ArgumentPath(rangeOf(file, arg.key()), directiveName, path));
            collectNestedArgumentPaths(arg.value(), path, directiveName, file, findings, questions);
        }
    }

    private static void collectNestedArgumentPaths(
        Node node, List<String> enclosing, String directiveName, FileSnapshot file,
        List<Finding> findings, DiagnosticFacts.Questions questions
    ) {
        if (node == null) return;
        if (OBJECT_FIELD.matches(node)) {
            Node nameNode = Nodes.childOfKind(node, NAME);
            Node valueNode = Nodes.childOfKind(node, VALUE);
            if (nameNode == null || valueNode == null) return;
            String fieldName = Nodes.text(nameNode, file.source());
            var path = new ArrayList<>(enclosing);
            path.add(fieldName);
            questions.nestedArgField(fieldName);
            findings.add(new Finding.ArgumentPath(
                rangeOf(file, nameNode), directiveName, List.copyOf(path)));
            collectNestedArgumentPaths(
                valueNode, path, directiveName, file, findings, questions);
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectNestedArgumentPaths(
                node.getChild(i).orElse(null), enclosing, directiveName, file, findings, questions);
        }
    }

    /**
     * A documented definition, as the replay needs it after the tree it was read from is gone: the
     * span its documentation block occupies, and the range of the name that block documents.
     *
     * <p>It exists because graphql-java anchors a <em>described</em> definition's location at the
     * opening delimiter of its doc block rather than at the name, the description being the AST
     * node's first token, so a build finding on a documented definition would underline the prose
     * instead of the declaration an author has to fix. The incumbent resolved that by walking up from
     * the location's node to the enclosing description; the walk collects every description instead,
     * and the judgement asks which one covers a location. Same answer, and it holds no node.
     */
    private record DescribedDefinition(Position start, Position end, Range nameRange) {

        boolean covers(Position position) {
            return !before(position, start) && before(position, end);
        }

        private static boolean before(Position a, Position b) {
            return a.getLine() != b.getLine()
                ? a.getLine() < b.getLine()
                : a.getCharacter() < b.getCharacter();
        }
    }

    /**
     * Every documented definition in the document, in the order the tree holds them. The identifying
     * child of a description's parent is a {@code name} for every definition kind except
     * {@code enum_value_definition}, which carries an {@code enum_value}; a description whose parent
     * offers neither is skipped, having no name to re-anchor to.
     */
    private static void collectDescribedDefinitions(
        FileSnapshot file, Node node, List<DescribedDefinition> out
    ) {
        if (node == null) return;
        if (DESCRIPTION.matches(node)) {
            Node def = node.getParent().orElse(null);
            Node name = def == null ? null : Nodes.childOfKind(def, NAME);
            if (name == null && def != null) {
                name = Nodes.childOfKind(def, ENUM_VALUE);
            }
            if (name != null) {
                out.add(new DescribedDefinition(
                    Positions.toLspPosition(file.source(), node.getStartByte()),
                    Positions.toLspPosition(file.source(), node.getEndByte()),
                    rangeOf(file, name)));
            }
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectDescribedDefinitions(file, node.getChild(i).orElse(null), out);
        }
    }

    /**
     * The last build's own findings about this document, placed in the buffer as it stands. Nothing
     * is judged here: the verdict is the build's, and what the row set holds is what a capture
     * recorded, so a buffer the author has since edited shows what the schema said when it was last
     * read rather than nothing at all.
     */
    private static void replay(
        Batch.Document document, DiagnosticFacts.Answers answers, List<Diagnostic> out
    ) {
        for (var row : answers.replayFor(document.uri())) {
            var d = new Diagnostic(replayRange(document, row), row.message());
            d.setSeverity(severityOf(row.severity()));
            d.setSource(VALIDATOR_SOURCE);
            if (row.lspCode() != null) {
                d.setCode(row.lspCode());
            }
            out.add(d);
        }
    }

    /**
     * Where a replayed finding squiggles: the name of the definition it landed inside the
     * documentation of, or the column-to-end-of-line range straight from the stored location. The end
     * column is {@link Integer#MAX_VALUE} (the gcc convention, clamped by the client to the line's
     * end), a zero-width range at column 1 being too subtle to find in an editor.
     */
    private static Range replayRange(Batch.Document document, DiagnosticFacts.ReplayRow row) {
        int line = row.sourceLine() - 1;
        int column = row.sourceColumn() == null ? 0 : Math.max(0, row.sourceColumn() - 1);
        var start = new Position(line, column);
        for (var described : document.describedDefinitions()) {
            if (described.covers(start)) {
                return described.nameRange();
            }
        }
        return new Range(start, new Position(line, Integer.MAX_VALUE));
    }

    /**
     * The view's severity, which is a closed pair. Every rejection arm is an error there, including
     * the deferred one, on the build's own finality: what an author can act on is the message, not a
     * softer colour. Anything that is not the error spelling is a warning, which is what the lint and
     * advisory arms are.
     */
    private static DiagnosticSeverity severityOf(String severity) {
        return "error".equals(severity) ? DiagnosticSeverity.Error : DiagnosticSeverity.Warning;
    }

    /**
     * The per-leaf collection. Each arm records what it will need to know instead of looking it up, so
     * the walk over a document reads nothing at all. What an arm can settle without a census it still
     * settles here: a scalar reference with no dot in it will not resolve whatever the classpath holds,
     * and saying so before the first build costs nothing.
     */
    private static void collect(
        Directives.Directive directive, LspVocabulary.Leaf leaf, LspVocabulary vocabulary,
        FileSnapshot file, List<Finding> findings,
        DiagnosticFacts.Questions questions, Settled out
    ) {
        var behavior = vocabulary.behaviorAt(leaf.coord()).orElse(null);
        if (behavior == null) return;
        switch (behavior) {
            case Behavior.CatalogTableBinding ignored -> {
                String spelling = value(leaf.valueNode(), file);
                if (spelling.isEmpty()) return;
                questions.tableName(spelling);
                findings.add(new Finding.TableName(rangeOf(file, leaf.valueNode()), spelling));
            }
            case Behavior.CatalogColumnBinding ignored ->
                collectMemberName(directive, leaf.valueNode(), file, findings, questions);
            case Behavior.CatalogFkBinding ignored -> {
                String spelling = value(leaf.valueNode(), file);
                if (spelling.isEmpty()) return;
                questions.foreignKeyName(spelling);
                findings.add(new Finding.ForeignKeyName(rangeOf(file, leaf.valueNode()), spelling));
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
                findings.add(new Finding.ClassName(rangeOf(file, leaf.valueNode()), fqn));
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
                findings.add(new Finding.NodeTypeName(rangeOf(file, leaf.valueNode()), typeName));
            }
        }
    }

    /** An author-written value with its quotes off, which is what every census is asked about. */
    private static String value(Node valueNode, FileSnapshot file) {
        return Nodes.unquote(Nodes.text(valueNode, file.source()));
    }

    /**
     * Collects a {@code @field(name:)} (or other {@code CatalogColumnBinding}) coordinate, as one of
     * two findings: a written {@code $source} asks where a payload's data arrives, and anything else
     * asks what the site's scope offers. The two are different questions rather than two readings of
     * one, which is why the sigil takes its own arm and returns before the member arm collects.
     */
    private static void collectMemberName(
        Directives.Directive directive, Node valueNode, FileSnapshot file,
        List<Finding> findings, DiagnosticFacts.Questions questions
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
        if (FieldSourceSigilGrammar.isUpstreamRoot(memberName)) {
            questions.sigilSite(typeName.get(), fieldName);
            findings.add(new Finding.SourceSigil(rangeOf(file, valueNode), typeName.get(), fieldName));
            return;
        }
        questions.memberSite(typeName.get(), fieldName);
        findings.add(new Finding.MemberName(rangeOf(file, valueNode), typeName.get(), fieldName, memberName));
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
        findings.add(new Finding.MethodName(rangeOf(file, leaf.valueNode()), classFqn.get(), methodName));
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
     * build-tier resolver; the LSP surfaces those errors via the build pipeline's diagnostics,
     * not inline.
     */
    private static void collectScalarType(
        Node valueNode, FileSnapshot file, List<Finding> findings,
        DiagnosticFacts.Questions questions, Settled out
    ) {
        String fqn = value(valueNode, file);
        if (fqn.isEmpty()) return;
        switch (ConstantReferenceGrammar.split(fqn)) {
            case ConstantReferenceGrammar.Reference.Malformed m ->
                out.add(diagnostic(file, valueNode,
                    "Invalid scalar reference '" + m.value() + "'. Expected a fully-qualified "
                    + "field reference of the form 'fully.qualified.Class.FIELD' pointing at a "
                    + "public static final GraphQLScalarType."));
            case ConstantReferenceGrammar.Reference.Parsed p -> {
                questions.className(p.classFqn());
                findings.add(new Finding.ScalarClassName(rangeOf(file, valueNode), p.classFqn()));
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
            rangeOf(file, valueNode), contentStart, entries, classFqn, methodName, fieldArgs));
    }

    /**
     * One finding's verdict, over answers the store has already given. Every arm that stays silent does
     * so for a reason the census told it, and the reasons are one rule: a name a populated census does
     * not hold will not resolve at codegen either, and a census holding nothing is a consumer who has
     * not built or compiled yet, whose schema is not full of wrong names.
     */
    private static void judge(
        Finding finding, DiagnosticFacts.Answers answers, byte[] source, List<Diagnostic> out
    ) {
        switch (finding) {
            case Finding.Ready(var diagnostic) -> out.add(diagnostic);
            case Finding.DirectiveApplication(var range, var name, var writtenArgs) ->
                judgeDirectiveApplication(answers, range, name, writtenArgs, out);
            case Finding.ArgumentPath(var range, var directiveName, var path) ->
                judgeArgumentPath(answers, range, directiveName, path, out);
            case Finding.TableName(var range, var spelling) -> {
                if (answers.tableName(spelling) == DiagnosticFacts.Resolution.UNKNOWN) {
                    out.add(diagnostic(range, "Unknown table '" + spelling
                        + "'. The jOOQ catalog does not contain a table with this name."));
                }
            }
            case Finding.ForeignKeyName(var range, var spelling) -> {
                if (answers.foreignKeyName(spelling) == DiagnosticFacts.Resolution.UNKNOWN) {
                    out.add(diagnostic(range, "Unknown foreign key '" + spelling
                        + "'. Not present in the jOOQ catalog."));
                }
            }
            case Finding.ClassName(var range, var fqn) -> {
                if (answers.className(fqn) == DiagnosticFacts.Resolution.UNKNOWN) {
                    out.add(diagnostic(range, "Unknown class '" + fqn
                        + "'. Not found on the compile classpath."));
                }
            }
            case Finding.ScalarClassName(var range, var fqn) -> {
                if (answers.className(fqn) == DiagnosticFacts.Resolution.UNKNOWN) {
                    out.add(diagnostic(range, "Unknown class '" + fqn
                        + "' on @scalarType. Not found on the compile classpath."));
                }
            }
            case Finding.NodeTypeName(var range, var typeName) ->
                judgeNodeTypeName(answers, range, typeName, out);
            case Finding.MethodName(var range, var classFqn, var methodName) ->
                judgeMethodName(answers, range, classFqn, methodName, out);
            case Finding.MemberName(var range, var typeName, var fieldName, var memberName) ->
                judgeMemberName(answers, range, typeName, fieldName, memberName, out);
            case Finding.SourceSigil(var range, var typeName, var fieldName) -> {
                if (answers.sourceSigilSite(typeName, fieldName) == DiagnosticFacts.Resolution.UNKNOWN) {
                    out.add(diagnostic(range, DiagnosticSeverity.Error,
                        FieldSourceSigilGrammar.notDefinedHereMessage()));
                }
            }
            case Finding.ArgMappingValue value -> judgeArgMapping(answers, source, value, out);
        }
    }

    /**
     * Judges one directive application against the definition the graph captured. Bundled and
     * user-authored directives are one population here, capture parsing graphitron's own
     * {@code directives.graphqls} like any other schema file, so the two validators this replaced are
     * one and an author's own directive gets the checks graphitron's have always had.
     *
     * <p>The freshness gate the incumbent applied is gone with the projection that carried it: a graph
     * whose SDL has never been captured holds no directive definitions and is silent, and one that has
     * reports against what it captured. That is the same posture every other arm here takes, and it is
     * what lets a stale buffer show the verdicts of its last captured content rather than nothing.
     */
    private static void judgeDirectiveApplication(
        DiagnosticFacts.Answers answers, Range range, String directiveName,
        List<String> writtenArgs, List<Diagnostic> out
    ) {
        switch (answers.directiveName(directiveName)) {
            case NO_CENSUS -> { /* nothing captured: no definition to judge against */ }
            case UNKNOWN -> out.add(diagnostic(range, DiagnosticSeverity.Warning,
                "Unknown directive '@" + directiveName
                    + "'. Not declared in any directive definition reachable from the parsed schema."));
            case RESOLVES -> {
                for (String required : answers.requiredArgumentsOf(directiveName)) {
                    if (writtenArgs.contains(required)) continue;
                    out.add(diagnostic(range, DiagnosticSeverity.Warning,
                        "Missing required argument '" + required + "' on @" + directiveName + "."));
                }
            }
        }
    }

    /**
     * Judges one written argument name by walking its path down from the directive's definition. Each
     * step resolves a name against the type the step before it named, and the descent stops the moment
     * a step reaches something a literal cannot descend into, which is what keeps an object written
     * where a scalar belongs from being judged against fields no type declares.
     *
     * <p>Only a path's last name is reported. A name that fails to resolve is the last name of its own
     * path, so the deeper paths running through it stop silently rather than reporting it again.
     */
    private static void judgeArgumentPath(
        DiagnosticFacts.Answers answers, Range range, String directiveName, List<String> path,
        List<Diagnostic> out
    ) {
        if (answers.directiveName(directiveName) != DiagnosticFacts.Resolution.RESOLVES) return;
        var step = answers.argument(directiveName, path.getFirst());
        if (step.isEmpty()) {
            if (path.size() == 1) {
                out.add(diagnostic(range, DiagnosticSeverity.Warning,
                    "Unknown argument '" + path.getFirst() + "' on @" + directiveName + "."));
            }
            return;
        }
        for (int depth = 1; depth < path.size(); depth++) {
            if (!step.get().descends()) return;
            String enclosingType = step.get().typeName();
            String name = path.get(depth);
            var next = answers.inputField(enclosingType, name);
            if (next.isEmpty()) {
                if (depth == path.size() - 1) {
                    out.add(diagnostic(range, DiagnosticSeverity.Warning,
                        "Unknown field '" + name + "' on input type '" + enclosingType + "'."));
                }
                return;
            }
            step = next;
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
        DiagnosticFacts.Answers answers, Range range, String typeName, List<Diagnostic> out
    ) {
        if (answers.nodeTypeName(typeName) != DiagnosticFacts.Resolution.UNKNOWN) return;
        out.add(diagnostic(range,
            "Unknown @node type '" + typeName + "' on @nodeId(typeName:). The type must be "
            + "declared in the schema and carry the @node directive."));
    }

    private static void judgeMethodName(
        DiagnosticFacts.Answers answers, Range range, String classFqn, String methodName,
        List<Diagnostic> out
    ) {
        // Sibling className unresolved, or a census with nothing in it: the className arm has the
        // first case and defers on the second, so this arm has nothing to add to either.
        if (answers.className(classFqn) != DiagnosticFacts.Resolution.RESOLVES) return;
        var overloads = answers.overloads(classFqn, methodName);
        if (overloads.isEmpty()) {
            out.add(diagnostic(range,
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
            out.add(diagnostic(range, DiagnosticSeverity.Warning,
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
        DiagnosticFacts.Answers answers, Range range, String typeName, String fieldName,
        String memberName, List<Diagnostic> out
    ) {
        var scope = answers.memberScope(typeName, fieldName).orElse(null);
        if (scope == null || scope.offers(memberName)) return;
        out.add(diagnostic(range, DiagnosticSeverity.Error, switch (scope) {
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
        DiagnosticFacts.Answers answers, byte[] source, Finding.ArgMappingValue value,
        List<Diagnostic> out
    ) {
        Range valueRange = value.range();
        int contentStart = value.contentStart();
        Set<String> paramNames = parameterNames(answers, value.classFqn(), value.methodName());
        List<String> fieldArgs = value.fieldArgs();

        var seenJava = new LinkedHashSet<String>();
        for (var entry : value.entries()) {
            if (!entry.hasColon() && entry.isBlank()) {
                out.add(diagnostic(valueRange, DiagnosticSeverity.Warning,
                    "Empty argMapping entry (stray comma)."));
                continue;
            }
            if (!entry.hasColon()) {
                out.add(byteDiagnostic(source, contentStart + entry.rawStart(), contentStart + entry.rawEnd(),
                    DiagnosticSeverity.Warning, "Expected 'javaParam: graphqlArg' in argMapping entry."));
                continue;
            }
            if (entry.java().isEmpty()) {
                out.add(byteDiagnostic(source, contentStart + entry.rawStart(), contentStart + entry.rawEnd(),
                    DiagnosticSeverity.Warning, "Missing Java parameter before ':' in argMapping."));
            } else {
                judgeArgMappingJavaParam(entry.java(), contentStart, paramNames, seenJava, source, out);
            }
            if (entry.graphql().isEmpty()) {
                out.add(byteDiagnostic(source, contentStart + entry.rawStart(), contentStart + entry.rawEnd(),
                    DiagnosticSeverity.Warning, "Missing GraphQL argument after ':' in argMapping."));
            } else {
                judgeArgMappingGraphqlArg(entry.graphql(), contentStart, fieldArgs, source, out);
            }
        }
    }

    private static void judgeArgMappingJavaParam(
        ArgMapping.Segment java, int contentStart, Set<String> paramNames,
        Set<String> seenJava, byte[] source, List<Diagnostic> out
    ) {
        String name = java.text();
        if (!seenJava.add(name)) {
            out.add(byteDiagnostic(source, contentStart + java.start(), contentStart + java.end(),
                DiagnosticSeverity.Warning, "Duplicate Java parameter '" + name + "' in argMapping."));
            return;
        }
        if (paramNames != null && !paramNames.contains(name)) {
            out.add(byteDiagnostic(source, contentStart + java.start(), contentStart + java.end(),
                DiagnosticSeverity.Warning,
                "Unknown Java parameter '" + name + "'; not a parameter of the referenced method."));
        }
    }

    private static void judgeArgMappingGraphqlArg(
        ArgMapping.Segment graphql, int contentStart, List<String> fieldArgs,
        byte[] source, List<Diagnostic> out
    ) {
        if (fieldArgs.isEmpty()) return; // no field args known (pre-build or argument-less field)
        String value = graphql.text();
        int dot = value.indexOf('.');
        String head = dot >= 0 ? value.substring(0, dot) : value;
        if (head.isEmpty() || fieldArgs.contains(head)) return;
        // Flag only the head segment span so a valid dot-path with a typo'd
        // first step underlines the offending step, not the whole path.
        int headEnd = graphql.start() + head.length();
        out.add(byteDiagnostic(source, contentStart + graphql.start(), contentStart + headEnd,
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
        byte[] source, int startByte, int endByte, DiagnosticSeverity severity, String message
    ) {
        return diagnostic(new Range(
            Positions.toLspPosition(source, startByte),
            Positions.toLspPosition(source, endByte)), severity, message);
    }

    /** The span a node occupies, which is all a verdict about that node needs to know about it. */
    private static Range rangeOf(FileSnapshot file, Node node) {
        return new Range(
            Positions.toLspPosition(file.source(), node.getStartByte()),
            Positions.toLspPosition(file.source(), node.getEndByte()));
    }

    private static Diagnostic diagnostic(Range range, DiagnosticSeverity severity, String message) {
        var d = new Diagnostic(range, message);
        d.setSeverity(severity);
        d.setSource(SOURCE);
        return d;
    }

    private static Diagnostic diagnostic(Range range, String message) {
        return diagnostic(range, DiagnosticSeverity.Error, message);
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
