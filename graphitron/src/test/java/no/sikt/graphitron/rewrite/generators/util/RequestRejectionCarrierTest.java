package no.sikt.graphitron.rewrite.generators.util;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeSpec;
import graphql.Scalars;
import graphql.execution.DataFetcherResult;
import graphql.execution.ExecutionStepInfo;
import graphql.execution.MergedField;
import graphql.execution.ResultPath;
import graphql.language.Field;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import no.sikt.graphitron.emit.ConnectionKeyShape;
import no.sikt.graphitron.emit.SessionIdentityRows;
import no.sikt.graphitron.emit.VoucherRuntime;
import no.sikt.graphitron.rewrite.generators.schema.ErrorRouterClassGenerator;
import no.sikt.graphitron.rewrite.generators.schema.GraphitronClientExceptionClassGenerator;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one request rejection in the runtime of a build that accepts vouchers, compiled at Java 17: the
 * sealed {@code RequestRejectedException} and its two arms, the carrier's one slot that acquires
 * nothing once a rejection is remembered, the one-vouched-value limit as an admission that answers
 * rather than rejects, with acquisition refusing a vouched key nothing admitted, the error router
 * declining every arm, and a {@code ConnectionKey} whose {@code toString} names its parts and never
 * their values.
 */
@UnitTier
class RequestRejectionCarrierTest {

    private static final String PACKAGE = "com.example";
    private static final String SCHEMA_PACKAGE = PACKAGE + ".schema";

    private static EmittedCodeHarness harness;
    private static Class<?> keyClass;
    private static Class<?> rejectedClass;
    private static Class<?> voucherRejected;
    private static Class<?> acquisitionFailed;

