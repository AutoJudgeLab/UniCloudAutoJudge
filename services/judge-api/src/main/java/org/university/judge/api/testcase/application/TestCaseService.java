package org.university.judge.api.testcase.application;

import java.util.List;
import java.util.UUID;
import jakarta.validation.Validator;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.university.judge.api.assignment.application.GradingRevisions;
import org.university.judge.api.assignment.domain.Assignment;
import org.university.judge.api.assignment.domain.AssignmentException;
import org.university.judge.api.assignment.domain.AssignmentRevision;
import org.university.judge.api.assignment.domain.AssignmentStatus;
import org.university.judge.api.assignment.persistence.AssignmentRepository;
import org.university.judge.api.assignment.persistence.AssignmentRevisionRepository;
import org.university.judge.api.security.CurrentUser;
import org.university.judge.api.testcase.api.TestCaseRequest;
import org.university.judge.api.testcase.api.TestCaseResponse;
import org.university.judge.api.testcase.domain.TestCase;
import org.university.judge.api.testcase.domain.TestCaseException;
import org.university.judge.api.testcase.persistence.TestCaseRepository;
import org.university.judge.api.user.Role;
import org.university.judge.api.user.User;
import org.university.judge.api.user.UserRepository;

import static org.university.judge.api.assignment.domain.AssignmentException.Reason.*;
import static org.university.judge.api.testcase.domain.TestCaseException.Reason.*;

@Service
@Transactional
@PreAuthorize("isAuthenticated()")
public class TestCaseService {
    private final AssignmentRepository assignments;
    private final AssignmentRevisionRepository revisions;
    private final TestCaseRepository cases;
    private final GradingRevisions gradingRevisions;
    private final UserRepository users;
    private final CurrentUser currentUser;
    private final Validator validator;

    public TestCaseService(AssignmentRepository assignments, AssignmentRevisionRepository revisions,
            TestCaseRepository cases, GradingRevisions gradingRevisions, UserRepository users,
            CurrentUser currentUser, Validator validator) {
        this.assignments = assignments;
        this.revisions = revisions;
        this.cases = cases;
        this.gradingRevisions = gradingRevisions;
        this.users = users;
        this.currentUser = currentUser;
        this.validator = validator;
    }

    public TestCaseView<List<TestCaseResponse>> list(UUID assignmentId) {
        User actor = actor(false);
        Assignment assignment = assignment(assignmentId, actor, false);
        AssignmentRevision revision = visibleRevision(assignment, actor);
        if (revision == null) return new TestCaseView<>(List.of(), 0);
        // Filter in SQL: hidden payloads are never loaded for student requests.
        List<TestCase> visible = actor.getRole() == Role.STUDENT
                ? cases.findByRevisionIdAndHiddenFalseOrderByExecutionOrder(revision.getId())
                : cases.findByRevisionIdOrderByExecutionOrder(revision.getId());
        return new TestCaseView<>(visible.stream().map(TestCaseResponse::from).toList(), revision.getRevisionNumber());
    }

    public TestCaseView<TestCaseResponse> get(UUID assignmentId, UUID testCaseId) {
        User actor = actor(false);
        Assignment assignment = assignment(assignmentId, actor, false);
        AssignmentRevision revision = visibleRevision(assignment, actor);
        if (revision == null) throw new TestCaseException(TEST_CASE_NOT_FOUND);
        TestCase test = (actor.getRole() == Role.STUDENT
                ? cases.findByIdAndRevisionIdAndHiddenFalse(testCaseId, revision.getId())
                : cases.findByIdAndRevisionId(testCaseId, revision.getId()))
                .orElseThrow(() -> new TestCaseException(TEST_CASE_NOT_FOUND));
        return view(test, revision);
    }

    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public TestCaseView<TestCaseResponse> create(UUID assignmentId, TestCaseRequest request) {
        Assignment assignment = assignment(assignmentId, actor(true), true);
        validate(request);
        AssignmentRevision latest = gradingRevisions.latestLocked(assignmentId);
        requireUniqueOrder(latest, request.executionOrder(), null);
        AssignmentRevision editable = editable(assignment, latest);
        TestCase test = new TestCase(editable, request.inputData(), request.expectedOutput(), request.points(),
                request.isHidden(), request.executionOrder());
        try { cases.saveAndFlush(test); }
        catch (DataIntegrityViolationException ignored) { throw new TestCaseException(TEST_CASE_CONFLICT); }
        return view(test, editable);
    }

    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public TestCaseView<TestCaseResponse> update(UUID assignmentId, UUID testCaseId, TestCaseRequest request) {
        Assignment assignment = assignment(assignmentId, actor(true), true);
        validate(request);
        AssignmentRevision latest = gradingRevisions.latestLocked(assignmentId);
        TestCase original = currentCase(latest, testCaseId);
        requireUniqueOrder(latest, request.executionOrder(), testCaseId);
        AssignmentRevision editable = editable(assignment, latest);
        TestCase target = copiedTarget(original, latest, editable);
        target.updateDetails(request.inputData(), request.expectedOutput(), request.points(), request.isHidden(), request.executionOrder());
        flush();
        return view(target, editable);
    }

    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public int delete(UUID assignmentId, UUID testCaseId) {
        Assignment assignment = assignment(assignmentId, actor(true), true);
        AssignmentRevision latest = gradingRevisions.latestLocked(assignmentId);
        TestCase original = currentCase(latest, testCaseId);
        AssignmentRevision editable = editable(assignment, latest);
        cases.delete(copiedTarget(original, latest, editable));
        flush();
        return editable.getRevisionNumber();
    }

