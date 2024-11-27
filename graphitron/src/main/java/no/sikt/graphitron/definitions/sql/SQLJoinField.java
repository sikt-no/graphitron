package no.sikt.graphitron.definitions.sql;

import com.squareup.javapoet.CodeBlock;
import no.sikt.graphitron.generators.context.JoinListSequence;
import org.jetbrains.annotations.NotNull;

/**
 * Abstraction that allows to apply different join conditions more smoothly.
 * Join conditions are ordered such that the conditions appear in the order required by jOOQ.
 * The limitation is that there can be only one "onKey" condition, and it must be called first, before any "on" conditions.
 */
public abstract class SQLJoinField implements Comparable<SQLJoinField> {
    private final static int orderingPriority = 1;

    /**
     * @return Ordering priority for this type of join condition.
     */
    public int getOrderingPriority() {
        return orderingPriority;
    }

    @Override
    public int compareTo(@NotNull SQLJoinField o) {
        return Integer.compare(getOrderingPriority(), o.getOrderingPriority());
    }

    /**
     * @return The name of the jOOQ method that corresponds to this condition.
     */
    abstract public String getMethodCallName();

    /**
     * @param joinSequence The source from which the join is originating. In other words, the left side of the join.
     * @param aliasName The name of the table alias to be used for this particular join statement.
     * @return A CodeBlock that contains a complete condition statement.
     */
    abstract public CodeBlock toJoinString(JoinListSequence joinSequence, String aliasName);
}
