package no.sikt.graphitron.lsp.inlay;

import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.parsing.DeclarationKind;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.Nodes;
import no.sikt.graphitron.lsp.parsing.Positions;
import no.sikt.graphitron.lsp.parsing.TypeContext;
import no.sikt.graphitron.lsp.state.InlayHintConfig;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.model.grammar.InferredDirectiveArgs;
import no.sikt.graphitron.model.read.StoreHandle;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.DIRECTIVE;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.DIRECTIVES;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.FIELDS_DEFINITION;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.FIELD_DEFINITION;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.INPUT_FIELDS_DEFINITION;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.INPUT_VALUE_DEFINITION;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.NAME;

/**
 * LSP inlay-hint provider. Three arms, each gated by an independent config toggle, each reading only
 * stored relations and no classification:
 * <ul>
 *   <li><b>Inferred-directive arm</b>: at {@code @table} / {@code @field} sites where the author
 *       omitted {@code name:}, renders the resolved value as a resolution overlay: text drawn beside
 *       the buffer that the buffer does not contain. {@code @reference}'s omitted {@code path:}
 *       renders nothing until the path discovery implies is stored.</li>
 *   <li><b>Label arm</b>: at a type declaration, what it holds, the table whose rows it holds or the
 *       class whose instances it does; at a field declaration, what it reads, its column, the method
 *       it calls or the table its statement lands on.</li>
 *   <li><b>Separate-fetch arm</b>: at a field whose rows come from a statement of its own,
 *       renders one marker word. Its own toggle rather than a second label, because whether a field
 *       costs a round trip is a delivery fact, and an author auditing a schema for query cost wants
 *       that one signal without a label beside every declaration.</li>
 * </ul>
 *
 * <h2>Collect, resolve, render</h2>
 *
 * <p>All four arms walk the visible region first and read nothing while they walk. What each site
 * needs is recorded as a {@link Pending} intent beside the question that answers it, so the whole
 * region's questions are known before the first of them is put to the store, and one statement
 * answers them ({@link InlayFacts}). Rendering then runs over the intents in walk order, which is
 * the order the arms produced them in, so what an editor receives is unchanged by the split.
 *
 * <p>The region is the unit, and it is the only unit this surface has: an inlay request is one
 * file's window, where a diagnostic recalculation spans the files a capture touched. An editor
 * reissues the request on every scroll, which is why the count mattered here: it used to grow with
 * the region, so an author scrolling a wide type paid a round trip per overlay.
 *
 * <p>The inferred-directive arm always asks the tree-sitter AST whether the canonical argument is
 * present in the buffer, and differs by directive in where it reads the value the author omitted.
 * {@code @table} reads the type's binding, and {@code @field} the member its own name reaches, which
 * is the column the field reads where the parent holds a table's row and a slot of the parent's
 * backing class where it does not ({@link InlayFacts.Answers#memberName}).
 *
 * <p>One cadence results, the capture's, and every overlay is silent where what it reads is absent. No
 * arm here reads a projection a generator pass built, so this surface answers in a workspace that has
 * captured its schema and never run a build, and answers the same in one that has.
 */
public final class InlayHints {

    private InlayHints() {}

    /**
     * One overlay the walk decided to render, and what still has to be resolved before it can be.
     * Every arm carries an already-computed {@link Position} rather than the tree-sitter node it came
     * from: a node is a native resource whose lifetime is the file lock, and a value that outlives the
     * walk must not be one.
     */
    private sealed interface Pending {

        Position position();

        /** What a visible type declaration holds: its table, or the class of its instances. */
        record TypeLabel(Position position, String typeName) implements Pending {}

        /** What a visible field declaration reads: its column, the method it calls, or its target table. */
        record FieldLabel(Position position, String typeName, String fieldName) implements Pending {}

        /** The round-trip marker at a visible field declaration. */
        record FetchMarker(Position position, String typeName, String fieldName) implements Pending {}

        /** A present {@code @table} whose omitted name the store may fill in. */
        record TableName(Position position, String typeName, String argName) implements Pending {}

        /** A type bound to a table that carries no {@code @table} at all. */
        record AbsentTable(Position position, String typeName) implements Pending {}

        /** A present {@code @field} whose omitted name the store may fill in. */
        record MemberName(Position position, String typeName, String fieldName, String argName)
            implements Pending {}

    }

