package no.sikt.graphitron.lsp.inlay;

import no.sikt.graphitron.lsp.facts.CatalogTable;
import no.sikt.graphitron.lsp.facts.TypeBackingClass;
import no.sikt.graphitron.lsp.facts.TypeMemberScope;
import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Records;
import org.jooq.TableField;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.CODE_READ_SLOT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CODE_REFERENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DIRECTIVE_APPLICATION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_COLUMN_SCOPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TARGET_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TARGET_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_RESOLVED_TYPE_BINDING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_RESULT_CLASS;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_RESULT_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_BACKING;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ENTRY;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ROOT_OPERATION;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.jooq.impl.DSL.falseCondition;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectDistinct;
import static org.jooq.impl.DSL.upper;
import static org.jooq.impl.DSL.selectOne;

/**
 * Everything one inlay-hint request asks the store, in one statement. A projection and not a view: it
 * joins the relations owning each fact to the coordinates one visible region happens to contain, and
 * produces the shape the renderers are about to walk. Nothing here is a rule of its own, each
 * precedence being applied from the reader that owns it rather than restated.
 *
 * <p>The shape is {@code DiagnosticFacts}': a multiset per arm, each subquery over the one relation
 * owning its fact, nothing joined between arms, and the whole driven from no table so no arm is
 * conditional on another's rows. What differs is the grain. A recalculation spans the files a capture
 * touched and unions their questions; an inlay request is one file's window and has no wider unit to
 * belong to, so the statement is the region's.
 *
 * <p>That grain is the reason this surface wanted the recomposition most. An editor reissues the
 * request on every scroll, so a count that tracked the region was paid at the cadence of the cursor
 * rather than of a build, and the arm that grew with it was the overlay of an omitted
 * {@code @field(name:)}: resolving one walked the site's own match, then the type's binding, then its
 * backing class, then that class's slots, each round trip's subject decided by the answer before it.
 * Those four questions are asked of four relations sharing no key, so they were always askable at
 * once, and the chain was in the reading rather than in the facts.
 *
 * <p>Every arm reads a stored relation and none a view: a region is asked about on every scroll, and
 * a view evaluated whole per request would make that cost grow with the graph.
 *
 * <p>Two arms deliberately return rows no renderer will read. The bindings are asked for every type a
 * member site names, and the backings for every member site's type, because narrowing either would
 * mean an arm asserting a precedence its reader owns: whether a binding or a class scopes a name is
 * the decision {@link TypeMemberScope#resolve} makes after every arm has returned. The cost is rows
 * in a payload rather than a round trip.
 */
final class InlayFacts {

    private InlayFacts() {}

    /** A field coordinate, which is what the member-site arms are keyed on. */
    record FieldCoord(String typeName, String fieldName) {}

    /**
     * What the walk of a visible region asks about it. Collected before any read, so the whole
     * region's questions are known before the first of them is answered, and a set throughout so a
     * type named by five declarations is asked about once.
     */
    static final class Questions {

        private final Set<String> declaredTypeNames = new LinkedHashSet<>();
        private final Set<String> backingTypeNames = new LinkedHashSet<>();
        private final Set<String> boundTableTypeNames = new LinkedHashSet<>();
        private final Set<String> memberTypeNames = new LinkedHashSet<>();
        private final Set<FieldCoord> memberSites = new LinkedHashSet<>();

        /** A visible declaration of any kind, which the label and round-trip arms are keyed on. */
        void declaration(String typeName) {
            declaredTypeNames.add(typeName);
        }

        /** A visible type declaration, whose label is the result it holds. */
        void typeDeclaration(String typeName) {
            declaredTypeNames.add(typeName);
            backingTypeNames.add(typeName);
        }

        /**
         * A site where a {@code @table} overlay may render: a present directive that omitted the name,
         * or a type carrying no {@code @table} at all. Both want the same certain binding.
         */
        void boundTableSite(String typeName) {
            boundTableTypeNames.add(typeName);
        }

        /**
         * A site where a {@code @field} overlay may render. A member name resolves against the column
         * the field reads first, which the type-keyed column arm answers, and against the type's scope
         * second, which is the type's bindings, its backing class and the tables that class is a
         * record of, so asking about the site is asking about all of them.
         */
        void memberSite(String typeName, String fieldName) {
            memberSites.add(new FieldCoord(typeName, fieldName));
            declaredTypeNames.add(typeName);
            memberTypeNames.add(typeName);
            backingTypeNames.add(typeName);
            boundTableTypeNames.add(typeName);
        }

