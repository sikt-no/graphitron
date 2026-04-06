package no.sikt.graphitron.rewrite.generators.lookup;

import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.field.ArgumentSpec;
import no.sikt.graphitron.rewrite.field.QueryField;
import no.sikt.graphitron.rewrite.field.ReturnTypeRef;
import no.sikt.graphitron.rewrite.type.GraphitronType.TableInputType;
import no.sikt.graphitron.rewrite.type.InputFieldRef.TableInputField;
import no.sikt.graphitron.rewrite.type.TableRef.ResolvedTable;

import java.util.List;

/**
 * Builds {@link LookupSpec} instances from a {@link GraphitronSchema}.
 *
 * <p>Scans all {@link QueryField.LookupQueryField} fields for arguments whose type is a
 * {@link TableInputType}. For each such field, produces one {@link LookupSpec} keyed on the
 * return type name. Only fully-resolved fields ({@link TableInputField}) are included;
 * unresolved fields are silently skipped (the validator already reports those errors).
 *
 * <p>If two lookup fields with the same return type have different input types, both are
 * included — one spec per qualifying field.
 */
public class LookupSpecBuilder {

    public static List<LookupSpec> build(GraphitronSchema schema) {
        return schema.fields().values().stream()
            .filter(f -> f instanceof QueryField.LookupQueryField)
            .map(f -> (QueryField.LookupQueryField) f)
            .flatMap(field -> field.arguments().stream()
                .filter(arg -> schema.types().get(arg.typeName()) instanceof TableInputType)
                .map(arg -> buildSpec(field, arg, (TableInputType) schema.types().get(arg.typeName()))))
            .filter(spec -> !spec.fields().isEmpty())
            .toList();
    }

    private static LookupSpec buildSpec(QueryField.LookupQueryField field, ArgumentSpec arg, TableInputType inputType) {
        if (!(field.returnType() instanceof ReturnTypeRef.TableBoundReturnType trt)) {
            return new LookupSpec(field.returnType().returnTypeName(), "", List.of());
        }
        String tableJavaFieldName = trt.table() instanceof ResolvedTable rt ? rt.javaFieldName() : "";

        var fields = inputType.fields().stream()
            .filter(f -> f instanceof TableInputField)
            .map(f -> (TableInputField) f)
            .map(f -> new LookupInputFieldSpec(
                f.name(),
                f.javaColumnName(),
                f.column().getType().getName()))
            .toList();

        return new LookupSpec(field.returnType().returnTypeName(), tableJavaFieldName, fields);
    }
}
