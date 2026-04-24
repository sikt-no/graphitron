package no.sikt.graphitron.rewrite.test;

import no.sikt.graphitron.generated.fetchers.CustomerFetchers;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Direct unit coverage for the generator-emitted {@code scatterSingleByIdx} helper. One fetcher
 * class's copy is sufficient — the helper is emitted identically on every class that has a
 * single-cardinality {@code @splitQuery} field, so exercising {@link CustomerFetchers}'s private
 * copy covers the template.
 *
 * <p>The emitted helper is {@code private static}; invoked reflectively. Result construction
 * uses a standalone {@link DSLContext} (no JDBC connection) so the test is fully in-memory.
 *
 * <p>Cases mirror plan-single-cardinality-split-query.md §5:
 * <ul>
 *   <li>empty result + keyCount=3 → three-null list</li>
 *   <li>full match in order → records at matching slots</li>
 *   <li>gap in matches → null preserved at the gap index</li>
 *   <li>duplicate-idx input → defensive {@link IllegalStateException}</li>
 * </ul>
 */
class ScatterSingleByIdxTest {

    private static Method SCATTER_SINGLE;
    private static DSLContext DSL_CTX;
    private static Field<Integer> IDX_FIELD;
    private static Field<String> VAL_FIELD;

    @BeforeAll
    static void locateHelperAndPrepare() throws NoSuchMethodException {
        SCATTER_SINGLE = CustomerFetchers.class.getDeclaredMethod(
            "scatterSingleByIdx", Result.class, int.class);
        SCATTER_SINGLE.setAccessible(true);
        DSL_CTX = DSL.using(SQLDialect.POSTGRES);
        IDX_FIELD = DSL.field("__idx__", Integer.class);
        VAL_FIELD = DSL.field("val", String.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Record> invoke(Result<Record> flat, int keyCount) {
        try {
            return (List<Record>) SCATTER_SINGLE.invoke(null, flat, keyCount);
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
        // Result<Record>, which erases to the same runtime type — the cast is safe.
        return (Result) DSL_CTX.newResult(IDX_FIELD, VAL_FIELD);
    }

    @Test
    void emptyResult_keyCount3_threeNulls() {
        List<Record> out = invoke(emptyResult(), 3);
        assertThat(out).hasSize(3).containsOnlyNulls();
    }

    @Test
    void fullMatch_recordsAtMatchingSlots() {
        Result<Record> flat = emptyResult();
        flat.add(newRecord(0, "a"));
        flat.add(newRecord(1, "b"));
        flat.add(newRecord(2, "c"));
        List<Record> out = invoke(flat, 3);
        assertThat(out).hasSize(3);
        assertThat(out.get(0).get(VAL_FIELD)).isEqualTo("a");
        assertThat(out.get(1).get(VAL_FIELD)).isEqualTo("b");
        assertThat(out.get(2).get(VAL_FIELD)).isEqualTo("c");
    }

    @Test
    void gapInMatches_nullPreservedAtGap() {
        // Keys 0, 1, 2 but only idx 0 and 2 match (idx 1 has no terminal row — the underlying
        // @splitQuery query joined to zero rows for key[1]). The slot at index 1 stays null.
        Result<Record> flat = emptyResult();
        flat.add(newRecord(0, "a"));
        flat.add(newRecord(2, "c"));
        List<Record> out = invoke(flat, 3);
        assertThat(out).hasSize(3);
        assertThat(out.get(0).get(VAL_FIELD)).isEqualTo("a");
        assertThat(out.get(1)).isNull();
        assertThat(out.get(2).get(VAL_FIELD)).isEqualTo("c");
    }

    @Test
    void duplicateIdx_throwsIllegalStateException() {
        // Two terminal rows for the same idx means the single-cardinality contract was violated
        // (the terminal.pk = parentInput.fk_value JOIN should yield at most one row per key).
        // The defensive check in the emitted scatter fires with a descriptive message.
        Result<Record> flat = emptyResult();
        flat.add(newRecord(0, "a"));
        flat.add(newRecord(0, "b"));
        assertThatThrownBy(() -> invoke(flat, 1))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("two rows at idx 0")
            .hasMessageContaining("single-cardinality @splitQuery contract requires ≤1 terminal row per key");
    }
}
