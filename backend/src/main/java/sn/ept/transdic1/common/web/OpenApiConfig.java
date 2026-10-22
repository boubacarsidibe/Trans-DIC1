package sn.ept.transdic1.common.web;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
  static final String BEARER = "bearerAuth";
  private static final String ERROR_SCHEMA = "ApiError";

  @Bean
  OpenAPI transDic1OpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("API Trans-DIC1")
                .version("v1")
                .description(
                    "API de supervision des infrastructures réseau et serveurs de l'EPT. "
                        + "Obtenez un jeton avec POST /api/v1/auth/login, puis cliquez sur "
                        + "« Authorize » et collez-le sans le préfixe Bearer."))
        .components(
            new Components()
                .addSecuritySchemes(
                    BEARER,
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
        .addSecurityItem(new SecurityRequirement().addList(BEARER));
  }

  /** Décrit partout le format d'erreur commun et les réponses 401 et 403 des routes protégées. */
  @Bean
  OpenApiCustomizer errorResponses() {
    return openApi -> {
      Map<String, Schema> errorSchemas = ModelConverters.getInstance().read(ApiExceptionHandler.ApiError.class);
      errorSchemas.forEach(
          (name, schema) -> openApi.getComponents().addSchemas(name, schema));
      if (openApi.getPaths() == null) return;
      openApi
          .getPaths()
          .forEach(
              (path, item) -> item.readOperations().forEach(OpenApiConfig::addErrors));
    };
  }

  private static void addErrors(Operation operation) {
    boolean secured = operation.getSecurity() == null || !operation.getSecurity().isEmpty();
    if (secured) {
      operation.getResponses().addApiResponse("401", error("Authentification requise ou jeton expiré"));
      operation.getResponses().addApiResponse("403", error("Rôle insuffisant pour cette action"));
    }
  }

  private static ApiResponse error(String description) {
    Schema<?> ref = new Schema<>().$ref("#/components/schemas/" + ERROR_SCHEMA);
    return new ApiResponse()
        .description(description)
        .content(new Content().addMediaType("application/json", new MediaType().schema(ref)));
  }
}
