package org.university.judge.worker.sandbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Drains a stream but keeps at most {@code limit} bytes, so endless output cannot exhaust worker memory. */
final class BoundedCapture implements Runnable {
    private final InputStream in;
    private final int limit;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private volatile boolean overflow;

    BoundedCapture(InputStream in, int limit) {
        this.in = in;
        this.limit = limit;
    }

    @Override
    public void run() {
        byte[] chunk = new byte[8192];
        try {
            int read;
            // Keep reading to the end even after the limit: a full pipe would block the docker client,
            // and a daemon blocked on that stream cannot stop the container.
            while ((read = in.read(chunk)) >= 0) {
                if (overflow) continue;
                synchronized (buffer) {
                    int room = limit - buffer.size();
                    if (read > room) {
                        buffer.write(chunk, 0, Math.max(room, 0));
                        overflow = true;
                    } else {
                        buffer.write(chunk, 0, read);
                    }
                }
            }
        } catch (IOException ignored) {
            // The stream closes when the container is killed.
        }
    }

    boolean overflowed() {
        return overflow;
    }

    String text() {
        synchronized (buffer) {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }
}
