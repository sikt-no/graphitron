package no.sikt.graphitron.plan;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.command.ArgBinding;
import no.sikt.graphitron.command.AuthoredMethodRef;
import no.sikt.graphitron.command.ColumnTerm;
import no.sikt.graphitron.command.ConditionCommand;
import no.sikt.graphitron.command.FacetFragment;
import no.sikt.graphitron.command.MatchKind;
import no.sikt.graphitron.command.OuterLift;
import no.sikt.graphitron.command.Predicate;
import no.sikt.graphitron.command.PresenceGuard;
import no.sikt.graphitron.command.ReachPath;
import no.sikt.graphitron.command.UnitMethodRef;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.BodyParam;
import no.sikt.graphitron.rewrite.model.CallParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ConditionFilter;
import no.sikt.graphitron.rewrite.model.FacetSpec;
import no.sikt.graphitron.rewrite.model.FkTargetConditionFilter;
import no.sikt.graphitron.rewrite.model.GeneratedConditionFilter;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.OperationMember;
import no.sikt.graphitron.rewrite.model.OperationMembers;
import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.model.jooq.TableRef;
import no.sikt.graphitron.rewrite.model.WhereFilter;
import no.sikt.graphitron.rewrite.model.WireAddress;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Produces the condition command relation: one {@link ConditionCommand} row per condition
 * member row, the {@code (coordinate, table)} keys being one key by construction (the member
 * key was minted to mirror this relation's). Membership is the member's presence and the row
 * inputs are the member's payload: {@link OperationMember.Condition.OnReturnTable} carries the
 * coordinate's own-table filter surface, {@link OperationMember.Condition.OnParticipant} one
 * table-bound participant's, and the glue naming is a total switch over the two arms, so the
 * per-participant expansion that used to live here as identity arms is now the member
 * production's fact. Override suppression stays upstream in
 * {@code FieldBuilder.projectFilters}, which already expresses a suppressed generated filter
 * as absence (an absent member).
 *
 * <p>One identity arm survives where the walk structure genuinely lives elsewhere:
 * {@link ChildField.NestingField} (its children have no {@code fieldsOf} entry, so the
 * producer recurses into the carried {@code nestedFields()}, reading the nested instances'
 * leaf-projected members per the member relation's domain boundary). A nesting type reused
 * from several {@code @table} parents yields the same nested coordinate once per reuse site;
 * the rows are equal by construction (filters resolve against the nested field's own return
 * table) and the producer deduplicates by key, failing hard if two reuse sites ever disagree
 * (the validator's nested-shape comparison enforces the same fact).
 *
 * <p>Every row renders glue and every consumer calls it; the migration dial that once restricted
 * rendering to root rows closed with call-site convergence. One producer-side backstop mirrors a
 * validator rejection (an accepted classification whose emit does not exist must fail loudly
 * before production, never mint a row nobody can render or call): any filter on a single-table
 * interface child coordinate, whose fetcher folds no filters at all.
 */
public final class ConditionCommands {

    private ConditionCommands() {}

    /** Locals every glue body binds before the producer names anything; see {@link ConditionCommand}. */
    private static final Set<String> RESERVED_LOCALS = Set.of("table", "args", "condition", "env");

    public static ConditionRelation produce(GraphitronSchema schema, String outputPackage) {
        var units = new GeneratedUnits(outputPackage);
        var rows = new LinkedHashMap<String, ConditionCommand>();
        for (var type : schema.types().values()) {
            for (var field : schema.fieldsOf(type.name())) {
                collect(schema, units, field, rows, false);
            }
        }
        return new ConditionRelation(List.copyOf(rows.values()));
    }

    private static void collect(GraphitronSchema schema, GeneratedUnits units,
            GraphitronField field, LinkedHashMap<String, ConditionCommand> rows, boolean nested) {
        // Nested coordinates have no fieldsOf entry; the walk reaches them through the
        // nesting field's own children.
        if (field instanceof ChildField.NestingField nf) {
            for (var nestedField : nf.nestedFields()) {
                collect(schema, units, nestedField, rows, true);
            }
        }
        if (!(field instanceof OutputField out)) {
            return;
        }
        // Flat coordinates read the minted member view; nested instances keep the
        // leaf-projected derivation, the member relation's domain boundary.
        var members = nested
            ? OperationMembers.membersOf(out)
            : schema.operationMembersOf(out.parentTypeName(), out.name());
        var conditions = members.stream()
            .filter(m -> m instanceof OperationMember.Condition)
            .map(m -> (OperationMember.Condition) m)
            .toList();
        if (conditions.isEmpty()) {
            return;
        }
        if (field instanceof ChildField.TableInterfaceField) {
            throw new IllegalStateException(
                "single-table interface child coordinate '" + field.qualifiedName() + "' carries "
                + "filters, which its fetcher does not fold; the validator must reject this shape "
                + "before production");
        }
        for (var condition : conditions) {
            switch (condition) {
                case OperationMember.Condition.OnReturnTable own ->
                    addRow(rows, row(out.parentTypeName(), out.name(), own.table(), own.filters(),
                        units.conditionMethod(out.parentTypeName(), out.name()),
                        facetsFor(schema, out.parentTypeName(), out.name()), units));
                case OperationMember.Condition.OnParticipant participant ->
                    addRow(rows, row(out.parentTypeName(), out.name(), participant.table(),
                        participant.filters(),
                        units.participantConditionMethod(out.parentTypeName(), out.name(),
                            participant.participant().typeName()),
                        List.of(), units));
            }
        }
    }

    /**
     * Registers a row under its {@code (coordinate, resolvedTable)} key. A key hit from a second
     * nesting reuse site must carry an identical row (same predicates, locals, lifts); anything
     * else means two reuse sites classified the same nested coordinate differently, and one glue
     * method cannot serve both, so production fails hard rather than silently keeping one.
     */
    private static void addRow(LinkedHashMap<String, ConditionCommand> rows, ConditionCommand row) {
        var key = row.coordinate() + "@" + row.table().tableName();
        var existing = rows.putIfAbsent(key, row);
        if (existing != null && !existing.equals(row)) {
            throw new IllegalStateException(
                "condition coordinate '" + key + "' was produced twice with diverging rows; a "
                + "nesting type shared across parents must classify its nested filters "
                + "identically at every reuse site (the validator's nested-shape comparison "
                + "enforces this before production)");
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
            // Same-table: nothing of ours sits between the method's return value and the WHERE
            // clause, so the author's null-mapping convention is the whole story and the call
            // always fires.
            case ConditionFilter cf ->
                new Predicate.Authored(authoredRef(cf), bindingsFor(cf.callParams(), localNames),
                    ReachPath.none(), PresenceGuard.always());
            // The FK-target @nodeId + @condition reach keeps its FK-only guarantee upstream, at
            // the validator's deferral of a non-FK path for that carrier, not through this type.
            // Its correlated EXISTS is ours, so it applies under the owning field's presence.
            case FkTargetConditionFilter fk ->
                new Predicate.Authored(authoredRef(fk.delegate()), bindingsFor(fk.callParams(), localNames),
                    ReachPath.narrow(fk.joinPath(),
                        fieldName + "'s FK-target @condition '" + fk.methodName() + "'"),
                    presenceOf(fk.field()));
        };
    }

    /**
     * Narrows the owning field's model-side wire address onto the row's presence guard, the same
     * produce-time narrowing {@link ReachPath#narrow} performs for the join path: the row carries
     * the address as three plain components, so the renderer spells a traversal without reading
     * the classified model.
     */
    private static PresenceGuard presenceOf(WireAddress field) {
        return new PresenceGuard.FieldPresent(field.outerArgName(), field.path(), field.list());
    }

    /**
     * The two components an authored call site emits, taken off the model's reflected reference
     * here so the row carries the address and not the signature. The class name rides as the
     * author wrote it, which is what {@code ClassName.bestGuess} resolves at the call site.
     */
    private static AuthoredMethodRef authoredRef(MethodRef method) {
        return new AuthoredMethodRef(method.className(), method.methodName());
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
                "generated condition filter on '" + fieldName + "' carries " + bodyParams.size()
                + " body params but " + callParams.size() + " call params; the two views must pair 1:1");
        }
        var terms = new ArrayList<ColumnTerm>(bodyParams.size());
        for (int i = 0; i < bodyParams.size(); i++) {
            var bp = bodyParams.get(i);
            var cp = callParams.get(i);
            if (!bp.name().equals(cp.name())) {
                throw new IllegalStateException(
                    "generated condition filter on '" + fieldName + "' pairs body param '" + bp.name()
                    + "' with call param '" + cp.name() + "'; the two views must share names positionally");
            }
            terms.add(termOf(bp, new ArgBinding(cp, localNames.of(cp)), fieldName));
        }
        return terms;
    }

    private static ColumnTerm termOf(BodyParam bp, ArgBinding binding, String fieldName) {
        return switch (bp) {
            case BodyParam.Eq eq ->
                new ColumnTerm(List.of(eq.column()), MatchKind.EQUALITY, eq.nonNull(), binding, ReachPath.none());
            case BodyParam.In in ->
                new ColumnTerm(List.of(in.column()), MatchKind.MEMBERSHIP, in.nonNull(), binding, ReachPath.none());
            case BodyParam.RowEq req ->
                new ColumnTerm(req.columns(), MatchKind.EQUALITY, req.nonNull(), binding, ReachPath.none());
            case BodyParam.RowIn rin ->
                new ColumnTerm(rin.columns(), MatchKind.MEMBERSHIP, rin.nonNull(), binding, ReachPath.none());
            case BodyParam.RemoteColumnPredicate r -> {
                var inner = termOf(r.inner(), binding, fieldName);
                yield new ColumnTerm(inner.columns(), inner.match(), inner.nonNull(), binding,
                    ReachPath.narrow(r.joinPath(),
                        fieldName + "'s reference filter '" + r.name() + "'"));
            }
        };
    }

    private static List<ArgBinding> bindingsFor(List<CallParam> callParams, LocalNames localNames) {
        return callParams.stream().map(cp -> new ArgBinding(cp, localNames.of(cp))).toList();
    }

    // ------------------------------------------------------------------------------------------
    // Facet fragments
    // ------------------------------------------------------------------------------------------

    /**
     * The facet specs of the coordinate's synthesised connection carrier, present exactly when
     * the coordinate is a faceted {@code @asConnection}. Resolved by coordinate through the
     * connection-synthesis relation ({@code GraphitronSchema.connectionSynthesis()}), whose typed
     * accessor reads the registry's reconciled connection entry, so a carrier using the
     * deprecated {@code connectionName:} override resolves identically to a derived-name one.
     */
    static List<FacetSpec> facetsFor(GraphitronSchema schema, String parentTypeName, String fieldName) {
        return schema.connectionSynthesis().facetsAt(parentTypeName, fieldName);
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
