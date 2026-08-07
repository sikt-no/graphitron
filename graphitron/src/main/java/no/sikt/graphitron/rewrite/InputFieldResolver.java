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
 * signals binding intent just as much as {@code @condition}-annotated does. A single failure keeps
 * the arm its producer chose (prefixed with this call site's context), so LSP fix-its and watch-mode
 * formatters consume whatever structured components that arm carries; a multi-cause fold has no
 * single arm to carry and still lifts as {@link Rejection#structural} with joined prose.
 * {@code @condition} reflection failures fold into the same {@link Resolution.Rejected} arm via the
 * resolver's private {@link InputFieldConditionFailure} buffer; no second mutation channel escapes.
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
     * argument's arg-level override) into the classifier's {@link ClassifyContext}. The
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
        var conditionFailures = new ArrayList<InputFieldConditionFailure>();
        var classified = new ArrayList<InputField>();
        var failures = new ArrayList<InputFieldResolution.Unresolved>();
        for (var f : iot.getFieldDefinitions()) {
            var res = ctx.classifyInputField(f, typeName, rt,
                ClassifyContext.withEnclosingOverride(enclosingOverride), conditionFailures);
            switch (res) {
                case InputFieldResolution.Resolved r -> classified.add(r.field());
                case InputFieldResolution.Unresolved u -> failures.add(u);
            }
        }
        if (failures.isEmpty() && conditionFailures.isEmpty()) {
            return new Resolution.Ok(List.copyOf(classified));
        }
        // Every cause is minted at the input field that carries it; this call site keeps one
        // rejection stating the consequence. An author sees one diagnostic per broken input field
        // plus one on the consuming field, which is the shape a compiler uses for "cannot
        // instantiate" plus its member errors.
        int minted = ctx.mintInputFieldFailures(typeName, failures, conditionFailures);
        return new Resolution.Rejected(Rejection.structural(
            "plain input type '" + typeName + "' against table '" + rt.tableName() + "': "
            + minted + " input field" + (minted == 1 ? "" : "s") + " could not be resolved"));
    }
}
