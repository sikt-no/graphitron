package no.sikt.graphitron.lsp.facts;

import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Condition;
import org.jooq.Record1;
import org.jooq.Records;

import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ERROR_HANDLER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_EXTERNAL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_NODE_ID;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MUTATION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ROUTINE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_SERVICE;
import static no.sikt.graphitron.model.Tables.INTENT_AUTHORED_TYPE_CLAIM;
import static no.sikt.graphitron.model.Tables.INTENT_BOUND_TABLE;
import static no.sikt.graphitron.model.Tables.INTENT_COLUMN_MATCH_CLAIM;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_REFERENCE_STEP_TARGET;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_SEPARATE_FETCH;
import static no.sikt.graphitron.model.Tables.INTENT_RESOLVED_FIELD_CLAIM;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_BACKING;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_BACKING_CONFLICT;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_BACKING_SEED;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectDistinct;

/**
 * What a claimed declaration carries beyond its classifier, read from the relation that owns each
 * fact. The claim relations answer what graphitron takes a declaration to be; this answers the detail
 * behind that answer, without any projection carrying every payload any variant might have.
 *
 * <p>The split between relations is the whole point. A classifier is a word, and the facts behind it
 * belong to different relations on different keys: a column match knows a table and a column and
 * which naming tier matched, a service knows a class and a method, a routine knows an ordered list of
 * names. A single record with all of those components would be mostly null at every coordinate, which
 * is the shape the sealed projection had and the reason a claim can grow a fact here without any
 * other claim's reader changing.
 *
 * <p>The split is not a reason to issue a statement per relation, and neither {@link #ofField} nor
 * {@link #ofType} does. One declaration read is one statement: a multiset per arm, each subquery
 * reading its own relation with its own columns and its own ordering, assembled into one row. Nothing
 * is joined, so no arm's fan-out can multiply another's, and each arm keeps the small record its
 * relation earns rather than contributing nullable columns to a shared one. What the classifier then
 * selects is a rendering decision over rows already in hand.
 *
 * <p>Two facts are read without asking the classifier first, because they hold across claims: the
 * join path an authored {@code @reference} resolves to, and whether the field is fetched by a
 * statement of its own. The second is the round-trip question, and it is a property of the field's
 * delivery rather than of what claims it: a {@code @splitQuery} column-matched field and a
 * {@code @service} child field are separately fetched for unrelated reasons. Both sit beside the
 * claims in the same statement rather than under them, which is what keeps a coordinate no claim
 * reaches from losing them.
 */
public final class ClaimFacts {

    private ClaimFacts() {}

    /** One resolved element of a field's {@code @reference} path: the key it joins on, and where it lands. */
    public record JoinStep(String constraintName, String toTable) {}

    /** One claim standing at a coordinate: its classifier, and the facts behind that classifier. */
    public record ClaimBlock(String classifier, List<DeclarationFact> facts) {}

    /**
     * Everything the store says about one field declaration: the claims standing at it with their
     * facts, and the two facts that hold whether or not anything claims it.
     */
    public record FieldBlock(List<ClaimBlock> claims, List<JoinStep> joinPath, List<String> fetchRules) {

        /** The store says nothing about the coordinate at all, which is a declaration with no block. */
        public boolean isEmpty() {
            return claims.isEmpty() && joinPath.isEmpty() && fetchRules.isEmpty();
        }

        /** The classifiers claiming the coordinate, in the order the claims were read. */
        public List<String> classifiers() {
            return classifiersOf(claims);
        }
    }

    /**
     * Everything the store says about one type declaration: the claims standing at it with their
     * facts, and, for a type no claim reaches, the class standing for it or the classes it cannot be
     * resolved between. At most one of the last two is present, {@code contested} being what the
     * store answers where {@code backing} is the answer it declines to give.
     */
    public record TypeBlock(List<ClaimBlock> claims, String backing, String contested) {

