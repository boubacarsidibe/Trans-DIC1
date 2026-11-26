package sn.ept.transdic1.collection.hypervisor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;
import org.springframework.stereotype.Component;
import sn.ept.transdic1.collection.port.HypervisorEndpoint;
import sn.ept.transdic1.collection.port.HypervisorInventory;
import sn.ept.transdic1.collection.port.HypervisorSource;
import sn.ept.transdic1.collection.port.HypervisorSourceException;
import sn.ept.transdic1.collection.port.HypervisorSourceException.Reason;
import sn.ept.transdic1.inventory.domain.HypervisorKind;
import sn.ept.transdic1.inventory.domain.HypervisorResourceKind;
import sn.ept.transdic1.inventory.domain.PowerState;

/**
 * Connecteur Proxmox VE (ADR 0055) : API REST {@code /api2/json}, jeton d'API en lecture seule
 * (rôle {@code PVEAuditor}) dans l'en-tête {@code Authorization: PVEAPIToken=…}.
 *
 * <p>Une seule requête lit tout le cluster ({@code /cluster/resources}) ; {@code /cluster/status}
 * donne l'adresse des hôtes. Les adresses des VM viennent de l'agent invité (QEMU Guest Agent),
 * l'UUID SMBIOS de la configuration de la VM : deux appels par invité, faits seulement quand le
 * service les demande. Certificat et nom d'hôte toujours vérifiés ; une autorité interne peut être
 * fournie (celle de Proxmox, {@code pve-root-ca.pem}). Redirections jamais suivies, réponse bornée.
 */
@Component
public class ProxmoxHypervisorSource implements HypervisorSource {
  /** Une réponse de {@code /cluster/resources} pour des milliers de VM tient en quelques Mo. */
  static final int MAX_BODY_BYTES = 32 * 1024 * 1024;

