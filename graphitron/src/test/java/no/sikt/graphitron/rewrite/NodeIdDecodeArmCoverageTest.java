package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.HelperRef;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mint pin. {@link NodeIdDecodeLedger#DECODE_ARMS} says which {@link CallSiteExtraction} arms
 * carry a node-id decode, and this computes the same set from the arms themselves.
 *
 * <p>It exists because the plan this implements once carried the arm list as prose, and that list
 * went stale inside a week: {@code NodeIdDecodePolymorphicRecord} landed as a fourth decode arm
 * while the plan sat waiting for review. Making the list a compiler-and-meta-test question is what
 * replaces the prose. The exhaustive switch in {@link NodeIdDecodeLedger#installedBy} catches a
 * fifth arm at compile time and makes an author answer for it; this catches an arm answered
 * <em>wrongly</em>, which the compiler cannot see. The failure mode it guards is the dangerous one:
 * a decode arm the ledger does not recognise reads downstream as a dropped instruction and fails a
 * build that should pass.
 *
 * <p><b>What "carries a decode" is, structurally.</b> An arm reaches a handle on the opaque wire
 * format: either a {@link HelperRef.Decode}, which is a resolved {@code decode<Type>} helper, or an
 * {@code encoderClass} naming the generated encoder the emitter calls through. Those are the two
 * spellings the tree has, and either one means the arm turns a wire id into key values. The search
 * descends through record components and through the element types of generic ones, so a decode
 * held one record down ({@code JooqRecord}'s {@code RecordKeyDecode} list, the one decode leaf that
 * is not an arm of the seal) is found where it sits.
 *
 * <p>The descent deliberately stops at a component typed as {@link CallSiteExtraction} itself.
 * Such a component is a delegation rather than a decode: {@code NestedInputField} carries whatever
 * its leaf carries, which is a property of a value and not of the arm, and following it would make
 * every arm reachable from every other.
 */
@UnitTier
class NodeIdDecodeArmCoverageTest {

    /** The component name that stands for a generated encoder the emitter decodes through. */
    private static final String ENCODER_COMPONENT = "encoderClass";

    @Test
    void theDeclaredDecodeArmsAreExactlyTheArmsThatReachAWireFormatHandle() {
        var structural = new TreeSet<String>();
        for (var arm : leafArms()) {
            if (reachesAWireFormatHandle(arm)) {
                structural.add(arm.getSimpleName());
            }
        }
        var declared = new TreeSet<String>();
        NodeIdDecodeLedger.DECODE_ARMS.forEach(c -> declared.add(c.getSimpleName()));

        assertThat(structural)
            .as("the arms that reach a decode helper or a generated encoder; the ledger's declared"
                + " set has to name exactly these, or an install reads as a dropped instruction")
            .isEqualTo(declared);
        assertThat(structural)
            .as("arms scanned (the reflection must not be vacuous)")
            .hasSizeGreaterThanOrEqualTo(5);
    }

    /**
     * That the delegating arm delegates. {@code NestedInputField} is the one arm whose answer is a
     * property of the value rather than of the class, so the set above cannot state it and this
     * exercises it directly: a descent whose leaf decodes is an install, and one whose leaf does
     * not is not.
     */
    @Test
    void aDescentInstallsExactlyWhatItsLeafInstalls() {
        var decoding = new CallSiteExtraction.NestedInputField("filter", java.util.List.of("id"),
            new CallSiteExtraction.ThrowOnMismatch(new HelperRef.Decode(
                no.sikt.graphitron.javapoet.ClassName.bestGuess("a.B"), "decodeFilm",
                java.util.List.of(), "Film")));
        var plain = new CallSiteExtraction.NestedInputField("filter", java.util.List.of("title"),
            new CallSiteExtraction.Direct());

        assertThat(NodeIdDecodeLedger.installedBy(decoding))
            .as("a descent onto a decoding leaf installs")
            .isTrue();
        assertThat(NodeIdDecodeLedger.installedBy(plain))
            .as("a descent onto an ordinary leaf does not")
            .isFalse();
    }

    /** Every leaf arm of the sealed hierarchy, interior seals expanded. */
    private static Set<Class<?>> leafArms() {
        var out = new LinkedHashSet<Class<?>>();
        var pending = new ArrayDeque<Class<?>>();
        pending.add(CallSiteExtraction.class);
        while (!pending.isEmpty()) {
            Class<?> next = pending.poll();
            var permitted = next.getPermittedSubclasses();
            if (permitted == null || permitted.length == 0) {
                if (next != CallSiteExtraction.class) {
                    out.add(next);
                }
                continue;
            }
            pending.addAll(java.util.List.of(permitted));
        }
        return out;
    }

    /**
     * Whether {@code arm} reaches a {@link HelperRef.Decode} or an encoder handle through its
     * record components, stopping at components typed as the seal itself.
     */
    private static boolean reachesAWireFormatHandle(Class<?> arm) {
        var seen = new HashSet<Class<?>>();
        var pending = new ArrayDeque<Class<?>>();
        pending.add(arm);
        while (!pending.isEmpty()) {
            Class<?> next = pending.poll();
            if (!next.isRecord() || !seen.add(next)) {
                continue;
            }
            for (RecordComponent component : next.getRecordComponents()) {
                if (HelperRef.Decode.class.equals(component.getType())
                        || ENCODER_COMPONENT.equals(component.getName())) {
                    return true;
                }
                for (Class<?> reached : reachedTypes(component)) {
                    if (!CallSiteExtraction.class.isAssignableFrom(reached)) {
                        pending.add(reached);
                    }
                }
            }
        }
        return false;
    }

    /** A component's own type plus the type arguments of a generic one, as raw classes. */
    private static Set<Class<?>> reachedTypes(RecordComponent component) {
        var out = new LinkedHashSet<Class<?>>();
        out.add(component.getType());
        if (component.getGenericType() instanceof ParameterizedType parameterized) {
            for (Type argument : parameterized.getActualTypeArguments()) {
                if (argument instanceof Class<?> raw) {
                    out.add(raw);
                }
            }
        }
        return out;
    }
}
