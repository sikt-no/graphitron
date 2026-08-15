package no.sikt.graphitron.lsp.inlay;

import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.facts.ClaimClassifiers;
import no.sikt.graphitron.lsp.facts.ClaimFacts;
import no.sikt.graphitron.lsp.facts.SeparateFetchRule;
import no.sikt.graphitron.lsp.parsing.DeclarationKind;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.Nodes;
import no.sikt.graphitron.lsp.parsing.Positions;
import no.sikt.graphitron.lsp.parsing.TypeContext;
import no.sikt.graphitron.lsp.state.InlayHintConfig;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.rewrite.catalog.FieldClassification;
import no.sikt.graphitron.rewrite.catalog.InferredDirectiveArgs;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.catalog.TypeClassification;
import no.sikt.graphitron.model.read.StoreHandle;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import java.util.ArrayList;
import java.util.LinkedHashSet;
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
 *       {@code path:} for the third), renders the resolved value as a ghost annotation.</li>
 *   <li><b>Classification arm</b>: at a field or type declaration the claim stratum has an
 *       opinion about, renders the classifiers claiming it, read from
 *       {@link ClaimClassifiers}.</li>
 *   <li><b>Separate-fetch arm</b>: at a field whose rows come from a statement of its own,
 *       renders one marker word. Its own toggle rather than a second label on the classification
 *       arm, because whether a field costs a round trip is a delivery fact rather than a
 *       classifier, and an author auditing a schema for query cost wants that one signal without
 *       a classifier beside every declaration.</li>
 * </ul>
 *
 * <p>The two store-backed arms share one walk of the visible region and one pass of bulk queries
 * over the type names in it, so turning both on costs a query per grain rather than a query per
 * declaration.
 *
 * <p>The inferred-directive arm asks the tree-sitter AST whether the canonical argument is
 * present and consults the {@link FieldClassification} / {@link TypeClassification} projections
 * on the snapshot for the resolved value when absent.
 *
 * <p>The two arms therefore run off different sources and different cadences. The classification
 * arm needs the store and no snapshot, and renders whatever the last capture wrote; the
 * inferred-directive arm needs the snapshot, rendering under
 * {@link LspSchemaSnapshot.Built.Current} and {@link LspSchemaSnapshot.Built.Previous}
 * indistinguishably and not at all under {@link LspSchemaSnapshot.Unavailable}. Each arm is
 * silent when what it reads is absent, so a session with one of the two answers still gets it.
 */
public final class InlayHints {

    private InlayHints() {}

    /**
     * Renderer for the present-directive inlay arm: emits the inferred canonical
     * argument as a ghost annotation on a directive that omitted it. One per
     * {@link InferredDirectiveArgs.Entry}, registered by directive name in
     * {@link #INFERRED_RENDERERS}.
     */
    @FunctionalInterface
    private interface InferredDirectiveRenderer {
        void render(List<InlayHint> out, FileSnapshot file, LspSchemaSnapshot.Built built,
                    Directives.Directive directive, String canonicalArgName);
    }

    /**
     * Registry pairing each inferred-directive entry with its present-arm renderer,
     * keyed by directive name. This replaced the {@code switch(directiveName)}
     * whose {@code default} silently dropped any {@link InferredDirectiveArgs.Entry}
     * without a renderer; {@code InlayHintRendererCoverageTest} now fails the build
     * when an entry has no matching key here, the LSP-side mirror of the catalog's
     * sealed {@code AbsentArm}. The renderers stay LSP-side because they need
     * {@link FileSnapshot} / {@link LspSchemaSnapshot.Built} context the catalog
     * {@code Entry} cannot carry.
     */
    private static final Map<String, InferredDirectiveRenderer> INFERRED_RENDERERS = Map.of(
        "table", InlayHints::renderInferredTableNameHint,
        "field", InlayHints::renderInferredFieldNameHint,
        "reference", InlayHints::renderInferredReferencePathHint
    );

    /** Directive names with a registered present-arm renderer; the coverage-test oracle. */
    public static Set<String> renderedInferredDirectives() {
        return INFERRED_RENDERERS.keySet();
    }

