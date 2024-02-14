package no.fellesstudentsystem.graphitron.generators.codebuilding;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.TypeName;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.generators.abstractions.AbstractMethodGenerator;
import no.fellesstudentsystem.graphitron.generators.dependencies.Dependency;
import no.fellesstudentsystem.graphql.naming.GraphQLReservedName;
import org.jetbrains.annotations.NotNull;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.asListedName;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.asRecordName;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.*;
import static no.fellesstudentsystem.graphitron.generators.db.FetchCountDBMethodGenerator.TOTAL_COUNT_NAME;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;
import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * Class containing various helper methods for constructing code with javapoet.
 */
public class FormatCodeBlocks {
    private final static CodeBlock
            COLLECT_TO_LIST = CodeBlock.of(".collect($T.toList())", COLLECTORS.className),
            DECLARE_CONTEXT_VARIABLE = CodeBlock.of(
                    "var $L = $T.selectContext($N, this.$N)",
                    Dependency.CONTEXT_NAME,
                    RESOLVER_HELPERS.className,
                    AbstractMethodGenerator.ENV_NAME,
                    Dependency.CONTEXT_NAME
            ),
            FIND_FIRST = CodeBlock.of(".stream().findFirst()"),
            EMPTY_LIST = CodeBlock.of("$T.of()", LIST.className),
            EMPTY_SET = CodeBlock.of("$T.of()", SET.className),
            EMPTY_MAP = CodeBlock.of("$T.of()", MAP.className),
            EMPTY_BLOCK = CodeBlock.builder().build();

    /**
     * @param variableName The name of the ArrayList variable.
     * @param typeName The parameter type of the ArrayList to declare.
     * @return CodeBlock that declares a new ArrayList variable.
     */
    @NotNull
    public static CodeBlock declareArrayList(String variableName, TypeName typeName) {
        return CodeBlock
                .builder()
                .addStatement("var $L = new $T<$T>()", asListedName(variableName), ARRAY_LIST.className, typeName)
                .build();
    }

    /**
     * @param name Name of a field that should be declared as a record. This will be the name of the variable.
     * @param recordTypeName TypeName of the jOOQ record that should be declared.
     * @return CodeBlock that declares a new record variable and that attaches context configuration.
     */
    public static CodeBlock declareRecord(String name, TypeName recordTypeName) {
        var recordName = asRecordName(name);
        return CodeBlock
                .builder()
                .add(declareVariable(recordName, recordTypeName))
                .addStatement("$N.attach($N.configuration())", recordName, Dependency.CONTEXT_NAME)
                .build();
    }

    /**
     * @param name Name of the variable.
     * @param typeName The type of the variable to declare.
     * @return CodeBlock that declares a simple variable.
     */
    public static CodeBlock declareVariable(String name, TypeName typeName) {
        return CodeBlock.builder().addStatement("var $L = new $T()", name, typeName).build();
    }

    /**
     * @return CodeBlock that contains an if statement with a null check on the provided name.
     */
    @NotNull
    public static CodeBlock ifNotNull(String name) {
        return CodeBlock.of("if ($N != null)", name);
    }

    /**
     * @return empty CodeBlock
     */
    public static CodeBlock empty() {
        return EMPTY_BLOCK;
    }

    /**
     * @param addTarget Name of updatable collection to add something to.
     * @param addition The name of the content that should be added.
     * @return CodeBlock that adds something to an updatable collection.
     */
    @NotNull
    public static CodeBlock addToList(String addTarget, String addition) {
        return CodeBlock.builder().addStatement("$N.add($N)", addTarget, addition).build();
    }

    /**
     * @param addTarget Name of updatable collection to add something to.
     * @param codeAddition The CodeBlock that provides something that should be added.
     * @return CodeBlock that adds something to an updatable collection.
     */
    @NotNull
    public static CodeBlock addToList(String addTarget, CodeBlock codeAddition) {
        return CodeBlock.builder().addStatement("$N.add($L)", addTarget, codeAddition).build();
    }

    /**
     * @return CodeBlock that creates an empty List.
     */
    @NotNull
    public static CodeBlock listOf() {
        return EMPTY_LIST;
    }

    /**
     * @return CodeBlock that wraps the supplied CodeBlock in a List.
     */
    @NotNull
    public static CodeBlock listOf(CodeBlock code) {
        return CodeBlock.of("$T.of($L)", LIST.className, code);
    }

    /**
     * @return CodeBlock that wraps the supplied variable name in a List.
     */
    @NotNull
    public static CodeBlock listOf(String variable) {
        return CodeBlock.of("$T.of($N)", LIST.className, variable);
    }

    /**
     * @return CodeBlock that creates an empty Set.
     */
    @NotNull
    public static CodeBlock setOf() {
        return EMPTY_SET;
    }

