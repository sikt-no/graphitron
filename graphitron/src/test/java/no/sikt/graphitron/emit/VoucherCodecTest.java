package no.sikt.graphitron.emit;

import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The emitted voucher runtime, compiled at Java 17 and run: a voucher round trips with its values in
 * the JSON kind their scalar serialized them as, two fields in either order sign the same voucher, and
 * every check refuses with its own reason while nothing else does.
 */
@UnitTier
class VoucherCodecTest {

    private static final String PACKAGE = "vouchers";
    private static final Instant ISSUED = Instant.parse("2026-11-12T10:00:00Z");
    private static final Instant EXPIRES = ISSUED.plus(Duration.ofMinutes(5));
    private static final UUID VOUCH = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");

    private static EmittedCodeHarness harness;
    private static Class<?> keysType;
    private static Method ed25519;
    private static Method issuing;
    private static Method accepting;
    private static Method issuer;
    private static Method verifier;
    private static Method sign;
    private static Method verify;
    private static KeyPair pair;
    private static KeyPair other;

    @BeforeAll
    static void compile() throws Exception {
        var units = new HashMap<String, TypeSpec>();
        for (TypeSpec spec : VoucherRuntime.render(PACKAGE)) {
            units.put(PACKAGE + ".schema." + spec.name(), spec);
        }
        harness = EmittedCodeHarness.compileStrictly(units);
        keysType = harness.load(PACKAGE + ".schema.VoucherKeys");
        var codec = harness.load(PACKAGE + ".schema.VoucherCodec");
        ed25519 = keysType.getMethod("ed25519", String.class, PrivateKey.class, Map.class);
        issuing = keysType.getMethod("issuing", String.class, PrivateKey.class);
        accepting = keysType.getMethod("accepting", Map.class);
        issuer = codec.getMethod("issuer", keysType, UUID.class, String.class, String.class, Instant.class);
        verifier = codec.getMethod("verifier", keysType, Instant.class, String.class);
        sign = harness.load(PACKAGE + ".schema.VoucherCodec$Issuer").getMethod("sign", String.class, Map.class, Map.class);
        verify = harness.load(PACKAGE + ".schema.VoucherCodec$Verifier")
            .getMethod("verify", String.class, String.class, String.class, Set.class, Set.class);
        var generator = KeyPairGenerator.getInstance("Ed25519");
        pair = generator.generateKeyPair();
        other = generator.generateKeyPair();
    }

    @AfterAll
    static void close() {
        if (harness != null) harness.close();
    }

