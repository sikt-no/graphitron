package no.sikt.graphitron.definitions.helpers;

import com.palantir.javapoet.ClassName;
import graphql.scalars.ExtendedScalars;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScalarUtilsTest {

    @AfterEach
    void tearDown() {
        ScalarUtils.setUserProvidedScalars(Map.of());
    }

    @Test
    @DisplayName("Should return set of built-in scalar names")
    void builtInScalarNames() {
        Set<String> builtInScalars = ScalarUtils.getBuiltInScalarNames();
        assertThat(builtInScalars).contains("String", "Int", "Boolean");
    }

    @Test
    @DisplayName("Should return map of extended scalar names to ExtendedScalar.-field names. Excluding those overridden by user-provided scalars")
    void extendedScalars() {
        String dateScalar = ExtendedScalars.Date.getName();
        ScalarUtils.setUserProvidedScalars(Map.of(dateScalar, String.class));

        Map<String, String> extendedScalars = ScalarUtils.getAllExtendedScalarsNotOverriddenByUserProvidedScalars();
        assertThat(extendedScalars)
                .containsEntry("BigDecimal", "GraphQLBigDecimal")
                .containsEntry("Long", "GraphQLLong")
                .doesNotContainKey(dateScalar);
    }

    @Test
    @DisplayName("Should return map of extended scalar names to Java class names")
    void customScalarsTypeNameMapping() {
        Map<String, String> customScalarsTypeNameMapping = ScalarUtils.getCustomScalarsTypeNameMapping();
        assertThat(customScalarsTypeNameMapping).containsEntry("BigDecimal", "java.math.BigDecimal")
                .containsEntry("Long", "java.lang.Long");
    }

    @Test
    @DisplayName("Should return ClassName for valid extended scalar name")
    void getCustomScalarTypeMapping_ValidScalar() {
        ClassName className = ScalarUtils.getCustomScalarTypeMapping("BigDecimal");
        assertThat(className.canonicalName()).isEqualTo("java.math.BigDecimal");
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException for unrecognized scalar name")
    void getCustomScalarTypeMapping_UnrecognizedScalar() {
        assertThatThrownBy(() -> ScalarUtils.getCustomScalarTypeMapping("UnrecognizedScalar"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Scalar type mapping not found for 'UnrecognizedScalar'. Configured scalars: BigDecimal, BigInteger");
    }

    @Test
    @DisplayName("Should add user-provided scalar to the mappings")
    void setUserProvidedScalars() {
        var scalarName = "CustomScalar";
        Class<?> scalarClass = java.time.Duration.class;

        ScalarUtils.setUserProvidedScalars(Map.of(scalarName, scalarClass));

        assertThat(ScalarUtils.getCustomScalarsTypeNameMapping())
                .containsEntry(scalarName, scalarClass.getCanonicalName());

        assertThat(ScalarUtils.getCustomScalarTypeMapping(scalarName).canonicalName())
                .isEqualTo(scalarClass.getCanonicalName());
    }

    @Test
    @DisplayName("User-provided scalar should take precedence over extended scalar")
    void userProvidedScalarTakesPrecedence() {
        var scalarName = ExtendedScalars.GraphQLBigDecimal.getName();
        Class<?> userProvidedClass = Double.class; // extended scalar is java.math.BigDecimal

        ScalarUtils.setUserProvidedScalars(Map.of(scalarName, userProvidedClass));

        assertThat(ScalarUtils.getCustomScalarsTypeNameMapping())
                .containsEntry(scalarName, userProvidedClass.getCanonicalName())
                .doesNotContainEntry(scalarName, "java.math.BigDecimal");

        assertThat(ScalarUtils.getCustomScalarTypeMapping(scalarName).canonicalName())
                .isEqualTo(userProvidedClass.getCanonicalName());
    }
}