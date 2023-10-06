package no.fellesstudentsystem.graphitron.definitions.sql;

import com.squareup.javapoet.CodeBlock;
import java.util.List;

/**
 * An extension of {@link SQLJoin} with additional data on which table to join towards and which alias should be used.
 * This class then contains all the information necessary to construct a complete join statement.
 */
public class SQLJoinStatement extends SQLJoin {
    private final String joinTargetTable, joinAlias, joinShortAliasName;

    /**
     * @param joinTargetTable The "right" side of a join statement. This is the table to be joined with.
     * @param joinFields List of any conditions for the join operation.
     * @param joinType What kind of join operation is to be used.
     */
    public SQLJoinStatement(String joinSourceTable, String joinTargetTable, String joinAlias, String joinShortAliasName, List<SQLJoinField> joinFields, SQLJoinType joinType) {
        super(joinSourceTable, joinFields, joinType);
        this.joinTargetTable = joinTargetTable;
        this.joinAlias = joinAlias;
        this.joinShortAliasName = joinShortAliasName;
    }

    /**
     * @return Source table to join from. The "left" side of a join statement.
     */
    public String getJoinSourceTable() {
        return super.getJoinSourceTable();
    }

    /**
     * @return The target table to be joined with. The "right" side of a join statement.
     */
    public String getJoinTargetTable() {
        return joinTargetTable;
    }

    /**
     * @return The alias to be used to uniquely distinguish this join operation.
     */
    public String getJoinAlias() {
        return joinAlias;
    }

    public String getJoinShortAliasName() {
        return joinShortAliasName;
    }

    /**
     * @return A string that contains a complete join statement followed by all conditions set for this join.
     */
    public CodeBlock toJoinString() {
        return super.toJoinString(joinAlias);
    }
}
