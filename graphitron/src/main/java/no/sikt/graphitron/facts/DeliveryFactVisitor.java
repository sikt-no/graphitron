package no.sikt.graphitron.facts;

import graphql.schema.GraphQLFieldDefinition;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Gathers the authored delivery markers ({@link DeliveryFacts}) from every reachable field
 * coordinate carrying {@code @splitQuery} or {@code @tenantFanOut}. This visitor is the sole
 * producer of the population; both directive names have exactly one home here.
 */
public final class DeliveryFactVisitor implements FactVisitor {

    /** The split-query delivery marker; classification-side constants delegate here. */
    public static final String DIR_SPLIT_QUERY = "splitQuery";

    /** The tenant fan-out marker; classification-side constants delegate here. */
    public static final String DIR_TENANT_FAN_OUT = "tenantFanOut";

    private final Map<GraphQLFieldDefinition, DeliveryFacts.Row> rows = new IdentityHashMap<>();

    @Override
    public Set<FactSubjectKind> kinds() {
        return Set.of(FactSubjectKind.FIELD_COORDINATE);
    }

    @Override
    public void visitFieldCoordinate(String parentTypeName, GraphQLFieldDefinition fieldDef) {
        boolean splitQuery = fieldDef.hasAppliedDirective(DIR_SPLIT_QUERY);
        boolean tenantFanOut = fieldDef.hasAppliedDirective(DIR_TENANT_FAN_OUT);
        if (!splitQuery && !tenantFanOut) {
            return;
        }
        rows.put(fieldDef, new DeliveryFacts.Row(parentTypeName, fieldDef.getName(), splitQuery, tenantFanOut));
    }

    /** The gathered relation; read once by {@link GatheredFacts#gather}'s slot fill. */
    DeliveryFacts relation() {
        return new DeliveryFacts(rows);
    }
}
