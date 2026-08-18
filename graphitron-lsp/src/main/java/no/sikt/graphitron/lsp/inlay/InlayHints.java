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
import no.sikt.graphitron.rewrite.catalog.InferredDirectiveArgs;
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
 * LSP inlay-hint provider. Three arms, each gated by an independent config toggle:
 * <ul>
 *   <li><b>Inferred-directive arm</b>: at {@code @table} / {@code @field} / {@code @reference}
 *       sites where the author omitted the canonical argument ({@code name:} for the first two,
 *       {@code path:} for the third), renders the resolved value as a resolution overlay: text
 *       drawn beside the buffer that the buffer does not contain.</li>
 *   <li><b>Classification arm</b>: at a field or type declaration the claim stratum has an
 *       opinion about, renders the classifiers claiming it. At a type declaration no claim names,
 *       renders instead the class the store backs it with, which is what graphitron knows a
 *       producer's payload type to be when no directive says anything about it.</li>
 *   <li><b>Separate-fetch arm</b>: at a field whose rows come from a statement of its own,
 *       renders one marker word. Its own toggle rather than a second label on the classification
 *       arm, because whether a field costs a round trip is a delivery fact rather than a
 *       classifier, and an author auditing a schema for query cost wants that one signal without
 *       a classifier beside every declaration.</li>
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
 * {@code @table} reads {@link no.sikt.graphitron.lsp.facts.BoundTables the binding relation} and
 * {@code @field} the member its own name reaches, which is the settled column match at the coordinate
 * where the parent is a table's and a slot of the parent's backing class where it is not
 * ({@link InlayFacts.Answers#memberName}). {@code @reference} fires only where the path is omitted, so
 * what it renders is the foreign key the generator discovers between the field's two endpoints
 * ({@link InlayFacts.Answers#discoveredForeignKey}), and it names one only where the discovery is
 * certain, a coordinate several keys connect being one the generator refuses to join at all.
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

        /** The classifiers claiming a visible type declaration, or the class standing for it. */
        record TypeLabel(Position position, String typeName) implements Pending {}

        /** The classifiers claiming a visible field declaration. */
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

        /** A present {@code @reference} whose omitted path the discovery may fill in. */
        record ReferencePath(Position position, String typeName, String fieldName, String argName)
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
     * when an entry has no matching key here, the LSP-side mirror of the catalog's
     * sealed {@code AbsentArm}. The collectors stay LSP-side because they need
     * {@link FileSnapshot} context the catalog {@code Entry} cannot carry.
     */
    private static final Map<String, InferredDirectiveCollector> INFERRED_RENDERERS = Map.of(
        "table", InlayHints::collectInferredTableName,
        "field", InlayHints::collectInferredFieldName,
        "reference", InlayHints::collectInferredReferencePath
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
            case Pending.TypeLabel(var position, var typeName) -> {
                var classifiers = answers.typeClassifiers(typeName);
                String label = classifiers.isEmpty()
                    ? simpleName(answers.backingClass(typeName).orElse(null))
                    : String.join(", ", classifiers);
                if (label != null) out.add(makeHint(position, label));
            }
            case Pending.FieldLabel(var position, var typeName, var fieldName) -> {
                var classifiers = answers.fieldClassifiers(typeName, fieldName);
                if (!classifiers.isEmpty()) {
                    out.add(makeHint(position, String.join(", ", classifiers)));
                }
            }
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
            case Pending.ReferencePath(var position, var typeName, var fieldName, var argName) ->
                answers.discoveredForeignKey(typeName, fieldName).ifPresent(key -> out.add(
                    makeHint(position, argName + ": [{key: \"" + key + "\"}]")));
        }
    }

    // ===== Classification arm =====

    /**
     * One visible declaration name the arm may annotate: a type when {@code fieldName} is null,
     * a field otherwise. Collected before any query so the two store reads are asked once for the
     * region rather than once per declaration in it.
     */
    private record ClaimSite(String typeName, String fieldName, Node nameNode) {}

    /**
     * Renders the classifiers claiming each visible declaration, one label per site, the
     * classifiers comma-joined where more than one claims a coordinate. A declaration no
     * classifier claims gets no hint: the arm marks where graphitron has an opinion about a
     * declaration, and silence is the honest reading of a plain SDL object or a field nothing
     * bound.
     *
     * <p>The label is the store's own classifier vocabulary and nothing else. The facts the
     * incumbent single-word label folded in (which table, which column, which join path, whether a
     * fetch batches) are what the hover renders, each from the relation that owns it, because a
     * word naming their combinations would be a taxonomy this arm had to keep in step with the
     * generator by hand.
     *
     * <p>One type-declaration label is not a classifier, and it is here because the alternative was
     * silence at a type graphitron has a firm opinion about. A payload type reached through a
     * {@code @service} return carries no type directive, so no claim names it, and what the store
     * knows about it is a backing class. That is not a claim (nothing authored declares it; the
     * {@code @record} directive that once did is deprecated and ignored), so it renders as the
     * class's own simple name rather than as a category word minted for the occasion. The
     * CamelCase reads as a class where a classifier reads as a category, and the class is the
     * answer an author actually wants at such a type. It shows only where no claim does, the label
     * having room for one answer: a claimed type's backing, and a contested type's candidates, are
     * the hover's to state.
     */
    private static void collectClassificationLabels(
        List<Pending> out, InlayFacts.Questions questions, FileSnapshot file, List<ClaimSite> sites
    ) {
        for (var site : sites) {
            var position = positionOf(file, site.nameNode());
            if (site.fieldName() == null) {
                // The backing question is asked for every visible type, not only for the ones no claim
                // names, because which of the two labels a type is decided after both arms answer;
                // narrowing it here would make one arm conditional on another's rows.
                questions.typeDeclaration(site.typeName());
                out.add(new Pending.TypeLabel(position, site.typeName()));
                continue;
            }
            questions.declaration(site.typeName());
            out.add(new Pending.FieldLabel(position, site.typeName(), site.fieldName()));
        }
    }

    /** The trailing segment of a binary class name, or null for no class at all. */
    private static String simpleName(String className) {
        if (className == null) return null;
        return className.substring(className.lastIndexOf('.') + 1);
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
        DeclarationKind.walkAll(root, typeDef -> {
            if (!intersects(typeDef, visibleRange)) return;
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
        var directives = Directives.findAll(root);
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
     * that binding is the classification arm's type label, which is why the label is a class name
     * there rather than a category.
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
        DeclarationKind.walkAll(root, typeDef -> {
            if (!intersects(typeDef, visibleRange)) return;
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

    /**
     * Keyed on the coordinate for the same reason the {@code @field} pass is, and answered by the
     * discovery relation: the foreign key connecting the field's own endpoints, which is what the
     * generator joins on where the author wrote no element. A single hop, because discovery never
     * searches past one; a path of several elements is one the author states, and a present
     * {@code path:} argument leaves this pass nothing to fill in.
     */
    private static void collectInferredReferencePath(
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
        Node nameNode = Nodes.childOfKind(enclosingField, NAME);
        if (nameNode == null) return;
        String fieldName = Nodes.text(nameNode, file.source());
        questions.referencePathSite(typeName, fieldName);
        out.add(new Pending.ReferencePath(
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
