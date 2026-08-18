package no.sikt.graphitron.lsp.hover;

import no.sikt.graphitron.lsp.facts.CatalogColumns;
import no.sikt.graphitron.lsp.facts.CatalogKeys;
import no.sikt.graphitron.lsp.facts.CatalogTable;
import no.sikt.graphitron.lsp.facts.ClassMemberSlots;
import no.sikt.graphitron.lsp.facts.ClasspathMethods;
import no.sikt.graphitron.lsp.facts.FieldColumnTable;
import no.sikt.graphitron.lsp.facts.SdlDescriptions;
import no.sikt.graphitron.lsp.facts.SourceDeclarations;
import no.sikt.graphitron.lsp.facts.TypeMemberScope;
import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.DeclarationKind;
import no.sikt.graphitron.lsp.parsing.DirectivePolicy;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.Nodes;
import no.sikt.graphitron.lsp.parsing.Positions;
import no.sikt.graphitron.lsp.parsing.SchemaCoordinate;
import no.sikt.graphitron.lsp.parsing.TypeContext;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Range;
import org.jooq.Field;
import org.jooq.Record2;
import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Point;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.LIST_VALUE;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.VALUE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_KEY_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLE;
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
 * <p>Every arm reads the fact store, the declaration-name arm around the coordinate dispatch
 * included: the classpath census and the java-source family for the arms that answer about Java, the
 * catalog census for those that answer about the database, and the graph's own {@code @node}
 * declarations for one more. The declaration-name arm's classification block is the claim stratum's,
 * so it renders with no generator pass behind it; what still comes from the projection is one
 * question inside the description overlay beneath it, which resolves a declaration through
 * {@link no.sikt.graphitron.lsp.parsing.DeclTarget} and so asks the snapshot which method a
 * method-backed field binds to.
 *
 * <p>Coordinates without a specific {@link Behavior} arm fall through to
 * the SDL-docstring hover, and so do the two name tokens the coordinate walk does not key: the
 * directive's own name and an argument's name. All three read {@link SdlDescriptions}, so a
 * description is the graph's own captured SDL whether an author wrote it or graphitron ships it. New
 * directives in {@code directives.graphqls} light up hover automatically; authoring effort moves from
 * "edit Hovers.java" to "edit the SDL".
 */
public final class Hovers {

    private Hovers() {}

    /**
     * Every arm this entry point can reach reads either the store or the classification snapshot, so
     * it takes no projection. The bundled vocabulary is the only one in scope today; the workspace's
     * vocabulary is wired through {@code GraphitronTextDocumentService}.
     */
    public static Optional<Hover> compute(
        FileSnapshot file, Optional<StoreHandle> store, LspSchemaSnapshot snapshot, Point pos
    ) {
        return compute(LspVocabulary.load(), file, CompletionData.empty(), store, snapshot, pos, false);
    }

