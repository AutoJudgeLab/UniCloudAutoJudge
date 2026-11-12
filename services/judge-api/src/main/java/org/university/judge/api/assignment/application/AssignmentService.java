package org.university.judge.api.assignment.application;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import jakarta.persistence.criteria.Predicate;
import jakarta.validation.Validator;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.university.judge.api.assignment.api.AssignmentListRequest;
import org.university.judge.api.assignment.api.AssignmentPageResponse;
import org.university.judge.api.assignment.api.AssignmentRequest;
import org.university.judge.api.assignment.api.AssignmentResponse;
import org.university.judge.api.assignment.domain.Assignment;
import org.university.judge.api.assignment.domain.AssignmentException;
import org.university.judge.api.assignment.domain.AssignmentRevision;
import org.university.judge.api.assignment.domain.AssignmentStatus;
import org.university.judge.api.assignment.persistence.AssignmentRepository;
import org.university.judge.api.assignment.persistence.AssignmentRevisionRepository;
import org.university.judge.api.security.CurrentUser;
import org.university.judge.api.submission.persistence.SubmissionRepository;
import org.university.judge.api.testcase.domain.TestCase;
import org.university.judge.api.testcase.persistence.TestCaseRepository;
import org.university.judge.api.user.Role;
import org.university.judge.api.user.User;
import org.university.judge.api.user.UserRepository;

import static org.university.judge.api.assignment.domain.AssignmentException.Reason.*;

@Service
@PreAuthorize("isAuthenticated()")
public class AssignmentService {
    private final AssignmentRepository assignments;
    private final AssignmentRevisionRepository revisions;
    private final TestCaseRepository testCases;
    private final SubmissionRepository submissions;
    private final UserRepository users;
    private final CurrentUser currentUser;
    private final Validator validator;
    private final GradingRevisions gradingRevisions;

    public AssignmentService(AssignmentRepository assignments, AssignmentRevisionRepository revisions,
            TestCaseRepository testCases, SubmissionRepository submissions, UserRepository users,
            CurrentUser currentUser, Validator validator, GradingRevisions gradingRevisions) {
        this.assignments = assignments;
        this.revisions = revisions;
        this.testCases = testCases;
        this.submissions = submissions;
        this.users = users;
        this.currentUser = currentUser;
        this.validator = validator;
        this.gradingRevisions = gradingRevisions;
    }

    @Transactional
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public AssignmentResult create(AssignmentRequest request) {
        User actor = actor(true);
        validate(request);
        Assignment assignment = assignments.saveAndFlush(new Assignment(actor, request.title(), request.description(),
                request.maximumScore(), request.deadline()));
        AssignmentRevision revision = gradingRevisions.append(assignment, null);
        return result(assignment, revision);
    }

    @Transactional(readOnly = true)
    public AssignmentPageResponse list(AssignmentListRequest request) {
        User actor = actor(false);
        String[] sorting = request.sort().split(",");
        Sort sort = Sort.by(Sort.Direction.fromString(sorting[1]), sorting[0]);
        if (!sorting[0].equals("id")) sort = sort.and(Sort.by("id"));
        Specification<Assignment> scope = (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (actor.getRole() == Role.STUDENT) predicates.add(builder.equal(root.get("status"), AssignmentStatus.PUBLISHED));
            if (actor.getRole() == Role.TEACHER) predicates.add(builder.equal(root.get("teacher").get("id"), actor.getId()));
            if (request.status() != null) predicates.add(builder.equal(root.get("status"), request.status()));
            return builder.and(predicates.toArray(Predicate[]::new));
        };
        return AssignmentPageResponse.from(assignments.findAll(scope, PageRequest.of(request.page(), request.size(), sort))
                .map(AssignmentResponse::from));
    }

    @Transactional(readOnly = true)
    public AssignmentResult get(UUID id) {
        User actor = actor(false);
        Assignment assignment = assignments.findById(id).orElseThrow(() -> new AssignmentException(ASSIGNMENT_NOT_FOUND));
        if ((actor.getRole() == Role.STUDENT && assignment.getStatus() != AssignmentStatus.PUBLISHED)
                || (actor.getRole() == Role.TEACHER && !assignment.getTeacher().getId().equals(actor.getId()))) {
            throw new AssignmentException(ASSIGNMENT_NOT_FOUND);
        }
        return result(assignment, revisions.findFirstByAssignmentIdOrderByRevisionNumberDesc(id).orElse(null));
    }

    @Transactional
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public AssignmentResult update(UUID id, AssignmentRequest request, boolean createNewRevision) {
        Assignment assignment = managedAssignment(id);
        requireEditable(assignment);
        validate(request);
        AssignmentRevision latest = gradingRevisions.latestLocked(id);
        if (!createNewRevision && matches(assignment, request)) return result(assignment, latest);
        if (!createNewRevision && (assignment.getStatus() == AssignmentStatus.PUBLISHED
                || (latest != null && latest.getSealedAt() != null))) {
            throw new AssignmentException(REVISION_REQUIRED);
        }
        assignment.updateDetails(request.title(), request.description(), request.maximumScore(), request.deadline());
        assignment.setStatus(AssignmentStatus.DRAFT);
        assignments.flush();
        return result(assignment, gradingRevisions.append(assignment, latest));
    }

