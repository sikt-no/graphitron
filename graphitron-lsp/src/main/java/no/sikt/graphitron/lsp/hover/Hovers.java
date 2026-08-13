package no.sikt.graphitron.lsp.hover;

import graphql.language.Description;
import no.sikt.graphitron.lsp.facts.CatalogColumns;
import no.sikt.graphitron.lsp.facts.CatalogKeys;
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
import org.jooq.Record2;
import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Point;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.LIST_VALUE;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.VALUE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_KEY_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLE;
import static no.sikt.graphitron.model.Tables.JAVA_CLASS_DECLARATION;
import static no.sikt.graphitron.model.Tables.JAVA_METHOD_DECLARATION;
import static no.sikt.graphitron.model.Tables.JVM_CLASS;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_REFERENTIAL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectCount;

/**
 * Hover content keyed on schema-coordinate behaviors. Cursor on a known
 * coordinate ({@code @table(name:)}, {@code ExternalCodeReference.method},
 * {@code ReferenceElement.key}, ...) reveals catalog metadata: class FQNs,
 * method signatures, table descriptions, FK direction, and so on.
 *
 * <p>Every coordinate arm reads the fact store: the classpath census and the java-source family for
 * the two arms that answer about Java, the catalog census for the three that answer about the
 * database, and the graph's own {@code @node} declarations for the fourth. What still comes from the
 * projection is the classification snapshot, which answers which table a column site belongs to, and
 * the declaration-name arm around the coordinate dispatch.
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

    /**
     * Every arm this entry point can reach, which is every arm but the declaration-name one, reads
     * either the store or the classification snapshot, so it takes no projection and no source
     * index. The bundled vocabulary is the only one in scope today; the workspace's vocabulary is
     * wired through {@code GraphitronTextDocumentService}.
     */
    public static Optional<Hover> compute(
        FileSnapshot file, Optional<StoreHandle> store, LspSchemaSnapshot snapshot, Point pos
    ) {
        return compute(LspVocabulary.load(), file, CompletionData.empty(), store,
            SourceWalker.Index.EMPTY, snapshot, pos, false);
    }

    /**
     * Canonical hover entry point. {@code store} is this document's graph, scoped and inside the
     * caller's read transaction, and every coordinate arm reads through it: the class census and its
     * methods, the catalog census behind the table, column and key arms, the graph's {@code @node}
     * declarations, and the Javadoc beneath several of them, which is a join to the {@code java_}
     * family on the source's own cadence. {@code catalog} and {@code sourceIndex} are what the
     * declaration-name arm still reads, and retire with it.
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
                vocabulary, coord, directive, file, store, snapshot, pos, rangeNode);
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

    /**
     * Every coordinate arm reads the store; what is left on the projection is the classification
     * snapshot, which answers which table a site's columns belong to rather than what the database
     * holds.
     */
    private static Optional<Hover> richerHover(
        LspVocabulary vocabulary, SchemaCoordinate coord,
        Directives.Directive directive, FileSnapshot file,
        Optional<StoreHandle> store, LspSchemaSnapshot snapshot,
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
            case Behavior.CatalogTableBinding ignored -> store.flatMap(s -> tableHover(file, s, rangeNode));
            // The column arm takes the store as an option rather than behind a flatMap: its
            // record- and POJO-backed sites answer from the classification snapshot's member
            // slots, and only the table-backed ones are a census read.
            case Behavior.CatalogColumnBinding ignored ->
                columnHover(directive, file, store, snapshot, rangeNode);
            case Behavior.CatalogFkBinding ignored -> store.flatMap(s -> fkHover(file, s, rangeNode));
            case Behavior.ArgMappingBinding ignored -> Optional.empty();
            case Behavior.ScalarTypeBinding ignored -> Optional.empty();
            case Behavior.NodeTypeBinding ignored -> store.flatMap(s -> nodeTypeHover(file, s, rangeNode));
        };
    }

    /**
     * A {@code @node} type, its {@code typeId} and its key columns, all graph-keyed: a
     * {@code @node} declaration is a fact about one graph's SDL, so the scope is the relation's own
     * {@code graph_name} rather than a source membership.
     */
    private static Optional<Hover> nodeTypeHover(
        FileSnapshot file, StoreHandle store, Node valueNode
    ) {
        String typeName = Nodes.unquote(Nodes.text(valueNode, file.source()));
        if (typeName.isEmpty()) return Optional.empty();
        // The declaration and its ordered key columns in one query. The left join keeps a @node
        // that named none, whose key-column side is absent rather than empty: the type-name and
        // catalog-primary-key fallbacks are derivations, and a hover reports what was written.
        var rows = store.dsl()
            .select(GRAPHITRON_NODE.TYPE_ID, GRAPHITRON_NODE_KEY_COLUMN.COLUMN_REF)
            .from(GRAPHITRON_NODE)
            .leftJoin(GRAPHITRON_NODE_KEY_COLUMN)
            .on(GRAPHITRON_NODE_KEY_COLUMN.GRAPH_NAME.eq(GRAPHITRON_NODE.GRAPH_NAME))
            .and(GRAPHITRON_NODE_KEY_COLUMN.TYPE_NAME.eq(GRAPHITRON_NODE.TYPE_NAME))
            .where(GRAPHITRON_NODE.GRAPH_NAME.eq(store.graphName()))
            .and(GRAPHITRON_NODE.TYPE_NAME.eq(typeName))
            .orderBy(GRAPHITRON_NODE_KEY_COLUMN.POSITION)
            .fetch();
        if (rows.isEmpty()) return Optional.empty();
        var keyColumns = rows.stream().map(Record2::value2).filter(Objects::nonNull).toList();
        return Optional.of(hover(file, valueNode, formatNodeType(typeName, rows.getFirst().value1(),
            keyColumns, keyColumns.isEmpty() ? List.of() : nodeColumns(store, typeName))));
    }

    /**
     * The columns of the table the node type binds to, for typing its key columns. The projection
     * looked a key column up by name across every table in the catalog and took the first hit, which
     * on a name as common as {@code id} answered from whichever table came first; a key column of a
     * node is a column of that node's own table or of nothing.
     *
     * <p>The binding is {@code @table}'s {@code name} argument as written, and the type-name
     * fallback when it is absent is the same derivation the generator applies. A qualifier is
     * dropped, since {@code sql_table} keys the schema separately.
     */
    private static List<CatalogColumns.Column> nodeColumns(StoreHandle store, String typeName) {
        String tableRef = store.dsl()
            .select(GRAPHITRON_TABLE.TABLE_REF)
            .from(GRAPHITRON_TABLE)
            .where(GRAPHITRON_TABLE.GRAPH_NAME.eq(store.graphName()))
            .and(GRAPHITRON_TABLE.TYPE_NAME.eq(typeName))
            .fetchOne(GRAPHITRON_TABLE.TABLE_REF);
        String tableName = tableRef == null || tableRef.isBlank() ? typeName : tableRef;
        int dot = tableName.lastIndexOf('.');
        return CatalogColumns.of(store, dot < 0 ? tableName : tableName.substring(dot + 1));
    }

    private static String formatNodeType(
        String typeName, String typeId, List<String> keyColumns, List<CatalogColumns.Column> columns
    ) {
        var sb = new StringBuilder();
        sb.append("**Node** `").append(typeName).append("`");
        if (typeId != null) {
            sb.append("\n\nTypeId: `").append(typeId).append("`");
        }
        if (!keyColumns.isEmpty()) {
            sb.append("\n\nKey columns:");
            for (String columnRef : keyColumns) {
                sb.append("\n- `").append(columnRef).append("`");
                columns.stream().filter(c -> c.isNamed(columnRef)).findFirst()
                    .ifPresent(c -> sb.append(" (`").append(c.bindingType()).append("`)"));
            }
        }
        return sb.toString();
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

    /**
     * The table the cursor names, with what the census holds about it: its description, and how many
     * columns and foreign keys it has. One query, the two counts as correlated subselects, so a
     * table with fifty columns costs the same as one with two and neither count can multiply the
     * row it is counted for.
     *
     * <p>A name two schemas both declare answers for both. {@code sql_table} records every table
     * every schema declares and its own charter says resolving an unqualified name against them is a
     * derivation, so this arm reports rather than picks; the projection answered from whichever
     * table its list happened to hold first.
     */
    private static Optional<Hover> tableHover(FileSnapshot file, StoreHandle store, Node valueNode) {
        String name = Nodes.unquote(Nodes.text(valueNode, file.source()));
        if (name.isEmpty()) return Optional.empty();
        var rows = store.dsl()
            .select(SQL_TABLE.TABLE_SCHEMA, SQL_TABLE.DESCRIPTION, classJavadocOf(SQL_TABLE.CLASS_FQN),
                columnCount(), referenceCount(store))
            .from(SQL_TABLE)
            .where(store.reads(SQL_TABLE.SOURCE_NAME))
            .and(SQL_TABLE.TABLE_NAME.equalIgnoreCase(name))
            .orderBy(SQL_TABLE.TABLE_SCHEMA)
            .fetch();
        if (rows.isEmpty()) return Optional.empty();
        var sb = new StringBuilder("**Table** `").append(name).append("`");
        boolean ambiguous = rows.size() > 1;
        for (var row : rows) {
            if (ambiguous) {
                sb.append("\n\nIn schema `").append(row.value1()).append("`:");
            }
            // The database comment wins over the generated class Javadoc, which for a table names
            // the table back at the reader. The column arm inverts this, for the reason stated
            // there; the precedence is per relation and belongs to the surface, not to a view.
            String description = row.value2() != null && !row.value2().isBlank()
                ? row.value2()
                : (row.value3() == null ? "" : row.value3());
            if (!description.isBlank()) {
                sb.append("\n\n").append(description);
            }
            sb.append("\n\n").append(count(row.value4(), "column"))
              .append(", ").append(count(row.value5(), "reference")).append(".");
        }
        return Optional.of(hover(file, valueNode, sb.toString()));
    }

    /** How many columns the table on the query's own side declares. */
    private static Field<Integer> columnCount() {
        return field(selectCount().from(SQL_COLUMN)
            .where(SQL_COLUMN.SOURCE_NAME.eq(SQL_TABLE.SOURCE_NAME))
            .and(SQL_COLUMN.TABLE_SCHEMA.eq(SQL_TABLE.TABLE_SCHEMA))
            .and(SQL_COLUMN.TABLE_NAME.eq(SQL_TABLE.TABLE_NAME)));
    }

    /**
     * How many foreign keys touch the table, in either direction. Not scoped to the table's own
     * source, because a key declared in another generated package against this table is still a key
     * touching it; the graph's read set is the scope, as everywhere else.
     */
    private static Field<Integer> referenceCount(StoreHandle store) {
        var touches = SQL_REFERENTIAL_CONSTRAINT.TABLE_SCHEMA.eq(SQL_TABLE.TABLE_SCHEMA)
            .and(SQL_REFERENTIAL_CONSTRAINT.TABLE_NAME.eq(SQL_TABLE.TABLE_NAME))
            .or(SQL_REFERENTIAL_CONSTRAINT.REFERENCED_SCHEMA.eq(SQL_TABLE.TABLE_SCHEMA)
                .and(SQL_REFERENTIAL_CONSTRAINT.REFERENCED_TABLE.eq(SQL_TABLE.TABLE_NAME)));
        return field(selectCount().from(SQL_REFERENTIAL_CONSTRAINT)
            .where(store.reads(SQL_REFERENTIAL_CONSTRAINT.SOURCE_NAME))
            .and(touches));
    }

    private static String count(int value, String noun) {
        return value + " " + noun + (value == 1 ? "" : "s");
    }

    private static Optional<Hover> columnHover(
        Directives.Directive directive, FileSnapshot file, Optional<StoreHandle> store,
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
                        return tableColumnHover(store, tableName, memberName, file, valueNode);
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
                tableColumnHover(store, j.tableName(), memberName, file, valueNode);
            case TypeBackingShape.JooqRecordBacking.Standalone ignored -> Optional.empty();
            case TypeBackingShape.TableBacking t ->
                tableColumnHover(store, t.tableName(), memberName, file, valueNode);
            case TypeBackingShape.NoBacking ignored -> Optional.empty();
        };
    }

    /**
     * The named column of the named table, under either of the two names the census carries for it.
     * The projection held only the jOOQ field name, so an author who wrote the SQL name got a hover
     * only where the two agree up to case; the diagnostic arm already accepts both spellings, and
     * the census is what lets this one agree with it.
     */
    private static Optional<Hover> tableColumnHover(
        Optional<StoreHandle> store, String tableName, String columnName,
        FileSnapshot file, Node valueNode
    ) {
        return store.flatMap(handle -> {
            var matches = CatalogColumns.of(handle, tableName).stream()
                .filter(column -> column.isNamed(columnName))
                .toList();
            return matches.isEmpty()
                ? Optional.<Hover>empty()
                : Optional.of(hover(file, valueNode, formatColumn(tableName, matches)));
        });
    }

    private static Optional<Hover> slotHover(
        List<TypeBackingShape.MemberSlot> slots, String memberName, FileSnapshot file, Node valueNode
    ) {
        return slots.stream()
            .filter(s -> s.name().equals(memberName))
            .findFirst()
            .map(slot -> hover(file, valueNode, "**" + slot.name() + "**: `" + slot.displayType() + "`"));
    }

    /**
     * The foreign key the cursor names, in the direction the census declares it: from the table that
     * holds the key to the table it references, which is the same reading whatever the enclosing
     * type is bound to. The projection matched the generated constant exactly, so an author who
     * wrote the SQL constraint name, which is the spelling the manual teaches and the completion arm
     * offers, got nothing; {@link CatalogKeys#named} matches the spellings the generator's own
     * resolver accepts.
     */
    private static Optional<Hover> fkHover(FileSnapshot file, StoreHandle store, Node valueNode) {
        String spelling = Nodes.unquote(Nodes.text(valueNode, file.source()));
        if (spelling.isEmpty()) return Optional.empty();
        var keys = CatalogKeys.named(store, spelling);
        if (keys.isEmpty()) return Optional.empty();
        var sb = new StringBuilder("**Foreign key** `").append(spelling).append("`");
        boolean ambiguous = keys.size() > 1;
        for (var key : keys) {
            sb.append("\n\n`").append(key.table()).append("` → `")
              .append(key.referencedTable()).append('`');
            if (ambiguous) {
                sb.append(" (schema `").append(key.schema()).append("`)");
            }
        }
        var constants = keys.stream().map(CatalogKeys.Key::constant)
            .filter(constant -> !constant.isEmpty() && !constant.equalsIgnoreCase(spelling))
            .distinct().toList();
        if (!constants.isEmpty()) {
            sb.append("\n\nAlso resolves under the generated constant `")
              .append(String.join("`, `", constants)).append("`.");
        }
        return Optional.of(hover(file, valueNode, sb.toString()));
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

    /**
     * One heading, then a section per matching column. Both of the column's types are rendered,
     * because the census carries both and neither derives from the other: the SQL type is what the
     * database declares and the Java type is what jOOQ binds it to, which is the one a resolver's
     * signature will be written against. The projection carried only the second, under a name that
     * called it a GraphQL type.
     */
    private static String formatColumn(String tableName, List<CatalogColumns.Column> matches) {
        var sb = new StringBuilder();
        sb.append("**Column** `").append(matches.getFirst().columnName()).append("`")
          .append(" on `").append(tableName).append("`");
        boolean ambiguous = matches.size() > 1;
        for (var column : matches) {
            if (ambiguous) {
                sb.append("\n\nIn schema `").append(column.schema()).append("`:");
            }
            sb.append("\n\nSQL type: `").append(column.sqlType()).append("`")
              .append(column.nullable() ? " (nullable)" : " (not null)")
              .append("\n\nJava type: `").append(column.bindingType()).append("`");
            // The generated field's Javadoc wins over the database comment, inverting the table
            // arm: a column's generated Javadoc carries the qualified column name and, where the
            // database has a comment, the comment too, so it is the richer of the two.
            String description = !column.javadoc().isEmpty() ? column.javadoc() : column.comment();
            if (!description.isBlank()) {
                sb.append("\n\n").append(description);
            }
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
