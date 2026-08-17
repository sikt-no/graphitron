package no.sikt.graphitron.lsp.facts;

import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Condition;

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

/**
 * What a claimed declaration carries beyond its classifier, read from the relation that owns each
 * fact. {@link ClaimClassifiers} answers what graphitron takes a declaration to be; this answers the
 * detail behind that answer, one query per claim rather than one projection carrying every payload
 * any variant might have.
 *
 * <p>The split is the whole point. A classifier is a word, and the facts behind it belong to
 * different relations on different keys: a column match knows a table and a column and which naming
 * tier matched, a service knows a class and a method, a routine knows an ordered list of names. A
 * single record with all of those components would be mostly null at every coordinate, which is the
 * shape the sealed projection had and the reason a claim can grow a fact here without any other
 * claim's reader changing.
 *
 * <p>Two facts are read without asking the classifier first, because they hold across claims: the
 * join path an authored {@code @reference} resolves to, and whether the field is fetched by a
 * statement of its own. The second is the round-trip question, and it is a property of the field's
 * delivery rather than of what claims it: a {@code @splitQuery} column-matched field and a
 * {@code @service} child field are separately fetched for unrelated reasons.
 */
public final class ClaimFacts {

    private ClaimFacts() {}

    /** One resolved element of a field's {@code @reference} path: the key it joins on, and where it lands. */
    public record JoinStep(String constraintName, String toTable) {}

