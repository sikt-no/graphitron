package no.sikt.graphitron.lsp.dispatch;

import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.Trigger;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Map.entry;
import static no.sikt.graphitron.lsp.dispatch.LspSurface.CODE_ACTION;
import static no.sikt.graphitron.lsp.dispatch.LspSurface.COMPLETION;
import static no.sikt.graphitron.lsp.dispatch.LspSurface.DEFINITION;
import static no.sikt.graphitron.lsp.dispatch.LspSurface.DIAGNOSTIC;
import static no.sikt.graphitron.lsp.dispatch.LspSurface.HOVER;
import static no.sikt.graphitron.lsp.dispatch.LspSurface.INLAY_HINT;
import static no.sikt.graphitron.lsp.dispatch.LspSurface.REFERENCES;

/**
 * What every surface does with every trigger, as data rather than as prose. One row per
 * {@link Trigger} leaf, naming what each surface does with it: answers, has a known gap, or
 * declines. The three statuses partition {@code Trigger} leaves × {@link LspSurface}
 * exhaustively, which is what {@code TriggerDispatchMatrixTest} asserts.
 *
 * <p>Both axes are guarded, and for the same reason on each. A row must name every surface
 * ({@link Reach} enforces it), so a surface added to {@link LspSurface} cannot slip into a
 * decline on all twenty-one triggers without anybody deciding that; and the key set must equal
 * the sealed trigger-leaf set, so a trigger cannot be added without every surface answering for
 * it. What used to default silently was the first of those: declines were the complement of
 * what a row bothered to name.
 *
 * <p>The guard is that {@link #MATRIX}'s key set must equal the sealed leaf set derived from
 * {@link Class#getPermittedSubclasses()}. A new trigger therefore fails the build until every
 * surface has said what it does with it, and a deleted trigger fails until its row goes. That
 * is the whole reason the vocabulary is sealed: were the leaf set hand-listed here, this table
 * would be an inventory somebody has to remember to edit, which is the prose problem relocated
 * into a source file rather than solved.
 *
 * <p>{@link Status#UNIMPLEMENTED} is a gap to close, not a behaviour to preserve. Several arms
 * return empty today because the dispatch era never filled them in, and reproducing that
 * emptiness would be porting the shape being replaced. {@link Status#NO_ANSWER} is the
 * deliberate decline: the surface is not meaningful for that trigger, most often because the
 * trigger is a whole-document sweep and the surface is cursor-keyed, or the reverse.
 */
public final class TriggerDispatch {

    private TriggerDispatch() {}

    /** What a surface does with a trigger. */
    public enum Status {

        /** The surface answers this trigger. */
        ANSWERED,

        /**
         * The surface deliberately declines: the pairing is not meaningful. A cursor-keyed
         * surface asked about a document sweep, or a sweep asked about a cursor token.
         */
        NO_ANSWER,

        /**
         * The surface should answer this trigger and does not yet. A gap to close on its own
         * facts, never by reproducing what the incumbent returned.
         */
        UNIMPLEMENTED
    }

