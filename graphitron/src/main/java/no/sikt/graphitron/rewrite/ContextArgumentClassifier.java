package no.sikt.graphitron.rewrite;

import com.palantir.javapoet.TypeName;
import no.sikt.graphitron.emit.FillQuery;
import no.sikt.graphitron.model.diagnostics.ConflictSite;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.MethodBackedField;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.rewrite.model.ResolvedContextArg;
import no.sikt.graphitron.rewrite.model.SqlGeneratingField;
import no.sikt.graphitron.rewrite.model.WhereFilter;
import no.sikt.graphitron.rewrite.model.ConditionFilter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import no.sikt.graphitron.model.diagnostics.ValidationError;

/**
 * Cross-site {@code contextArgument} type-agreement classifier.
 *
 * <p>Walks every {@link MethodRef} reachable from a classified field set, collects every
 * {@link MethodRef.Param.Typed} whose source is {@link ParamSource.Context}, keys by parameter
 * name, and requires every site to declare the same structural {@link TypeName}:
 *
 * <ul>
 *   <li>{@link Classification#resolved}: alphabetically sorted map of names that resolved to a
 *       single {@link TypeName}. The factory emitter ({@code Graphitron.newExecutionInput(...)})
 *       and the call-site cast emitter both read {@link ResolvedContextArg#javaType}; that single
 *       source of truth keeps the factory's typed {@code put} and the call-site's typed cast from
 *       drifting.</li>
 *   <li>{@link Classification#conflicts}: a {@link Rejection.AuthorError.TypeConflict} per name
 *       with disagreeing sites, drained into {@link ValidationError}s by
 *       {@code GraphitronSchemaValidator.validateContextArgumentTypeAgreement}.</li>
 * </ul>
 *
 * <p>A {@code @service} call's keys are not walked: the store states them, each with the type of
 * the parameter it fills ({@link FillQuery#contextKeys}), and they join the walked names after the
 * fold.
 *
 * <p>Invoked once by {@link GraphitronSchema}'s constructor; the cached {@link Classification}
 * hangs off {@link GraphitronSchema#contextArguments()} for both downstream consumers.
 */
public final class ContextArgumentClassifier {

    private ContextArgumentClassifier() {}

    public record Classification(
        Map<String, ResolvedContextArg> resolved,
        List<Rejection> conflicts
    ) {
        public Classification {
            // LinkedHashMap (not Map.copyOf) so the classifier's alphabetical iteration order
            // survives the defensive copy; Map.copyOf's iteration order is unspecified.
            resolved = Collections.unmodifiableMap(new LinkedHashMap<>(resolved));
            conflicts = List.copyOf(conflicts);
        }
    }

    public static Classification classify(GraphitronSchema schema) {
        return classify(schema.fields().values());
    }

    /**
     * Same as {@link #classify(GraphitronSchema)} but takes the classified fields directly, so
     * {@link GraphitronSchema}'s constructor can classify before the schema record is assembled.
     */
    public static Classification classify(Collection<GraphitronField> fields) {
        return classify(fields, List.of());
    }

