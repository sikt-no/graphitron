package no.sikt.graphitron.codereferences.dummyreferences;

/**
 * Test fixtures for the pipeline-tier coverage of the SDL-field-to-Java-accessor resolution
 * unification, exercising the binding <em>walk</em> (grounding a child backing class through a
 * parent accessor) rather than only the emission-side property read the {@link R88AccessorFixtures}
 * fixtures cover. Each
 * top-level parent is the reflected return type of a {@code DummyService.r461*} producer; its fields
 * ground child SDL types through the walk's probe under the unified rule set.
 *
 * <p>Behavior changes exercised: walk candidate order (B1), field arguments (B2), {@code @field(name:)}
 * (B3), non-boolean {@code is<Name>} (B4), a public field and a bare accessor not read (B5), and bridge/covariant-return
 * filtering plus inheritance (B6 and the member filter).
 */
public final class R461AccessorFixtures {

    private R461AccessorFixtures() {}

    /** Getter-side child (a Java record → {@code JavaRecordType} when grounded). */
    public record BeanChild(String tag) {}

    /** Bare-side child (a plain POJO → {@code PojoResultType.Backed} when grounded). */
    public static final class FluentChild { public String getTag() { return ""; } }

    /** Neutral child used by the single-accessor cases (record → {@code JavaRecordType}). */
    public record SimpleChild(String tag) {}

    /** Child reached through an inherited accessor. */
    public record InheritedChild(String tag) {}

    /** Child reached through a covariant-return override (a bridge method is generated). */
    public record DetailChild(String tag) {}

    // ===== B1: the class's form decides what is read =====

    /**
     * POJO parent exposing both a fluent {@code film()} and a bean {@code getFilm()} with different
     * return types. A class that is no record is read through its getters, so the walk grounds the
     * child from {@code getFilm()}'s {@link BeanChild}, which is what emission resolves.
     */
    public static final class OrderParentPojo {
        public FluentChild film() { return null; }
        public BeanChild getFilm() { return null; }
    }

    /**
     * Java-record parent with a bare component {@code film()} and a hand-rolled {@code getFilm()}.
     * A record is read through its components, so the walk grounds the child from the component's
     * {@link FluentChild}.
     */
    public record OrderParentRecord(FluentChild film) {
        public BeanChild getFilm() { return null; }
    }

    // ===== B2: field arguments =====

    /**
     * Zero-arg accessor; an argument-bearing SDL field no longer grounds through it (arity gate). The
     * {@code getSibling()} accessor lets a test declare a second object field so the payload is not a
     * single-object-field carrier, isolating the sole-producer rejection from the carrier flip.
     */
    public static final class ZeroArgParent {
        public SimpleChild getChild() { return null; }
        public SimpleChild getSibling() { return null; }
    }

    /** Per-argument accessor; an argument-bearing SDL field grounds through it. */
    public static final class PerArgParent {
        public SimpleChild getChild(String x) { return null; }
    }

    // ===== B3: @field(name:) =====

    /** The walk probes the {@code @field(name:)}-resolved accessor base name, not the raw field name. */
    public static final class RenameParent {
        public SimpleChild getRenamed() { return null; }
    }

    // ===== B4: non-boolean is<Name> =====

    /**
     * {@code isChild()} returns a non-boolean; the is-gate stops it grounding the child. With a single
     * object data field in the SDL this flips the payload from a plain wrapper to a two-level
     * carrier (B8 flip case 1); with a second object field ({@code getSibling()}) it isolates the
     * sole-producer boolean-gate rejection.
     */
    public static final class NonBooleanIsParent {
        public SimpleChild isChild() { return null; }
        public SimpleChild getSibling() { return null; }
    }

    // ===== B5: what is not read =====

    /** Public field of a bindable type: a public field is not read, so it grounds nothing. */
    public static final class PublicFieldParent {
        public SimpleChild child = null;
        public SimpleChild getSibling() { return null; }
    }

    /** A bare accessor on a class that is no record: not read, so it grounds nothing. */
    public static final class ZeroArgParentBare {
        public SimpleChild child() { return null; }
        public SimpleChild getSibling() { return null; }
    }

    // ===== B6: covariant-return bridge + inheritance =====

    /** Covariant-return source interface: the bridge {@code Object getDetail()} must be skipped. */
    public interface DetailSource { Object getDetail(); }

    public static final class CovariantParent implements DetailSource {
        @Override public DetailChild getDetail() { return null; }
    }

    public static class BaseChildParent {
        public InheritedChild getInheritedChild() { return null; }
    }

    /** Inherits {@code getInheritedChild()} from its superclass; the accessor is still matched. */
    public static final class SubChildParent extends BaseChildParent {}
}
