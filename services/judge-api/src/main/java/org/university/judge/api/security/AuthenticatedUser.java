package org.university.judge.api.security;

import java.security.Principal;
import java.util.UUID;
import org.university.judge.api.user.Role;

/** Stable application identity; contains no JWT or local-login implementation details. */
public record AuthenticatedUser(UUID id, Role role, long authenticationVersion) implements Principal {
    @Override
    public String getName() {
        return id.toString();
    }
}
