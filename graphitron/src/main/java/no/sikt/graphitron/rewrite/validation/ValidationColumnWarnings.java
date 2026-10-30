package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.emit.RecordAccessorQuery;
import no.sikt.graphitron.model.diagnostics.BuildWarning;
import no.sikt.graphitron.model.jooq.RecordGetters;
import no.sikt.graphitron.model.lint.ExcludedTypes;
import no.sikt.graphitron.model.lint.LintRule;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.ErrorChannel;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.ServiceMethodCall;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * The {@code validation-column-unchecked} advisory: a column a VALIDATION pre-step cannot check on a
 * jOOQ record parameter, because the store found no getter the record reads it through. The same
 * partition the pre-step emits its checks from
 * ({@link ServiceMethodCall.ValidationSubject.WrittenColumns#partition}) over the same
 * {@link RecordAccessorQuery.Rows}, so the advisory and the emitted checks cannot disagree about a
 * column.
 *
 * <p>One finding per (record class, column), located at the first field in schema order that
 * reaches it, and none at a field whose owning type the lint configuration excludes. Folded in at
 * report assembly, so a validate-only run and the LSP see it too, and suppressible by rule id.
 */
public final class ValidationColumnWarnings {

    private ValidationColumnWarnings() {}

    public static List<BuildWarning> findings(GraphitronSchema schema, RecordAccessorQuery.Rows accessors,
                                              ExcludedTypes excludedTypes) {
        var findings = new ArrayList<BuildWarning>();
        var seen = new LinkedHashSet<String>();
        for (GraphitronField field : schema.fields().values()) {
            var call = validatedCall(field);
            if (call.isEmpty() || excludedTypes.matches(field.parentTypeName())) {
                continue;
            }
            for (var target : call.get().validationTargets()) {
                if (!(target.subject() instanceof ServiceMethodCall.ValidationSubject.WrittenColumns columns)) {
                    continue;
                }
                var table = columns.record().carrier().table();
                for (var binding : columns.partition(accessors::propertyOf).unchecked()) {
                    String column = binding.column().sqlName();
                    if (!seen.add(table.recordClassName() + " " + column)) {
                        continue;
                    }
                    findings.add(BuildWarning.LintFinding.of(
                        "'" + field.parentTypeName() + "." + field.name() + "' validates the jOOQ record "
                            + table.recordClassName() + " under a VALIDATION handler, but cannot check its column '"
                            + column + "': the record declares no public getter " + RecordGetters.getterName(column)
                            + "(), the name jOOQ's default generator strategy gives it, so the constraints on that "
                            + "column's property are not run. A generator strategy that renames getters causes "
                            + "this; silence the rule (validation-column-unchecked) if the column needs no check.",
                        field.location(), LintRule.VALIDATION_COLUMN_UNCHECKED));
                }
            }
        }
        return findings;
    }

    /**
     * The service call a VALIDATION pre-step runs before, on a field whose channel carries a
     * VALIDATION handler: the four root service shapes, which are the fields the pre-step is emitted
     * on.
     */
    private static Optional<ServiceMethodCall> validatedCall(GraphitronField field) {
        record Call(ServiceMethodCall call, Optional<ErrorChannel.Mapped> channel) {}
        var call = switch (field) {
            case QueryField.QueryServiceTableField f -> new Call(f.serviceMethodCall(), f.errorChannel());
            case QueryField.QueryServiceRecordField f -> new Call(f.serviceMethodCall(), f.errorChannel());
            case MutationField.MutationServiceTableField f -> new Call(f.serviceMethodCall(), f.errorChannel());
            case MutationField.MutationServiceRecordField f -> new Call(f.serviceMethodCall(), f.errorChannel());
            default -> null;
        };
        if (call == null || call.channel().isEmpty() || !call.channel().get().hasValidationHandler()) {
            return Optional.empty();
        }
        return Optional.of(call.call());
    }
}
