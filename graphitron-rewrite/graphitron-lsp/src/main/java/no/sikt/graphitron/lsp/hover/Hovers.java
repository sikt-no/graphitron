package no.sikt.graphitron.lsp.hover;

import graphql.language.Description;
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
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.DirectiveShape;
import no.sikt.graphitron.rewrite.catalog.FieldClassification;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.catalog.TypeBackingShape;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Range;
import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Point;

import java.util.List;
import java.util.Optional;

/**
 * Hover content keyed on schema-coordinate behaviors. Cursor on a known
 * coordinate ({@code @table(name:)}, {@code ExternalCodeReference.method},
 * {@code ReferenceElement.key}, ...) reveals catalog metadata: class FQNs,
 * method signatures, table descriptions, FK direction, and so on.
 *
 * <p>Coordinates without a specific {@link Behavior} arm fall through to
 * the SDL-docstring hover: every {@code DirectiveDefinition} and
 * {@code InputValueDefinition} carries a description string in the parsed
 * registry, and that description renders as the default hover. New
 * directives in {@code directives.graphqls} light up hover automatically;
 * authoring effort moves from "edit Hovers.java" to "edit the SDL".
 */
public final class Hovers {

    private Hovers() {}

    public static Optional<Hover> compute(
        WorkspaceFile file, CompletionData catalog, LspSchemaSnapshot snapshot, Point pos
    ) {
        // The bundled vocabulary is the only one in scope today; the
        // workspace's vocabulary is wired through GraphitronTextDocumentService.
        return compute(LspVocabulary.load(), file, catalog, snapshot, pos, false);
    }

    /**
     * R160 — {@code classificationHoverEnabled} gates the parallel
     * {@link DeclarationHovers} dispatch on SDL declaration coordinates. Default false
     * preserves the no-behaviour-change-by-default contract; the document service flips
     * it on per {@link no.sikt.graphitron.lsp.state.Workspace#inlayHintConfig()}.
     */
    public static Optional<Hover> compute(
        LspVocabulary vocabulary, WorkspaceFile file, CompletionData catalog,
        LspSchemaSnapshot snapshot, Point pos
    ) {
        return compute(vocabulary, file, catalog, snapshot, pos, false);
    }

    public static Optional<Hover> compute(
        LspVocabulary vocabulary, WorkspaceFile file, CompletionData catalog,
        LspSchemaSnapshot snapshot, Point pos, boolean classificationHoverEnabled
    ) {
        var directiveOpt = Directives.findContaining(file.tree().getRootNode(), pos);
        if (directiveOpt.isEmpty()) {
            // R160 — no directive at the cursor; try the classification-hover arm on SDL
            // declaration coordinates (field-definition / type-definition name tokens).
            if (classificationHoverEnabled) {
                return DeclarationHovers.compute(file, snapshot, pos);
            }
            return Optional.empty();
        }
        var directive = directiveOpt.get();
        String directiveName = Nodes.text(directive.nameNode(), file.source());
        var resolution = DirectiveResolution.resolve(vocabulary, snapshot, directiveName);

        // Directive-name hover comes first. coordinateAt is leaf-oriented
        // (arg coordinates, not directive coordinates), so a cursor on the
        // directive's name token falls through coordinateAt to no-coord
        // today. Resolve through DirectiveResolution and surface the
        // directive's description (bundled SDL or user snapshot) before
        // the coordinate path runs.
        if (Nodes.contains(directive.nameNode(), pos)) {
            return directiveNameHover(resolution, directive, file);
        }

        var coordOpt = vocabulary.coordinateAt(directive, pos, file.source());
        var rangeNode = valueNodeFor(directive, pos);

        if (coordOpt.isPresent() && rangeNode != null) {
            var coord = coordOpt.get();
            var richer = richerHover(vocabulary, coord, directive, file, catalog, snapshot, pos, rangeNode);
            if (richer.isPresent()) return richer;
            // SDL docstring on the coordinate. Empty if the parsed
            // definition has no description (rare in directives.graphqls).
            var bundled = docstringHover(vocabulary, coord, file, rangeNode);
            if (bundled.isPresent()) return bundled;
        }

        // User-arm fallback: only on User resolution. Gating here preserves
        // bundled-shadows-snapshot precedence (R139 settled design note 4):
        // for bundled directives, a missing bundled arg description stays
        // empty rather than leaking through to a shadow snapshot entry.
        if (resolution instanceof DirectiveResolution.User user) {
            return userArgHover(user.shape(), directive, pos, file);
        }
        return Optional.empty();
    }

