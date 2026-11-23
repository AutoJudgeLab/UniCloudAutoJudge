package org.university.judge.api.user;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.university.judge.api.security.CurrentUser;

@Service
public class UserProfileService {
    private final CurrentUser currentUser;
    private final UserRepository users;

    public UserProfileService(CurrentUser currentUser, UserRepository users) {
        this.currentUser = currentUser;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public UserResponse currentProfile() {
        return users.findById(currentUser.require().id()).filter(User::isEnabled).map(UserResponse::from)
                .orElseThrow(() -> new BadCredentialsException("Authentication is required"));
    }
}
