package org.university.judge.worker.sandbox;

import java.nio.file.Path;
import java.util.List;

/**
 * @param image            container image; it must already be pulled, the sandbox has no network
 * @param workDir          host directory mounted as /work, which is also the working directory
 * @param workDirWritable  true only for compilation, which has to write its output
 * @param command          command line inside the container, with paths relative to /work
 * @param stdin            text fed to the program's standard input
 * @param timeLimitMillis  wall-clock limit for the program itself
 * @param memoryMb         hard memory limit of the container
 */
public record RunRequest(String image, Path workDir, boolean workDirWritable, List<String> command, String stdin,
        long timeLimitMillis, int memoryMb) {
    public RunRequest {
        command = List.copyOf(command);
        stdin = stdin == null ? "" : stdin;
    }
}
