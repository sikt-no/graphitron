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
 * token format, the key provider it signs and verifies with, and the exception a rejected voucher fails
 * the request with. What the three classes say depends on nothing but the package they land in, so
 * they are rendered whole; whether a build writes them is the store's question, answered by
 * {@link #vouches}.
 *
 * <p>The format, version 1: base64url without padding over the version byte, the 16-byte vouch id, the
 * scope, the caller, the expiry in epoch seconds, the key id, the claims section, the lookup section,
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
    /** The rejection's simple name. */
    public static final String REJECTED = "VoucherRejectedException";

    /** The token format version this runtime writes and the only one it reads. */
    public static final int VERSION = 1;

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
     * Whether the store's graph vouches, and so writes the voucher runtime: whether it mints a voucher
     * key on any type. The minted key is the stored decision that a marker applies, made once by the
     * {@code minted-keys} stage, and a marker it would not mint a key for (one in a graph with no
     * federation link) is a defect that fails the build before anything is emitted.
     */
    public static boolean vouches(StoreHandle store) {
        return store.dsl().fetchExists(GRAPHITRON_MINTED_KEY,
            GRAPHITRON_MINTED_KEY.GRAPH_NAME.eq(store.graphName()),
            GRAPHITRON_MINTED_KEY.ROLE.eq("ISSUE"));
    }

    /** The three classes, each landing in {@code <outputPackage>.schema}. */
    public static List<TypeSpec> render(String outputPackage) {
        String pkg = outputPackage + ".schema";
        var keys = ClassName.get(pkg, KEYS);
        var rejected = ClassName.get(pkg, REJECTED);
        return List.of(codec(ClassName.get(pkg, CODEC), keys, rejected), keys(keys), rejected(rejected));
    }

    private static TypeSpec rejected(ClassName self) {
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
            .addEnumConstant("CALLER", constant("the voucher was issued to another caller"))
            .addEnumConstant("CLAIMS", constant("the voucher's claims name other fields than the accepting field's claims"))
            .addEnumConstant("KEY", constant("the voucher's lookup part names other fields than the entity's key"))
            .addEnumConstant("VALUE", constant("a value in the voucher does not parse as its field's scalar"))
            .addEnumConstant("INCONSISTENT", constant("vouchers sharing a vouch id disagree on scope, caller or expiry"))
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
            .superclass(RuntimeException.class)
            .addJavadoc("A voucher failed verification. It fails the request before any connection is taken. The\n"
                + "message names the check that failed and nothing from the token, since a voucher is a\n"
                + "credential.\n")
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
                .addStatement("var verifying = $T.copyOf(verificationKeys)", Map.class)
                .beginControlFlow("try")
                .beginControlFlow("if (signingKey != null)")
                .addStatement("$T.getInstance(ALGORITHM).initSign(signingKey)", Signature.class)
                .endControlFlow()
                .beginControlFlow("for (var key : verifying.values())")
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
                .addStatement("var signature = $T.getInstance(ALGORITHM)", Signature.class)
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
                .addStatement("var key = verifying.get(keyId)")
                .beginControlFlow("if (key == null)")
                .addStatement("return false")
                .endControlFlow()
                .beginControlFlow("try")
                .addStatement("var verifier = $T.getInstance(ALGORITHM)", Signature.class)
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
                .addParameter(String.class, "caller")
                .addParameter(Instant.class, "expires")
                .addParameter(String.class, "keyId")
                .addParameter(OBJECT_MAP, "claims")
                .addParameter(OBJECT_MAP, "lookup")
                .build())
            .addMethod(MethodSpec.compactConstructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addStatement("$T.requireNonNull(vouchId, $S)", Objects.class, "vouchId")
                .addStatement("$T.requireNonNull(scope, $S)", Objects.class, "scope")
                .addStatement("$T.requireNonNull(caller, $S)", Objects.class, "caller")
                .addStatement("$T.requireNonNull(expires, $S)", Objects.class, "expires")
                .addStatement("$T.requireNonNull(keyId, $S)", Objects.class, "keyId")
                .addStatement("claims = $T.unmodifiableMap(new $T<>(claims))", Collections.class, TreeMap.class)
                .addStatement("lookup = $T.unmodifiableMap(new $T<>(lookup))", Collections.class, TreeMap.class)
                .build())
            .build();

        var issuerClass = self.nestedClass("Issuer");
        var verifierClass = self.nestedClass("Verifier");

        var issuer = TypeSpec.classBuilder("Issuer")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addJavadoc("Signs the vouchers of one operation and scope: the vouch id, scope, caller, expiry and key\n"
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
                .addParameter(WIRE_MAP, "claims")
                .addParameter(WIRE_MAP, "lookup")
                .addStatement("var bytes = new $T()", ByteArrayOutputStream.class)
                .addStatement("var out = new $T(bytes)", DataOutputStream.class)
                .beginControlFlow("try")
                .addStatement("out.write(head)")
                .addStatement("writeSection(out, claims)")
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
            .addParameter(String.class, "caller")
            .addParameter(Instant.class, "expires")
            .addStatement("var bytes = new $T()", ByteArrayOutputStream.class)
            .addStatement("var out = new $T(bytes)", DataOutputStream.class)
            .beginControlFlow("try")
            .addStatement("out.writeByte(VERSION)")
            .addStatement("out.writeLong(vouchId.getMostSignificantBits())")
            .addStatement("out.writeLong(vouchId.getLeastSignificantBits())")
            .addStatement("writeString(out, scope)")
            .addStatement("writeString(out, caller)")
            .addStatement("out.writeLong(expires.getEpochSecond())")
            .addStatement("writeString(out, keys.signingKeyId())")
            .nextControlFlow("catch ($T e)", IOException.class)
            .addStatement("throw new $T(e)", UncheckedIOException.class)
            .endControlFlow()
            .addStatement("return new $T(keys, bytes.toByteArray())", issuerClass)
            .build();

        var verify = MethodSpec.methodBuilder("verify")
            .addModifiers(Modifier.PUBLIC)
            .returns(token)
            .addJavadoc("Decodes and checks a voucher presented for an entity of {@code typeName}: its signature\n"
                + "with the type name, its expiry within {@link VoucherCodec#CLOCK_SKEW}, its scope, its caller,\n"
                + "that its claims name exactly {@code claimFields} and its lookup part exactly\n"
                + "{@code keyFields}, and that it agrees on scope, caller and expiry with every voucher this\n"
                + "verifier passed under the same vouch id. Needs no connection.\n"
                + "\n"
                + "@throws $T naming the first check that failed\n", rejected)
            .addParameter(String.class, "typeName")
            .addParameter(String.class, "voucher")
            .addParameter(String.class, "scope")
            .addParameter(STRING_SET, "claimFields")
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
            .addStatement("var vouchId = new $T(buffer.getLong(), buffer.getLong())", UUID.class)
            .addStatement("var tokenScope = readString(buffer)")
            .addStatement("var tokenCaller = readString(buffer)")
            .addStatement("var expires = $T.ofEpochSecond(buffer.getLong())", Instant.class)
            .addStatement("var keyId = readString(buffer)")
            .addStatement("var claims = readSection(buffer)")
            .addStatement("var lookup = readSection(buffer)")
            .addStatement("body = new byte[buffer.position()]")
            .addStatement("buffer.get(0, body)")
            .addStatement("signature = new byte[length(buffer)]")
            .addStatement("buffer.get(signature)")
            .beginControlFlow("if (buffer.hasRemaining())")
            .addStatement("throw new $T($T.MALFORMED)", rejected, reason)
            .endControlFlow()
            .addStatement("decoded = new $T(vouchId, tokenScope, tokenCaller, expires, keyId, claims, lookup)", token)
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
            .beginControlFlow("if (!decoded.caller().equals(caller))")
            .addStatement("throw new $T($T.CALLER)", rejected, reason)
            .endControlFlow()
            .beginControlFlow("if (!decoded.claims().keySet().equals(claimFields))")
            .addStatement("throw new $T($T.CLAIMS)", rejected, reason)
            .endControlFlow()
            .beginControlFlow("if (!decoded.lookup().keySet().equals(keyFields))")
            .addStatement("throw new $T($T.KEY)", rejected, reason)
            .endControlFlow()
            .addStatement("var seen = vouches.putIfAbsent(decoded.vouchId(), decoded)")
            .beginControlFlow("if (seen != null && !(seen.scope().equals(decoded.scope())"
                + " && seen.expires().equals(decoded.expires())))")
            .addStatement("throw new $T($T.INCONSISTENT)", rejected, reason)
            .endControlFlow()
            .addStatement("return decoded")
            .build();

        var verifier = TypeSpec.classBuilder("Verifier")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addJavadoc("Checks the vouchers one request presents, against the request's caller and the instant it\n"
                + "checks at, and remembers each vouch id it passed so vouchers of one vouch must agree. One per\n"
                + "request; not for sharing between threads.\n")
            .addField(keys, "keys", Modifier.PRIVATE, Modifier.FINAL)
            .addField(Instant.class, "now", Modifier.PRIVATE, Modifier.FINAL)
            .addField(String.class, "caller", Modifier.PRIVATE, Modifier.FINAL)
            .addField(FieldSpec.builder(ParameterizedTypeName.get(ClassName.get(Map.class), ClassName.get(UUID.class), token),
                "vouches", Modifier.PRIVATE, Modifier.FINAL)
                .initializer("new $T<>()", HashMap.class).build())
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PRIVATE)
                .addParameter(keys, "keys")
                .addParameter(Instant.class, "now")
                .addParameter(String.class, "caller")
                .addStatement("this.keys = keys")
                .addStatement("this.now = now")
                .addStatement("this.caller = caller")
                .build())
            .addMethod(verify)
            .build();

        var verifierFactory = MethodSpec.methodBuilder("verifier")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(verifierClass)
            .addJavadoc("A verifier for one request's vouchers, checking with the verification keys of\n"
                + "{@code keys}, expiry against {@code now} and the caller against {@code caller}.\n")
            .addParameter(keys, "keys")
            .addParameter(Instant.class, "now")
            .addParameter(String.class, "caller")
            .addStatement("return new $T($T.requireNonNull(keys, $S), $T.requireNonNull(now, $S), $T.requireNonNull(caller, $S))",
                verifierClass, Objects.class, "keys", Objects.class, "now", Objects.class, "caller")
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
            .addStatement("var sorted = new $T<$T, $T>(section)", TreeMap.class, String.class, Object.class)
            .addStatement("out.writeInt(sorted.size())")
            .beginControlFlow("for (var entry : sorted.entrySet())")
            .addStatement("var value = entry.getValue()")
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
            .addStatement("var section = new $T<$T, $T>()", TreeMap.class, String.class, Object.class)
            .addStatement("$T previous = null", String.class)
            .beginControlFlow("for (int i = 0; i < count; i++)")
            .addStatement("var name = readString(buffer)")
            .beginControlFlow("if (previous != null && previous.compareTo(name) >= 0)")
            .addStatement("throw new $T($S)", IllegalArgumentException.class, "section out of order")
            .endControlFlow()
            .addStatement("previous = name")
            .addStatement("byte tag = buffer.get()")
            .addStatement("var text = readString(buffer)")
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
            .addStatement("var integer = new $T(text)", BigInteger.class)
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
