package no.sikt.graphitron.lsp.diagnostics;

import no.sikt.graphitron.lsp.facts.CarrierDataField;
import no.sikt.graphitron.lsp.facts.CatalogColumns;
import no.sikt.graphitron.lsp.facts.CatalogKeys;
import no.sikt.graphitron.lsp.facts.CatalogTable;
import no.sikt.graphitron.lsp.facts.CatalogTables;
import no.sikt.graphitron.lsp.facts.ClassMemberSlots;
import no.sikt.graphitron.lsp.facts.TypeBackingClass;
import no.sikt.graphitron.lsp.facts.TypeMemberScope;
import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.Records;
import org.jooq.Select;
import org.jooq.TableField;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.INTENT_CARRIER_DATA_FIELD;
import static no.sikt.graphitron.model.Tables.INTENT_BOUND_TABLE;
import static no.sikt.graphitron.model.Tables.INTENT_CLASS_MEMBER_SLOT;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_COLUMN_TABLE;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_BACKING;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_BACKING_SEED;
import static no.sikt.graphitron.model.Tables.JVM_CLASS;
import static no.sikt.graphitron.model.Tables.JVM_METHOD;
import static no.sikt.graphitron.model.Tables.JVM_METHOD_PARAMETER;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_REFERENTIAL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.falseCondition;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectDistinct;
import static org.jooq.impl.DSL.selectOne;

/**
 * Everything one document's diagnostics ask the store, in one statement. This is a projection and not
 * a view: it joins the relations that own each fact to the coordinates one file happens to contain,
 * and it produces the nested shape the judgement is about to walk. Nothing here belongs in the DDL,
 * and nothing here is a rule of its own, each match rule being composed from the reader that defines
 * it and each precedence applied rather than restated.
 *
 * <p>The shape is a declaration hover's, at a document's grain instead of a coordinate's: a multiset
 * per arm, each subquery over the relations owning its fact, nothing joined between arms, and the whole
 * driven from no table so no arm is conditional on another's rows. What differs is what the arms are
 * keyed on. A hover asks about one coordinate, so its arms take a coordinate; a document asks about
 * every value an author wrote in it, so its arms take the whole collected set and the statement count
 * stops tracking the file's size. {@code DiagnosticsStatementCountTest} holds the number.
 *
 * <p>What makes a whole document reachable in one statement is that the questions are independent. An
 * author's table name, foreign key, class, method, {@code @node} reference and member name are resolved
 * by relations sharing no key, and the walk that collects them reads nothing, so at no point does an
 * answer decide what to ask next. The one chain that looks like an exception is the member arm, where a
 * name resolves against whatever the site's scope turns out to be: that is a join from the coordinate
 * through the scope to the columns, and a join inside one arm is not a second statement.
 *
 * <p>An empty question set costs nothing rather than a statement returning nothing: a document whose
 * directives name no value the store could resolve has nothing to ask, which is the honest reading of a
 * file carrying only {@code @deprecated}.
 */
final class DiagnosticFacts {

    private DiagnosticFacts() {}

    /**
     * What a census says about one name an author wrote. Three outcomes rather than a boolean, for
     * {@link no.sikt.graphitron.lsp.facts.ClasspathClasses.Presence}'s reason: a name a populated
     * census does not hold will not resolve at codegen either, and a census holding nothing is a
     * consumer who has not built yet, whose schema is not full of wrong names. One vocabulary for
     * every census here, so no arm of the judgement can hold the two questions in the wrong order.
     */
    enum Resolution {

        /** The census holds this name. */
        RESOLVES,

        /** The census holds names of this kind, and none of them is this one. */
        UNKNOWN,

        /** The census holds nothing of this kind: what this graph reads has not been generated yet. */
        NO_CENSUS
    }

    /** One method an author named, which is the grain the census answers about overloads at. */
    record MethodRef(String className, String methodName) {}

    /** One field coordinate, which is where a member name is written. */
    record FieldCoord(String typeName, String fieldName) {}

    /**
     * What a document asks, accumulated by the walk before anything is read. Sets throughout: a schema
     * naming one table forty times asks about it once, and the arms are keyed on what was asked rather
     * than on how often.
     */
    static final class Questions {

        private final Set<String> tableNames = new LinkedHashSet<>();
        private final Set<String> foreignKeyNames = new LinkedHashSet<>();
        private final Set<String> classNames = new LinkedHashSet<>();
        private final Set<MethodRef> methods = new LinkedHashSet<>();
        private final Set<String> nodeTypeNames = new LinkedHashSet<>();
        private final Set<FieldCoord> memberSites = new LinkedHashSet<>();
        private final Set<String> memberTypeNames = new LinkedHashSet<>();
        private final Set<FieldCoord> sigilSites = new LinkedHashSet<>();
        private final Set<String> sigilTypeNames = new LinkedHashSet<>();