    private static Optional<Hover> directiveNameHover(
        DirectiveResolution resolution, Directives.Directive directive, WorkspaceFile file
    ) {
        return switch (resolution) {
            case DirectiveResolution.Bundled bundled ->
                bundledDescription(bundled.def().getDescription())
                    .map(text -> hover(file, directive.nameNode(), text));
            case DirectiveResolution.User user ->
                user.shape().description()
                    .filter(d -> !d.isBlank())
                    .map(text -> hover(file, directive.nameNode(), text));
            case DirectiveResolution.Unknown ignored -> Optional.empty();
        };
    }

    private static Optional<String> bundledDescription(Description description) {
        if (description == null) return Optional.empty();
        String text = description.getContent();
        if (text == null || text.isBlank()) return Optional.empty();
        return Optional.of(text);
    }

    /**
     * Arg-name docstring fallback for a user-declared directive. Walks the
     * user-typed arg list, matches the cursor against an arg-key node, and
     * surfaces the snapshot's {@link DirectiveShape}-side
     * {@code InputValueShape.description()} when present. Freshness-agnostic
     * by design — hovers prefer stale info over silence.
     */
    private static Optional<Hover> userArgHover(
        DirectiveShape shape, Directives.Directive directive, Point pos, WorkspaceFile file
    ) {
        for (var arg : directive.arguments()) {
            if (!arg.contains(pos)) continue;
            if (!Nodes.contains(arg.key(), pos)) continue;
            String argName = Nodes.text(arg.key(), file.source());
            for (var argShape : shape.args()) {
                if (!argShape.name().equals(argName)) continue;
                return argShape.description()
                    .filter(d -> !d.isBlank())
                    .map(text -> hover(file, arg.key(), text));
            }
        }
        return Optional.empty();
    }

    private static Optional<Hover> richerHover(
        LspVocabulary vocabulary, SchemaCoordinate coord,
        Directives.Directive directive, WorkspaceFile file, CompletionData catalog,
        LspSchemaSnapshot snapshot, Point pos, Node rangeNode
    ) {
        var behavior = vocabulary.behaviorAt(coord);
        if (behavior.isEmpty()) return Optional.empty();
        return switch (behavior.get()) {
            case Behavior.ClassNameBinding ignored -> classNameHover(file, catalog, rangeNode);
            case Behavior.MethodNameBinding mnb ->
                methodHover(vocabulary, directive, file, catalog, pos, rangeNode, mnb.classNameCoord());
            case Behavior.CatalogTableBinding ignored -> tableHover(file, catalog, rangeNode);
            case Behavior.CatalogColumnBinding ignored -> columnHover(directive, file, catalog, snapshot, rangeNode);
            case Behavior.CatalogFkBinding ignored -> fkHover(file, catalog, rangeNode);
            case Behavior.ArgMappingBinding ignored -> Optional.empty();
            case Behavior.ScalarTypeBinding ignored -> Optional.empty();
            case Behavior.NodeTypeBinding ignored -> nodeTypeHover(file, catalog, rangeNode);
        };
    }

    private static Optional<Hover> nodeTypeHover(
        WorkspaceFile file, CompletionData catalog, Node valueNode
    ) {
        String typeName = Nodes.unquote(Nodes.text(valueNode, file.source()));
        if (typeName.isEmpty()) return Optional.empty();
        var meta = catalog.nodeMetadata().get(typeName);
        if (meta == null) return Optional.empty();
        return Optional.of(hover(file, valueNode, formatNodeType(typeName, meta, catalog)));
    }

    private static String formatNodeType(
        String typeName, CompletionData.NodeMetadata meta, CompletionData catalog
    ) {
        var sb = new StringBuilder();
        sb.append("**Node** `").append(typeName).append("`");
        if (meta.typeId() != null) {
            sb.append("\n\nTypeId: `").append(meta.typeId()).append("`");
        }
        if (meta.keyColumns() != null && !meta.keyColumns().isEmpty()) {
            sb.append("\n\nKey columns:");
            for (String columnName : meta.keyColumns()) {
                sb.append("\n- `").append(columnName).append("`");
                String graphqlType = columnGraphqlType(catalog, columnName);
                if (graphqlType != null) {
                    sb.append(" — `").append(graphqlType).append("`");
                }
            }
        }
        return sb.toString();
    }

