package no.sikt.graphitron.rewrite.generators.lookup;

import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.field.ArgumentSpec;
import no.sikt.graphitron.rewrite.field.QueryField;
import no.sikt.graphitron.rewrite.field.ReturnTypeRef;
import no.sikt.graphitron.rewrite.type.GraphitronType.TableInputType;
import no.sikt.graphitron.rewrite.type.InputFieldRef.TableInputField;
import no.sikt.graphitron.rewrite.type.TableRef.ResolvedTable;

import java.util.List;
import java.util.Objects;

/**
 * Builds {@link LookupSpec} instances from a {@link GraphitronSchema}.
 *
 * <p>Scans all {@link QueryField.LookupQueryField} fields and produces one {@link LookupSpec}
 * per qualifying field. Two code-generation paths are supported:
 *
 * <ul>
 *   <li><b>Input-type path</b>: the field has an argument whose type is a
 *       {@link TableInputType} (e.g. {@code query(input: [CustomerInput])}). The spec's
 *       {@link LookupSpec#inputArgName()} is set to the argument name; each field of the input
 *       type becomes one {@link LookupInputFieldSpec} (only fully-resolved
 *       {@link no.sikt.graphitron.rewrite.type.InputFieldRef.TableInputField} entries are
 *       included).</li>
 *   <li><b>Flat-args path</b>: the field has no {@code TableInputType} argument but has
 *       direct scalar or list arguments (e.g. {@code personById(tenantId: String, ids: [ID])}).
 *       {@link LookupSpec#inputArgName()} is {@code null}; each non-condition, non-orderBy
 *       argument that can be resolved against the return type's table becomes one
 *       {@link LookupInputFieldSpec}, with {@link LookupInputFieldSpec#list()} set according
 *       to whether the argument is a list type.</li>
 * </ul>
 *
 * <p>Fields that produce an empty spec (no resolvable columns) are silently dropped — the
 * validator already reports those errors.
 */
public class LookupSpecBuilder {

    public static List<LookupSpec> build(GraphitronSchema schema, JooqCatalog catalog) {
        return schema.fields().values().stream()
            .filter(f -> f instanceof QueryField.LookupQueryField)
            .map(f -> (QueryField.LookupQueryField) f)
            .map(field -> buildSpec(field, schema, catalog))
            .filter(spec -> spec != null && !spec.fields().isEmpty())
            .toList();
    }

    private static LookupSpec buildSpec(
            QueryField.LookupQueryField field, GraphitronSchema schema, JooqCatalog catalog) {

        if (!(field.returnType() instanceof ReturnTypeRef.TableBoundReturnType trt)) return null;
        if (!(trt.table() instanceof ResolvedTable rt)) return null;

        String tableJavaFieldName = rt.javaFieldName();

        // Prefer input-type arg if present
        var inputTypeArgOpt = field.arguments().stream()
            .filter(arg -> schema.types().get(arg.typeName()) instanceof TableInputType)
            .findFirst();

        if (inputTypeArgOpt.isPresent()) {
            return buildInputTypeSpec(field, tableJavaFieldName, inputTypeArgOpt.get(), schema);
        } else {
            return buildFlatSpec(field, tableJavaFieldName, rt, catalog);
        }
    }

    /** Builds a spec for the input-type-arg case. */
    private static LookupSpec buildInputTypeSpec(
            QueryField.LookupQueryField field, String tableJavaFieldName,
            ArgumentSpec arg, GraphitronSchema schema) {

        var inputType = (TableInputType) schema.types().get(arg.typeName());
        var fields = inputType.fields().stream()
            .filter(f -> f instanceof TableInputField)
            .map(f -> (TableInputField) f)
            .map(f -> new LookupInputFieldSpec(
                f.name(),
                f.javaColumnName(),
                f.column().getType().getName(),
                false))
            .toList();

        return new LookupSpec(field.returnType().returnTypeName(), tableJavaFieldName, arg.name(), fields);
    }

    /** Builds a spec for the flat-args case (direct scalar/list arguments). */
    private static LookupSpec buildFlatSpec(
            QueryField.LookupQueryField field, String tableJavaFieldName,
            ResolvedTable rt, JooqCatalog catalog) {

        var fields = field.arguments().stream()
            .filter(arg -> !arg.orderBy() && !arg.conditionArg())
            .map(arg -> buildFlatArgSpec(arg, rt, catalog))
            .filter(Objects::nonNull)
            .toList();

        return new LookupSpec(field.returnType().returnTypeName(), tableJavaFieldName, null, fields);
    }

    private static LookupInputFieldSpec buildFlatArgSpec(
            ArgumentSpec arg, ResolvedTable rt, JooqCatalog catalog) {

        return catalog.findColumn(rt.table(), arg.columnName())
            .map(e -> new LookupInputFieldSpec(
                arg.name(),
                e.javaName(),
                e.column().getType().getName(),
                arg.list()))
            .orElse(null);
    }
}