    @Transactional
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public AssignmentResult publish(UUID id) {
        Assignment assignment = managedAssignment(id);
        requireEditable(assignment);
        AssignmentRevision latest = gradingRevisions.latestLocked(id);
        if (assignment.getStatus() == AssignmentStatus.PUBLISHED && latest != null && latest.getSealedAt() != null) return result(assignment, latest);
        validate(new AssignmentRequest(assignment.getTitle(), assignment.getDescription(), assignment.getMaximumScore(), assignment.getDeadline()));
        // Legacy rows may not yet have a revision matching their editable metadata.
        if (latest == null || !matches(assignment, latest)) latest = gradingRevisions.append(assignment, latest);
        List<TestCase> gradingTests = testCases.findByRevisionIdOrderByExecutionOrder(latest.getId());
        if (gradingTests.isEmpty()) throw new AssignmentException(TEST_CASES_REQUIRED);
        if (gradingTests.stream().anyMatch(test -> !validator.validate(test).isEmpty() || test.getPoints().signum() <= 0)) {
            throw new AssignmentException(INVALID_TEST_CASES);
        }
        var total = gradingTests.stream().map(TestCase::getPoints).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        if (total.compareTo(latest.getMaximumScore()) != 0) throw new AssignmentException(TEST_CASE_SCORE_MISMATCH);
        if (latest.getSealedAt() == null) {
            latest.seal();
            revisions.flush();
        }
        assignment.setStatus(AssignmentStatus.PUBLISHED);
        assignments.flush();
        return result(assignment, latest);
    }

    @Transactional
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public AssignmentResult archive(UUID id) {
        Assignment assignment = managedAssignment(id);
        assignment.setStatus(AssignmentStatus.ARCHIVED);
        assignments.flush();
        return result(assignment, gradingRevisions.latestLocked(id));
    }

    @Transactional
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public void delete(UUID id) {
        Assignment assignment = managedAssignment(id);
        requireEditable(assignment);
        List<AssignmentRevision> history = revisions.findByAssignmentIdOrderByRevisionNumber(id);
        if (submissions.existsByAssignmentId(id)) throw new AssignmentException(ASSIGNMENT_HAS_SUBMISSIONS);
        if (history.stream().anyMatch(revision -> revision.getSealedAt() != null)) {
            throw new AssignmentException(GRADING_HISTORY_RETAINED);
        }
        try {
            testCases.deleteAllByAssignmentId(id);
            testCases.flush();
            revisions.deleteAllByAssignmentId(id);
            revisions.flush();
            assignments.delete(assignment);
            assignments.flush();
        } catch (DataIntegrityViolationException ignored) {
            throw new AssignmentException(ASSIGNMENT_IN_USE);
        }
    }

    private User actor(boolean write) {
        var identity = currentUser.require();
        User actor = (write ? users.findByIdForShare(identity.id()) : users.findById(identity.id()))
                .orElseThrow(() -> new BadCredentialsException("Authentication is required"));
        if (!actor.isEnabled() || actor.getTokenVersion() != identity.authenticationVersion()) {
            throw new BadCredentialsException("Authentication is required");
        }
        if (write && actor.getRole() != Role.ADMIN && actor.getRole() != Role.TEACHER) {
            throw new AccessDeniedException("Teacher or administrator privileges are required");
        }
        return actor;
    }

    private Assignment managedAssignment(UUID id) {
        User actor = actor(true);
        Assignment assignment = assignments.findByIdForUpdate(id).orElseThrow(() -> new AssignmentException(ASSIGNMENT_NOT_FOUND));
        if (actor.getRole() != Role.ADMIN && !assignment.getTeacher().getId().equals(actor.getId())) {
            throw new AccessDeniedException("Only the owner or an administrator can manage this assignment");
        }
        return assignment;
    }

    private void validate(AssignmentRequest request) {
        if (request == null || !validator.validate(request).isEmpty()) throw new AssignmentException(INVALID_ASSIGNMENT);
    }

    private void requireEditable(Assignment assignment) {
        if (assignment.getStatus() == AssignmentStatus.ARCHIVED) throw new AssignmentException(ARCHIVED_ASSIGNMENT);
    }

    private boolean matches(Assignment assignment, AssignmentRequest request) {
        return assignment.getTitle().equals(request.title()) && assignment.getDescription().equals(request.description())
                && assignment.getMaximumScore().compareTo(request.maximumScore()) == 0 && assignment.getDeadline().equals(request.deadline());
    }

    private boolean matches(Assignment assignment, AssignmentRevision revision) {
        return assignment.getTitle().equals(revision.getTitle()) && assignment.getDescription().equals(revision.getDescription())
                && assignment.getMaximumScore().compareTo(revision.getMaximumScore()) == 0 && assignment.getDeadline().equals(revision.getDeadline());
    }

    private AssignmentResult result(Assignment assignment, AssignmentRevision revision) {
        return new AssignmentResult(AssignmentResponse.from(assignment), revision == null ? 0 : revision.getRevisionNumber());
    }
}
