package sn.ept.transdic1.security.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;

/**
 * Décodeur des jetons du fournisseur OIDC, créé seulement si {@code auth.oidc.enabled=true}. La
 * configuration du fournisseur (clés de signature) est lue à la première utilisation : le backend
 * démarre même si le fournisseur est momentanément injoignable. Vérifie la signature, l'émetteur et
 * la date d'expiration. {@code auth.oidc.jwk-set-uri} sert quand le backend joint le fournisseur
 * par une autre adresse que le navigateur (réseau Docker) : les clés sont lues là, l'émetteur
 * attendu reste celui des jetons.
 */
@Configuration
@ConditionalOnProperty(name = "auth.oidc.enabled", havingValue = "true")
public class OidcConfig {
  @Bean
  @ConditionalOnMissingBean(JwtDecoder.class)
  JwtDecoder oidcJwtDecoder(
      @Value("${auth.oidc.issuer-uri}") String issuer,
      @Value("${auth.oidc.jwk-set-uri:}") String jwkSetUri) {
    return new SupplierJwtDecoder(
        () -> {
          NimbusJwtDecoder decoder =
              jwkSetUri.isBlank()
                  ? (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuer)
                  : NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
          OAuth2TokenValidator<Jwt> validator =
              new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer));
          decoder.setJwtValidator(validator);
          return decoder;
        });
  }
}
