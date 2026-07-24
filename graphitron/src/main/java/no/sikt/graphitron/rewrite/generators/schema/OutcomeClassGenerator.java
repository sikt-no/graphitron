package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.TypeVariableName;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Generates the {@code Outcome<T>} runtime wrapper emitted at
 * {@code <outputPackage>.schema.Outcome}, once per code-generation run.
 *
 * <p>{@code Outcome} is the request-time GraphQL source object an outcome field's fetcher returns:
 * {@code Success(value)} on the success projection, {@code ErrorList(errors)} on the error
 * projection. It is the request-time witness of the success/error fork, distinct from the
 * classify-time {@link no.sikt.graphitron.rewrite.model.ErrorChannel} carrier.
 *
 * <p>The wrapper exists because graphql-java short-circuits <em>all</em> of an object type's
 * children when the source is null, so an errors field on a forking type would never be fetched
 * and the typed error silently dropped. With {@code Outcome} the source is always non-null and
 * graphql-java always descends: data fields project {@code Success.value} (null on the
 * {@code ErrorList} arm) and the errors field projects {@code ErrorList.errors}.
 *
 * <p>Two corollaries every immediate child of an outcome type must honour:
 * <ul>
 *   <li><b>Every immediate child arm-switches</b>: unwrap {@code Success} before the read, null on
 *       {@code ErrorList}. Pinned at build time by
 *       {@code GraphitronSchemaValidator.validateOutcomeChildArmSwitch}.</li>
 *   <li><b>Success-projection fields must be nullable</b>: on a non-null SDL type the error-arm
 *       null raises {@code NonNullableFieldWasNullError} and bubbles up, dropping the sibling
 *       errors field. Rejected at classify time as
 *       {@code ErrorChannelWalkerError.NonNullableSuccessProjectionField}.</li>
 * </ul>
 *
 * <p>Emitted as plain classes with accessors rather than a sealed interface with {@code record}
 * arms because the project's JavaPoet fork does not expose {@code sealed} / {@code permits} /
 * record builders. Generated alongside {@code ErrorRouter} / {@code ErrorMappings}, preserving the
 * rewrite's no-runtime-jar invariant.
 */
public final class OutcomeClassGenerator {

    public static final String CLASS_NAME = "Outcome";
    public static final String SUCCESS_CLASS = "Success";
    public static final String ERROR_LIST_CLASS = "ErrorList";

    private static final ClassName OBJECT_CN = ClassName.get(Object.class);
    private static final ClassName LIST_CN = ClassName.get(List.class);

    private OutcomeClassGenerator() {}

    public static List<TypeSpec> generate(String outputPackage) {
        var outcomeRaw = ClassName.get(outputPackage + ".schema", CLASS_NAME);

        var sT = TypeVariableName.get("T");
        var success = TypeSpec.classBuilder(SUCCESS_CLASS)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addTypeVariable(sT)
            .addSuperinterface(ParameterizedTypeName.get(outcomeRaw, sT))
            .addJavadoc("The success projection: $L holds exactly what the success path produces\n"
                + "today (a typed jOOQ record, {@code Result<Record>}, or {@code List<XRecord>}).\n", "{@code value}")
            .addField(sT, "value", Modifier.PRIVATE, Modifier.FINAL)
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(sT, "value")
                .addStatement("this.value = value")
                .build())
            .addMethod(MethodSpec.methodBuilder("value")
                .addModifiers(Modifier.PUBLIC)
                .returns(sT)
                .addStatement("return value")
                .build())
            .build();

        var eT = TypeVariableName.get("T");
        var listOfObject = ParameterizedTypeName.get(LIST_CN, OBJECT_CN);
        var errorList = TypeSpec.classBuilder(ERROR_LIST_CLASS)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addTypeVariable(eT)
            .addSuperinterface(ParameterizedTypeName.get(outcomeRaw, eT))
            .addJavadoc("The error projection: the matched error list. {@code List<Object>} because it\n"
                + "carries two populations, matched throwables on the catch path and Jakarta\n"
                + "{@code ConstraintViolation} objects on the validator pre-step path. {@code T} is\n"
                + "phantom on this arm.\n")
            .addField(listOfObject, "errors", Modifier.PRIVATE, Modifier.FINAL)
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(listOfObject, "errors")
                .addStatement("this.errors = errors")
                .build())
            .addMethod(MethodSpec.methodBuilder("errors")
                .addModifiers(Modifier.PUBLIC)
                .returns(listOfObject)
                .addStatement("return errors")
                .build())
            .build();

        var iT = TypeVariableName.get("T");
        var spec = TypeSpec.interfaceBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(iT)
            .addJavadoc("Request-time wrapper witnessing the success/error fork of an outcome field.\n"
                + "\n"
                + "<p>The source is always non-null so graphql-java always descends into the outcome\n"
                + "type's children: data fields project {@code Success.value} (null on the error arm,\n"
                + "so they render null and their children are not visited) and the errors field\n"
                + "projects {@code ErrorList.errors}. A null source would make graphql-java\n"
                + "short-circuit all children, silently dropping the typed error.\n"
                + "\n"
                + "<p>Every immediate child of an outcome type must arm-switch: each data-channel\n"
                + "fetcher unwraps {@code Success} before its read and returns null on\n"
                + "{@code ErrorList}. Success-projection fields must be nullable, else the error-arm\n"
                + "null bubbles up and drops the sibling errors field.\n")
            .addType(success)
            .addType(errorList)
            .build();

        return List.of(spec);
    }
}
