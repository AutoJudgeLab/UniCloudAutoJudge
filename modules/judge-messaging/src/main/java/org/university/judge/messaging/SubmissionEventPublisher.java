package org.university.judge.messaging;

/** A successful return acknowledges transport delivery, not evaluation completion. */
public interface SubmissionEventPublisher {
    String publish(SubmissionCreatedMessageV1 event);
}

