package org.university.judge.worker.sandbox;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.university.judge.worker.config.WorkerProperties;
import org.university.judge.worker.sandbox.RunResult.Outcome;

/**
 * Runs every command in a fresh, throw-away Docker container: no network, hard memory/CPU/process
 * limits, read-only root file system, no capabilities, unprivileged user, wall-clock kill and capped output.
 *
 * <p>Access to the Docker daemon is close to root on the host. Run the worker directly on the host
 * (or with rootless Docker/Podman) and never mount docker.sock into a container that runs student code.
 */
@Component
public class DockerSandboxRunner implements SandboxRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DockerSandboxRunner.class);
    private static final int STDERR_LIMIT = 64 * 1024;
    /** docker run's own failure code: daemon unreachable, unknown image, bad flag... */
    private static final int DOCKER_FAILURE = 125;
    /** 128 + SIGKILL; without our own kill this is the kernel OOM killer enforcing --memory. */
    private static final int KILLED = 137;
    private static final long MANAGEMENT_TIMEOUT_SECONDS = 10;

    private static final String NOBODY = "65534:65534";

    private final WorkerProperties.Sandbox sandbox;
    private final int maxOutputBytes;
    private final String containerUser;

    public DockerSandboxRunner(WorkerProperties properties) {
        this.sandbox = properties.sandbox();
        this.maxOutputBytes = properties.limits().maxOutputBytes();
        this.containerUser = unprivilegedUser();
    }

    /**
     * Student code runs under the worker's own non-root uid, so the worker can remove what the compiler
     * wrote. A root worker, or a host without POSIX ids (Docker Desktop on Windows), uses "nobody".
     */
    private static String unprivilegedUser() {
        try {
            Path probe = Files.createTempFile("judge-uid-", null);
            try {
                int uid = (Integer) Files.getAttribute(probe, "unix:uid");
                int gid = (Integer) Files.getAttribute(probe, "unix:gid");
                return uid == 0 ? NOBODY : uid + ":" + gid;
            } finally {
                Files.deleteIfExists(probe);
            }
        } catch (IOException | RuntimeException noPosixIds) {
            return NOBODY;
        }
    }

    @Override
    public void prepare(Path workDir) throws IOException {
        if (!NOBODY.equals(containerUser)) return;
        try {
            Files.setPosixFilePermissions(workDir, PosixFilePermissions.fromString("rwxrwxrwx"));
        } catch (UnsupportedOperationException windowsHost) {
            // Bind mounts from a Windows host carry no POSIX permissions.
        }
    }

    @Override
    public RunResult run(RunRequest request) {
        String name = "judge-" + UUID.randomUUID();
        long started = System.nanoTime();
        Process process;
        try {
            process = new ProcessBuilder(commandLine(name, request)).start();
        } catch (IOException e) {
            return RunResult.sandboxError("Cannot start " + sandbox.dockerBinary() + ": " + e.getMessage());
        }

        BoundedCapture out = new BoundedCapture(process.getInputStream(), maxOutputBytes);
        BoundedCapture err = new BoundedCapture(process.getErrorStream(), STDERR_LIMIT);
        Thread outReader = Thread.ofVirtual().start(out);
        Thread errReader = Thread.ofVirtual().start(err);
        Thread stdinWriter = Thread.ofVirtual().start(() -> {
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(request.stdin().getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // The program may exit without reading all of its input.
            }
        });

        long deadline = started + TimeUnit.MILLISECONDS.toNanos(request.timeLimitMillis() + sandbox.startupGrace().toMillis());
        boolean finished = false;
        boolean outputLimit = false;
        try {
            while (!finished) {
                if (out.overflowed()) {
                    outputLimit = true;
                    break;
                }
                if (System.nanoTime() >= deadline) break;
                finished = process.waitFor(20, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            kill(name, process);
            return RunResult.sandboxError("Interrupted while waiting for the sandbox");
        }
        if (!finished) kill(name, process);
        join(outReader);
        join(errReader);
        join(stdinWriter);

        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        int exit = finished ? process.exitValue() : -1;
        Outcome outcome;
        if (outputLimit || (finished && out.overflowed())) outcome = Outcome.OUTPUT_LIMIT;
        else if (!finished) outcome = Outcome.TIME_LIMIT;
        else if (exit == DOCKER_FAILURE) outcome = Outcome.SANDBOX_ERROR;
        else if (exit == KILLED) outcome = Outcome.MEMORY_LIMIT;
        else outcome = Outcome.COMPLETED;
        return new RunResult(outcome, exit, out.text(), err.text(), elapsed);
    }

    List<String> commandLine(String name, RunRequest request) {
        String mount = "type=bind,source=" + request.workDir().toAbsolutePath() + ",target=/work"
                + (request.workDirWritable() ? "" : ",readonly");
        List<String> command = new ArrayList<>(List.of(
                sandbox.dockerBinary(), "run", "--rm", "-i",
                "--name", name,
                "--network", "none",
                "--memory", request.memoryMb() + "m",
                "--memory-swap", request.memoryMb() + "m",
                "--cpus", String.valueOf(sandbox.cpus()),
                "--pids-limit", String.valueOf(sandbox.pidsLimit()),
                "--read-only",
                "--tmpfs", "/tmp:rw,noexec,nosuid,size=" + sandbox.tmpfsMb() + "m",
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                "--user", containerUser,
                "--mount", mount,
                "-w", "/work",
                request.image()));
        command.addAll(request.command());
        return command;
    }

    /**
     * Stops a container that overran a limit. Every step is bounded: a sandbox that cannot be stopped
     * must not take the worker's evaluation slot with it.
     */
    private void kill(String name, Process process) {
        // Close the attached client first, which releases the container's output stream.
        process.destroyForcibly();
        if (!docker("kill", name) && !docker("rm", "-f", name)) {
            LOG.warn("Container {} could not be confirmed stopped; check it with 'docker ps'", name);
        }
    }

    /** Runs a docker management command, discarding its output, and never waits longer than a few seconds. */
    private boolean docker(String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(sandbox.dockerBinary());
        command.addAll(List.of(arguments));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            process.getOutputStream().close();
            if (process.waitFor(MANAGEMENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return process.exitValue() == 0;
            process.destroyForcibly();
            return false;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
