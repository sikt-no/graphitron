package no.sikt.graphitron.rewrite.model;

/**
 * A user-supplied join-condition method, typed to the fixed two-argument calling convention
 * the join-path emitters use: {@code method(sourceAlias, targetAlias)}, emitted by
 * {@code JoinPathEmitter.emitTwoArgMethodCall}. The first argument is the alias of the table
 * the hop is entered from, the second the alias of the newly-joined table.
 *
 * <p>Exists because a bare {@link MethodRef} does not carry the calling convention: the same
 * interface is implemented by {@link ConditionFilter}, whose {@code WhereFilter} convention is
 * different. Wrapping the join-condition population in its own type makes handing a filter-shaped
 * method to a join-condition emit site a compile error instead of a generated-code bug.
 *
 * <p>Carried in two places with two distinct emit shapes:
 * <ul>
 *   <li>{@link On.Predicate#condition()} — the JOIN's ON clause:
 *       {@code .join(target).on(method(src, tgt))}.</li>
 *   <li>{@link JoinStep.Hop#filter()} — a filter appended to the enclosing SELECT's
 *       WHERE, alongside an FK-keyed join: {@code .onKey(FK) ... .where(method(src, tgt))}.</li>
 * </ul>
 * Same calling convention, different clause; the carrying component names the clause.
 *
 * <p>Produced by {@code BuildContext.parsePathElement} from a {@code condition:} sub-argument on
 * a {@code @reference} path element, wrapping the {@link MethodRef} that
 * {@code BuildContext.resolveConditionRef} reflected.
 */
public record JoinConditionRef(MethodRef method) {

    public JoinConditionRef {
        if (method == null) {
            throw new NullPointerException(
                "JoinConditionRef.method must not be null; an absent join condition is an absent "
                + "JoinConditionRef (null component on the carrier), never a null method inside "
                + "one.");
        }
    }
}
