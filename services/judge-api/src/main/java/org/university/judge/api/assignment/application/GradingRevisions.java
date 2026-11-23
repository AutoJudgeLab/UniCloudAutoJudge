package org.university.judge.api.assignment.application;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.university.judge.api.assignment.domain.Assignment;
import org.university.judge.api.assignment.domain.AssignmentRevision;
import org.university.judge.api.assignment.persistence.AssignmentRevisionRepository;
import org.university.judge.api.testcase.domain.TestCase;
import org.university.judge.api.testcase.persistence.TestCaseRepository;

/** Shared revision operations; callers hold the assignment write lock first. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class GradingRevisions {
    private final AssignmentRevisionRepository revisions;
    private final TestCaseRepository testCases;

    public GradingRevisions(AssignmentRevisionRepository revisions, TestCaseRepository testCases) {
        this.revisions = revisions;
        this.testCases = testCases;
    }

    public AssignmentRevision latestLocked(UUID assignmentId) {
        List<AssignmentRevision> history = revisions.findByAssignmentIdOrderByRevisionNumber(assignmentId);
        return history.isEmpty() ? null : history.getLast();
    }

    public AssignmentRevision append(Assignment assignment, AssignmentRevision previous) {
        int number = previous == null ? 1 : Math.addExact(previous.getRevisionNumber(), 1);
        AssignmentRevision revision = revisions.saveAndFlush(new AssignmentRevision(assignment, number));
        if (previous != null) {
            for (TestCase original : testCases.findByRevisionIdOrderByExecutionOrder(previous.getId())) {
                testCases.save(new TestCase(revision, original.getInputData(), original.getExpectedOutput(), original.getPoints(),
                        original.isHidden(), original.getExecutionOrder()));
            }
            testCases.flush();
        }
        return revision;
    }
}
