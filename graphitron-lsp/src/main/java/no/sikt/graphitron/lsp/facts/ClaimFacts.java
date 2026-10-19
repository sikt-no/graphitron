package no.sikt.graphitron.lsp.facts;

import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.Records;

import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ERROR_HANDLER_ENTRY;
import static no.sikt.graphitron.model.Tables.INTENT_AUTHORED_TYPE_CLAIM;
import static no.sikt.graphitron.model.Tables.INTENT_BOUND_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_BACKING;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_BACKING_CONFLICT;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectDistinct;

/**
 * What a claimed type declaration carries beyond its classifier, read from the relation that owns
 * each fact: a table type its tables, an error type its handlers, and a type no claim reaches the
 * class standing for it. A field's hover reads no claim; {@link FieldInformation} states its facts.
 *
 * <p>One declaration read is one statement: a multiset per arm, each subquery reading its own
 * relation with its own columns and its own ordering, assembled into one row. Nothing is joined, so
 * no arm's fan-out can multiply another's. The arms are handed to the caller rather than fetched
 * here, because the declaration hover asks these questions beside what the coordinate binds to and
 * issues both together.
 */
public final class ClaimFacts {

    private ClaimFacts() {}

    /** One claim standing at a coordinate: its classifier, and the facts behind that classifier. */
    public record ClaimBlock(String classifier, List<DeclarationFact> facts) {}

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
            handlers = multiset(select(GRAPHITRON_ERROR_HANDLER_ENTRY.HANDLER,
                GRAPHITRON_ERROR_HANDLER_ENTRY.CLASS_NAME)
                .from(GRAPHITRON_ERROR_HANDLER_ENTRY)
                .where(GRAPHITRON_ERROR_HANDLER_ENTRY.GRAPH_NAME.eq(graph))
                .and(GRAPHITRON_ERROR_HANDLER_ENTRY.TYPE_NAME.eq(typeName))
                .orderBy(GRAPHITRON_ERROR_HANDLER_ENTRY.POSITION))
                .convertFrom(rows -> rows.map(Records.mapping(Handler::new)))
                .as("type_claim_handlers");
            grounded = multiset(selectDistinct(GRAPHITRON_TYPE_BACKING.CLASS_NAME)
                .from(GRAPHITRON_TYPE_BACKING)
                .where(GRAPHITRON_TYPE_BACKING.GRAPH_NAME.eq(graph))
                .and(GRAPHITRON_TYPE_BACKING.TYPE_NAME.eq(typeName))
                .and(TypeBackingClass.GROUNDED))
                .convertFrom(rows -> rows.map(Record1::value1))
                .as("type_claim_grounded");
            reached = multiset(selectDistinct(GRAPHITRON_TYPE_BACKING.CLASS_NAME)
                .from(GRAPHITRON_TYPE_BACKING)
                .where(GRAPHITRON_TYPE_BACKING.GRAPH_NAME.eq(graph))
                .and(GRAPHITRON_TYPE_BACKING.TYPE_NAME.eq(typeName)))
                .convertFrom(rows -> rows.map(Record1::value1))
                .as("type_claim_reached");
            // The contested set is the backing rows the conflict relation names, joined rather
            // than read off a column: the classes are rows under the same key, and the relation
            // that says the type is contested carries the arity and nothing else.
            contested = multiset(selectDistinct(GRAPHITRON_TYPE_BACKING.CLASS_NAME)
                .from(INTENT_TYPE_BACKING_CONFLICT)
                .join(GRAPHITRON_TYPE_BACKING)
                .on(GRAPHITRON_TYPE_BACKING.GRAPH_NAME.eq(INTENT_TYPE_BACKING_CONFLICT.GRAPH_NAME),
                    GRAPHITRON_TYPE_BACKING.TYPE_NAME.eq(INTENT_TYPE_BACKING_CONFLICT.TYPE_NAME))
                .where(INTENT_TYPE_BACKING_CONFLICT.GRAPH_NAME.eq(graph))
                .and(INTENT_TYPE_BACKING_CONFLICT.TYPE_NAME.eq(typeName))
                .orderBy(GRAPHITRON_TYPE_BACKING.CLASS_NAME))
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

    private static List<DeclarationFact> labelled(String label, List<String> values) {
        var facts = new ArrayList<DeclarationFact>(values.size());
        for (String value : values) add(facts, label, value);
        return facts;
    }

    private static void add(List<DeclarationFact> facts, String label, String value) {
        if (value == null || value.isBlank()) return;
        facts.add(new DeclarationFact(label, value));
    }
}
