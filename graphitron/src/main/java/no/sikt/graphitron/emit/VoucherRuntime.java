package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.WildcardTypeName;

import no.sikt.graphitron.model.read.StoreHandle;

import javax.lang.model.element.Modifier;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_MINTED_KEY;

/**
 * The voucher runtime a vouching build writes into its {@code schema} package: the codec that owns the
 * token format, the key provider it signs and verifies with, and the two arms of the request rejection a
 * voucher can fail the request with, a failed check and a second vouched-for value in one request. What
 * the four classes say depends on nothing but the package they land in, so they are rendered whole;
 * whether a build writes them is the store's question, answered by {@link #roles}: a build that issues or
 * accepts writes them.
 *
 * <p>The format, version 2: base64url without padding over the version byte, the 16-byte vouch id, the
 * scope, the expiry in epoch seconds, the key id, the vouched-for section, the lookup section,
 * and then the signature. A string is its UTF-8 length as an {@code int} and its bytes. A section is
 * its entry count and its entries in strictly ascending name order, each the field name, a tag naming
 * the JSON kind of the value the field's scalar serialized ({@code S} string, {@code I} integer,
 * {@code F} decimal, {@code B} boolean) and the value's text, so the accepting side's scalar parses
 * the value exactly as it would a plain representation's. The signature covers every byte before it
 * and the entity type name, which the token does not carry, so a voucher for one type is refused as
 * another's. Generated code compiles at Java 17 and depends on the JDK alone; Ed25519 is the JDK's.
 */
public final class VoucherRuntime {

    /** The codec's simple name. */
    public static final String CODEC = "VoucherCodec";
    /** The key provider's simple name. */
    public static final String KEYS = "VoucherKeys";
    /** The simple name of the codec's per-operation issuers, nested in the codec. */
    public static final String ISSUERS = "Issuers";
    /** The rejection's simple name. */
    public static final String REJECTED = "VoucherRejectedException";
    /** The simple name of the rejection a second vouched-for value in one request fails it with. */
    public static final String LIMIT = "VouchedValueLimitExceededException";
    /** The simple name of the sealed request rejection both arms extend, in the connection runtime. */
    public static final String REQUEST_REJECTED = "RequestRejectedException";
    /** What the limit's rejection says: the limit, and nothing of any voucher. */
    public static final String LIMIT_MESSAGE =
        "The request presents vouchers for more than one vouched-for value; a request may carry vouchers for one";

    /** The token format version this runtime writes and the only one it reads. */
    public static final int VERSION = 2;

    /** How far past its expiry a voucher is still accepted, absorbing clock skew between the subgraphs. */
    public static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    private static final TypeName OBJECT_MAP =
        ParameterizedTypeName.get(ClassName.get(Map.class), ClassName.get(String.class), ClassName.get(Object.class));
    private static final TypeName WIRE_MAP = ParameterizedTypeName.get(ClassName.get(Map.class),
        ClassName.get(String.class), WildcardTypeName.subtypeOf(Object.class));
    private static final TypeName STRING_SET =
        ParameterizedTypeName.get(ClassName.get(Set.class), ClassName.get(String.class));

    private VoucherRuntime() {}

    /**
     * What a graph does with vouchers, read once per run and handed to every generator the answer
     * changes. {@code issues}: the issuers and their per-operation wiring. {@code accepts}: the voucher
     * alternative and its dispatch. Either: the codec, the keys, the rejection arms and the runtime's
     * voucher keys.
     */
    public record Roles(boolean issues, boolean accepts) {
        /** A graph that neither issues nor accepts. */
        public static Roles none() {
            return new Roles(false, false);
        }

        /** Whether the build writes the voucher runtime: whether it issues or accepts. */
        public boolean any() {
            return issues || accepts;
        }
    }

    /**
     * What the store's graph does with vouchers: whether it mints an {@code ISSUE} key on any type, and
     * whether it mints an {@code ACCEPT} key. The minted key is the stored decision that a marker
     * applies, made once by the {@code minted-keys} stage, and a marker it would not mint a key for (one
     * in a graph with no federation link) is a defect that fails the build before anything is emitted.
     */
    public static Roles roles(StoreHandle store) {
        var roles = store.dsl().selectDistinct(GRAPHITRON_MINTED_KEY.ROLE)
            .from(GRAPHITRON_MINTED_KEY)
            .where(GRAPHITRON_MINTED_KEY.GRAPH_NAME.eq(store.graphName()),
                GRAPHITRON_MINTED_KEY.ROLE.in("ISSUE", "ACCEPT"))
            .fetchSet(GRAPHITRON_MINTED_KEY.ROLE);
        return new Roles(roles.contains("ISSUE"), roles.contains("ACCEPT"));
    }