    /**
     * Canonical hover entry point. {@code store} is this document's graph, scoped and inside the
     * caller's read transaction, and every arm reads through it: the class census and its methods, the
     * catalog census behind the table, column and key arms, the graph's {@code @node} declarations,
     * the doc comments beneath several of them, which are a join to the {@code java_} family on the
     * source's own cadence, and the captured SDL behind every docstring. {@code catalog} is what the
     * declaration-name arm's binding resolution still reads, and retires with goto-definition's.
     *
     * <p>{@code classificationHoverEnabled} gates the parallel {@link DeclarationHovers} dispatch on
     * SDL declaration coordinates. Default false preserves the no-behaviour-change-by-default
     * contract; the document service flips it on per
     * {@link no.sikt.graphitron.lsp.state.Workspace#inlayHintConfig()}.
     */
    public static Optional<Hover> compute(
        LspVocabulary vocabulary, FileSnapshot file, CompletionData catalog,
        Optional<StoreHandle> store, LspSchemaSnapshot snapshot,
        Point pos, boolean classificationHoverEnabled
    ) {
        var directiveOpt = Directives.findContaining(file.tree().getRootNode(), pos);
        if (directiveOpt.isEmpty()) {
            // No directive at the cursor; try the classification-hover arm on SDL
            // declaration coordinates (field-definition / type-definition name tokens).
            // The catalog resolves which declaration the coordinate binds to, the store
            // describes it, and the description lands beneath the classification block.
            if (classificationHoverEnabled) {
                return DeclarationHovers.compute(file, store, snapshot, pos);
            }
            return Optional.empty();
        }
        var directive = directiveOpt.get();

        // Directive-name hover comes first. coordinateAt is leaf-oriented (arg coordinates, not
        // directive coordinates), so a cursor on the directive's name token falls through
        // coordinateAt to no-coord; the directive's own coordinate is what describes it.
        if (Nodes.contains(directive.nameNode(), pos)) {
            String directiveName = Nodes.text(directive.nameNode(), file.source());
            return descriptionHover(
                store, new SchemaCoordinate.Directive(directiveName), file, directive.nameNode());
        }

        var coordOpt = vocabulary.coordinateAt(directive, pos, file.source());
        var rangeNode = valueNodeFor(directive, pos);

        if (coordOpt.isPresent() && rangeNode != null) {
            var coord = coordOpt.get();
            var richer = richerHover(vocabulary, coord, directive, file, store, pos, rangeNode);
            if (richer.isPresent()) return richer;
            // SDL docstring on the coordinate. Empty if the definition carries no description
            // (rare in directives.graphqls).
            var docstring = descriptionHover(store, coord, file, rangeNode);
            if (docstring.isPresent()) return docstring;
        }

        return argNameHover(store, directive, pos, file);
    }

    /**
     * The cursor on an argument's <em>name</em> rather than its value, which the coordinate walk
     * declines by design (a name is not a bound value), answered by the argument's own coordinate.
     * Bundled and user-declared directives alike: one relation describes both, so the incumbent's
     * gate on a user-shaped projection is gone and hovering {@code name:} inside {@code @table} now
     * says what {@code name:} means.
     *
     * <p>Top-level arguments only, as the incumbent did. A nested object field's key would need the
     * enclosing input type, which is the descent {@link LspVocabulary#locateAt} performs for value
     * positions only.
     */
    private static Optional<Hover> argNameHover(
        Optional<StoreHandle> store, Directives.Directive directive, Point pos, FileSnapshot file
    ) {
        String directiveName = Nodes.text(directive.nameNode(), file.source());
        for (var arg : directive.arguments()) {
            if (!arg.contains(pos)) continue;
            if (!Nodes.contains(arg.key(), pos)) continue;
            String argName = Nodes.text(arg.key(), file.source());
            return descriptionHover(store,
                new SchemaCoordinate.DirectiveArg(directiveName, argName), file, arg.key());
        }
        return Optional.empty();
    }

