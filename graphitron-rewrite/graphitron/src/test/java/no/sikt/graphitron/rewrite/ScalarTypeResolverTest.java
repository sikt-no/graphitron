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

    // ===== Directive value parsing (resolveFromDirectiveValue) =====

    @Test
    void resolveFromDirectiveValue_wellFormedRef_delegatesToResolveFromConstantFqn() {
        ScalarResolution result = ScalarTypeResolver.resolveFromDirectiveValue(
            FIXTURE_PKG + ".ScalarConstants.MONEY", LOADER);

        assertThat(result).isInstanceOfSatisfying(ScalarResolution.Resolved.class, r -> {
            assertThat(r.javaType()).isEqualTo(ClassName.get(Money.class));
            assertThat(r.scalarConstantField()).isEqualTo("MONEY");
        });
    }

    @Test
    void resolveFromDirectiveValue_noDot_returnsClassNotFoundPointingAtValueAsWritten() {
        ScalarResolution result = ScalarTypeResolver.resolveFromDirectiveValue("NoDots", LOADER);

        assertThat(result).isEqualTo(new ScalarResolution.Rejected.ClassNotFound("NoDots"));
    }

    @Test
    void resolveFromDirectiveValue_trailingDot_returnsClassNotFoundPointingAtValueAsWritten() {
        ScalarResolution result = ScalarTypeResolver.resolveFromDirectiveValue("graphql.Scalars.", LOADER);

        assertThat(result).isEqualTo(new ScalarResolution.Rejected.ClassNotFound("graphql.Scalars."));
    }

    @Test
    void resolveFromDirectiveValue_missingClass_returnsClassNotFoundForClassPart() {
        ScalarResolution result = ScalarTypeResolver.resolveFromDirectiveValue("does.not.exist.Class.FIELD", LOADER);

        assertThat(result).isEqualTo(new ScalarResolution.Rejected.ClassNotFound("does.not.exist.Class"));
    }

    // ===== Federation-namespace recognition =====

    @Test
    void isFederationNamespaceScalar_recognisesFieldSet() {
        assertThat(ScalarTypeResolver.isFederationNamespaceScalar("federation__FieldSet")).isTrue();
    }

    @Test
    void isFederationNamespaceScalar_rejectsUnknownName() {
        assertThat(ScalarTypeResolver.isFederationNamespaceScalar("federation__NoSuchThing")).isFalse();
    }

    @Test
    void resolveFederationNamespaceScalar_returnsStringResolution() {
        ScalarResolution result = ScalarTypeResolver.resolveFederationNamespaceScalar("federation__FieldSet");

        assertThat(result).isInstanceOfSatisfying(ScalarResolution.Resolved.class, r -> {
            assertThat(r.javaType()).isEqualTo(ClassName.get(String.class));
            assertThat(r.scalarConstantOwner()).isEqualTo(ClassName.get("graphql", "Scalars"));
            assertThat(r.scalarConstantField()).isEqualTo("GraphQLString");
        });
    }

    @Test
    void resolveFederationNamespaceScalar_unknown_returnsFieldNotFound() {
        ScalarResolution result = ScalarTypeResolver.resolveFederationNamespaceScalar("Unknown");

        assertThat(result).isInstanceOf(ScalarResolution.Rejected.FieldNotFound.class);
    }

    // ===== Phase 3: convention layer =====

    @Test
    void conventionTable_recognisesBigDecimal() {
        assertThat(ScalarTypeResolver.conventionTable()).containsKey("BigDecimal");
        assertThat(ScalarTypeResolver.conventionTable()).containsKey("GraphQLBigDecimal");
    }

    @Test
    void conventionTable_rejectsUnknownName() {
        assertThat(ScalarTypeResolver.conventionTable()).doesNotContainKey("NotAnExtendedScalar");
    }

    @Test
    void resolveByConvention_bigDecimal_resolvesAgainstExtendedScalars() {
        // ExtendedScalars is on the test classpath; the convention table points BigDecimal at
        // graphql.scalars.ExtendedScalars.GraphQLBigDecimal whose Coercing's I parameter is
        // java.math.BigDecimal. The Resolved arm carries the boxed Java type and the owner +
        // field name for additionalType registration.
        ScalarResolution result = ScalarTypeResolver.resolveByConvention("BigDecimal", LOADER);

        assertThat(result).isInstanceOfSatisfying(ScalarResolution.Resolved.class, r -> {
            assertThat(r.javaType()).isEqualTo(ClassName.get(java.math.BigDecimal.class));
            assertThat(r.scalarConstantOwner())
                .isEqualTo(ClassName.get("graphql.scalars", "ExtendedScalars"));
            assertThat(r.scalarConstantField()).isEqualTo("GraphQLBigDecimal");
        });
    }

    @Test
    void resolveByConvention_aliasedName_resolvesToSameConstant() {
        // The bare-name and GraphQL-prefixed forms target the same static field.
        ScalarResolution bare = ScalarTypeResolver.resolveByConvention("BigDecimal", LOADER);
        ScalarResolution prefixed = ScalarTypeResolver.resolveByConvention("GraphQLBigDecimal", LOADER);

        assertThat(bare).isEqualTo(prefixed);
    }

    @Test
    void resolveByConvention_uuid_recoversUuidJavaType() {
        ScalarResolution result = ScalarTypeResolver.resolveByConvention("UUID", LOADER);

        assertThat(result).isInstanceOfSatisfying(ScalarResolution.Resolved.class, r -> {
            assertThat(r.javaType()).isEqualTo(ClassName.get(java.util.UUID.class));
            assertThat(r.scalarConstantField()).isEqualTo("UUID");
        });
    }

    @Test
    void resolveByConvention_nameNotInTable_returnsFieldNotFoundNamingExtendedScalars() {
        // The miss shape distinguishes "name not in table" (FieldNotFound naming ExtendedScalars)
        // from "name in table but artifact missing" (ClassNotFound). Callers route on the arm to
        // decide whether to suggest adding the artifact vs. adding @scalarType.
        ScalarResolution result = ScalarTypeResolver.resolveByConvention("NotAnExtendedScalar", LOADER);

        assertThat(result).isEqualTo(new ScalarResolution.Rejected.FieldNotFound(
            "graphql.scalars.ExtendedScalars", "NotAnExtendedScalar"));
    }

    // ===== Phase 3: convention-table drift signal =====

    /**
     * Reflects on {@code graphql.scalars.ExtendedScalars}' public static fields and asserts every
     * exposed {@link graphql.schema.GraphQLScalarType} constant is either covered by the
     * convention table or named in the explicit-exclusion list. The intent is purely as a drift
     * signal: when {@code extended-scalars} bumps and adds a new constant, this test fails and
     * the maintainer decides whether to add a convention entry (typical) or extend the
     * exclusions list (rare).
     */
    @Test
    void conventionTable_coversEveryExtendedScalarsField() throws Exception {
        Class<?> cls = Class.forName("graphql.scalars.ExtendedScalars");
        java.util.Set<String> reflectedFqns = new java.util.LinkedHashSet<>();
        for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
            int mods = f.getModifiers();
            if (!java.lang.reflect.Modifier.isPublic(mods) || !java.lang.reflect.Modifier.isStatic(mods)) continue;
            if (!graphql.schema.GraphQLScalarType.class.isAssignableFrom(f.getType())) continue;
            reflectedFqns.add(cls.getName() + "." + f.getName());
        }

        java.util.Set<String> covered =
            java.util.Set.copyOf(new java.util.LinkedHashSet<>(ScalarTypeResolver.conventionTable().values()));
        java.util.Set<String> exclusions = java.util.Set.of(
            // Coercing<Object, Object> — graphitron's resolver treats Object as unbound. Consumers
            // wanting JSON or unstructured Object should declare a typed wrapper and reach for
            // @scalarType.
            "graphql.scalars.ExtendedScalars.Object",
            "graphql.scalars.ExtendedScalars.Json"
        );

        java.util.Set<String> uncovered = new java.util.LinkedHashSet<>(reflectedFqns);
        uncovered.removeAll(covered);
        uncovered.removeAll(exclusions);

        assertThat(uncovered).as(
            "ExtendedScalars exposes constants not in the convention table and not on the "
                + "exclusion list. Either add a convention entry (SDL-name → FQN) or extend the "
                + "exclusions list with a justification."
        ).isEmpty();
    }

    @Test
    void conventionTable_everyAuthoredFqnResolvesAgainstExtendedScalars() {
        // Inverse direction: any FQN we author must actually exist on the library at the resolver's
        // declared version. Phantom entries (typo, library rename) surface here as ClassNotFound or
        // FieldNotFound rejections instead of being discovered by a consumer.
        for (String name : ScalarTypeResolver.conventionTable().keySet()) {
            ScalarResolution result = ScalarTypeResolver.resolveByConvention(name, LOADER);
            assertThat(result)
                .as("convention entry '%s' must resolve against extended-scalars on the test classpath", name)
                .isInstanceOf(ScalarResolution.Resolved.class);
        }
    }
}
