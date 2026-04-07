package no.sikt.graphitron.rewrite.generators.lookup;

import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.field.ArgumentRef.InputTypeArg.TableInputTypeArg;
import no.sikt.graphitron.rewrite.field.ArgumentRef.ScalarArg.ColumnArg;
import no.sikt.graphitron.rewrite.field.QueryField;
import no.sikt.graphitron.rewrite.field.ReturnTypeRef;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.type.GraphitronType.TableInputType;
import no.sikt.graphitron.rewrite.type.InputFieldRef.TableInputField;
import no.sikt.graphitron.rewrite.type.TableRef.ResolvedTable;

import java.util.List;

/**
 * Builds {@link LookupSpec} instances from a {@link GraphitronSchema}.
 *
 * <p>Scans all {@link QueryField.LookupQueryField} fields and produces one {@link LookupSpec}
 * per qualifying field. Two code-generation paths are supported:
 *
 * <ul>
 *   <li><b>Input-type path</b>: the field has an argument whose type is a
 *       {@link TableInputType}. The spec's {@link LookupSpec#inputArgName()} is set to that
 *       argument's name; each fully-resolved field of the input type becomes one
 *       {@link LookupInputFieldSpec}.</li>
 *   <li><b>Flat-args path</b>: the field has no {@code TableInputType} argument.
 *       {@link LookupSpec#inputArgName()} is {@code null}; each
 *       {@link ArgumentRef.ScalarArg.ColumnArg} from {@link QueryField.LookupQueryField#arguments()}
 *       becomes one {@link LookupInputFieldSpec}. Column resolution was performed during schema
 *       building — no catalog access is needed here.</li>
 * </ul>
 *
 * <p>Fields that produce an empty spec (no resolvable columns) are silently dropped — the
 * validator already reports those errors.
 */
public class LookupSpecBuilder {

    public static List<LookupSpec> build(GraphitronSchema schema) {
        return schema.fields().values().stream()
            .filter(f -> f instanceof QueryField.LookupQueryField)
            .map(f -> (QueryField.LookupQueryField) f)
            .map(field -> buildSpec(field, schema))
            .filter(spec -> spec != null && !spec.fields().isEmpty())
            .toList();
    }

    private static LookupSpec buildSpec(QueryField.LookupQueryField field, GraphitronSchema schema) {
        if (!(field.returnType() instanceof ReturnTypeRef.TableBoundReturnType trt)) return null;
        if (!(trt.table() instanceof ResolvedTable rt)) return null;

        String tableJavaFieldName = rt.javaFieldName();

        // Prefer input-type arg that has been promoted to TableInputType
        var inputTypeArgOpt = field.arguments().stream()
            .filter(a -> a instanceof TableInputTypeArg)
            .map(a -> (TableInputTypeArg) a)
            .findFirst();

        if (inputTypeArgOpt.isPresent()) {
            return buildInputTypeSpec(field, tableJavaFieldName, inputTypeArgOpt.get(), schema);
        } else {
            return buildFlatSpec(field, tableJavaFieldName);
        }
    }

    private static LookupSpec buildInputTypeSpec(
            QueryField.LookupQueryField field, String tableJavaFieldName,
            TableInputTypeArg arg, GraphitronSchema schema) {

        var inputType = (TableInputType) schema.types().get(arg.typeName());
        var fields = inputType.fields().stream()
            .filter(f -> f instanceof TableInputField)
            .map(f -> (TableInputField) f)
            .map(f -> new LookupInputFieldSpec(
                f.name(),
                f.javaColumnName(),
                f.columnClass(),
                false))
            .toList();

        return new LookupSpec(field.returnType().returnTypeName(), tableJavaFieldName, arg.name(), fields);
    }

    private static LookupSpec buildFlatSpec(QueryField.LookupQueryField field, String tableJavaFieldName) {
        var fields = field.arguments().stream()
            .filter(a -> a instanceof ColumnArg)
            .map(a -> (ColumnArg) a)
            .map(a -> new LookupInputFieldSpec(
                a.name(),
                a.javaColumnName(),
                a.columnClass(),
                a.list()))
            .toList();

        return new LookupSpec(field.returnType().returnTypeName(), tableJavaFieldName, null, fields);
    }
}
