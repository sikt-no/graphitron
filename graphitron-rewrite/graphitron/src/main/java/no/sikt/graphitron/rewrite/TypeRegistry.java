package no.sikt.graphitron.rewrite;

import graphql.language.SourceLocation;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Type-axis classification registry that funnels every write through one of four named
 * operations ({@link #classify}, {@link #enrich}, {@link #demote}, {@link #synthesize})
 * and emits a {@link ClassificationTrace} record per call. The backing map is private,
 * so a new bypass site has to add a public method on the registry (visible in code review).
 *
 * <p>Operation contracts:
 * <ul>
 *   <li>{@code classify} — primary classification. Asserts no prior entry exists.
 *   <li>{@code enrich} — replaces an entry with a structurally compatible richer version
 *       (e.g. {@code InterfaceType} gaining its resolved participants). Asserts a prior
 *       entry exists.
 *   <li>{@code demote} — replaces an entry with {@link UnclassifiedType} (or any other
 *       classification regression). Asserts a prior entry exists.
 *   <li>{@code synthesize} — graphitron-generated type with no SDL origin (Connection /
 *       Edge / PageInfo from {@code @asConnection}). Asserts no prior entry exists.
 * </ul>
 */
public final class TypeRegistry {

    private final Map<String, GraphitronType> types = new LinkedHashMap<>();

    /**
     * R279 slice 2 — the single reconciling write entry the field-first walk will call (slice 3) to
     * register a type's classification, tolerating repeated registration. The eager type pass routes
     * its per-type classification ({@code classify}) and participant enrichment ({@code enrich})
     * through here; {@code demote} / {@code synthesize} and the cross-type passes stay on their
     * explicit verbs through this slice.
     *
     * <p>Reconciliation:
     * <ul>
     *   <li>name absent → store (the {@code classify} case);
     *   <li>repeat that agrees ({@code equals}) → idempotent no-op;
     *   <li>demotion to {@link UnclassifiedType} → replace (the enrich-to-rejection case, and the
     *       future field-walk rejection);
     *   <li>same-kind enrichment (same concrete type, richer value) → replace (the {@code enrich} case);
     *   <li>incompatible repeat (two <em>different</em> concrete classifications) → <strong>throws</strong>.
     * </ul>
     *
     * <p>The throw is a deliberate tripwire: the eager two-pass driver provably never produces an
     * incompatible repeat (every repeat is the enrich pass replacing a same-kind or rejected value),
     * so reaching it in slice 2 is a bug. Slice 3 replaces the throw with demotion-to-{@code
     * UnclassifiedType} once the field walk can register genuinely-competing verdicts, landing the
     * exact compatibility predicate together with its validator mirror against real inputs rather
     * than guessing it here. The absent/present axis is shaped so slice 5 can fold
     * {@code ConnectionPromoter}'s hand-rolled enrich-or-synthesize fork into this same entry.
     */
    public void register(String name, GraphitronType type) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        var existing = types.get(name);
        if (existing == null) {
            types.put(name, type);
            trace(ClassificationTrace.Op.classify, name, type);
            return;
        }
        if (existing.equals(type)) {
            return;
        }
        if (type instanceof UnclassifiedType) {
            types.put(name, type);
            trace(ClassificationTrace.Op.demote, name, type);
            return;
        }
        if (existing.getClass() == type.getClass()) {
            types.put(name, type);
            trace(ClassificationTrace.Op.enrich, name, type);
            return;
        }
        throw new IllegalStateException("register('" + name + "'): incompatible repeat — "
            + existing.getClass().getSimpleName() + " then " + type.getClass().getSimpleName()
            + "; the eager type pass must never produce an incompatible repeat. Slice 3 replaces "
            + "this tripwire with demotion-to-UnclassifiedType plus its validator mirror.");
    }

    /** Register a type for the first time. Throws if {@code name} is already classified. */
    public void classify(String name, GraphitronType type) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        if (types.containsKey(name)) {
            throw new IllegalStateException("classify('" + name + "'): already classified as "
                + types.get(name).getClass().getSimpleName());
        }
        types.put(name, type);
        trace(ClassificationTrace.Op.classify, name, type);
    }

    /**
     * Replace an existing entry with a structurally compatible enriched version. Throws if
     * {@code name} is not yet classified.
     */
    public void enrich(String name, GraphitronType type) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        if (!types.containsKey(name)) {
            throw new IllegalStateException("enrich('" + name + "'): no prior classification");
        }
        types.put(name, type);
        trace(ClassificationTrace.Op.enrich, name, type);
    }

    /**
     * Replace an existing entry with a classification regression (typically
     * {@link UnclassifiedType}). Throws if {@code name} is not yet classified.
     */
    public void demote(String name, GraphitronType type) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        if (!types.containsKey(name)) {
            throw new IllegalStateException("demote('" + name + "'): no prior classification");
        }
        types.put(name, type);
        trace(ClassificationTrace.Op.demote, name, type);
    }

    /**
     * Register a graphitron-synthesised type with no SDL origin (Connection / Edge /
     * PageInfo). Throws if {@code name} is already classified — the synthesis pass is
     * expected to early-return on collision rather than overwrite.
     */
    public void synthesize(String name, GraphitronType type) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        if (types.containsKey(name)) {
            throw new IllegalStateException("synthesize('" + name + "'): already classified as "
                + types.get(name).getClass().getSimpleName());
        }
        types.put(name, type);
        trace(ClassificationTrace.Op.synthesize, name, type);
    }

    /** True when {@code name} has been classified by any operation. */
    public boolean contains(String name) {
        return types.containsKey(name);
    }

    /** Returns the current classification for {@code name}, or {@code null}. */
    public GraphitronType get(String name) {
        return types.get(name);
    }

    /** Read-only view of all classifications, in insertion order. */
    public Map<String, GraphitronType> entries() {
        return Collections.unmodifiableMap(types);
    }

    private static void trace(ClassificationTrace.Op op, String name, GraphitronType type) {
        if (!ClassificationTrace.isEnabled()) return;
        SourceLocation loc = type.location();
        String source = loc == null ? null : loc.getSourceName();
        if (type instanceof UnclassifiedType u) {
            ClassificationTrace.emit(op, "", name, leafName(type), source,
                RejectionKind.of(u.rejection()), u.rejection().message());
        } else {
            ClassificationTrace.emit(op, "", name, leafName(type), source, null, null);
        }
    }

    private static String leafName(GraphitronType type) {
        Class<?> c = type.getClass();
        Class<?> enclosing = c.getEnclosingClass();
        if (enclosing != null) {
            return enclosing.getSimpleName() + "." + c.getSimpleName();
        }
        return c.getSimpleName();
    }
}
