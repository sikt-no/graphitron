package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.codereferences.dummyreferences.DummyRecord;
import no.sikt.graphitron.codereferences.dummyreferences.TestRecordDto;
import org.jooq.Row1;
import org.jooq.Row2;

/**
 * Lifter-method stub used by {@link GraphitronSchemaBuilderTest} to exercise the
 * {@code @batchKeyLifter} classifier paths. The methods are intentionally
 * non-functional ({@link UnsupportedOperationException}); the resolver only
 * reflects on signatures (parameter type, return type, RowN type arguments) and
 * never invokes them at classification time.
 *
 * <p>Sibling of {@link TestServiceStub} / {@link TestConditionStub}.
 */
class TestLifterStub {

    /** Valid Row1 lifter on the {@link DummyRecord} backing class — Row1&lt;Integer&gt;. */
    public static Row1<Integer> dummyRow1Integer(DummyRecord parent) {
        throw new UnsupportedOperationException();
    }

    /** Valid Row2 composite-key lifter on {@link DummyRecord} — Row2&lt;Integer, Integer&gt;. */
    public static Row2<Integer, Integer> dummyRow2IntInt(DummyRecord parent) {
        throw new UnsupportedOperationException();
    }

    /** Valid Row1 lifter on the {@link TestRecordDto} Java-record backing class. */
    public static Row1<Integer> javaRecordRow1Integer(TestRecordDto parent) {
        throw new UnsupportedOperationException();
    }

    /**
     * Lifter whose return type is {@code Long} (not RowN). Used to exercise
     * Invariant #3 (return must be {@code org.jooq.Row1..Row22}).
     */
    public static Long wrongReturnLong(DummyRecord parent) {
        throw new UnsupportedOperationException();
    }

    /**
     * Lifter whose parameter is {@code Integer}, not assignable from
     * {@link DummyRecord}. Used to exercise Invariant #2 (parameter must be
     * assignable from the parent's backing class).
     */
    public static Row1<Integer> wrongParamType(Integer notARecord) {
        throw new UnsupportedOperationException();
    }

    /**
     * Lifter returning {@code Row1<String>} where the target column is typed
     * {@code Integer}. Used to exercise Invariant #4 (per-position column-class
     * match).
     */
    public static Row1<String> dummyRow1String(DummyRecord parent) {
        throw new UnsupportedOperationException();
    }
}