        /** The store says nothing about the type at all, which is a declaration with no block. */
        public boolean isEmpty() {
            return claims.isEmpty() && backing == null && contested == null;
        }

        /** The classifiers claiming the type, in the order the claims were read. */
        public List<String> classifiers() {
            return classifiersOf(claims);
        }
    }

    private static List<String> classifiersOf(List<ClaimBlock> claims) {
        return claims.stream().map(ClaimBlock::classifier).toList();
    }

    /**
     * Everything about {@code Type.field}, in one statement. A coordinate several classifiers claim
     * answers with a block each, in the classifiers' own alphabetical order, so one coordinate reads
     * the same way on every request and a conflict renders as the claims it is made of.
     *
     * <p>A claim contributes no facts where its whole content is the claim itself ({@code LOOKUP_KEY}
     * is a marker), and none where its relation holds no row, which is the state a presence-arm claim
     * is in: the directive is there and its arguments did not decode.
     */
    public static FieldBlock ofField(StoreHandle store, String typeName, String fieldName) {
        var arms = arms(store, typeName, fieldName);
        var claims = new ArrayList<ClaimBlock>(arms.classifiers().size());
        for (String classifier : arms.classifiers()) {
            claims.add(new ClaimBlock(classifier, factsOf(classifier, arms, fieldName)));
        }
        return new FieldBlock(claims, joinPath(arms.steps()), arms.fetchRules());
    }

    /**
     * Everything about the type {@code typeName}, in one statement. {@code TABLE} answers with every
     * candidate its binding resolves to, ambiguity being rows here as it is in the view;
     * {@code ERROR} answers with its handlers in declaration order.
     *
     * <p>The backing is read beside the claims for the same reason the field block reads its
     * round-trip rule beside them: a type a {@code @service} return hands back carries no type
     * directive, so no claim names it, and the class its producer returns is the whole of what the
     * store knows it to be. Which of the two a surface shows is that surface's decision; both are
     * answered here.
     *
     * <p>{@code contested} is the one thing gated, and on {@code backing} rather than on a claim:
     * {@link TypeBackingClass} declines to name a class for a type its populations disagree about,
     * and the arity behind that decline is only an answer where the decline happened.
     */
    public static TypeBlock ofType(StoreHandle store, String typeName) {
        var arms = typeArms(store, typeName);
        var claims = new ArrayList<ClaimBlock>(arms.classifiers().size());
        for (String classifier : arms.classifiers()) {
            claims.add(new ClaimBlock(classifier, typeFactsOf(classifier, arms)));
        }
        var backing = TypeBackingClass.resolve(arms.grounded(), arms.reached()).orElse(null);
        return new TypeBlock(claims, backing, backing == null ? first(arms.contested()) : null);
    }