    /**
     * The surfaces a trigger reaches: every surface named exactly once, across the three
     * verdicts. Completeness is checked here rather than defaulted, which is the difference
     * between this table and the one it replaced.
     *
     * <p>A row used to name only the surfaces it had something to say about, and everything
     * else fell to {@link Status#NO_ANSWER}. That kept rows short and hid the case the table
     * exists to catch: a <em>new surface</em> silently acquired a decline on all twenty-one
     * triggers, and no test could tell that from twenty-one deliberate declines. Requiring the
     * verdicts to partition the surface set turns the omission into a build failure, so adding
     * a constant to {@link LspSurface} forces a pass over every row. The cost is that a row
     * names its declines; that is the guard, not an accident of style.
     */
    private record Reach(
        Set<LspSurface> answered, Set<LspSurface> unimplemented, Set<LspSurface> declined
    ) {

        Reach {
            var seen = EnumSet.noneOf(LspSurface.class);
            for (var verdict : List.of(answered, unimplemented, declined)) {
                for (var surface : verdict) {
                    if (!seen.add(surface)) {
                        throw new IllegalArgumentException(
                            "a surface may carry only one verdict per trigger, and " + surface
                                + " carries more than one");
                    }
                }
            }
            var missing = EnumSet.allOf(LspSurface.class);
            missing.removeAll(seen);
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException(
                    "every surface must state a verdict for every trigger, and these state none: "
                        + missing + ". A surface added to LspSurface joins this row too; decide "
                        + "what it does with the trigger rather than letting it default to a "
                        + "decline nobody reviewed.");
            }
        }

    }

    /**
     * A row under construction. Deliberately not a {@link Reach} yet, and deliberately without a
     * "everything else declines" convenience: filling the declines by complement is exactly the
     * defaulting {@link Reach} exists to stop, since a new surface would fall into the
     * complement of every row and no test could see it. The row is finished by
     * {@link #declines}, which names the rest, so a surface that nobody has thought about
     * cannot be silently swept into a verdict.
     */
    private record Row(Set<LspSurface> answered, Set<LspSurface> unimplemented) {

        /** Records surfaces that should answer this trigger and do not yet. */
        Row gaps(LspSurface... surfaces) {
            return new Row(answered, setOf(surfaces));
        }

        /** Names the surfaces that deliberately decline, completing the row. */
        Reach declines(LspSurface... surfaces) {
            return new Reach(answered, unimplemented, setOf(surfaces));
        }
    }

    /** Opens a row: these surfaces answer the trigger. */
    private static Row answers(LspSurface... surfaces) {
        return new Row(setOf(surfaces), EnumSet.noneOf(LspSurface.class));
    }

    private static Set<LspSurface> setOf(LspSurface... surfaces) {
        return surfaces.length == 0
            ? EnumSet.noneOf(LspSurface.class)
            : EnumSet.copyOf(Arrays.asList(surfaces));
    }

    /**
     * The matrix. Read a row as "this trigger is answered by these surfaces, is a known gap on
     * these, and is declined everywhere else".
     *
     * <p>The value bindings come first, then the name tokens, then the document sweeps. The
     * sweeps reach exactly one surface each, because a sweep exists to serve one channel; the
     * value bindings reach up to four, which is why sharing a projection between them looked
     * economical and cost the distinctions the facts carry.
     */
    private static final Map<Class<? extends Trigger>, Reach> MATRIX = Map.ofEntries(

        // The value bindings: a cursor inside a directive-argument value. Each of the four that
        // names a catalog or Java target answers REFERENCES with the coordinates bound to the
        // same target, which is the reverse of the jump DEFINITION makes from the same cursor.
        entry(Behavior.ClassNameBinding.class, answers(COMPLETION, HOVER, DEFINITION, REFERENCES, DIAGNOSTIC)
            .declines(INLAY_HINT, CODE_ACTION)),
        entry(Behavior.MethodNameBinding.class, answers(COMPLETION, HOVER, DEFINITION, REFERENCES, DIAGNOSTIC)
            .declines(INLAY_HINT, CODE_ACTION)),
        entry(Behavior.CatalogTableBinding.class, answers(COMPLETION, HOVER, DEFINITION, REFERENCES, DIAGNOSTIC)
            .declines(INLAY_HINT, CODE_ACTION)),
        entry(Behavior.CatalogColumnBinding.class, answers(COMPLETION, HOVER, DEFINITION, REFERENCES, DIAGNOSTIC)
            .declines(INLAY_HINT, CODE_ACTION)),
        entry(Behavior.CatalogFkBinding.class, answers(COMPLETION, HOVER, DEFINITION, REFERENCES, DIAGNOSTIC)
            .declines(INLAY_HINT, CODE_ACTION)),
        // A @nodeId(typeName:) names an SDL type, so its references are that type's, answered by
        // the same arm the type-name populations go through rather than by one of its own.
        entry(Behavior.NodeTypeBinding.class, answers(COMPLETION, HOVER, REFERENCES, DIAGNOSTIC)
            .gaps(DEFINITION).declines(INLAY_HINT, CODE_ACTION)),
        // @argMapping content addresses input fields, whose usage population is the deferred
        // field-name subject rather than anything this surface answers today.
        entry(Behavior.ArgMappingBinding.class, answers(COMPLETION, DIAGNOSTIC)
            .gaps(HOVER, DEFINITION, REFERENCES).declines(INLAY_HINT, CODE_ACTION)),
        entry(Behavior.ScalarTypeBinding.class, answers(COMPLETION, DIAGNOSTIC)
            .gaps(HOVER, DEFINITION, REFERENCES).declines(INLAY_HINT, CODE_ACTION)),

        // The name tokens: a cursor on a name rather than on a bound value.
        // A directive's own name and its argument names are vocabulary rather than schema
        // coordinates; "where else is @table used" is a question about the vocabulary, and the
        // population that would answer it is the directive-application census, not this surface's.
        entry(Trigger.CursorToken.DirectiveName.class, answers(HOVER)
            .gaps(REFERENCES).declines(COMPLETION, DEFINITION, INLAY_HINT, CODE_ACTION, DIAGNOSTIC)),
        entry(Trigger.CursorToken.DirectiveArgName.class, answers(COMPLETION, HOVER)
            .declines(DEFINITION, REFERENCES, INLAY_HINT, CODE_ACTION, DIAGNOSTIC)),
        // A declaration name is a type's or a member's. The type half is answered; the member
        // half (who uses this field?) is deferred, and the trigger carries one verdict, so the
        // row reads ANSWERED on the strength of the half that resolves.
        entry(Trigger.CursorToken.SdlDeclarationName.class, answers(HOVER, DEFINITION, REFERENCES)
            .declines(COMPLETION, INLAY_HINT, CODE_ACTION, DIAGNOSTIC)),
        entry(Trigger.CursorToken.SdlTypeReference.class, answers(DEFINITION, REFERENCES)
            .gaps(COMPLETION).declines(HOVER, INLAY_HINT, CODE_ACTION, DIAGNOSTIC)),

        // The document sweeps: no cursor, one surface each. REFERENCES is cursor-keyed, so every
        // sweep declines it for the same reason the other cursor surfaces do.
        entry(Trigger.DocumentScan.ClassificationHints.class, answers(INLAY_HINT)
            .declines(COMPLETION, HOVER, DEFINITION, REFERENCES, CODE_ACTION, DIAGNOSTIC)),
        entry(Trigger.DocumentScan.InferredDirectiveHints.class, answers(INLAY_HINT)
            .declines(COMPLETION, HOVER, DEFINITION, REFERENCES, CODE_ACTION, DIAGNOSTIC)),
        entry(Trigger.DocumentScan.AbsentDirectiveHints.class, answers(INLAY_HINT)
            .declines(COMPLETION, HOVER, DEFINITION, REFERENCES, CODE_ACTION, DIAGNOSTIC)),
        entry(Trigger.DocumentScan.LintFindings.class, answers(CODE_ACTION)
            .declines(COMPLETION, HOVER, DEFINITION, REFERENCES, INLAY_HINT, DIAGNOSTIC)),
        entry(Trigger.DocumentScan.SdlActionDetectors.class, answers()
            .gaps(CODE_ACTION).declines(COMPLETION, HOVER, DEFINITION, REFERENCES, INLAY_HINT, DIAGNOSTIC)),
        entry(Trigger.DocumentScan.UnknownArgs.class, answers(DIAGNOSTIC)
            .declines(COMPLETION, HOVER, DEFINITION, REFERENCES, INLAY_HINT, CODE_ACTION)),
        entry(Trigger.DocumentScan.RequiredArgs.class, answers(DIAGNOSTIC)
            .declines(COMPLETION, HOVER, DEFINITION, REFERENCES, INLAY_HINT, CODE_ACTION)),
        entry(Trigger.DocumentScan.UnknownDirective.class, answers(DIAGNOSTIC)
            .declines(COMPLETION, HOVER, DEFINITION, REFERENCES, INLAY_HINT, CODE_ACTION)),
        entry(Trigger.DocumentScan.SchemaValidation.class, answers(DIAGNOSTIC)
            .declines(COMPLETION, HOVER, DEFINITION, REFERENCES, INLAY_HINT, CODE_ACTION)));

    /** Every trigger leaf the matrix declares. Equal to the sealed leaf set, or the meta-test fails. */
    public static Set<Class<? extends Trigger>> declaredTriggers() {
        return MATRIX.keySet();
    }

    /**
     * The status of one cell. Throws rather than defaulting when the trigger has no row, because a
     * missing row is the condition the meta-test exists to catch and silently reporting
     * {@link Status#NO_ANSWER} for it would hide exactly that.
     */
    public static Status statusOf(Class<? extends Trigger> trigger, LspSurface surface) {
        Reach reach = MATRIX.get(trigger);
        if (reach == null) {
            throw new IllegalArgumentException(
                "no dispatch row for trigger " + trigger.getName()
                    + "; every sealed Trigger leaf must be declared in the matrix");
        }
        if (reach.answered().contains(surface)) return Status.ANSWERED;
        if (reach.unimplemented().contains(surface)) return Status.UNIMPLEMENTED;
        return Status.NO_ANSWER;
    }

    /** The triggers a surface answers today. */
    public static Set<Class<? extends Trigger>> answeredBy(LspSurface surface) {
        return triggersWith(surface, Status.ANSWERED);
    }

    /** The triggers a surface should answer and does not yet. */
    public static Set<Class<? extends Trigger>> gapsOf(LspSurface surface) {
        return triggersWith(surface, Status.UNIMPLEMENTED);
    }

    /**
     * The triggers a surface deliberately declines. Together with {@link #answeredBy} and
     * {@link #gapsOf} this covers every trigger, which is the property {@link Reach} enforces
     * row by row and {@code TriggerDispatchMatrixTest} asserts surface by surface. Exposed so
     * that property is assertable rather than only enforceable: a row missing a verdict throws
     * while this class initialises, which is a build failure with a stack trace, and a test that
     * states the partition says what the stack trace means.
     */
    public static Set<Class<? extends Trigger>> declinedBy(LspSurface surface) {
        return triggersWith(surface, Status.NO_ANSWER);
    }

    private static Set<Class<? extends Trigger>> triggersWith(LspSurface surface, Status status) {
        return MATRIX.keySet().stream()
            .filter(t -> statusOf(t, surface) == status)
            .collect(Collectors.toUnmodifiableSet());
    }
}