        boolean isEmpty() {
            return declaredTypeNames.isEmpty() && backingTypeNames.isEmpty()
                && boundTableTypeNames.isEmpty() && memberSites.isEmpty();
        }
    }

    // ===== The rows the arms return =====

    /** A table whose rows a type holds; several rows are a type reached from several tables. */
    record ResultTableRow(String typeName, String tableName) {}

    /** A class whose instances a type holds; several rows are producers returning different classes. */
    record ResultClassRow(String typeName, String className) {}

    /** The column a scalar field with no path reads, on one table its read departs from. */
    record TargetColumnRow(String typeName, String fieldName, String tableName, String columnName) {}

    /** A column a scalar field with a path names on the table the path lands on. */
    record PathColumnRow(String typeName, String fieldName, String columnName) {}

    /** A method a code reference written at a field resolves to. */
    record MethodRow(String typeName, String fieldName, String className, String methodName) {}

    /** A table a field's statement lands on. */
    record TargetTableRow(String typeName, String fieldName, String tableName) {}

    /** One field below a root whose fetcher runs a statement of its own. */
    record ProducerRow(String typeName, String fieldName) {}

    /** One candidate class standing for a type, from either of the two backing populations. */
    record BackingRow(String typeName, String className) {}

    /**
     * One table a type's {@code @table} resolves to.
     *
     * @param candidates the binding's own arity, read from the relation rather than counted here,
     *                   which is what lets an overlay refuse to speak where two tables answer to one name
     */
    record BoundTableRow(
        String typeName, Integer candidates,
        String tableSourceName, String tableSchema, String tableName
    ) {}

    /** A candidate backing class read back as the table whose rows jOOQ binds to it. */
    record RedirectRow(
        String typeName, String className,
        String tableSourceName, String tableSchema, String tableName
    ) {}

    /** One member slot a backing class offers, keyed by class because a slot is a fact about one. */
    record SlotRow(String className, String slotName) {}

