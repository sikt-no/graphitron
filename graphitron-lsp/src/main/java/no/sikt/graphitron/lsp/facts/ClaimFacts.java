package no.sikt.graphitron.lsp.facts;

import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.Records;
import org.jooq.TableField;

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
 * <p>The split is not a reason to issue a statement per relation, and neither {@link #fieldArms} nor
 * {@link #typeArms} does. One declaration read is one statement: a multiset per arm, each subquery
 * reading its own relation with its own columns and its own ordering, assembled into one row. Nothing
 * is joined, so no arm's fan-out can multiply another's, and each arm keeps the small record its
 * relation earns rather than contributing nullable columns to a shared one. What the classifier then
 * selects is a rendering decision over rows already in hand.
 *
 * <p>The arms are handed to the caller rather than fetched here, and one statement is still the
 * contract: the declaration hover asks these questions beside what the coordinate binds to, so the
 * caller is what holds both sets and issues them together.
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
     *
     * <p>{@code contested} is a list for the same reason {@code grounded} and {@code reached} are:
     * it is a set of class names, one row per class, and a surface that wants one string joins them
     * where it renders them rather than reading a join the store performed.
     */
    public record TypeBlock(List<ClaimBlock> claims, String backing, List<String> contested) {

        public TypeBlock {
            contested = List.copyOf(contested);
        }

        /** The store says nothing about the type at all, which is a declaration with no block. */
        public boolean isEmpty() {
            return claims.isEmpty() && backing == null && contested.isEmpty();
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
     * Everything about {@code Type.field}, as arms of one statement. A coordinate several classifiers
     * claim answers with a block each, in the classifiers' own alphabetical order, so one coordinate
     * reads the same way on every request and a conflict renders as the claims it is made of.
     *
     * <p>A claim contributes no facts where its whole content is the claim itself ({@code LOOKUP_KEY}
     * is a marker), and none where its relation holds no row, which is the state a presence-arm claim
     * is in: the directive is there and its arguments did not decode.
     *
     * <p>Arms rather than a finished query because the declaration hover asks what the coordinate binds
     * to in the same statement. The two question sets are independent and keyed on the same coordinate,
     * so issuing them separately would be a round trip bought with nothing.
     */
    public static final class FieldArms {

        private final String fieldName;
        private final Field<List<String>> classifiers;
        private final Field<List<ColumnMatch>> columnMatch;
        private final Field<List<Member>> service;
        private final Field<List<Member>> externalField;
        private final Field<List<String>> nodeTypes;
        private final Field<List<String>> routines;
        private final Field<List<Mutation>> mutation;
        private final Field<List<Step>> steps;
        private final Field<List<String>> fetchRules;

        private FieldArms(StoreHandle store, String typeName, String fieldName) {
            String graph = store.graphName();
            this.fieldName = fieldName;
            classifiers = multiset(selectDistinct(INTENT_RESOLVED_FIELD_CLAIM.CLASSIFIER)
                .from(INTENT_RESOLVED_FIELD_CLAIM)
                .where(coordinate(INTENT_RESOLVED_FIELD_CLAIM.GRAPH_NAME.eq(graph),
                    INTENT_RESOLVED_FIELD_CLAIM.TYPE_NAME.eq(typeName),
                    INTENT_RESOLVED_FIELD_CLAIM.FIELD_NAME.eq(fieldName)))
                .orderBy(INTENT_RESOLVED_FIELD_CLAIM.CLASSIFIER))
                .convertFrom(rows -> rows.map(Record1::value1))
                .as("claim_classifiers");
            columnMatch = multiset(select(INTENT_COLUMN_MATCH_CLAIM.TABLE_NAME,
                INTENT_COLUMN_MATCH_CLAIM.COLUMN_NAME, INTENT_COLUMN_MATCH_CLAIM.MATCHED_NAME,
                INTENT_COLUMN_MATCH_CLAIM.MATCHED_BY)
                .from(INTENT_COLUMN_MATCH_CLAIM)
                .where(coordinate(INTENT_COLUMN_MATCH_CLAIM.GRAPH_NAME.eq(graph),
                    INTENT_COLUMN_MATCH_CLAIM.TYPE_NAME.eq(typeName),
                    INTENT_COLUMN_MATCH_CLAIM.FIELD_NAME.eq(fieldName))))
                .convertFrom(rows -> rows.map(Records.mapping(ColumnMatch::new)))
                .as("claim_column_match");
            service = memberArm(GRAPHITRON_SERVICE.CLASS_NAME, GRAPHITRON_SERVICE.METHOD,
                coordinate(GRAPHITRON_SERVICE.GRAPH_NAME.eq(graph),
                    GRAPHITRON_SERVICE.TYPE_NAME.eq(typeName),
                    GRAPHITRON_SERVICE.FIELD_NAME.eq(fieldName))).as("claim_service");
            externalField = memberArm(GRAPHITRON_EXTERNAL_FIELD.CLASS_NAME,
                GRAPHITRON_EXTERNAL_FIELD.METHOD,
                coordinate(GRAPHITRON_EXTERNAL_FIELD.GRAPH_NAME.eq(graph),
                    GRAPHITRON_EXTERNAL_FIELD.TYPE_NAME.eq(typeName),
                    GRAPHITRON_EXTERNAL_FIELD.FIELD_NAME.eq(fieldName))).as("claim_external_field");
            nodeTypes = multiset(select(GRAPHITRON_FIELD_NODE_ID.NODE_TYPE_REF)
                .from(GRAPHITRON_FIELD_NODE_ID)
                .where(coordinate(GRAPHITRON_FIELD_NODE_ID.GRAPH_NAME.eq(graph),
                    GRAPHITRON_FIELD_NODE_ID.TYPE_NAME.eq(typeName),
                    GRAPHITRON_FIELD_NODE_ID.FIELD_NAME.eq(fieldName))))
                .convertFrom(rows -> rows.map(Record1::value1))
                .as("claim_node_types");
            // One row per application, in application order: the directive is repeatable and each
            // row is a routine of its own, so a chained field reads as the chain it is.
            routines = multiset(select(GRAPHITRON_ROUTINE.ROUTINE_REF)
                .from(GRAPHITRON_ROUTINE)
                .where(coordinate(GRAPHITRON_ROUTINE.GRAPH_NAME.eq(graph),
                    GRAPHITRON_ROUTINE.TYPE_NAME.eq(typeName),
                    GRAPHITRON_ROUTINE.FIELD_NAME.eq(fieldName)))
                .orderBy(GRAPHITRON_ROUTINE.ORDINAL))
                .convertFrom(rows -> rows.map(Record1::value1))
                .as("claim_routines");
            mutation = multiset(select(GRAPHITRON_MUTATION.OPERATION, GRAPHITRON_MUTATION.TABLE_REF,
                GRAPHITRON_MUTATION.MULTI_ROW)
                .from(GRAPHITRON_MUTATION)
                .where(coordinate(GRAPHITRON_MUTATION.GRAPH_NAME.eq(graph),
                    GRAPHITRON_MUTATION.TYPE_NAME.eq(typeName),
                    GRAPHITRON_MUTATION.FIELD_NAME.eq(fieldName))))
                .convertFrom(rows -> rows.map(Records.mapping(Mutation::new)))
                .as("claim_mutation");
            // Only elements whose destination is certain: a path the chain could not walk
            // contributes nothing rather than a partial route.
            steps = multiset(select(INTENT_FIELD_REFERENCE_STEP_TARGET.ORDINAL,
                INTENT_FIELD_REFERENCE_STEP_TARGET.CONSTRAINT_NAME,
                INTENT_FIELD_REFERENCE_STEP_TARGET.TO_TABLE)
                .from(INTENT_FIELD_REFERENCE_STEP_TARGET)
                .where(coordinate(INTENT_FIELD_REFERENCE_STEP_TARGET.GRAPH_NAME.eq(graph),
                    INTENT_FIELD_REFERENCE_STEP_TARGET.TYPE_NAME.eq(typeName),
                    INTENT_FIELD_REFERENCE_STEP_TARGET.FIELD_NAME.eq(fieldName)))
                .and(INTENT_FIELD_REFERENCE_STEP_TARGET.TARGETS.eq(1))
                .orderBy(INTENT_FIELD_REFERENCE_STEP_TARGET.ORDINAL,
                    INTENT_FIELD_REFERENCE_STEP_TARGET.POSITION))
                .convertFrom(rows -> rows.map(Records.mapping(Step::new)))
                .as("claim_steps");
            fetchRules = multiset(select(INTENT_FIELD_SEPARATE_FETCH.RULE)
                .from(INTENT_FIELD_SEPARATE_FETCH)
                .where(coordinate(INTENT_FIELD_SEPARATE_FETCH.GRAPH_NAME.eq(graph),
                    INTENT_FIELD_SEPARATE_FETCH.TYPE_NAME.eq(typeName),
                    INTENT_FIELD_SEPARATE_FETCH.FIELD_NAME.eq(fieldName)))
                .orderBy(INTENT_FIELD_SEPARATE_FETCH.RULE))
                .convertFrom(rows -> rows.map(Record1::value1))
                .as("claim_fetch_rules");
        }

        /** The arms, for a caller assembling them into a select of its own. */
        public List<Field<?>> fields() {
            return List.of(classifiers, columnMatch, service, externalField, nodeTypes, routines,
                mutation, steps, fetchRules);
        }

        /** The block, read off the record the caller's statement returned. */
        public FieldBlock read(Record row) {
            var rows = new FieldRows(row.get(classifiers), row.get(columnMatch), row.get(service),
                row.get(externalField), row.get(nodeTypes), row.get(routines), row.get(mutation),
                row.get(steps), row.get(fetchRules));
            var claims = new ArrayList<ClaimBlock>(rows.classifiers().size());
            for (String classifier : rows.classifiers()) {
                claims.add(new ClaimBlock(classifier, factsOf(classifier, rows, fieldName)));
            }
            return new FieldBlock(claims, joinPath(rows.steps()), rows.fetchRules());
        }
    }

    /** The nine arms of a field declaration's read, for a caller composing a statement of its own. */
    public static FieldArms fieldArms(StoreHandle store, String typeName, String fieldName) {
        return new FieldArms(store, typeName, fieldName);
    }

    /** A {@code Class#method} pair arm, which is the same shape over the two producer relations. */
    private static Field<List<Member>> memberArm(
        TableField<?, String> classColumn, TableField<?, String> methodColumn, Condition coordinate
    ) {
        return multiset(select(classColumn, methodColumn)
            .from(classColumn.getTable())
            .where(coordinate))
            .convertFrom(rows -> rows.map(Records.mapping(Member::new)));
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
    public static final class TypeArms {

        private final Field<List<String>> classifiers;
        private final Field<List<String>> tables;
        private final Field<List<Handler>> handlers;
        private final Field<List<String>> grounded;
        private final Field<List<String>> reached;
        private final Field<List<String>> contested;

        private TypeArms(StoreHandle store, String typeName) {
            String graph = store.graphName();
            classifiers = multiset(selectDistinct(INTENT_AUTHORED_TYPE_CLAIM.CLASSIFIER)
                .from(INTENT_AUTHORED_TYPE_CLAIM)
                .where(INTENT_AUTHORED_TYPE_CLAIM.GRAPH_NAME.eq(graph))
                .and(INTENT_AUTHORED_TYPE_CLAIM.TYPE_NAME.eq(typeName))
                .orderBy(INTENT_AUTHORED_TYPE_CLAIM.CLASSIFIER))
                .convertFrom(rows -> rows.map(Record1::value1))
                .as("type_claim_classifiers");
            tables = multiset(select(INTENT_BOUND_TABLE.TABLE_NAME)
                .from(INTENT_BOUND_TABLE)
                .where(INTENT_BOUND_TABLE.GRAPH_NAME.eq(graph))
                .and(INTENT_BOUND_TABLE.TYPE_NAME.eq(typeName))
                .orderBy(INTENT_BOUND_TABLE.TABLE_SCHEMA, INTENT_BOUND_TABLE.TABLE_NAME))
                .convertFrom(rows -> rows.map(Record1::value1))
                .as("type_claim_tables");
            handlers = multiset(select(GRAPHITRON_ERROR_HANDLER.HANDLER,
                GRAPHITRON_ERROR_HANDLER.CLASS_NAME)
                .from(GRAPHITRON_ERROR_HANDLER)
                .where(GRAPHITRON_ERROR_HANDLER.GRAPH_NAME.eq(graph))
                .and(GRAPHITRON_ERROR_HANDLER.TYPE_NAME.eq(typeName))
                .orderBy(GRAPHITRON_ERROR_HANDLER.POSITION))
                .convertFrom(rows -> rows.map(Records.mapping(Handler::new)))
                .as("type_claim_handlers");
            grounded = multiset(selectDistinct(INTENT_TYPE_BACKING_SEED.CLASS_NAME)
                .from(INTENT_TYPE_BACKING_SEED)
                .where(INTENT_TYPE_BACKING_SEED.GRAPH_NAME.eq(graph))
                .and(INTENT_TYPE_BACKING_SEED.TYPE_NAME.eq(typeName)))
                .convertFrom(rows -> rows.map(Record1::value1))
                .as("type_claim_grounded");
            reached = multiset(selectDistinct(INTENT_TYPE_BACKING.CLASS_NAME)
                .from(INTENT_TYPE_BACKING)
                .where(INTENT_TYPE_BACKING.GRAPH_NAME.eq(graph))
                .and(INTENT_TYPE_BACKING.TYPE_NAME.eq(typeName)))
                .convertFrom(rows -> rows.map(Record1::value1))
                .as("type_claim_reached");
            // The contested set is the backing rows the conflict relation names, joined rather
            // than read off a column: the classes are rows under the same key, and the relation
            // that says the type is contested carries the arity and nothing else.
            contested = multiset(selectDistinct(INTENT_TYPE_BACKING.CLASS_NAME)
                .from(INTENT_TYPE_BACKING_CONFLICT)
                .join(INTENT_TYPE_BACKING)
                .on(INTENT_TYPE_BACKING.GRAPH_NAME.eq(INTENT_TYPE_BACKING_CONFLICT.GRAPH_NAME),
                    INTENT_TYPE_BACKING.TYPE_NAME.eq(INTENT_TYPE_BACKING_CONFLICT.TYPE_NAME))
                .where(INTENT_TYPE_BACKING_CONFLICT.GRAPH_NAME.eq(graph))
                .and(INTENT_TYPE_BACKING_CONFLICT.TYPE_NAME.eq(typeName))
                .orderBy(INTENT_TYPE_BACKING.CLASS_NAME))
                .convertFrom(rows -> rows.map(Record1::value1))
                .as("type_claim_contested");
        }

        /** The arms, for a caller assembling them into a select of its own. */
        public List<Field<?>> fields() {
            return List.of(classifiers, tables, handlers, grounded, reached, contested);
        }

        /** The block, read off the record the caller's statement returned. */
        public TypeBlock read(Record row) {
            var rows = new TypeRows(row.get(classifiers), row.get(tables), row.get(handlers),
                row.get(grounded), row.get(reached), row.get(contested));
            var claims = new ArrayList<ClaimBlock>(rows.classifiers().size());
            for (String classifier : rows.classifiers()) {
                claims.add(new ClaimBlock(classifier, typeFactsOf(classifier, rows)));
            }
            var backing = TypeBackingClass.resolve(rows.grounded(), rows.reached()).orElse(null);
            return new TypeBlock(claims, backing,
                backing == null ? rows.contested() : List.of());
        }
    }

    /** The six arms of a type declaration's read, for a caller composing a statement of its own. */
    public static TypeArms typeArms(StoreHandle store, String typeName) {
        return new TypeArms(store, typeName);
    }

    /** What the field statement brought back, one component per relation read. */
    private record FieldRows(
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

    /** What the type statement brought back, one component per relation read. */
    private record TypeRows(
        List<String> classifiers,
        List<String> tables,
        List<Handler> handlers,
        List<String> grounded,
        List<String> reached,
        List<String> contested
    ) {}

    private record Handler(String handler, String className) {}

    /** The type grain's {@link #factsOf}: the same switch over rows already in hand. */
    private static List<DeclarationFact> typeFactsOf(String classifier, TypeRows arms) {
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
    private static List<DeclarationFact> factsOf(String classifier, FieldRows arms, String fieldName) {
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

    /** The three-part coordinate every field-grain read here is keyed on. */
    private static Condition coordinate(Condition graph, Condition type, Condition field) {
        return graph.and(type).and(field);
    }

    private static void add(List<DeclarationFact> facts, String label, String value) {
        if (value == null || value.isBlank()) return;
        facts.add(new DeclarationFact(label, value));
    }
}