    /**
     * Collector for the present-directive inlay arm: records the intent to overlay the canonical
     * argument on a directive that omitted it, plus the question that resolves it. One per
     * {@link InferredDirectiveArgs.Entry}, registered by directive name in
     * {@link #INFERRED_RENDERERS}. Every collector reads the buffer and records a question; none of
     * them resolves a value, which is what keeps the region's reads to one statement.
     */
    @FunctionalInterface
    private interface InferredDirectiveCollector {
        void collect(List<Pending> out, InlayFacts.Questions questions, FileSnapshot file,
                     Directives.Directive directive, String canonicalArgName);
    }

    /**
     * Registry pairing each inferred-directive entry with its present-arm collector,
     * keyed by directive name. This replaced the {@code switch(directiveName)}
     * whose {@code default} silently dropped any {@link InferredDirectiveArgs.Entry}
     * without a renderer; {@code InlayHintRendererCoverageTest} now fails the build
     * when an entry has no matching key here. The collectors stay here rather than
     * beside the entry table because they need {@link FileSnapshot} context an
     * {@link InferredDirectiveArgs.Entry} cannot carry.
     */
    private static final Map<String, InferredDirectiveCollector> INFERRED_RENDERERS = Map.of(
        "table", InlayHints::collectInferredTableName,
        "field", InlayHints::collectInferredFieldName
    );

    /** Directive names with a registered present-arm renderer; the coverage-test oracle. */
    public static Set<String> renderedInferredDirectives() {
        return INFERRED_RENDERERS.keySet();
    }

    public static List<InlayHint> compute(
        InlayHintConfig config, FileSnapshot file, Optional<StoreHandle> store, Range visibleRange
    ) {
        if (config == null || !config.anyEnabled()) return List.of();
        if (file == null || file.tree() == null) return List.of();

        var pending = new ArrayList<Pending>();
        var questions = new InlayFacts.Questions();
        Node root = file.tree().getRootNode();
        boolean storeArms = (config.classification() || config.separateFetch()) && store.isPresent();
        if (storeArms) {
            // One walk for both store-backed arms. They annotate the same declaration sites and
            // differ only in what they ask about them, so a second walk would re-derive the region.
            var sites = collectSites(file, root, visibleRange);
            if (config.classification()) {
                collectClassificationLabels(pending, questions, file, sites);
            }
            if (config.separateFetch()) {
                collectFetchMarkers(pending, questions, file, sites);
            }
        }
        if (config.inferredDirectives()) {
            collectInferredDirectiveHints(
                pending, questions, file, store.isPresent(), root, visibleRange);
        }
        if (pending.isEmpty()) return List.of();
        // One statement for the whole region, or none at all in a session with no store, where every
        // arm's answer is empty and every overlay is silent.
        var answers = store.map(handle -> InlayFacts.of(handle, questions)).orElseGet(InlayFacts::none);
        var hints = new ArrayList<InlayHint>(pending.size());
        for (var intent : pending) render(intent, answers, hints);
        return hints;
    }

    /** One intent's overlay, or none where what it needed is not in the answer. */
    private static void render(Pending intent, InlayFacts.Answers answers, List<InlayHint> out) {
        switch (intent) {
            case Pending.TypeLabel(var position, var typeName) ->
                answers.typeLabel(typeName).ifPresent(label -> out.add(makeHint(position, label)));
            case Pending.FieldLabel(var position, var typeName, var fieldName) ->
                answers.fieldLabel(typeName, fieldName).ifPresent(label -> out.add(makeHint(position, label)));
            case Pending.FetchMarker(var position, var typeName, var fieldName) -> {
                if (answers.marksSeparateFetch(typeName, fieldName)) {
                    out.add(makeHint(position, SEPARATE_FETCH_LABEL));
                }
            }
            case Pending.TableName(var position, var typeName, var argName) ->
                answers.certainBoundTable(typeName).ifPresent(table -> out.add(
                    makeHint(position, argName + ": \"" + table.tableName() + "\"")));
            case Pending.AbsentTable(var position, var typeName) ->
                answers.certainBoundTable(typeName).ifPresent(table -> out.add(makeHint(position,
                    "@table(name: \"" + table.tableName() + "\")")));
            case Pending.MemberName(var position, var typeName, var fieldName, var argName) ->
                answers.memberName(typeName, fieldName).ifPresent(memberName -> out.add(
                    makeHint(position, argName + ": \"" + memberName + "\"")));
        }
    }

