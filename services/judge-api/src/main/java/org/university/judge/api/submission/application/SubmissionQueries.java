package org.university.judge.api.submission.application;

import java.util.ArrayList;
import java.util.UUID;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.university.judge.api.assignment.domain.AssignmentException;
import org.university.judge.api.assignment.domain.AssignmentStatus;
import org.university.judge.api.submission.api.SubmissionListRequest;
import org.university.judge.api.submission.api.SubmissionPageResponse;
import org.university.judge.api.submission.api.SubmissionResponse;
import org.university.judge.api.submission.domain.Submission;
import org.university.judge.api.submission.domain.SubmissionException;
import org.university.judge.api.submission.persistence.SubmissionRepository;
import org.university.judge.api.user.Role;

@Service
@Transactional(readOnly = true)
@PreAuthorize("isAuthenticated()")
public class SubmissionQueries {
    private final SubmissionRepository submissions;
    private final SubmissionAccess access;
    public SubmissionQueries(SubmissionRepository submissions, SubmissionAccess access) { this.submissions = submissions; this.access = access; }

    public SubmissionResponse get(UUID id) {
        var actor = access.actor(false, false);
        var submission = submissions.findById(id).orElseThrow(() -> new SubmissionException(SubmissionException.Code.SUBMISSION_NOT_FOUND));
        if ((actor.getRole() == Role.STUDENT && !submission.getStudent().getId().equals(actor.getId()))
                || (actor.getRole() == Role.TEACHER && !submission.getAssignment().getTeacher().getId().equals(actor.getId()))) {
            throw new SubmissionException(SubmissionException.Code.SUBMISSION_NOT_FOUND);
        }
        return SubmissionResponse.from(submission);
    }

    public SubmissionPageResponse mine(SubmissionListRequest request) { return list(null, true, request); }

    public SubmissionPageResponse forAssignment(UUID assignmentId, SubmissionListRequest request) { return list(assignmentId, false, request); }

    private SubmissionPageResponse list(UUID assignmentId, boolean ownOnly, SubmissionListRequest request) {
        var actor = access.actor(false, false);
        if (assignmentId != null) {
            var assignment = access.assignment(assignmentId, false);
            boolean invisible = actor.getRole() == Role.TEACHER && !assignment.getTeacher().getId().equals(actor.getId());
            if (actor.getRole() == Role.STUDENT && assignment.getStatus() != AssignmentStatus.PUBLISHED
                    && !submissions.existsByAssignmentIdAndStudentId(assignmentId, actor.getId())) invisible = true;
            if (invisible) throw new AssignmentException(AssignmentException.Reason.ASSIGNMENT_NOT_FOUND);
        }
        Specification<Submission> scope = (root, query, builder) -> {
            var predicates = new ArrayList<Predicate>();
            if (ownOnly || actor.getRole() == Role.STUDENT) predicates.add(builder.equal(root.get("student").get("id"), actor.getId()));
            else if (actor.getRole() == Role.TEACHER) predicates.add(builder.equal(root.get("assignment").get("teacher").get("id"), actor.getId()));
            if (assignmentId != null) predicates.add(builder.equal(root.get("assignment").get("id"), assignmentId));
            if (request.assignmentId() != null) predicates.add(builder.equal(root.get("assignment").get("id"), request.assignmentId()));
            if (request.status() != null) predicates.add(builder.equal(root.get("status"), request.status()));
            if (request.submittedFrom() != null) predicates.add(builder.greaterThanOrEqualTo(root.get("submittedAt"), request.submittedFrom()));
            if (request.submittedBefore() != null) predicates.add(builder.lessThan(root.get("submittedAt"), request.submittedBefore()));
            if (request.programmingLanguage() != null) predicates.add(builder.equal(root.get("programmingLanguage"), request.programmingLanguage().name()));
            return builder.and(predicates.toArray(Predicate[]::new));
        };
        String[] sorting = request.sort().split(",");
        Sort sort = Sort.by(Sort.Direction.fromString(sorting[1]), sorting[0]);
        if (!sorting[0].equals("id")) sort = sort.and(Sort.by("id"));
        return SubmissionPageResponse.from(submissions.findAll(scope, PageRequest.of(request.page(), request.size(), sort)).map(SubmissionResponse::from));
    }
}