    /**
     * {@link #classify(Collection)}, adding the context keys the store states the service calls read.
     * A name the walk resolved takes the service sites of its own type; a name it found in conflict
     * stays one; a name only services read resolves to the type of its first key in
     * {@code serviceKeys}' order.
     */
    public static Classification classify(Collection<GraphitronField> fields,
            List<FillQuery.ContextKey> serviceKeys) {
        var byName = new LinkedHashMap<String, List<Declared>>();
        for (var field : fields) {
            collectFromField(field, byName);
        }
        // Input-field-level @condition filters also reach the field walk via
        // SqlGeneratingField.filters() (the projection appends them there); visiting input
        // fields' condition() directly keeps this walk correct independently of that projection.
        for (var field : fields) {
            collectFromInputFieldCondition(field, byName);
        }
        // Stable alphabetical output for deterministic factory parameter ordering.
        var resolved = new TreeMap<String, ResolvedContextArg>();
        var conflicts = new ArrayList<Rejection>();
        var conflicted = new HashSet<String>();
        for (var entry : byName.entrySet()) {
            String name = entry.getKey();
            List<Declared> sites = entry.getValue();
            var distinct = sites.stream()
                .map(Declared::javaType)
                .distinct()
                .toList();
            if (distinct.size() == 1) {
                var methodSites = sites.stream().map(Declared::site).toList();
                resolved.put(name, new ResolvedContextArg(name, distinct.get(0), methodSites));
            } else {
                conflicted.add(name);
                conflicts.add(Rejection.contextArgumentTypeConflict(name,
                    sites.stream().map(Declared::asConflictSite).toList()));
            }
        }
        var services = new LinkedHashMap<String, List<FillQuery.ContextKey>>();
        for (var key : serviceKeys) {
            services.computeIfAbsent(key.key(), k -> new ArrayList<>()).add(key);
        }
        for (var entry : services.entrySet()) {
            String name = entry.getKey();
            if (conflicted.contains(name)) {
                continue;
            }
            var walked = resolved.get(name);
            // The store refuses one key read as disagreeing types, so this pick only keeps the fold total.
            TypeName javaType = walked != null ? walked.javaType() : entry.getValue().get(0).type();
            var sites = new ArrayList<ResolvedContextArg.Site>(walked != null ? walked.sites() : List.of());
            entry.getValue().stream()
                .filter(key -> key.type().equals(javaType))
                .forEach(key -> sites.add(new ResolvedContextArg.Site.Service(key.className(), key.methodName())));
            resolved.put(name, new ResolvedContextArg(name, javaType, sites));
        }
        return new Classification(resolved, conflicts);
    }

    private static void collectFromField(GraphitronField field,
            Map<String, List<Declared>> byName) {
        if (field instanceof MethodBackedField mbf) {
            collectFromMethodRef(mbf.method(), byName);
        }
        if (field instanceof SqlGeneratingField sgf) {
            for (WhereFilter wf : sgf.filters()) {
                if (wf instanceof ConditionFilter cf) {
                    collectFromMethodRef(cf, byName);
                }
            }
        }
    }

    private static void collectFromInputFieldCondition(GraphitronField field,
            Map<String, List<Declared>> byName) {
        switch (field) {
            case InputField.ColumnBackedField f -> f.condition().ifPresent(ac -> collectFromMethodRef(ac.filter(), byName));
            case InputField.ColumnBackedReferenceField f -> f.condition().ifPresent(ac -> collectFromMethodRef(ac.filter(), byName));
            case InputField.NestingField f -> f.condition().ifPresent(ac -> collectFromMethodRef(ac.filter(), byName));
            case InputField.UnboundField f -> f.condition().ifPresent(ac -> collectFromMethodRef(ac.filter(), byName));
            case InputField.ConditionOwnedField f -> collectFromMethodRef(f.condition().filter(), byName);
            default -> { /* non-input fields handled by collectFromField */ }
        }
    }

    private static void collectFromMethodRef(MethodRef method,
            Map<String, List<Declared>> byName) {
        var site = new ResolvedContextArg.Site.Method(method);
        for (var p : method.params()) {
            if (p instanceof MethodRef.Param.Typed typed && typed.source() instanceof ParamSource.Context) {
                byName.computeIfAbsent(typed.name(), k -> new ArrayList<>())
                    .add(new Declared(site, typed.javaType()));
            }
        }
    }

    /**
     * One site's declaration during the fold: the coordinate and the structural type it declared.
     * The fold needs the {@link TypeName} to decide agreement and to hand the agreed type to
     * {@link ResolvedContextArg}, so the classifier holds the typed form and spells it only on the
     * losing path, where {@link ConflictSite} carries the coordinate and the spelling into a
     * rejection the store holds.
     */
    private record Declared(ResolvedContextArg.Site site, TypeName javaType) {
        ConflictSite asConflictSite() {
            return new ConflictSite(site.className(), site.methodName(), javaType.toString());
        }
    }
}