    // ===== Label arm =====

    /**
     * One visible declaration name the arm may annotate: a type when {@code fieldName} is null,
     * a field otherwise. Collected before any query so the two store reads are asked once for the
     * region rather than once per declaration in it.
     */
    private record ClaimSite(String typeName, String fieldName, Node nameNode) {}

    /**
     * Labels each visible declaration with what the store states it is: a type with the table whose
     * rows it holds, or the simple name of the class whose instances it holds; a field with the
     * column it reads, the method it calls, or the table its statement lands on. A declaration the
     * store states none of gets no hint, and neither does a type holding several, which is the
     * hover's to list. The label names a fact, never a category, so it cannot fall out of step with
     * the generator.
     */
    private static void collectClassificationLabels(
        List<Pending> out, InlayFacts.Questions questions, FileSnapshot file, List<ClaimSite> sites
    ) {
        for (var site : sites) {
            var position = positionOf(file, site.nameNode());
            if (site.fieldName() == null) {
                questions.typeDeclaration(site.typeName());
                out.add(new Pending.TypeLabel(position, site.typeName()));
                continue;
            }
            questions.declaration(site.typeName());
            out.add(new Pending.FieldLabel(position, site.typeName(), site.fieldName()));
        }
    }

    // ===== Separate-fetch arm =====

    /**
     * Marks the visible fields whose rows are fetched by a statement of their own. One word, the
     * same at every site, because the reason is the hover's business and a marker an author scans
     * a schema with should read as one signal rather than four.
     *
     * <p>Silent at a field a universal rule reaches, which today is every field of a root type: a
     * marker true of an entire type down its whole length is noise, and its absence there is not a
     * claim that the field inlines. Silent at a field no rule reaches for the opposite reason and
     * with the same caveat, the relation still not carrying a child reached through a connection
     * wrapper nor the polymorphic fan-in, so this arm marks what it can prove and never marks the
     * complement.
     */
    private static void collectFetchMarkers(
        List<Pending> out, InlayFacts.Questions questions, FileSnapshot file, List<ClaimSite> sites
    ) {
        for (var site : sites) {
            if (site.fieldName() == null) continue;
            questions.declaration(site.typeName());
            out.add(new Pending.FetchMarker(
                positionOf(file, site.nameNode()), site.typeName(), site.fieldName()));
        }
    }

    /** The marker's whole text. Named here so the arm and its test cannot spell it differently. */
    public static final String SEPARATE_FETCH_LABEL = "separate fetch";

    /** Every declaration name in the visible region, types and their fields alike. */
    private static List<ClaimSite> collectSites(FileSnapshot file, Node root, Range visibleRange) {
        var sites = new ArrayList<ClaimSite>();
        DeclarationKind.walkAll(root, node -> intersects(node, visibleRange), typeDef -> {
            String typeName = TypeContext.declaredNameOf(typeDef, file.source()).orElse(null);
            if (typeName == null) return;
            Node typeNameNode = Nodes.childOfKind(typeDef, NAME);
            if (typeNameNode != null) {
                sites.add(new ClaimSite(typeName, null, typeNameNode));
            }
            Node fieldsContainer = Nodes.childOfKind(typeDef, FIELDS_DEFINITION);
            if (fieldsContainer == null) {
                fieldsContainer = Nodes.childOfKind(typeDef, INPUT_FIELDS_DEFINITION);
            }
            if (fieldsContainer == null) return;
            for (int i = 0; i < fieldsContainer.getChildCount(); i++) {
                Node child = fieldsContainer.getChild(i).orElse(null);
                if (child == null) continue;
                if (!FIELD_DEFINITION.matches(child) && !INPUT_VALUE_DEFINITION.matches(child)) continue;
                if (!intersects(child, visibleRange)) continue;
                Node nameNode = Nodes.childOfKind(child, NAME);
                if (nameNode == null) continue;
                sites.add(new ClaimSite(typeName, Nodes.text(nameNode, file.source()), nameNode));
            }
        });
        return sites;
    }

