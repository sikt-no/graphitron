package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.ConflictSite;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.MethodBackedField;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.ResolvedContextArg;
import no.sikt.graphitron.rewrite.model.SqlGeneratingField;
import no.sikt.graphitron.rewrite.model.WhereFilter;
import no.sikt.graphitron.rewrite.model.ConditionFilter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Cross-site {@code contextArgument} type-agreement classifier.
 *
 * <p>Walks every {@link MethodRef} reachable from a classified field set (the schema's fields and
 * their condition filters), collects every {@link MethodRef.Param.Typed} whose source is
 * {@link ParamSource.Context}, keys by parameter name, and requires every site to declare the
 * same structural {@link TypeName}. The classifier returns both:
 *
 * <ul>
 *   <li>{@link Classification#resolved}, alphabetically sorted map of contextArgument names that
 *       resolved unambiguously to a single {@link TypeName}. Consumed by the schema-driven factory
 *       emitter ({@code Graphitron.newExecutionInput(...)}) and the call-site emitter (the Java
 *       cast literal at the {@code getContextArgument} call). Both emitters read
 *       {@link ResolvedContextArg#javaType} directly: that single source of truth is what prevents
 *       the factory's typed {@code put} and the call-site's typed cast from drifting.</li>
 *   <li>{@link Classification#conflicts}, the typed
 *       {@link Rejection.AuthorError.TypeConflict} rejections for every name with disagreeing
 *       sites. The validator drains these into {@link ValidationError}s via
 *       {@code GraphitronSchemaValidator.validateContextArgumentTypeAgreement}.</li>
 * </ul>
 *
 * <p>The classifier is invoked once at parse boundary by {@link GraphitronSchema}'s constructor;
 * the cached {@link Classification} hangs off {@link GraphitronSchema#contextArguments()} and is
 * read by both downstream consumers (validator + facade emitter) without re-classifying.
 */
public final class ContextArgumentClassifier {

    private ContextArgumentClassifier() {}

    public record Classification(
        Map<String, ResolvedContextArg> resolved,
        List<Rejection> conflicts
    ) {
        public Classification {
            // LinkedHashMap (not Map.copyOf) so the alphabetical TreeMap iteration order the
            // classifier built survives the defensive copy. Map.copyOf returns an unmodifiable map
            // whose iteration order is unspecified per JDK 16+ docs and can drift across runs.
            resolved = Collections.unmodifiableMap(new LinkedHashMap<>(resolved));
            conflicts = List.copyOf(conflicts);
        }
    }

    public static Classification classify(GraphitronSchema schema) {
        return classify(schema.fields().values());
    }

    /**
     * Same as {@link #classify(GraphitronSchema)} but takes the classified fields directly. Used
     * by {@link GraphitronSchema}'s constructor to compute the {@link Classification} eagerly
     * during schema construction, before the schema record is fully assembled.
     */
    public static Classification classify(Collection<GraphitronField> fields) {
        // Working map: name -> per-site (MethodRef, TypeName) pair in encounter order.
        var byName = new LinkedHashMap<String, List<ConflictSite>>();
        for (var field : fields) {
            collectFromField(field, byName);
        }
        // Walk types so that input-field-level @condition filters reachable only through
        // GraphitronType.InputType / TableInputType also contribute their MethodRefs.
        // Today's projection step appends them to SqlGeneratingField.filters() so the field-walk
        // catches them, but visiting input fields directly via their condition() accessor is
        // shape-symmetric and resilient to any future drift in the projection.
        for (var field : fields) {
            collectFromInputFieldCondition(field, byName);
        }
        // Stable alphabetical output for deterministic factory parameter ordering.
        var resolved = new TreeMap<String, ResolvedContextArg>();
        var conflicts = new ArrayList<Rejection>();
        for (var entry : byName.entrySet()) {
            String name = entry.getKey();
            List<ConflictSite> sites = entry.getValue();
            var distinct = sites.stream()
                .map(ConflictSite::declared)
                .distinct()
                .toList();
            if (distinct.size() == 1) {
                var methodSites = sites.stream().map(ConflictSite::site).toList();
                resolved.put(name, new ResolvedContextArg(name, distinct.get(0), methodSites));
            } else {
                conflicts.add(Rejection.contextArgumentTypeConflict(name, sites));
            }
        }
        return new Classification(resolved, conflicts);
    }

    private static void collectFromField(GraphitronField field,
            Map<String, List<ConflictSite>> byName) {
        if (field instanceof MethodBackedField mbf) {
            collectFromMethodRef(mbf.method(), byName);
        }
        // The four root sync @service permits no longer implement MethodBackedField;
        // their context-arg slots live on the carrier. Walk both rounds and project every
        // FromContext entry into the same per-name conflict-site map.
        if (field instanceof no.sikt.graphitron.rewrite.model.ServiceField sf) {
            collectFromCarrier(sf.serviceMethodCall(), byName);
        }
        if (field instanceof SqlGeneratingField sgf) {
            for (WhereFilter wf : sgf.filters()) {
                if (wf instanceof ConditionFilter cf) {
                    collectFromMethodRef(cf, byName);
                }
            }
        }
    }

    /**
     * Walks a {@link ServiceMethodCall} carrier for {@link MappingEntry.FromContext} entries
     * across {@code ctorArgs} (when present) and {@code methodArgs}, recording each as a
     * {@link ConflictSite} keyed on the carrier's class + method coordinate. The walker enforces
     * the same per-name fold as {@link #collectFromMethodRef}. The carrier is carried
     * honestly through {@link ConflictSite.Site.Carrier}, retiring the empty synthetic
     * {@link MethodRef.Service} sentinel the carrier coordinate previously fabricated to satisfy
     * {@link ConflictSite}'s old {@code MethodRef}-typed field.
     */
    private static void collectFromCarrier(
            no.sikt.graphitron.rewrite.model.ServiceMethodCall carrier,
            Map<String, List<ConflictSite>> byName) {
        var site = new ConflictSite.Site.Carrier(carrier);
        if (carrier instanceof no.sikt.graphitron.rewrite.model.ServiceMethodCall.Instance inst) {
            for (var entry : inst.ctorArgs()) recordFromContext(entry, site, byName);
        }
        for (var entry : carrier.methodArgs()) recordFromContext(entry, site, byName);
    }

    private static void recordFromContext(
            no.sikt.graphitron.rewrite.model.MappingEntry entry,
            ConflictSite.Site site,
            Map<String, List<ConflictSite>> byName) {
        if (entry instanceof no.sikt.graphitron.rewrite.model.MappingEntry.FromContext fc) {
            byName.computeIfAbsent(fc.contextKey(), k -> new ArrayList<>())
                .add(new ConflictSite(site, fc.javaType()));
        }
    }

    private static void collectFromInputFieldCondition(GraphitronField field,
            Map<String, List<ConflictSite>> byName) {
        switch (field) {
            case InputField.ColumnField f -> f.condition().ifPresent(ac -> collectFromMethodRef(ac.filter(), byName));
            case InputField.ColumnReferenceField f -> f.condition().ifPresent(ac -> collectFromMethodRef(ac.filter(), byName));
            case InputField.CompositeColumnField ignored -> { /* no condition slot */ }
            case InputField.CompositeColumnReferenceField ignored -> { /* no condition slot */ }
            case InputField.NestingField f -> f.condition().ifPresent(ac -> collectFromMethodRef(ac.filter(), byName));
            case InputField.UnboundField f -> f.condition().ifPresent(ac -> collectFromMethodRef(ac.filter(), byName));
            default -> { /* non-input fields handled by collectFromField */ }
        }
    }

    private static void collectFromMethodRef(MethodRef method,
            Map<String, List<ConflictSite>> byName) {
        for (var p : method.params()) {
            if (p instanceof MethodRef.Param.Typed typed && typed.source() instanceof ParamSource.Context) {
                byName.computeIfAbsent(typed.name(), k -> new ArrayList<>())
                    .add(ConflictSite.of(method, typed.javaType()));
            }
        }
    }
}
