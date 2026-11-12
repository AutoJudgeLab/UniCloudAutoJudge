package org.university.judge.api.assignment.application;

import org.university.judge.api.assignment.api.AssignmentResponse;

/** The revision is returned separately as an HTTP header, never as a grading-data entity. */
public record AssignmentResult(AssignmentResponse assignment, int revisionNumber) {
}
