package sn.ept.transdic1.collection.probe;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Certificats de test générés au démarrage avec {@code keytool} du JDK courant (aucune API interne
 * {@code sun.*}, aucun fichier binaire versionné).
 */
final class TestTls {
  private static final char[] PASSWORD = "changeit".toCharArray();
  private static final String ALIAS = "test";

  private final KeyStore keyStore;

  private TestTls(KeyStore keyStore) {
    this.keyStore = keyStore;
  }

  /** Certificat auto-signé valide pour {@code localhost} et {@code 127.0.0.1}. */
  static TestTls selfSigned() {
    return generate("dns:localhost,ip:127.0.0.1", null, 365);
  }

  /**
   * Génère une paire de clés et un certificat auto-signé.
   *
   * @param san extension subjectAltName au format keytool (ex. {@code dns:localhost,ip:127.0.0.1})
   * @param startDate date de début au format keytool (ex. {@code -10d}) ou {@code null}
   * @param validityDays durée de validité en jours
   */
  static TestTls generate(String san, String startDate, int validityDays) {
    return generate(
        san, startDate, validityDays, List.of("-keyalg", "EC", "-groupname", "secp256r1"));
  }

  /** Certificat ancien : RSA 1024 bits signé en SHA-1 (désactivé par les contraintes du JDK). */
  static TestTls weakLegacy(String san, int validityDays) {
    return generate(
        san,
        null,
        validityDays,
        List.of("-keyalg", "RSA", "-keysize", "1024", "-sigalg", "SHA1withRSA"));
  }

  private static TestTls generate(
      String san, String startDate, int validityDays, List<String> keyOptions) {
    try {
      Path dir = Files.createTempDirectory("test-tls");
      Path file = dir.resolve("keystore.p12");
      String keytool =
          Path.of(System.getProperty("java.home"), "bin", "keytool").toAbsolutePath().toString();
      List<String> command =
          new java.util.ArrayList<>(
              List.of(
                  keytool,
                  "-genkeypair",
                  "-alias",
                  ALIAS,
                  "-dname",
                  "CN=localhost",
                  "-ext",
                  "SAN=" + san,
                  "-validity",
                  Integer.toString(validityDays),
                  "-storetype",
                  "PKCS12",
                  "-keystore",
                  file.toString(),
                  "-storepass",
                  new String(PASSWORD),
                  "-keypass",
                  new String(PASSWORD)));
      command.addAll(keyOptions);
      if (startDate != null) {
        command.add("-startdate");
        command.add(startDate);
      }
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
        throw new IllegalStateException("keytool a échoué : " + output);
      }
      KeyStore store = KeyStore.getInstance("PKCS12");
      try (InputStream in = Files.newInputStream(file)) {
        store.load(in, PASSWORD);
      }
      Files.deleteIfExists(file);
      Files.deleteIfExists(dir);
      return new TestTls(store);
    } catch (IOException | InterruptedException | java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  Certificate certificate() {
    try {
      return keyStore.getCertificate(ALIAS);
    } catch (java.security.KeyStoreException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Contexte serveur présentant ce certificat. */
  SSLContext serverContext() {
    try {
      KeyManagerFactory kmf =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      kmf.init(keyStore, PASSWORD);
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(kmf.getKeyManagers(), null, null);
      return context;
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Contexte client qui fait confiance uniquement à ce certificat. */
  SSLContext clientContextTrustingThis() {
    try {
      KeyStore trust = KeyStore.getInstance("PKCS12");
      trust.load(null, null);
      trust.setCertificateEntry(ALIAS, certificate());
      TrustManagerFactory tmf =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tmf.init(trust);
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, tmf.getTrustManagers(), null);
      return context;
    } catch (IOException | java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
