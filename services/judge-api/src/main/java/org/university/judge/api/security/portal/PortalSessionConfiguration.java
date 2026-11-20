package org.university.judge.api.security.portal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.session.web.http.DefaultCookieSerializer;

/** OIDC state, PKCE verifier and authorized clients survive API replica changes. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "security.authentication-mode", havingValue = "portal")
@EnableRedisHttpSession(redisNamespace = "judge:portal:sessions", maxInactiveIntervalInSeconds = 1800)
public class PortalSessionConfiguration {
    @Bean
    DefaultCookieSerializer cookieSerializer(@Value("${server.servlet.session.cookie.secure:true}") boolean secure) {
        var cookie = new DefaultCookieSerializer();
        cookie.setCookieName("JUDGE_SESSION");
        cookie.setCookiePath("/");
        cookie.setUseHttpOnlyCookie(true);
        cookie.setUseSecureCookie(secure);
        cookie.setSameSite("Lax");
        return cookie;
    }
}