    private static String columnGraphqlType(CompletionData catalog, String columnName) {
        for (var table : catalog.tables()) {
            for (var column : table.columns()) {
                if (column.name().equalsIgnoreCase(columnName)) {
                    return column.graphqlType();
                }
            }
        }
        return null;
    }

    private static Optional<Hover> classNameHover(
        WorkspaceFile file, CompletionData catalog, Node valueNode
    ) {
        String fqn = Nodes.unquote(Nodes.text(valueNode, file.source()));
        if (fqn.isEmpty()) return Optional.empty();
        return findExternal(catalog, fqn).map(ref -> hover(file, valueNode, formatClass(ref)));
    }

    private static Optional<Hover> methodHover(
        LspVocabulary vocabulary,
        Directives.Directive directive, WorkspaceFile file, CompletionData catalog,
        Point pos, Node valueNode, SchemaCoordinate classNameCoord
    ) {
        String methodName = Nodes.unquote(Nodes.text(valueNode, file.source()));
        if (methodName.isEmpty()) return Optional.empty();
        var fqn = vocabulary.siblingStringAt(directive, pos, classNameCoord, file.source());
        if (fqn.isEmpty()) return Optional.empty();
        var refOpt = findExternal(catalog, fqn.get());
        if (refOpt.isEmpty()) return Optional.empty();
        return refOpt.get().methods().stream()
            .filter(m -> m.name().equals(methodName))
            .findFirst()
            .map(method -> hover(file, valueNode, formatMethod(refOpt.get(), method)));
    }

