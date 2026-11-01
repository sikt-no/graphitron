package no.sikt.graphitron.facts;

import graphql.schema.GraphQLFieldDefinition;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Gathers the lookup trigger ({@link LookupFacts}) from every reachable field coordinate whose
 * arguments carry {@code @lookupKey}. This visitor is the sole producer of the population; the
 * directive name has exactly one home here.
 */
public final class LookupFactVisitor implements FactVisitor {

    /** The lookup-key directive; classification-side constants delegate here. */
    public static final String DIR_LOOKUP_KEY = "lookupKey";

    private final Map<GraphQLFieldDefinition, LookupFacts.FieldRow> fieldRows = new IdentityHashMap<>();

    @Override
    public Set<FactSubjectKind> kinds() {
        return Set.of(FactSubjectKind.FIELD_COORDINATE);
    }

    @Override
    public void visitFieldCoordinate(String parentTypeName, GraphQLFieldDefinition fieldDef) {
        var lookupArgs = new ArrayList<String>();
        for (var arg : fieldDef.getArguments()) {
            if (arg.hasAppliedDirective(DIR_LOOKUP_KEY)) {
                lookupArgs.add(arg.getName());
            }
        }
        if (lookupArgs.isEmpty()) {
            return;
        }
        fieldRows.put(fieldDef, new LookupFacts.FieldRow(parentTypeName, fieldDef.getName(), lookupArgs));
    }

    /** The gathered relation; read once by {@link GatheredFacts#gather}'s slot fill. */
    LookupFacts relation() {
        return new LookupFacts(fieldRows);
    }
}
