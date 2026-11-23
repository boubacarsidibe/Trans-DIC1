package sn.ept.transdic1.security.service;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import sn.ept.transdic1.security.port.Directory;

/**
 * Dialogue avec l'annuaire par JNDI (ADR 0046). Volontairement mince et bête : tout ce qui se
 * raisonne est ailleurs, parce que ce code ne peut pas être éprouvé sans annuaire.
 *
 * <p><b>Aucune bibliothèque ajoutée.</b> JNDI est dans le JDK depuis toujours et suffit à une
 * liaison simple et à une recherche. Une bibliothèque LDAP apporterait des pools de connexions et
 * une gestion fine des référents dont nous n'avons pas l'usage, pour un arbre de dépendances de
 * plus à surveiller.
 *
 * <p><b>Le mot de passe en clair est refusé par défaut.</b> Une liaison simple sur {@code ldap://}
 * envoie le mot de passe tel quel sur le réseau. Le service refuse de le faire à moins que
 * l'exploitation ne l'ait explicitement permis, et le dit dans le journal : c'est le genre de
 * réglage qu'on ne veut pas voir arriver en production par inadvertance.
 */
// Adaptateur et non service : derrière ce bean il n'y a que le dialogue réseau, tout le
// raisonnement vit dans DirectoryAuthenticationService et DirectoryRoles (ADR 0046).
@Component
public class JndiDirectory implements Directory {
  private static final Logger LOG = LoggerFactory.getLogger(JndiDirectory.class);

  private static final String FACTORY = "com.sun.jndi.ldap.LdapCtxFactory";

  private final String url;
  private final String userDnPattern;
  private final String emailAttribute;
  private final String memberOfAttribute;
  private final String groupSearchBase;
  private final String groupFilter;
  private final boolean allowInsecure;
  private final int timeoutMs;

  @SuppressWarnings(
      "java:S107") // Un réglage par décision d'exploitation, et chacune est documentée.
  public JndiDirectory(
      @Value("${ldap.url:}") String url,
      @Value("${ldap.user-dn-pattern:}") String userDnPattern,
      @Value("${ldap.email-attribute:mail}") String emailAttribute,
      @Value("${ldap.member-of-attribute:memberOf}") String memberOfAttribute,
      @Value("${ldap.group-search-base:}") String groupSearchBase,
      @Value("${ldap.group-filter:(member={0})}") String groupFilter,
      @Value("${ldap.allow-insecure:false}") boolean allowInsecure,
      @Value("${ldap.timeout-ms:5000}") int timeoutMs) {
    this.url = url == null ? "" : url.trim();
    this.userDnPattern = userDnPattern == null ? "" : userDnPattern.trim();
    this.emailAttribute = blankTo(emailAttribute, "mail");
    this.memberOfAttribute = blankTo(memberOfAttribute, "memberOf");
    this.groupSearchBase = groupSearchBase == null ? "" : groupSearchBase.trim();
    this.groupFilter = blankTo(groupFilter, "(member={0})");
    this.allowInsecure = allowInsecure;
    this.timeoutMs = Math.max(1_000, Math.min(30_000, timeoutMs));
  }

  @Override
  public boolean enabled() {
    if (url.isEmpty() || userDnPattern.isEmpty()) {
      return false;
    }
    if (!secure() && !allowInsecure) {
      // Dit une fois par tentative plutôt qu'au démarrage : l'exploitation doit le voir au moment
      // où quelqu'un n'arrive pas à se connecter.
      LOG.warn(
          "Annuaire configuré en ldap:// sans chiffrement : les mots de passe ne seront pas"
              + " envoyés. Employez ldaps:// ou, en dernier recours, ldap.allow-insecure=true.");
      return false;
    }
    return true;
  }

  @Override
  public Optional<DirectoryUser> authenticate(String username, String password) {
    if (!enabled() || isBlank(username) || isBlank(password)) {
      return Optional.empty();
    }
    String dn = distinguishedName(username);
    if (dn == null) {
      return Optional.empty();
    }
    DirContext context = null;
    try {
      context = new InitialDirContext(environment(dn, password));
      Attributes attributes =
          context.getAttributes(dn, new String[] {emailAttribute, memberOfAttribute});
      List<String> groups = new ArrayList<>(values(attributes, memberOfAttribute));
      if (groups.isEmpty() && !groupSearchBase.isEmpty()) {
        groups.addAll(searchGroups(context, dn));
      }
      return Optional.of(
          new DirectoryUser(
              username,
              values(attributes, emailAttribute).stream().findFirst().orElse(null),
              DirectoryRoles.shortNames(groups)));
    } catch (javax.naming.AuthenticationException e) {
      // Mot de passe refusé : cas ordinaire, pas un incident.
      return Optional.empty();
    } catch (javax.naming.NamingException e) {
      // Annuaire injoignable, nom distingué introuvable, certificat refusé : l'appelant ne doit
      // pas distinguer ces cas d'un mauvais mot de passe, mais l'exploitation doit les voir.
      LOG.warn("Annuaire injoignable ou réponse inattendue pour {} : {}", username, e.toString());
      return Optional.empty();
    } finally {
      close(context);
    }
  }