    /**
     * The facts behind one field-grain {@code classifier} at {@code Type.field}, in the order a
     * reader should show them. Empty for a classifier whose whole content is the claim itself
     * ({@code LOOKUP_KEY} is a marker), and empty for a claim whose relation holds no row, which is
     * the state a presence-arm claim is in: the directive is there and its arguments did not decode.
     */
    public static List<DeclarationFact> ofField(
        StoreHandle store, String typeName, String fieldName, String classifier
    ) {
        return switch (classifier) {
            case "TABLE_COLUMN" -> columnMatch(store, typeName, fieldName);
            case "SERVICE" -> service(store, typeName, fieldName);
            case "EXTERNAL_FIELD" -> externalField(store, typeName, fieldName);
            case "NODE_ID" -> nodeId(store, typeName, fieldName);
            case "ROUTINE" -> routines(store, typeName, fieldName);
            case "MUTATION" -> mutation(store, typeName, fieldName);
            default -> List.of();
        };
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
     * Where {@code Type.field}'s authored {@code @reference} path lands, element by element. Reads
     * the first application's elements, the repeatable directive's ordinal grain collapsed the way
     * every other reader of the path collapses it, and only elements whose destination is certain;
     * a path the chain could not walk contributes nothing rather than a partial route.
     */
    public static List<JoinStep> joinPath(StoreHandle store, String typeName, String fieldName) {
        var rows = store.dsl()
            .select(INTENT_FIELD_REFERENCE_STEP_TARGET.ORDINAL,
                INTENT_FIELD_REFERENCE_STEP_TARGET.CONSTRAINT_NAME,
                INTENT_FIELD_REFERENCE_STEP_TARGET.TO_TABLE)
            .from(INTENT_FIELD_REFERENCE_STEP_TARGET)
            .where(coordinate(INTENT_FIELD_REFERENCE_STEP_TARGET.GRAPH_NAME.eq(store.graphName()),
                INTENT_FIELD_REFERENCE_STEP_TARGET.TYPE_NAME.eq(typeName),
                INTENT_FIELD_REFERENCE_STEP_TARGET.FIELD_NAME.eq(fieldName)))
            .and(INTENT_FIELD_REFERENCE_STEP_TARGET.TARGETS.eq(1))
            .orderBy(INTENT_FIELD_REFERENCE_STEP_TARGET.ORDINAL,
                INTENT_FIELD_REFERENCE_STEP_TARGET.POSITION)
            .fetch();
        var steps = new ArrayList<JoinStep>(rows.size());
        Integer application = null;
        for (var row : rows) {
            if (application == null) application = row.value1();
            if (!application.equals(row.value1())) break;
            steps.add(new JoinStep(row.value2(), row.value3()));
        }
        return steps;
    }

    private static List<DeclarationFact> columnMatch(StoreHandle store, String typeName, String fieldName) {
        var row = store.dsl()
            .select(INTENT_COLUMN_MATCH_CLAIM.TABLE_NAME, INTENT_COLUMN_MATCH_CLAIM.COLUMN_NAME,
                INTENT_COLUMN_MATCH_CLAIM.MATCHED_NAME, INTENT_COLUMN_MATCH_CLAIM.MATCHED_BY)
            .from(INTENT_COLUMN_MATCH_CLAIM)
            .where(coordinate(INTENT_COLUMN_MATCH_CLAIM.GRAPH_NAME.eq(store.graphName()),
                INTENT_COLUMN_MATCH_CLAIM.TYPE_NAME.eq(typeName),
                INTENT_COLUMN_MATCH_CLAIM.FIELD_NAME.eq(fieldName)))
            .fetchOne();
        if (row == null) return List.of();
        var facts = new ArrayList<DeclarationFact>();
        add(facts, "Column", row.value2());
        add(facts, "Table", row.value1());
        // The name that matched is only worth a line where it is not the field's own: a @field
        // binding is the author's redirection, and seeing which spelling the match ran on is the
        // difference between "graphitron read my binding" and "graphitron ignored it".
        if (row.value3() != null && !row.value3().equals(fieldName)) {
            add(facts, "Matched name", row.value3());
        }
        add(facts, "Matched by", "JOOQ_NAME".equals(row.value4()) ? "generated name" : "SQL name");
        return facts;
    }

    private static List<DeclarationFact> service(StoreHandle store, String typeName, String fieldName) {
        var row = store.dsl()
            .select(GRAPHITRON_SERVICE.CLASS_NAME, GRAPHITRON_SERVICE.METHOD)
            .from(GRAPHITRON_SERVICE)
            .where(coordinate(GRAPHITRON_SERVICE.GRAPH_NAME.eq(store.graphName()),
                GRAPHITRON_SERVICE.TYPE_NAME.eq(typeName),
                GRAPHITRON_SERVICE.FIELD_NAME.eq(fieldName)))
            .fetchOne();
        var facts = new ArrayList<DeclarationFact>();
        if (row != null) add(facts, "Service", member(row.value1(), row.value2()));
        return facts;
    }

    private static List<DeclarationFact> externalField(StoreHandle store, String typeName, String fieldName) {
        var row = store.dsl()
            .select(GRAPHITRON_EXTERNAL_FIELD.CLASS_NAME, GRAPHITRON_EXTERNAL_FIELD.METHOD)
            .from(GRAPHITRON_EXTERNAL_FIELD)
            .where(coordinate(GRAPHITRON_EXTERNAL_FIELD.GRAPH_NAME.eq(store.graphName()),
                GRAPHITRON_EXTERNAL_FIELD.TYPE_NAME.eq(typeName),
                GRAPHITRON_EXTERNAL_FIELD.FIELD_NAME.eq(fieldName)))
            .fetchOne();
        var facts = new ArrayList<DeclarationFact>();
        if (row != null) add(facts, "External field", member(row.value1(), row.value2()));
        return facts;
    }

    /** {@code Class#method}, the class alone where the reference named no method, null where neither decoded. */
    private static String member(String className, String methodName) {
        if (className == null) return null;
        return methodName == null ? className : className + "#" + methodName;
    }

    private static List<DeclarationFact> nodeId(StoreHandle store, String typeName, String fieldName) {
        var ref = store.dsl()
            .select(GRAPHITRON_FIELD_NODE_ID.NODE_TYPE_REF)
            .from(GRAPHITRON_FIELD_NODE_ID)
            .where(coordinate(GRAPHITRON_FIELD_NODE_ID.GRAPH_NAME.eq(store.graphName()),
                GRAPHITRON_FIELD_NODE_ID.TYPE_NAME.eq(typeName),
                GRAPHITRON_FIELD_NODE_ID.FIELD_NAME.eq(fieldName)))
            .fetchOne(GRAPHITRON_FIELD_NODE_ID.NODE_TYPE_REF);
        var facts = new ArrayList<DeclarationFact>();
        add(facts, "Node type", ref);
        return facts;
    }

    private static List<DeclarationFact> routines(StoreHandle store, String typeName, String fieldName) {
        var refs = store.dsl()
            .select(GRAPHITRON_ROUTINE.ROUTINE_REF)
            .from(GRAPHITRON_ROUTINE)
            .where(coordinate(GRAPHITRON_ROUTINE.GRAPH_NAME.eq(store.graphName()),
                GRAPHITRON_ROUTINE.TYPE_NAME.eq(typeName),
                GRAPHITRON_ROUTINE.FIELD_NAME.eq(fieldName)))
            .orderBy(GRAPHITRON_ROUTINE.ORDINAL)
            .fetch(GRAPHITRON_ROUTINE.ROUTINE_REF);
        var facts = new ArrayList<DeclarationFact>(refs.size());
        // One fact per application, in application order: the directive is repeatable and each row
        // is a routine of its own, so a chained field reads as the chain it is.
        for (String ref : refs) add(facts, "Routine", ref);
        return facts;
    }

    private static List<DeclarationFact> mutation(StoreHandle store, String typeName, String fieldName) {
        var row = store.dsl()
            .select(GRAPHITRON_MUTATION.OPERATION, GRAPHITRON_MUTATION.TABLE_REF,
                GRAPHITRON_MUTATION.MULTI_ROW)
            .from(GRAPHITRON_MUTATION)
            .where(coordinate(GRAPHITRON_MUTATION.GRAPH_NAME.eq(store.graphName()),
                GRAPHITRON_MUTATION.TYPE_NAME.eq(typeName),
                GRAPHITRON_MUTATION.FIELD_NAME.eq(fieldName)))
            .fetchOne();
        if (row == null) return List.of();
        var facts = new ArrayList<DeclarationFact>();
        add(facts, "Operation", row.value1());
        add(facts, "Table", row.value2());
        if (Boolean.TRUE.equals(row.value3())) add(facts, "Rows", "many");
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