    private static Optional<Hover> tableHover(
        WorkspaceFile file, CompletionData catalog, Node valueNode
    ) {
        String name = Nodes.unquote(Nodes.text(valueNode, file.source()));
        return catalog.getTable(name).map(t -> hover(file, valueNode, formatTable(t)));
    }

    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "java-record-type-backs-record-class",
        reliesOn = "Renders the record component's displayType on hover under a @record-bound "
            + "Java record parent; trusts the classifier's RecordBacking projection without "
            + "re-reading the backing class."
    )
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "field-classification-payload-faithful",
        reliesOn = "Routes @field(name:) column hover through "
            + "FieldClassification.lspColumnDispatch(): Resolve(tableName) renders the column "
            + "metadata against the projected terminal table (the @reference terminal for "
            + "Column / ColumnReference / CompositeColumn / CompositeColumnReference); Silent "
            + "returns empty (InputUnbound, Unclassified, where the wrong-table hover would be "
            + "misleading); FallThrough routes back to the backing-driven dispatch for "
            + "non-column-bearing permits."
    )
    private static Optional<Hover> columnHover(
        Directives.Directive directive, WorkspaceFile file, CompletionData catalog,
        LspSchemaSnapshot snapshot, Node valueNode
    ) {
        String memberName = Nodes.unquote(Nodes.text(valueNode, file.source()));
        if (!(snapshot instanceof LspSchemaSnapshot.Built built)) return Optional.empty();
        var typeDecl = DeclarationKind.enclosing(directive.outer());
        if (typeDecl.isEmpty()) return Optional.empty();
        var typeName = TypeContext.declaredNameOf(typeDecl.get(), file.source());
        if (typeName.isEmpty()) return Optional.empty();
        var fieldName = TypeContext.enclosingFieldOrInputValueDefinition(directive.outer())
            .flatMap(fd -> TypeContext.fieldNameOf(fd, file.source()))
            .orElse(null);
        // R233: prefer the field classification's projected terminal table over the enclosing
        // type's backing for @reference path fields and the other column-bearing permits.
        // lspColumnDispatch() collapses the 30 permits onto three arms; Resolve and Silent
        // each return directly from this method, FallThrough drops through to the existing
        // backing-driven dispatch below. Snapshot-uncertainty (empty optional) also falls
        // through.
        if (fieldName != null) {
            var classification = built.fieldClassification(typeName.get(), fieldName);
            if (classification.isPresent()) {
                switch (classification.get().lspColumnDispatch()) {
                    case FieldClassification.LspColumnDispatch.Resolve(var tableName) -> {
                        return tableColumnHover(catalog, tableName, memberName, file, valueNode);
                    }
                    case FieldClassification.LspColumnDispatch.Silent ignored -> { return Optional.empty(); }
                    case FieldClassification.LspColumnDispatch.FallThrough ignored -> { /* fall through */ }
                }
            }
        }
        var backing = built.typesByName().get(typeName.get());
        if (backing == null) return Optional.empty();
        return switch (backing) {
            case TypeBackingShape.RecordBacking r -> slotHover(r.components(), memberName, file, valueNode);
            case TypeBackingShape.PojoBacking p -> slotHover(p.accessors(), memberName, file, valueNode);
            case TypeBackingShape.JooqRecordBacking.WithTable j ->
                tableColumnHover(catalog, j.tableName(), memberName, file, valueNode);
            case TypeBackingShape.JooqRecordBacking.Standalone ignored -> Optional.empty();
            case TypeBackingShape.TableBacking t ->
                tableColumnHover(catalog, t.tableName(), memberName, file, valueNode);
            case TypeBackingShape.NoBacking ignored -> Optional.empty();
        };
    }

    private static Optional<Hover> tableColumnHover(
        CompletionData catalog, String tableName, String columnName,
        WorkspaceFile file, Node valueNode
    ) {
        var tableOpt = catalog.getTable(tableName);
        if (tableOpt.isEmpty()) return Optional.empty();
        return tableOpt.get().columns().stream()
            .filter(c -> c.name().equalsIgnoreCase(columnName))
            .findFirst()
            .map(column -> hover(file, valueNode, formatColumn(tableName, column)));
    }

    private static Optional<Hover> slotHover(
        List<TypeBackingShape.MemberSlot> slots, String memberName, WorkspaceFile file, Node valueNode
    ) {
        return slots.stream()
            .filter(s -> s.name().equals(memberName))
            .findFirst()
            .map(slot -> hover(file, valueNode, "**" + slot.name() + "**: `" + slot.displayType() + "`"));
    }

    private static Optional<Hover> fkHover(
        WorkspaceFile file, CompletionData catalog, Node valueNode
    ) {
        String fkName = Nodes.unquote(Nodes.text(valueNode, file.source()));
        for (var table : catalog.tables()) {
            for (var ref : table.references()) {
                if (!ref.keyName().equals(fkName)) continue;
                String arrow = ref.inverse() ? "←" : "→";
                String content = "**Foreign key** `" + fkName + "`\n\n"
                    + "`" + table.name() + "` " + arrow + " `" + ref.targetTable() + "`";
                return Optional.of(hover(file, valueNode, content));
            }
        }
        return Optional.empty();
    }

    private static Optional<Hover> docstringHover(
        LspVocabulary vocabulary, SchemaCoordinate coord, WorkspaceFile file, Node rangeNode
    ) {
        return vocabulary.descriptionOf(coord)
            .filter(d -> !d.isBlank())
            .map(text -> hover(file, rangeNode, text));
    }

    /**
     * Returns the value node carrying the cursor — for a flat directive
     * arg the arg's value, for a nested object field the field's value
     * child, for a list element the element under the cursor. Used as
     * the hover range so the editor highlights the right span when
     * surfacing the popup; mirrors the
     * {@link LspVocabulary#leafCoordinates}-side contract that scalar
     * leaves never carry an enclosing {@code list_value} as their
     * value node.
     */
    private static Node valueNodeFor(Directives.Directive directive, Point pos) {
        for (var arg : directive.arguments()) {
            if (!arg.contains(pos)) continue;
            Node nested = innermostObjectFieldContaining(arg.value(), pos);
            if (nested != null) {
                Node valueNode = childOfKind(nested, "value");
                if (valueNode != null && Nodes.contains(valueNode, pos)) {
                    Node element = listElementContaining(valueNode, pos);
                    return element != null ? element : valueNode;
                }
            }
            if (Nodes.contains(arg.value(), pos)) {
                Node element = listElementContaining(arg.value(), pos);
                return element != null ? element : arg.value();
            }
        }
        return null;
    }

    /**
     * If {@code node} is or contains a {@code list_value}, returns the
     * non-punctuation child element the cursor sits inside. Returns null
     * when {@code node} is not list-shaped (so callers fall through to
     * the arg / object-field value).
     */
    private static Node listElementContaining(Node node, Point pos) {
        Node listValue = findListValue(node);
        if (listValue == null) return null;
        for (int i = 0; i < listValue.getChildCount(); i++) {
            Node child = listValue.getChild(i).orElse(null);
            if (child == null) continue;
            String type = child.getType();
            if ("[".equals(type) || "]".equals(type) || ",".equals(type) || "comma".equals(type)) continue;
            if (Nodes.contains(child, pos)) return child;
        }
        return null;
    }

    private static Node findListValue(Node node) {
        if (node == null) return null;
        if ("list_value".equals(node.getType())) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            Node child = node.getChild(i).orElse(null);
            if (child != null && "list_value".equals(child.getType())) return child;
        }
        return null;
    }

    private static Node innermostObjectFieldContaining(Node node, Point pos) {
        if (node == null || !Nodes.contains(node, pos)) return null;
        Node best = null;
        if ("object_field".equals(node.getType())) best = node;
        for (int i = 0; i < node.getChildCount(); i++) {
            Node descendant = innermostObjectFieldContaining(node.getChild(i).orElse(null), pos);
            if (descendant != null) best = descendant;
        }
        return best;
    }

    private static Node childOfKind(Node parent, String kind) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            Node child = parent.getChild(i).orElse(null);
            if (child != null && kind.equals(child.getType())) return child;
        }
        return null;
    }

    private static Optional<CompletionData.ExternalReference> findExternal(
        CompletionData catalog, String fqn
    ) {
        return catalog.externalReferences().stream()
            .filter(r -> r.className().equals(fqn))
            .findFirst();
    }

    private static String formatClass(CompletionData.ExternalReference ref) {
        return "**Class** `" + ref.className() + "`";
    }

    private static String formatMethod(
        CompletionData.ExternalReference ref, CompletionData.Method method
    ) {
        var sb = new StringBuilder();
        sb.append("**Method** `").append(method.name()).append("`")
          .append(" on `").append(ref.className()).append("`")
          .append("\n\n```\n")
          .append(method.returnType()).append(' ').append(method.name()).append('(');
        boolean missingNames = false;
        for (int i = 0; i < method.parameters().size(); i++) {
            if (i > 0) sb.append(", ");
            var p = method.parameters().get(i);
            sb.append(p.type()).append(' ');
            if (p.name() != null) {
                sb.append(p.name());
            } else {
                sb.append("arg").append(i);
                missingNames = true;
            }
        }
        sb.append(")\n```");
        if (missingNames) {
            sb.append("\n\n_Parameter names are unavailable; recompile with the `-parameters` flag to surface them._");
        }
        return sb.toString();
    }

    private static String formatTable(CompletionData.Table table) {
        var sb = new StringBuilder();
        sb.append("**Table** `").append(table.name()).append("`");
        if (!table.description().isEmpty()) {
            sb.append("\n\n").append(table.description());
        }
        sb.append("\n\n").append(table.columns().size()).append(" column")
            .append(table.columns().size() == 1 ? "" : "s")
            .append(", ").append(table.references().size()).append(" reference")
            .append(table.references().size() == 1 ? "" : "s").append(".");
        return sb.toString();
    }

    private static String formatColumn(String tableName, CompletionData.Column column) {
        var sb = new StringBuilder();
        sb.append("**Column** `").append(column.name()).append("`")
          .append(" on `").append(tableName).append("`")
          .append("\n\nType: `").append(column.graphqlType()).append("`")
          .append(column.nullable() ? " (nullable)" : " (not null)");
        if (!column.description().isEmpty()) {
            sb.append("\n\n").append(column.description());
        }
        return sb.toString();
    }

    private static Hover hover(WorkspaceFile file, Node rangeNode, String markdown) {
        var content = new MarkupContent(MarkupKind.MARKDOWN, markdown);
        var start = Positions.toLspPosition(file.source(), rangeNode.getStartByte());
        var end = Positions.toLspPosition(file.source(), rangeNode.getEndByte());
        return new Hover(content, new Range(start, end));
    }
}
