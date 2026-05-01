package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLInputObjectType;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.TableRef;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Classifies the fields of a plain (non-{@code @table}) input type at the call site against a
 * resolving table. The fifth projection resolver under R6 (Phase 6d), sibling to
 * {@link OrderByResolver} (Phase 5), {@link LookupMappingResolver} (Phase 6a),
 * {@link PaginationResolver} (Phase 6b), and {@link ConditionResolver} (Phase 6c).
 *
 * <p>Used to populate {@link ArgumentRef.InputTypeArg.PlainInputArg#fields()} when
 * {@link FieldBuilder#classifyArgument} routes a {@code GraphitronType.InputType} or
 * {@code UnclassifiedType} (input-resolution-failed) argument through the plain-input path.
 *
 * <p>The actual per-field classification is delegated to
 * {@link BuildContext#classifyInputField}; this resolver is the small wrapper that:
 * <ul>
 *   <li>Skips the work entirely when {@code rt} is {@code null} or the type is not an input
 *       object (returns {@link List#of()}).</li>
 *   <li>Iterates the schema-level field definitions, building each {@link InputField} via the
 *       context-level classifier.</li>
 *   <li>Collects per-field condition errors into a per-call buffer, then re-prefixes them with
 *       {@code "plain input type '<typeName>': "} before appending to the caller's accumulating
 *       errors list.</li>
 *   <li>Silently skips fields that fail column resolution — their conditions cannot be built,
 *       and the caller's accumulating errors list will surface the structural problem via the
 *       outer-argument classification path. {@code @notGenerated} fields are rejected up front
 *       by {@link FieldBuilder#classifyArgument} as an
 *       {@link ArgumentRef.UnclassifiedArg}, so they never reach this classifier.</li>
 * </ul>
 *
 * <p>No sealed result wrapper: the projection is total — every input shape produces a list
 * (possibly empty). The errors-list mutation pattern is preserved because the per-field errors
 * are emitted by {@link BuildContext#classifyInputField} into a buffer, then forwarded with a
 * uniform prefix; threading sealed results through the per-field walk would force every field's
 * potential error to surface as a top-level rejection, which is a behaviour change.
 */
final class InputFieldResolver {

    private final BuildContext ctx;

    InputFieldResolver(BuildContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Classifies the fields of a plain (non-{@code @table}) input type {@code typeName} against
     * the resolving table {@code rt}. Returns {@link List#of()} when {@code rt} is {@code null}
     * or the schema type is not an input object. Per-field condition-build errors are appended
     * to {@code errors} prefixed with {@code "plain input type '<typeName>': "}.
     */
    List<InputField> resolve(String typeName, TableRef rt, List<String> errors) {
        if (rt == null) return List.of();
        var rawType = ctx.schema.getType(typeName);
        if (!(rawType instanceof GraphQLInputObjectType iot)) return List.of();
        var condErrors = new ArrayList<String>();
        var classified = new ArrayList<InputField>();
        for (var f : iot.getFieldDefinitions()) {
            var res = ctx.classifyInputField(f, typeName, rt, new LinkedHashSet<>(), condErrors);
            if (res instanceof InputFieldResolution.Resolved r) {
                classified.add(r.field());
            }
        }
        condErrors.forEach(e -> errors.add("plain input type '" + typeName + "': " + e));
        return List.copyOf(classified);
    }
}
