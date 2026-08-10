package no.sikt.graphitron.rewrite.test.internal;

import no.sikt.graphitron.generated.fetchers.QueryFetchers;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

/**
 * Direct unit coverage for the generator-emitted {@code scatterLookupByIdx} helper, the root
 * lookup's sibling of the helper {@link ScatterSingleByIdxTest} covers. It is emitted once per
 * class holding a list-returning lookup, so {@link QueryFetchers}'s private copy covers the
 * template.
 *
 * <p>What earns a test here rather than at the execution tier is the tie-break. The slot-per-key
 * and null-for-a-miss halves of the contract are pinned end-to-end against PostgreSQL, but "two
 * rows on one key keeps the first" is a deliberate divergence from the {@code scatterSingleByIdx}
 * throw, chosen because a lookup joins on author-declared columns the schema never required to be
 * unique. SQL will not reliably produce that collision on the example schema's uniquely-keyed
 * fixtures, so nothing else observes the decision.
 *
 * <p>The emitted helper is {@code private static}; invoked reflectively. Result construction uses
 * a standalone {@link DSLContext} (no JDBC connection) so the test is fully in-memory.
 */
@UnitTier
class ScatterLookupByIdxTest {

    private static Method SCATTER_LOOKUP;
    private static DSLContext DSL_CTX;
    private static Field<Integer> IDX_FIELD;
    private static Field<String> VAL_FIELD;

    @BeforeAll
    static void locateHelperAndPrepare() throws NoSuchMethodException {
        SCATTER_LOOKUP = QueryFetchers.class.getDeclaredMethod(
            "scatterLookupByIdx", Result.class, int.class);
        SCATTER_LOOKUP.setAccessible(true);
        DSL_CTX = DSL.using(SQLDialect.POSTGRES);
        IDX_FIELD = DSL.field("__idx__", Integer.class);
        VAL_FIELD = DSL.field("val", String.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Record> invoke(Result<Record> flat, int keyCount) {
        try {
            return (List<Record>) SCATTER_LOOKUP.invoke(null, flat, keyCount);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw new AssertionError(e.getCause());
        }
    }

    private static Record newRecord(int idx, String value) {
        Record r = DSL_CTX.newRecord(IDX_FIELD, VAL_FIELD);
        r.set(IDX_FIELD, idx);
        r.set(VAL_FIELD, value);
        return r;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Result<Record> emptyResult() {
        // newResult(Field, Field) returns Result<Record2<T1,T2>>; the scatter helper takes
        // Result<Record>, which erases to the same runtime type, so the cast is safe.
        return (Result) DSL_CTX.newResult(IDX_FIELD, VAL_FIELD);
    }

    @Test
    void everyKeyMissed_allSlotsNull() {
        // Three keys, no matching rows. The caller still gets three slots: an empty list would
        // mean "no keys asked", which is a different answer.
        assertThat(invoke(emptyResult(), 3)).hasSize(3).containsOnlyNulls();
    }

    @Test
    void missInTheMiddle_holdsItsPosition() {
        Result<Record> flat = emptyResult();
        flat.add(newRecord(0, "a"));
        flat.add(newRecord(2, "c"));
        List<Record> out = invoke(flat, 3);
        assertThat(out).hasSize(3);
        assertThat(out.get(0).get(VAL_FIELD)).isEqualTo("a");
        assertThat(out.get(1)).as("the missed key keeps its slot").isNull();
        assertThat(out.get(2).get(VAL_FIELD)).isEqualTo("c");
    }

    @Test
    void rowsArrivingOutOfIdxOrder_landAtTheirOwnKeysPosition() {
        // The scatter is what carries input order, which is why the emitted arm drops ORDER BY.
        // Fed rows in reverse, the output still reads in key order.
        Result<Record> flat = emptyResult();
        flat.add(newRecord(2, "c"));
        flat.add(newRecord(0, "a"));
        flat.add(newRecord(1, "b"));
        List<Record> out = invoke(flat, 3);
        assertThat(out).extracting(r -> r.get(VAL_FIELD)).containsExactly("a", "b", "c");
    }

    @Test
    void twoRowsOnOneKey_keepsTheFirst() {
        // The documented answer for a key bound to a non-unique column: one of the matching rows
        // is returned. Deliberately not the scatterSingleByIdx throw: a second row per key is a
        // schema mistake here, not a broken generator invariant, so the request still answers.
        Result<Record> flat = emptyResult();
        flat.add(newRecord(0, "first"));
        flat.add(newRecord(0, "second"));
        List<Record> out = invoke(flat, 1);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).get(VAL_FIELD)).isEqualTo("first");
    }

    @Test
    void repeatedKeysScatterToSeparateSlots() {
        // A repeated key is not deduplicated: it arrives as separate VALUES rows with separate
        // idx values, so each occurrence gets answered in its own slot.
        Result<Record> flat = emptyResult();
        flat.add(newRecord(0, "same"));
        flat.add(newRecord(1, "same"));
        flat.add(newRecord(2, "same"));
        assertThat(invoke(flat, 3)).hasSize(3)
            .extracting(r -> r.get(VAL_FIELD)).containsExactly("same", "same", "same");
    }
}