    /**
     * The nine arms of the field-declaration read, each its own subquery over the relation that owns
     * the fact, all in one statement over no table at all. Nothing drives the statement, which is
     * deliberate: driving it from the claim relation would have made the join path and the
     * separate-fetch rules conditional on a claim existing, and the coordinate that most needs those
     * two facts is the one no claim reaches.
     *
     * <p>Every arm but three holds at most one row, by its relation's own key; they are read as lists
     * anyway so that absence is an empty list in every arm rather than a null in some and an empty
     * list in others.
     */
    private static Arms arms(StoreHandle store, String typeName, String fieldName) {
        String graph = store.graphName();
        return store.dsl()
            .select(
                multiset(selectDistinct(INTENT_RESOLVED_FIELD_CLAIM.CLASSIFIER)
                    .from(INTENT_RESOLVED_FIELD_CLAIM)
                    .where(coordinate(INTENT_RESOLVED_FIELD_CLAIM.GRAPH_NAME.eq(graph),
                        INTENT_RESOLVED_FIELD_CLAIM.TYPE_NAME.eq(typeName),
                        INTENT_RESOLVED_FIELD_CLAIM.FIELD_NAME.eq(fieldName)))
                    .orderBy(INTENT_RESOLVED_FIELD_CLAIM.CLASSIFIER))
                    .convertFrom(rows -> rows.map(Record1::value1)),
                multiset(select(INTENT_COLUMN_MATCH_CLAIM.TABLE_NAME,
                    INTENT_COLUMN_MATCH_CLAIM.COLUMN_NAME, INTENT_COLUMN_MATCH_CLAIM.MATCHED_NAME,
                    INTENT_COLUMN_MATCH_CLAIM.MATCHED_BY)
                    .from(INTENT_COLUMN_MATCH_CLAIM)
                    .where(coordinate(INTENT_COLUMN_MATCH_CLAIM.GRAPH_NAME.eq(graph),
                        INTENT_COLUMN_MATCH_CLAIM.TYPE_NAME.eq(typeName),
                        INTENT_COLUMN_MATCH_CLAIM.FIELD_NAME.eq(fieldName))))
                    .convertFrom(rows -> rows.map(Records.mapping(ColumnMatch::new))),
                multiset(select(GRAPHITRON_SERVICE.CLASS_NAME, GRAPHITRON_SERVICE.METHOD)
                    .from(GRAPHITRON_SERVICE)
                    .where(coordinate(GRAPHITRON_SERVICE.GRAPH_NAME.eq(graph),
                        GRAPHITRON_SERVICE.TYPE_NAME.eq(typeName),
                        GRAPHITRON_SERVICE.FIELD_NAME.eq(fieldName))))
                    .convertFrom(rows -> rows.map(Records.mapping(Member::new))),
                multiset(select(GRAPHITRON_EXTERNAL_FIELD.CLASS_NAME, GRAPHITRON_EXTERNAL_FIELD.METHOD)
                    .from(GRAPHITRON_EXTERNAL_FIELD)
                    .where(coordinate(GRAPHITRON_EXTERNAL_FIELD.GRAPH_NAME.eq(graph),
                        GRAPHITRON_EXTERNAL_FIELD.TYPE_NAME.eq(typeName),
                        GRAPHITRON_EXTERNAL_FIELD.FIELD_NAME.eq(fieldName))))
                    .convertFrom(rows -> rows.map(Records.mapping(Member::new))),
                multiset(select(GRAPHITRON_FIELD_NODE_ID.NODE_TYPE_REF)
                    .from(GRAPHITRON_FIELD_NODE_ID)
                    .where(coordinate(GRAPHITRON_FIELD_NODE_ID.GRAPH_NAME.eq(graph),
                        GRAPHITRON_FIELD_NODE_ID.TYPE_NAME.eq(typeName),
                        GRAPHITRON_FIELD_NODE_ID.FIELD_NAME.eq(fieldName))))
                    .convertFrom(rows -> rows.map(Record1::value1)),
                // One row per application, in application order: the directive is repeatable and each
                // row is a routine of its own, so a chained field reads as the chain it is.
                multiset(select(GRAPHITRON_ROUTINE.ROUTINE_REF)
                    .from(GRAPHITRON_ROUTINE)
                    .where(coordinate(GRAPHITRON_ROUTINE.GRAPH_NAME.eq(graph),
                        GRAPHITRON_ROUTINE.TYPE_NAME.eq(typeName),
                        GRAPHITRON_ROUTINE.FIELD_NAME.eq(fieldName)))
                    .orderBy(GRAPHITRON_ROUTINE.ORDINAL))
                    .convertFrom(rows -> rows.map(Record1::value1)),
                multiset(select(GRAPHITRON_MUTATION.OPERATION, GRAPHITRON_MUTATION.TABLE_REF,
                    GRAPHITRON_MUTATION.MULTI_ROW)
                    .from(GRAPHITRON_MUTATION)
                    .where(coordinate(GRAPHITRON_MUTATION.GRAPH_NAME.eq(graph),
                        GRAPHITRON_MUTATION.TYPE_NAME.eq(typeName),
                        GRAPHITRON_MUTATION.FIELD_NAME.eq(fieldName))))
                    .convertFrom(rows -> rows.map(Records.mapping(Mutation::new))),
                // Only elements whose destination is certain: a path the chain could not walk
                // contributes nothing rather than a partial route.
                multiset(select(INTENT_FIELD_REFERENCE_STEP_TARGET.ORDINAL,
                    INTENT_FIELD_REFERENCE_STEP_TARGET.CONSTRAINT_NAME,
                    INTENT_FIELD_REFERENCE_STEP_TARGET.TO_TABLE)
                    .from(INTENT_FIELD_REFERENCE_STEP_TARGET)
                    .where(coordinate(INTENT_FIELD_REFERENCE_STEP_TARGET.GRAPH_NAME.eq(graph),
                        INTENT_FIELD_REFERENCE_STEP_TARGET.TYPE_NAME.eq(typeName),
                        INTENT_FIELD_REFERENCE_STEP_TARGET.FIELD_NAME.eq(fieldName)))
                    .and(INTENT_FIELD_REFERENCE_STEP_TARGET.TARGETS.eq(1))
                    .orderBy(INTENT_FIELD_REFERENCE_STEP_TARGET.ORDINAL,
                        INTENT_FIELD_REFERENCE_STEP_TARGET.POSITION))
                    .convertFrom(rows -> rows.map(Records.mapping(Step::new))),
                multiset(select(INTENT_FIELD_SEPARATE_FETCH.RULE)
                    .from(INTENT_FIELD_SEPARATE_FETCH)
                    .where(coordinate(INTENT_FIELD_SEPARATE_FETCH.GRAPH_NAME.eq(graph),
                        INTENT_FIELD_SEPARATE_FETCH.TYPE_NAME.eq(typeName),
                        INTENT_FIELD_SEPARATE_FETCH.FIELD_NAME.eq(fieldName)))
                    .orderBy(INTENT_FIELD_SEPARATE_FETCH.RULE))
                    .convertFrom(rows -> rows.map(Record1::value1)))
            .fetchOne(Records.mapping(Arms::new));
    }

