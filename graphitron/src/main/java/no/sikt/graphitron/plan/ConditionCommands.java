package no.sikt.graphitron.plan;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.command.ArgBinding;
import no.sikt.graphitron.command.ColumnTerm;
import no.sikt.graphitron.command.ConditionCommand;
import no.sikt.graphitron.command.FacetFragment;
import no.sikt.graphitron.command.FkHop;
import no.sikt.graphitron.command.MatchKind;
import no.sikt.graphitron.command.OuterLift;
import no.sikt.graphitron.command.Predicate;
import no.sikt.graphitron.command.UnitMethodRef;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.BodyParam;
import no.sikt.graphitron.rewrite.model.CallParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ConditionFilter;
import no.sikt.graphitron.rewrite.model.ConnectionNaming;
import no.sikt.graphitron.rewrite.model.FacetSpec;
import no.sikt.graphitron.rewrite.model.FkTargetConditionFilter;
import no.sikt.graphitron.rewrite.model.GeneratedConditionFilter;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.LookupField;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.WhereFilter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Produces the condition command relation: one {@link ConditionCommand} row per covered
 * {@code (coordinate, resolvedTable)} key with a nonempty live filter set. Production reads the
 * resolved filter lists exactly where the generators read them today ({@code filters()} /
 * {@code participantFilters()}); re-sourcing onto raw relations is later work, and override
 * suppression stays upstream in {@code FieldBuilder.projectFilters}, which already expresses a
 * suppressed generated filter as absence.
 *
 * <p>The relation is total: root, participant-expanded, lookup (non-key filters only; lookup
 * keys ride the VALUES join and are not predicates) and child coordinates all get rows. The
 * committed half ({@link ConditionRelation#committedRows()}) is restricted to the coordinates
 * whose consumers already call glue, the {@link #rendersIntoConditionsClass} predicate; the
 * validator reads the same predicate for the env-bound rejection, so the rejection's scope and
 * the renderer's scope cannot drift apart.
 *
 * <p>Fields nested inside a {@code ChildField.NestingField} have no walkable coordinate yet, so
 * they produce no row; a nested <em>generated</em> filter is rejected at validate time (the
 * {@code GraphitronSchemaValidator} nested-filter rule) rather than silently skipped.
 */
public final class ConditionCommands {

    private ConditionCommands() {}

    /** Locals every glue body binds before the producer names anything; see {@link ConditionCommand}. */
    private static final Set<String> RESERVED_LOCALS = Set.of("table", "args", "condition");

    /**
     * True for the coordinates this run renders glue for: the root rows whose fetchers already
     * call a {@code <Root>Conditions} method. This is the migration dial's predicate, read by
     * both {@link #produce} (to commit rows) and the validator (to scope the env-bound
     * rejection); the call-site convergence slice widens it as consumers converge.
     */
    public static boolean rendersIntoConditionsClass(GraphitronField field) {
        return field instanceof QueryField.QueryTableField
            || field instanceof QueryField.QueryTableInterfaceField;
    }

    public static ConditionRelation produce(GraphitronSchema schema, String outputPackage) {
        var units = new GeneratedUnits(outputPackage);
        var rows = new ArrayList<ConditionCommand>();
        var committedRows = new ArrayList<ConditionCommand>();
        for (var type : schema.types().values()) {
            for (var field : schema.fieldsOf(type.name())) {
                switch (field) {
                    case QueryField.QueryTableField qtf when !qtf.filters().isEmpty() -> {
                        var row = row(qtf.parentTypeName(), qtf.name(), qtf.returnType().table(), qtf.filters(),
                            units.conditionMethod(qtf.parentTypeName(), qtf.name()),
                            facetsFor(schema, qtf), units);
                        rows.add(row);
                        if (rendersIntoConditionsClass(qtf)) {
                            committedRows.add(row);
                        }
                    }
                    case QueryField.QueryTableInterfaceField qtif when !qtif.filters().isEmpty() -> {
                        var row = row(qtif.parentTypeName(), qtif.name(), qtif.returnType().table(), qtif.filters(),
                            units.conditionMethod(qtif.parentTypeName(), qtif.name()), List.of(), units);
                        rows.add(row);
                        if (rendersIntoConditionsClass(qtif)) {
                            committedRows.add(row);
                        }
                    }
                    case QueryField.QueryLookupTableField qlf when !qlf.filters().isEmpty() -> {
                        requireNoGeneratedFilterOnLookup(qlf, qlf.filters());
                        rows.add(row(qlf.parentTypeName(), qlf.name(), qlf.returnType().table(), qlf.filters(),
                            units.conditionMethod(qlf.parentTypeName(), qlf.name()), List.of(), units));
                    }
                    case QueryField.QueryInterfaceField qif ->
                        addParticipantRows(rows, units, qif.parentTypeName(), qif.name(), qif.participantFilters());
                    case QueryField.QueryUnionField quf ->
                        addParticipantRows(rows, units, quf.parentTypeName(), quf.name(), quf.participantFilters());
                    case ChildField.TableTargetField ttf when !ttf.filters().isEmpty() -> {
                        if (ttf instanceof LookupField) {
                            requireNoGeneratedFilterOnLookup(ttf, ttf.filters());
                        }
                        rows.add(row(ttf.parentTypeName(), ttf.name(), ttf.returnType().table(), ttf.filters(),
                            units.conditionMethod(ttf.parentTypeName(), ttf.name()), List.of(), units));
                    }
                    default -> { }
                }
            }
        }
        return new ConditionRelation(rows, committedRows);
    }

    private static void addParticipantRows(List<ConditionCommand> rows, GeneratedUnits units,
            String parentTypeName, String fieldName,
            List<no.sikt.graphitron.rewrite.model.ParticipantFilters> participantFilters) {
        for (var pf : participantFilters) {
            if (pf.filters().isEmpty()) {
                continue;
            }
            rows.add(row(parentTypeName, fieldName, pf.participant().table(), pf.filters(),
                units.participantConditionMethod(parentTypeName, fieldName, pf.participant().typeName()),
                List.of(), units));
        }
    }

    /**
     * The validator's mirror of {@code TypeConditionsGenerator}'s lookup skip: lookup keys ride
     * the VALUES join and the entity layer never emits a generated method for a lookup
     * coordinate, so a generated column filter here would be a call to a method that does not
     * exist. The {@code GraphitronSchemaValidator} rejects the shape before production; this
     * throw is the producer-side backstop.
     */
    private static void requireNoGeneratedFilterOnLookup(GraphitronField field, List<WhereFilter> filters) {
        if (filters.stream().anyMatch(f -> f instanceof GeneratedConditionFilter)) {
            throw new IllegalStateException(
                "lookup coordinate '" + field.qualifiedName() + "' carries a generated column filter,"
                + " which no emitter implements; the validator must reject this before production");
        }
    }

    // ------------------------------------------------------------------------------------------
    // Row construction
    // ------------------------------------------------------------------------------------------

    private static ConditionCommand row(String parentTypeName, String fieldName, TableRef table,
            List<WhereFilter> filters, UnitMethodRef glue, List<FacetSpec> facetSpecs, GeneratedUnits units) {
        var localNames = nameLocals(filters);
        var predicates = new ArrayList<Predicate>();
        for (var filter : filters) {
            predicates.add(predicateOf(filter, localNames, fieldName));
        }
        var fragments = facetSpecs.isEmpty()
            ? List.<FacetFragment>of()
            : fragments(parentTypeName, fieldName, predicates, facetSpecs, units);
        return new ConditionCommand(
            FieldCoordinates.coordinates(parentTypeName, fieldName),
            table, predicates, glue, liftsOf(bindingsOf(predicates)), fragments);
    }

    private static Predicate predicateOf(WhereFilter filter, LocalNames localNames, String fieldName) {
        return switch (filter) {
            case GeneratedConditionFilter gcf -> new Predicate.Generated(termsOf(gcf, localNames, fieldName));
            case ConditionFilter cf ->
                new Predicate.Authored(cf, bindingsFor(cf.callParams(), localNames), List.of());
            case FkTargetConditionFilter fk ->
                new Predicate.Authored(fk.delegate(), bindingsFor(fk.callParams(), localNames),
                    narrowPath(fk.joinPath(), fieldName + "'s FK-target @condition '" + fk.methodName() + "'"));
        };
    }

    /**
     * One {@link ColumnTerm} per body param, paired with its call-site view positionally: the
     * model guarantees a {@code BodyParam} and its {@code CallParam} share name and extraction
     * (see {@code BodyParam}'s contract), and the pairing check makes a drift a production
     * failure. A remote predicate contributes its inner comparison with the hop path narrowed
     * onto the term's reach slot.
     */
    private static List<ColumnTerm> termsOf(GeneratedConditionFilter gcf, LocalNames localNames, String fieldName) {
        var bodyParams = gcf.bodyParams();
        var callParams = gcf.callParams();
        if (bodyParams.size() != callParams.size()) {
            throw new IllegalStateException(
                "generated condition filter '" + gcf.methodName() + "' carries " + bodyParams.size()
                + " body params but " + callParams.size() + " call params; the two views must pair 1:1");
        }
        var terms = new ArrayList<ColumnTerm>(bodyParams.size());
        for (int i = 0; i < bodyParams.size(); i++) {
            var bp = bodyParams.get(i);
            var cp = callParams.get(i);
            if (!bp.name().equals(cp.name())) {
                throw new IllegalStateException(
                    "generated condition filter '" + gcf.methodName() + "' pairs body param '" + bp.name()
                    + "' with call param '" + cp.name() + "'; the two views must share names positionally");
            }
            terms.add(termOf(bp, new ArgBinding(cp, localNames.of(cp)), fieldName));
        }
        return terms;
    }

    private static ColumnTerm termOf(BodyParam bp, ArgBinding binding, String fieldName) {
        return switch (bp) {
            case BodyParam.Eq eq ->
                new ColumnTerm(List.of(eq.column()), MatchKind.EQUALITY, eq.nonNull(), binding, List.of());
            case BodyParam.In in ->
                new ColumnTerm(List.of(in.column()), MatchKind.MEMBERSHIP, in.nonNull(), binding, List.of());
            case BodyParam.RowEq req ->
                new ColumnTerm(req.columns(), MatchKind.EQUALITY, req.nonNull(), binding, List.of());
            case BodyParam.RowIn rin ->
                new ColumnTerm(rin.columns(), MatchKind.MEMBERSHIP, rin.nonNull(), binding, List.of());
            case BodyParam.RemoteColumnPredicate r -> {
                var inner = termOf(r.inner(), binding, fieldName);
                yield new ColumnTerm(inner.columns(), inner.match(), inner.nonNull(), binding,
                    narrowPath(r.joinPath(), fieldName + "'s reference filter '" + r.name() + "'"));
            }
        };
    }

    private static List<FkHop> narrowPath(List<JoinStep> path, String context) {
        var hops = new ArrayList<FkHop>(path.size());
        for (var step : path) {
            hops.add(FkHop.narrow(step, context));
        }
        return hops;
    }

    private static List<ArgBinding> bindingsFor(List<CallParam> callParams, LocalNames localNames) {
        return callParams.stream().map(cp -> new ArgBinding(cp, localNames.of(cp))).toList();
    }

    // ------------------------------------------------------------------------------------------
    // Facet fragments
    // ------------------------------------------------------------------------------------------

    /**
     * The facet specs of the coordinate's synthesised connection carrier, present exactly when
     * the field is a faceted {@code @asConnection}. The carrier resolves through the default
     * connection name; faceted carriers using the deprecated {@code connectionName:} override are
     * rejected at classify time, so the derived name always hits where facets exist.
     */
    private static List<FacetSpec> facetsFor(GraphitronSchema schema, QueryField.QueryTableField qtf) {
        var entry = schema.types().get(
            ConnectionNaming.defaultConnectionName(qtf.parentTypeName(), qtf.name()));
        return entry instanceof GraphitronType.ConnectionType ct ? ct.facets() : List.of();
    }

    /**
     * Computes the masked predicate list of every fragment outright, so the fragment set is data
     * and no mask vocabulary exists. Masking is decided per generated term: a facet owns a term
     * when the term's binding is the facet's own ({@link #isFacetBinding}). The base fragment
     * keeps every term no facet owns plus every authored predicate <em>unmasked</em>; a per-facet
     * fragment keeps exactly its own term. Static omission of a masked term renders the same SQL
     * as the runtime null-guard because facet bindings are guaranteed nullable
     * ({@code GraphitronSchemaBuilder}'s facet-misuse rejection); authored predicates are never
     * masked, a deliberate carry-over of the emitter behaviour this data replaces.
     */
    private static List<FacetFragment> fragments(String parentTypeName, String fieldName,
            List<Predicate> predicates, List<FacetSpec> facetSpecs, GeneratedUnits units) {
        var fragments = new ArrayList<FacetFragment>(facetSpecs.size() + 1);
        var basePredicates = new ArrayList<Predicate>();
        for (var predicate : predicates) {
            switch (predicate) {
                case Predicate.Generated generated -> {
                    var kept = generated.terms().stream()
                        .filter(t -> facetSpecs.stream().noneMatch(f -> isFacetBinding(t.binding().param(), f)))
                        .toList();
                    if (!kept.isEmpty()) {
                        basePredicates.add(new Predicate.Generated(kept));
                    }
                }
                case Predicate.Authored authored -> basePredicates.add(authored);
            }
        }
        fragments.add(new FacetFragment(
            units.facetBaseConditionMethod(parentTypeName, fieldName),
            basePredicates, liftsOf(bindingsOf(basePredicates))));
        for (var facet : facetSpecs) {
            var own = predicates.stream()
                .filter(p -> p instanceof Predicate.Generated)
                .flatMap(p -> ((Predicate.Generated) p).terms().stream())
                .filter(t -> isFacetBinding(t.binding().param(), facet))
                .toList();
            fragments.add(new FacetFragment(
                units.facetConditionMethod(parentTypeName, fieldName, facet.inputFieldName()),
                own.isEmpty() ? List.of() : List.of(new Predicate.Generated(own)),
                liftsOf(own.stream().map(ColumnTerm::binding).toList())));
        }
        return fragments;
    }

    /**
     * True when {@code param} is {@code facet}'s own binding: a nested extraction riding the
     * facet's carrier argument whose traversal path is exactly the facet's input field. Matching
     * on the full extraction identity keeps a same-named top-level argument and a same-named
     * field on a sibling input argument out of the facet's suppression set.
     */
    private static boolean isFacetBinding(CallParam param, FacetSpec facet) {
        return param.extraction() instanceof CallSiteExtraction.NestedInputField nif
            && nif.outerArgName().equals(facet.filterArgName())
            && nif.path().equals(List.of(facet.inputFieldName()));
    }

    // ------------------------------------------------------------------------------------------
    // Producer-named locals and lifts
    // ------------------------------------------------------------------------------------------

    private static List<ArgBinding> bindingsOf(List<Predicate> predicates) {
        var bindings = new ArrayList<ArgBinding>();
        for (var predicate : predicates) {
            switch (predicate) {
                case Predicate.Generated generated -> generated.terms().forEach(t -> bindings.add(t.binding()));
                case Predicate.Authored authored -> bindings.addAll(authored.bindings());
            }
        }
        return bindings;
    }

    /**
     * One shared-outer lift per outer argument two or more of the given bindings traverse, in
     * first-occurrence order; the local is {@code <camelCaseOuter>Map}, the naming the retired
     * per-generator lift used. A fragment's lift set is computed over its own retained bindings,
     * so it is always a subset of the row's.
     */
    private static List<OuterLift> liftsOf(List<ArgBinding> bindings) {
        var counts = new LinkedHashMap<String, Integer>();
        for (var binding : bindings) {
            if (binding.param().extraction() instanceof CallSiteExtraction.NestedInputField nif) {
                counts.merge(nif.outerArgName(), 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
            .filter(e -> e.getValue() >= 2)
            .map(e -> new OuterLift(e.getKey(), toCamelCase(e.getKey()) + "Map"))
            .toList();
    }

    /**
     * Names one glue body local per argument value, qualified and collision-free: the default is
     * the camel-cased argument name; a collision (same-named fields across sibling arguments)
     * qualifies by the nested extraction's outer argument, and anything still colliding takes a
     * positional suffix. Reserved names and the lift locals are avoided the same way. This is
     * where the old generated parameter list's collision (uncompilable output on same-named
     * sibling filter fields) dissolves: there is no fixed signature to collide in, only locals
     * the producer names.
     */
    private static LocalNames nameLocals(List<WhereFilter> filters) {
        var params = new ArrayList<CallParam>();
        for (var filter : filters) {
            params.addAll(filter.callParams());
        }
        var taken = new HashSet<>(RESERVED_LOCALS);
        // Lift locals are derived from outer-arg names before binding locals are assigned, so a
        // binding local can never shadow a lift local it reads through.
        for (var filter : filters) {
            for (var param : filter.callParams()) {
                if (param.extraction() instanceof CallSiteExtraction.NestedInputField nif) {
                    taken.add(toCamelCase(nif.outerArgName()) + "Map");
                }
            }
        }
        var names = new LinkedHashMap<CallParam, String>();
        for (var param : params) {
            if (names.containsKey(param)) {
                continue;
            }
            String base = toCamelCase(param.name());
            String candidate = base;
            if (taken.contains(candidate) && param.extraction() instanceof CallSiteExtraction.NestedInputField nif) {
                candidate = toCamelCase(nif.outerArgName()) + Character.toUpperCase(base.charAt(0)) + base.substring(1);
            }
            int suffix = 2;
            while (taken.contains(candidate)) {
                candidate = base + suffix++;
            }
            taken.add(candidate);
            names.put(param, candidate);
        }
        return new LocalNames(names);
    }

    private record LocalNames(java.util.Map<CallParam, String> names) {
        String of(CallParam param) {
            var name = names.get(param);
            if (name == null) {
                throw new IllegalStateException("no local was named for call param '" + param.name() + "'");
            }
            return name;
        }
    }

    /** Snake-case to lowerCamelCase for body locals, mirroring the emitters' argument naming. */
    private static String toCamelCase(String snakeName) {
        var parts = snakeName.split("_");
        var sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            sb.append(Character.toUpperCase(parts[i].charAt(0)));
            sb.append(parts[i], 1, parts[i].length());
        }
        return sb.toString();
    }
}
