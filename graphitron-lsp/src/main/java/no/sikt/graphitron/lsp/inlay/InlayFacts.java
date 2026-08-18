package no.sikt.graphitron.lsp.inlay;

import no.sikt.graphitron.lsp.facts.CatalogTable;
import no.sikt.graphitron.lsp.facts.SeparateFetchRule;
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

import static no.sikt.graphitron.model.Tables.INTENT_AUTHORED_TYPE_CLAIM;
import static no.sikt.graphitron.model.Tables.INTENT_BOUND_TABLE;
import static no.sikt.graphitron.model.Tables.INTENT_CLASS_MEMBER_SLOT;
import static no.sikt.graphitron.model.Tables.INTENT_COLUMN_MATCH_CLAIM;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_SEPARATE_FETCH;
import static no.sikt.graphitron.model.Tables.INTENT_RESOLVED_FIELD_CLAIM;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_BACKING;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_BACKING_SEED;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.jooq.impl.DSL.falseCondition;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.selectDistinct;

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
 * <p>Two arms deliberately return rows no renderer will read. The bindings are asked for every type a
 * member site names as well as every type an overlay might render a {@code @table} for, and the
 * backings for every visible declaration as well as every member site's type, because narrowing
 * either would mean an arm asserting a precedence its reader owns: which of a claim and a backing
 * labels a type, and whether a binding or a class scopes a name, are decisions
 * {@link TypeBackingClass#resolve} and {@link TypeMemberScope#resolve} make after every arm has
 * returned. The cost is rows in a payload rather than a round trip.
 */
final class InlayFacts {

    private InlayFacts() {}

    /** A field coordinate, which is what the two field-grain arms are keyed on. */
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

        /** A visible declaration of any kind, which the claim and round-trip arms are keyed on. */
        void declaration(String typeName) {
            declaredTypeNames.add(typeName);
        }

        /**
         * A visible type declaration. Its label falls back to the class standing for the type where no
         * claim names it, so the type grain asks one question the field grain does not.
         */
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
         * A site where a {@code @field} overlay may render. A member name resolves against the site's
         * own match first and against the type's scope second, and that scope is the type's bindings,
         * its backing class and the tables that class is a record of, so asking about the site is
         * asking about all four.
         */
        void memberSite(String typeName, String fieldName) {
            memberSites.add(new FieldCoord(typeName, fieldName));
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

    /** One classifier standing at a type declaration; several rows are what makes it contested. */
    record TypeClaimRow(String typeName, String classifier) {}

    /** One classifier standing at a field coordinate, on the same terms. */
    record FieldClaimRow(String typeName, String fieldName, String classifier) {}

    /** One rule by which a field's rows come from a statement of their own. */
    record RuleRow(String typeName, String fieldName, String rule) {}

    /** One candidate class standing for a type, from either of the two backing populations. */
    record BackingRow(String typeName, String className) {}

    /**
     * One table a type's {@code @table} resolves to.
     *
     * @param candidates the binding's own arity, read from the view rather than counted here, which is
     *                   what lets an overlay refuse to speak where two tables answer to one name
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

    /** The column the match reduction settled on at a coordinate. */
    record ColumnMatchRow(String typeName, String fieldName, String columnName) {}

    /** One member slot a backing class offers, keyed by class because a slot is a fact about one. */
    record SlotRow(String className, String slotName) {}

    /** Every question the region asked, answered. */
    record Answers(
        List<TypeClaimRow> typeClaims,
        List<FieldClaimRow> fieldClaims,
        List<RuleRow> separateFetchRules,
        List<BackingRow> backingSeeds,
        List<BackingRow> backingReached,
        List<BoundTableRow> boundTables,
        List<RedirectRow> redirects,
        List<ColumnMatchRow> columnMatches,
        List<SlotRow> slots
    ) {

        /** The classifiers claiming a type, in the classifier order the arm returned them in. */
        List<String> typeClassifiers(String typeName) {
            return typeClaims.stream()
                .filter(row -> row.typeName().equals(typeName))
                .map(TypeClaimRow::classifier)
                .toList();
        }

        /** The classifiers claiming a field coordinate. */
        List<String> fieldClassifiers(String typeName, String fieldName) {
            return fieldClaims.stream()
                .filter(row -> row.typeName().equals(typeName) && row.fieldName().equals(fieldName))
                .map(FieldClaimRow::classifier)
                .toList();
        }

        /** Whether a round-trip marker at this coordinate would tell a reader something. */
        boolean marksSeparateFetch(String typeName, String fieldName) {
            return SeparateFetchRule.marksInline(separateFetchRules.stream()
                .filter(row -> row.typeName().equals(typeName) && row.fieldName().equals(fieldName))
                .map(RuleRow::rule)
                .toList());
        }

        /** The class standing for a type, by the grounding rule the backing reader owns. */
        Optional<String> backingClass(String typeName) {
            return TypeBackingClass.resolve(
                classNamesOf(backingSeeds, typeName), classNamesOf(backingReached, typeName));
        }

        /**
         * The one table a type is bound to, absent where the binding is ambiguous. The arity is the
         * view's own column, so an overlay never re-derives it from the rows this arm brought back.
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
         * {@code @field(name:)}. Both arms of {@code FieldMemberName} over rows already in hand: the
         * site's settled column first, then the type's scope, which answers only where that scope is a
         * class's. A scope that turned out to be a table's has no answer here, the match for such a
         * site not being derived by any relation, and the absence is the store's rather than this
         * reader's.
         */
        Optional<String> memberName(String typeName, String fieldName) {
            var column = columnMatches.stream()
                .filter(row -> row.typeName().equals(typeName) && row.fieldName().equals(fieldName))
                .findFirst()
                .map(ColumnMatchRow::columnName);
            if (column.isPresent()) return column;
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
        return new Answers(List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of());
    }

    /** Every question the region asked, answered in one statement. */
    static Answers of(StoreHandle store, Questions questions) {
        if (questions.isEmpty()) return none();
        String graph = store.graphName();
        return store.dsl()
            .select(
                multiset(selectDistinct(INTENT_AUTHORED_TYPE_CLAIM.TYPE_NAME,
                        INTENT_AUTHORED_TYPE_CLAIM.CLASSIFIER)
                    .from(INTENT_AUTHORED_TYPE_CLAIM)
                    .where(INTENT_AUTHORED_TYPE_CLAIM.GRAPH_NAME.eq(graph))
                    .and(INTENT_AUTHORED_TYPE_CLAIM.TYPE_NAME.in(questions.declaredTypeNames))
                    .orderBy(INTENT_AUTHORED_TYPE_CLAIM.TYPE_NAME,
                        INTENT_AUTHORED_TYPE_CLAIM.CLASSIFIER))
                    .convertFrom(rows -> rows.map(Records.mapping(TypeClaimRow::new))),
                multiset(selectDistinct(INTENT_RESOLVED_FIELD_CLAIM.TYPE_NAME,
                        INTENT_RESOLVED_FIELD_CLAIM.FIELD_NAME, INTENT_RESOLVED_FIELD_CLAIM.CLASSIFIER)
                    .from(INTENT_RESOLVED_FIELD_CLAIM)
                    .where(INTENT_RESOLVED_FIELD_CLAIM.GRAPH_NAME.eq(graph))
                    .and(INTENT_RESOLVED_FIELD_CLAIM.TYPE_NAME.in(questions.declaredTypeNames))
                    .orderBy(INTENT_RESOLVED_FIELD_CLAIM.TYPE_NAME,
                        INTENT_RESOLVED_FIELD_CLAIM.FIELD_NAME, INTENT_RESOLVED_FIELD_CLAIM.CLASSIFIER))
                    .convertFrom(rows -> rows.map(Records.mapping(FieldClaimRow::new))),
                multiset(selectDistinct(INTENT_FIELD_SEPARATE_FETCH.TYPE_NAME,
                        INTENT_FIELD_SEPARATE_FETCH.FIELD_NAME, INTENT_FIELD_SEPARATE_FETCH.RULE)
                    .from(INTENT_FIELD_SEPARATE_FETCH)
                    .where(INTENT_FIELD_SEPARATE_FETCH.GRAPH_NAME.eq(graph))
                    .and(INTENT_FIELD_SEPARATE_FETCH.TYPE_NAME.in(questions.declaredTypeNames))
                    .orderBy(INTENT_FIELD_SEPARATE_FETCH.TYPE_NAME,
                        INTENT_FIELD_SEPARATE_FETCH.FIELD_NAME, INTENT_FIELD_SEPARATE_FETCH.RULE))
                    .convertFrom(rows -> rows.map(Records.mapping(RuleRow::new))),
                backingArm(store, questions.backingTypeNames, INTENT_TYPE_BACKING_SEED.TYPE_NAME,
                    INTENT_TYPE_BACKING_SEED.CLASS_NAME, INTENT_TYPE_BACKING_SEED.GRAPH_NAME),
                backingArm(store, questions.backingTypeNames, INTENT_TYPE_BACKING.TYPE_NAME,
                    INTENT_TYPE_BACKING.CLASS_NAME, INTENT_TYPE_BACKING.GRAPH_NAME),
                boundTableArm(store, questions.boundTableTypeNames),
                redirectArm(store, questions.memberTypeNames),
                columnMatchArm(store, questions.memberSites),
                slotArm(store, questions.memberTypeNames))
            .fetchOne(Records.mapping(Answers::new));
    }

    /**
     * One backing population, either of the two the grounding rule chooses between. Both have the same
     * shape over different relations, which is why this is one method called twice: what separates them
     * is which relation grounds a class, and that is the parameter.
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
     * Every table the region's types are bound to, in the schema then table order {@code BoundTables}
     * answers in, so an overlay naming the first candidate names the one it would have named. The
     * arity rides along rather than being filtered here: the same rows serve the overlay, which may
     * only speak when the binding is certain, and the member scope, which takes every candidate.
     */
    private static Field<List<BoundTableRow>> boundTableArm(
        StoreHandle store, Collection<String> typeNames
    ) {
        return multiset(selectDistinct(INTENT_BOUND_TABLE.TYPE_NAME, INTENT_BOUND_TABLE.CANDIDATES,
                INTENT_BOUND_TABLE.TABLE_SOURCE_NAME, INTENT_BOUND_TABLE.TABLE_SCHEMA,
                INTENT_BOUND_TABLE.TABLE_NAME)
            .from(INTENT_BOUND_TABLE)
            .where(INTENT_BOUND_TABLE.GRAPH_NAME.eq(store.graphName()))
            .and(INTENT_BOUND_TABLE.TYPE_NAME.in(typeNames))
            .orderBy(INTENT_BOUND_TABLE.TYPE_NAME, INTENT_BOUND_TABLE.TABLE_SCHEMA,
                INTENT_BOUND_TABLE.TABLE_NAME))
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
        return multiset(selectDistinct(INTENT_TYPE_BACKING.TYPE_NAME, INTENT_TYPE_BACKING.CLASS_NAME,
                SQL_TABLE.SOURCE_NAME, SQL_TABLE.TABLE_SCHEMA, SQL_TABLE.TABLE_NAME)
            .from(INTENT_TYPE_BACKING)
            .join(SQL_TABLE).on(SQL_TABLE.RECORD_CLASS_FQN.eq(INTENT_TYPE_BACKING.CLASS_NAME))
            .where(INTENT_TYPE_BACKING.GRAPH_NAME.eq(store.graphName()))
            .and(INTENT_TYPE_BACKING.TYPE_NAME.in(typeNames))
            .and(store.reads(SQL_TABLE.SOURCE_NAME))
            .orderBy(INTENT_TYPE_BACKING.TYPE_NAME, SQL_TABLE.TABLE_SCHEMA, SQL_TABLE.TABLE_NAME))
            .convertFrom(rows -> rows.map(Records.mapping(RedirectRow::new)));
    }

    /**
     * The column the match reduction settled on at each site, read through the resolved claim rather
     * than raw: a coordinate an authored directive claims is one the generator reads no column at, and
     * the raw reading is for a surface explaining an override rather than for one naming the member.
     */
    private static Field<List<ColumnMatchRow>> columnMatchArm(
        StoreHandle store, Collection<FieldCoord> sites
    ) {
        var claim = INTENT_COLUMN_MATCH_CLAIM;
        var resolved = INTENT_RESOLVED_FIELD_CLAIM;
        return multiset(selectDistinct(claim.TYPE_NAME, claim.FIELD_NAME, claim.COLUMN_NAME)
            .from(claim)
            .join(resolved)
            .on(resolved.GRAPH_NAME.eq(claim.GRAPH_NAME))
            .and(resolved.TYPE_NAME.eq(claim.TYPE_NAME))
            .and(resolved.FIELD_NAME.eq(claim.FIELD_NAME))
            .and(resolved.CLASSIFIER.eq(claim.CLASSIFIER))
            .where(claim.GRAPH_NAME.eq(store.graphName()))
            .and(coordinateIn(claim.TYPE_NAME, claim.FIELD_NAME, sites))
            .orderBy(claim.TYPE_NAME, claim.FIELD_NAME, claim.COLUMN_NAME))
            .convertFrom(rows -> rows.map(Records.mapping(ColumnMatchRow::new)));
    }

    /**
     * The slots every candidate backing class of the region's member-site types offers, keyed by class
     * rather than by type: which class a type resolves to is the grounding rule's answer, applied after
     * this arm has returned, and a slot is a fact about a class either way.
     */
    private static Field<List<SlotRow>> slotArm(StoreHandle store, Collection<String> typeNames) {
        return multiset(selectDistinct(INTENT_CLASS_MEMBER_SLOT.CLASS_NAME,
                INTENT_CLASS_MEMBER_SLOT.SLOT_NAME)
            .from(INTENT_CLASS_MEMBER_SLOT)
            .join(INTENT_TYPE_BACKING)
            .on(INTENT_TYPE_BACKING.CLASS_NAME.eq(INTENT_CLASS_MEMBER_SLOT.CLASS_NAME))
            .where(store.reads(INTENT_CLASS_MEMBER_SLOT.SOURCE_NAME))
            .and(INTENT_TYPE_BACKING.GRAPH_NAME.eq(store.graphName()))
            .and(INTENT_TYPE_BACKING.TYPE_NAME.in(typeNames))
            .orderBy(INTENT_CLASS_MEMBER_SLOT.CLASS_NAME, INTENT_CLASS_MEMBER_SLOT.SLOT_NAME))
            .convertFrom(rows -> rows.map(Records.mapping(SlotRow::new)));
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
