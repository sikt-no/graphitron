package no.sikt.graphitron.rewrite.model;

/**
 * Capability of the mutation leaves that carry a DML write payload: the coordinate's write
 * member is the carried {@link OperationMember.Write.Dml} arm, verbatim. The member view
 * ({@link OperationMembers#membersOf}) and the minted relation's payload extraction read this
 * one accessor instead of enumerating per-leaf arms, so the write payload is homed once and
 * the member row is the leaf's component by identity, not by copy.
 *
 * <p>The payload originates at leaf construction (the classifier resolves the write target,
 * the {@code UpdateRows} / {@code DeleteRows} walkers produce the carriers); the narrow
 * {@link OperationMember.Write.Dml} type makes the routine and condition-matched write arms
 * unrepresentable here.
 */
public sealed interface DmlWriteField
        permits MutationField.MutationDmlRecordField, MutationField.MutationBulkDmlRecordField {

    /** The DML write payload: verb identity and per-verb input surface, one home. */
    OperationMember.Write.Dml write();
}