    /**
     * Every coordinate arm reads the store and nothing else, the column arm included now that what
     * a type's members resolve against is a read rather than a permit. The snapshot reaches hover
     * only through the declaration-name arm, which this dispatch does not key.
     */
    private static Optional<Hover> richerHover(
        LspVocabulary vocabulary, SchemaCoordinate coord,
        Directives.Directive directive, FileSnapshot file,
        Optional<StoreHandle> store, Point pos, Node rangeNode
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
            case Behavior.CatalogColumnBinding ignored ->
                store.flatMap(s -> columnHover(directive, file, s, rangeNode));
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
            .select(SourceDeclarations.classJavadocOf(JVM_CLASS.CLASS_NAME))
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
        return Optional.of(hover(file, valueNode, formatMethod(fqn.get(), methodName, overloads,
            SourceDeclarations.methodJavadocByArity(store, fqn.get(), methodName))));
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
            .select(SQL_TABLE.TABLE_SCHEMA, SQL_TABLE.DESCRIPTION,
                SourceDeclarations.classJavadocOf(SQL_TABLE.CLASS_FQN),
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
        Directives.Directive directive, FileSnapshot file, StoreHandle store, Node valueNode
    ) {
        String memberName = Nodes.unquote(Nodes.text(valueNode, file.source()));
        var typeDecl = DeclarationKind.enclosing(directive.outer());
        if (typeDecl.isEmpty()) return Optional.empty();
        var typeName = TypeContext.declaredNameOf(typeDecl.get(), file.source());
        if (typeName.isEmpty()) return Optional.empty();
        var fieldName = TypeContext.enclosingFieldOrInputValueDefinition(directive.outer())
            .flatMap(fd -> TypeContext.fieldNameOf(fd, file.source()))
            .orElse(null);
        // Prefer the site's own resolved scope over the enclosing type's backing: a @reference
        // path's terminal table, or the table the named type is itself bound to, is where the
        // column named here lives. A Silent scope renders nothing rather than pulling metadata off
        // the parent's table, so the editor falls through to the SDL docstring; no row at all means
        // the parent's own scope answers, which is the dispatch below.
        if (fieldName != null) {
            var scope = FieldColumnTable.of(store, typeName.get(), fieldName);
            if (scope.isPresent()) {
                switch (scope.get()) {
                    case FieldColumnTable.Scope.Resolved(var table) -> {
                        return tableColumnHover(store, table, memberName, file, valueNode);
                    }
                    case FieldColumnTable.Scope.Silent ignored -> { return Optional.empty(); }
                }
            }
        }
        // The parent's own scope, which is one read: whether the type resolves against a table or
        // against a class, and which one, come back together, and a type the store scopes to
        // neither renders nothing.
        return TypeMemberScope.of(store, typeName.get())
            .flatMap(scope -> switch (scope) {
                case TypeMemberScope.Scope.Tables(var candidates) ->
                    tableColumnHover(store, candidates, memberName, file, valueNode);
                case TypeMemberScope.Scope.Members(var className) ->
                    slotHover(store, className, memberName, file, valueNode);
            });
    }

    /**
     * The named column of the tables a binding resolved to, under either of the two names the
     * census carries for it. The projection held only the jOOQ field name, so an author who wrote
     * the SQL name got a hover only where the two agree up to case; the diagnostic arm already
     * accepts both spellings, and the census is what lets this one agree with it.
     *
     * <p>Every candidate of an ambiguous binding contributes, and the render names the column's
     * schema where more than one answers, so what an author sees is that two tables spell it rather
     * than one arbitrary pick.
     */
    private static Optional<Hover> tableColumnHover(
        StoreHandle store, List<CatalogTable> tables, String columnName,
        FileSnapshot file, Node valueNode
    ) {
        if (tables.isEmpty()) return Optional.empty();
        return render(CatalogColumns.of(store, tables),
            tables.getFirst().tableName(), columnName, file, valueNode);
    }

    /**
     * The same hover for a table a resolution already picked, so an ambiguous name cannot pull in a
     * second schema's column of the same name behind the caller.
     */
    private static Optional<Hover> tableColumnHover(
        StoreHandle store, CatalogTable table, String columnName,
        FileSnapshot file, Node valueNode
    ) {
        return render(
            CatalogColumns.of(store, table), table.tableName(), columnName, file, valueNode);
    }

    private static Optional<Hover> render(
        List<CatalogColumns.Column> columns, String tableName, String columnName,
        FileSnapshot file, Node valueNode
    ) {
        var matches = columns.stream().filter(column -> column.isNamed(columnName)).toList();
        return matches.isEmpty()
            ? Optional.<Hover>empty()
            : Optional.of(hover(file, valueNode, formatColumn(tableName, matches)));
    }

    /**
     * The named member of {@code className}, under the one spelling the classifier would accept.
     * What the class offers comes from {@link ClassMemberSlots}, so a class the census holds
     * nothing for renders nothing, on the same terms as a member name the class does not offer.
     */
    private static Optional<Hover> slotHover(
        StoreHandle store, String className, String memberName,
        FileSnapshot file, Node valueNode
    ) {
        return ClassMemberSlots.named(store, className, memberName)
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

    /**
     * The description the graph's captured SDL carries at {@code coord}, rendered over
     * {@code rangeNode}. The three docstring arms differ only in which coordinate they hand over and
     * which node they highlight; the read is one.
     */
    private static Optional<Hover> descriptionHover(
        Optional<StoreHandle> store, SchemaCoordinate coord, FileSnapshot file, Node rangeNode
    ) {
        return store.flatMap(handle -> SdlDescriptions.of(handle, coord))
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
