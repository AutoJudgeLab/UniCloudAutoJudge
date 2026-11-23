package org.university.judge.api.submission.application;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.university.judge.api.assignment.domain.Assignment;
import org.university.judge.api.assignment.domain.AssignmentException;
import org.university.judge.api.assignment.domain.AssignmentRevision;
import org.university.judge.api.assignment.domain.AssignmentStatus;
import org.university.judge.api.assignment.persistence.AssignmentRepository;
import org.university.judge.api.assignment.persistence.AssignmentRevisionRepository;
import org.university.judge.api.security.CurrentUser;
import org.university.judge.api.submission.domain.SubmissionException;
import org.university.judge.api.user.Role;
import org.university.judge.api.user.User;
import org.university.judge.api.user.UserRepository;

import static org.university.judge.api.submission.domain.SubmissionException.Code.*;

@Component
public class SubmissionAccess {
    private final CurrentUser current;
    private final UserRepository users;
    private final AssignmentRepository assignments;
    private final AssignmentRevisionRepository revisions;
    private final jakarta.persistence.EntityManager entityManager;
    public SubmissionAccess(CurrentUser current, UserRepository users, AssignmentRepository assignments, AssignmentRevisionRepository revisions,
                            jakarta.persistence.EntityManager entityManager) {
        this.current = current; this.users = users; this.assignments = assignments; this.revisions = revisions;
        this.entityManager = entityManager;
    }

    public User actor(boolean lock, boolean studentOnly) {
        var identity = current.require();
        User actor = (lock ? users.findByIdForShare(identity.id()) : users.findById(identity.id()))
                .orElseThrow(() -> new BadCredentialsException("Authentication is required"));
        // A previously loaded actor may have been disabled/demoted during S3 I/O.
        if (lock) entityManager.refresh(actor);
        if (!actor.isEnabled() || actor.getTokenVersion() != identity.authenticationVersion()) throw new BadCredentialsException("Authentication is required");
        if (studentOnly && actor.getRole() != Role.STUDENT) throw new AccessDeniedException("Only students can submit solutions");
        return actor;
    }

    public Assignment assignment(UUID id, boolean lock) {
        return (lock ? assignments.findByIdForShare(id) : assignments.findById(id))
                .orElseThrow(() -> new AssignmentException(AssignmentException.Reason.ASSIGNMENT_NOT_FOUND));
    }

    public AssignmentRevision grading(Assignment assignment) {
        if (assignment.getStatus() != AssignmentStatus.PUBLISHED) throw new SubmissionException(ASSIGNMENT_NOT_PUBLISHED);
        AssignmentRevision latest = revisions.findFirstByAssignmentIdOrderByRevisionNumberDesc(assignment.getId())
                .orElseThrow(() -> new SubmissionException(GRADING_NOT_READY));
        if (latest.getSealedAt() == null || !latest.getTitle().equals(assignment.getTitle())
                || !latest.getDescription().equals(assignment.getDescription())
                || latest.getMaximumScore().compareTo(assignment.getMaximumScore()) != 0
                || !latest.getDeadline().equals(assignment.getDeadline())) throw new SubmissionException(GRADING_NOT_READY);
        return latest;
    }
}
