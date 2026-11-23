package no.sikt.graphitron.plan;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.command.ColumnTerm;
import no.sikt.graphitron.command.ConditionCommand;
import no.sikt.graphitron.command.FacetFragment;
import no.sikt.graphitron.command.ReachPath;
import no.sikt.graphitron.command.UnitMethodRef;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.BodyParam;
import no.sikt.graphitron.rewrite.model.CallParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.FacetSpec;
import no.sikt.graphitron.rewrite.model.GeneratedConditionFilter;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.OperationMember;
import no.sikt.graphitron.rewrite.model.OperationMembers;
import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.model.jooq.TableRef;
import no.sikt.graphitron.rewrite.model.WhereFilter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

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
     * nesting reuse site must carry an identical row; anything
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

    /**
     * The row's three signature facts are read off every argument value its filters consume, the
     * {@link CallParam}s of generated and authored filters alike: one consumed value that reads
     * the request context, the session or a {@code @nodeId} decode forks the whole row.
     */
    private static ConditionCommand row(String parentTypeName, String fieldName, TableRef table,
            List<WhereFilter> filters, UnitMethodRef glue, List<FacetSpec> facetSpecs, GeneratedUnits units) {
        var fragments = facetSpecs.isEmpty()
            ? List.<FacetFragment>of()
            : fragments(parentTypeName, fieldName, filters, facetSpecs, units);
        return new ConditionCommand(
            FieldCoordinates.coordinates(parentTypeName, fieldName),
            table, glue, fragments,
            WhereFilter.anyReadRequestContext(filters),
            WhereFilter.anyReadSession(filters),
            filters.stream().flatMap(f -> f.callParams().stream()).anyMatch(CallParam::decodesNodeId));
    }

    /**
     * The {@link ColumnTerm} a body param compares, the columns and reach a facet groups on. A
     * remote predicate contributes its inner comparison's columns with the hop path narrowed onto
     * the term's reach.
     */
    private static ColumnTerm termOf(BodyParam bp, String fieldName) {
        return switch (bp) {
            case BodyParam.Eq eq -> new ColumnTerm(List.of(eq.column()), ReachPath.none());
            case BodyParam.In in -> new ColumnTerm(List.of(in.column()), ReachPath.none());
            case BodyParam.RowEq req -> new ColumnTerm(req.columns(), ReachPath.none());
            case BodyParam.RowIn rin -> new ColumnTerm(rin.columns(), ReachPath.none());
            case BodyParam.PartialRowIn prin -> new ColumnTerm(prin.columns(), ReachPath.none());
            case BodyParam.RemoteColumnPredicate r -> new ColumnTerm(termOf(r.inner(), fieldName).columns(),
                ReachPath.narrow(r.joinPath(), fieldName + "'s reference filter '" + r.name() + "'"));
        };
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
     * The base fragment and one own fragment per facet. A per-facet fragment carries exactly its
     * own generated term, keyed by the facet's input field name so the launcher planner reads the
     * term's reach and column as the facet's grouping by key rather than by search. A facet owns a
     * generated term when the term's call param is the facet's own ({@link #isFacetBinding}); a
     * body param and its call param pair positionally (see {@code BodyParam}'s contract), and the
     * pairing check makes a drift a production failure.
     */
    private static List<FacetFragment> fragments(String parentTypeName, String fieldName,
            List<WhereFilter> filters, List<FacetSpec> facetSpecs, GeneratedUnits units) {
        var fragments = new ArrayList<FacetFragment>(facetSpecs.size() + 1);
        fragments.add(new FacetFragment.Base(units.facetBaseConditionMethod(parentTypeName, fieldName)));
        for (var facet : facetSpecs) {
            var own = new ArrayList<ColumnTerm>();
            for (var filter : filters) {
                if (!(filter instanceof GeneratedConditionFilter gcf)) {
                    continue;
                }
                var bodyParams = gcf.bodyParams();
                var callParams = gcf.callParams();
                if (bodyParams.size() != callParams.size()) {
                    throw new IllegalStateException(
                        "generated condition filter on '" + fieldName + "' carries " + bodyParams.size()
                        + " body params but " + callParams.size() + " call params; the two views must pair 1:1");
                }
                for (int i = 0; i < bodyParams.size(); i++) {
                    var bp = bodyParams.get(i);
                    var cp = callParams.get(i);
                    if (!bp.name().equals(cp.name())) {
                        throw new IllegalStateException(
                            "generated condition filter on '" + fieldName + "' pairs body param '" + bp.name()
                            + "' with call param '" + cp.name() + "'; the two views must share names positionally");
                    }
                    if (isFacetBinding(cp, facet)) {
                        own.add(termOf(bp, fieldName));
                    }
                }
            }
            if (own.size() > 1) {
                throw new IllegalStateException(
                    "facet '" + facet.inputFieldName() + "' on '" + parentTypeName + "." + fieldName
                    + "' owns " + own.size() + " generated terms; a facet field binds one filter"
                    + " value, so it owns at most one term");
            }
            fragments.add(new FacetFragment.Own(facet.inputFieldName(),
                units.facetConditionMethod(parentTypeName, fieldName, facet.inputFieldName()),
                own.stream().findFirst()));
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
}