    /** Every question the region asked, answered. */
    record Answers(
        List<ResultTableRow> resultTables,
        List<ResultClassRow> resultClasses,
        List<TargetColumnRow> targetColumns,
        List<PathColumnRow> pathColumns,
        List<MethodRow> methods,
        List<TargetTableRow> targetTables,
        List<ProducerRow> producers,
        List<BackingRow> backingSeeds,
        List<BackingRow> backingReached,
        List<BoundTableRow> boundTables,
        List<RedirectRow> redirects,
        List<SlotRow> slots
    ) {

        /**
         * What a type holds, as one word: the table whose rows it holds, else the simple name of the
         * class whose instances it holds, and nothing where it holds several or none.
         */
        Optional<String> typeLabel(String typeName) {
            var tables = resultTables.stream().filter(row -> row.typeName().equals(typeName))
                .map(ResultTableRow::tableName).distinct().toList();
            if (tables.size() == 1) return Optional.of(tables.getFirst());
            if (!tables.isEmpty()) return Optional.empty();
            var classes = resultClasses.stream().filter(row -> row.typeName().equals(typeName))
                .map(ResultClassRow::className).distinct().toList();
            return classes.size() == 1 ? Optional.of(simpleName(classes.getFirst())) : Optional.empty();
        }

        /**
         * What a field reads, as one phrase: the column, else the method it calls, else the table its
         * statement lands on. Several columns, one per table the read departs from, are listed.
         */
        Optional<String> fieldLabel(String typeName, String fieldName) {
            var columns = targetColumns.stream()
                .filter(row -> row.typeName().equals(typeName) && row.fieldName().equals(fieldName))
                .map(row -> row.tableName() + "." + row.columnName()).toList();
            if (!columns.isEmpty()) return Optional.of(String.join(", ", columns));
            var called = methods.stream()
                .filter(row -> row.typeName().equals(typeName) && row.fieldName().equals(fieldName))
                .map(row -> simpleName(row.className()) + "." + row.methodName()).distinct().toList();
            if (!called.isEmpty()) return Optional.of(String.join(", ", called));
            var landed = targetTables.stream()
                .filter(row -> row.typeName().equals(typeName) && row.fieldName().equals(fieldName))
                .map(TargetTableRow::tableName).distinct().toList();
            if (!landed.isEmpty()) return Optional.of("\u2192 " + String.join(", ", landed));
            return Optional.empty();
        }

        /**
         * Whether the field runs a statement of its own, which is a round trip worth marking. A root
         * field is no producer here: every one of its siblings is one too, so a marker on each would
         * say nothing.
         */
        boolean marksSeparateFetch(String typeName, String fieldName) {
            return producers.stream()
                .anyMatch(row -> row.typeName().equals(typeName) && row.fieldName().equals(fieldName));
        }

        /** The class standing for a type, by the grounding rule the backing reader owns. */
        Optional<String> backingClass(String typeName) {
            return TypeBackingClass.resolve(
                classNamesOf(backingSeeds, typeName), classNamesOf(backingReached, typeName));
        }

        /**
         * The one table a type is bound to, absent where the binding is ambiguous. The arity is the
         * relation's own column, so an overlay never re-derives it from the rows this arm brought back.
         */
        Optional<CatalogTable> certainBoundTable(String typeName) {
            return boundTables.stream()
                .filter(row -> row.typeName().equals(typeName) && Integer.valueOf(1).equals(row.candidates()))
                .findFirst()
                .map(row -> new CatalogTable(
                    row.tableSourceName(), row.tableSchema(), row.tableName()));
        }

        /**
         * The member a field's own name reaches, spelled as an author would write it into
         * {@code @field(name:)}. Over rows already in hand: the column the field reads, or for a field
         * with a path the one column of that name where the path lands, then the type's scope, which
         * answers only where that scope is a class's.
         */
        Optional<String> memberName(String typeName, String fieldName) {
            var column = targetColumns.stream()
                .filter(row -> row.typeName().equals(typeName) && row.fieldName().equals(fieldName))
                .map(TargetColumnRow::columnName)
                .distinct()
                .toList();
            if (column.size() == 1) return Optional.of(column.getFirst());
            var pathColumn = pathColumns.stream()
                .filter(row -> row.typeName().equals(typeName) && row.fieldName().equals(fieldName))
                .map(PathColumnRow::columnName)
                .toList();
            if (pathColumn.size() == 1) return Optional.of(pathColumn.getFirst());
            var scope = TypeMemberScope.resolve(
                boundTablesOf(typeName),
                () -> backingClass(typeName),
                className -> redirectTablesOf(typeName, className)).orElse(null);
            if (!(scope instanceof TypeMemberScope.Scope.Members members)) return Optional.empty();
            // Exact spelling, never case-insensitive, which is the slot relation's own rule: a member
            // name is a Java identifier the author is naming rather than a coordinate resolved for them.
            return slots.stream()
                .filter(row -> row.className().equals(members.className())
                    && row.slotName().equals(fieldName))
                .findFirst()
                .map(SlotRow::slotName);
        }

        private List<CatalogTable> boundTablesOf(String typeName) {
            return boundTables.stream()
                .filter(row -> row.typeName().equals(typeName))
                .map(row -> new CatalogTable(
                    row.tableSourceName(), row.tableSchema(), row.tableName()))
                .distinct()
                .toList();
        }

        private List<CatalogTable> redirectTablesOf(String typeName, String className) {
            return redirects.stream()
                .filter(row -> row.typeName().equals(typeName) && row.className().equals(className))
                .map(row -> new CatalogTable(
                    row.tableSourceName(), row.tableSchema(), row.tableName()))
                .distinct()
                .toList();
        }

        private static List<String> classNamesOf(List<BackingRow> rows, String typeName) {
            return rows.stream()
                .filter(row -> row.typeName().equals(typeName))
                .map(BackingRow::className)
                .distinct()
                .toList();
        }
    }

