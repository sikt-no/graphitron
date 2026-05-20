package no.sikt.graphitron.lsp.inlay;

import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.Nodes;
import no.sikt.graphitron.lsp.parsing.Positions;
import no.sikt.graphitron.lsp.parsing.TypeContext;
import no.sikt.graphitron.lsp.state.InlayHintConfig;
import no.sikt.graphitron.lsp.state.WorkspaceFile;
import no.sikt.graphitron.rewrite.catalog.FieldClassification;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.catalog.TypeClassification;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * R160 — LSP inlay-hint provider. Two arms, each gated by an independent config toggle:
 * <ul>
 *   <li><b>Inferred-directive arm</b>: at {@code @table} / {@code @field} / {@code @reference}
 *       sites where the author omitted the canonical argument ({@code name:} for the first two,
 *       {@code path:} for the third), renders the resolved value as a ghost annotation.</li>
 *   <li><b>Classification arm</b>: at every field declaration and every object / interface /
 *       input / union type declaration, renders a compact label naming the classified
 *       variant (e.g. {@code "joined column"}, {@code "query field"}, {@code "node type"}).</li>
 * </ul>
 *
 * <p>The inferred-directive arm asks the tree-sitter AST whether the canonical argument is
 * present and consults the {@link FieldClassification} / {@link TypeClassification} projections
 * on the snapshot for the resolved value when absent. The classification arm dispatches over
 * the projection entries directly.
 *
 * <p>Stale-snapshot behaviour mirrors the existing hover arms: hints render under
 * {@link LspSchemaSnapshot.Built.Current} and {@link LspSchemaSnapshot.Built.Previous}
 * indistinguishably. Under {@link LspSchemaSnapshot.Unavailable}, no hints render.
 */
public final class InlayHints {

    private InlayHints() {}