    // ===== Inferred-directive arm =====

    private static void collectInferredDirectiveHints(
        List<Pending> out, InlayFacts.Questions questions, FileSnapshot file,
        boolean hasStore, Node root, Range visibleRange
    ) {
        var directives = visibleRange == null
            ? Directives.findAll(root)
            : Directives.findAll(root, visibleRange.getStart().getLine(), visibleRange.getEnd().getLine());
        for (var directive : directives) {
            if (!intersects(directive.outer(), visibleRange)) continue;
            String directiveName = Nodes.text(directive.nameNode(), file.source());
            // Dispatch is keyed by the canonical-arg table (single source of truth in
            // InferredDirectiveArgs); the entry's argName tells the collector which buffer
            // arg to check, the directive name tells the collector where the value comes from.
            var entry = InferredDirectiveArgs.findByDirective(directiveName).orElse(null);
            if (entry == null) continue;
            var collector = INFERRED_RENDERERS.get(entry.directiveName());
            // Completeness is asserted by InlayHintRendererCoverageTest, not by a
            // silent default arm; the guard is the belt to that test's suspenders.
            if (collector != null) {
                collector.collect(out, questions, file, directive, entry.argName());
            }
        }
        if (hasStore) {
            collectAbsentTableHints(out, questions, file, root, visibleRange);
        }
    }

    // ===== Absent-directive arm =====

    /**
     * The {@code @table} arm's second pass: a type bound to a table but carrying no {@code @table}
     * at all gets the whole directive as an overlay, where the first pass only fills in an argument a
     * present directive omitted. What it reaches today is the {@code extend type} site, a binding
     * being a property of the type rather than of the declaration in front of the cursor.
     *
     * <p>It does not reach the undirected bindings, and one of those two is still a missing relation
     * while the other will never be overlaid. A directiveless object reached from a field of a scoped
     * type resolves its fields against the parent's own row, and nothing states that binding yet, so
     * read an overlay that appears and do not read its absence as "this type is unbound". A type
     * produced by a class-returning field is the other, and the store answers it now; it gets no
     * overlay because an overlay renders a directive an author could have written, and no directive
     * carries a backing class any more, {@code @record} being deprecated and ignored. What shows
     * that binding is the label arm's type label, which is why the label is a class name there.
     *
     * <p>The one absent arm there is, rather than a strategy per entry with two of the three left
     * null. The other two would each overlay most of the file: {@code @field} every column-bound
     * field, and {@code @reference} every field whose type is bound to a table its parent's table has
     * one foreign key to, which is the ordinary shape of a schema rather than a fact worth pointing
     * at. Both are decisions about what is worth showing, so a future third arm is a pass someone
     * writes and argues for, not a flag flipped on an entry.
     */
    private static void collectAbsentTableHints(
        List<Pending> out, InlayFacts.Questions questions, FileSnapshot file,
        Node root, Range visibleRange
    ) {
        DeclarationKind.walkAll(root, node -> intersects(node, visibleRange), typeDef -> {
            String typeName = TypeContext.declaredNameOf(typeDef, file.source()).orElse(null);
            if (typeName == null) return;
            if (typeCarriesDirective(typeDef, "table", file.source())) return;
            Node nameNode = Nodes.childOfKind(typeDef, NAME);
            if (nameNode == null) return;
            questions.boundTableSite(typeName);
            out.add(new Pending.AbsentTable(positionOf(file, nameNode), typeName));
        });
    }

    private static boolean typeCarriesDirective(Node typeDef, String directiveName, byte[] source) {
        Node directives = Nodes.childOfKind(typeDef, DIRECTIVES);
        if (directives == null) return false;
        for (int i = 0; i < directives.getChildCount(); i++) {
            Node child = directives.getChild(i).orElse(null);
            if (child == null || !DIRECTIVE.matches(child)) continue;
            Node nameNode = Nodes.childOfKind(child, NAME);
            if (nameNode != null && directiveName.equals(Nodes.text(nameNode, source))) return true;
        }
        return false;
    }

