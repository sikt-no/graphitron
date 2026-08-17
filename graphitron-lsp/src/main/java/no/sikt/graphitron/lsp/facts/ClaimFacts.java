package no.sikt.graphitron.lsp.facts;

import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Condition;
import org.jooq.Record1;
import org.jooq.Records;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ERROR_HANDLER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_EXTERNAL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_NODE_ID;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MUTATION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ROUTINE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_SERVICE;
import static no.sikt.graphitron.model.Tables.INTENT_COLUMN_MATCH_CLAIM;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_REFERENCE_STEP_TARGET;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_SEPARATE_FETCH;
import static no.sikt.graphitron.model.Tables.INTENT_RESOLVED_FIELD_CLAIM;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectDistinct;

/**
 * What a claimed declaration carries beyond its classifier, read from the relation that owns each
 * fact. {@link ClaimClassifiers} answers what graphitron takes a declaration to be; this answers the
 * detail behind that answer, without any projection carrying every payload any variant might have.
 *
 * <p>The split between relations is the whole point. A classifier is a word, and the facts behind it
 * belong to different relations on different keys: a column match knows a table and a column and
 * which naming tier matched, a service knows a class and a method, a routine knows an ordered list of
 * names. A single record with all of those components would be mostly null at every coordinate, which
 * is the shape the sealed projection had and the reason a claim can grow a fact here without any
 * other claim's reader changing.
 *
 * <p>The split is not a reason to issue a statement per relation, and {@link #ofField} does not. One
 * field-declaration read is one statement: a multiset per arm, each subquery reading its own relation
 * with its own columns and its own ordering, assembled into one row. Nothing is joined, so no arm's
 * fan-out can multiply another's, and each arm keeps the small record its relation earns rather than
 * contributing nullable columns to a shared one. What the classifier then selects is a rendering
 * decision over rows already in hand.
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
            return claims.stream().map(ClaimBlock::classifier).toList();
        }
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
     * The facts behind one type-grain {@code classifier} at {@code typeName}. {@code TABLE} answers
     * with every candidate its binding resolves to, ambiguity being rows here as it is in the view;
     * {@code ERROR} answers with its handlers in declaration order.
     */
    public static List<DeclarationFact> ofType(StoreHandle store, String typeName, String classifier) {
        return switch (classifier) {
            case "TABLE" -> boundTables(store, typeName);
            case "ERROR" -> errorHandlers(store, typeName);
            default -> List.of();
        };
    }

    /**
     * Why each field of {@code typeNames} is fetched by a statement of its own, keyed by
     * {@code Type.field}, one rule per reason. A coordinate no rule reaches is absent.
     *
     * <p>Absence is not the claim that the field inlines. Two populations are still outside the
     * relation, a child reached through a connection wrapper and the polymorphic fan-in, so a
     * surface may report a rule it finds and must not report the absence of one. The relation's own
     * comment carries the same prohibition and names both, since it outlives this reader.
     *
     * <p>Bulk, like the classifier readers and for the same reason: the inlay arm annotates a whole
     * visible region and a query per field would pay per declaration on the screen.
     */
    public static Map<String, List<String>> separateFetchRules(
        StoreHandle store, Collection<String> typeNames
    ) {
        if (typeNames.isEmpty()) return Map.of();
        var rows = store.dsl()
            .select(INTENT_FIELD_SEPARATE_FETCH.TYPE_NAME, INTENT_FIELD_SEPARATE_FETCH.FIELD_NAME,
                INTENT_FIELD_SEPARATE_FETCH.RULE)
            .from(INTENT_FIELD_SEPARATE_FETCH)
            .where(INTENT_FIELD_SEPARATE_FETCH.GRAPH_NAME.eq(store.graphName()))
            .and(INTENT_FIELD_SEPARATE_FETCH.TYPE_NAME.in(typeNames))
            .orderBy(INTENT_FIELD_SEPARATE_FETCH.TYPE_NAME, INTENT_FIELD_SEPARATE_FETCH.FIELD_NAME,
                INTENT_FIELD_SEPARATE_FETCH.RULE)
            .fetch();
        var byCoordinate = new LinkedHashMap<String, List<String>>();
        for (var row : rows) {
            byCoordinate.computeIfAbsent(row.value1() + "." + row.value2(), ignored -> new ArrayList<>())
                .add(row.value3());
        }
        return byCoordinate;
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

    private static List<DeclarationFact> boundTables(StoreHandle store, String typeName) {
        var tables = BoundTables.of(store, typeName);
        var facts = new ArrayList<DeclarationFact>(tables.size());
        for (var table : tables) add(facts, "Table", table.tableName());
        return facts;
    }

    private static List<DeclarationFact> errorHandlers(StoreHandle store, String typeName) {
        var rows = store.dsl()
            .select(GRAPHITRON_ERROR_HANDLER.HANDLER, GRAPHITRON_ERROR_HANDLER.CLASS_NAME)
            .from(GRAPHITRON_ERROR_HANDLER)
            .where(GRAPHITRON_ERROR_HANDLER.GRAPH_NAME.eq(store.graphName()))
            .and(GRAPHITRON_ERROR_HANDLER.TYPE_NAME.eq(typeName))
            .orderBy(GRAPHITRON_ERROR_HANDLER.POSITION)
            .fetch();
        var facts = new ArrayList<DeclarationFact>(rows.size());
        for (var row : rows) {
            add(facts, "Handler", row.value2() == null ? row.value1() : row.value1() + " " + row.value2());
        }
        return facts;
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