    /**
     * @return CodeBlock that wraps the supplied CodeBlock in a Set.
     */
    @NotNull
    public static CodeBlock setOf(CodeBlock code) {
        return CodeBlock.of("$T.of($L)", SET.className, code);
    }

    /**
     * @return CodeBlock that wraps the supplied variable name in a Set.
     */
    @NotNull
    public static CodeBlock setOf(String variable) {
        return CodeBlock.of("$T.of($N)", SET.className, variable);
    }

    /**
     * @return CodeBlock that creates an empty Map.
     */
    @NotNull
    public static CodeBlock mapOf() {
        return EMPTY_MAP;
    }

    /**
     * @return CodeBlock that wraps this method name in a method call format.
     */
    public static CodeBlock asMethodCall(String method) {
        return CodeBlock.of("." + method + "()");
    }

    /**
     * @return CodeBlock that wraps the supplied CodeBlock in a Map.
     */
    @NotNull
    public static CodeBlock mapOf(CodeBlock code) {
        return CodeBlock.of("$T.of($L)", MAP.className, code);
    }

    /**
     * @return CodeBlock that adds a collect to List call to be used on a Stream.
     */
    @NotNull
    public static CodeBlock collectToList() {
        return COLLECT_TO_LIST;
    }

    /**
     * @return CodeBlock that adds a findFirst call to be used on a collection.
     */
    @NotNull
    public static CodeBlock findFirst() {
        return FIND_FIRST;
    }

    /**
     * @return CodeBlock that wraps the provided variable name in a simple null check.
     */
    @NotNull
    public static CodeBlock nullIfNullElse(String variable) {
        return CodeBlock.of("$N == null ? null : ", variable);
    }

    /**
     * @return CodeBlock that wraps the provided CodeBlock name in a simple null check.
     */
    @NotNull
    public static CodeBlock nullIfNullElse(CodeBlock code) {
        return CodeBlock.of("$L == null ? null : ", code);
    }

    /**
     * @return CodeBlock that declares a resolver context variable with a check for null.
     */
    @NotNull
    public static CodeBlock declareContextVariable() {
        return DECLARE_CONTEXT_VARIABLE;
    }


    /**
     * @return CodeBlock consisting of a function for a count DB call.
     */
    @NotNull
    public static CodeBlock countDBFunction(String queryLocation, String queryMethodName, String inputList) {
        return CodeBlock.of(
                "($L, $L) -> $N.contains($S) ? $N.count$L($N$L) : null",
                IDS_NAME,
                SELECTION_SET_NAME,
                SELECTION_SET_NAME,
                TOTAL_COUNT_NAME,
                uncapitalize(queryLocation),
                capitalize(queryMethodName),
                Dependency.CONTEXT_NAME,
                inputList.isEmpty() ? "" : ", " + inputList
        );
    }

    /**
     * @return CodeBlock consisting of a function for a generic DB call.
     */
    @NotNull
    public static CodeBlock queryDBFunction(String queryLocation, String queryMethodName, String inputList, boolean hasIds, boolean usesIds) {
        return CodeBlock.of(
                "($L$L) -> $N.$L($N$L$L, $N)",
                hasIds ? IDS_NAME + ", " : "",
                SELECTION_SET_NAME,
                uncapitalize(queryLocation),
                queryMethodName,
                Dependency.CONTEXT_NAME,
                usesIds ? ", " + IDS_NAME : "",
                inputList.isEmpty() ? "" : ", " + inputList,
                SELECTION_SET_NAME
        );
    }

    /**
     * @return CodeBlock consisting of a declaration of the page size variable through a method call.
     */
    @NotNull
    public static CodeBlock declarePageSize(int defaultFirst) {
        return CodeBlock.builder().addStatement(
                "int $L = $T.getPageSize($N, $L, $L)",
                PAGE_SIZE_NAME,
                RESOLVER_HELPERS.className,
                GraphQLReservedName.PAGINATION_FIRST.getName(),
                GeneratorConfig.getMaxAllowedPageSize(),
                defaultFirst
        ).build();
    }

    /**
     * @return CodeBlock that wraps and returns the provided variable in a CompletableFuture.
     */
    @NotNull
    public static CodeBlock returnCompletedFuture(String variable) {
        return CodeBlock
                .builder()
                .addStatement("return $T.completedFuture($N)", COMPLETABLE_FUTURE.className, variable)
                .build();
    }

    /**
     * @return CodeBlock that wraps and returns the provided CodeBlock in a CompletableFuture.
     */
    @NotNull
    public static CodeBlock returnCompletedFuture(CodeBlock code) {
        return CodeBlock
                .builder()
                .addStatement("return $T.completedFuture($L)", COMPLETABLE_FUTURE.className, code)
                .build();
    }
}
