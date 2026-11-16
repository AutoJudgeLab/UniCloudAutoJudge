package org.university.judge.worker.sandbox;

import java.io.IOException;
import java.nio.file.Path;

/** Runs one command in isolation. An interface so the grader can be tested without Docker. */
@FunctionalInterface
public interface SandboxRunner {

    RunResult run(RunRequest request);

    /** Makes a freshly created work directory usable by the sandboxed process. */
    default void prepare(Path workDir) throws IOException {
    }
}