    public static List<InlayHint> compute(
        InlayHintConfig config, FileSnapshot file, Optional<StoreHandle> store,
        LspSchemaSnapshot snapshot, Range visibleRange
    ) {
        if (config == null || !config.anyEnabled()) return List.of();
        if (file == null || file.tree() == null) return List.of();

        var hints = new ArrayList<InlayHint>();
        Node root = file.tree().getRootNode();
        boolean storeArms = (config.classification() || config.separateFetch()) && store.isPresent();
        if (storeArms) {
            // One walk for both store-backed arms. They annotate the same declaration sites and
            // differ only in what they ask about them, so a second walk would re-derive the region.
            var sites = collectSites(file, root, visibleRange);
            if (!sites.isEmpty()) {
                var typeNames = new LinkedHashSet<String>();
                for (var site : sites) typeNames.add(site.typeName());
                if (config.classification()) {
                    collectClassificationHints(hints, file, store.get(), sites, typeNames);
                }
                if (config.separateFetch()) {
                    collectSeparateFetchHints(hints, file, store.get(), sites, typeNames);
                }
            }
        }
        if (config.inferredDirectives() && snapshot instanceof LspSchemaSnapshot.Built built) {
            collectInferredDirectiveHints(hints, file, built, root, visibleRange);
        }
        return hints;
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
     */
    private static void collectClassificationHints(
        List<InlayHint> out, FileSnapshot file, StoreHandle store,
        List<ClaimSite> sites, Set<String> typeNames
    ) {
        var byType = ClaimClassifiers.ofTypes(store, typeNames);
        var byCoordinate = ClaimClassifiers.ofFields(store, typeNames);
        for (var site : sites) {
            var classifiers = site.fieldName() == null
                ? byType.get(site.typeName())
                : byCoordinate.get(site.typeName() + "." + site.fieldName());
            if (classifiers == null || classifiers.isEmpty()) continue;
            out.add(makeHint(file, site.nameNode(), String.join(", ", classifiers), InlayHintKind.Type));
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
     * with the same caveat, the relation not yet carrying the implicit split a class-backed parent
     * forces, so this arm marks what it can prove and never marks the complement.
     */
    private static void collectSeparateFetchHints(
        List<InlayHint> out, FileSnapshot file, StoreHandle store,
        List<ClaimSite> sites, Set<String> typeNames
    ) {
        var byCoordinate = ClaimFacts.separateFetchRules(store, typeNames);
        for (var site : sites) {
            if (site.fieldName() == null) continue;
            var rules = byCoordinate.get(site.typeName() + "." + site.fieldName());
            if (rules == null || !SeparateFetchRule.marksInline(rules)) continue;
            out.add(makeHint(file, site.nameNode(), SEPARATE_FETCH_LABEL, InlayHintKind.Type));
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
        List<InlayHint> out, FileSnapshot file, LspSchemaSnapshot.Built built,
        Node root, Range visibleRange
    ) {
        var directives = Directives.findAll(root);
        for (var directive : directives) {
            if (!intersects(directive.outer(), visibleRange)) continue;
            String directiveName = Nodes.text(directive.nameNode(), file.source());
            // Dispatch is keyed by the canonical-arg table (single source of truth in
            // InferredDirectiveArgs); the entry's argName tells the renderer which buffer
            // arg to check, the directive name tells the renderer which projection to read.
            var entry = InferredDirectiveArgs.findByDirective(directiveName).orElse(null);
            if (entry == null) continue;
            var renderer = INFERRED_RENDERERS.get(entry.directiveName());
            // Completeness is asserted by InlayHintRendererCoverageTest, not by a
            // silent default arm; the guard is the belt to that test's suspenders.
            if (renderer != null) {
                renderer.render(out, file, built, directive, entry.argName());
            }
        }
        collectAbsentDirectiveHints(out, file, built, root, visibleRange);
    }

    // ===== Absent-directive arm =====

    /**
     * Second pass for entries with a non-null {@link InferredDirectiveArgs.Entry#absentArm()}.
     * Walks type-definition nodes (parallel to the classification arm) and, for each
     * absent-eligible entry, delegates to the entry's {@link InferredDirectiveArgs.AbsentArm}
     * strategy for the resolved value, emitting a synthetic full-directive hint when the
     * type carries no directive of that name and the strategy returns a value for the
     * classification.
     */
    private static void collectAbsentDirectiveHints(
        List<InlayHint> out, FileSnapshot file, LspSchemaSnapshot.Built built,
        Node root, Range visibleRange
    ) {
        boolean anyAbsentEligible = false;
        for (var entry : InferredDirectiveArgs.ENTRIES) {
            if (entry.absentArm() != null) { anyAbsentEligible = true; break; }
        }
        if (!anyAbsentEligible) return;
        DeclarationKind.walkAll(root, typeDef -> {
            if (!intersects(typeDef, visibleRange)) return;
            String typeName = TypeContext.declaredNameOf(typeDef, file.source()).orElse(null);
            if (typeName == null) return;
            var classification = built.typeClassificationsByName().get(typeName);
            if (classification == null) return;
            Node nameNode = Nodes.childOfKind(typeDef, NAME);
            if (nameNode == null) return;
            for (var entry : InferredDirectiveArgs.ENTRIES) {
                var arm = entry.absentArm();
                if (arm == null) continue;
                if (typeCarriesDirective(typeDef, entry.directiveName(), file.source())) continue;
                arm.resolveAbsentValue(classification).ifPresent(value ->
                    out.add(makeHint(file, nameNode,
                        "@" + entry.directiveName() + "(" + entry.argName() + ": \"" + value + "\")",
                        InlayHintKind.Type)));
            }
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

    private static void renderInferredTableNameHint(
        List<InlayHint> out, FileSnapshot file, LspSchemaSnapshot.Built built,
        Directives.Directive directive, String canonicalArgName
    ) {
        if (hasNamedArg(directive, canonicalArgName, file.source())) return;
        // Resolve the enclosing type and look up its classification for the resolved tableName.
        var enclosingType = DeclarationKind.enclosing(directive.outer()).orElse(null);
        if (enclosingType == null) return;
        String typeName = TypeContext.declaredNameOf(enclosingType, file.source()).orElse(null);
        if (typeName == null) return;
        var classification = built.typeClassificationsByName().get(typeName);
        if (classification == null) return;
        String tableName = TypeContext.tableNameFromClassification(classification).orElse(null);
        if (tableName == null) return;
        out.add(makeHint(file, directive.nameNode(),
            canonicalArgName + ": \"" + tableName + "\"", InlayHintKind.Type));
    }

    private static void renderInferredFieldNameHint(
        List<InlayHint> out, FileSnapshot file, LspSchemaSnapshot.Built built,
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
        var classification = built.fieldClassificationsByCoord()
            .get(typeName + "." + fieldName);
        if (classification == null) return;
        String columnName = columnNameOf(classification);
        if (columnName == null) return;
        out.add(makeHint(file, directive.nameNode(),
            canonicalArgName + ": \"" + columnName + "\"", InlayHintKind.Type));
    }

    private static void renderInferredReferencePathHint(
        List<InlayHint> out, FileSnapshot file, LspSchemaSnapshot.Built built,
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
        var classification = built.fieldClassificationsByCoord()
            .get(typeName + "." + fieldName);
        if (classification == null) return;
        List<FieldClassification.FkStep> path = fkPathOf(classification);
        if (path == null || path.isEmpty()) return;
        StringBuilder sb = new StringBuilder(canonicalArgName + ": [");
        for (int i = 0; i < path.size(); i++) {
            if (i > 0) sb.append(", ");
            var step = path.get(i);
            sb.append("{");
            if (step.fkName() != null) {
                sb.append("key: \"").append(step.fkName()).append("\"");
            } else if (step.targetTableName() != null) {
                sb.append("table: \"").append(step.targetTableName()).append("\"");
            }
            sb.append("}");
        }
        sb.append("]");
        out.add(makeHint(file, directive.nameNode(), sb.toString(), InlayHintKind.Type));
    }

    // ===== Projection accessors =====

    private static String columnNameOf(FieldClassification classification) {
        return switch (classification) {
            case FieldClassification.Column c -> c.columnName();
            case FieldClassification.ColumnReference c -> c.columnName();
            case FieldClassification.ParticipantCrossTable c -> c.columnName();
            case FieldClassification.RecordOrProperty c -> c.columnName();
            default -> null;
        };
    }

    private static List<FieldClassification.FkStep> fkPathOf(FieldClassification classification) {
        return switch (classification) {
            case FieldClassification.ColumnReference c -> c.joinPath();
            case FieldClassification.CompositeColumnReference c -> c.joinPath();
            default -> null;
        };
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

    private static InlayHint makeHint(FileSnapshot file, Node anchor, String label, InlayHintKind kind) {
        // Anchor the hint at the end of the anchor node (so the ghost annotation appears
        // immediately after the directive name or declaration name).
        Position pos = Positions.toLspPosition(file.source(), anchor.getEndByte());
        var hint = new InlayHint(pos, org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(label));
        hint.setKind(kind);
        hint.setPaddingLeft(true);
        return hint;
    }
}
