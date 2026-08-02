package no.sikt.graphitron.facts;

import graphql.schema.GraphQLFieldDefinition;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Gathers the authored ordering trigger ({@link OrderByFacts}) from every reachable field
 * coordinate: arguments carrying {@code @orderBy} and field-level {@code @defaultOrder}
 * applications. This visitor is the sole producer of the authored population; both directive
 * names have exactly one home here. The directive payloads (the sort-enum vocabulary behind an
 * orderBy argument, {@code @defaultOrder}'s field list) stay with the classification-side
 * resolver that joins them to the catalog; the walked fact is the application site.
 */
public final class OrderByFactVisitor implements FactVisitor {

    /** The argument-level ordering directive; classification-side constants delegate here. */
    public static final String DIR_ORDER_BY = "orderBy";

    /** The field-level fixed-ordering directive; classification-side constants delegate here. */
    public static final String DIR_DEFAULT_ORDER = "defaultOrder";

    private final Map<GraphQLFieldDefinition, OrderByFacts.Row> rows = new IdentityHashMap<>();

    @Override
    public Set<FactSubjectKind> kinds() {
        return Set.of(FactSubjectKind.FIELD_COORDINATE);
    }

    @Override
    public void visitFieldCoordinate(String parentTypeName, GraphQLFieldDefinition fieldDef) {
        var orderByArgs = new ArrayList<String>();
        for (var arg : fieldDef.getArguments()) {
            if (arg.hasAppliedDirective(DIR_ORDER_BY)) {
                orderByArgs.add(arg.getName());
            }
        }
        boolean defaultOrder = fieldDef.hasAppliedDirective(DIR_DEFAULT_ORDER);
        if (orderByArgs.isEmpty() && !defaultOrder) {
            return;
        }
        rows.put(fieldDef, new OrderByFacts.Row(parentTypeName, fieldDef.getName(),
            orderByArgs, defaultOrder));
    }

    /** The gathered relation; read once by {@link GatheredFacts#gather}'s slot fill. */
    OrderByFacts relation() {
        return new OrderByFacts(rows);
    }
}