    private final List<Object> asked = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    static void compile() {
        var identity = SessionIdentityRows.handleLess();
        var shape = ConnectionKeyShape.vouchedFor(List.of(
            new ConnectionKeyShape.VouchedForPart("eierId", ClassName.get(String.class)),
            new ConnectionKeyShape.VouchedForPart("orgId", ClassName.get(Integer.class))));
        Map<String, TypeSpec> units = new LinkedHashMap<>();
        for (TypeSpec spec : ConnectionRuntimeClassGenerator.generate(PACKAGE, identity, shape, null, true)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        for (TypeSpec spec : GraphitronTransactionProviderGenerator.generate(PACKAGE)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        for (TypeSpec spec : GraphitronConnectionInstrumentationGenerator.generate(PACKAGE, null, identity, false)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        for (TypeSpec spec : GraphitronClientExceptionClassGenerator.generate()) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        for (TypeSpec spec : ErrorRouterClassGenerator.generate(PACKAGE)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        for (TypeSpec spec : VoucherRuntime.render(PACKAGE)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        harness = EmittedCodeHarness.compile(units);
        keyClass = harness.load(SCHEMA_PACKAGE + ".ConnectionKey");
        rejectedClass = harness.load(SCHEMA_PACKAGE + "." + VoucherRuntime.REQUEST_REJECTED);
        voucherRejected = harness.load(SCHEMA_PACKAGE + "." + VoucherRuntime.REJECTED);
        acquisitionFailed = harness.load(SCHEMA_PACKAGE + ".AcquisitionFailedException");
    }

    @AfterAll
    static void close() {
        if (harness != null) harness.close();
    }

    @BeforeEach
    void reset() {
        asked.clear();
    }

    // --- the sealed rejection ----------------------------------------------------------------------

    @Test
    void theRequestRejectionIsSealedOverItsTwoArms() {
        assertThat(rejectedClass.isSealed()).isTrue();
        assertThat(Modifier.isAbstract(rejectedClass.getModifiers())).isTrue();
        assertThat(rejectedClass.getSuperclass()).isEqualTo(RuntimeException.class);
        assertThat(Arrays.asList(rejectedClass.getPermittedSubclasses()))
            .containsExactlyInAnyOrder(acquisitionFailed, voucherRejected);
        assertThat(List.of(acquisitionFailed, voucherRejected))
            .allSatisfy(arm -> assertThat(rejectedClass.isAssignableFrom(arm)).isTrue());
    }

    @Test
    void eachArmIsARequestRejection_andTheCauseIsTheSourcesForAnAcquisitionAndTheRejectionItselfOtherwise()
            throws Throwable {
        var sourceFault = new java.sql.SQLException("pool exhausted");
        Throwable acquisition = (Throwable) acquisitionFailed.getConstructor(Throwable.class).newInstance(sourceFault);
        Throwable voucher = voucherRejection("SIGNATURE");
        var carrier = harness.load(SCHEMA_PACKAGE + ".OwnedConnections");
        for (Throwable arm : List.of(acquisition, voucher)) {
            assertThat((boolean) invoke(() -> carrier.getMethod("isRequestRejection", Throwable.class)
                .invoke(null, new RuntimeException("wrapped", arm)))).as(arm.getClass().getSimpleName()).isTrue();
        }
        assertThat(rejectionCause(acquisition)).contains(sourceFault);
        assertThat(rejectionCause(voucher)).containsSame(voucher);
        assertThat(rejectionCause(new IllegalStateException("not a rejection"))).isEmpty();
        assertThat(voucher.getMessage()).isEqualTo("voucher rejected: the voucher's signature does not verify for this entity type");
    }

    @Test
    void theErrorRouterDeclinesEveryArm_answeringAPlaceholderWithNoReference() throws Throwable {
        var router = harness.load(SCHEMA_PACKAGE + ".ErrorRouter");
        var sourceFault = new java.sql.SQLException("pool exhausted");
        for (Throwable arm : List.of(
                (Throwable) acquisitionFailed.getConstructor(Throwable.class).newInstance(sourceFault),
                voucherRejection("EXPIRED"))) {
            var answered = (DataFetcherResult<?>) invoke(() -> router
                .getMethod("surfaceClientErrorOrRedact", Throwable.class, DataFetchingEnvironment.class)
                .invoke(null, arm, env()));
            assertThat(answered.getData()).isNull();
            assertThat(answered.getErrors()).singleElement().satisfies(error -> {
                assertThat(error.getMessage()).isEqualTo("The request was rejected.");
                assertThat(error.getMessage()).doesNotContain("Reference");
            });
        }
    }

    // --- the carrier's one slot --------------------------------------------------------------------

    @Test
    void onceARejectionIsRemembered_theCarrierAcquiresNothingMore() throws Throwable {
        var carrier = carrier();
        Throwable rejection = voucherRejection("SCOPE");
        Object remembered = invoke(() -> carrier.getClass().getMethod("reject", rejectedClass).invoke(carrier, rejection));
        assertThat(remembered).isSameAs(rejection);
        Object later = invoke(() -> carrier.getClass().getMethod("reject", rejectedClass)
            .invoke(carrier, voucherRejection("EXPIRED")));
        assertThat(later).as("the first rejection wins").isSameAs(rejection);

        assertThatThrownBy(() -> dslFor(carrier, defaultKey())).isSameAs(rejection);
        assertThatThrownBy(() -> dslFor(carrier, vouchedKey("olivia", 1))).isSameAs(rejection);
        assertThat(asked).isEmpty();
        assertThat(requestRejection(carrier)).containsSame(rejection);
        releaseAll(carrier);
    }

    @Test
    void oneVouchedKeyPerRequest_theFirstAdmitted_aSecondAnsweredNotAdmitted_andNothingRejected() throws Throwable {
        for (var order : List.of(List.of("olivia", "peter"), List.of("peter", "olivia"))) {
            asked.clear();
            var carrier = carrier();
            Object first = vouchedKey(order.get(0), 1);
            Object second = vouchedKey(order.get(1), 1);
            assertThat(admitVouched(carrier, defaultKey())).as("a key without vouched-for parts").isTrue();
            assertThat(admitVouched(carrier, first)).isTrue();
            assertThat(admitVouched(carrier, first)).as("the admitted key again").isTrue();
            assertThat(admitVouched(carrier, second)).isFalse();
            assertThat(admitVouched(carrier, vouchedKey(order.get(0), 2))).as("another value of a second part").isFalse();
            assertThat(asked).as("admission acquires nothing").isEmpty();
            dslFor(carrier, defaultKey());
            dslFor(carrier, first);
            assertThat(asked).containsExactly(defaultKey(), first);
            assertThat(requestRejection(carrier)).as("the limit is never a request rejection").isEmpty();
            releaseAll(carrier);
        }
    }

    @Test
    void acquisitionRefusesAVouchedKeyNothingAdmitted_asAGeneratorFault() throws Throwable {
        var carrier = carrier();
        Object olivia = vouchedKey("olivia", 1);
        assertThatThrownBy(() -> dslFor(carrier, olivia)).as("nothing admitted yet")
            .isInstanceOf(IllegalStateException.class);
        assertThat(admitVouched(carrier, olivia)).isTrue();
        assertThatThrownBy(() -> dslFor(carrier, vouchedKey("peter", 1))).as("another key than the admitted one")
            .isInstanceOf(IllegalStateException.class);
        assertThat(asked).isEmpty();
        assertThat(requestRejection(carrier)).isEmpty();
        releaseAll(carrier);
    }

    // --- the key ------------------------------------------------------------------------------------

    @Test
    void theKeyNamesItsPartsAndNeverTheirValues() throws Throwable {
        assertThat(vouchedKey("olivia", 42)).hasToString("ConnectionKey[eierId, orgId]");
        assertThat(defaultKey()).hasToString("ConnectionKey[]");
        assertThat((boolean) keyClass.getMethod("hasVouchedFor").invoke(vouchedKey("olivia", 42))).isTrue();
        assertThat((boolean) keyClass.getMethod("hasVouchedFor").invoke(defaultKey())).isFalse();
        assertThat(vouchedKey("olivia", 42)).isEqualTo(vouchedKey("olivia", 42)).isNotEqualTo(vouchedKey("peter", 42));
        assertThatThrownBy(() -> invoke(() -> keyClass.getConstructor(Optional.class, Optional.class)
                .newInstance(Optional.of("olivia"), Optional.empty())))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // --- helpers -------------------------------------------------------------------------------------

    private Throwable voucherRejection(String reason) throws Exception {
        Class<?> reasonClass = harness.load(SCHEMA_PACKAGE + "." + VoucherRuntime.REJECTED + "$Reason");
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object constant = Enum.valueOf((Class) reasonClass, reason);
        return (Throwable) voucherRejected.getConstructor(reasonClass).newInstance(constant);
    }

    private static Object defaultKey() throws Exception {
        return keyClass.getMethod("defaultSource").invoke(null);
    }

    private static Object vouchedKey(String owner, int org) throws Exception {
        return keyClass.getMethod("forVouchedFor", String.class, Integer.class).invoke(null, owner, org);
    }

    @SuppressWarnings("unchecked")
    private static Optional<Object> rejectionCause(Throwable thrown) throws Throwable {
        var carrier = harness.load(SCHEMA_PACKAGE + ".OwnedConnections");
        return (Optional<Object>) invoke(() -> carrier.getMethod("rejectionCause", Throwable.class).invoke(null, thrown));
    }

    @SuppressWarnings("unchecked")
    private static Optional<Object> requestRejection(Object carrier) throws Throwable {
        return (Optional<Object>) invoke(() -> carrier.getClass().getMethod("requestRejection").invoke(carrier));
    }

    private Object carrier() throws Throwable {
        Class<?> runtimeClass = harness.load(SCHEMA_PACKAGE + ".GraphitronRuntime");
        Class<?> keysClass = harness.load(SCHEMA_PACKAGE + "." + VoucherRuntime.KEYS);
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Object keys = keysClass.getMethod("ed25519", String.class, PrivateKey.class, Map.class)
            .invoke(null, "k1", pair.getPrivate(), Map.<String, PublicKey>of("k1", pair.getPublic()));
        Object runtime = runtimeClass.getConstructor(SQLDialect.class, keysClass).newInstance(SQLDialect.POSTGRES, keys);
        Class<?> providerClass = harness.load(SCHEMA_PACKAGE + ".GraphitronTransactionProvider");
        Class<?> policy = Arrays.stream(providerClass.getDeclaredClasses())
            .filter(c -> c.getSimpleName().equals("CommitPolicy")).findFirst().orElseThrow();
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object commit = Enum.valueOf((Class) policy, "COMMIT");
        return harness.load(SCHEMA_PACKAGE + ".OwnedConnections")
            .getConstructor(runtimeClass, policy, harness.load(SCHEMA_PACKAGE + ".ConnectionSource"))
            .newInstance(runtime, commit, source());
    }

    /** A source recording every key it is asked for, leasing a connection that does nothing. */
    private Object source() {
        Class<?> sourceInterface = harness.load(SCHEMA_PACKAGE + ".ConnectionSource");
        Class<?> leaseClass = harness.load(SCHEMA_PACKAGE + ".Lease");
        Class<?> releaseClass = harness.load(SCHEMA_PACKAGE + ".Lease$Release");
        return Proxy.newProxyInstance(harness.classLoader(), new Class<?>[]{sourceInterface},
            (proxy, method, args) -> switch (method.getName()) {
                case "acquire" -> {
                    asked.add(args[0]);
                    Object release = Proxy.newProxyInstance(harness.classLoader(), new Class<?>[]{releaseClass},
                        (p, m, a) -> switch (m.getName()) {
                            case "hashCode" -> System.identityHashCode(p);
                            case "equals" -> p == a[0];
                            case "toString" -> "Release";
                            default -> null;
                        });
                    yield leaseClass.getMethod("of", Connection.class, releaseClass).invoke(null, connection(), release);
                }
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "RecordingSource";
                default -> null;
            });
    }

    private Connection connection() {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getAutoCommit", "isValid" -> true;
                case "isClosed" -> false;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "Connection";
                default -> null;
            });
    }

    private static boolean admitVouched(Object carrier, Object key) throws Throwable {
        return (boolean) invoke(() -> carrier.getClass().getMethod("admitVouched", keyClass).invoke(carrier, key));
    }

    private static void dslFor(Object carrier, Object key) throws Throwable {
        invoke(() -> carrier.getClass().getMethod("dslFor", keyClass).invoke(carrier, key));
    }

    private static void releaseAll(Object carrier) throws Throwable {
        invoke(() -> carrier.getClass().getMethod("releaseAll").invoke(carrier));
    }

    private static DataFetchingEnvironment env() {
        return DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
            .graphQLContext(graphql.GraphQLContext.newContext().build())
            .mergedField(MergedField.newMergedField(Field.newField("_entities").build()).build())
            .executionStepInfo(ExecutionStepInfo.newExecutionStepInfo()
                .type(Scalars.GraphQLString)
                .path(ResultPath.rootPath().segment("_entities").segment(0))
                .build())
            .build();
    }

    private static Object invoke(ThrowingSupplier body) throws Throwable {
        try {
            return body.get();
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        Object get() throws Exception;
    }
}
