package no.fellesstudentsystem.graphitron.definitions.sql;

import com.squareup.javapoet.CodeBlock;
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
    public CodeBlock toJoinString(String joinSourceTable, String aliasName) {
        return condition.formatToString(List.of(CodeBlock.of(joinSourceTable), CodeBlock.of(aliasName)));
    }
}
