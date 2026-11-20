package org.university.judge.api.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtSettings.class)
@ConditionalOnProperty(name = "security.authentication-mode", havingValue = "local", matchIfMissing = true)
public class JwtConfiguration {
    @Bean
    RSAKey signingKey(JwtSettings settings) {
        if (settings.getIssuer() == null || settings.getIssuer().isBlank()
                || settings.getAudience() == null || settings.getAudience().isBlank()
                || settings.getAccessTokenTtl() == null
                || settings.getAccessTokenTtl().compareTo(Duration.ofMinutes(1)) < 0
                || settings.getAccessTokenTtl().compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalStateException("JWT issuer, audience and token lifetime (1–60 minutes) must be configured");
        }
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            RSAPrivateKey privateKey = (RSAPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(
                    Base64.getDecoder().decode(settings.getPrivateKeyBase64())));
            RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(
                    Base64.getDecoder().decode(settings.getPublicKeyBase64())));
            if (publicKey.getModulus().bitLength() < 2048 || !publicKey.getModulus().equals(privateKey.getModulus())
                    || !(privateKey instanceof RSAPrivateCrtKey crt)
                    || !publicKey.getPublicExponent().equals(crt.getPublicExponent())) {
                throw new IllegalArgumentException();
            }
            return new RSAKey.Builder(publicKey).privateKey(privateKey).keyID("local-auth").build();
        } catch (Exception ignored) {
            // Do not include key material or configuration values in startup errors.
            throw new IllegalStateException("Configure matching Base64 DER RSA keys (PKCS#8 private, X.509 public), at least 2048 bits");
        }
    }

    @Bean
    JwtEncoder jwtEncoder(RSAKey signingKey) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)));
    }

    @Bean
    JwtDecoder jwtDecoder(RSAKey signingKey, JwtSettings settings) throws Exception {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(signingKey.toRSAPublicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ofSeconds(30)),
                new JwtIssuerValidator(settings.getIssuer()),
                new JwtClaimValidator<List<String>>("aud", audience -> audience != null && audience.contains(settings.getAudience())),
                new JwtClaimValidator<Instant>("exp", expiry -> expiry != null),
                new JwtClaimValidator<Instant>("iat", issued -> issued != null && !issued.isAfter(Instant.now().plusSeconds(30)))));
        return decoder;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
