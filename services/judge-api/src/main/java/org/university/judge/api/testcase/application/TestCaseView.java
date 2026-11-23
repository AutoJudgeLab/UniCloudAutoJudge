package org.university.judge.api.testcase.application;

/** Internal response envelope; controllers expose the revision in an HTTP header. */
public record TestCaseView<T>(T body, int revisionNumber) { }
