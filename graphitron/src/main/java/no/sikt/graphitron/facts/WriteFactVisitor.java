package no.sikt.graphitron.facts;

import graphql.language.BooleanValue;
import graphql.language.EnumValue;
import graphql.language.StringValue;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLFieldDefinition;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Gathers the write trigger ({@link WriteFacts}) from every reachable field coordinate
 * carrying {@code @mutation}. This visitor is the sole producer of the population; the
 * directive name has exactly one home here. Its argument names are local constants: their
 * literals coincide with argument names other directives also use, so no classification-side
 * constant delegates to them.
 */
public final class WriteFactVisitor implements FactVisitor {

    /** The mutation directive; classification-side constants delegate here. */
    public static final String DIR_MUTATION = "mutation";

    /** The directive's verb argument. */
    public static final String ARG_TYPE_NAME = "typeName";

    /** The directive's bulk flag. */
    public static final String ARG_MULTI_ROW = "multiRow";

    /** The directive's field-relative write-target argument. */
    public static final String ARG_TABLE = "table";

    private final Map<GraphQLFieldDefinition, WriteFacts.Row> rows = new IdentityHashMap<>();

    @Override
    public Set<FactSubjectKind> kinds() {
        return Set.of(FactSubjectKind.FIELD_COORDINATE);
    }

    @Override
    public void visitFieldCoordinate(String parentTypeName, GraphQLFieldDefinition fieldDef) {
        var dir = fieldDef.getAppliedDirective(DIR_MUTATION);
        if (dir == null) {
            return;
        }
        rows.put(fieldDef, new WriteFacts.Row(parentTypeName, fieldDef.getName(),
            verbToken(dir), multiRow(dir), tableArg(dir)));
    }

    /**
     * Coerces the directive's {@code typeName:} verb argument. graphql-java surfaces it either
     * as the raw schema AST literal ({@link EnumValue}) or as an already-parsed {@link String}
     * depending on the resolution path; both coerce here, once.
     */
    private static Optional<String> verbToken(GraphQLAppliedDirective dir) {
        var arg = dir.getArgument(ARG_TYPE_NAME);
        if (arg == null || arg.getValue() == null) {
            return Optional.empty();
        }
        Object val = arg.getValue();
        String raw = val instanceof EnumValue ev ? ev.getName()
            : val instanceof String s ? s
            : null;
        return Optional.ofNullable(raw);
    }

    private static boolean multiRow(GraphQLAppliedDirective dir) {
        var arg = dir.getArgument(ARG_MULTI_ROW);
        if (arg == null || arg.getValue() == null) {
            return false;
        }
        Object val = arg.getValue();
        if (val instanceof BooleanValue bv) {
            return bv.isValue();
        }
        return val instanceof Boolean b && b;
    }

    private static Optional<String> tableArg(GraphQLAppliedDirective dir) {
        var arg = dir.getArgument(ARG_TABLE);
        if (arg == null || arg.getValue() == null) {
            return Optional.empty();
        }
        Object val = arg.getValue();
        String raw = val instanceof StringValue sv ? sv.getValue()
            : val instanceof String s ? s
            : null;
        return raw == null || raw.isBlank() ? Optional.empty() : Optional.of(raw);
    }

    /** The gathered relation; read once by {@link GatheredFacts#gather}'s slot fill. */
    WriteFacts relation() {
        return new WriteFacts(rows);
    }
}
