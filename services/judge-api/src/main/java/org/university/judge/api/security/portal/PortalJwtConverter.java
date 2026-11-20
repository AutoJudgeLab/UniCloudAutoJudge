package org.university.judge.api.security.portal;

import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** The Portal access JWT does not carry user roles. Userinfo also checks revocation/disabled users. */
public class PortalJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final RestClient http;
    private final String userInfoUri;
    private final PortalIdentityService identities;

    public PortalJwtConverter(RestClient http, String userInfoUri, PortalIdentityService identities) {
        this.http = http; this.userInfoUri = userInfoUri; this.identities = identities;
    }

    @Override public AbstractAuthenticationToken convert(Jwt jwt) {
        Map<String, Object> claims;
        try {
            claims = http.get().uri(userInfoUri).headers(headers -> headers.setBearerAuth(jwt.getTokenValue()))
                    .retrieve().body(new ParameterizedTypeReference<Map<String, Object>>() { });
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403
                    || e.getStatusCode().value() == 400) throw PortalIdentityService.invalid();
            throw unavailable();
        } catch (RestClientException e) {
            throw unavailable();
        }
        if (claims == null) throw PortalIdentityService.invalid();
        var user = identities.resolve(jwt.getSubject(), claims);
        return new PreAuthenticatedAuthenticationToken(user, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
    }

    private static OAuth2AuthenticationException unavailable() {
        // Do not retain remote bodies, URLs or Bearer values in exception messages/causes.
        return new OAuth2AuthenticationException(new OAuth2Error("temporarily_unavailable"));
    }
}
