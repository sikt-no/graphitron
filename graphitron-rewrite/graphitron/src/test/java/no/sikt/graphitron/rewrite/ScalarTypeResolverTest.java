package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.model.CoercingDeclarationKind;
import no.sikt.graphitron.rewrite.model.ScalarResolution;
import no.sikt.graphitron.rewrite.scalarfixture.Money;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for {@link ScalarTypeResolver}. The spec built-in path and the
 * consumer-supplied reflection path are exercised here so the resolver's Rejected arms each
 * carry the typed payload downstream consumers (validator, LSP fix-its) read.
 */
@UnitTier
class ScalarTypeResolverTest {

    private static final String FIXTURE_PKG = "no.sikt.graphitron.rewrite.scalarfixture";
    private static final ClassLoader LOADER = ScalarTypeResolverTest.class.getClassLoader();

    // ===== Spec built-ins =====

    @Test
    void resolveBuiltIn_int_returnsInteger() {
        ScalarResolution result = ScalarTypeResolver.resolveBuiltIn("Int");

        assertThat(result).isInstanceOfSatisfying(ScalarResolution.Resolved.class, r -> {
            assertThat(r.javaType()).isEqualTo(ClassName.get(Integer.class));
            assertThat(r.scalarConstantOwner()).isEqualTo(ClassName.get("graphql", "Scalars"));
            assertThat(r.scalarConstantField()).isEqualTo("GraphQLInt");
        });
    }

    @Test
    void resolveBuiltIn_float_returnsDouble() {
        ScalarResolution result = ScalarTypeResolver.resolveBuiltIn("Float");

        assertThat(result).isInstanceOfSatisfying(ScalarResolution.Resolved.class, r -> {
            assertThat(r.javaType()).isEqualTo(ClassName.get(Double.class));
            assertThat(r.scalarConstantField()).isEqualTo("GraphQLFloat");
        });
    }

    @Test
    void resolveBuiltIn_string_returnsString() {
        ScalarResolution result = ScalarTypeResolver.resolveBuiltIn("String");

        assertThat(result).isInstanceOfSatisfying(ScalarResolution.Resolved.class, r -> {
            assertThat(r.javaType()).isEqualTo(ClassName.get(String.class));
            assertThat(r.scalarConstantField()).isEqualTo("GraphQLString");
        });
    }

    @Test
    void resolveBuiltIn_boolean_returnsBoolean() {
        ScalarResolution result = ScalarTypeResolver.resolveBuiltIn("Boolean");

        assertThat(result).isInstanceOfSatisfying(ScalarResolution.Resolved.class, r -> {
            assertThat(r.javaType()).isEqualTo(ClassName.get(Boolean.class));
            assertThat(r.scalarConstantField()).isEqualTo("GraphQLBoolean");
        });
    }

    @Test
    void resolveBuiltIn_id_returnsString() {
        // ID is the carve-out: graphql-java's GraphqlIDCoercing implements Coercing<Object, Object>,
        // so routing it through the consumer-Coercing path would mis-classify it as erased. The
        // resolver's built-in table maps "ID" → java.lang.String, matching trunk behaviour.
        ScalarResolution result = ScalarTypeResolver.resolveBuiltIn("ID");

        assertThat(result).isInstanceOfSatisfying(ScalarResolution.Resolved.class, r -> {
            assertThat(r.javaType()).isEqualTo(ClassName.get(String.class));
            assertThat(r.scalarConstantField()).isEqualTo("GraphQLID");
        });
    }

    @Test
    void resolveBuiltIn_unknownName_returnsFieldNotFound() {
        // Phase 1 has no directive / convention layer yet; non-built-in names produce a typed
        // rejection so Phase 2/3 consumers can route through the same surface.
        ScalarResolution result = ScalarTypeResolver.resolveBuiltIn("BigDecimal");

        assertThat(result).isEqualTo(new ScalarResolution.Rejected.FieldNotFound("graphql.Scalars", "BigDecimal"));
    }

    @Test
    void isSpecBuiltIn_matchesBuiltInsOnly() {
        assertThat(ScalarTypeResolver.isSpecBuiltIn("Int")).isTrue();
        assertThat(ScalarTypeResolver.isSpecBuiltIn("Float")).isTrue();
        assertThat(ScalarTypeResolver.isSpecBuiltIn("String")).isTrue();
        assertThat(ScalarTypeResolver.isSpecBuiltIn("Boolean")).isTrue();
        assertThat(ScalarTypeResolver.isSpecBuiltIn("ID")).isTrue();
        assertThat(ScalarTypeResolver.isSpecBuiltIn("BigDecimal")).isFalse();
        assertThat(ScalarTypeResolver.isSpecBuiltIn("DateTime")).isFalse();
    }

    @Test
    void builtInJavaType_returnsNullForUnknown() {
        // builtInJavaType is the convenience callers use when they don't need the
        // GraphQLScalarType constant; null means "not a built-in", same as the legacy
        // RowsMethodShape.standardScalarJavaType contract.
        assertThat(ScalarTypeResolver.builtInJavaType("Int")).isEqualTo(ClassName.get(Integer.class));
        assertThat(ScalarTypeResolver.builtInJavaType("ID")).isEqualTo(ClassName.get(String.class));
        assertThat(ScalarTypeResolver.builtInJavaType("BigDecimal")).isNull();
    }

    // ===== Consumer-supplied path: Resolved =====

