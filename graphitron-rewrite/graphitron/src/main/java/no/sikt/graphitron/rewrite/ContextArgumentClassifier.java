package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.ConflictSite;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.LoadBearingClassifierCheck;
import no.sikt.graphitron.rewrite.model.MethodBackedField;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.ResolvedContextArg;
import no.sikt.graphitron.rewrite.model.SqlGeneratingField;
import no.sikt.graphitron.rewrite.model.WhereFilter;
import no.sikt.graphitron.rewrite.model.ConditionFilter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Cross-site {@code contextArgument} type-agreement classifier.
 *
 * <p>Walks every {@link MethodRef} reachable from a {@link GraphitronSchema} (the schema's
 * fields and their condition filters), collects every {@link MethodRef.Param.Typed} whose source
 * is {@link ParamSource.Context}, keys by parameter name, and requires every site to declare the
 * same structural {@link TypeName}. The classifier returns both:
 *
 * <ul>
 *   <li>{@link Classification#resolved} — alphabetically sorted map of contextArgument names
 *       that resolved unambiguously to a single {@link TypeName}. Consumed by the schema-driven
 *       factory emitter ({@code Graphitron.newExecutionInput(...)}) and the call-site emitter
 *       ({@code $T.class} literal at the {@code getContextArgument} call). Both emitters read
 *       {@link ResolvedContextArg#javaType} directly: that single source of truth is what
 *       prevents the factory's typed {@code put} and the call-site's typed {@code cast} from
 *       drifting.</li>
 *   <li>{@link Classification#conflicts} — the typed
 *       {@link Rejection.AuthorError.TypeConflict} rejections for every name with disagreeing
 *       sites. The validator drains these into {@link ValidationError}s via
 *       {@code GraphitronSchemaValidator.validateContextArgumentTypeAgreement}.</li>
 * </ul>
 */
@LoadBearingClassifierCheck(
    key = "context-argument.type-agreement",
    description = "Cross-site agreement on contextArgument Java types; consumed by the factory "
        + "emitter and the getContextArgument call-site emitter. A single TypeName per "
        + "contextArgument name is stored on ResolvedContextArg.javaType and both emitters read "
        + "that field directly; structural disagreement across sites surfaces as "
        + "Rejection.AuthorError.TypeConflict."
)
public final class ContextArgumentClassifier {

    private ContextArgumentClassifier() {}

    public record Classification(
        Map<String, ResolvedContextArg> resolved,
        List<Rejection> conflicts
    ) {
        public Classification {
            resolved = Map.copyOf(resolved);
            conflicts = List.copyOf(conflicts);
        }
    }

    public static Classification classify(GraphitronSchema schema) {
        // Working map: name -> per-site (MethodRef, TypeName) pair in encounter order.
        var byName = new LinkedHashMap<String, List<ConflictSite>>();
        for (var field : schema.fields().values()) {
            collectFromField(field, byName);
        }
        // Walk types so that input-field-level @condition filters reachable only through
        // GraphitronType.InputType / TableInputType also contribute their MethodRefs.
        // Today's projection step appends them to SqlGeneratingField.filters() so the field-walk
        // catches them, but visiting input fields directly via their condition() accessor is
        // shape-symmetric and resilient to any future drift in the projection.
        for (var field : schema.fields().values()) {
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
        if (field instanceof SqlGeneratingField sgf) {
            for (WhereFilter wf : sgf.filters()) {
                if (wf instanceof ConditionFilter cf) {
                    collectFromMethodRef(cf, byName);
                }
            }
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
            default -> { /* non-input fields handled by collectFromField */ }
        }
    }

    private static void collectFromMethodRef(MethodRef method,
            Map<String, List<ConflictSite>> byName) {
        for (var p : method.params()) {
            if (p instanceof MethodRef.Param.Typed typed && typed.source() instanceof ParamSource.Context) {
                byName.computeIfAbsent(typed.name(), k -> new ArrayList<>())
                    .add(new ConflictSite(method, typed.javaType()));
            }
        }
    }
}
