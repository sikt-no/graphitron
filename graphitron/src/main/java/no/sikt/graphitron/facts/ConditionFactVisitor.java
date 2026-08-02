package no.sikt.graphitron.facts;

import graphql.language.BooleanValue;
import graphql.schema.GraphQLDirectiveContainer;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Gathers the authored condition trigger ({@link ConditionFacts}) from every reachable field
 * coordinate (field-level and argument-level {@code @condition} applications) and every
 * reachable input object field. This visitor is the sole producer of the authored population;
 * the directive name and its {@code override:} argument name have exactly one home here. The
 * deep directive payload ({@code className} / {@code method} / {@code argMapping} /
 * {@code contextArguments}) stays with the classification-side resolver that joins it to the
 * consumer's reflected Java surface; the walked fact is the application site and the
 * suppression edge.
 */
public final class ConditionFactVisitor implements FactVisitor {

    /** The condition directive; classification-side constants delegate here. */
    public static final String DIR_CONDITION = "condition";

    /** The directive's generated-subtree suppression argument. */
    public static final String ARG_OVERRIDE = "override";

    private final Map<GraphQLFieldDefinition, ConditionFacts.FieldRow> fieldRows = new IdentityHashMap<>();
    private final Map<GraphQLInputObjectField, ConditionFacts.InputFieldRow> inputFieldRows = new IdentityHashMap<>();

    @Override
    public Set<FactSubjectKind> kinds() {
        return Set.of(FactSubjectKind.FIELD_COORDINATE, FactSubjectKind.INPUT_OBJECT_FIELD);
    }

    @Override
    public void visitFieldCoordinate(String parentTypeName, GraphQLFieldDefinition fieldDef) {
        boolean onField = fieldDef.hasAppliedDirective(DIR_CONDITION);
        var argSites = new ArrayList<ConditionFacts.ArgSite>();
        for (var arg : fieldDef.getArguments()) {
            if (arg.hasAppliedDirective(DIR_CONDITION)) {
                argSites.add(new ConditionFacts.ArgSite(arg.getName(), overrideFlag(arg)));
            }
        }
        if (!onField && argSites.isEmpty()) {
            return;
        }
        fieldRows.put(fieldDef, new ConditionFacts.FieldRow(parentTypeName, fieldDef.getName(),
            onField, onField && overrideFlag(fieldDef), argSites));
    }

    @Override
    public void visitInputObjectField(GraphQLInputObjectType parent, GraphQLInputObjectField field) {
        if (!field.hasAppliedDirective(DIR_CONDITION)) {
            return;
        }
        inputFieldRows.put(field, new ConditionFacts.InputFieldRow(
            parent.getName(), field.getName(), overrideFlag(field)));
    }

    /**
     * Coerces the directive's {@code override:} argument. graphql-java surfaces a directive
     * argument either as the raw schema AST literal ({@link BooleanValue}) or as an
     * already-parsed {@link Boolean} depending on the resolution path; both coerce here, once.
     * Absent or null means {@code false}.
     */
    private static boolean overrideFlag(GraphQLDirectiveContainer container) {
        var dir = container.getAppliedDirective(DIR_CONDITION);
        if (dir == null) {
            return false;
        }
        var arg = dir.getArgument(ARG_OVERRIDE);
        if (arg == null || arg.getValue() == null) {
            return false;
        }
        Object val = arg.getValue();
        if (val instanceof BooleanValue bv) {
            return bv.isValue();
        }
        return val instanceof Boolean b && b;
    }

    /** The gathered relation; read once by {@link GatheredFacts#gather}'s slot fill. */
    ConditionFacts relation() {
        return new ConditionFacts(fieldRows, inputFieldRows);
    }
}
