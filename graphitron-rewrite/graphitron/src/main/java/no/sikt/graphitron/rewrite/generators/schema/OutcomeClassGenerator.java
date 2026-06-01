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
 * {@code <outputPackage>.schema.Outcome}, once per code-generation run (R244).
 *
 * <p>{@code Outcome} is the request-time GraphQL source object an outcome field's fetcher returns:
 * {@code Success(value)} on the success projection, {@code ErrorList(errors)} on the error
 * projection. It is the request-time witness of the success/error fork, deliberately distinct from
 * the classify-time {@code ErrorChannel} carrier.
 *
 * <h2>The graphql-java completion invariant this type encodes</h2>
 *
 * <p>The wrapper exists to satisfy a graphql-java 25.0 completion invariant that a {@code null}
 * source violates. A null source makes graphql-java's {@code completeValueForObject} short-circuit
 * <em>all</em> of an object type's children, so an errors field on a forking type is never fetched
 * and the typed error is silently dropped ({@code {outcomeField: null}}, no top-level error
 * either). {@code Outcome} resolves this by construction: the source is always non-null, so
 * graphql-java always descends. The data (success-projection) fields project {@code Success.value}
 * (null on the {@code ErrorList} arm, so they render null and their own children are not visited)
 * and the errors field projects {@code ErrorList.errors}.
 *
 * <p>Two corollaries every immediate child of an outcome type must honour:
 * <ul>
 *   <li><b>Every immediate child arm-switches.</b> Each data-channel fetcher unwraps
 *       {@code Success} before its existing read and returns null on {@code ErrorList}; the errors
 *       field reads {@code ErrorList.errors}. An un-switched child would read a property off the
 *       {@code Outcome} object itself. Pinned at build time by
 *       {@code GraphitronSchemaValidator.validateOutcomeChildArmSwitch}.</li>
 *   <li><b>Success-projection fields must be nullable.</b> On the {@code ErrorList} arm a data
 *       field resolves null; if its SDL type is non-null, graphql-java raises
 *       {@code NonNullableFieldWasNullError} and bubbles the null up to the outcome field, dropping
 *       the sibling errors field. The typed rejection is
 *       {@code ErrorChannelWalkerError.NonNullableSuccessProjectionField}; its classify-time
 *       enforcement lands with the in-scope flip (the slice-1 commit that flips {@code @service}
 *       fields to the wrapper), where the SDL nullability is reachable. Until then the rule's
 *       message and LSP surface exist but nothing rejects on it.</li>
 * </ul>
 *
 * <p>{@code ErrorList.errors} is {@code List<Object>} because it carries two populations: matched
 * throwables on the catch path and Jakarta {@code ConstraintViolation} objects on the
 * validator pre-step path. The per-{@code @error}-type field DataFetchers read off each element.
 *
 * <p>Emitted as plain classes with accessors rather than a sealed interface with {@code record}
 * arms because the project's JavaPoet fork does not yet expose {@code sealed} / {@code permits} /
 * {@code recordBuilder}; the runtime contract (non-null source, {@code Success}/{@code ErrorList}
 * arms, {@code value()}/{@code errors()} accessors) is unchanged. Generated alongside
 * {@code ErrorRouter} / {@code ErrorMappings}; preserves the rewrite's no-runtime-jar invariant.
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

        // Success<T> implements Outcome<T> { private final T value; Success(T value); T value(); }
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

        // ErrorList<T> implements Outcome<T> { private final List<Object> errors; ...; List<Object> errors(); }
        // T is phantom on this arm (the error path discards the success type); it exists only so the
        // arm satisfies Outcome<T>.
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
