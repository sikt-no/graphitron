package no.sikt.graphitron.definitions.helpers;

import com.palantir.javapoet.CodeBlock;
import graphql.Scalars;
import graphql.scalars.ExtendedScalars;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ScalarUtilsTest {

    @BeforeEach
    void setUp() {
        ScalarUtils.initialize(Set.of());
    }

    @Test
    @DisplayName("Should return set of built-in scalar names")
    void builtInScalarNames() {
        Set<String> builtInScalars = ScalarUtils.getInstance().getBuiltInScalarNames();
        assertThat(builtInScalars).contains("String", "Int", "Boolean");
    }

    @Test
    @DisplayName("Should return map of scalar names to CodeBlocks representing the code to access the scalar field")
    void getScalarTypeCodeBlockMapping() {
        assertThat(ScalarUtils.getInstance().getScalarTypeCodeBlockMapping())
                .containsEntry("BigDecimal", CodeBlock.of("$T.$N", ExtendedScalars.class, "GraphQLBigDecimal"))
                .containsEntry("BigInteger", CodeBlock.of("$T.$N", ExtendedScalars.class, "GraphQLBigInteger"));
    }

    @Test
    @DisplayName("Should return map of scalar names to Java class names")
    void getScalarTypeNameMapping() {
        assertThat(ScalarUtils.getInstance().getScalarTypeNameMapping())
                .containsEntry("Boolean", "java.lang.Boolean") //from Scalars
                .containsEntry("BigDecimal", "java.math.BigDecimal") //from ExtendedScalars
                .containsEntry("_Any", "java.lang.Object"); //from _Any
    }

    @Test
    @DisplayName("Should use custom scalar type mapping for ID scalar instead of default from Scalars")
    void getCustomScalarsTypeNameMappingCustomID() {
        assertThat(ScalarUtils.getInstance().getScalarTypeNameMapping())
                .containsEntry("ID", "java.lang.String") //from CustomScalars
                .doesNotContainEntry("ID", "java.lang.Object"); //from Scalars
    }

    @Test
    @DisplayName("Should return ClassName for valid extended scalar name")
    void getScalarTypeMapping_ValidScalar() {
        var className = ScalarUtils.getInstance().getScalarTypeMapping("BigDecimal");
        assertThat(className.canonicalName()).isEqualTo("java.math.BigDecimal");
    }

    @Test
    @DisplayName("Initialize should add user-provided scalar to the mappings")
    void initialize() {
        //add Scalars to the end of definitions, re-adding the ID scalar from Scalars that overrides the one from CustomScalars
        ScalarUtils.initialize(Set.of(Scalars.class));

        assertThat(ScalarUtils.getInstance().getScalarTypeNameMapping())
                .doesNotContainEntry("ID", "java.lang.String") //from CustomScalars
                .containsEntry("ID", "java.lang.Object"); //from Scalars
    }
}