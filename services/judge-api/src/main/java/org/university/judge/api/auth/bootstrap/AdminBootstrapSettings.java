package org.university.judge.api.auth.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("security.bootstrap")
public class AdminBootstrapSettings {
    private boolean enabled;
    private String username;
    private String email;
    private String password;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getUsername() { return username; }
    public void setUsername(String value) { username = value; }
    public String getEmail() { return email; }
    public void setEmail(String value) { email = value; }
    public String getPassword() { return password; }
    public void setPassword(String value) { password = value; }
}