  /** Nom distingué de la personne, d'après le gabarit configuré. */
  String distinguishedName(String username) {
    String escaped = escape(username);
    if (escaped == null) {
      return null;
    }
    return userDnPattern.replace("{0}", escaped);
  }

  /**
   * Échappe les caractères qui ont un sens dans un nom distingué (RFC 4514). Sans cela, un nom
   * d'utilisateur contenant une virgule ou un signe égal construirait un autre nom distingué que
   * celui voulu.
   */
  static String escape(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    var out = new StringBuilder();
    for (char c : value.trim().toCharArray()) {
      if ("\\,+\"<>;=#".indexOf(c) >= 0) {
        out.append('\\');
      }
      out.append(c);
    }
    return out.toString();
  }

  /** Échappe les caractères qui ont un sens dans un filtre de recherche (RFC 4515). */
  static String escapeFilter(String value) {
    if (value == null) {
      return "";
    }
    var out = new StringBuilder();
    for (char c : value.toCharArray()) {
      switch (c) {
        case '\\' -> out.append("\\5c");
        case '*' -> out.append("\\2a");
        case '(' -> out.append("\\28");
        case ')' -> out.append("\\29");
        case '\0' -> out.append("\\00");
        default -> out.append(c);
      }
    }
    return out.toString();
  }

  /** Vrai si la liaison se fait sur un canal chiffré. */
  boolean secure() {
    return url.toLowerCase(Locale.ROOT).startsWith("ldaps://");
  }

  private Hashtable<String, String> environment(String dn, String password) {
    var environment = new Hashtable<String, String>();
    environment.put(Context.INITIAL_CONTEXT_FACTORY, FACTORY);
    environment.put(Context.PROVIDER_URL, url);
    environment.put(Context.SECURITY_AUTHENTICATION, "simple");
    environment.put(Context.SECURITY_PRINCIPAL, dn);
    environment.put(Context.SECURITY_CREDENTIALS, password);
    environment.put("com.sun.jndi.ldap.connect.timeout", String.valueOf(timeoutMs));
    environment.put("com.sun.jndi.ldap.read.timeout", String.valueOf(timeoutMs));
    // Ne jamais suivre un référent : il pourrait pointer vers un serveur que nous n'avons pas
    // choisi, et nous lui enverrions le mot de passe.
    environment.put(Context.REFERRAL, "ignore");
    return environment;
  }

  private List<String> searchGroups(DirContext context, String dn)
      throws javax.naming.NamingException {
    var controls = new SearchControls();
    controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
    controls.setReturningAttributes(new String[] {"cn"});
    controls.setTimeLimit(timeoutMs);
    var found = new ArrayList<String>();
    NamingEnumeration<SearchResult> results =
        context.search(groupSearchBase, groupFilter.replace("{0}", escapeFilter(dn)), controls);
    try {
      while (results.hasMore()) {
        SearchResult result = results.next();
        values(result.getAttributes(), "cn").stream().findFirst().ifPresent(found::add);
      }
    } finally {
      results.close();
    }
    return found;
  }

  private static List<String> values(Attributes attributes, String name) {
    if (attributes == null) {
      return List.of();
    }
    Attribute attribute = attributes.get(name);
    if (attribute == null) {
      return List.of();
    }
    var found = new ArrayList<String>();
    try {
      NamingEnumeration<?> all = attribute.getAll();
      while (all.hasMore()) {
        Object value = all.next();
        if (value != null && !value.toString().isBlank()) {
          found.add(value.toString());
        }
      }
    } catch (javax.naming.NamingException e) {
      LOG.debug("Attribut {} illisible : {}", name, e.toString());
    }
    return found;
  }

  private static void close(DirContext context) {
    if (context == null) {
      return;
    }
    try {
      context.close();
    } catch (javax.naming.NamingException e) {
      LOG.debug("Fermeture de la connexion à l'annuaire : {}", e.toString());
    }
  }

  private static String blankTo(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
