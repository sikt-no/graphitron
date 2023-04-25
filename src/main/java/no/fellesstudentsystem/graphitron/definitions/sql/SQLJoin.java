package no.fellesstudentsystem.graphitron.definitions.sql;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Contains information on how to format join operations, including what type of join to use and which columns should be compared.
 * This class contains only information on the join source, and needs to be provided the target table in order to produce
 * a full join statement.
 * <p>
 * This class is intended as a temporary stage before the final target table and aliases are determined,
 * as these may depend on additional graph paths that must be evaluated first, and can not be directly inferred from a single field and its directives.
 */
public class SQLJoin {
    private final List<SQLJoinField> joinFields;
    private final SQLJoinType joinType;
    private final String joinSourceTable;

    /**
     * @param joinSourceTable The "left" side of a join statement. This is the table to be joined from.
     * @param joinFields List of any conditions for the join operation.
     * @param joinType What kind of join operation is to be used.
     */
    public SQLJoin(String joinSourceTable, List<SQLJoinField> joinFields, SQLJoinType joinType) {
        // Remember to sort so that the key join always comes first.
        this.joinFields = joinFields.stream().sorted().collect(Collectors.toList());
        this.joinSourceTable = joinSourceTable;
        this.joinType = joinType;
    }

    /**
     * @return List of column or key comparisons used to construct the join statement.
     */
    public List<SQLJoinField> getJoinFields() {
        return joinFields;
    }

    /**
     * @return What kind of join operation is to be used.
     */
    public SQLJoinType getJoinType() {
        return joinType;
    }

    /**
     * @return Source table to join from. The "left" side of a join statement.
     */
    public String getJoinSourceTable() {
        return joinSourceTable;
    }

    /**
     * @param aliasName The name of the table alias to be used for this particular join statement.
     * @return A string that contains a complete join statement followed by all conditions set for this join.
     */
    public String toJoinString(String aliasName, Map<String, Method> conditionOverrides) {
        var sb = new StringBuilder();
        sb
                .append(".")
                .append(joinType == SQLJoinType.LEFT ? "leftJoin" : "join")
                .append("(")
                .append(aliasName)
                .append(")\n");

        var fields = getJoinFields();
        for (int i = 0; i < fields.size(); i++) {
            var joinField = fields.get(i);
            sb
                    .append(".")
                    .append(i == 0 ? joinField.getMethodCallName() : "and")
                    .append("(")
                    .append(joinField.toJoinString(joinSourceTable, aliasName, conditionOverrides))
                    .append(")\n");
        }
        return sb.toString();
    }

    /**
     * @param joinTargetTable The table that be used as parameter for the join method call.
     * @param aliasName The name of the table alias to be used for this particular join statement.
     * @return An instance of {@link SQLJoinStatement}.
     */
    public SQLJoinStatement toStatement(String joinTargetTable, String aliasName) {
        return new SQLJoinStatement(joinSourceTable, joinTargetTable, aliasName, getJoinFields(), getJoinType());
    }

    /**
     * @param joinSourceTable Override the source table for this statement.
     * @param joinTargetTable The table that be used as parameter for the join method call.
     * @param aliasName The name of the table alias to be used for this particular join statement.
     * @return An instance of {@link SQLJoinStatement}.
     */
    public SQLJoinStatement toStatement(String joinSourceTable, String joinTargetTable, String aliasName) {
        return new SQLJoinStatement(joinSourceTable, joinTargetTable, aliasName, getJoinFields(), getJoinType());
    }
}
