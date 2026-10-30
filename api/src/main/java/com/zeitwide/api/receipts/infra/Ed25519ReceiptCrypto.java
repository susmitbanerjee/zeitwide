package com.zeitwide.api.receipts.infra;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zeitwide.api.receipts.app.ReceiptCrypto;
import com.zeitwide.api.receipts.domain.PublicReceiptKey;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Ed25519 signing and verification using the JDK's built-in provider. */
public final class Ed25519ReceiptCrypto implements ReceiptCrypto {

  private static final byte[] KEY_PAIR_CHECK =
      "zeitwide-receipt-key-pair-check".getBytes(StandardCharsets.US_ASCII);

  private final String activeKeyId;
  private final PrivateKey activePrivateKey;
  private final Map<String, VerificationKey> verificationKeys;
  private final List<PublicReceiptKey> publicKeys;

  public Ed25519ReceiptCrypto(
      String activeKeyId, String signingKeyPem, List<PublicReceiptKey> publicKeys) {
    this.activeKeyId = requireText(activeKeyId, "active key ID");
    this.publicKeys = List.copyOf(publicKeys);
    if (this.publicKeys.isEmpty()) {
      throw new IllegalStateException("At least one receipt public key is required");
    }
    try {
      KeyFactory factory = KeyFactory.getInstance("Ed25519");
      this.activePrivateKey = privateKey(factory, signingKeyPem);
      Map<String, VerificationKey> keys = new HashMap<>();
      for (PublicReceiptKey key : this.publicKeys) {
        PublicKey publicKey = publicKey(factory, key.publicKey());
        if (keys.putIfAbsent(key.keyId(), new VerificationKey(key, publicKey)) != null) {
          throw new IllegalStateException("Duplicate receipt key ID: " + key.keyId());
        }
      }
      this.verificationKeys = Map.copyOf(keys);
      VerificationKey activeKey =
          Objects.requireNonNull(
              this.verificationKeys.get(this.activeKeyId),
              "The active receipt key must be in the public key ring");
      verifyKeyPair(activeKey.publicKey());
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Receipt Ed25519 keys could not be loaded", exception);
    }
  }

  @Override
  public String activeKeyId() {
    return activeKeyId;
  }

  @Override
  public byte[] sign(Instant issuedAt, byte[] canonicalPayload) {
    VerificationKey active = verificationKeys.get(activeKeyId);
    if (!active.validAt(issuedAt)) {
      throw new IllegalStateException("The active receipt key is outside its validity interval");
    }
    try {
      Signature signature = Signature.getInstance("Ed25519");
      signature.initSign(activePrivateKey);
      signature.update(canonicalPayload);
      return signature.sign();
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("The JDK could not sign a receipt", exception);
    }
  }

  @Override
  public boolean verify(
      String keyId, Instant issuedAt, byte[] canonicalPayload, byte[] signatureBytes) {
    VerificationKey key = verificationKeys.get(keyId);
    if (key == null || !key.validAt(issuedAt) || signatureBytes.length != 64) {
      return false;
    }
    try {
      Signature signature = Signature.getInstance("Ed25519");
      signature.initVerify(key.publicKey());
      signature.update(canonicalPayload);
      return signature.verify(signatureBytes);
    } catch (GeneralSecurityException | IllegalArgumentException invalidSignature) {
      return false;
    }
  }

  @Override
  public List<PublicReceiptKey> publicKeys() {
    return publicKeys;
  }

  static List<PublicReceiptKey> parsePublicKeys(String json, ObjectMapper mapper) {
    try {
      List<ConfiguredPublicKey> configured = mapper.readValue(json, new TypeReference<>() {});
      return configured.stream()
          .map(
              key ->
                  new PublicReceiptKey(
                      key.keyId(), key.publicKey(), key.validFrom(), key.validTo()))
          .toList();
    } catch (java.io.IOException | RuntimeException exception) {
      throw new IllegalStateException("Receipt public key configuration is invalid", exception);
    }
  }

  private void verifyKeyPair(PublicKey publicKey) throws GeneralSecurityException {
    Signature signature = Signature.getInstance("Ed25519");
    signature.initSign(activePrivateKey);
    signature.update(KEY_PAIR_CHECK);
    byte[] signed = signature.sign();
    signature.initVerify(publicKey);
    signature.update(KEY_PAIR_CHECK);
    if (!signature.verify(signed)) {
      throw new IllegalStateException(
          "The active receipt private key does not match its public key");
    }
  }

  private static PrivateKey privateKey(KeyFactory factory, String pem)
      throws GeneralSecurityException {
    byte[] encoded = pemBytes(pem, "PRIVATE KEY");
    return factory.generatePrivate(new PKCS8EncodedKeySpec(encoded));
  }

  private static PublicKey publicKey(KeyFactory factory, String pem)
      throws GeneralSecurityException {
    byte[] encoded = pemBytes(pem, "PUBLIC KEY");
    return factory.generatePublic(new X509EncodedKeySpec(encoded));
  }

  private static byte[] pemBytes(String pem, String label) {
    String header = "-----BEGIN " + label + "-----";
    String footer = "-----END " + label + "-----";
    if (pem == null || !pem.contains(header) || !pem.contains(footer)) {
      throw new IllegalStateException("Receipt key must be PEM encoded as " + label);
    }
    String body = pem.replace(header, "").replace(footer, "");
    return Base64.getMimeDecoder().decode(body);
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("A receipt " + name + " is required");
    }
    return value;
  }

  private record VerificationKey(PublicReceiptKey metadata, PublicKey publicKey) {

    private boolean validAt(Instant instant) {
      return !instant.isBefore(metadata.validFrom())
          && (metadata.validTo() == null || !instant.isAfter(metadata.validTo()));
    }
  }

  private record ConfiguredPublicKey(
      @JsonProperty("key_id") String keyId,
      @JsonProperty("public_key") String publicKey,
      @JsonProperty("valid_from") Instant validFrom,
      @JsonProperty("valid_to") Instant validTo) {}
}
