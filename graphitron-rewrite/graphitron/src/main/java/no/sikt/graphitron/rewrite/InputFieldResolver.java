package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLInputObjectType;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.TableRef;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Classifies the fields of a plain (non-{@code @table}) input type at the call site against a
 * resolving table. Sibling to {@link OrderByResolver}, {@link LookupMappingResolver},
 * {@link PaginationResolver}, and {@link ConditionResolver}.
 *
 * <p>Used to populate {@link ArgumentRef.InputTypeArg.PlainInputArg#fields()} when
 * {@link FieldBuilder#classifyArgument} routes a {@code GraphitronType.InputType} or
 * {@code UnclassifiedType} (input-resolution-failed) argument through the plain-input path.
 *
 * <p>Returns a sealed {@link Resolution}: {@link Resolution.Ok} when every field classifies
 * cleanly (including the empty-fields case where {@code rt} is {@code null} or the schema type is
 * not an input object), {@link Resolution.Rejected} when any field fails to resolve against the
 * target table or any {@code @condition} reflection fails. Mirrors
 * {@link OrderByResolver.Resolved} on the orderBy side.
 *
 * <p>Any {@link InputFieldResolution.Unresolved} is a build error: bare-field-without-{@code @condition}
 * signals binding intent just as much as {@code @condition}-annotated does. The single-Unresolved
 * column-miss case lifts as {@link Rejection#unknownColumn} so LSP fix-its and watch-mode
 * formatters consume the structured {@code attempt + candidates}; everything else lifts as
 * {@link Rejection#structural} with joined prose. {@code @condition} reflection failures fold
 * into the same {@link Resolution.Rejected} arm via the resolver's private {@code condErrors}
 * buffer; no second mutation channel escapes.
 */
final class InputFieldResolver {

    /** Sealed result of {@link #resolve}; siblings {@link OrderByResolver.Resolved}. */
    sealed interface Resolution {
        record Ok(List<InputField> fields) implements Resolution {}
        record Rejected(Rejection rejection) implements Resolution {
            public String message() { return rejection.message(); }
        }
    }

    private final BuildContext ctx;

    InputFieldResolver(BuildContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Classifies the fields of a plain (non-{@code @table}) input type {@code typeName} against
     * the resolving table {@code rt}.
     *
     * <p>{@code enclosingOverride} threads the call site's cascade flag (the field-level
     * {@code @condition(override:true)} on the enclosing query field ORed with the consuming
     * argument's arg-level override) into the classifier's {@link ClassifyContext}. R215: the
     * classifier's variant decisions today do not branch on this value (column-miss uniformly
     * lifts to {@link InputField.UnboundField}); the flag rides through for nested-input cascade
     * propagation and future-growth axes.
     *
     * <p>Returns {@link Resolution.Ok} with an empty list when {@code rt} is {@code null} or the
     * schema type is not an input object (no work to do). Returns {@link Resolution.Rejected}
     * when at least one field fails column resolution or any {@code @condition} reflection fails.
     */
    Resolution resolve(String typeName, TableRef rt, boolean enclosingOverride) {
        if (rt == null) return new Resolution.Ok(List.of());
        var rawType = ctx.schema.getType(typeName);
        if (!(rawType instanceof GraphQLInputObjectType iot)) return new Resolution.Ok(List.of());
        var condErrors = new ArrayList<String>();
        var classified = new ArrayList<InputField>();
        var failures = new ArrayList<InputFieldResolution.Unresolved>();
        for (var f : iot.getFieldDefinitions()) {
            var res = ctx.classifyInputField(f, typeName, rt,
                ClassifyContext.withEnclosingOverride(enclosingOverride), condErrors);
            switch (res) {
                case InputFieldResolution.Resolved r -> classified.add(r.field());
                case InputFieldResolution.Unresolved u -> failures.add(u);
            }
        }
        String prefix = "plain input type '" + typeName + "': ";
        if (failures.isEmpty() && condErrors.isEmpty()) {
            return new Resolution.Ok(List.copyOf(classified));
        }
        // A typed `unknownColumn` arm carries exactly one (attempt, candidates) payload, so
        // we can only lift to it when there's a single column-miss failure and no condition
        // errors competing for the structured slot; everything else folds to structural prose.
        boolean canLiftToUnknownName = condErrors.isEmpty()
            && failures.size() == 1
            && failures.get(0).lookupColumn() != null;
        if (canLiftToUnknownName) {
            var u = failures.get(0);
            String summary = prefix + "input field '" + u.fieldName() + "'";
            return new Resolution.Rejected(Rejection.unknownColumn(
                summary, u.lookupColumn(), ctx.catalog.columnJavaNamesOf(rt.tableName())));
        }
        var parts = new ArrayList<String>();
        for (var u : failures) {
            parts.add("input field '" + u.fieldName() + "': " + u.reason());
        }
        parts.addAll(condErrors);
        return new Resolution.Rejected(Rejection.structural(
            prefix + parts.stream().collect(Collectors.joining("; "))));
    }
}
