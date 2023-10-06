package no.fellesstudentsystem.graphitron.definitions.sql;

/**
 * For multiple joins on the same table, aliases are required to distinguish them.
 */
public class SQLAlias {
    private final String joinTargetMethod, name, joinSourceTable, shortAliasName;

    /**
     * @param joinSourceTable  The table to be joined from.
     * @param joinTargetMethod The table to be joined with, using the provided method.
     */
    public SQLAlias(String name, String joinSourceTable, String joinTargetMethod, String shortAliasName) {
        this.joinSourceTable = joinSourceTable;
        this.joinTargetMethod = joinTargetMethod;
        this.name = name.replaceAll("[.]", "_").replaceAll("[()]", "");
        this.shortAliasName = shortAliasName;
    }

    /**
     * @return Source table to join from. The "left" side of a join statement.
     */
    public String getJoinSourceTable() {
        return joinSourceTable;
    }

    /**
     * @return The target table to be joined with. The "right" side of a join statement.
     */
    public String getJoinTargetMethod() {
        return joinTargetMethod;
    }

    /**
     * @return The alias to be used to uniquely distinguish this join operation.
     */
    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return "var " + name + " = " + joinSourceTable + "." + joinTargetMethod + "().as(\"" + shortAliasName + "\")";
    }
}
