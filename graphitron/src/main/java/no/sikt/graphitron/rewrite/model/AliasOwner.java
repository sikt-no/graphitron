package no.sikt.graphitron.rewrite.model;

import java.util.Objects;

/**
 * Which name owns a {@link ResultKeyAliasedField}'s result-key alias namespace: the verdict that
 * decides whether the field's {@code __rk_} alias is qualified, and by what.
 *
 * <p>The problem it solves. A single-table discriminated interface's query folds every
 * participant's {@code $project} into one {@code LinkedHashSet<Field<?>>}, and an aliased jOOQ
 * field compares equal on its alias alone. Two participants that declare a same-named field over
 * different join paths therefore mint one alias, render different SQL, compare equal, and the
 * second term is dropped: the losing participant's rows read the winner's column. Qualifying the
 * alias per owner makes the two terms distinct, so both reach the statement.
 *
 * <p>The two arms and what each buys:
 * <ul>
 *   <li>{@link Shared}: the bare {@code __rk_<resultKey>} alias. Every field outside a
 *       single-table discriminated interface's own participant projection, where nothing merges
 *       with a sibling's select list, so qualification would churn aliases for no gain.</li>
 *   <li>{@link QualifiedBy}: {@code __rk_<owner>$<resultKey>}. A field a participant declares
 *       itself is owned by the participant type; a field the interface declares is owned by the
 *       interface, so every participant's arm mints the identical alias and the fold's set
 *       collapses them to one term exactly as today.</li>
 * </ul>
 *
 * <p>The value is minted once, at capture, off {@code (declaring type, field name)}, and copied
 * by every downstream carrier; the write side ({@code ProjectionUnitRenderer}) and the read side
 * ({@code no.sikt.graphitron.rewrite.generators.FetcherEmitter}) both spell the stamped value
 * rather than re-deriving the predicate, which is what keeps the two halves of the alias from
 * drifting.
 *
 * @see ResultKeyAliasedField
 */
public sealed interface AliasOwner {

    /** The unqualified namespace: the alias is the reserved prefix plus the runtime result key. */
    record Shared() implements AliasOwner {}

    /**
     * The qualified namespace: the alias interposes {@code owner} between the reserved prefix and
     * the runtime result key. {@code owner} is a GraphQL type name (a participant type, or the
     * discriminated interface that declares the field), so the composed alias is injective by
     * construction: GraphQL names cannot contain the {@code $} delimiter, and no bare
     * {@code __rk_<key>} can spell one.
     */
    record QualifiedBy(String owner) implements AliasOwner {
        public QualifiedBy {
            Objects.requireNonNull(owner, "owner");
            if (owner.isBlank()) {
                throw new IllegalArgumentException(
                    "AliasOwner.QualifiedBy.owner must be non-blank: the qualifier is a GraphQL "
                    + "type name, and a blank one would compose an alias no read can address");
            }
        }
    }

    /** The unqualified verdict, the answer for every non-participant coordinate. */
    static AliasOwner shared() {
        return new Shared();
    }

    /** The qualified verdict, owned by the named GraphQL type. */
    static AliasOwner qualifiedBy(String owner) {
        return new QualifiedBy(owner);
    }
}
