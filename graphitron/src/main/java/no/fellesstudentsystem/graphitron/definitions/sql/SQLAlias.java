package no.fellesstudentsystem.graphitron.definitions.sql;

import java.util.Objects;

/**
 * For multiple joins on the same table, aliases are required to distinguish them.
 */
public class SQLAlias {
    private final String joinTargetMethod, name, joinSourceTable, shortName, rendered;

    /**
     * @param joinTargetMethod The table to be joined with, using the provided method.
     * @param joinSourceTable  The table to be joined from.
     */
    public SQLAlias(String name, String shortName, String joinTargetMethod, String joinSourceTable) {
        this.joinSourceTable = joinSourceTable;
        this.joinTargetMethod = joinTargetMethod;
        this.name = name.replaceAll("[.]", "_").replaceAll("[()]", "");
        this.shortName = shortName;
        rendered = "var " + this.name + " = " + joinSourceTable + "." + joinTargetMethod + "().as(\"" + shortName + "\")";
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

    /**
     * @return Shortened alias name that will not exceed the max character limit for aliases.
     */
    public String getShortName() {
        return shortName;
    }

    @Override
    public String toString() {
        return rendered;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SQLAlias)) return false;
        SQLAlias sqlAlias = (SQLAlias) o;
        return Objects.equals(joinTargetMethod, sqlAlias.joinTargetMethod) && Objects.equals(name, sqlAlias.name) && Objects.equals(joinSourceTable, sqlAlias.joinSourceTable);
    }

    @Override
    public int hashCode() {
        return Objects.hash(joinTargetMethod, name, joinSourceTable);
    }
}
