package org.university.judge.worker.queue;

import java.util.Map;

/**
 * One stream record as delivered to this consumer.
 *
 * @param recordId       Redis transport ID; changes on every re-publication and is not a deduplication key
 * @param fields         raw wire fields of SubmissionCreatedMessageV1
 * @param deliveryCount  1 for a first delivery, higher after the record was reclaimed
 */
public record QueuedEvent(String recordId, Map<String, String> fields, long deliveryCount) {
    public QueuedEvent {
        fields = Map.copyOf(fields);
    }
}