        void tableName(String spelling) {
            tableNames.add(spelling);
        }

        void foreignKeyName(String spelling) {
            foreignKeyNames.add(spelling);
        }

        void className(String fqn) {
            classNames.add(fqn);
        }

        /**
         * A method question implies its class's: every arm resolving a method name stands behind the
         * class resolving first, so asking about the method is asking about both.
         */
        void method(String classFqn, String methodName) {
            classNames.add(classFqn);
            methods.add(new MethodRef(classFqn, methodName));
        }

        void nodeTypeName(String typeName) {
            nodeTypeNames.add(typeName);
        }

        /**
         * A member name is written at a coordinate but resolved against a scope some arms key on the
         * owning type, so a site contributes to both sets. The field name may be absent, a member name
         * written where no field encloses it having no coordinate of its own; the type's scope still
         * answers for it.
         */
        void memberSite(String typeName, String fieldName) {
            memberTypeNames.add(typeName);
            if (fieldName != null) {
                memberSites.add(new FieldCoord(typeName, fieldName));
            }
        }

        /**
         * A written {@code $source} asks two things at once, and the second is about the type rather
         * than the coordinate: whether the store holds the parent at all, which is what separates a
         * site the sigil does not belong at from one nothing has been captured for yet. A site with no
         * enclosing field contributes only the type question, having no coordinate to admit.
         */
        void sigilSite(String typeName, String fieldName) {
            sigilTypeNames.add(typeName);
            if (fieldName != null) {
                sigilSites.add(new FieldCoord(typeName, fieldName));
            }
        }

        /**
         * Folds another document's questions in. Sets throughout, so the union of what several
         * documents ask is what one statement answers for all of them, and two files naming the same
         * type ask about it once.
         */
        void addAll(Questions other) {
            tableNames.addAll(other.tableNames);
            foreignKeyNames.addAll(other.foreignKeyNames);
            classNames.addAll(other.classNames);
            methods.addAll(other.methods);
            nodeTypeNames.addAll(other.nodeTypeNames);
            memberSites.addAll(other.memberSites);
            memberTypeNames.addAll(other.memberTypeNames);
            sigilSites.addAll(other.sigilSites);
            sigilTypeNames.addAll(other.sigilTypeNames);
        }

        boolean isEmpty() {
            return tableNames.isEmpty() && foreignKeyNames.isEmpty() && classNames.isEmpty()
                && methods.isEmpty() && nodeTypeNames.isEmpty() && memberTypeNames.isEmpty()
                && sigilTypeNames.isEmpty();
        }
    }

    // ===== The rows the arms return =====

    /**
     * One parameter of one overload, the descriptor being what tells two overloads apart.
     *
     * @param position the parameter's index, or absent where the row is the left join's placeholder for
     *                 a method that declares no parameter. It is the only thing separating a
     *                 no-argument method from one whose parameters all came back nameless, which are
     *                 opposite answers to whether a mapping entry can be called unknown.
     * @param parameterName the parameter's name, or absent where the class was compiled without
     *                 {@code -parameters}
     */
    record ParameterRow(
        String className, String methodName, String descriptor, Integer position, String parameterName
    ) {}

    /**
     * One overload as the two rules over it need it. Both rules are
     * {@link no.sikt.graphitron.lsp.facts.ClasspathMethods.Method}'s and are spelled the same way here
     * so a reader comparing the two surfaces sees one rule rather than two readings of a row shape.
     */
    record Overload(int arity, List<String> parameterNames) {

        /** Whether any parameter came back nameless, the class having been compiled without names. */
        boolean hasUnnamedParameters() {
            return arity > parameterNames.size();
        }

        /** Whether the overload takes parameters and named none of them. */
        boolean isNameless() {
            return arity > 0 && parameterNames.isEmpty();
        }
    }

    /** The site's own resolution, and one column of the table it names. */
    record OverrideRow(
        String typeName, String fieldName, String disposition, String tableSourceName,
        String tableSchema, String tableName, String columnName, String jooqName
    ) {}

    /** A table the site's parent is bound to, and one of its columns. */
    record ParentRow(
        String typeName, String tableSourceName, String tableSchema, String tableName,
        String columnName, String jooqName
    ) {}

    /** A candidate backing class of a type, from whichever population reached it. */
    record BackingRow(String typeName, String className) {}

