package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * An implementing or member type of an interface or union.
 *
 * <p>Two variants:
 * <ul>
 *   <li>{@link TableBound} — the participant has a resolved jOOQ table and an optional
 *       discriminator value. Generator code may emit SQL for this participant.</li>
 *   <li>{@link Unbound} — the participant is not table-backed (e.g. {@code @error} types,
 *       structural interfaces, value types). Generator code must skip SQL generation for
 *       unbound participants.</li>
 * </ul>
 */
public sealed interface ParticipantRef permits ParticipantRef.TableBound, ParticipantRef.Unbound {

    /** The simple GraphQL type name (e.g. {@code "Film"}). */
    String typeName();

    /**
     * A table-backed participant.
     *
     * <p>{@code table} is always non-null. {@code discriminatorValue} is the value from
     * {@code @discriminator(value:)} on this type, or {@code null} when the directive is absent.
     * Only populated for {@link GraphitronType.TableInterfaceType} participants; always {@code null}
     * for plain {@link GraphitronType.InterfaceType} (multi-table) participants.
     *
     * <p>{@code crossTableFields} lists the participant's fields whose value lives on a different
     * table than the participant's own ({@code @reference}-traversed). Used by the
     * {@code TableInterfaceType} fetcher to emit conditional LEFT JOINs gated by the participant's
     * discriminator value, so non-matching rows carry NULL for the cross-table columns. Empty for
     * participants without cross-table fields, and always empty for {@link GraphitronType.InterfaceType}
     * / {@link GraphitronType.UnionType} participants (multi-table polymorphism does not project
     * cross-table fields through this path).
     */
    record TableBound(String typeName, TableRef table, String discriminatorValue,
                      List<CrossTableField> crossTableFields)
            implements ParticipantRef {

        public TableBound {
            crossTableFields = List.copyOf(crossTableFields);
        }

        /** Convenience constructor for participants with no cross-table fields. */
        public TableBound(String typeName, TableRef table, String discriminatorValue) {
            this(typeName, table, discriminatorValue, List.of());
        }

        /**
         * A participant field whose value lives on a different table than the participant's own.
         * The {@code TableInterfaceType} fetcher emits a conditional LEFT JOIN gated by the
         * participant's discriminator value, projects {@link #column} aliased as {@link #aliasName},
         * and a per-field fetcher reads it back from the result {@code Record} by that alias.
         *
         * <p>{@code fkJoin} is the single-hop {@code @reference} from the interface table to
         * the cross table (exposed via {@link #targetTable()}). Its {@code sourceColumns} sit on
         * the interface table (FK holder) and its {@code targetColumns} sit on the referenced
         * side; the generator builds the JOIN ON condition by equating the two arity-1 column
         * lists.
         */
        public record CrossTableField(
            String fieldName,
            ColumnRef column,
            JoinStep.FkJoin fkJoin,
            String aliasName
        ) {
            /** The cross table joined to project this field — equivalent to {@code fkJoin().targetTable()}. */
            public TableRef targetTable() { return fkJoin.targetTable(); }

            /** Java variable name used in the generated interface fetcher to hold the aliased target table. */
            public String aliasVarName() { return aliasName + "_alias"; }
        }
    }

    /**
     * A non-table-backed participant.
     *
     * <p>Used for {@code @error} types, structural interfaces, and other types that carry no
     * jOOQ table. Generator switches must handle this variant and skip SQL-emitting paths.
     */
    record Unbound(String typeName) implements ParticipantRef {}
}
