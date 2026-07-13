package no.sikt.graphitron.codereferences.dummyreferences;

/**
 * Test fixtures for R461's pipeline-tier coverage of the SDL-field-to-Java-accessor resolution
 * unification, exercising the R96 binding <em>walk</em> (grounding a child backing class through a
 * parent accessor) rather than only the emission-side property read the R88 fixtures cover. Each
 * top-level parent is the reflected return type of a {@code DummyService.r461*} producer; its fields
 * ground child SDL types through the walk's probe under the unified rule set.
 *
 * <p>Behavior changes exercised: walk candidate order (B1), field arguments (B2), {@code @field(name:)}
 * (B3), non-boolean {@code is<Name>} (B4), public-field fallback (B5), and bridge/covariant-return
 * filtering plus inheritance (B6 and the member filter).
 */
public final class R461AccessorFixtures {

    private R461AccessorFixtures() {}

    /** Getter-side child (a Java record → {@code JavaRecordType} when grounded). */
    public record BeanChild(String tag) {}

    /** Bare-side child (a plain POJO → {@code PojoResultType.Backed} when grounded). */
    public static final class FluentChild { public String tag() { return ""; } }

    /** Neutral child used by the single-accessor cases (record → {@code JavaRecordType}). */
    public record SimpleChild(String tag) {}

    /** Child reached through an inherited accessor. */
    public record InheritedChild(String tag) {}

    /** Child reached through a covariant-return override (a bridge method is generated). */
    public record DetailChild(String tag) {}

    // ===== B1: walk candidate order =====

    /**
     * POJO parent exposing both a fluent {@code film()} and a bean {@code getFilm()} with different
     * return types. Under {@code POJO_FIRST} the walk grounds the child from {@code getFilm()}'s
     * {@link BeanChild}, which is what emission resolves — the headline failure-scenario fix.
     */
    public static final class OrderParentPojo {
        public FluentChild film() { return null; }
        public BeanChild getFilm() { return null; }
    }

    /**
     * Java-record parent with a bare component {@code film()} and a hand-rolled {@code getFilm()}.
     * Under {@code RECORD_FIRST} the walk grounds the child from the bare component's
     * {@link FluentChild}.
     */
    public record OrderParentRecord(FluentChild film) {
        public BeanChild getFilm() { return null; }
    }

    // ===== B2: field arguments =====

    /**
     * Zero-arg accessor; an argument-bearing SDL field no longer grounds through it (arity gate). The
     * {@code sibling()} accessor lets a test declare a second object field so the payload is not a
     * single-object-field R329 carrier, isolating the sole-producer rejection from the carrier flip.
     */
    public static final class ZeroArgParent {
        public SimpleChild child() { return null; }
        public SimpleChild sibling() { return null; }
    }

    /** Per-argument accessor; an argument-bearing SDL field grounds through it. */
    public static final class PerArgParent {
        public SimpleChild child(String x) { return null; }
    }

    // ===== B3: @field(name:) =====

    /** The walk probes the {@code @field(name:)}-resolved accessor base name, not the raw field name. */
    public static final class RenameParent {
        public SimpleChild renamed() { return null; }
    }

    // ===== B4: non-boolean is<Name> =====

    /**
     * {@code isChild()} returns a non-boolean; the is-gate stops it grounding the child. With a single
     * object data field in the SDL this flips the payload from a plain wrapper to an R329 two-level
     * carrier (B8 flip case 1); with a second object field ({@code sibling()}) it isolates the
     * sole-producer boolean-gate rejection.
     */
    public static final class NonBooleanIsParent {
        public SimpleChild isChild() { return null; }
        public SimpleChild sibling() { return null; }
    }

    // ===== B5: public-field fallback =====

    /** Public field of a bindable type; grounds a no-argument field, not an argument-bearing one. */
    public static final class PublicFieldParent {
        public SimpleChild child = null;
        public SimpleChild sibling() { return null; }
    }

    // ===== B6: covariant-return bridge + inheritance =====

    /** Covariant-return source interface: the bridge {@code Object detail()} must be skipped. */
    public interface DetailSource { Object detail(); }

    public static final class CovariantParent implements DetailSource {
        @Override public DetailChild detail() { return null; }
    }

    public static class BaseChildParent {
        public InheritedChild inheritedChild() { return null; }
    }

    /** Inherits {@code inheritedChild()} from its superclass; the accessor is still matched. */
    public static final class SubChildParent extends BaseChildParent {}
}
