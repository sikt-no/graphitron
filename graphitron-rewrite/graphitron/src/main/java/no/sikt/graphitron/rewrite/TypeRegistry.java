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
