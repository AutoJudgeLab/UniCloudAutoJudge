package org.university.judge.api.leaderboard.application;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.university.judge.api.assignment.domain.AssignmentException;
import org.university.judge.api.assignment.domain.AssignmentStatus;
import org.university.judge.api.leaderboard.api.*;
import org.university.judge.api.leaderboard.persistence.LeaderboardRepository;
import org.university.judge.api.submission.application.SubmissionAccess;
import org.university.judge.api.user.Role;

@Service
@PreAuthorize("isAuthenticated()")
public class LeaderboardService {
    private final SubmissionAccess access;
    private final LeaderboardRepository repository;
    public LeaderboardService(SubmissionAccess access, LeaderboardRepository repository) { this.access = access; this.repository = repository; }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public LeaderboardPage get(UUID id, LeaderboardRequest request) {
        var actor = access.actor(false, false);
        var assignment = access.assignment(id, false);
        if (assignment.getStatus() != AssignmentStatus.PUBLISHED && actor.getRole() != Role.ADMIN
                && !(actor.getRole() == Role.TEACHER && assignment.getTeacher().getId().equals(actor.getId()))) {
            throw new AssignmentException(AssignmentException.Reason.ASSIGNMENT_NOT_FOUND);
        }
        return repository.find(id, request);
    }
}

