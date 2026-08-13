package no.sikt.graphitron.lsp.dispatch;

import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.Trigger;

import java.util.Arrays;
import java.util.EnumSet;
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

/**
 * What every surface does with every trigger, as data rather than as prose. One row per
 * {@link Trigger} leaf, naming the surfaces that answer it and the surfaces where answering it
 * is a known gap; every other surface declines it. The three statuses partition
 * {@code Trigger} leaves × {@link LspSurface} exhaustively, which is what
 * {@code TriggerDispatchMatrixTest} asserts.
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
     * The surfaces a trigger reaches. Anything named in neither set is {@link Status#NO_ANSWER}
     * for that trigger, so the common case (a trigger that concerns one or two surfaces) stays
     * one line and the exhaustiveness still comes from the leaf set rather than from this table.
     */
    private record Reach(Set<LspSurface> answered, Set<LspSurface> unimplemented) {

        Reach {
            var both = EnumSet.noneOf(LspSurface.class);
            both.addAll(answered);
            both.retainAll(unimplemented);
            if (!both.isEmpty()) {
                throw new IllegalArgumentException(
                    "a surface cannot both answer a trigger and have a gap for it: " + both);
            }
        }
    }

    private static Reach answers(LspSurface... surfaces) {
        return new Reach(setOf(surfaces), EnumSet.noneOf(LspSurface.class));
    }

    private static Reach gaps(LspSurface... surfaces) {
        return new Reach(EnumSet.noneOf(LspSurface.class), setOf(surfaces));
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

        // The value bindings: a cursor inside a directive-argument value.
        entry(Behavior.ClassNameBinding.class, answers(COMPLETION, HOVER, DEFINITION, DIAGNOSTIC)),
        entry(Behavior.MethodNameBinding.class, answers(COMPLETION, HOVER, DEFINITION, DIAGNOSTIC)),
        entry(Behavior.CatalogTableBinding.class, answers(COMPLETION, HOVER, DEFINITION, DIAGNOSTIC)),
        entry(Behavior.CatalogColumnBinding.class, answers(COMPLETION, HOVER, DEFINITION, DIAGNOSTIC)),
        entry(Behavior.CatalogFkBinding.class, answers(COMPLETION, HOVER, DEFINITION, DIAGNOSTIC)),
        entry(Behavior.NodeTypeBinding.class,
            new Reach(setOf(COMPLETION, HOVER, DIAGNOSTIC), setOf(DEFINITION))),
        entry(Behavior.ArgMappingBinding.class,
            new Reach(setOf(COMPLETION, DIAGNOSTIC), setOf(HOVER, DEFINITION))),
        entry(Behavior.ScalarTypeBinding.class,
            new Reach(setOf(COMPLETION, DIAGNOSTIC), setOf(HOVER, DEFINITION))),

        // The name tokens: a cursor on a name rather than on a bound value.
        entry(Trigger.CursorToken.DirectiveName.class, answers(HOVER)),
        entry(Trigger.CursorToken.DirectiveArgName.class, answers(COMPLETION, HOVER)),
        entry(Trigger.CursorToken.SdlDeclarationName.class, answers(HOVER, DEFINITION)),
        entry(Trigger.CursorToken.SdlTypeReference.class,
            new Reach(setOf(DEFINITION), setOf(COMPLETION))),

        // The document sweeps: no cursor, one surface each.
        entry(Trigger.DocumentScan.ClassificationHints.class, answers(INLAY_HINT)),
        entry(Trigger.DocumentScan.InferredDirectiveHints.class, answers(INLAY_HINT)),
        entry(Trigger.DocumentScan.AbsentDirectiveHints.class, answers(INLAY_HINT)),
        entry(Trigger.DocumentScan.LintFindings.class, answers(CODE_ACTION)),
        entry(Trigger.DocumentScan.SdlActionDetectors.class, gaps(CODE_ACTION)),
        entry(Trigger.DocumentScan.UnknownArgs.class, answers(DIAGNOSTIC)),
        entry(Trigger.DocumentScan.RequiredArgs.class, answers(DIAGNOSTIC)),
        entry(Trigger.DocumentScan.UnknownDirective.class, answers(DIAGNOSTIC)),
        entry(Trigger.DocumentScan.SchemaValidation.class, answers(DIAGNOSTIC)));

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

    private static Set<Class<? extends Trigger>> triggersWith(LspSurface surface, Status status) {
        return MATRIX.keySet().stream()
            .filter(t -> statusOf(t, surface) == status)
            .collect(Collectors.toUnmodifiableSet());
    }
}
