package sn.ept.transdic1.collection.template;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Catalogue des modèles d'équipement livrés (ADR 0022) : fichiers JSON lus et validés une fois au
 * démarrage. Un fichier invalide arrête le démarrage avec un message qui nomme le fichier, plutôt
 * que d'appliquer plus tard un modèle faux à un équipement.
 */
@Component
public class EquipmentTemplateCatalog {
  public static final String DEFAULT_LOCATION = "classpath*:templates/equipment/*.json";
  private static final Logger LOG = LoggerFactory.getLogger(EquipmentTemplateCatalog.class);
  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);

  private final Map<String, EquipmentTemplate> templates;

  @Autowired
  public EquipmentTemplateCatalog(
      @Value("${templates.equipment.location:" + DEFAULT_LOCATION + "}") String location)
      throws IOException {
    this(read(location));
    LOG.info("{} modèle(s) d'équipement chargé(s) depuis {}", templates.size(), location);
  }

  private EquipmentTemplateCatalog(Map<String, EquipmentTemplate> templates) {
    this.templates = templates;
  }

  /** Catalogue lu depuis un motif de ressources (tests, démarrage). */
  public static EquipmentTemplateCatalog fromClasspath(String location) throws IOException {
    return new EquipmentTemplateCatalog(read(location));
  }

  /** Catalogue construit depuis des contenus en mémoire (nom de fichier -> JSON). */
  public static EquipmentTemplateCatalog fromSources(Map<String, String> sources) {
    return new EquipmentTemplateCatalog(parseAll(sources));
  }

  public List<EquipmentTemplate> all() {
    return List.copyOf(templates.values());
  }

  public Optional<EquipmentTemplate> find(String id) {
    return Optional.ofNullable(id == null ? null : templates.get(id));
  }

  /**
   * Filtre le catalogue. {@code vendor} : égalité sans casse ; {@code q} : texte cherché dans
   * l'identifiant, le nom, le fabricant et la description ; {@code sysDescr} : modèles dont un des
   * fragments {@code sysDescrHints} apparaît dans le sysDescr d'un équipement découvert.
   */
  public List<EquipmentTemplate> search(
      TemplateCategory category, String vendor, String q, String sysDescr) {
    String text = blank(q) ? null : q.trim().toLowerCase(Locale.ROOT);
    String descr = blank(sysDescr) ? null : sysDescr.toLowerCase(Locale.ROOT);
    return templates.values().stream()
        .filter(t -> category == null || t.category() == category)
        .filter(t -> blank(vendor) || t.vendor().equalsIgnoreCase(vendor.trim()))
        .filter(
            t ->
                text == null
                    || (t.id() + " " + t.name() + " " + t.vendor() + " " + t.description())
                        .toLowerCase(Locale.ROOT)
                        .contains(text))
        .filter(
            t ->
                descr == null
                    || t.sysDescrHints().stream()
                        .anyMatch(h -> descr.contains(h.toLowerCase(Locale.ROOT))))
        .toList();
  }

  // ------------------------------------------------------------------ lecture

  private static Map<String, EquipmentTemplate> read(String location) throws IOException {
    Resource[] resources = new PathMatchingResourcePatternResolver().getResources(location);
    Map<String, String> sources = new LinkedHashMap<>();
    List<Resource> sorted = new ArrayList<>(List.of(resources));
    sorted.sort(Comparator.comparing(r -> String.valueOf(r.getFilename())));
    for (Resource resource : sorted) {
      try (var in = resource.getInputStream()) {
        sources.put(
            String.valueOf(resource.getFilename()),
            new String(in.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    return parseAll(sources);
  }

  private static Map<String, EquipmentTemplate> parseAll(Map<String, String> sources) {
    Map<String, EquipmentTemplate> parsed = new LinkedHashMap<>();
    Map<String, String> fileOf = new LinkedHashMap<>();
    sources.forEach(
        (file, json) -> {
          EquipmentTemplate template = parse(file, json);
          EquipmentTemplateValidator.validate(template, file);
          if (parsed.containsKey(template.id())) {
            throw invalid(
                file,
                "identifiant « "
                    + template.id()
                    + " » déjà utilisé par "
                    + fileOf.get(template.id()));
          }
          parsed.put(template.id(), template);
          fileOf.put(template.id(), file);
        });
    Map<String, EquipmentTemplate> sorted = new LinkedHashMap<>();
    parsed.values().stream()
        .sorted(Comparator.comparing(EquipmentTemplate::name, String.CASE_INSENSITIVE_ORDER))
        .forEach(t -> sorted.put(t.id(), t));
    return sorted;
  }

  private static EquipmentTemplate parse(String file, String json) {
    try {
      EquipmentTemplate template = MAPPER.readValue(json, EquipmentTemplate.class);
      if (template == null) {
        throw invalid(file, "fichier vide");
      }
      return template;
    } catch (UnrecognizedPropertyException e) {
      throw invalid(file, "champ inconnu « " + e.getPropertyName() + " »");
    } catch (InvalidFormatException e) {
      throw invalid(
          file, "valeur « " + e.getValue() + " » invalide pour le champ " + path(e.getPath()));
    } catch (JsonProcessingException e) {
      throw invalid(file, "JSON illisible : " + e.getOriginalMessage());
    }
  }

  private static String path(
      List<com.fasterxml.jackson.databind.JsonMappingException.Reference> path) {
    StringBuilder out = new StringBuilder();
    for (var reference : path) {
      if (reference.getFieldName() != null) {
        out.append(out.isEmpty() ? "" : ".").append(reference.getFieldName());
      } else {
        out.append('[').append(reference.getIndex()).append(']');
      }
    }
    return out.toString();
  }

  static IllegalStateException invalid(String file, String reason) {
    return new IllegalStateException(
        "Modèle d'équipement invalide (fichier " + file + ") : " + reason);
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