  private static final Pattern NODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.-]{0,62}");
  private static final Pattern GUEST_ID = Pattern.compile("(qemu|lxc)/(\\d{1,9})");
  private static final Pattern SMBIOS_UUID =
      Pattern.compile("(?:^|,)uuid=([0-9a-fA-F-]{32,36})(?:,|$)");

  private final ObjectMapper json = new ObjectMapper();
  private final Map<String, HttpClient> clients = new ConcurrentHashMap<>();

  @Override
  public HypervisorKind kind() {
    return HypervisorKind.PROXMOX;
  }

  @Override
  public HypervisorInventory read(HypervisorEndpoint endpoint) throws HypervisorSourceException {
    JsonNode resources = get(endpoint, "/api2/json/cluster/resources");
    if (!resources.isArray()) {
      throw new HypervisorSourceException(
          Reason.INVALID_RESPONSE, "Réponse de Proxmox illisible : liste de ressources attendue");
    }
    Map<String, String> addresses = nodeAddresses(endpoint);
    List<HypervisorInventory.Host> hosts = new ArrayList<>();
    List<HypervisorInventory.Guest> guests = new ArrayList<>();
    for (JsonNode item : resources) {
      String type = item.path("type").asText("");
      if ("node".equals(type)) {
        String node = item.path("node").asText("");
        if (!NODE.matcher(node).matches()) {
          continue;
        }
        hosts.add(
            new HypervisorInventory.Host(
                "node/" + node,
                node,
                hostPower(item.path("status").asText("")),
                addresses.get(node),
                fraction(item, "cpu"),
                ratio(item, "mem", "maxmem"),
                ratio(item, "disk", "maxdisk"),
                longOrNull(item, "uptime")));
      } else if ("qemu".equals(type) || "lxc".equals(type)) {
        String id = item.path("id").asText("");
        if (!GUEST_ID.matcher(id).matches()) {
          continue;
        }
        boolean vm = "qemu".equals(type);
        // Sans agent invité, Proxmox ne connaît pas l'occupation du disque d'une VM (il renvoie 0).
        Double disk = ratio(item, "disk", "maxdisk");
        if (vm && disk != null && disk == 0.0) {
          disk = null;
        }
        guests.add(
            new HypervisorInventory.Guest(
                id,
                item.path("name").asText(id),
                vm ? HypervisorResourceKind.VM : HypervisorResourceKind.CONTAINER,
                "node/" + item.path("node").asText(""),
                guestPower(item.path("status").asText("")),
                item.path("template").asInt(0) == 1,
                fraction(item, "cpu"),
                ratio(item, "mem", "maxmem"),
                disk,
                longOrNull(item, "netin"),
                longOrNull(item, "netout"),
                longOrNull(item, "uptime")));
      }
    }
    return new HypervisorInventory(hosts, guests);
  }

  @Override
  public List<String> guestAddresses(HypervisorEndpoint endpoint, HypervisorInventory.Guest guest)
      throws HypervisorSourceException {
    String[] parts = path(guest);
    List<String> found = new ArrayList<>();
    try {
      if (guest.kind() == HypervisorResourceKind.VM) {
        JsonNode data =
            get(
                endpoint,
                "/api2/json/nodes/"
                    + parts[0]
                    + "/qemu/"
                    + parts[1]
                    + "/agent/network-get-interfaces");
        for (JsonNode nic : data.path("result")) {
          for (JsonNode address : nic.path("ip-addresses")) {
            add(found, address.path("ip-address").asText(""));
          }
        }
      } else {
        JsonNode data =
            get(endpoint, "/api2/json/nodes/" + parts[0] + "/lxc/" + parts[1] + "/interfaces");
        for (JsonNode nic : data) {
          add(found, nic.path("inet").asText("").split("/")[0]);
          add(found, nic.path("inet6").asText("").split("/")[0]);
        }
      }
    } catch (HypervisorSourceException e) {
      // Agent invité arrêté (HTTP 500) ou droit de le lire absent (403) : l'adresse reste inconnue,
      // ce n'est pas une panne. Une vraie panne (réseau, délai) remonte.
      if (e.meansUnreachable() && e.httpStatus() == 0) {
        throw e;
      }
      return List.of();
    }
    // IPv4 d'abord : c'est elle qu'une sonde ICMP visera.
    found.sort((a, b) -> Boolean.compare(a.contains(":"), b.contains(":")));
    return found;
  }

  @Override
  public Optional<String> guestUuid(HypervisorEndpoint endpoint, HypervisorInventory.Guest guest)
      throws HypervisorSourceException {
    if (guest.kind() != HypervisorResourceKind.VM) {
      return Optional.empty();
    }
    String[] parts = path(guest);
    try {
      JsonNode config =
          get(endpoint, "/api2/json/nodes/" + parts[0] + "/qemu/" + parts[1] + "/config");
      var matcher = SMBIOS_UUID.matcher(config.path("smbios1").asText(""));
      return matcher.find()
          ? Optional.of(matcher.group(1).toLowerCase(Locale.ROOT))
          : Optional.empty();
    } catch (HypervisorSourceException e) {
      if (e.meansUnreachable() && e.httpStatus() == 0) {
        throw e;
      }
      return Optional.empty();
    }
  }

  /**
   * Redémarrage forcé d'une VM (QEMU seulement, jamais un conteneur ni un hôte) : {@code POST
   * /nodes/{noeud}/qemu/{vmid}/status/reset}, avec le jeton au seul droit {@code VM.PowerMgmt}.
   */
  @Override
  public String resetGuest(
      HypervisorEndpoint endpoint, String guestExternalId, String hostExternalId)
      throws HypervisorSourceException {
    String[] parts = path(guestExternalId, hostExternalId);
    if (!guestExternalId.startsWith("qemu/")) {
      throw new HypervisorSourceException(
          Reason.INVALID_RESPONSE, "Seule une VM peut être redémarrée : " + guestExternalId);
    }
    JsonNode task =
        send(
            endpoint, "/api2/json/nodes/" + parts[0] + "/qemu/" + parts[1] + "/status/reset", true);
    return task.asText("");
  }

  // --- HTTP -------------------------------------------------------------------------------------

  /** Champ {@code data} d'une réponse de l'API, ou une erreur classée. */
  JsonNode get(HypervisorEndpoint endpoint, String path) throws HypervisorSourceException {
    return send(endpoint, path, false);
  }

  private JsonNode send(HypervisorEndpoint endpoint, String path, boolean post)
      throws HypervisorSourceException {
    URI uri = endpoint.apiUrl().resolve(path);
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(uri)
            .timeout(endpoint.timeout())
            .header("Authorization", "PVEAPIToken=" + endpoint.tokenId() + "=" + endpoint.secret())
            .header("Accept", "application/json");
    HttpRequest request =
        post ? builder.POST(HttpRequest.BodyPublishers.noBody()).build() : builder.GET().build();
    HttpResponse<InputStream> response;
    try {
      response = client(endpoint).send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (HttpTimeoutException e) {
      throw new HypervisorSourceException(Reason.TIMEOUT, "Délai dépassé vers " + uri.getHost(), e);
    } catch (SSLException e) {
      throw new HypervisorSourceException(
          Reason.CERTIFICATE_REFUSED,
          "Certificat de " + uri.getHost() + " refusé (autorité ou nom d'hôte) : " + rootMessage(e),
          e);
    } catch (ConnectException e) {
      throw new HypervisorSourceException(
          Reason.UNREACHABLE, uri.getHost() + " injoignable (connexion refusée)", e);
    } catch (IOException e) {
      if (rootCause(e) instanceof javax.net.ssl.SSLHandshakeException
          || rootCause(e) instanceof java.security.cert.CertificateException) {
        throw new HypervisorSourceException(
            Reason.CERTIFICATE_REFUSED,
            "Certificat de " + uri.getHost() + " refusé : " + rootMessage(e),
            e);
      }
      throw new HypervisorSourceException(
          Reason.UNREACHABLE,
          uri.getHost() + " injoignable (" + e.getClass().getSimpleName() + ")",
          e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HypervisorSourceException(Reason.UNREACHABLE, "Appel interrompu", e);
    }
    try (InputStream body = response.body()) {
      int status = response.statusCode();
      if (status == 401) {
        throw new HypervisorSourceException(
            Reason.AUTH_REFUSED, "Jeton refusé par Proxmox (HTTP 401)", status);
      }
      if (status == 403) {
        throw new HypervisorSourceException(
            Reason.AUTH_REFUSED,
            post
                ? "Droits insuffisants (HTTP 403) : droit VM.PowerMgmt attendu sur le jeton"
                    + " d'action"
                : "Droits insuffisants (HTTP 403) : rôle PVEAuditor attendu",
            status);
      }
      if (status >= 500) {
        throw new HypervisorSourceException(
            Reason.UNREACHABLE, "Proxmox répond HTTP " + status, status);
      }
      if (status != 200) {
        throw new HypervisorSourceException(
            Reason.INVALID_RESPONSE, "Réponse HTTP " + status + " inattendue", status);
      }
      byte[] bytes = body.readNBytes(MAX_BODY_BYTES + 1);
      if (bytes.length > MAX_BODY_BYTES) {
        throw new HypervisorSourceException(
            Reason.INVALID_RESPONSE, "Réponse de Proxmox démesurée");
      }
      JsonNode root;
      try {
        root = json.readTree(bytes);
      } catch (IOException e) {
        throw new HypervisorSourceException(
            Reason.INVALID_RESPONSE, "Réponse de Proxmox illisible (JSON attendu)");
      }
      if (root == null || !root.has("data")) {
        throw new HypervisorSourceException(
            Reason.INVALID_RESPONSE, "Réponse de Proxmox illisible (champ data absent)");
      }
      return root.get("data");
    } catch (HttpTimeoutException e) {
      throw new HypervisorSourceException(Reason.TIMEOUT, "Délai dépassé vers " + uri.getHost(), e);
    } catch (IOException e) {
      throw new HypervisorSourceException(
          Reason.UNREACHABLE, "Réponse interrompue de " + uri.getHost(), e);
    }
  }

  private HttpClient client(HypervisorEndpoint endpoint) throws HypervisorSourceException {
    String pem = endpoint.caCertificatePem() == null ? "" : endpoint.caCertificatePem().trim();
    String key = fingerprint(pem) + "|" + endpoint.timeout().toMillis();
    try {
      return clients.computeIfAbsent(
          key,
          k -> {
            try {
              return HttpClient.newBuilder()
                  .version(HttpClient.Version.HTTP_1_1)
                  .connectTimeout(endpoint.timeout())
                  .followRedirects(HttpClient.Redirect.NEVER)
                  .sslContext(sslContext(pem))
                  .build();
            } catch (GeneralSecurityException e) {
              throw new IllegalArgumentException(e.getMessage(), e);
            }
          });
    } catch (IllegalArgumentException e) {
      throw new HypervisorSourceException(
          Reason.CERTIFICATE_REFUSED,
          "Autorité de certification inutilisable : " + e.getMessage(),
          e);
    }
  }

  /**
   * Contexte TLS : les autorités du système, ou seulement l'autorité interne fournie (PEM). La
   * vérification n'est jamais désactivée.
   */
  public static SSLContext sslContext(String pem) throws GeneralSecurityException {
    if (pem == null || pem.isBlank()) {
      return SSLContext.getDefault();
    }
    Collection<? extends Certificate> certificates =
        CertificateFactory.getInstance("X.509")
            .generateCertificates(
                new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
    if (certificates.isEmpty()) {
      throw new GeneralSecurityException("aucun certificat dans le PEM fourni");
    }
    KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
    try {
      store.load(null, null);
    } catch (IOException e) {
      throw new GeneralSecurityException(e);
    }
    int index = 0;
    for (Certificate certificate : certificates) {
      store.setCertificateEntry("autorite-" + index++, certificate);
    }
    TrustManagerFactory trust =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trust.init(store);
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(null, trust.getTrustManagers(), null);
    return context;
  }

  // --- Lecture des réponses ---------------------------------------------------------------------

  /** Adresse de chaque hôte, selon {@code /cluster/status} ; vide si l'appel est refusé. */
  private Map<String, String> nodeAddresses(HypervisorEndpoint endpoint)
      throws HypervisorSourceException {
    Map<String, String> addresses = new HashMap<>();
    try {
      for (JsonNode item : get(endpoint, "/api2/json/cluster/status")) {
        if ("node".equals(item.path("type").asText()) && item.hasNonNull("ip")) {
          addresses.put(item.path("name").asText(), item.path("ip").asText());
        }
      }
    } catch (HypervisorSourceException e) {
      if (e.meansUnreachable()) {
        throw e;
      }
    }
    return addresses;
  }

  private static String[] path(HypervisorInventory.Guest guest) throws HypervisorSourceException {
    return path(guest.externalId(), guest.hostExternalId());
  }

  private static String[] path(String externalId, String hostExternalId)
      throws HypervisorSourceException {
    var matcher = GUEST_ID.matcher(externalId == null ? "" : externalId);
    String node = hostExternalId == null ? "" : hostExternalId.replaceFirst("^node/", "");
    if (!matcher.matches() || !NODE.matcher(node).matches()) {
      throw new HypervisorSourceException(
          Reason.INVALID_RESPONSE, "Invité mal identifié : " + externalId);
    }
    return new String[] {node, matcher.group(2)};
  }

  private static void add(List<String> found, String address) {
    String value = address == null ? "" : address.trim();
    if (value.isEmpty() || found.contains(value)) {
      return;
    }
    String lower = value.toLowerCase(Locale.ROOT);
    if (lower.startsWith("127.")
        || lower.equals("::1")
        || lower.startsWith("fe80:")
        || lower.startsWith("169.254.")) {
      return;
    }
    found.add(value);
  }

  private static PowerState hostPower(String status) {
    return switch (status) {
      case "online" -> PowerState.ONLINE;
      case "offline" -> PowerState.OFFLINE;
      default -> PowerState.UNKNOWN;
    };
  }

  private static PowerState guestPower(String status) {
    return switch (status) {
      case "running" -> PowerState.RUNNING;
      case "stopped" -> PowerState.STOPPED;
      case "paused", "suspended" -> PowerState.PAUSED;
      default -> PowerState.UNKNOWN;
    };
  }

  private static Double fraction(JsonNode item, String field) {
    return item.hasNonNull(field) ? round(item.get(field).asDouble() * 100.0) : null;
  }

  private static Double ratio(JsonNode item, String used, String max) {
    double total = item.path(max).asDouble(0);
    if (total <= 0 || !item.hasNonNull(used)) {
      return null;
    }
    return round(Math.min(100.0, item.get(used).asDouble() * 100.0 / total));
  }

  private static Long longOrNull(JsonNode item, String field) {
    return item.hasNonNull(field) ? item.get(field).asLong() : null;
  }

  private static double round(double value) {
    return Math.round(value * 100.0) / 100.0;
  }

  private static String fingerprint(String pem) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(pem.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Throwable rootCause(Throwable error) {
    Throwable current = error;
    while (current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
    }
    return current;
  }

  private static String rootMessage(Throwable error) {
    String message = rootCause(error).getMessage();
    return message == null ? rootCause(error).getClass().getSimpleName() : message;
  }

  /** Pour les tests : durée de vie des clients HTTP mis en cache. */
  int cachedClients() {
    return clients.size();
  }
}
