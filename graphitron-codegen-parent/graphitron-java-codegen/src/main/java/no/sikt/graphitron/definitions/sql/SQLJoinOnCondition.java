package no.sikt.graphitron.definitions.sql;

import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.generators.context.JoinListSequence;

import java.util.List;

/**
 * SQL join condition which joins using an external condition method.
 */
public class SQLJoinOnCondition extends SQLJoinField {
    private final static String JOOQ_METHOD_CALL = "on";
    private final static int orderingPriority = 1;
    private final SQLCondition condition;

    public SQLJoinOnCondition(SQLCondition condition) {
        this.condition = condition;
    }

    @Override
    public int getOrderingPriority() {
        return orderingPriority;
    }

    @Override
    public String getMethodCallName() {
        return JOOQ_METHOD_CALL;
    }

    @Override
    public CodeBlock toJoinString(JoinListSequence joinSequence, String aliasName) {
        return condition.formatToString(List.of(joinSequence.render(), CodeBlock.of(aliasName)));
    }
}
