package org.university.judge.api.user.administration;

import java.util.List;
import org.springframework.data.domain.Page;
import org.university.judge.api.user.User;
import org.university.judge.api.user.UserResponse;

public record UserPageResponse(List<UserResponse> content, int page, int size, long totalElements, int totalPages) {
    public static UserPageResponse from(Page<User> users) {
        return new UserPageResponse(users.getContent().stream().map(UserResponse::from).toList(),
                users.getNumber(), users.getSize(), users.getTotalElements(), users.getTotalPages());
    }
}