    private User actor(boolean write) {
        var identity = currentUser.require();
        User actor = users.findByIdForShare(identity.id()).orElseThrow(() -> new BadCredentialsException("Authentication is required"));
        if (!actor.isEnabled() || actor.getTokenVersion() != identity.authenticationVersion()) {
            throw new BadCredentialsException("Authentication is required");
        }
        if (write && actor.getRole() != Role.TEACHER && actor.getRole() != Role.ADMIN) {
            throw new AccessDeniedException("Teacher or administrator privileges are required");
        }
        return actor;
    }

    private Assignment assignment(UUID id, User actor, boolean write) {
        // Shared reads prevent a publish/archive/fork between checking visibility
        // and selecting test payloads. These are deliberately not read-only DB transactions.
        Assignment assignment = (write ? assignments.findByIdForUpdate(id) : assignments.findByIdForShare(id))
                .orElseThrow(() -> new AssignmentException(ASSIGNMENT_NOT_FOUND));
        if (actor.getRole() == Role.TEACHER && !assignment.getTeacher().getId().equals(actor.getId())) {
            if (write) throw new AccessDeniedException("Only the owner or an administrator can manage these test cases");
            throw new AssignmentException(ASSIGNMENT_NOT_FOUND);
        }
        if (actor.getRole() == Role.STUDENT && assignment.getStatus() != AssignmentStatus.PUBLISHED) {
            throw new AssignmentException(ASSIGNMENT_NOT_FOUND);
        }
        if (write && assignment.getStatus() == AssignmentStatus.ARCHIVED) throw new AssignmentException(ARCHIVED_ASSIGNMENT);
        return assignment;
    }

    private AssignmentRevision visibleRevision(Assignment assignment, User actor) {
        AssignmentRevision latest = revisions.findFirstByAssignmentIdOrderByRevisionNumberDesc(assignment.getId()).orElse(null);
        if (actor.getRole() == Role.STUDENT && (latest == null || latest.getSealedAt() == null)) {
            throw new AssignmentException(ASSIGNMENT_NOT_FOUND);
        }
        return latest;
    }

    private AssignmentRevision editable(Assignment assignment, AssignmentRevision latest) {
        if (latest == null || latest.getSealedAt() != null || assignment.getStatus() == AssignmentStatus.PUBLISHED) {
            assignment.setStatus(AssignmentStatus.DRAFT);
            assignments.flush();
            return gradingRevisions.append(assignment, latest);
        }
        return latest;
    }

    private TestCase currentCase(AssignmentRevision revision, UUID id) {
        if (revision == null) throw new TestCaseException(TEST_CASE_NOT_FOUND);
        return cases.findByIdAndRevisionId(id, revision.getId()).orElseThrow(() -> new TestCaseException(TEST_CASE_NOT_FOUND));
    }

    private TestCase copiedTarget(TestCase original, AssignmentRevision previous, AssignmentRevision editable) {
        return previous.getId().equals(editable.getId()) ? original
                : cases.findByRevisionIdAndExecutionOrder(editable.getId(), original.getExecutionOrder()).orElseThrow();
    }

    private void requireUniqueOrder(AssignmentRevision revision, int order, UUID excluding) {
        if (revision != null) cases.findByRevisionIdAndExecutionOrder(revision.getId(), order).ifPresent(existing -> {
            if (!existing.getId().equals(excluding)) throw new TestCaseException(DUPLICATE_EXECUTION_ORDER);
        });
    }

    private void validate(TestCaseRequest request) {
        if (request == null || !validator.validate(request).isEmpty()) throw new TestCaseException(INVALID_TEST_CASE);
    }

    private void flush() {
        try { cases.flush(); }
        catch (DataIntegrityViolationException ignored) { throw new TestCaseException(TEST_CASE_CONFLICT); }
    }

    private TestCaseView<TestCaseResponse> view(TestCase test, AssignmentRevision revision) {
        return new TestCaseView<>(TestCaseResponse.from(test), revision.getRevisionNumber());
    }
}
