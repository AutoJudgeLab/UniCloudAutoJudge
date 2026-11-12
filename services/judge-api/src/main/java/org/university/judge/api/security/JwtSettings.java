package org.university.judge.api.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("security.jwt")
public class JwtSettings {
    private String privateKeyBase64;
    private String publicKeyBase64;
    private String issuer;
    private String audience;
    private Duration accessTokenTtl;

    public String getPrivateKeyBase64() { return privateKeyBase64; }
    public void setPrivateKeyBase64(String value) { privateKeyBase64 = value; }
    public String getPublicKeyBase64() { return publicKeyBase64; }
    public void setPublicKeyBase64(String value) { publicKeyBase64 = value; }
    public String getIssuer() { return issuer; }
    public void setIssuer(String value) { issuer = value; }
    public String getAudience() { return audience; }
    public void setAudience(String value) { audience = value; }
    public Duration getAccessTokenTtl() { return accessTokenTtl; }
    public void setAccessTokenTtl(Duration value) { accessTokenTtl = value; }
}
