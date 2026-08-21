package no.sikt.graphitron.model.test;

import no.sikt.graphitron.model.boot.StoreAnswer;

/**
 * Unwraps a {@link StoreAnswer} in a test that is not about the budget.
 *
 * <p>Deliberately here and not on {@link StoreAnswer} itself. In main sources an unwrap is exactly
 * what the sealed arm exists to prevent: a caller that could take the absence-shaped path without
 * stating that it had chosen to. A test asserting something else entirely has already chosen, by
 * minting an {@link no.sikt.graphitron.model.boot.ReadBudget.Unbounded} reader, and an arm it can
 * never reach would otherwise cost every such case a switch that says nothing.
 *
 * <p>It fails loudly rather than degrading. A case that meets the other arm has met a reader whose
 * budget it did not ask for, which is a defect in the harness and not a result to fold into the
 * assertion under test.
 */
public final class StoreAnswers {

    private StoreAnswers() {}

    /** The value a read produced, or an assertion failure naming the statement that overran. */
    public static <T> T answered(StoreAnswer<T> answer) {
        return switch (answer) {
            case StoreAnswer.Answered<T> value -> value.value();
            case StoreAnswer.OutOfBudget<T> expired -> throw new AssertionError(
                "this case expected an answer, but the read ran past its "
                    + expired.budget().describe() + " budget on: " + expired.sql());
        };
    }
}
