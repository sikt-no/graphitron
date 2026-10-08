package no.sikt.graphitron.rewrite;

import graphql.language.SourceLocation;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLObjectType;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ConnectionType;
import no.sikt.graphitron.rewrite.model.GraphitronType.EdgeType;
import no.sikt.graphitron.rewrite.model.GraphitronType.FacetValueType;
import no.sikt.graphitron.rewrite.model.GraphitronType.FacetsType;
import no.sikt.graphitron.rewrite.model.GraphitronType.PageInfoType;
import no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType;
import no.sikt.graphitron.model.diagnostics.Rejection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import no.sikt.graphitron.model.diagnostics.RejectionKind;

/**
 * Type-axis classification registry that funnels every write through {@link #register} and emits a
 * {@link ClassificationTrace} record per call. The backing map is private, so a new bypass site has
 * to add a public method on the registry (visible in code review).
 *
 * <p>{@link #register} is the <strong>only</strong> write verb: every caller registers what a
 * field or a cross-type pass implies and the accumulator reconciles. The trace {@code Op}
 * (classify / demote / enrich) is recorded per call, derived from the reconciliation arm taken.
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
     *   <li>name absent → store (traced as {@code classify});
     *   <li>repeat that agrees ({@code equals}) → idempotent no-op;
     *   <li>demotion to {@link UnclassifiedType} → replace (the enrich-to-rejection case, and the
     *       field-walk rejection);
     *   <li>same-kind enrichment of a tag-bearing synthesised arm
     *       ({@link ConnectionType} / {@link EdgeType} / {@link PageInfoType}) → <strong>merge</strong>
     *       (union the federation {@code @tag} applications, OR the {@code shareable} flag), so two
     *       {@code @asConnection} carriers reaching the same connection name, and the single shared
     *       {@code PageInfo}, accumulate the union of their tags without the producer reasoning about
     *       multiplicity;
     *   <li>same-kind enrichment otherwise (same concrete type, richer value) → replace (traced
     *       as {@code enrich});
     *   <li>incompatible repeat (two <em>different</em> concrete classifications) → demote to
     *       {@link UnclassifiedType}, surfaced by {@code GraphitronSchemaValidator}'s unclassified-type
     *       pass.
     * </ul>
     *
     * <p>Demotion is the accumulator's reaction to an incompatible repeat, not a verb the producer
     * calls: the field-first walk registers what each field implies and never reasons about conflict.
     * The demote arm is reached only when the walk registers genuinely-competing verdicts (e.g. a
     * connection name colliding with an SDL type), which is a build error.
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
     * Reconciles two same-kind registrations of a synthesised arm that inherits federation
     * directives from its carriers: the {@code @tag}s both carry, and {@code @shareable} when
     * either is (the {@code shareable} flag OR-ed alongside). Every other same-kind repeat keeps
     * the incoming value (plain enrich). This is the walk's home of the rule
     * {@code EmittedRegistry} applies on the store side: a type several carriers mint (a Connection
     * and Edge shared through {@code connectionName:}, the one {@code PageInfo}, a facet value type)
     * carries the tags every carrier carries, since a contract excluding one carrier's tag must
     * keep the type for the others, and is shareable when any carrier is, which is a composition
     * requirement rather than a filter. Both are commutative and associative, so the merge is
     * independent of visit order. Structural / SDL-declared entries reference the same
     * assembled-schema form on every registration, so they compare equal and never reach here;
     * only the directive-driven synthesised forms (whose only applied directives are {@code @tag}
     * and {@code @shareable}) are merged. The first registration's location is kept.
     */
    private static GraphitronType mergeSynthesisedTags(GraphitronType existing, GraphitronType incoming) {
        return switch (existing) {
            case ConnectionType e -> {
                var i = (ConnectionType) incoming;
                yield new ConnectionType(e.name(), e.location(), e.elementTypeName(), e.edgeTypeName(),
                    e.itemNullable(), e.shareable() || i.shareable(), e.facets(),
                    mergeDirectives(e.schemaType(), i.schemaType()));
            }
            case EdgeType e -> {
                var i = (EdgeType) incoming;
                yield new EdgeType(e.name(), e.location(), e.elementTypeName(),
                    e.itemNullable(), e.shareable() || i.shareable(),
                    mergeDirectives(e.schemaType(), i.schemaType()));
            }
            case PageInfoType e -> {
                var i = (PageInfoType) incoming;
                yield new PageInfoType(e.name(), e.location(), e.shareable() || i.shareable(),
                    mergeDirectives(e.schemaType(), i.schemaType()));
            }
            case FacetsType e -> {
                var i = (FacetsType) incoming;
                yield new FacetsType(e.name(), e.location(), e.connectionName(),
                    mergeDirectives(e.schemaType(), i.schemaType()));
            }
            case FacetValueType e -> {
                var i = (FacetValueType) incoming;
                yield new FacetValueType(e.name(), e.location(), e.valueTypeName(), e.valueNullable(),
                    mergeDirectives(e.schemaType(), i.schemaType()));
            }
            default -> incoming;
        };
    }

    /**
     * Returns {@code existing} carrying the {@code @tag} applications both forms carry and every
     * other applied directive either carries: the other directives first, in the order
     * {@code existing} then {@code incoming} carries them, then the kept tags in {@code existing}'s
     * order, which is the order the synthesised builders apply them in. Identity is the directive
     * name plus its {@code name} argument (so repeatable {@code @tag(name:)} matches per value while
     * non-repeatable markers like {@code @shareable} match by name). Returns {@code existing}
     * unchanged when the merge changes nothing.
     */
    private static GraphQLObjectType mergeDirectives(GraphQLObjectType existing, GraphQLObjectType incoming) {
        if (existing == null) return incoming;
        if (incoming == null) return existing;
        var incomingTags = new HashSet<Object>();
        for (var d : incoming.getAppliedDirectives()) {
            if (TAG_DIRECTIVE.equals(d.getName())) incomingTags.add(directiveKey(d));
        }
        var others = new ArrayList<GraphQLAppliedDirective>();
        var tags = new ArrayList<GraphQLAppliedDirective>();
        var present = new HashSet<Object>();
        for (var d : existing.getAppliedDirectives()) {
            if (!TAG_DIRECTIVE.equals(d.getName())) {
                if (present.add(directiveKey(d))) others.add(d);
            } else if (incomingTags.contains(directiveKey(d)) && present.add(directiveKey(d))) {
                tags.add(d);
            }
        }
        for (var d : incoming.getAppliedDirectives()) {
            if (!TAG_DIRECTIVE.equals(d.getName()) && present.add(directiveKey(d))) others.add(d);
        }
        var merged = new ArrayList<>(others);
        merged.addAll(tags);
        if (merged.equals(existing.getAppliedDirectives())) return existing;
        return existing.transform(b -> b.replaceAppliedDirectives(merged));
    }

    /** The federation {@code @tag} directive name; matches {@code TagApplier.TAG_DIRECTIVE_NAME}. */
    private static final String TAG_DIRECTIVE = "tag";

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
