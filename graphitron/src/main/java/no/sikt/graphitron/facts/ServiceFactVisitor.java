package no.sikt.graphitron.facts;

import graphql.schema.GraphQLFieldDefinition;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Gathers the service-call trigger ({@link ServiceFacts}) from every reachable field
 * coordinate carrying {@code @service}. This visitor is the sole producer of the population;
 * the directive name has exactly one home here.
 */
public final class ServiceFactVisitor implements FactVisitor {

    /** The service directive; classification-side constants delegate here. */
    public static final String DIR_SERVICE = "service";

    private final Map<GraphQLFieldDefinition, ServiceFacts.Row> rows = new IdentityHashMap<>();

    @Override
    public Set<FactSubjectKind> kinds() {
        return Set.of(FactSubjectKind.FIELD_COORDINATE);
    }

    @Override
    public void visitFieldCoordinate(String parentTypeName, GraphQLFieldDefinition fieldDef) {
        if (!fieldDef.hasAppliedDirective(DIR_SERVICE)) {
            return;
        }
        rows.put(fieldDef, new ServiceFacts.Row(parentTypeName, fieldDef.getName()));
    }

    /** The gathered relation; read once by {@link GatheredFacts#gather}'s slot fill. */
    ServiceFacts relation() {
        return new ServiceFacts(rows);
    }
}
