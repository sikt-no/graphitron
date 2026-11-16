package no.sikt.graphitron.rewrite.generators.util;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.generators.schema.GraphitronClientExceptionClassGenerator;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Generates the {@code TableAliases} utility class, emitted once per code-generation run: the one
 * place a generated statement names a table it reads inside a correlated subquery.
 *
 * <p>A nested read aliases each table it declares under the table it is correlated to, so a
 * subquery over the same table as an enclosing one never shadows it. The alias is a readable,
 * length-capped name minted at generation time
 * ({@link no.sikt.graphitron.render.TableAliasFragments#literal}) followed by a nesting depth
 * marker, {@code _d<n>}, where {@code n} is one more than the marker on the enclosing alias (an
 * enclosing alias with no marker, a root table, counts as depth 0). Depth strictly increases along
 * every chain of enclosing scopes, so an alias never equals one it is nested in, and the name stays
 * short however deep the selection nests.
 *
 * <p>Nesting past {@link #MAX_DEPTH_DEFAULT} levels is refused with the generated
 * {@code GraphitronClientException} while the statement is being built, before any SQL reaches the
 * database: the depth of an inline read is chosen by the client, and so is the size of the one
 * statement it becomes. The check lives here rather than in a host's instrumentation so it holds
 * whether or not the host installs a depth limit of its own.
 *
 * <p>Generated as a source file so consuming projects have no runtime dependency on Graphitron.
 */
public final class TableAliasesClassGenerator {

    public static final String CLASS_NAME = "TableAliases";

    /** The deepest nesting a generated statement accepts; deeper selections are refused. */
    public static final int MAX_DEPTH_DEFAULT = 32;

    private static final String DEPTH_MARK = "_d";

    private static final ClassName TABLE = ClassName.get("org.jooq", "Table");

    private TableAliasesClassGenerator() {}

    public static List<TypeSpec> generate(String outputPackage) {
        var clientException = ClassName.get(outputPackage + ".schema",
            GraphitronClientExceptionClassGenerator.CLASS_NAME);
        var anyTable = ParameterizedTypeName.get(TABLE, WildcardTypeName.subtypeOf(Object.class));

        var maxDepth = FieldSpec.builder(int.class, "MAX_DEPTH",
                Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .initializer("$L", MAX_DEPTH_DEFAULT)
            .addJavadoc("The deepest nesting of correlated reads one statement accepts.\n")
            .build();

        var nested = MethodSpec.methodBuilder("nested")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(String.class)
            .addParameter(anyTable, "enclosing")
            .addParameter(String.class, "name")
            .addJavadoc("The alias of a table read inside a subquery correlated to {@code enclosing}:\n"
                + "{@code name} followed by a depth one deeper than {@code enclosing}'s, so it never\n"
                + "equals an alias it is nested in. Refuses a selection nested past {@link #MAX_DEPTH}.\n")
            .addStatement("int depth = depthOf(enclosing.getName()) + 1")
            .beginControlFlow("if (depth > MAX_DEPTH)")
            .addStatement("throw new $T($S + MAX_DEPTH + $S)", clientException,
                "The query nests related data more than ",
                " levels deep in one read; select fewer nested levels or split the query.")
            .endControlFlow()
            .addStatement("return name + $S + depth", DEPTH_MARK)
            .build();

        var depthOf = MethodSpec.methodBuilder("depthOf")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(int.class)
            .addParameter(String.class, "alias")
            .addJavadoc("The depth marker an alias {@link #nested} minted ends in, or 0 for any other name.\n")
            .addStatement("int mark = alias.lastIndexOf($S)", DEPTH_MARK)
            .addStatement("int start = mark + $L", DEPTH_MARK.length())
            .beginControlFlow("if (mark < 0 || start == alias.length() || alias.length() - start > 3)")
            .addStatement("return 0")
            .endControlFlow()
            .beginControlFlow("for (int i = start; i < alias.length(); i++)")
            .beginControlFlow("if (alias.charAt(i) < '0' || alias.charAt(i) > '9')")
            .addStatement("return 0")
            .endControlFlow()
            .endControlFlow()
            .addStatement("return $T.parseInt(alias.substring(start))", Integer.class)
            .build();

        var spec = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("Names the tables a generated statement reads inside correlated subqueries, so a\n"
                + "nested read never shadows the row it is correlated to, and bounds how deep one\n"
                + "statement nests.\n")
            .addField(maxDepth)
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
            .addMethod(nested)
            .addMethod(depthOf)
            .build();
        return List.of(spec);
    }
}