    @Test
    void resolveFromConstantFqn_wellFormedNamedCoercing_returnsResolved() {
        ScalarResolution result = ScalarTypeResolver.resolveFromConstantFqn(
            FIXTURE_PKG + ".ScalarConstants", "MONEY", LOADER);

        assertThat(result).isInstanceOfSatisfying(ScalarResolution.Resolved.class, r -> {
            assertThat(r.javaType()).isEqualTo(ClassName.get(Money.class));
            assertThat(r.scalarConstantOwner()).isEqualTo(ClassName.get(FIXTURE_PKG, "ScalarConstants"));
            assertThat(r.scalarConstantField()).isEqualTo("MONEY");
        });
    }

    // ===== Consumer-supplied path: Rejected arms =====

    @Test
    void resolveFromConstantFqn_unknownClass_returnsClassNotFound() {
        ScalarResolution result = ScalarTypeResolver.resolveFromConstantFqn(
            "does.not.exist.Class", "FOO", LOADER);

        assertThat(result).isEqualTo(new ScalarResolution.Rejected.ClassNotFound("does.not.exist.Class"));
    }

    @Test
    void resolveFromConstantFqn_unknownField_returnsFieldNotFound() {
        ScalarResolution result = ScalarTypeResolver.resolveFromConstantFqn(
            "graphql.Scalars", "GraphQLDoesNotExist", LOADER);

        assertThat(result).isEqualTo(new ScalarResolution.Rejected.FieldNotFound(
            "graphql.Scalars", "GraphQLDoesNotExist"));
    }

    @Test
    void resolveFromConstantFqn_packagePrivateField_returnsFieldNotAccessible() {
        ScalarResolution result = ScalarTypeResolver.resolveFromConstantFqn(
            FIXTURE_PKG + ".InaccessibleConstants", "PACKAGE_PRIVATE_STATIC", LOADER);

        assertThat(result).isEqualTo(new ScalarResolution.Rejected.FieldNotAccessible(
            FIXTURE_PKG + ".InaccessibleConstants", "PACKAGE_PRIVATE_STATIC",
            /* isPublic */ false, /* isStatic */ true));
    }

    @Test
    void resolveFromConstantFqn_publicInstanceField_returnsFieldNotAccessible() {
        ScalarResolution result = ScalarTypeResolver.resolveFromConstantFqn(
            FIXTURE_PKG + ".InaccessibleConstants", "publicInstance", LOADER);

        assertThat(result).isEqualTo(new ScalarResolution.Rejected.FieldNotAccessible(
            FIXTURE_PKG + ".InaccessibleConstants", "publicInstance",
            /* isPublic */ true, /* isStatic */ false));
    }

    @Test
    void resolveFromConstantFqn_nullField_returnsNullAtCodegen() {
        ScalarResolution result = ScalarTypeResolver.resolveFromConstantFqn(
            FIXTURE_PKG + ".MisconfiguredConstants", "NULL_SCALAR", LOADER);

        assertThat(result).isEqualTo(new ScalarResolution.Rejected.NullAtCodegen(
            FIXTURE_PKG + ".MisconfiguredConstants", "NULL_SCALAR"));
    }

    @Test
    void resolveFromConstantFqn_nonScalarTypeField_returnsNotAScalarType() {
        ScalarResolution result = ScalarTypeResolver.resolveFromConstantFqn(
            FIXTURE_PKG + ".MisconfiguredConstants", "NOT_A_SCALAR", LOADER);

        assertThat(result).isEqualTo(new ScalarResolution.Rejected.NotAScalarType(
            FIXTURE_PKG + ".MisconfiguredConstants", "NOT_A_SCALAR", "java.lang.String"));
    }

    @Test
    void resolveFromConstantFqn_anonymousCoercing_returnsCoercingErasedAnonymous() {
        ScalarResolution result = ScalarTypeResolver.resolveFromConstantFqn(
            FIXTURE_PKG + ".ScalarConstants", "ANONYMOUS_MONEY", LOADER);

        assertThat(result).isInstanceOfSatisfying(ScalarResolution.Rejected.CoercingErased.class, c -> {
            assertThat(c.declarationKind()).isEqualTo(CoercingDeclarationKind.ANONYMOUS_CLASS);
            assertThat(c.coercingClass()).contains("ScalarConstants");
        });
    }

    @Test
    void resolveFromConstantFqn_rawCoercing_returnsCoercingErasedRaw() {
        ScalarResolution result = ScalarTypeResolver.resolveFromConstantFqn(
            FIXTURE_PKG + ".ScalarConstants", "RAW_MONEY", LOADER);

        assertThat(result).isEqualTo(new ScalarResolution.Rejected.CoercingErased(
            FIXTURE_PKG + ".RawCoercing", CoercingDeclarationKind.RAW_TYPE));
    }

    @Test
    void resolveFromConstantFqn_erasedNamedCoercing_returnsCoercingErasedNamed() {
        ScalarResolution result = ScalarTypeResolver.resolveFromConstantFqn(
            FIXTURE_PKG + ".ScalarConstants", "ERASED_NAMED_MONEY", LOADER);

        assertThat(result).isEqualTo(new ScalarResolution.Rejected.CoercingErased(
            FIXTURE_PKG + ".ErasedNamedCoercing", CoercingDeclarationKind.ERASED_NAMED_CLASS));
    }
}