    /** A backing class read back as the table jOOQ binds to it, and one of that table's columns. */
    record RedirectRow(
        String typeName, String className, String tableSourceName, String tableSchema,
        String tableName, String columnName, String jooqName
    ) {}

    /** A slot a candidate backing class offers. */
    record SlotRow(String className, String slotName, String origin) {}

    /**
     * The store's answer to a whole document, and the only thing the judgement reads. The accessors are
     * the questions restated; the components are the arms, and nothing outside this class reads them.
     */
    record Answers(
        boolean storeAnswered,
        List<String> resolvedTableNames,
        boolean anyTable,
        List<String> resolvedForeignKeyNames,
        boolean anyForeignKey,
        List<String> resolvedClassNames,
        boolean anyClass,
        List<ParameterRow> parameters,
        List<String> resolvedNodeTypeNames,
        List<OverrideRow> overrides,
        List<ParentRow> parents,
        List<BackingRow> backingSeeds,
        List<BackingRow> backingReached,
        List<RedirectRow> redirects,
        List<SlotRow> slots,
        List<FieldCoord> sigilSites,
        List<String> declaredSigilTypeNames
    ) {

        /**
         * Whether the {@code $source} sigil belongs at this coordinate, and if not, whether the store
         * is in a position to say so. {@link Resolution#RESOLVES} is an admitted site,
         * {@link Resolution#UNKNOWN} a captured type the sigil does not belong on, and
         * {@link Resolution#NO_CENSUS} a parent the store has never seen, where a buffer naming a type
         * no capture has read is the ordinary reason and a judgement asserting the sigil is misplaced
         * would be asserting it about a shape nothing has resolved.
         */
        Resolution sourceSigilSite(String typeName, String fieldName) {
            return resolution(fieldName != null && sigilSites.contains(new FieldCoord(typeName, fieldName)),
                declaredSigilTypeNames.contains(typeName));
        }

        /** What the catalog says about a table name an author wrote. */
        Resolution tableName(String spelling) {
            return resolution(resolvedTableNames.contains(spelling), anyTable);
        }

        /** What the key census says about a {@code @reference(key:)} value. */
        Resolution foreignKeyName(String spelling) {
            return resolution(resolvedForeignKeyNames.contains(spelling), anyForeignKey);
        }

        /** What the classpath census says about a class FQN. Exact, a class name being an identifier. */
        Resolution className(String fqn) {
            return resolution(resolvedClassNames.contains(fqn), anyClass);
        }

        /**
         * What the graph's {@code @node} declarations say about a referenced type. The census this arm
         * defers on is the store itself rather than a population: a capture writes every {@code @node}
         * in the graph, so no row means the schema declares none and the reference is wrong, while no
         * store at all means nothing has been captured yet.
         */
        Resolution nodeTypeName(String typeName) {
            return resolution(resolvedNodeTypeNames.contains(typeName), storeAnswered);
        }

        /**
         * The overloads the census holds under one name, each as its own parameter-name list. Grouped
         * by descriptor because that is what separates two overloads, and both rules a caller applies
         * over the result, whether every overload is nameless and what the union of names is, are about
         * overloads rather than about parameters. An overload compiled without {@code -parameters}
         * arrives as an empty list rather than as an absent one, which is what makes the two tellable
         * apart from a method that takes nothing.
         */
        List<Overload> overloads(String classFqn, String methodName) {
            var arity = new LinkedHashMap<String, Integer>();
            var names = new LinkedHashMap<String, List<String>>();
            for (var row : parameters) {
                if (!row.className().equals(classFqn) || !row.methodName().equals(methodName)) continue;
                arity.merge(row.descriptor(), row.position() == null ? 0 : 1, Integer::sum);
                var declared = names.computeIfAbsent(row.descriptor(), ignored -> new ArrayList<>());
                if (row.parameterName() != null) {
                    declared.add(row.parameterName());
                }
            }
            return arity.entrySet().stream()
                .map(entry -> new Overload(entry.getValue(), names.get(entry.getKey())))
                .toList();
        }

        /**
         * What a member name written at this coordinate resolves against, or empty where nothing
         * written here may be called wrong. Empty covers three cases the arms answer apart and a
         * judgement treats alike: the site's own resolution declining, no scope reaching the site at
         * all, and a scope whose census population is empty. The first is the authored-conflict
         * silence, the third a consumer who has generated no model yet.
         *
         * <p>The site's own resolution is consulted before its parent's, and
         * {@link TypeMemberScope#resolve} decides between the parent's binding and its backing class.
         * Both orderings belong to the readers that state them and are applied here, not repeated.
         */
        Optional<MemberScope> memberScope(String typeName, String fieldName) {
            var site = overrideRowsAt(typeName, fieldName);
            if (!site.isEmpty()) {
                if (!"RESOLVE".equals(site.getFirst().disposition())) return Optional.empty();
                return columnScope(site.getFirst().tableName(),
                    namesOf(site, OverrideRow::columnName, OverrideRow::jooqName));
            }
            return TypeMemberScope.resolve(
                    boundTablesOf(typeName),
                    () -> TypeBackingClass.resolve(
                        classNamesOf(backingSeeds, typeName), classNamesOf(backingReached, typeName)),
                    className -> redirectTablesOf(typeName, className))
                .flatMap(scope -> switch (scope) {
                    case TypeMemberScope.Scope.Tables(var candidates) ->
                        columnScope(candidates.getFirst().tableName(), columnsOf(typeName, candidates));
                    case TypeMemberScope.Scope.Members(var className) -> slotScope(className);
                });
        }

        private static Resolution resolution(boolean resolves, boolean populated) {
            if (resolves) return Resolution.RESOLVES;
            return populated ? Resolution.UNKNOWN : Resolution.NO_CENSUS;
        }

        private List<OverrideRow> overrideRowsAt(String typeName, String fieldName) {
            if (fieldName == null) return List.of();
            return overrides.stream()
                .filter(row -> row.typeName().equals(typeName) && row.fieldName().equals(fieldName))
                .toList();
        }

        private Optional<MemberScope> columnScope(String tableName, List<CatalogColumns.Names> columns) {
            return columns.isEmpty()
                ? Optional.empty()
                : Optional.of(new MemberScope.Columns(tableName, columns));
        }

        private Optional<MemberScope> slotScope(String className) {
            var offered = slots.stream().filter(row -> row.className().equals(className)).toList();
            if (offered.isEmpty()) return Optional.empty();
            return Optional.of(new MemberScope.Slots(className,
                ClassMemberSlots.Origin.of(offered.getFirst().origin()),
                offered.stream().map(SlotRow::slotName).toList()));
        }

        private List<CatalogTable> boundTablesOf(String typeName) {
            return parents.stream()
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

        /**
         * The columns of the tables a scope resolved to, from whichever arm reached them. Both
         * table-producing arms are searched rather than the one the scope came from: a table is the
         * same table however the site arrived at it, and its columns rode along with the row that
         * named it.
         */
        private List<CatalogColumns.Names> columnsOf(String typeName, List<CatalogTable> candidates) {
            var columns = new ArrayList<CatalogColumns.Names>();
            for (var table : candidates) {
                columns.addAll(namesOf(parents.stream()
                        .filter(row -> row.typeName().equals(typeName) && namesTable(table,
                            row.tableSourceName(), row.tableSchema(), row.tableName()))
                        .toList(),
                    ParentRow::columnName, ParentRow::jooqName));
                columns.addAll(namesOf(redirects.stream()
                        .filter(row -> row.typeName().equals(typeName) && namesTable(table,
                            row.tableSourceName(), row.tableSchema(), row.tableName()))
                        .toList(),
                    RedirectRow::columnName, RedirectRow::jooqName));
            }
            return columns;
        }

        private static boolean namesTable(
            CatalogTable table, String sourceName, String schema, String tableName
        ) {
            return table.sourceName().equals(sourceName) && table.schema().equals(schema)
                && table.tableName().equals(tableName);
        }

        private static List<String> classNamesOf(List<BackingRow> rows, String typeName) {
            return rows.stream()
                .filter(row -> row.typeName().equals(typeName))
                .map(BackingRow::className)
                .distinct()
                .toList();
        }

        /**
         * The two names off rows whose column side may be absent. Every columns arm is a left join, a
         * scope that reached a table the census holds no column for being a different answer from a
         * scope that reached no table at all, so the absent side is dropped here rather than in the arm.
         */
        private static <R> List<CatalogColumns.Names> namesOf(
            List<R> rows, Function<R, String> columnName, Function<R, String> jooqName
        ) {
            var names = new ArrayList<CatalogColumns.Names>(rows.size());
            for (var row : rows) {
                String column = columnName.apply(row);
                if (column != null) {
                    names.add(new CatalogColumns.Names(column, jooqName.apply(row)));
                }
            }
            return names;
        }
    }

    /** What a member name written at a site resolves against, in the two forms a scope takes. */
    sealed interface MemberScope {

        /** Whether {@code spelling} is a name this scope offers. */
        boolean offers(String spelling);

        /** The names resolve against a table's columns; never empty. */
        record Columns(String tableName, List<CatalogColumns.Names> columns) implements MemberScope {

            @Override
            public boolean offers(String spelling) {
                return columns.stream().anyMatch(column -> column.isNamed(spelling));
            }
        }

        /** The names resolve against a class's member slots; never empty. */
        record Slots(String className, ClassMemberSlots.Origin origin, List<String> slotNames)
            implements MemberScope {

            @Override
            public boolean offers(String spelling) {
                return slotNames.contains(spelling);
            }
        }
    }

    /**
     * What a session with no store answers, which is that every census is empty. Not a statement, and
     * deliberately the same value an empty question set produces: silence on every value is one policy
     * rather than a branch each arm of the judgement takes for itself.
     */
    static Answers none() {
        return new Answers(false, List.of(), false, List.of(), false, List.of(), false, List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of());
    }

    /** Every question the document asked, answered in one statement. */
    static Answers of(StoreHandle store, Questions questions) {
        if (questions.isEmpty()) return none();
        return store.dsl()
            .select(
                // A column of the answer rather than a flag beside it: that a store answered is
                // literally true when this statement runs, and the one arm deferring on the store's own
                // presence reads it exactly as the others read their census probe.
                inline(true),
                tableSpellings(store, questions.tableNames),
                censusHolds(selectOne().from(SQL_TABLE).where(store.reads(SQL_TABLE.SOURCE_NAME))),
                foreignKeySpellings(store, questions.foreignKeyNames),
                censusHolds(selectOne().from(SQL_REFERENTIAL_CONSTRAINT)
                    .where(store.reads(SQL_REFERENTIAL_CONSTRAINT.SOURCE_NAME))),
                multiset(selectDistinct(JVM_CLASS.CLASS_NAME)
                    .from(JVM_CLASS)
                    .where(store.reads(JVM_CLASS.SOURCE_NAME))
                    .and(JVM_CLASS.CLASS_NAME.in(questions.classNames))
                    .orderBy(JVM_CLASS.CLASS_NAME))
                    .convertFrom(rows -> rows.map(Record1::value1)),
                censusHolds(selectOne().from(JVM_CLASS).where(store.reads(JVM_CLASS.SOURCE_NAME))),
                parameterArm(store, questions.methods),
                multiset(selectDistinct(GRAPHITRON_NODE.TYPE_NAME)
                    .from(GRAPHITRON_NODE)
                    .where(GRAPHITRON_NODE.GRAPH_NAME.eq(store.graphName()))
                    .and(GRAPHITRON_NODE.TYPE_NAME.in(questions.nodeTypeNames))
                    .orderBy(GRAPHITRON_NODE.TYPE_NAME))
                    .convertFrom(rows -> rows.map(Record1::value1)),
                overrideArm(store, questions.memberSites),
                parentArm(store, questions.memberTypeNames),
                backingArm(store, questions.memberTypeNames, INTENT_TYPE_BACKING_SEED.TYPE_NAME,
                    INTENT_TYPE_BACKING_SEED.CLASS_NAME, INTENT_TYPE_BACKING_SEED.GRAPH_NAME),
                backingArm(store, questions.memberTypeNames, INTENT_TYPE_BACKING.TYPE_NAME,
                    INTENT_TYPE_BACKING.CLASS_NAME, INTENT_TYPE_BACKING.GRAPH_NAME),
                redirectArm(store, questions.memberTypeNames),
                slotArm(store, questions.memberTypeNames),
                sigilSiteArm(store, questions.sigilSites),
                declaredTypeArm(store, questions.sigilTypeNames))
            .fetchOne(Records.mapping(Answers::new));
    }

    /**
     * Which of the authored table names the catalog resolves, asked as one probe per spelling
     * projecting the spelling itself. The alternative was to fetch what matched and re-apply the match
     * rule to the result, which would have put a second copy of that rule in this consumer; a probe
     * selecting its own literal keeps the rule where {@link CatalogTables#spelledBy} defines it and
     * answers in the vocabulary the author wrote. {@code SELECT DISTINCT} rather than a limit, so a
     * spelling several tables satisfy is still one answer.
     */
    private static Field<List<String>> tableSpellings(
        StoreHandle store, Collection<String> spellings
    ) {
        return spellingProbes(spellings, spelling -> selectDistinct(inline(spelling))
            .from(SQL_TABLE)
            .where(store.reads(SQL_TABLE.SOURCE_NAME))
            .and(CatalogTables.spelledBy(spelling)));
    }

    /**
     * The same probe over the key census, which needs the {@code sql_constraint} join
     * {@link CatalogKeys#spelledBy}'s unqualified arm reads the generated spelling from. Its own arm
     * rather than a parameter of the read above because the join belongs to the question.
     */
    private static Field<List<String>> foreignKeySpellings(
        StoreHandle store, Collection<String> spellings
    ) {
        return spellingProbes(spellings, spelling -> selectDistinct(inline(spelling))
            .from(SQL_REFERENTIAL_CONSTRAINT)
            .join(SQL_CONSTRAINT)
            .on(SQL_CONSTRAINT.SOURCE_NAME.eq(SQL_REFERENTIAL_CONSTRAINT.SOURCE_NAME))
            .and(SQL_CONSTRAINT.TABLE_SCHEMA.eq(SQL_REFERENTIAL_CONSTRAINT.TABLE_SCHEMA))
            .and(SQL_CONSTRAINT.TABLE_NAME.eq(SQL_REFERENTIAL_CONSTRAINT.TABLE_NAME))
            .and(SQL_CONSTRAINT.CONSTRAINT_NAME.eq(SQL_REFERENTIAL_CONSTRAINT.CONSTRAINT_NAME))
            .where(store.reads(SQL_REFERENTIAL_CONSTRAINT.SOURCE_NAME))
            .and(CatalogKeys.spelledBy(spelling)));
    }

    /** One arm out of a probe per spelling, empty where the document asked about none of this kind. */
    private static Field<List<String>> spellingProbes(
        Collection<String> spellings, Function<String, Select<Record1<String>>> probe
    ) {
        Select<Record1<String>> probes = null;
        for (String spelling : spellings) {
            var one = probe.apply(spelling);
            probes = probes == null ? one : probes.unionAll(one);
        }
        if (probes == null) {
            probes = selectDistinct(inline("")).from(SQL_TABLE).where(falseCondition());
        }
        return multiset(probes).convertFrom(rows -> rows.map(Record1::value1));
    }

    /** Whether a census holds anything at all, which every arm over it reads absence against. */
    private static Field<Boolean> censusHolds(Select<?> probe) {
        return field(exists(probe));
    }

    /**
     * Every parameter of every overload the document's methods name, left-joined so a method taking
     * nothing arrives as a row with no parameter. The class rides on the row rather than being assumed,
     * one document naming methods on many classes.
     */
    private static Field<List<ParameterRow>> parameterArm(
        StoreHandle store, Collection<MethodRef> methods
    ) {
        Condition named = falseCondition();
        for (var method : methods) {
            named = named.or(JVM_METHOD.CLASS_NAME.eq(method.className())
                .and(JVM_METHOD.METHOD_NAME.eq(method.methodName())));
        }
        return multiset(select(JVM_METHOD.CLASS_NAME, JVM_METHOD.METHOD_NAME, JVM_METHOD.DESCRIPTOR,
                JVM_METHOD_PARAMETER.POSITION, JVM_METHOD_PARAMETER.PARAMETER_NAME)
            .from(JVM_METHOD)
            .leftJoin(JVM_METHOD_PARAMETER)
            .on(JVM_METHOD_PARAMETER.SOURCE_NAME.eq(JVM_METHOD.SOURCE_NAME))
            .and(JVM_METHOD_PARAMETER.CLASS_NAME.eq(JVM_METHOD.CLASS_NAME))
            .and(JVM_METHOD_PARAMETER.METHOD_NAME.eq(JVM_METHOD.METHOD_NAME))
            .and(JVM_METHOD_PARAMETER.DESCRIPTOR.eq(JVM_METHOD.DESCRIPTOR))
            .where(store.reads(JVM_METHOD.SOURCE_NAME))
            .and(named)
            .orderBy(JVM_METHOD.CLASS_NAME, JVM_METHOD.METHOD_NAME, JVM_METHOD.DESCRIPTOR,
                JVM_METHOD_PARAMETER.POSITION))
            .convertFrom(rows -> rows.map(Records.mapping(ParameterRow::new)));
    }

    /**
     * The site's own resolution and the columns of the table it names, left-joined: a resolution naming
     * a table the census holds no column for is a row with no column, which is silence rather than a
     * wrong name, and an inner join would have made it indistinguishable from a site with no
     * resolution at all.
     */
    private static Field<List<OverrideRow>> overrideArm(
        StoreHandle store, Collection<FieldCoord> sites
    ) {
        var site = INTENT_FIELD_COLUMN_TABLE;
        return multiset(select(site.TYPE_NAME, site.FIELD_NAME, site.DISPOSITION,
                site.TABLE_SOURCE_NAME, site.TABLE_SCHEMA, site.TABLE_NAME,
                SQL_COLUMN.COLUMN_NAME, SQL_COLUMN.JOOQ_NAME)
            .from(site)
            .leftJoin(SQL_COLUMN)
            .on(SQL_COLUMN.SOURCE_NAME.eq(site.TABLE_SOURCE_NAME))
            .and(SQL_COLUMN.TABLE_SCHEMA.eq(site.TABLE_SCHEMA))
            .and(SQL_COLUMN.TABLE_NAME.eq(site.TABLE_NAME))
            .where(site.GRAPH_NAME.eq(store.graphName()))
            .and(coordinateIn(site.TYPE_NAME, site.FIELD_NAME, sites))
            .orderBy(site.TYPE_NAME, site.FIELD_NAME, SQL_COLUMN.ORDINAL))
            .convertFrom(rows -> rows.map(Records.mapping(OverrideRow::new)));
    }

    /**
     * Every column of every table the document's types are bound to, in the schema then table order
     * {@code BoundTables} answers in, so a message naming the first candidate names the one it would
     * have named. Left-joined for the reason the arm above is.
     */
    private static Field<List<ParentRow>> parentArm(
        StoreHandle store, Collection<String> typeNames
    ) {
        return multiset(select(INTENT_BOUND_TABLE.TYPE_NAME, INTENT_BOUND_TABLE.TABLE_SOURCE_NAME,
                INTENT_BOUND_TABLE.TABLE_SCHEMA, INTENT_BOUND_TABLE.TABLE_NAME,
                SQL_COLUMN.COLUMN_NAME, SQL_COLUMN.JOOQ_NAME)
            .from(INTENT_BOUND_TABLE)
            .leftJoin(SQL_COLUMN)
            .on(SQL_COLUMN.SOURCE_NAME.eq(INTENT_BOUND_TABLE.TABLE_SOURCE_NAME))
            .and(SQL_COLUMN.TABLE_SCHEMA.eq(INTENT_BOUND_TABLE.TABLE_SCHEMA))
            .and(SQL_COLUMN.TABLE_NAME.eq(INTENT_BOUND_TABLE.TABLE_NAME))
            .where(INTENT_BOUND_TABLE.GRAPH_NAME.eq(store.graphName()))
            .and(INTENT_BOUND_TABLE.TYPE_NAME.in(typeNames))
            .orderBy(INTENT_BOUND_TABLE.TYPE_NAME, INTENT_BOUND_TABLE.TABLE_SCHEMA,
                INTENT_BOUND_TABLE.TABLE_NAME, SQL_COLUMN.ORDINAL))
            .convertFrom(rows -> rows.map(Records.mapping(ParentRow::new)));
    }

    /**
     * One backing population, either of the two the grounding rule chooses between. Both arms have the
     * same shape over different relations, which is why they are one method called twice rather than
     * two: what separates them is which relation grounds a class, and that is the parameter.
     */
    private static Field<List<BackingRow>> backingArm(
        StoreHandle store, Collection<String> typeNames, TableField<?, String> typeColumn,
        TableField<?, String> classColumn, TableField<?, String> graphColumn
    ) {
        return multiset(selectDistinct(typeColumn, classColumn)
            .from(typeColumn.getTable())
            .where(graphColumn.eq(store.graphName()))
            .and(typeColumn.in(typeNames))
            .orderBy(typeColumn, classColumn))
            .convertFrom(rows -> rows.map(Records.mapping(BackingRow::new)));
    }

    /**
     * A candidate backing class read back as the table whose rows jOOQ binds to it, with that table's
     * columns. The join {@code CatalogTables.ofRecordClass} performs, composed here rather than run per
     * class: a type whose backing turns out to be a generated record scopes to that record's table, and
     * the class census excludes the generated package, so this is the only route such a type has to a
     * member name at all.
     *
     * <p>A type its own {@code @table} answers is in here too, its binding reaching a record class on
     * the backing relation's own table arm, and the rows are redundant for it. Deliberately: excluding
     * them would mean an anti-join against the binding arm, which is this arm asserting the precedence
     * that {@link TypeMemberScope#resolve} owns, and an arm conditional on another arm's rows is the one
     * property that makes a statement's arms readable apart. The cost is rows in a payload rather than a
     * round trip, and the judgement takes the binding first regardless.
     */
    private static Field<List<RedirectRow>> redirectArm(
        StoreHandle store, Collection<String> typeNames
    ) {
        return multiset(select(INTENT_TYPE_BACKING.TYPE_NAME, INTENT_TYPE_BACKING.CLASS_NAME,
                SQL_TABLE.SOURCE_NAME, SQL_TABLE.TABLE_SCHEMA, SQL_TABLE.TABLE_NAME,
                SQL_COLUMN.COLUMN_NAME, SQL_COLUMN.JOOQ_NAME)
            .from(INTENT_TYPE_BACKING)
            .join(SQL_TABLE).on(SQL_TABLE.RECORD_CLASS_FQN.eq(INTENT_TYPE_BACKING.CLASS_NAME))
            .leftJoin(SQL_COLUMN)
            .on(SQL_COLUMN.SOURCE_NAME.eq(SQL_TABLE.SOURCE_NAME))
            .and(SQL_COLUMN.TABLE_SCHEMA.eq(SQL_TABLE.TABLE_SCHEMA))
            .and(SQL_COLUMN.TABLE_NAME.eq(SQL_TABLE.TABLE_NAME))
            .where(INTENT_TYPE_BACKING.GRAPH_NAME.eq(store.graphName()))
            .and(INTENT_TYPE_BACKING.TYPE_NAME.in(typeNames))
            .and(store.reads(SQL_TABLE.SOURCE_NAME))
            .orderBy(INTENT_TYPE_BACKING.TYPE_NAME, SQL_TABLE.TABLE_SCHEMA, SQL_TABLE.TABLE_NAME,
                SQL_COLUMN.ORDINAL))
            .convertFrom(rows -> rows.map(Records.mapping(RedirectRow::new)));
    }

    /**
     * The slots every candidate backing class of the document's types offers, keyed by class rather
     * than by type: which class a type resolves to is the grounding rule's answer, applied over the two
     * populations after this arm has returned, and a slot is a fact about a class either way.
     */
    private static Field<List<SlotRow>> slotArm(StoreHandle store, Collection<String> typeNames) {
        return multiset(selectDistinct(INTENT_CLASS_MEMBER_SLOT.CLASS_NAME,
                INTENT_CLASS_MEMBER_SLOT.SLOT_NAME, INTENT_CLASS_MEMBER_SLOT.ORIGIN)
            .from(INTENT_CLASS_MEMBER_SLOT)
            .join(INTENT_TYPE_BACKING)
            .on(INTENT_TYPE_BACKING.CLASS_NAME.eq(INTENT_CLASS_MEMBER_SLOT.CLASS_NAME))
            .where(store.reads(INTENT_CLASS_MEMBER_SLOT.SOURCE_NAME))
            .and(INTENT_TYPE_BACKING.GRAPH_NAME.eq(store.graphName()))
            .and(INTENT_TYPE_BACKING.TYPE_NAME.in(typeNames))
            .orderBy(INTENT_CLASS_MEMBER_SLOT.CLASS_NAME, INTENT_CLASS_MEMBER_SLOT.SLOT_NAME))
            .convertFrom(rows -> rows.map(Records.mapping(SlotRow::new)));
    }

    /**
     * Which of the coordinates carrying a written {@code $source} are sites the sigil belongs at.
     * The narrowing is {@link CarrierDataField#sigilSite}'s, shared with the completion that offers
     * the sigil, so the two surfaces cannot come to disagree about where it belongs.
     */
    private static Field<List<FieldCoord>> sigilSiteArm(
        StoreHandle store, Collection<FieldCoord> sites
    ) {
        var carrier = INTENT_CARRIER_DATA_FIELD;
        return multiset(selectDistinct(carrier.TYPE_NAME, carrier.FIELD_NAME)
            .from(carrier)
            .where(carrier.GRAPH_NAME.eq(store.graphName()))
            .and(coordinateIn(carrier.TYPE_NAME, carrier.FIELD_NAME, sites))
            .and(CarrierDataField.sigilSite())
            .orderBy(carrier.TYPE_NAME, carrier.FIELD_NAME))
            .convertFrom(rows -> rows.map(Records.mapping(FieldCoord::new)));
    }

    /**
     * Which of the types a written {@code $source} sits inside the store holds a declaration for. The
     * census this arm defers on is the graph's own SDL, so no row means the parent is a type no
     * capture has read rather than one the store judged.
     */
    private static Field<List<String>> declaredTypeArm(
        StoreHandle store, Collection<String> typeNames
    ) {
        return multiset(selectDistinct(GRAPHQL_TYPE.TYPE_NAME)
            .from(GRAPHQL_TYPE)
            .where(GRAPHQL_TYPE.GRAPH_NAME.eq(store.graphName()))
            .and(GRAPHQL_TYPE.TYPE_NAME.in(typeNames))
            .orderBy(GRAPHQL_TYPE.TYPE_NAME))
            .convertFrom(rows -> rows.map(Record1::value1));
    }

    /** A disjunction over whole coordinates, which is what keys the arms a coordinate's rows come from. */
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