    /** What one statement brought back, one component per relation read. */
    private record Arms(
        List<String> classifiers,
        List<ColumnMatch> columnMatch,
        List<Member> service,
        List<Member> externalField,
        List<String> nodeTypes,
        List<String> routines,
        List<Mutation> mutation,
        List<Step> steps,
        List<String> fetchRules
    ) {}

    private record ColumnMatch(String tableName, String columnName, String matchedName, String matchedBy) {}

    private record Member(String className, String methodName) {}

    private record Mutation(String operation, String tableRef, Boolean multiRow) {}

    /** A reference-path element as the relation holds it, before the applications are separated. */
    private record Step(Integer application, String constraintName, String toTable) {}

    /**
     * The six arms of the type-declaration read, on the same terms as the field one: a subquery per
     * relation, over no table at all, nothing joined. The two backing populations are arms of it
     * rather than a call to {@link TypeBackingClass}, because that reader answers about a collection
     * of types and this statement is about one; what it holds beyond the rows, the rule choosing
     * between them, is read from it by {@link TypeBackingClass#resolve}.
     *
     * <p>The bound-table arm reads the relation {@link BoundTables} reads, at the same ordering, and
     * takes only the name a hover line renders. Two readers of one relation is what composing a
     * statement costs; the alternative is a second round trip for a column already keyed on the
     * coordinate in hand.
     */
    private static TypeArms typeArms(StoreHandle store, String typeName) {
        String graph = store.graphName();
        return store.dsl()
            .select(
                multiset(selectDistinct(INTENT_AUTHORED_TYPE_CLAIM.CLASSIFIER)
                    .from(INTENT_AUTHORED_TYPE_CLAIM)
                    .where(INTENT_AUTHORED_TYPE_CLAIM.GRAPH_NAME.eq(graph))
                    .and(INTENT_AUTHORED_TYPE_CLAIM.TYPE_NAME.eq(typeName))
                    .orderBy(INTENT_AUTHORED_TYPE_CLAIM.CLASSIFIER))
                    .convertFrom(rows -> rows.map(Record1::value1)),
                multiset(select(INTENT_BOUND_TABLE.TABLE_NAME)
                    .from(INTENT_BOUND_TABLE)
                    .where(INTENT_BOUND_TABLE.GRAPH_NAME.eq(graph))
                    .and(INTENT_BOUND_TABLE.TYPE_NAME.eq(typeName))
                    .orderBy(INTENT_BOUND_TABLE.TABLE_SCHEMA, INTENT_BOUND_TABLE.TABLE_NAME))
                    .convertFrom(rows -> rows.map(Record1::value1)),
                multiset(select(GRAPHITRON_ERROR_HANDLER.HANDLER, GRAPHITRON_ERROR_HANDLER.CLASS_NAME)
                    .from(GRAPHITRON_ERROR_HANDLER)
                    .where(GRAPHITRON_ERROR_HANDLER.GRAPH_NAME.eq(graph))
                    .and(GRAPHITRON_ERROR_HANDLER.TYPE_NAME.eq(typeName))
                    .orderBy(GRAPHITRON_ERROR_HANDLER.POSITION))
                    .convertFrom(rows -> rows.map(Records.mapping(Handler::new))),
                multiset(selectDistinct(INTENT_TYPE_BACKING_SEED.CLASS_NAME)
                    .from(INTENT_TYPE_BACKING_SEED)
                    .where(INTENT_TYPE_BACKING_SEED.GRAPH_NAME.eq(graph))
                    .and(INTENT_TYPE_BACKING_SEED.TYPE_NAME.eq(typeName)))
                    .convertFrom(rows -> rows.map(Record1::value1)),
                multiset(selectDistinct(INTENT_TYPE_BACKING.CLASS_NAME)
                    .from(INTENT_TYPE_BACKING)
                    .where(INTENT_TYPE_BACKING.GRAPH_NAME.eq(graph))
                    .and(INTENT_TYPE_BACKING.TYPE_NAME.eq(typeName)))
                    .convertFrom(rows -> rows.map(Record1::value1)),
                multiset(select(INTENT_TYPE_BACKING_CONFLICT.CLASS_NAMES)
                    .from(INTENT_TYPE_BACKING_CONFLICT)
                    .where(INTENT_TYPE_BACKING_CONFLICT.GRAPH_NAME.eq(graph))
                    .and(INTENT_TYPE_BACKING_CONFLICT.TYPE_NAME.eq(typeName)))
                    .convertFrom(rows -> rows.map(Record1::value1)))
            .fetchOne(Records.mapping(TypeArms::new));
    }

