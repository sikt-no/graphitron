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
 * nothing once a rejection is remembered, the cap on the operation's vouched-for set as an admission
 * that answers rather than rejects (the first values in order fill it, the runtime's
 * {@code VouchedValueLimit} is asked once, failing closed below 1 and leaving the default where it
 * throws), the set closing into the operation's one vouched key, with acquisition refusing any other
 * vouched key, the error router declining every arm, and a {@code ConnectionKey} of two vouched-for
 * fields whose rows and key name their fields and never their values.
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
        var limit = ConnectionRuntimeClassGenerator.renderVouchedValueLimit(PACKAGE);
        units.put(SCHEMA_PACKAGE + "." + limit.name(), limit);
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
        assertThatThrownBy(() -> dslFor(carrier, vouchedKey(row("olivia", 1)))).isSameAs(rejection);
        assertThat(asked).isEmpty();
        assertThat(requestRejection(carrier)).containsSame(rejection);
        releaseAll(carrier);
    }

    // --- the operation's vouched-for set ------------------------------------------------------------

    @Test
    void theFirstValuesInOrderFillTheSetUpToTheCap_aRepeatedValueIsAdmittedAgain_andNothingIsRejected()
            throws Throwable {
        var carrier = carrier(env -> 2);
        assertThat(admitVouched(carrier, row("olivia", 1))).isTrue();
        assertThat(admitVouched(carrier, row("peter", 1))).isTrue();
        assertThat(admitVouched(carrier, row("olivia", 1))).as("an admitted value again").isTrue();
        assertThat(admitVouched(carrier, row("quinn", 1))).as("past the cap").isFalse();
        assertThat(admitVouched(carrier, row("olivia", 2))).as("another row, though one part was admitted").isFalse();
        assertThat(asked).as("admission acquires nothing").isEmpty();
        Object key = closeVouched(carrier);
        assertThat(key).isEqualTo(vouchedKey(row("peter", 1), row("olivia", 1)));
        assertThat(vouchedForOf(key)).as("in admission order").containsExactly(row("olivia", 1), row("peter", 1));
        dslFor(carrier, defaultKey());
        dslFor(carrier, key);
        assertThat(asked).as("one lease for every admitted value").containsExactly(defaultKey(), key);
        assertThat(requestRejection(carrier)).as("the cap is never a request rejection").isEmpty();
        releaseAll(carrier);
    }

    @Test
    void aCapOfOneAdmitsTheFirstValueInRepresentationOrderAlone() throws Throwable {
        for (var order : List.of(List.of("olivia", "peter"), List.of("peter", "olivia"))) {
            var carrier = carrier(env -> 1);
            assertThat(admitVouched(carrier, row(order.get(0), 1))).isTrue();
            assertThat(admitVouched(carrier, row(order.get(1), 1))).isFalse();
            assertThat(admitVouched(carrier, row(order.get(0), 1))).isTrue();
            assertThat(closeVouched(carrier)).isEqualTo(vouchedKey(row(order.get(0), 1)));
            releaseAll(carrier);
        }
    }

    @Test
    void theLimitIsAskedOncePerOperation_withTheEnvironmentOfTheFirstAdmission() throws Throwable {
        var envs = Collections.synchronizedList(new ArrayList<DataFetchingEnvironment>());
        IntLimit limit = env -> {
            envs.add(env);
            return 5;
        };
        var first = env();
        var carrier = carrier(limit);
        assertThat(admitVouched(carrier, row("olivia", 1), first)).isTrue();
        assertThat(admitVouched(carrier, row("peter", 1), env())).isTrue();
        assertThat(admitVouched(carrier, row("quinn", 1), env())).isTrue();
        assertThat(envs).singleElement().isSameAs(first);
        var another = carrier(limit);
        assertThat(admitVouched(another, row("olivia", 1))).isTrue();
        assertThat(envs).as("asked again for another operation").hasSize(2);
    }

    @Test
    void withoutALimitAnOperationAdmitsTheDefault() throws Throwable {
        var carrier = carrier(null);
        for (int i = 0; i < ConnectionRuntimeClassGenerator.DEFAULT_VOUCHED_VALUE_LIMIT; i++) {
            assertThat(admitVouched(carrier, row("owner" + i, i))).as("value %d", i).isTrue();
        }
        assertThat(admitVouched(carrier, row("one too many", -1))).isFalse();
        assertThat(ConnectionRuntimeClassGenerator.DEFAULT_VOUCHED_VALUE_LIMIT).isEqualTo(1000);
        assertThat(harness.load(SCHEMA_PACKAGE + ".GraphitronRuntime").getField("DEFAULT_VOUCHED_VALUE_LIMIT")
            .getInt(null)).isEqualTo(1000);
    }

    @Test
    void anAnswerBelowOneAdmitsNoValue_failingClosed() throws Throwable {
        for (int answer : new int[]{0, -1, Integer.MIN_VALUE}) {
            var carrier = carrier(env -> answer);
            assertThat(admitVouched(carrier, row("olivia", 1))).as("answer %d", answer).isFalse();
            assertThatThrownBy(() -> closeVouched(carrier)).as("nothing admitted leaves the set open")
                .isInstanceOf(IllegalStateException.class);
            assertThat(admitVouched(carrier, row("olivia", 1))).isFalse();
        }
    }

    @Test
    void aLimitThatThrowsLeavesTheDefaultStanding_andIsLoggedOnceNamingItsClassAndNoMessage() throws Throwable {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SCHEMA_PACKAGE + ".ErrorRouter");
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var carrier = carrier(env -> {
                throw new IllegalStateException("secret detail");
            });
            for (int i = 0; i < ConnectionRuntimeClassGenerator.DEFAULT_VOUCHED_VALUE_LIMIT; i++) {
                assertThat(admitVouched(carrier, row("owner" + i, i))).isTrue();
            }
            assertThat(admitVouched(carrier, row("one too many", -1))).isFalse();
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.ERROR);
                assertThat(event.getFormattedMessage())
                    .startsWith(ConnectionRuntimeClassGenerator.VOUCHED_VALUE_LIMIT_CLASS_NAME + " ")
                    .contains(IllegalStateException.class.getName())
                    .contains("path = " + ResultPath.rootPath().segment("_entities").segment(0))
                    .doesNotContain("secret detail");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void onceClosed_aValueInTheSetIsAdmittedAndNoOtherWhateverRoomTheCapHas() throws Throwable {
        var carrier = carrier(null);
        assertThat(admitVouched(carrier, row("olivia", 1))).isTrue();
        Object key = closeVouched(carrier);
        assertThat(admitVouched(carrier, row("olivia", 1))).as("a later lookup field's value in the set").isTrue();
        assertThat(admitVouched(carrier, row("peter", 1))).as("the key is a value, so it cannot grow").isFalse();
        assertThat(closeVouched(carrier)).as("the same key on every close").isEqualTo(key);
        assertThat(vouchedForOf(key)).containsExactly(row("olivia", 1));
        releaseAll(carrier);
    }

    @Test
    void acquisitionRefusesAVouchedKeyOtherThanTheOperationsVouchedKey_asAGeneratorFault() throws Throwable {
        var carrier = carrier(null);
        Object olivia = vouchedKey(row("olivia", 1));
        assertThatThrownBy(() -> dslFor(carrier, olivia)).as("nothing closed yet")
            .isInstanceOf(IllegalStateException.class);
        assertThat(admitVouched(carrier, row("olivia", 1))).isTrue();
        assertThat(closeVouched(carrier)).isEqualTo(olivia);
        assertThatThrownBy(() -> dslFor(carrier, vouchedKey(row("peter", 1)))).as("another key than the operation's")
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> dslFor(carrier, vouchedKey(row("olivia", 1), row("peter", 1))))
            .as("a larger set than the operation's").isInstanceOf(IllegalStateException.class);
        dslFor(carrier, olivia);
        assertThat(asked).containsExactly(olivia);
        assertThat(requestRejection(carrier)).isEmpty();
        releaseAll(carrier);
    }

    // --- the key ------------------------------------------------------------------------------------

    @Test
    void theKeyIsASetOfRows_eachNamingItsFieldsAndNeverTheirValues() throws Throwable {
        Object key = vouchedKey(row("olivia", 42), row("peter", 7));
        assertThat(key).hasToString("ConnectionKey[eierId, orgId]");
        assertThat(row("olivia", 42)).hasToString("VouchedFor[eierId, orgId]");
        assertThat(String.valueOf(vouchedForOf(key))).doesNotContain("olivia").doesNotContain("42");
        assertThat(defaultKey()).hasToString("ConnectionKey[]");
        assertThat(vouchedForOf(defaultKey())).isEmpty();
        assertThat((boolean) keyClass.getMethod("hasVouchedFor").invoke(key)).isTrue();
        assertThat((boolean) keyClass.getMethod("hasVouchedFor").invoke(defaultKey())).isFalse();
        assertThat(key).as("a set: order does not matter")
            .isEqualTo(vouchedKey(row("peter", 7), row("olivia", 42)))
            .isNotEqualTo(vouchedKey(row("olivia", 42)))
            .isNotEqualTo(vouchedKey(row("olivia", 7), row("peter", 42)));
        assertThatThrownBy(() -> vouchedKey()).as("a vouched key is never empty")
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> vouchedForOf(key).add(row("quinn", 1)))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> invoke(() -> vouchedForClass().getConstructor(String.class, Integer.class)
                .newInstance(null, 1)))
            .isInstanceOf(NullPointerException.class);
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

    private static Class<?> vouchedForClass() {
        return harness.load(SCHEMA_PACKAGE + ".ConnectionKey$VouchedFor");
    }

    /** One voucher's vouched-for row: the owner and the organisation, in the shape's name order. */
    private static Object row(String owner, int org) throws Exception {
        return vouchedForClass().getConstructor(String.class, Integer.class).newInstance(owner, org);
    }

    private static Object vouchedKey(Object... rows) throws Throwable {
        var values = new java.util.LinkedHashSet<>(Arrays.asList(rows));
        return invoke(() -> keyClass.getMethod("forVouchedFor", java.util.Set.class).invoke(null, values));
    }

    @SuppressWarnings("unchecked")
    private static java.util.Set<Object> vouchedForOf(Object key) throws Exception {
        return (java.util.Set<Object>) keyClass.getMethod("vouchedFor").invoke(key);
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

    /** The test's stand-in for the generated {@code VouchedValueLimit}, proxied onto it in {@link #carrier}. */
    @FunctionalInterface
    private interface IntLimit {
        int maxVouchedValues(DataFetchingEnvironment env);
    }

    private Object carrier() throws Throwable {
        return carrier(null);
    }

    /** A carrier over a runtime given {@code limit} as its {@code VouchedValueLimit}, or none where null. */
    private Object carrier(IntLimit limit) throws Throwable {
        Class<?> runtimeClass = harness.load(SCHEMA_PACKAGE + ".GraphitronRuntime");
        Class<?> keysClass = harness.load(SCHEMA_PACKAGE + "." + VoucherRuntime.KEYS);
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Object keys = keysClass.getMethod("ed25519", String.class, PrivateKey.class, Map.class)
            .invoke(null, "k1", pair.getPrivate(), Map.<String, PublicKey>of("k1", pair.getPublic()));
        Object runtime = runtimeClass.getConstructor(SQLDialect.class, keysClass).newInstance(SQLDialect.POSTGRES, keys);
        if (limit != null) {
            Class<?> limitClass = harness.load(SCHEMA_PACKAGE + "."
                + ConnectionRuntimeClassGenerator.VOUCHED_VALUE_LIMIT_CLASS_NAME);
            Object hook = Proxy.newProxyInstance(harness.classLoader(), new Class<?>[]{limitClass},
                (proxy, method, args) -> switch (method.getName()) {
                    case "maxVouchedValues" -> limit.maxVouchedValues((DataFetchingEnvironment) args[0]);
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "TestLimit";
                    default -> null;
                });
            runtime = runtimeClass.getMethod("withVouchedValueLimit", limitClass).invoke(runtime, hook);
        }
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

    private static boolean admitVouched(Object carrier, Object value) throws Throwable {
        return admitVouched(carrier, value, env());
    }

    private static boolean admitVouched(Object carrier, Object value, DataFetchingEnvironment env) throws Throwable {
        return (boolean) invoke(() -> carrier.getClass()
            .getMethod("admitVouched", vouchedForClass(), DataFetchingEnvironment.class).invoke(carrier, value, env));
    }

    private static Object closeVouched(Object carrier) throws Throwable {
        return invoke(() -> carrier.getClass().getMethod("closeVouched").invoke(carrier));
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