    private static final Set<String> TYPE_DEFINITION_KINDS = Set.of(
        "object_type_definition",
        "interface_type_definition",
        "input_object_type_definition",
        "union_type_definition",
        "scalar_type_definition",
        "enum_type_definition"
    );

    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "field-classification-payload-faithful",
        reliesOn = "Reads tableName / columnName / joinPath off FieldClassification projection records "
            + "without dispatching back on the generator-side permit. The classification inlay arm uses "
            + "the projection identity to pick the label; the inferred-directive arm reads the resolved "
            + "column / FK chain off the same projection to render hint text."
    )
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "type-classification-payload-faithful",
        reliesOn = "Reads tableName off TypeClassification.Table / Node / TableInterface / TableInput "
            + "projection records without dispatching back on the generator-side permit. The "
            + "inferred-directive arm renders the resolved @table name from this projection."
    )
    public static List<InlayHint> compute(
        InlayHintConfig config, WorkspaceFile file, LspSchemaSnapshot snapshot, Range visibleRange
    ) {
        if (config == null || !config.anyEnabled()) return List.of();
        if (!(snapshot instanceof LspSchemaSnapshot.Built built)) return List.of();
        if (file == null || file.tree() == null) return List.of();

        var hints = new ArrayList<InlayHint>();
        Node root = file.tree().getRootNode();
        if (config.classification()) {
            collectClassificationHints(hints, file, built, root, visibleRange);
        }
        if (config.inferredDirectives()) {
            collectInferredDirectiveHints(hints, file, built, root, visibleRange);
        }
        return hints;
    }

    // ===== Classification arm =====

    private static void collectClassificationHints(
        List<InlayHint> out, WorkspaceFile file, LspSchemaSnapshot.Built built,
        Node root, Range visibleRange
    ) {
        walkTypeDefinitions(root, typeDef -> {
            if (!intersects(typeDef, visibleRange)) return;
            TypeContext.declaredNameOf(typeDef, file.source()).ifPresent(typeName -> {
                var classification = built.typeClassificationsByName().get(typeName);
                if (classification == null) return;
                Node nameNode = childOfKind(typeDef, "name");
                if (nameNode == null) return;
                out.add(makeHint(
                    file, nameNode,
                    LspClassificationLabels.projectionTypeLabel(classification),
                    InlayHintKind.Type));
            });
            // Walk field-definition children for this type
            Node fieldsContainer = childOfKind(typeDef, "fields_definition");
            if (fieldsContainer == null) {
                fieldsContainer = childOfKind(typeDef, "input_fields_definition");
            }
            if (fieldsContainer != null) {
                String parentTypeName = TypeContext.declaredNameOf(typeDef, file.source()).orElse(null);
                if (parentTypeName != null) {
                    for (int i = 0; i < fieldsContainer.getChildCount(); i++) {
                        Node child = fieldsContainer.getChild(i).orElse(null);
                        if (child == null) continue;
                        String kind = child.getType();
                        if (!"field_definition".equals(kind) && !"input_value_definition".equals(kind)) continue;
                        if (!intersects(child, visibleRange)) continue;
                        Node nameNode = childOfKind(child, "name");
                        if (nameNode == null) continue;
                        String fieldName = Nodes.text(nameNode, file.source());
                        var classification = built.fieldClassificationsByCoord()
                            .get(parentTypeName + "." + fieldName);
                        if (classification == null) continue;
                        out.add(makeHint(
                            file, nameNode,
                            LspClassificationLabels.projectionLabel(classification),
                            InlayHintKind.Type));
                    }
                }
            }
        });
    }

    // ===== Inferred-directive arm =====

    private static void collectInferredDirectiveHints(
        List<InlayHint> out, WorkspaceFile file, LspSchemaSnapshot.Built built,
        Node root, Range visibleRange
    ) {
        var directives = Directives.findAll(root);
        for (var directive : directives) {
            if (!intersects(directive.outer(), visibleRange)) continue;
            String directiveName = Nodes.text(directive.nameNode(), file.source());
            switch (directiveName) {
                case "table" -> renderInferredTableNameHint(out, file, built, directive);
                case "field" -> renderInferredFieldNameHint(out, file, built, directive);
                case "reference" -> renderInferredReferencePathHint(out, file, built, directive);
                default -> { /* not an inference target */ }
            }
        }
    }

    private static void renderInferredTableNameHint(
        List<InlayHint> out, WorkspaceFile file, LspSchemaSnapshot.Built built,
        Directives.Directive directive
    ) {
        if (hasNamedArg(directive, "name", file.source())) return;
        // Resolve the enclosing type and look up its classification for the resolved tableName.
        var enclosingType = TypeContext.enclosingTypeDefinition(directive.outer()).orElse(null);
        if (enclosingType == null) return;
        String typeName = TypeContext.declaredNameOf(enclosingType, file.source()).orElse(null);
        if (typeName == null) return;
        var classification = built.typeClassificationsByName().get(typeName);
        if (classification == null) return;
        String tableName = tableNameOf(classification);
        if (tableName == null) return;
        out.add(makeHint(file, directive.nameNode(),
            "name: \"" + tableName + "\"", InlayHintKind.Type));
    }

    private static void renderInferredFieldNameHint(
        List<InlayHint> out, WorkspaceFile file, LspSchemaSnapshot.Built built,
        Directives.Directive directive
    ) {
        if (hasNamedArg(directive, "name", file.source())) return;
        var enclosingField = TypeContext.enclosingFieldDefinition(directive.outer())
            .or(() -> enclosingInputValueDefinition(directive.outer())).orElse(null);
        if (enclosingField == null) return;
        var enclosingType = TypeContext.enclosingTypeDefinition(enclosingField).orElse(null);
        if (enclosingType == null) return;
        String typeName = TypeContext.declaredNameOf(enclosingType, file.source()).orElse(null);
        if (typeName == null) return;
        String fieldName = TypeContext.fieldNameOf(enclosingField, file.source())
            .orElseGet(() -> {
                Node nameNode = childOfKind(enclosingField, "name");
                return nameNode != null ? Nodes.text(nameNode, file.source()) : null;
            });
        if (fieldName == null) return;
        var classification = built.fieldClassificationsByCoord()
            .get(typeName + "." + fieldName);
        if (classification == null) return;
        String columnName = columnNameOf(classification);
        if (columnName == null) return;
        out.add(makeHint(file, directive.nameNode(),
            "name: \"" + columnName + "\"", InlayHintKind.Type));
    }

    private static void renderInferredReferencePathHint(
        List<InlayHint> out, WorkspaceFile file, LspSchemaSnapshot.Built built,
        Directives.Directive directive
    ) {
        if (hasNamedArg(directive, "path", file.source())) return;
        var enclosingField = TypeContext.enclosingFieldDefinition(directive.outer())
            .or(() -> enclosingInputValueDefinition(directive.outer())).orElse(null);
        if (enclosingField == null) return;
        var enclosingType = TypeContext.enclosingTypeDefinition(enclosingField).orElse(null);
        if (enclosingType == null) return;
        String typeName = TypeContext.declaredNameOf(enclosingType, file.source()).orElse(null);
        if (typeName == null) return;
        Node nameNode = childOfKind(enclosingField, "name");
        if (nameNode == null) return;
        String fieldName = Nodes.text(nameNode, file.source());
        var classification = built.fieldClassificationsByCoord()
            .get(typeName + "." + fieldName);
        if (classification == null) return;
        List<FieldClassification.FkStep> path = fkPathOf(classification);
        if (path == null || path.isEmpty()) return;
        StringBuilder sb = new StringBuilder("path: [");
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

    private static String tableNameOf(TypeClassification classification) {
        return switch (classification) {
            case TypeClassification.Table t -> t.tableName();
            case TypeClassification.Node n -> n.tableName();
            case TypeClassification.TableInterface ti -> ti.tableName();
            case TypeClassification.TableInput ti -> ti.tableName();
            default -> null;
        };
    }

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
            if ("input_value_definition".equals(node.getType())) {
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

    /**
     * Walks all type-definition nodes reachable from {@code root}. Tree-sitter-graphql produces
     * one definition node per SDL top-level definition; we traverse the {@code document}'s
     * children and filter by node type.
     */
    private static void walkTypeDefinitions(Node root, java.util.function.Consumer<Node> sink) {
        var seen = new LinkedHashSet<Node>();
        walk(root, node -> {
            if (TYPE_DEFINITION_KINDS.contains(node.getType()) && seen.add(node)) {
                sink.accept(node);
            }
        });
    }

    private static void walk(Node node, java.util.function.Consumer<Node> visitor) {
        visitor.accept(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            Node child = node.getChild(i).orElse(null);
            if (child != null) walk(child, visitor);
        }
    }

    private static Node childOfKind(Node parent, String kind) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            Node child = parent.getChild(i).orElse(null);
            if (child != null && kind.equals(child.getType())) return child;
        }
        return null;
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

    private static InlayHint makeHint(WorkspaceFile file, Node anchor, String label, InlayHintKind kind) {
        // Anchor the hint at the end of the anchor node (so the ghost annotation appears
        // immediately after the directive name or declaration name).
        Position pos = Positions.toLspPosition(file.source(), anchor.getEndByte());
        var hint = new InlayHint(pos, org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(label));
        hint.setKind(kind);
        hint.setPaddingLeft(true);
        return hint;
    }
}