    /**
     * What a session with no store answers, which is that every relation is empty. Not a statement,
     * and deliberately the same value an empty question set produces: an overlay with nothing to
     * render is silent, and that is one policy rather than a branch per renderer.
     */
    static Answers none() {
        return new Answers(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /** Every question the region asked, answered in one statement. */
    static Answers of(StoreHandle store, Questions questions) {
        if (questions.isEmpty()) return none();
        String graph = store.graphName();
        return store.dsl()
            .select(
                resultTableArm(store, questions.declaredTypeNames),
                resultClassArm(store, questions.declaredTypeNames),
                targetColumnArm(store, questions.declaredTypeNames),
                pathColumnArm(store, questions.memberSites),
                methodArm(store, questions.declaredTypeNames),
                targetTableArm(store, questions.declaredTypeNames),
                multiset(selectDistinct(GRAPHITRON_PRODUCER.TYPE_NAME, GRAPHITRON_PRODUCER.FIELD_NAME)
                    .from(GRAPHITRON_PRODUCER)
                    .where(GRAPHITRON_PRODUCER.GRAPH_NAME.eq(graph))
                    .and(GRAPHITRON_PRODUCER.TYPE_NAME.in(questions.declaredTypeNames))
                    .andNotExists(selectOne().from(GRAPHQL_ROOT_OPERATION)
                        .where(GRAPHQL_ROOT_OPERATION.GRAPH_NAME.eq(GRAPHITRON_PRODUCER.GRAPH_NAME))
                        .and(GRAPHQL_ROOT_OPERATION.TYPE_NAME.eq(GRAPHITRON_PRODUCER.TYPE_NAME)))
                    .orderBy(GRAPHITRON_PRODUCER.TYPE_NAME, GRAPHITRON_PRODUCER.FIELD_NAME))
                    .convertFrom(rows -> rows.map(Records.mapping(ProducerRow::new))),
                backingArm(store, questions.backingTypeNames, TypeBackingClass.GROUNDED),
                backingArm(store, questions.backingTypeNames, TypeBackingClass.REACHED),
                boundTableArm(store, questions.boundTableTypeNames),
                redirectArm(store, questions.memberTypeNames),
                slotArm(store, questions.memberTypeNames))
            .fetchOne(Records.mapping(Answers::new));
    }

    /**
     * One backing population, either of the two the grounding rule chooses between, as
     * {@link TypeBackingClass#GROUNDED} or {@link TypeBackingClass#REACHED} names it. Both are rows of
     * one relation, which is why this is one method called twice: what separates them is which rows
     * count, and that is the parameter.
     */
    private static Field<List<BackingRow>> backingArm(
        StoreHandle store, Collection<String> typeNames, Condition population
    ) {
        return multiset(selectDistinct(GRAPHITRON_TYPE_BACKING.TYPE_NAME, GRAPHITRON_TYPE_BACKING.CLASS_NAME)
            .from(GRAPHITRON_TYPE_BACKING)
            .where(GRAPHITRON_TYPE_BACKING.GRAPH_NAME.eq(store.graphName()))
            .and(GRAPHITRON_TYPE_BACKING.TYPE_NAME.in(typeNames))
            .and(population)
            .orderBy(GRAPHITRON_TYPE_BACKING.TYPE_NAME, GRAPHITRON_TYPE_BACKING.CLASS_NAME))
            .convertFrom(rows -> rows.map(Records.mapping(BackingRow::new)));
    }

    /**
     * Every table the region's types are bound to, in the schema then table order {@code BoundTables}
     * answers in, so an overlay naming the first candidate names the one it would have named. The
     * arity rides along rather than being filtered here: the same rows serve the overlay, which may
     * only speak when the binding is certain, and the member scope, which takes every candidate.
     */
    private static Field<List<BoundTableRow>> boundTableArm(
        StoreHandle store, Collection<String> typeNames
    ) {
        var b = GRAPHITRON_RESOLVED_TYPE_BINDING;
        return multiset(selectDistinct(b.TYPE_NAME, b.CANDIDATES,
                b.TABLE_SOURCE_NAME, b.TABLE_SCHEMA, b.TABLE_NAME)
            .from(b)
            .where(b.GRAPH_NAME.eq(store.graphName()))
            .and(b.TYPE_NAME.in(typeNames))
            .orderBy(b.TYPE_NAME, b.TABLE_SCHEMA, b.TABLE_NAME))
            .convertFrom(rows -> rows.map(Records.mapping(BoundTableRow::new)));
    }

    /**
     * A candidate backing class read back as the table whose rows jOOQ binds to it, which is the join
     * {@code CatalogTables.ofRecordClass} performs, composed here rather than run per class. A type
     * whose own {@code @table} answers is in here too, its binding reaching a record class on the
     * backing relation's table arm; the rows are redundant for it, and the scope rule takes the
     * binding first regardless.
     */
    private static Field<List<RedirectRow>> redirectArm(
        StoreHandle store, Collection<String> typeNames
    ) {
        return multiset(selectDistinct(GRAPHITRON_TYPE_BACKING.TYPE_NAME, GRAPHITRON_TYPE_BACKING.CLASS_NAME,
                SQL_TABLE.SOURCE_NAME, SQL_TABLE.TABLE_SCHEMA, SQL_TABLE.TABLE_NAME)
            .from(GRAPHITRON_TYPE_BACKING)
            .join(SQL_TABLE).on(SQL_TABLE.RECORD_CLASS_FQN.eq(GRAPHITRON_TYPE_BACKING.CLASS_NAME))
            .where(GRAPHITRON_TYPE_BACKING.GRAPH_NAME.eq(store.graphName()))
            .and(GRAPHITRON_TYPE_BACKING.TYPE_NAME.in(typeNames))
            .and(store.reads(SQL_TABLE.SOURCE_NAME))
            .orderBy(GRAPHITRON_TYPE_BACKING.TYPE_NAME, SQL_TABLE.TABLE_SCHEMA, SQL_TABLE.TABLE_NAME))
            .convertFrom(rows -> rows.map(Records.mapping(RedirectRow::new)));
    }

    /**
     * The slots every candidate backing class of the region's member-site types offers, keyed by class
     * rather than by type: which class a type resolves to is the grounding rule's answer, applied after
     * this arm has returned, and a slot is a fact about a class either way.
     */
    private static Field<List<SlotRow>> slotArm(StoreHandle store, Collection<String> typeNames) {
        return multiset(selectDistinct(CODE_READ_SLOT.CLASS_NAME, CODE_READ_SLOT.SLOT_NAME)
            .from(CODE_READ_SLOT)
            .join(GRAPHITRON_TYPE_BACKING)
            .on(GRAPHITRON_TYPE_BACKING.CLASS_NAME.eq(CODE_READ_SLOT.CLASS_NAME))
            .where(store.reads(CODE_READ_SLOT.SOURCE_NAME))
            .and(GRAPHITRON_TYPE_BACKING.GRAPH_NAME.eq(store.graphName()))
            .and(GRAPHITRON_TYPE_BACKING.TYPE_NAME.in(typeNames))
            .orderBy(CODE_READ_SLOT.CLASS_NAME, CODE_READ_SLOT.SLOT_NAME))
            .convertFrom(rows -> rows.map(Records.mapping(SlotRow::new)));
    }

    /** The tables whose rows the region's types hold. */
    private static Field<List<ResultTableRow>> resultTableArm(StoreHandle store, Collection<String> typeNames) {
        var t = GRAPHITRON_RESULT_TABLE;
        return multiset(selectDistinct(t.TYPE_NAME, t.TABLE_NAME).from(t)
            .where(t.GRAPH_NAME.eq(store.graphName()), t.TYPE_NAME.in(typeNames))
            .orderBy(t.TYPE_NAME, t.TABLE_NAME))
            .convertFrom(rows -> rows.map(Records.mapping(ResultTableRow::new)));
    }

    /** The classes whose instances the region's types hold. */
    private static Field<List<ResultClassRow>> resultClassArm(StoreHandle store, Collection<String> typeNames) {
        var c = GRAPHITRON_RESULT_CLASS;
        return multiset(selectDistinct(c.TYPE_NAME, c.CLASS_NAME).from(c)
            .where(c.GRAPH_NAME.eq(store.graphName()), c.TYPE_NAME.in(typeNames))
            .orderBy(c.TYPE_NAME, c.CLASS_NAME))
            .convertFrom(rows -> rows.map(Records.mapping(ResultClassRow::new)));
    }

    /** The columns the region's scalar fields with no path read. */
    private static Field<List<TargetColumnRow>> targetColumnArm(StoreHandle store, Collection<String> typeNames) {
        var c = GRAPHITRON_FIELD_TARGET_COLUMN;
        return multiset(selectDistinct(c.TYPE_NAME, c.FIELD_NAME, c.FROM_TABLE, c.COLUMN_NAME).from(c)
            .where(c.GRAPH_NAME.eq(store.graphName()), c.TYPE_NAME.in(typeNames))
            .orderBy(c.TYPE_NAME, c.FIELD_NAME, c.FROM_TABLE))
            .convertFrom(rows -> rows.map(Records.mapping(TargetColumnRow::new)));
    }

    /**
     * The column each member site with a path names where the path lands, by its own name, the SQL
     * name before the generated one as the target column matches it. A field with a path has no
     * target column yet, the landing being the forward walk's to state; the column scope's terminal
     * table is where it reads until then.
     */
    private static Field<List<PathColumnRow>> pathColumnArm(StoreHandle store, Collection<FieldCoord> sites) {
        var sc = GRAPHITRON_FIELD_COLUMN_SCOPE;
        var c = SQL_COLUMN;
        return multiset(selectDistinct(sc.TYPE_NAME, sc.FIELD_NAME, c.COLUMN_NAME).from(sc)
            .join(c).on(c.SOURCE_NAME.eq(sc.TABLE_SOURCE_NAME), c.TABLE_SCHEMA.eq(sc.TABLE_SCHEMA),
                c.TABLE_NAME.eq(sc.TABLE_NAME), c.COLUMN_NAME_UPPER.eq(upper(sc.FIELD_NAME)))
            .where(sc.GRAPH_NAME.eq(store.graphName()), sc.BASIS.eq("PATH_TERMINAL"))
            .and(coordinateIn(sc.TYPE_NAME, sc.FIELD_NAME, sites))
            .orderBy(sc.TYPE_NAME, sc.FIELD_NAME, c.COLUMN_NAME))
            .convertFrom(rows -> rows.map(Records.mapping(PathColumnRow::new)));
    }

    /**
     * The methods the code references written at the region's fields resolve to. A reference is
     * keyed by the object value it was written as, two levels under the directive.
     */
    private static Field<List<MethodRow>> methodArm(StoreHandle store, Collection<String> typeNames) {
        var f = GRAPHITRON_FIELD;
        var d = GRAPHITRON_DIRECTIVE_APPLICATION;
        var arg = GRAPHQL_AST_ENTRY.as("inlay_arg");
        var val = GRAPHQL_AST_ENTRY.as("inlay_val");
        var r = GRAPHITRON_CODE_REFERENCE;
        return multiset(selectDistinct(f.TYPE_NAME, f.FIELD_NAME, r.METHOD_CLASS_NAME, r.METHOD_NAME).from(f)
            .join(d).on(d.GRAPH_NAME.eq(f.GRAPH_NAME), d.COORDINATE.eq(f.COORDINATE), d.ORIGIN.eq("AUTHORED"))
            .join(arg).on(arg.GRAPH_NAME.eq(d.GRAPH_NAME), arg.SOURCE_NAME.eq(d.SOURCE_NAME),
                arg.PARENT_LINE.eq(d.SOURCE_LINE), arg.PARENT_COLUMN.eq(d.SOURCE_COLUMN))
            .join(val).on(val.GRAPH_NAME.eq(arg.GRAPH_NAME), val.SOURCE_NAME.eq(arg.SOURCE_NAME),
                val.PARENT_LINE.eq(arg.SOURCE_LINE), val.PARENT_COLUMN.eq(arg.SOURCE_COLUMN))
            .join(r).on(r.GRAPH_NAME.eq(val.GRAPH_NAME), r.SOURCE_NAME.eq(val.SOURCE_NAME),
                r.SOURCE_LINE.eq(val.SOURCE_LINE), r.SOURCE_COLUMN.eq(val.SOURCE_COLUMN))
            .where(f.GRAPH_NAME.eq(store.graphName()), f.TYPE_NAME.in(typeNames))
            .orderBy(f.TYPE_NAME, f.FIELD_NAME, r.METHOD_CLASS_NAME, r.METHOD_NAME))
            .convertFrom(rows -> rows.map(Records.mapping(MethodRow::new)));
    }

    /** The tables the region's fields' statements land on, through the type that binds each. */
    private static Field<List<TargetTableRow>> targetTableArm(StoreHandle store, Collection<String> typeNames) {
        var tt = GRAPHITRON_FIELD_TARGET_TABLE;
        var b = GRAPHITRON_RESOLVED_TYPE_BINDING;
        return multiset(selectDistinct(tt.TYPE_NAME, tt.FIELD_NAME, b.TABLE_NAME).from(tt)
            .join(b).on(b.GRAPH_NAME.eq(tt.GRAPH_NAME), b.TYPE_NAME.eq(tt.ARRIVING_TYPE_NAME))
            .where(tt.GRAPH_NAME.eq(store.graphName()), tt.TYPE_NAME.in(typeNames))
            .orderBy(tt.TYPE_NAME, tt.FIELD_NAME, b.TABLE_NAME))
            .convertFrom(rows -> rows.map(Records.mapping(TargetTableRow::new)));
    }

    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    /** A disjunction over whole coordinates, which is what keys the arm a site's own match comes from. */
    private static Condition coordinateIn(
        TableField<?, String> typeColumn, TableField<?, String> fieldColumn,
        Collection<FieldCoord> sites
    ) {
        Condition any = falseCondition();
        for (var site : sites) {
            any = any.or(typeColumn.eq(site.typeName()).and(fieldColumn.eq(site.fieldName())));
        }
        return any;
    }
}