    /** What the type statement brought back, one component per relation read. */
    private record TypeArms(
        List<String> classifiers,
        List<String> tables,
        List<Handler> handlers,
        List<String> grounded,
        List<String> reached,
        List<String> contested
    ) {}

    private record Handler(String handler, String className) {}

    /** The type grain's {@link #factsOf}: the same switch over rows already in hand. */
    private static List<DeclarationFact> typeFactsOf(String classifier, TypeArms arms) {
        return switch (classifier) {
            case "TABLE" -> labelled("Table", arms.tables());
            case "ERROR" -> handlerFacts(arms.handlers());
            default -> List.of();
        };
    }

    private static List<DeclarationFact> handlerFacts(List<Handler> rows) {
        var facts = new ArrayList<DeclarationFact>(rows.size());
        for (var row : rows) {
            add(facts, "Handler", row.className() == null
                ? row.handler()
                : row.handler() + " " + row.className());
        }
        return facts;
    }

    /**
     * Which arm a classifier reads, and how it labels what it finds. A switch with no query in it:
     * the rows are already in hand, and what remains is the one thing this layer owns, which of them
     * a given classifier is the answer to and what an author should see it called.
     */
    private static List<DeclarationFact> factsOf(String classifier, Arms arms, String fieldName) {
        return switch (classifier) {
            case "TABLE_COLUMN" -> columnMatchFacts(arms.columnMatch(), fieldName);
            case "SERVICE" -> memberFacts("Service", arms.service());
            case "EXTERNAL_FIELD" -> memberFacts("External field", arms.externalField());
            case "NODE_ID" -> labelled("Node type", arms.nodeTypes());
            case "ROUTINE" -> labelled("Routine", arms.routines());
            case "MUTATION" -> mutationFacts(arms.mutation());
            default -> List.of();
        };
    }

