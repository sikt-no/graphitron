package no.fellesstudentsystem.graphitron.definitions.sql;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

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
    public String toJoinString(String joinSourceTable, String aliasName) {
        return condition.formatToString(List.of(joinSourceTable, aliasName));
    }

    @Override
    public String toJoinString(String joinSourceTable, String aliasName, Map<String, Method> conditionOverrides) {
        return condition.formatToString(List.of(joinSourceTable, aliasName), conditionOverrides);
    }
}
