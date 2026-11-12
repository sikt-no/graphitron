package no.sikt.graphitron.rewrite.generators.util;

import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.emit.ConnectionKeyShape;
import no.sikt.graphitron.emit.SessionIdentityRows;
import no.sikt.graphitron.emit.VoucherRuntime;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The runtime of a build that vouches, compiled at Java 17: every public constructor of the runtime
 * takes the voucher keys last, so a runtime without them does not compile, and the withers keep them;
 * the session state takes the caller first and hands it back.
 */
@UnitTier
class VouchingRuntimeTest {

    private static final String PACKAGE = "com.example";
    private static final String SCHEMA_PACKAGE = PACKAGE + ".schema";

    private static EmittedCodeHarness harness;
    private static Class<?> runtime;
    private static Class<?> keys;
    private static Class<?> sessionState;

    @BeforeAll
    static void compile() {
        var identity = SessionIdentityRows.handleLess();
        Map<String, TypeSpec> units = new LinkedHashMap<>();
        for (TypeSpec spec : ConnectionRuntimeClassGenerator.generate(PACKAGE, identity, ConnectionKeyShape.none(), null, true)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        for (TypeSpec spec : GraphitronTransactionProviderGenerator.generate(PACKAGE)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        for (TypeSpec spec : GraphitronConnectionInstrumentationGenerator.generate(PACKAGE, null, identity, true)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        for (TypeSpec spec : no.sikt.graphitron.rewrite.generators.schema.GraphitronClientExceptionClassGenerator.generate()) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        for (TypeSpec spec : no.sikt.graphitron.rewrite.generators.schema.ErrorRouterClassGenerator.generate(PACKAGE)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        for (TypeSpec spec : VoucherRuntime.render(PACKAGE)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        harness = EmittedCodeHarness.compile(units);
        runtime = harness.load(SCHEMA_PACKAGE + ".GraphitronRuntime");
        keys = harness.load(SCHEMA_PACKAGE + "." + VoucherRuntime.KEYS);
        sessionState = harness.load(SCHEMA_PACKAGE + ".SessionState");
    }

    @AfterAll
    static void close() {
        if (harness != null) harness.close();
    }

    @Test
    void everyPublicRuntimeConstructorTakesTheVoucherKeysLast() {
        var constructors = Arrays.stream(runtime.getDeclaredConstructors())
            .filter(c -> Modifier.isPublic(c.getModifiers()))
            .toList();
        assertThat(constructors).isNotEmpty();
        assertThat(constructors).allSatisfy(c -> {
            var parameters = c.getParameterTypes();
            assertThat(parameters[parameters.length - 1]).isEqualTo(keys);
        });
    }

    @Test
    void theRuntimeHoldsItsKeysThroughEveryWither() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var voucherKeys = keys.getMethod("ed25519", String.class, PrivateKey.class, Map.class)
            .invoke(null, "k1", pair.getPrivate(), Map.<String, PublicKey>of("k1", pair.getPublic()));
        var dataSource = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] {DataSource.class}, (proxy, method, args) -> null);
        var built = runtime.getConstructor(DataSource.class, org.jooq.SQLDialect.class, keys)
            .newInstance(dataSource, org.jooq.SQLDialect.POSTGRES, voucherKeys);
        var limit = harness.load(SCHEMA_PACKAGE + ".BatchSizeLimit");
        var limited = runtime.getMethod("withBatchSizeLimit", limit)
            .invoke(built, Proxy.newProxyInstance(harness.classLoader(), new Class<?>[] {limit},
                (proxy, method, args) -> 1));
        assertThat(runtime.getMethod("voucherKeys").invoke(limited)).isSameAs(voucherKeys);
        assertThatThrownBy(() -> runtime.getConstructor(DataSource.class, org.jooq.SQLDialect.class, keys)
                .newInstance(dataSource, org.jooq.SQLDialect.POSTGRES, null))
            .hasRootCauseInstanceOf(NullPointerException.class);
    }

    @Test
    void theSessionStateTakesTheCallerFirstAndHandsItBack() throws Exception {
        var mount = harness.load(SCHEMA_PACKAGE + ".SessionState$Mount");
        var unmount = harness.load(SCHEMA_PACKAGE + ".SessionState$Unmount");
        var of = sessionState.getMethod("of", String.class, mount, unmount);
        var state = of.invoke(null, "alice",
            Proxy.newProxyInstance(harness.classLoader(), new Class<?>[] {mount}, (p, m, a) -> null),
            Proxy.newProxyInstance(harness.classLoader(), new Class<?>[] {unmount}, (p, m, a) -> null));
        assertThat(sessionState.getMethod("caller").invoke(state)).isEqualTo("alice");
        assertThat(Arrays.stream(sessionState.getMethods()).filter(m -> m.getName().equals("of")))
            .as("one of form per build").hasSize(1);
    }

    @Test
    void aBuildThatDoesNotVouchHasNeitherKeysNorCaller() {
        var plain = ConnectionRuntimeClassGenerator.generate(PACKAGE, SessionIdentityRows.handleLess(),
            ConnectionKeyShape.none(), null);
        assertThat(plain).allSatisfy(spec -> {
            assertThat(spec.methodSpecs()).noneMatch(m -> m.name().equals("voucherKeys") || m.name().equals("caller"));
            assertThat(spec.methodSpecs()).allSatisfy(m -> assertThat(m.parameters())
                .noneMatch(p -> p.type().toString().endsWith(VoucherRuntime.KEYS)));
        });
    }
}
