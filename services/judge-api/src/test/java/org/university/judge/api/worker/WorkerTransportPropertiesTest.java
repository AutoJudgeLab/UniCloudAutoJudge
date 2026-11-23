package org.university.judge.api.worker;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkerTransportPropertiesTest {
    @Test void enablingTransportRequiresASeparateStrongCredential() {
        assertThatThrownBy(() -> new WorkerTransportProperties(true, "", Duration.ofMinutes(4)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkerTransportProperties(true, "short-secret", Duration.ofMinutes(4)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("short-secret");
        String token = UUID.randomUUID().toString() + UUID.randomUUID();
        assertThat(new WorkerTransportProperties(true, token, Duration.ofMinutes(4)).toString()).doesNotContain(token);
        assertThat(new WorkerTransportProperties(false, "", Duration.ofMinutes(4)).enabled()).isFalse();
    }
}