    /**
     * The first application's elements, the repeatable directive's ordinal grain collapsed the way
     * every other reader of the path collapses it.
     */
    private static List<JoinStep> joinPath(List<Step> steps) {
        var path = new ArrayList<JoinStep>(steps.size());
        Integer application = null;
        for (var step : steps) {
            if (application == null) application = step.application();
            if (!application.equals(step.application())) break;
            path.add(new JoinStep(step.constraintName(), step.toTable()));
        }
        return path;
    }

    private static List<DeclarationFact> columnMatchFacts(List<ColumnMatch> rows, String fieldName) {
        if (rows.isEmpty()) return List.of();
        var match = rows.getFirst();
        var facts = new ArrayList<DeclarationFact>();
        add(facts, "Column", match.columnName());
        add(facts, "Table", match.tableName());
        // The name that matched is only worth a line where it is not the field's own: a @field
        // binding is the author's redirection, and seeing which spelling the match ran on is the
        // difference between "graphitron read my binding" and "graphitron ignored it".
        if (match.matchedName() != null && !match.matchedName().equals(fieldName)) {
            add(facts, "Matched name", match.matchedName());
        }
        add(facts, "Matched by", "JOOQ_NAME".equals(match.matchedBy()) ? "generated name" : "SQL name");
        return facts;
    }

    private static List<DeclarationFact> memberFacts(String label, List<Member> rows) {
        var facts = new ArrayList<DeclarationFact>();
        if (!rows.isEmpty()) add(facts, label, member(rows.getFirst().className(), rows.getFirst().methodName()));
        return facts;
    }

    private static List<DeclarationFact> labelled(String label, List<String> values) {
        var facts = new ArrayList<DeclarationFact>(values.size());
        for (String value : values) add(facts, label, value);
        return facts;
    }

    /** {@code Class#method}, the class alone where the reference named no method, null where neither decoded. */
    private static String member(String className, String methodName) {
        if (className == null) return null;
        return methodName == null ? className : className + "#" + methodName;
    }

    private static List<DeclarationFact> mutationFacts(List<Mutation> rows) {
        if (rows.isEmpty()) return List.of();
        var mutation = rows.getFirst();
        var facts = new ArrayList<DeclarationFact>();
        add(facts, "Operation", mutation.operation());
        add(facts, "Table", mutation.tableRef());
        if (Boolean.TRUE.equals(mutation.multiRow())) add(facts, "Rows", "many");
        return facts;
    }

    /** The single row an at-most-one-row arm holds, or null where it holds none. */
    private static String first(List<String> values) {
        return values.isEmpty() ? null : values.getFirst();
    }

    /** The three-part coordinate every field-grain read here is keyed on. */
    private static Condition coordinate(Condition graph, Condition type, Condition field) {
        return graph.and(type).and(field);
    }

    private static void add(List<DeclarationFact> facts, String label, String value) {
        if (value == null || value.isBlank()) return;
        facts.add(new DeclarationFact(label, value));
    }
}
