package no.sikt.graphitron.lsp.hover;

import graphql.language.Description;
import no.sikt.graphitron.lsp.facts.ClasspathMethods;
import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.DeclarationKind;
import no.sikt.graphitron.lsp.parsing.DirectivePolicy;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.Nodes;
import no.sikt.graphitron.lsp.parsing.Positions;
import no.sikt.graphitron.lsp.parsing.SchemaCoordinate;
import no.sikt.graphitron.lsp.parsing.TypeContext;
import no.sikt.graphitron.lsp.Descriptions;
import no.sikt.graphitron.lsp.state.DirectiveResolution;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.DirectiveShape;
import no.sikt.graphitron.rewrite.catalog.FieldClassification;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.catalog.SourceWalker;
import no.sikt.graphitron.rewrite.catalog.TypeBackingShape;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Range;
import org.jooq.Field;
import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Point;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.LIST_VALUE;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.VALUE;
import static no.sikt.graphitron.model.Tables.JAVA_CLASS_DECLARATION;
import static no.sikt.graphitron.model.Tables.JAVA_METHOD_DECLARATION;
import static no.sikt.graphitron.model.Tables.JVM_CLASS;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.select;

/**
 * Hover content keyed on schema-coordinate behaviors. Cursor on a known
 * coordinate ({@code @table(name:)}, {@code ExternalCodeReference.method},
 * {@code ReferenceElement.key}, ...) reveals catalog metadata: class FQNs,
 * method signatures, table descriptions, FK direction, and so on.
 *
 * <p>The two arms that answer about Java, a class name and a method name, read the fact store: the
 * classpath census for what exists, and the java-source family for what its declaration says about
 * itself. The rest of the arms still read the projection and move one at a time.
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
        FileSnapshot file, CompletionData catalog, Optional<StoreHandle> store,
        LspSchemaSnapshot snapshot, Point pos
    ) {
        // The bundled vocabulary is the only one in scope today; the
        // workspace's vocabulary is wired through GraphitronTextDocumentService.
        // An empty source index means the arms still on the projection fall back to the catalog's
        // build-derivable text (the production path passes the live index).
        return compute(LspVocabulary.load(), file, catalog, store,
            SourceWalker.Index.EMPTY, snapshot, pos, false);
    }

    /**
     * Canonical hover entry point. {@code store} is this document's graph, scoped and inside the
     * caller's read transaction, and the arms that have migrated read everything through it: the
     * class census and its methods, and the Javadoc beneath both, which is a join to the
     * {@code java_} family on the source's own cadence. {@code sourceIndex} is what the arms still
     * on the projection read the same Javadoc from, and retires with the last of them.
     *
     * <p>{@code classificationHoverEnabled} gates the parallel {@link DeclarationHovers} dispatch on
     * SDL declaration coordinates. Default false preserves the no-behaviour-change-by-default
     * contract; the document service flips it on per
     * {@link no.sikt.graphitron.lsp.state.Workspace#inlayHintConfig()}.
     */
    public static Optional<Hover> compute(
        LspVocabulary vocabulary, FileSnapshot file, CompletionData catalog,
        Optional<StoreHandle> store, SourceWalker.Index sourceIndex, LspSchemaSnapshot snapshot,
        Point pos, boolean classificationHoverEnabled
    ) {
        var directiveOpt = Directives.findContaining(file.tree().getRootNode(), pos);
        if (directiveOpt.isEmpty()) {
            // No directive at the cursor; try the classification-hover arm on SDL
            // declaration coordinates (field-definition / type-definition name tokens).
            // Pass the catalog and source index so the arm overlays the bound
            // jOOQ class / column / member Javadoc beneath the classification block.
            if (classificationHoverEnabled) {
                return DeclarationHovers.compute(file, catalog, sourceIndex, snapshot, pos);
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
            var richer = richerHover(
                vocabulary, coord, directive, file, catalog, store, sourceIndex, snapshot, pos, rangeNode);
            if (richer.isPresent()) return richer;
            // SDL docstring on the coordinate. Empty if the parsed
            // definition has no description (rare in directives.graphqls).
            var bundled = docstringHover(vocabulary, coord, file, rangeNode);
            if (bundled.isPresent()) return bundled;
        }

        // User-arm fallback: only on User resolution. Gating here preserves
        // bundled-shadows-snapshot precedence: for bundled directives, a
        // missing bundled arg description stays empty rather than leaking
        // through to a shadow snapshot entry.
        if (resolution instanceof DirectiveResolution.User user) {
            return userArgHover(user.shape(), directive, pos, file);
        }
        return Optional.empty();
    }

    private static Optional<Hover> directiveNameHover(
        DirectiveResolution resolution, Directives.Directive directive, FileSnapshot file
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
        DirectiveShape shape, Directives.Directive directive, Point pos, FileSnapshot file
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
        Directives.Directive directive, FileSnapshot file, CompletionData catalog,
        Optional<StoreHandle> store, SourceWalker.Index sourceIndex, LspSchemaSnapshot snapshot,
        Point pos, Node rangeNode
    ) {
        var behavior = vocabulary.behaviorAt(coord);
        if (behavior.isEmpty()) return Optional.empty();
        return switch (behavior.get()) {
            // @record carve-out: @record is deprecated/ignored, so its className slot is not a live
            // binding and gets no live-binding hover. Its ExternalCodeReference.className coordinate
            // is shared with @enum, so the carve-out keys on the directive name (see DirectivePolicy).
            // Falls through to the SDL docstring hover at the call site.
            case Behavior.ClassNameBinding ignored ->
                DirectivePolicy.bindsLiveClass(Nodes.text(directive.nameNode(), file.source()))
                    ? store.flatMap(s -> classNameHover(file, s, rangeNode))
                    : Optional.empty();
            case Behavior.MethodNameBinding mnb ->
                store.flatMap(s -> methodHover(
                    vocabulary, directive, file, s, pos, rangeNode, mnb.classNameCoord()));
            case Behavior.CatalogTableBinding ignored -> tableHover(file, catalog, sourceIndex, rangeNode);
            case Behavior.CatalogColumnBinding ignored -> columnHover(directive, file, catalog, sourceIndex, snapshot, rangeNode);
            case Behavior.CatalogFkBinding ignored -> fkHover(file, catalog, rangeNode);
            case Behavior.ArgMappingBinding ignored -> Optional.empty();
            case Behavior.ScalarTypeBinding ignored -> Optional.empty();
            case Behavior.NodeTypeBinding ignored -> nodeTypeHover(file, catalog, rangeNode);
        };
    }

    private static Optional<Hover> nodeTypeHover(
        FileSnapshot file, CompletionData catalog, Node valueNode
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

    /**
     * The class the cursor names, if this graph's classpath census holds it, with the Javadoc its
     * source declaration carries. One query answers both: presence is a {@code jvm_class} row inside
     * the graph's read set, and the description is a correlated select into the {@code java_} family
     * by name, the only join that reaches a doc comment at all. Absence falls through to the SDL
     * docstring on the coordinate, which is what the author sees for a class nothing has compiled.
     */
    private static Optional<Hover> classNameHover(FileSnapshot file, StoreHandle store, Node valueNode) {
        String fqn = Nodes.unquote(Nodes.text(valueNode, file.source()));
        if (fqn.isEmpty()) return Optional.empty();
        var row = store.dsl()
            .select(classJavadocOf(JVM_CLASS.CLASS_NAME))
            .from(JVM_CLASS)
            .where(store.reads(JVM_CLASS.SOURCE_NAME))
            .and(JVM_CLASS.CLASS_NAME.eq(fqn))
            // A class reachable under two classpath entries is two rows with one answer.
            .orderBy(JVM_CLASS.SOURCE_NAME)
            .limit(1)
            .fetchOne();
        if (row == null) return Optional.empty();
        var content = new StringBuilder("**Class** `").append(fqn).append("`");
        String javadoc = row.value1();
        if (javadoc != null && !javadoc.isBlank()) {
            content.append("\n\n").append(javadoc);
        }
        return Optional.of(hover(file, valueNode, content.toString()));
    }

    /**
     * Every overload the named method has, not the first one that matches. SDL names a method by
     * name alone, so which overload an author meant is not a question the census can answer; the
     * projection resolved to whichever entry its list happened to hold first and hid the rest, and
     * showing all of them is what the relation says.
     */
    private static Optional<Hover> methodHover(
        LspVocabulary vocabulary, Directives.Directive directive, FileSnapshot file,
        StoreHandle store, Point pos, Node valueNode, SchemaCoordinate classNameCoord
    ) {
        String methodName = Nodes.unquote(Nodes.text(valueNode, file.source()));
        if (methodName.isEmpty()) return Optional.empty();
        var fqn = vocabulary.siblingStringAt(directive, pos, classNameCoord, file.source());
        if (fqn.isEmpty()) return Optional.empty();
        var overloads = ClasspathMethods.named(store, fqn.get(), methodName);
        if (overloads.isEmpty()) return Optional.empty();
        return Optional.of(hover(file, valueNode,
            formatMethod(fqn.get(), methodName, overloads, javadocByArity(store, fqn.get(), methodName))));
    }

    /**
     * The doc comment a source declaration carries, as a correlated scalar select against a class
     * name on the query's own side. A correlated select rather than a join because
     * {@code java_class_declaration} is keyed on {@code (file, class_name)}: one FQN declared in two
     * files is two rows, which a join would multiply the answer by. The first declaration in file
     * order that carries a comment wins, which is arbitrary but stated, deterministic, and a property
     * of a malformed source tree rather than of a census.
     */
    private static Field<String> classJavadocOf(Field<String> className) {
        return field(select(JAVA_CLASS_DECLARATION.JAVADOC)
            .from(JAVA_CLASS_DECLARATION)
            .where(JAVA_CLASS_DECLARATION.CLASS_NAME.eq(className))
            .and(JAVA_CLASS_DECLARATION.JAVADOC.isNotNull())
            .orderBy(JAVA_CLASS_DECLARATION.FILE)
            .limit(1));
    }

    /**
     * Doc comments for one method name, keyed by the arity the source declares. Arity is what the
     * two populations can be joined on: a parse reads unqualified parameter types as written where
     * the classfile carries erased ones, so {@code java_method_declaration} counts parameters and
     * {@code jvm_method} spells a descriptor, and the count is their only common ground. Two
     * same-arity overloads therefore share one comment, the first in (file, declaration) order, the
     * same collapse the projection's source index made before this read replaced it.
     */
    private static Map<Integer, String> javadocByArity(
        StoreHandle store, String classFqn, String methodName
    ) {
        var byArity = new HashMap<Integer, String>();
        var rows = store.dsl()
            .select(JAVA_METHOD_DECLARATION.PARAMETER_COUNT, JAVA_METHOD_DECLARATION.JAVADOC)
            .from(JAVA_METHOD_DECLARATION)
            .where(JAVA_METHOD_DECLARATION.CLASS_NAME.eq(classFqn))
            .and(JAVA_METHOD_DECLARATION.METHOD_NAME.eq(methodName))
            .and(JAVA_METHOD_DECLARATION.JAVADOC.isNotNull())
            .orderBy(JAVA_METHOD_DECLARATION.FILE, JAVA_METHOD_DECLARATION.ORDINAL)
            .fetch();
        for (var row : rows) {
            byArity.putIfAbsent(row.value1(), row.value2());
        }
        return byArity;
    }

    private static Optional<Hover> tableHover(
        FileSnapshot file, CompletionData catalog, SourceWalker.Index sourceIndex, Node valueNode
    ) {
        String name = Nodes.unquote(Nodes.text(valueNode, file.source()));
        return catalog.getTable(name).map(t -> hover(file, valueNode, formatTable(t, sourceIndex)));
    }

    private static Optional<Hover> columnHover(
        Directives.Directive directive, FileSnapshot file, CompletionData catalog,
        SourceWalker.Index sourceIndex, LspSchemaSnapshot snapshot, Node valueNode
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
        // Prefer the field classification's projected terminal table over the enclosing
        // type's backing for @reference path fields and the other column-bearing permits.
        // lspColumnDispatch() collapses the permits onto three arms; Resolve and Silent
        // each return directly from this method, FallThrough drops through to the existing
        // backing-driven dispatch below. Snapshot-uncertainty (empty optional) also falls
        // through.
        if (fieldName != null) {
            var classification = built.fieldClassification(typeName.get(), fieldName);
            if (classification.isPresent()) {
                switch (classification.get().lspColumnDispatch()) {
                    case FieldClassification.LspColumnDispatch.Resolve(var tableName) -> {
                        return tableColumnHover(catalog, tableName, memberName, file, valueNode, sourceIndex);
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
                tableColumnHover(catalog, j.tableName(), memberName, file, valueNode, sourceIndex);
            case TypeBackingShape.JooqRecordBacking.Standalone ignored -> Optional.empty();
            case TypeBackingShape.TableBacking t ->
                tableColumnHover(catalog, t.tableName(), memberName, file, valueNode, sourceIndex);
            case TypeBackingShape.NoBacking ignored -> Optional.empty();
        };
    }

    private static Optional<Hover> tableColumnHover(
        CompletionData catalog, String tableName, String columnName,
        FileSnapshot file, Node valueNode, SourceWalker.Index sourceIndex
    ) {
        var tableOpt = catalog.getTable(tableName);
        if (tableOpt.isEmpty()) return Optional.empty();
        var table = tableOpt.get();
        return table.columns().stream()
            .filter(c -> c.name().equalsIgnoreCase(columnName))
            .findFirst()
            .map(column -> hover(file, valueNode, formatColumn(table, column, sourceIndex)));
    }

    private static Optional<Hover> slotHover(
        List<TypeBackingShape.MemberSlot> slots, String memberName, FileSnapshot file, Node valueNode
    ) {
        return slots.stream()
            .filter(s -> s.name().equals(memberName))
            .findFirst()
            .map(slot -> hover(file, valueNode, "**" + slot.name() + "**: `" + slot.displayType() + "`"));
    }

    private static Optional<Hover> fkHover(
        FileSnapshot file, CompletionData catalog, Node valueNode
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
        LspVocabulary vocabulary, SchemaCoordinate coord, FileSnapshot file, Node rangeNode
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
            Node nested = Nodes.innermostObjectFieldContaining(arg.value(), pos);
            if (nested != null) {
                Node valueNode = Nodes.childOfKind(nested, VALUE);
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
        if (LIST_VALUE.matches(node)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            Node child = node.getChild(i).orElse(null);
            if (LIST_VALUE.matches(child)) return child;
        }
        return null;
    }

    /**
     * One heading for the name, then a section per overload: its doc comment where the source
     * declares one, and its signature. With a single overload that reads exactly as the one-method
     * hover always did; with several the author sees each signature rather than one the surface
     * picked. The {@code -parameters} note is about the build rather than about any one method, so it
     * is appended once when any signature had to fall back to placeholder names.
     */
    private static String formatMethod(
        String classFqn, String methodName, List<ClasspathMethods.Method> overloads,
        Map<Integer, String> javadocByArity
    ) {
        var sb = new StringBuilder();
        sb.append("**Method** `").append(methodName).append("`")
          .append(" on `").append(classFqn).append("`");
        boolean missingNames = false;
        for (var method : overloads) {
            String javadoc = javadocByArity.get(method.arity());
            if (javadoc != null && !javadoc.isBlank()) {
                sb.append("\n\n").append(javadoc);
            }
            sb.append("\n\n```\n").append(method.signature()).append("\n```");
            missingNames |= method.hasUnnamedParameters();
        }
        if (missingNames) {
            sb.append("\n\n_Parameter names are unavailable; recompile with the `-parameters` flag to surface them._");
        }
        return sb.toString();
    }

    private static String formatTable(CompletionData.Table table, SourceWalker.Index sourceIndex) {
        var sb = new StringBuilder();
        sb.append("**Table** `").append(table.name()).append("`");
        String description = Descriptions.ofTable(table, sourceIndex);
        if (!description.isEmpty()) {
            sb.append("\n\n").append(description);
        }
        sb.append("\n\n").append(table.columns().size()).append(" column")
            .append(table.columns().size() == 1 ? "" : "s")
            .append(", ").append(table.references().size()).append(" reference")
            .append(table.references().size() == 1 ? "" : "s").append(".");
        return sb.toString();
    }

    private static String formatColumn(
        CompletionData.Table table, CompletionData.Column column, SourceWalker.Index sourceIndex
    ) {
        var sb = new StringBuilder();
        sb.append("**Column** `").append(column.name()).append("`")
          .append(" on `").append(table.name()).append("`")
          .append("\n\nType: `").append(column.graphqlType()).append("`")
          .append(column.nullable() ? " (nullable)" : " (not null)");
        String description = Descriptions.ofColumn(table, column, sourceIndex);
        if (!description.isEmpty()) {
            sb.append("\n\n").append(description);
        }
        return sb.toString();
    }

    private static Hover hover(FileSnapshot file, Node rangeNode, String markdown) {
        var content = new MarkupContent(MarkupKind.MARKDOWN, markdown);
        var start = Positions.toLspPosition(file.source(), rangeNode.getStartByte());
        var end = Positions.toLspPosition(file.source(), rangeNode.getEndByte());
        return new Hover(content, new Range(start, end));
    }
}
