package no.sikt.graphitron.rewrite;

import graphql.language.SourceLocation;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLObjectType;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ConnectionType;
import no.sikt.graphitron.rewrite.model.GraphitronType.EdgeType;
import no.sikt.graphitron.rewrite.model.GraphitronType.PageInfoType;
import no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType;
import no.sikt.graphitron.rewrite.model.Rejection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Type-axis classification registry that funnels every write through {@link #register} and emits a
 * {@link ClassificationTrace} record per call. The backing map is private, so a new bypass site has
 * to add a public method on the registry (visible in code review).
 *
 * <p>The verb-collapse is complete: {@link #register} is the
 * <strong>only</strong> write verb. The former {@code classify} (assert-absent), {@code demote}
 * (assert-present), {@code enrich}, and {@code synthesize} verbs all dissolved into it; every caller
 * now registers what a field or a cross-type pass implies and the accumulator reconciles. The trace
 * {@code Op} (classify / demote / enrich) is still recorded per call, derived from the reconciliation
 * arm taken, so the observability the named verbs gave is preserved without the caller-side branching.
 */
public final class TypeRegistry {

    private final Map<String, GraphitronType> types = new LinkedHashMap<>();

    /**
     * The single reconciling write entry. The field-first walk, connection synthesis, and every
     * cross-type pass (nesting-type registration, the case-fold collision sweep, node-typeId
     * uniqueness, multi-producer rejection, federation entity demotion) route through here; there
     * is no other write verb. Tolerates repeated registration, reconciling by the
     * rules below.
     *
     * <p>Reconciliation:
     * <ul>
     *   <li>name absent → store (the former {@code classify} case);
     *   <li>repeat that agrees ({@code equals}) → idempotent no-op;
     *   <li>demotion to {@link UnclassifiedType} → replace (the enrich-to-rejection case, and the
     *       field-walk rejection);
     *   <li>same-kind enrichment of a tag-bearing synthesised arm
     *       ({@link ConnectionType} / {@link EdgeType} / {@link PageInfoType}) → <strong>merge</strong>
     *       (union the federation {@code @tag} applications, OR the {@code shareable} flag), so two
     *       {@code @asConnection} carriers reaching the same connection name, and the single shared
     *       {@code PageInfo}, accumulate the union of their tags without the producer reasoning about
     *       multiplicity;
     *   <li>same-kind enrichment otherwise (same concrete type, richer value) → replace (the
     *       {@code enrich} case);
     *   <li>incompatible repeat (two <em>different</em> concrete classifications) → demote to
     *       {@link UnclassifiedType}, surfaced by {@code GraphitronSchemaValidator}'s unclassified-type
     *       pass.
     * </ul>
     *
     * <p>Demotion is the accumulator's reaction to an incompatible repeat, not a verb the producer
     * calls: the field-first walk registers what each field implies and never reasons about conflict.
     * The eager driver provably never produces an incompatible repeat, so the demote arm is reached
     * only once the walk registers genuinely-competing verdicts (e.g. a connection name colliding with
     * an SDL type), where it replaces what was previously a silent first-write enrich with a build
     * error. Connection synthesis ({@code ConnectionPromoter.synthesiseForField}) routes through this
     * entry; its former hand-rolled enrich-or-synthesize fork and tag-union post-pass are gone.
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
            var merged = mergeSynthesisedTags(existing, type);
            types.put(name, merged);
            trace(ClassificationTrace.Op.enrich, name, merged);
            return;
        }
        var demoted = new UnclassifiedType(name, type.location(), Rejection.structural(
            "type '" + name + "' classified incompatibly: " + existing.getClass().getSimpleName()
            + " then " + type.getClass().getSimpleName()
            + ". A synthesised Connection / Edge / PageInfo name collides with an SDL-declared type, "
            + "or two fields imply different classifications for it; rename one so each type name maps "
            + "to a single classification."));
        types.put(name, demoted);
        trace(ClassificationTrace.Op.demote, name, demoted);
    }

    /**
     * Reconciles two same-kind registrations of a tag-bearing synthesised arm by unioning their
     * federation {@code @tag} applications (and OR-ing {@code shareable}); every other same-kind
     * repeat keeps the incoming value (plain enrich). This is the single home of the connection
     * tag-union that {@code ConnectionPromoter} previously did as a post-pass: carriers sharing a
     * connection name, and every carrier feeding the one shared {@code PageInfo}, contribute the
     * union of their tags regardless of visit order, so the merge is commutative. Structural /
     * SDL-declared entries reference the same assembled-schema form on every registration, so they
     * compare equal and never reach here; only the directive-driven synthesised forms (whose only
     * applied directives are {@code @tag} and {@code @shareable}) are merged.
     */
    private static GraphitronType mergeSynthesisedTags(GraphitronType existing, GraphitronType incoming) {
        return switch (existing) {
            case ConnectionType e -> {
                var i = (ConnectionType) incoming;
                yield new ConnectionType(e.name(), e.location(), e.elementTypeName(), e.edgeTypeName(),
                    e.itemNullable(), e.shareable() || i.shareable(), e.facets(),
                    unionDirectives(e.schemaType(), i.schemaType()));
            }
            case EdgeType e -> {
                var i = (EdgeType) incoming;
                yield new EdgeType(e.name(), e.location(), e.elementTypeName(),
                    e.itemNullable(), e.shareable() || i.shareable(),
                    unionDirectives(e.schemaType(), i.schemaType()));
            }
            case PageInfoType e -> {
                var i = (PageInfoType) incoming;
                yield new PageInfoType(e.name(), e.location(), e.shareable() || i.shareable(),
                    unionDirectives(e.schemaType(), i.schemaType()));
            }
            default -> incoming;
        };
    }

    /**
     * Returns {@code existing} with every applied directive from {@code incoming} it does not already
     * carry appended. Identity is the directive name plus its {@code name} argument (so repeatable
     * {@code @tag(name:)} dedups per value while non-repeatable markers like {@code @shareable} dedup
     * by name). Returns {@code existing} unchanged when there is nothing to add.
     */
    private static GraphQLObjectType unionDirectives(GraphQLObjectType existing, GraphQLObjectType incoming) {
        if (existing == null) return incoming;
        if (incoming == null) return existing;
        var present = new HashSet<Object>();
        for (var d : existing.getAppliedDirectives()) present.add(directiveKey(d));
        var toAdd = new ArrayList<GraphQLAppliedDirective>();
        for (var d : incoming.getAppliedDirectives()) {
            if (present.add(directiveKey(d))) toAdd.add(d);
        }
        if (toAdd.isEmpty()) return existing;
        return existing.transform(b -> toAdd.forEach(b::withAppliedDirective));
    }

    private static Object directiveKey(GraphQLAppliedDirective directive) {
        var nameArg = directive.getArgument("name");
        Object argValue = nameArg == null ? null : nameArg.getValue();
        return List.of(directive.getName(), String.valueOf(argValue));
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
