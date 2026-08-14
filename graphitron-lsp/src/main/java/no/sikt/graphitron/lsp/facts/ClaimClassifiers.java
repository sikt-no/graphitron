package no.sikt.graphitron.lsp.facts;

import no.sikt.graphitron.model.read.StoreHandle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.model.Tables.INTENT_AUTHORED_TYPE_CLAIM;
import static no.sikt.graphitron.model.Tables.INTENT_RESOLVED_FIELD_CLAIM;

/**
 * What graphitron was told a declaration is: the classifier of every claim standing at a
 * coordinate, at the field grain and at the type grain. This is the one label the store answers
 * for a declaration, and it is deliberately the only one.
 *
 * <p>The incumbent surfaces rendered a single name per declaration, minted from a sealed
 * projection variant, and that name folded several independent facts into one word: a column
 * against a composite column is an arity, a column reference against a column is the presence of
 * a join path, a query table against a table target is whether the parent is a root. A relation
 * carrying such a name would have to enumerate the combinations, which is the monolith the fact
 * model exists to take apart. So the classifier stays at the vocabulary the claim views already
 * publish, and a surface wanting the rest of what the old name encoded joins the relation that
 * owns each fact.
 *
 * <p>A coordinate can carry more than one claim, which is exactly what makes it conflicted, so
 * both readers answer with a list rather than a value. The list <em>is</em> the conflict: two
 * classifiers at one coordinate say more than a single word naming the fact that there were two.
 * The order is the classifier's own alphabetical order, so one coordinate reads the same way on
 * every request.
 *
 * <p>Both readers take the type names in hand rather than the whole graph, because the caller is
 * rendering a region of one file and a whole-graph fetch per keystroke would pay for declarations
 * nobody is looking at. Both distinct on the classifier as well, so one classifier appearing twice
 * at a coordinate is this reader's answer to state rather than every claim arm's collapse rule to
 * uphold.
 */
public final class ClaimClassifiers {

    private ClaimClassifiers() {}

    /**
     * The classifiers claiming each field of {@code typeNames}, keyed by {@code Type.field}. A
     * coordinate with no claim is absent rather than empty: no directive named it and no
     * structural classifier matched it, which is a declaration graphitron has no opinion about.
     */
    public static Map<String, List<String>> ofFields(StoreHandle store, Collection<String> typeNames) {
        if (typeNames.isEmpty()) return Map.of();
        var rows = store.dsl()
            .selectDistinct(INTENT_RESOLVED_FIELD_CLAIM.TYPE_NAME,
                INTENT_RESOLVED_FIELD_CLAIM.FIELD_NAME, INTENT_RESOLVED_FIELD_CLAIM.CLASSIFIER)
            .from(INTENT_RESOLVED_FIELD_CLAIM)
            .where(INTENT_RESOLVED_FIELD_CLAIM.GRAPH_NAME.eq(store.graphName()))
            .and(INTENT_RESOLVED_FIELD_CLAIM.TYPE_NAME.in(typeNames))
            .orderBy(INTENT_RESOLVED_FIELD_CLAIM.TYPE_NAME, INTENT_RESOLVED_FIELD_CLAIM.FIELD_NAME,
                INTENT_RESOLVED_FIELD_CLAIM.CLASSIFIER)
            .fetch();
        var byCoordinate = new LinkedHashMap<String, List<String>>();
        for (var row : rows) {
            byCoordinate.computeIfAbsent(row.value1() + "." + row.value2(), ignored -> new ArrayList<>())
                .add(row.value3());
        }
        return byCoordinate;
    }

    /**
     * The classifiers claiming each of {@code typeNames}, keyed by type name. Absent for a type no
     * domain directive named, root operation types included: a root classifies before any type
     * directive is read, which is why the claim view masks the three root names out.
     *
     * <p>Reads the authored relation directly rather than a resolved sibling because the type grain
     * has no inferred population to mask: {@code @table} and {@code @error} are the whole
     * vocabulary, and a resolved view over one input would be a copy of it.
     */
    public static Map<String, List<String>> ofTypes(StoreHandle store, Collection<String> typeNames) {
        if (typeNames.isEmpty()) return Map.of();
        var rows = store.dsl()
            .selectDistinct(INTENT_AUTHORED_TYPE_CLAIM.TYPE_NAME, INTENT_AUTHORED_TYPE_CLAIM.CLASSIFIER)
            .from(INTENT_AUTHORED_TYPE_CLAIM)
            .where(INTENT_AUTHORED_TYPE_CLAIM.GRAPH_NAME.eq(store.graphName()))
            .and(INTENT_AUTHORED_TYPE_CLAIM.TYPE_NAME.in(typeNames))
            .orderBy(INTENT_AUTHORED_TYPE_CLAIM.TYPE_NAME, INTENT_AUTHORED_TYPE_CLAIM.CLASSIFIER)
            .fetch();
        var byTypeName = new LinkedHashMap<String, List<String>>();
        for (var row : rows) {
            byTypeName.computeIfAbsent(row.value1(), ignored -> new ArrayList<>()).add(row.value2());
        }
        return byTypeName;
    }
}