    /**
     * The four classes, each landing in {@code <outputPackage>.schema}: the codec, the keys, the failed
     * check's rejection and the limit's. Both rejections extend the connection runtime's sealed
     * {@code RequestRejectedException}, which permits them in a build that writes this runtime.
     */
    public static List<TypeSpec> render(String outputPackage) {
        String pkg = outputPackage + ".schema";
        var keys = ClassName.get(pkg, KEYS);
        var rejected = ClassName.get(pkg, REJECTED);
        var requestRejected = ClassName.get(pkg, REQUEST_REJECTED);
        return List.of(codec(ClassName.get(pkg, CODEC), keys, rejected), keys(keys),
            rejected(rejected, requestRejected), limit(ClassName.get(pkg, LIMIT), requestRejected));
    }

    private static TypeSpec limit(ClassName self, ClassName requestRejected) {
        return TypeSpec.classBuilder(self)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .superclass(requestRejected)
            .addJavadoc("A request presented vouchers for more than one vouched-for value: one request reads on one\n"
                + "vouched connection. It rejects the request as a whole, so the limit is never mistaken for\n"
                + "a denial; the message names the limit and nothing of any voucher.\n")
            .addField(FieldSpec.builder(long.class, "serialVersionUID", Modifier.PRIVATE, Modifier.STATIC,
                Modifier.FINAL).initializer("1L").build())
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addStatement("super($S)", LIMIT_MESSAGE)
                .build())
            .build();
    }

    private static TypeSpec rejected(ClassName self, ClassName requestRejected) {
        var reason = self.nestedClass("Reason");
        var reasonEnum = TypeSpec.enumBuilder("Reason")
            .addModifiers(Modifier.PUBLIC)
            .addJavadoc("The check a rejected voucher failed.\n")
            .addEnumConstant("MALFORMED", constant("the voucher is not a well-formed token"))
            .addEnumConstant("UNKNOWN_VERSION", constant("the voucher's format version is not one this build reads"))
            .addEnumConstant("UNKNOWN_KEY", constant("the voucher names a key id with no verification key"))
            .addEnumConstant("SIGNATURE", constant("the voucher's signature does not verify for this entity type"))
            .addEnumConstant("EXPIRED", constant("the voucher has expired"))
            .addEnumConstant("SCOPE", constant("the voucher was issued for another scope"))
            .addEnumConstant("VOUCHED_FOR", constant("the voucher's vouched-for section names other fields than the accepting field's vouchesFor"))
            .addEnumConstant("KEY", constant("the voucher's lookup part names other fields than the entity's key"))
            .addEnumConstant("VALUE", constant("a value in the voucher is not a value of its field's column"))
            .addField(String.class, "check", Modifier.PRIVATE, Modifier.FINAL)
            .addMethod(MethodSpec.constructorBuilder()
                .addParameter(String.class, "check")
                .addStatement("this.check = check")
                .build())
            .addMethod(MethodSpec.methodBuilder("check")
                .addModifiers(Modifier.PUBLIC)
                .returns(String.class)
                .addJavadoc("What failed, in words; never anything read from the token.\n")
                .addStatement("return check")
                .build())
            .build();
        return TypeSpec.classBuilder(self)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .superclass(requestRejected)
            .addJavadoc("A voucher failed verification. It rejects the request as a whole before any connection is\n"
                + "taken. The message names the check that failed and nothing from the token, since a voucher\n"
                + "is a credential.\n")
            .addType(reasonEnum)
            .addField(FieldSpec.builder(long.class, "serialVersionUID", Modifier.PRIVATE, Modifier.STATIC,
                Modifier.FINAL).initializer("1L").build())
            .addField(reason, "reason", Modifier.PRIVATE, Modifier.FINAL)
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(reason, "reason")
                .addStatement("super($S + reason.check())", "voucher rejected: ")
                .addStatement("this.reason = reason")
                .build())
            .addMethod(MethodSpec.methodBuilder("reason")
                .addModifiers(Modifier.PUBLIC)
                .returns(reason)
                .addJavadoc("The check that failed.\n")
                .addStatement("return reason")
                .build())
            .build();
    }

    private static TypeSpec constant(String check) {
        return TypeSpec.anonymousClassBuilder("$S", check).build();
    }

    private static TypeSpec keys(ClassName self) {
        var verificationKeys = ParameterizedTypeName.get(ClassName.get(Map.class), ClassName.get(String.class),
            ClassName.get(PublicKey.class));
        var ed25519 = MethodSpec.methodBuilder("ed25519")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(self)
            .addJavadoc("Keys over the JDK's Ed25519: vouchers are signed with {@code signingKey} under\n"
                + "{@code signingKeyId}, and verified with the public key their key id names, so rotation\n"
                + "adds a key id. Either side may be absent: a subgraph that only accepts holds no signing\n"
                + "key, one that only issues no verification keys. A key that is not an Ed25519 key is\n"
                + "refused here rather than at the first voucher.\n"
                + "\n"
                + "@param signingKeyId the key id stamped on issued vouchers, or null with no signing key\n"
                + "@param signingKey the private key vouchers are signed with, or null\n"
                + "@param verificationKeys the public keys vouchers are verified with, by key id\n")
            .addParameter(String.class, "signingKeyId")
            .addParameter(PrivateKey.class, "signingKey")
            .addParameter(verificationKeys, "verificationKeys")
            .addCode(CodeBlock.builder()
                .beginControlFlow("if ((signingKeyId == null) != (signingKey == null))")
                .addStatement("throw new $T($S)", IllegalArgumentException.class,
                    "a signing key and its key id are given together or not at all")
                .endControlFlow()
                .addStatement("$T verifying = $T.copyOf(verificationKeys)", verificationKeys, Map.class)
                .beginControlFlow("try")
                .beginControlFlow("if (signingKey != null)")
                .addStatement("$T.getInstance(ALGORITHM).initSign(signingKey)", Signature.class)
                .endControlFlow()
                .beginControlFlow("for ($T key : verifying.values())", PublicKey.class)
                .addStatement("$T.getInstance(ALGORITHM).initVerify(key)", Signature.class)
                .endControlFlow()
                .nextControlFlow("catch ($T e)", GeneralSecurityException.class)
                .addStatement("throw new $T($S, e)", IllegalArgumentException.class, "not an Ed25519 key")
                .endControlFlow()
                .add("return new $T() {\n", self).indent()
                .add("@$T\n", Override.class)
                .add("public $T signingKeyId() {\n", String.class).indent()
                .addStatement("requireSigning(signingKey)")
                .addStatement("return signingKeyId")
                .unindent().add("}\n\n")
                .add("@$T\n", Override.class)
                .add("public byte[] sign(byte[] message) {\n").indent()
                .addStatement("requireSigning(signingKey)")
                .beginControlFlow("try")
                .addStatement("$T signature = $T.getInstance(ALGORITHM)", Signature.class, Signature.class)
                .addStatement("signature.initSign(signingKey)")
                .addStatement("signature.update(message)")
                .addStatement("return signature.sign()")
                .nextControlFlow("catch ($T e)", GeneralSecurityException.class)
                .addStatement("throw new $T($S, e)", IllegalStateException.class, "signing a voucher failed")
                .endControlFlow()
                .unindent().add("}\n\n")
                .add("@$T\n", Override.class)
                .add("public boolean hasVerificationKey($T keyId) {\n", String.class).indent()
                .addStatement("return verifying.containsKey(keyId)")
                .unindent().add("}\n\n")
                .add("@$T\n", Override.class)
                .add("public boolean verify($T keyId, byte[] message, byte[] signature) {\n", String.class).indent()
                .addStatement("$T key = verifying.get(keyId)", PublicKey.class)
                .beginControlFlow("if (key == null)")
                .addStatement("return false")
                .endControlFlow()
                .beginControlFlow("try")
                .addStatement("$T verifier = $T.getInstance(ALGORITHM)", Signature.class, Signature.class)
                .addStatement("verifier.initVerify(key)")
                .addStatement("verifier.update(message)")
                .addStatement("return verifier.verify(signature)")
                .nextControlFlow("catch ($T e)", SignatureException.class)
                .addStatement("return false")
                .nextControlFlow("catch ($T e)", GeneralSecurityException.class)
                .addStatement("throw new $T($S, e)", IllegalStateException.class, "verifying a voucher failed")
                .endControlFlow()
                .unindent().add("}\n")
                .unindent().add("};\n")
                .build())
            .build();
        var requireSigning = MethodSpec.methodBuilder("requireSigning")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addParameter(PrivateKey.class, "signingKey")
            .beginControlFlow("if (signingKey == null)")
            .addStatement("throw new $T($S)", IllegalStateException.class, "these voucher keys hold no signing key")
            .endControlFlow()
            .build();
        return TypeSpec.interfaceBuilder(self)
            .addModifiers(Modifier.PUBLIC)
            .addJavadoc("The keys vouchers are signed and verified with: a signing key on the issuing side,\n"
                + "verification keys by key id on the accepting side, so the public key can sit with the\n"
                + "accepting subgraph and rotation adds a key id. {@link #ed25519} is the default over the\n"
                + "JDK; implement this to sign elsewhere, for example through a remote signing service.\n")
            .addField(FieldSpec.builder(String.class, "ALGORITHM", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .addJavadoc("The JDK signature algorithm the default keys use.\n")
                .initializer("$S", "Ed25519").build())
            .addMethod(MethodSpec.methodBuilder("signingKeyId")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(String.class)
                .addJavadoc("The key id stamped on the vouchers {@link #sign} signs.\n")
                .build())
            .addMethod(MethodSpec.methodBuilder("sign")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(byte[].class)
                .addParameter(byte[].class, "message")
                .addJavadoc("Signs {@code message} with the signing key.\n")
                .build())
            .addMethod(MethodSpec.methodBuilder("hasVerificationKey")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(boolean.class)
                .addParameter(String.class, "keyId")
                .addJavadoc("Whether a verification key is held under {@code keyId}.\n")
                .build())
            .addMethod(MethodSpec.methodBuilder("verify")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(boolean.class)
                .addParameter(String.class, "keyId")
                .addParameter(byte[].class, "message")
                .addParameter(byte[].class, "signature")
                .addJavadoc("Whether {@code signature} is the signature of {@code message} under the verification\n"
                    + "key {@code keyId} names; false where no key is held under it.\n")
                .build())
            .addMethod(MethodSpec.methodBuilder("issuing")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(self)
                .addJavadoc("Ed25519 keys that sign and verify nothing: for a subgraph that only issues.\n")
                .addParameter(String.class, "signingKeyId")
                .addParameter(PrivateKey.class, "signingKey")
                .addStatement("return ed25519($T.requireNonNull(signingKeyId, $S), $T.requireNonNull(signingKey, $S), $T.of())",
                    Objects.class, "signingKeyId", Objects.class, "signingKey", Map.class)
                .build())
            .addMethod(MethodSpec.methodBuilder("accepting")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(self)
                .addJavadoc("Ed25519 keys that verify and sign nothing: for a subgraph that only accepts.\n")
                .addParameter(verificationKeys, "verificationKeys")
                .addStatement("return ed25519(null, null, verificationKeys)")
                .build())
            .addMethod(ed25519)
            .addMethod(requireSigning)
            .build();
    }

    private static TypeSpec codec(ClassName self, ClassName keys, ClassName rejected) {
        var token = self.nestedClass("Token");
        var reason = rejected.nestedClass("Reason");
        var tokenRecord = TypeSpec.recordBuilder("Token")
            .addModifiers(Modifier.PUBLIC)
            .addJavadoc("A voucher's content. Each section maps a field name to the value its scalar serialized, as\n"
                + "a {@code String}, a {@code Boolean}, an {@code Integer}, {@code Long} or {@code BigInteger} by\n"
                + "fit, or a {@code Double}; the sections iterate in name order.\n")
            .recordConstructor(MethodSpec.constructorBuilder()
                .addParameter(UUID.class, "vouchId")
                .addParameter(String.class, "scope")
                .addParameter(Instant.class, "expires")
                .addParameter(String.class, "keyId")
                .addParameter(OBJECT_MAP, "vouchedFor")
                .addParameter(OBJECT_MAP, "lookup")
                .build())
            .addMethod(MethodSpec.compactConstructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addStatement("$T.requireNonNull(vouchId, $S)", Objects.class, "vouchId")
                .addStatement("$T.requireNonNull(scope, $S)", Objects.class, "scope")
                .addStatement("$T.requireNonNull(expires, $S)", Objects.class, "expires")
                .addStatement("$T.requireNonNull(keyId, $S)", Objects.class, "keyId")
                .addStatement("vouchedFor = $T.unmodifiableMap(new $T<>(vouchedFor))", Collections.class, TreeMap.class)
                .addStatement("lookup = $T.unmodifiableMap(new $T<>(lookup))", Collections.class, TreeMap.class)
                .build())
            .build();

        var issuerClass = self.nestedClass("Issuer");
        var verifierClass = self.nestedClass("Verifier");

        var issuer = TypeSpec.classBuilder("Issuer")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addJavadoc("Signs the vouchers of one operation and scope: the vouch id, scope, expiry and key\n"
                + "id are fixed when it is made, and each row's voucher varies only in its type and values.\n")
            .addField(keys, "keys", Modifier.PRIVATE, Modifier.FINAL)
            .addField(byte[].class, "head", Modifier.PRIVATE, Modifier.FINAL)
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PRIVATE)
                .addParameter(keys, "keys")
                .addParameter(byte[].class, "head")
                .addStatement("this.keys = keys")
                .addStatement("this.head = head")
                .build())
            .addMethod(MethodSpec.methodBuilder("sign")
                .addModifiers(Modifier.PUBLIC)
                .returns(String.class)
                .addJavadoc("The voucher for one row of {@code typeName}. Each value is what the field's scalar\n"
                    + "serialized: a string, a number or a boolean.\n"
                    + "\n"
                    + "@throws IllegalArgumentException where a value is null or of another kind\n")
                .addParameter(String.class, "typeName")
                .addParameter(WIRE_MAP, "vouchedFor")
                .addParameter(WIRE_MAP, "lookup")
                .addStatement("$T bytes = new $T()", ByteArrayOutputStream.class, ByteArrayOutputStream.class)
                .addStatement("$T out = new $T(bytes)", DataOutputStream.class, DataOutputStream.class)
                .beginControlFlow("try")
                .addStatement("out.write(head)")
                .addStatement("writeSection(out, vouchedFor)")
                .addStatement("writeSection(out, lookup)")
                .addStatement("byte[] signature = keys.sign(signed(bytes.toByteArray(), typeName))")
                .addStatement("out.writeInt(signature.length)")
                .addStatement("out.write(signature)")
                .nextControlFlow("catch ($T e)", IOException.class)
                .addStatement("throw new $T(e)", UncheckedIOException.class)
                .endControlFlow()
                .addStatement("return $T.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray())", Base64.class)
                .build())
            .build();

        var issuerFactory = MethodSpec.methodBuilder("issuer")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(issuerClass)
            .addJavadoc("The issuer of one operation's vouchers under one scope, signing with the signing key of\n"
                + "{@code keys}. The expiry is kept to the second.\n")
            .addParameter(keys, "keys")
            .addParameter(UUID.class, "vouchId")
            .addParameter(String.class, "scope")
            .addParameter(Instant.class, "expires")
            .addStatement("$T bytes = new $T()", ByteArrayOutputStream.class, ByteArrayOutputStream.class)
            .addStatement("$T out = new $T(bytes)", DataOutputStream.class, DataOutputStream.class)
            .beginControlFlow("try")
            .addStatement("out.writeByte(VERSION)")
            .addStatement("out.writeLong(vouchId.getMostSignificantBits())")
            .addStatement("out.writeLong(vouchId.getLeastSignificantBits())")
            .addStatement("writeString(out, scope)")
            .addStatement("out.writeLong(expires.getEpochSecond())")
            .addStatement("writeString(out, keys.signingKeyId())")
            .nextControlFlow("catch ($T e)", IOException.class)
            .addStatement("throw new $T(e)", UncheckedIOException.class)
            .endControlFlow()
            .addStatement("return new $T(keys, bytes.toByteArray())", issuerClass)
            .build();

        var issuersClass = self.nestedClass(ISSUERS);
        var issuers = TypeSpec.classBuilder(ISSUERS)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addJavadoc("The issuers of one operation: bound to the keys and the instant the operation started, it\n"
                + "hands out one issuer per scope and lifetime, under a vouch id minted once for them, so every\n"
                + "voucher of the operation under one scope shares id and expiry. Safe to share between the\n"
                + "operation's threads.\n")
            .addField(keys, "keys", Modifier.PRIVATE, Modifier.FINAL)
            .addField(Instant.class, "started", Modifier.PRIVATE, Modifier.FINAL)
            .addField(FieldSpec.builder(ParameterizedTypeName.get(ClassName.get(java.util.concurrent.ConcurrentMap.class),
                    ParameterizedTypeName.get(ClassName.get(List.class), ClassName.get(Object.class)), issuerClass), "byScope", Modifier.PRIVATE, Modifier.FINAL)
                .initializer("new $T<>()", java.util.concurrent.ConcurrentHashMap.class).build())
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addJavadoc("@param keys the keys vouchers are signed with\n"
                    + "@param started when the operation started; each expiry counts from it\n")
                .addParameter(keys, "keys")
                .addParameter(Instant.class, "started")
                .addStatement("this.keys = $T.requireNonNull(keys, $S)", Objects.class, "keys")
                .addStatement("this.started = $T.requireNonNull(started, $S)", Objects.class, "started")
                .build())
            .addMethod(MethodSpec.methodBuilder("issuer")
                .addModifiers(Modifier.PUBLIC)
                .returns(issuerClass)
                .addJavadoc("The operation's issuer for {@code scope}, its vouchers expiring {@code ttl} after the\n"
                    + "operation started.\n")
                .addParameter(String.class, "scope")
                .addParameter(Duration.class, "ttl")
                .addStatement("return byScope.computeIfAbsent($T.of(scope, ttl),"
                    + " key -> $T.issuer(keys, $T.randomUUID(), scope, started.plus(ttl)))", List.class, self, UUID.class)
                .build())
            .build();

        var verify = MethodSpec.methodBuilder("verify")
            .addModifiers(Modifier.PUBLIC)
            .returns(token)
            .addJavadoc("Decodes and checks a voucher presented for an entity of {@code typeName}: its signature\n"
                + "with the type name, its expiry within {@link VoucherCodec#CLOCK_SKEW}, its scope,\n"
                + "that its vouched-for section names exactly {@code vouchedForFields} and its lookup part exactly\n"
                + "{@code keyFields}. Each voucher is checked on its own; the vouch id is an audit handle and\n"
                + "agrees with nothing. Needs no connection.\n"
                + "\n"
                + "@throws $T naming the first check that failed\n", rejected)
            .addParameter(String.class, "typeName")
            .addParameter(String.class, "voucher")
            .addParameter(String.class, "scope")
            .addParameter(STRING_SET, "vouchedForFields")
            .addParameter(STRING_SET, "keyFields")
            .addStatement("$T buffer", ByteBuffer.class)
            .beginControlFlow("try")
            .addStatement("buffer = $T.wrap($T.getUrlDecoder().decode(voucher))", ByteBuffer.class, Base64.class)
            .nextControlFlow("catch ($T e)", IllegalArgumentException.class)
            .addStatement("throw new $T($T.MALFORMED)", rejected, reason)
            .endControlFlow()
            .addStatement("$T decoded", token)
            .addStatement("byte[] body")
            .addStatement("byte[] signature")
            .beginControlFlow("try")
            .beginControlFlow("if (buffer.get() != VERSION)")
            .addStatement("throw new $T($T.UNKNOWN_VERSION)", rejected, reason)
            .endControlFlow()
            .addStatement("$T vouchId = new $T(buffer.getLong(), buffer.getLong())", UUID.class, UUID.class)
            .addStatement("$T tokenScope = readString(buffer)", String.class)
            .addStatement("$T expires = $T.ofEpochSecond(buffer.getLong())", Instant.class, Instant.class)
            .addStatement("$T keyId = readString(buffer)", String.class)
            .addStatement("$T vouchedFor = readSection(buffer)", OBJECT_MAP)
            .addStatement("$T lookup = readSection(buffer)", OBJECT_MAP)
            .addStatement("body = new byte[buffer.position()]")
            .addStatement("buffer.get(0, body)")
            .addStatement("signature = new byte[length(buffer)]")
            .addStatement("buffer.get(signature)")
            .beginControlFlow("if (buffer.hasRemaining())")
            .addStatement("throw new $T($T.MALFORMED)", rejected, reason)
            .endControlFlow()
            .addStatement("decoded = new $T(vouchId, tokenScope, expires, keyId, vouchedFor, lookup)", token)
            .nextControlFlow("catch ($T | $T e)", BufferUnderflowException.class, IllegalArgumentException.class)
            .addStatement("throw new $T($T.MALFORMED)", rejected, reason)
            .endControlFlow()
            .beginControlFlow("if (!keys.hasVerificationKey(decoded.keyId()))")
            .addStatement("throw new $T($T.UNKNOWN_KEY)", rejected, reason)
            .endControlFlow()
            .beginControlFlow("if (!keys.verify(decoded.keyId(), signed(body, typeName), signature))")
            .addStatement("throw new $T($T.SIGNATURE)", rejected, reason)
            .endControlFlow()
            .beginControlFlow("if (now.isAfter(decoded.expires().plus(CLOCK_SKEW)))")
            .addStatement("throw new $T($T.EXPIRED)", rejected, reason)
            .endControlFlow()
            .beginControlFlow("if (!decoded.scope().equals(scope))")
            .addStatement("throw new $T($T.SCOPE)", rejected, reason)
            .endControlFlow()
            .beginControlFlow("if (!decoded.vouchedFor().keySet().equals(vouchedForFields))")
            .addStatement("throw new $T($T.VOUCHED_FOR)", rejected, reason)
            .endControlFlow()
            .beginControlFlow("if (!decoded.lookup().keySet().equals(keyFields))")
            .addStatement("throw new $T($T.KEY)", rejected, reason)
            .endControlFlow()
            .addStatement("return decoded")
            .build();

        var verifier = TypeSpec.classBuilder("Verifier")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addJavadoc("Checks the vouchers one request presents, against the instant it checks at. One per\n"
                + "request.\n")
            .addField(keys, "keys", Modifier.PRIVATE, Modifier.FINAL)
            .addField(Instant.class, "now", Modifier.PRIVATE, Modifier.FINAL)
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PRIVATE)
                .addParameter(keys, "keys")
                .addParameter(Instant.class, "now")
                .addStatement("this.keys = keys")
                .addStatement("this.now = now")
                .build())
            .addMethod(verify)
            .build();

        var verifierFactory = MethodSpec.methodBuilder("verifier")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(verifierClass)
            .addJavadoc("A verifier for one request's vouchers, checking with the verification keys of\n"
                + "{@code keys} and expiry against {@code now}.\n")
            .addParameter(keys, "keys")
            .addParameter(Instant.class, "now")
            .addStatement("return new $T($T.requireNonNull(keys, $S), $T.requireNonNull(now, $S))",
                verifierClass, Objects.class, "keys", Objects.class, "now")
            .build();
        var signed = MethodSpec.methodBuilder("signed")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(byte[].class)
            .addParameter(byte[].class, "body")
            .addParameter(String.class, "typeName")
            .addStatement("byte[] type = typeName.getBytes($T.UTF_8)", StandardCharsets.class)
            .addStatement("return $T.allocate(body.length + 4 + type.length).put(body).putInt(type.length).put(type).array()",
                ByteBuffer.class)
            .build();

        var writeString = MethodSpec.methodBuilder("writeString")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addException(IOException.class)
            .addParameter(DataOutputStream.class, "out")
            .addParameter(String.class, "value")
            .addStatement("byte[] bytes = value.getBytes($T.UTF_8)", StandardCharsets.class)
            .addStatement("out.writeInt(bytes.length)")
            .addStatement("out.write(bytes)")
            .build();

        var writeSection = MethodSpec.methodBuilder("writeSection")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addException(IOException.class)
            .addParameter(DataOutputStream.class, "out")
            .addParameter(WIRE_MAP, "section")
            .addStatement("$T<$T, $T> sorted = new $T<>(section)", TreeMap.class, String.class, Object.class, TreeMap.class)
            .addStatement("out.writeInt(sorted.size())")
            .beginControlFlow("for ($T<$T, $T> entry : sorted.entrySet())", Map.Entry.class, String.class, Object.class)
            .addStatement("$T value = entry.getValue()", Object.class)
            .addStatement("char tag")
            .beginControlFlow("if (value instanceof $T)", String.class)
            .addStatement("tag = 'S'")
            .nextControlFlow("else if (value instanceof $T)", Boolean.class)
            .addStatement("tag = 'B'")
            .nextControlFlow("else if (value instanceof $T || value instanceof $T || value instanceof $T"
                    + " || value instanceof $T || value instanceof $T)",
                Integer.class, Long.class, Short.class, Byte.class, BigInteger.class)
            .addStatement("tag = 'I'")
            .nextControlFlow("else if (value instanceof $T || value instanceof $T || value instanceof $T)",
                Double.class, Float.class, BigDecimal.class)
            .addStatement("tag = 'F'")
            .nextControlFlow("else")
            .addStatement("throw new $T($S + entry.getKey() + $S)", IllegalArgumentException.class,
                "the voucher value of '", "' is not a serialized string, number or boolean")
            .endControlFlow()
            .addStatement("writeString(out, entry.getKey())")
            .addStatement("out.writeByte(tag)")
            .addStatement("writeString(out, value.toString())")
            .endControlFlow()
            .build();

        var length = MethodSpec.methodBuilder("length")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(int.class)
            .addParameter(ByteBuffer.class, "buffer")
            .addStatement("int length = buffer.getInt()")
            .beginControlFlow("if (length < 0 || length > buffer.remaining())")
            .addStatement("throw new $T()", BufferUnderflowException.class)
            .endControlFlow()
            .addStatement("return length")
            .build();

        var readString = MethodSpec.methodBuilder("readString")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(String.class)
            .addParameter(ByteBuffer.class, "buffer")
            .addStatement("byte[] bytes = new byte[length(buffer)]")
            .addStatement("buffer.get(bytes)")
            .addStatement("return new $T(bytes, $T.UTF_8)", String.class, StandardCharsets.class)
            .build();

        var readSection = MethodSpec.methodBuilder("readSection")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(OBJECT_MAP)
            .addParameter(ByteBuffer.class, "buffer")
            .addStatement("int count = buffer.getInt()")
            .beginControlFlow("if (count < 0 || count > buffer.remaining())")
            .addStatement("throw new $T()", BufferUnderflowException.class)
            .endControlFlow()
            .addStatement("$T<$T, $T> section = new $T<>()", TreeMap.class, String.class, Object.class, TreeMap.class)
            .addStatement("$T previous = null", String.class)
            .beginControlFlow("for (int i = 0; i < count; i++)")
            .addStatement("$T name = readString(buffer)", String.class)
            .beginControlFlow("if (previous != null && previous.compareTo(name) >= 0)")
            .addStatement("throw new $T($S)", IllegalArgumentException.class, "section out of order")
            .endControlFlow()
            .addStatement("previous = name")
            .addStatement("byte tag = buffer.get()")
            .addStatement("$T text = readString(buffer)", String.class)
            .addStatement("section.put(name, value(tag, text))")
            .endControlFlow()
            .addStatement("return section")
            .build();

        var value = MethodSpec.methodBuilder("value")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(Object.class)
            .addParameter(byte.class, "tag")
            .addParameter(String.class, "text")
            .beginControlFlow("if (tag == 'S')")
            .addStatement("return text")
            .endControlFlow()
            .beginControlFlow("if (tag == 'B')")
            .beginControlFlow("if (text.equals($S) || text.equals($S))", "true", "false")
            .addStatement("return $T.valueOf(text)", Boolean.class)
            .endControlFlow()
            .addStatement("throw new $T($S)", IllegalArgumentException.class, "not a boolean")
            .endControlFlow()
            .beginControlFlow("if (tag == 'I')")
            .addStatement("$T integer = new $T(text)", BigInteger.class, BigInteger.class)
            .beginControlFlow("if (integer.bitLength() < 32)")
            .addStatement("return integer.intValue()")
            .endControlFlow()
            .beginControlFlow("if (integer.bitLength() < 64)")
            .addStatement("return integer.longValue()")
            .endControlFlow()
            .addStatement("return integer")
            .endControlFlow()
            .beginControlFlow("if (tag == 'F')")
            .addStatement("return $T.valueOf(text)", Double.class)
            .endControlFlow()
            .addStatement("throw new $T($S)", IllegalArgumentException.class, "unknown value tag")
            .build();

        return TypeSpec.classBuilder(self)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("Owns the voucher format: signs a voucher on the issuing side and decodes and checks one on\n"
                + "the accepting side. A failed check is a {@link $T}, never another exception.\n", rejected)
            .addType(tokenRecord)
            .addField(FieldSpec.builder(int.class, "VERSION", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .addJavadoc("The format version written, and the only one read.\n")
                .initializer("$L", VERSION).build())
            .addField(FieldSpec.builder(Duration.class, "CLOCK_SKEW", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .addJavadoc("How far past its expiry a voucher is still accepted.\n")
                .initializer("$T.ofSeconds($L)", Duration.class, CLOCK_SKEW.toSeconds()).build())
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
            .addType(issuer)
            .addType(issuers)
            .addType(verifier)
            .addMethod(issuerFactory)
            .addMethod(verifierFactory)
            .addMethod(signed)
            .addMethod(writeString)
            .addMethod(writeSection)
            .addMethod(length)
            .addMethod(readString)
            .addMethod(readSection)
            .addMethod(value)
            .build();
    }
}
