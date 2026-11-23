package org.university.judge.api.user;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
import org.university.judge.api.persistence.CreatedEntity;

@Entity
@Table(name = "users")
public class User extends CreatedEntity {
    @NotBlank
    @Size(max = 100)
    @Column(name = "username", nullable = false, length = 100)
    private String username;

    @Email
    @Size(max = 254)
    @Column(name = "email", length = 254)
    private String email;

    @Size(max = 255)
    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    @Column(nullable = false)
    private boolean enabled = true;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "token_version", nullable = false, insertable = false, updatable = false)
    private long tokenVersion;

    @NotNull
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected User() {
    }

    public User(String username, String email, String passwordHash, Role role) {
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    @Column(name = "external_issuer", length = 512)
    private String externalIssuer;

    @Column(name = "external_subject", length = 255)
    private String externalSubject;

    public static User portal(String issuer, String subject, String email, Role role) {
        User user = new User("portal:" + java.util.UUID.randomUUID(), email, null, role);
        user.externalIssuer = issuer;
        user.externalSubject = subject;
        return user;
    }

    @JsonIgnore
    public boolean isExternal() { return externalIssuer != null; }

    public void synchronizePortal(String email, Role role) {
        if (!isExternal()) throw new IllegalStateException("Not a portal identity");
        this.email = email;
        this.role = Objects.requireNonNull(role);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void changeRole(Role role) {
        Objects.requireNonNull(role);
        if (this.role == Role.ADMIN || role == Role.ADMIN) {
            throw new IllegalArgumentException("Administrative roles require separate provisioning");
        }
        this.role = role;
    }

    @JsonIgnore
    public long getTokenVersion() {
        return tokenVersion;
    }

    @PrePersist
    private void initializeUpdatedAt() {
        updatedAt = getCreatedAt();
    }

    @PreUpdate
    private void updateTimestamp() {
        updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    @JsonIgnore
    public String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

}