    private static Object call(Method method, Object target, Object... args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static Object keys() throws Throwable {
        return call(ed25519, null, "k1", pair.getPrivate(), Map.of("k1", pair.getPublic()));
    }

    private static String issue(Object keys, UUID vouch, String scope, Instant expires, String typeName,
                                Map<String, ?> claims, Map<String, ?> lookup) throws Throwable {
        return (String) call(sign, call(issuer, null, keys, vouch, scope, "alice", expires), typeName, claims, lookup);
    }

    private static String voucher() throws Throwable {
        return issue(keys(), VOUCH, "results", EXPIRES, "Result", Map.of("ownerId", 42), Map.of("id", "r-7"));
    }

    private static Object verifierAt(Object keys, Instant now) throws Throwable {
        return call(verifier, null, keys, now, "alice");
    }

    private static Object check(Object verifier, String typeName, String voucher, String scope,
                                Set<String> claims, Set<String> key) throws Throwable {
        return call(verify, verifier, typeName, voucher, scope, claims, key);
    }

    private static Object check(String voucher) throws Throwable {
        return check(verifierAt(keys(), ISSUED), "Result", voucher, "results", Set.of("ownerId"), Set.of("id"));
    }

    private static Object accessor(Object record, String name) throws Exception {
        var method = record.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(record);
    }

    private static String reasonOf(Throwable thrown) throws Exception {
        assertThat(thrown.getClass().getSimpleName()).isEqualTo("VoucherRejectedException");
        return accessor(thrown, "reason").toString();
    }

    private static void assertRejected(ThrowingCall call, String reason) throws Exception {
        Throwable thrown = null;
        try {
            call.run();
        } catch (Throwable t) {
            thrown = t;
        }
        assertThat(thrown).as("rejected for " + reason).isNotNull();
        assertThat(reasonOf(thrown)).isEqualTo(reason);
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Throwable;
    }

    private static String tamper(String voucher, java.util.function.UnaryOperator<byte[]> change) {
        var bytes = change.apply(Base64.getUrlDecoder().decode(voucher));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Test
    void aVoucherRoundTripsWithItsValuesInTheKindTheirScalarSerialized() throws Throwable {
        var claims = Map.of("ownerId", 42, "region", "north", "active", true);
        var lookup = Map.<String, Object>of("id", 7L << 40, "big", new BigInteger("123456789012345678901234567890"),
            "uuid", "6f1c2b0e-0000-4000-8000-000000000001", "weight", 1.5);
        var voucher = issue(keys(), VOUCH, "results", EXPIRES, "Result", claims, lookup);
        var token = check(verifierAt(keys(), ISSUED), "Result", voucher, "results", claims.keySet(), lookup.keySet());
        assertThat(accessor(token, "vouchId")).isEqualTo(VOUCH);
        assertThat(accessor(token, "scope")).isEqualTo("results");
        assertThat(accessor(token, "caller")).isEqualTo("alice");
        assertThat(accessor(token, "expires")).isEqualTo(EXPIRES);
        assertThat(accessor(token, "keyId")).isEqualTo("k1");
        assertThat(accessor(token, "claims")).isEqualTo(Map.of("active", true, "ownerId", 42, "region", "north"));
        assertThat(List.copyOf(((Map<?, ?>) accessor(token, "claims")).keySet())).isEqualTo(List.of("active", "ownerId", "region"));
        assertThat(accessor(token, "lookup")).isEqualTo(lookup);
        assertThat(((Map<?, ?>) accessor(token, "claims")).get("ownerId")).isInstanceOf(Integer.class);
    }

    @Test
    void aKeyOfTwoFieldsSignsTheSameVoucherWhicheverOrderItsFieldsComeIn() throws Throwable {
        var forward = new LinkedHashMap<String, Object>();
        forward.put("a", 1);
        forward.put("b", "two");
        var backward = new LinkedHashMap<String, Object>();
        backward.put("b", "two");
        backward.put("a", 1);
        var claims = Map.of("ownerId", 42);
        assertThat(issue(keys(), VOUCH, "results", EXPIRES, "Result", claims, forward))
            .isEqualTo(issue(keys(), VOUCH, "results", EXPIRES, "Result", claims, backward));
        check(verifierAt(keys(), ISSUED), "Result", issue(keys(), VOUCH, "results", EXPIRES, "Result", claims, backward),
            "results", Set.of("ownerId"), Set.of("b", "a"));
    }

    @Test
    void aVoucherIsAcceptedWithinTheClockSkewPastItsExpiryAndRefusedBeyondIt() throws Throwable {
        var voucher = voucher();
        check(verifierAt(keys(), EXPIRES.plusSeconds(30)), "Result", voucher, "results", Set.of("ownerId"), Set.of("id"));
        assertRejected(() -> check(verifierAt(keys(), EXPIRES.plusSeconds(31)), "Result", voucher, "results",
            Set.of("ownerId"), Set.of("id")), "EXPIRED");
    }

    @Test
    void eachCheckRefusesWithItsOwnReason() throws Throwable {
        var voucher = voucher();
        var keys = keys();
        assertRejected(() -> check("not a voucher!"), "MALFORMED");
        assertRejected(() -> check(voucher.substring(0, voucher.length() - 20)), "MALFORMED");
        assertRejected(() -> check(tamper(voucher, b -> { b[0] = 2; return b; })), "UNKNOWN_VERSION");
        assertRejected(() -> check(tamper(voucher, b -> { b[b.length - 1] ^= 1; return b; })), "SIGNATURE");
        assertRejected(() -> check(tamper(voucher, b -> replaceLast(b, "r-7", "r-8"))), "SIGNATURE");
        assertRejected(() -> check(verifierAt(call(accepting, null, Map.of("k2", pair.getPublic())), ISSUED), "Result",
            voucher, "results", Set.of("ownerId"), Set.of("id")), "UNKNOWN_KEY");
        assertRejected(() -> check(verifierAt(call(accepting, null, Map.of("k1", other.getPublic())), ISSUED), "Result",
            voucher, "results", Set.of("ownerId"), Set.of("id")), "SIGNATURE");
        assertRejected(() -> check(verifierAt(keys, ISSUED), "Document", voucher, "results", Set.of("ownerId"),
            Set.of("id")), "SIGNATURE");
        assertRejected(() -> check(verifierAt(keys, ISSUED), "Result", voucher, "documents", Set.of("ownerId"),
            Set.of("id")), "SCOPE");
        assertRejected(() -> check(call(verifier, null, keys, ISSUED, "bob"), "Result", voucher, "results",
            Set.of("ownerId"), Set.of("id")), "CALLER");
        assertRejected(() -> check(verifierAt(keys, ISSUED), "Result", voucher, "results", Set.of("tenantId"),
            Set.of("id")), "CLAIMS");
        assertRejected(() -> check(verifierAt(keys, ISSUED), "Result", voucher, "results", Set.of("ownerId", "tenantId"),
            Set.of("id")), "CLAIMS");
        assertRejected(() -> check(verifierAt(keys, ISSUED), "Result", voucher, "results", Set.of(),
            Set.of("id")), "CLAIMS");
        assertRejected(() -> check(verifierAt(keys, ISSUED), "Result", voucher, "results", Set.of("ownerId"),
            Set.of("resultId")), "KEY");
    }

    @Test
    void vouchersSharingAVouchIdMustAgreeOnScopeAndExpiry() throws Throwable {
        var keys = keys();
        var claims = Set.of("ownerId");
        var key = Set.of("id");
        var first = issue(keys, VOUCH, "results", EXPIRES, "Result", Map.of("ownerId", 42), Map.of("id", "r-1"));
        var second = issue(keys, VOUCH, "results", EXPIRES, "Result", Map.of("ownerId", 42), Map.of("id", "r-2"));
        var otherScope = issue(keys, VOUCH, "documents", EXPIRES, "Result", Map.of("ownerId", 42), Map.of("id", "r-3"));
        var otherExpiry = issue(keys, VOUCH, "results", EXPIRES.plusSeconds(1), "Result", Map.of("ownerId", 42),
            Map.of("id", "r-4"));
        var otherVouch = issue(keys, UUID.randomUUID(), "documents", EXPIRES, "Result", Map.of("ownerId", 42),
            Map.of("id", "r-5"));

        var verifier = verifierAt(keys, ISSUED);
        check(verifier, "Result", first, "results", claims, key);
        check(verifier, "Result", second, "results", claims, key);
        check(verifier, "Result", otherVouch, "documents", claims, key);
        assertRejected(() -> check(verifier, "Result", otherScope, "documents", claims, key), "INCONSISTENT");
        assertRejected(() -> check(verifier, "Result", otherExpiry, "results", claims, key), "INCONSISTENT");
    }

    @Test
    void aRejectionNamesTheCheckAndNothingFromTheToken() throws Throwable {
        Throwable thrown = null;
        try {
            check(verifierAt(keys(), ISSUED), "Result", voucher(), "documents", Set.of("ownerId"), Set.of("id"));
        } catch (Throwable t) {
            thrown = t;
        }
        assertThat(thrown).hasMessage("voucher rejected: the voucher was issued for another scope");
    }

    @Test
    void aValueThatIsNoSerializedScalarIsRefusedAtIssue() {
        assertThatThrownBy(() -> issue(keys(), VOUCH, "results", EXPIRES, "Result", Map.of("ownerId", Map.of()),
                Map.of("id", 1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ownerId");
    }

    @Test
    void keysHoldOnlyTheSideTheyWereGiven() throws Throwable {
        var issuingOnly = call(issuing, null, "k1", pair.getPrivate());
        var voucher = issue(issuingOnly, VOUCH, "results", EXPIRES, "Result", Map.of("ownerId", 42), Map.of("id", "r-7"));
        assertRejected(() -> check(verifierAt(issuingOnly, ISSUED), "Result", voucher, "results", Set.of("ownerId"),
            Set.of("id")), "UNKNOWN_KEY");
        var acceptingOnly = call(accepting, null, Map.of("k1", pair.getPublic()));
        check(verifierAt(acceptingOnly, ISSUED), "Result", voucher, "results", Set.of("ownerId"), Set.of("id"));
        assertThatThrownBy(() -> issue(acceptingOnly, VOUCH, "results", EXPIRES, "Result", Map.of("ownerId", 42),
            Map.of("id", "r-7"))).isInstanceOf(IllegalStateException.class);
        var rsa = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        assertThatThrownBy(() -> call(issuing, null, "k1", rsa.getPrivate()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] replaceLast(byte[] bytes, String from, String to) {
        var text = new String(bytes, StandardCharsets.ISO_8859_1);
        int at = text.lastIndexOf(from);
        assertThat(at).isPositive();
        var replaced = text.substring(0, at) + to + text.substring(at + from.length());
        return replaced.getBytes(StandardCharsets.ISO_8859_1);
    }
}