    /**
     * Keyed on the enclosing type's declared name rather than on the directive node in hand, so an
     * {@code extend type} site resolves through the base declaration's binding the way every other
     * reader of a type's binding does.
     */
    private static void collectInferredTableName(
        List<Pending> out, InlayFacts.Questions questions, FileSnapshot file,
        Directives.Directive directive, String canonicalArgName
    ) {
        if (hasNamedArg(directive, canonicalArgName, file.source())) return;
        var enclosingType = DeclarationKind.enclosing(directive.outer()).orElse(null);
        if (enclosingType == null) return;
        String typeName = TypeContext.declaredNameOf(enclosingType, file.source()).orElse(null);
        if (typeName == null) return;
        questions.boundTableSite(typeName);
        out.add(new Pending.TableName(
            positionOf(file, directive.nameNode()), typeName, canonicalArgName));
    }

    /**
     * Keyed on the enclosing type's declared name for the same reason the {@code @table} pass is, and
     * answered by {@link InlayFacts.Answers#memberName}: a column where the site resolves against a
     * table and a class member where it resolves against a class, over rows the region's one statement
     * already brought back.
     */
    private static void collectInferredFieldName(
        List<Pending> out, InlayFacts.Questions questions, FileSnapshot file,
        Directives.Directive directive, String canonicalArgName
    ) {
        if (hasNamedArg(directive, canonicalArgName, file.source())) return;
        var enclosingField = TypeContext.enclosingFieldDefinition(directive.outer())
            .or(() -> enclosingInputValueDefinition(directive.outer())).orElse(null);
        if (enclosingField == null) return;
        var enclosingType = DeclarationKind.enclosing(enclosingField).orElse(null);
        if (enclosingType == null) return;
        String typeName = TypeContext.declaredNameOf(enclosingType, file.source()).orElse(null);
        if (typeName == null) return;
        String fieldName = TypeContext.fieldNameOf(enclosingField, file.source())
            .orElseGet(() -> {
                Node nameNode = Nodes.childOfKind(enclosingField, NAME);
                return nameNode != null ? Nodes.text(nameNode, file.source()) : null;
            });
        if (fieldName == null) return;
        questions.memberSite(typeName, fieldName);
        out.add(new Pending.MemberName(
            positionOf(file, directive.nameNode()), typeName, fieldName, canonicalArgName));
    }

    // ===== Tree-sitter helpers =====

    private static java.util.Optional<Node> enclosingInputValueDefinition(Node inner) {
        Node node = inner;
        while (node != null) {
            if (INPUT_VALUE_DEFINITION.matches(node)) {
                return java.util.Optional.of(node);
            }
            Node parent = node.getParent().orElse(null);
            if (parent == null || parent.equals(node)) {
                return java.util.Optional.empty();
            }
            node = parent;
        }
        return java.util.Optional.empty();
    }

    private static boolean hasNamedArg(Directives.Directive directive, String argName, byte[] source) {
        for (var arg : directive.arguments()) {
            if (argName.equals(Nodes.text(arg.key(), source))) return true;
        }
        return false;
    }

    private static boolean intersects(Node node, Range visibleRange) {
        if (visibleRange == null) return true;
        Point start = node.getStartPoint();
        Point end = node.getEndPoint();
        Position rangeStart = visibleRange.getStart();
        Position rangeEnd = visibleRange.getEnd();
        // Node ends before range starts
        if (end.row() < rangeStart.getLine()
            || (end.row() == rangeStart.getLine() && end.column() < rangeStart.getCharacter())) {
            return false;
        }
        // Node starts after range ends
        if (start.row() > rangeEnd.getLine()
            || (start.row() == rangeEnd.getLine() && start.column() > rangeEnd.getCharacter())) {
            return false;
        }
        return true;
    }

    /**
     * Where an overlay anchored on {@code anchor} goes: the end of the node, so the text appears
     * immediately after the directive name or declaration name. Resolved during the walk, which is
     * what lets an intent outlive the node it came from.
     */
    private static Position positionOf(FileSnapshot file, Node anchor) {
        return Positions.toLspPosition(file.source(), anchor.getEndByte());
    }

    /**
     * Every overlay this surface emits, one kind for all of them: an inlay hint's kind is a client
     * rendering hint and every arm here annotates a declaration with what graphitron makes of it.
     */
    private static InlayHint makeHint(Position position, String label) {
        var hint = new InlayHint(position, org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(label));
        hint.setKind(InlayHintKind.Type);
        hint.setPaddingLeft(true);
        return hint;
    }
}
