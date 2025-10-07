package no.sikt.graphitron.definitions.sql;

import no.sikt.graphitron.definitions.interfaces.JoinElement;
import no.sikt.graphitron.definitions.mapping.AliasWrapper;
import no.sikt.graphitron.generators.context.JoinListSequence;
import no.sikt.graphitron.javapoet.CodeBlock;

import java.util.List;
import java.util.Objects;

/**
 * An extension of {@link SQLJoin} with additional data on which table to join towards and which alias should be used.
 * This class then contains all the information necessary to construct a complete join statement.
 */
public class SQLJoinStatement extends SQLJoin {
    private final JoinElement joinTargetTable;
    private final String comparableJoinString;
    private final AliasWrapper joinAlias;
    private final CodeBlock joinString;

    /**
     * @param joinSequence The "left" side of a join statement. This is the table/sequence to join from.
     * @param joinTargetTable The "right" side of a join statement. This is the table to be joined with.
     * @param joinFields List of any conditions for the join operation.
     * @param nullable What kind of join operation is to be used.
     */
    public SQLJoinStatement(
            JoinListSequence joinSequence,
            JoinElement joinTargetTable,
            AliasWrapper joinAlias,
            List<SQLJoinField> joinFields,
            boolean nullable
    ) {
        super(joinSequence, joinFields, nullable);
        this.joinTargetTable = joinTargetTable;
        this.joinAlias = joinAlias;
        joinString = super.toJoinString(joinAlias.getAlias().getMappingName());
        comparableJoinString = joinString.toString();
    }

    /**
     * @return Source table to join from. The "left" side of a join statement.
     */
    public JoinListSequence getJoinSequence() {
        return super.getJoinSequence();
    }

    /**
     * @return The target table to be joined with. The "right" side of a join statement.
     */
    public JoinElement getJoinTargetTable() {
        return joinTargetTable;
    }

    /**
     * @return The alias to be used to uniquely distinguish this join operation.
     */
    public AliasWrapper getJoinAlias() {
        return joinAlias;
    }

    /**
     * @return A string that contains a complete join statement followed by all conditions set for this join.
     */
    public CodeBlock toJoinString() {
        return joinString;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SQLJoinStatement that)) return false;
        return Objects.equals(comparableJoinString, that.comparableJoinString);
    }

    @Override
    public int hashCode() {
        return Objects.hash(comparableJoinString);
    }
}
