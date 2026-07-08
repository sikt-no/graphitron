package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.TableRef;

/**
 * Where a correlated routine call's {@link ParamSource.SourceColumn} bindings read the previous
 * chain node's columns (R435) — the column-reference sibling of {@link ArgumentValueSource},
 * which answers the same question for {@link ParamSource.Arg} bindings.
 *
 * <ul>
 *   <li>{@link TypedAlias}: the previous node is materialised in the query as a typed jOOQ table
 *       alias (the parent alias at an inline chain's head, the preceding hop's alias mid-chain),
 *       so the binding reads {@code alias.COL} — the generated table class's typed column.</li>
 *   <li>{@link ParentInputField}: the previous node is the chain's implicit head and the query is
 *       the batched keyed re-query, where the head is not materialised — its bound columns ride
 *       the {@code parentInput} VALUES table (they ARE the DataLoader key). The binding reads
 *       {@code parentInput.field("<sqlName>", Tables.<OWNER>.<COL>.getDataType())}, the same
 *       sqlName + owner-{@code DataType} lookup the split correlation JOIN uses (R413), so the
 *       {@code Field}'s type metadata matches the VALUES cell binds and javac still selects the
 *       routine's {@code Field} overload.</li>
 *   <li>{@link None}: the routine node is the chain's head with no previous node (R449 D5 — the
 *       root {@code @routine} fetcher). A root chain's bindings are all {@link ParamSource.Arg}
 *       (pinned by {@code QueryField.QueryRoutineTableField}'s compact constructor, which requires
 *       every start binding be {@code ParamSource.Arg} — {@code RoutineDirectiveResolver} rejects
 *       {@code columnMapping} at root), so a {@link ParamSource.SourceColumn} binding never reaches
 *       {@code emitCall} with this arm; the emitter throws classifier-unreachable if one does.</li>
 * </ul>
 */
public sealed interface PreviousNodeRef {

    /** The previous node's in-scope typed table alias. */
    record TypedAlias(String alias) implements PreviousNodeRef {}

    /**
     * The {@code parentInput} VALUES table of a batched keyed re-query; {@code ownerTable} is
     * the catalog table that owns the bound columns (the chain's implicit head), read for the
     * typed {@code getDataType()} lookup.
     */
    record ParentInputField(String valuesLocal, TableRef ownerTable) implements PreviousNodeRef {}

    /**
     * No previous node — the routine node is the chain's head (R449 D5, the root {@code @routine}
     * fetcher). Carries no payload; a root chain binds every parameter from a GraphQL argument
     * ({@link ParamSource.Arg}), so no {@link ParamSource.SourceColumn} read against a previous
     * node is possible.
     */
    record None() implements PreviousNodeRef {}
}
