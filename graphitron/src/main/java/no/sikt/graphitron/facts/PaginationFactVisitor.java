package no.sikt.graphitron.facts;

import graphql.language.IntValue;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLTypeUtil;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Gathers the pagination fact ({@link PaginationFacts}) from every reachable field coordinate:
 * the authored population (arguments carrying one of the four reserved names) and the inferred
 * population (the connection directive's presence, with its coerced {@code defaultFirstValue}
 * when authored). This visitor is the sole producer of both populations; the reserved-name
 * recognition and the directive-argument coercion each have exactly one home here.
 */
public final class PaginationFactVisitor implements FactVisitor {

    /** The pagination-implying connection directive; classification-side constants delegate here. */
    public static final String DIR_AS_CONNECTION = "asConnection";

    /** The directive's authored default-page-size argument. */
    public static final String ARG_DEFAULT_FIRST_VALUE = "defaultFirstValue";

    private final Map<GraphQLFieldDefinition, PaginationFacts.Row> rows = new IdentityHashMap<>();

    @Override
    public Set<FactSubjectKind> kinds() {
        return Set.of(FactSubjectKind.FIELD_COORDINATE);
    }

    @Override
    public void visitFieldCoordinate(String parentTypeName, GraphQLFieldDefinition fieldDef) {
        var args = new ArrayList<PaginationFacts.PaginationArg>();
        for (var arg : fieldDef.getArguments()) {
            var role = roleOf(arg.getName());
            if (role == null) {
                continue;
            }
            var type = arg.getType();
            boolean nonNull = type instanceof GraphQLNonNull;
            boolean list = GraphQLTypeUtil.unwrapNonNull(type) instanceof GraphQLList;
            String typeName = ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(type)).getName();
            args.add(new PaginationFacts.PaginationArg(role, typeName, nonNull, list));
        }
        boolean asConnection = fieldDef.hasAppliedDirective(DIR_AS_CONNECTION);
        if (args.isEmpty() && !asConnection) {
            return;
        }
        rows.put(fieldDef, new PaginationFacts.Row(parentTypeName, fieldDef.getName(),
            args, asConnection, asConnection ? authoredDefaultFirst(fieldDef) : OptionalInt.empty()));
    }

    private static PaginationFacts.Role roleOf(String argName) {
        return switch (argName) {
            case "first"  -> PaginationFacts.Role.FIRST;
            case "last"   -> PaginationFacts.Role.LAST;
            case "after"  -> PaginationFacts.Role.AFTER;
            case "before" -> PaginationFacts.Role.BEFORE;
            default       -> null;
        };
    }

    /**
     * Coerces the directive's {@code defaultFirstValue} argument. graphql-java surfaces a
     * directive argument either as the raw schema AST literal ({@link IntValue}) or as an
     * already-parsed {@link Number} depending on the resolution path; both coerce here, once,
     * so no reader carries its own coercion arms.
     */
    private static OptionalInt authoredDefaultFirst(GraphQLFieldDefinition fieldDef) {
        var dir = fieldDef.getAppliedDirective(DIR_AS_CONNECTION);
        if (dir == null) {
            return OptionalInt.empty();
        }
        var arg = dir.getArgument(ARG_DEFAULT_FIRST_VALUE);
        if (arg == null || arg.getValue() == null) {
            return OptionalInt.empty();
        }
        Object val = arg.getValue();
        if (val instanceof IntValue iv) {
            return OptionalInt.of(iv.getValue().intValueExact());
        }
        if (val instanceof Number n) {
            return OptionalInt.of(n.intValue());
        }
        return OptionalInt.empty();
    }

    /** The gathered relation; read once by {@link GatheredFacts#gather}'s slot fill. */
    PaginationFacts relation() {
        return new PaginationFacts(rows);
    }
}
