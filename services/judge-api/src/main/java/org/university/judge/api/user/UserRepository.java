package org.university.judge.api.user;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {
    Optional<User> findByExternalIssuerAndExternalSubject(String issuer, String subject);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.externalIssuer = :issuer and u.externalSubject = :subject")
    Optional<User> findExternalForUpdate(@Param("issuer") String issuer, @Param("subject") String subject);
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
    @Query("select u from User u where lower(u.username) = lower(:username) and u.externalIssuer is null")
    Optional<User> findByUsernameIgnoreCase(@Param("username") String username);
    @Query("select u from User u where lower(u.email) = lower(:email) and u.externalIssuer is null")
    Optional<User> findByEmailIgnoreCase(@Param("email") String email);
    boolean existsByUsernameIgnoreCase(String username);
    boolean existsByEmailIgnoreCase(String email);
    @Query("select (count(u) > 0) from User u where u.role = :role and u.externalIssuer is null")
    boolean existsByRole(@Param("role") Role role);
    @Query("select count(u) from User u where u.role = :role and u.enabled = true and u.externalIssuer is null")
    long countByRoleAndEnabledTrue(@Param("role") Role role);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForShare(@Param("id") UUID id);
}
