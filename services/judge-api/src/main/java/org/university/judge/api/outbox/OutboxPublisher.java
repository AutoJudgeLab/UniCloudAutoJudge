package org.university.judge.api.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class OutboxPublisher {
    private final OutboxDelivery delivery;
    private final OutboxProperties properties;
    private final MeterRegistry meters;
    public OutboxPublisher(OutboxDelivery delivery, OutboxProperties properties, MeterRegistry meters) {
        this.delivery = delivery; this.properties = properties; this.meters = meters;
    }
    public void publishBatch() {
        try {
            for (int i = 0; i < properties.batchSize(); i++) if (!delivery.deliverNext()) break;
        } catch (RuntimeException failure) {
            meters.counter("judge.outbox.publishing.failures", "reason", "DATABASE_OR_COMMIT").increment();
            LoggerFactory.getLogger(OutboxPublisher.class).atWarn().addKeyValue("reason", "DATABASE_OR_COMMIT")
                    .log("Outbox batch interrupted; durable events will retry");
        }
    }
}

