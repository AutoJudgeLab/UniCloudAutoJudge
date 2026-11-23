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
        try {
            return execute(name, request);
        } finally {
            // Cleanup must also run when the grading thread is interrupted during shutdown.
            boolean interrupted = Thread.interrupted();
            if (!docker("rm", "-f", name)) LOG.debug("Container {} already absent or removal failed", name);
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private RunResult execute(String name, RunRequest request) {
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
        boolean workspaceLimit = false;
        long nextWorkspaceCheck = started;
        try {
            while (!finished) {
                if (request.workDirWritable() && System.nanoTime() >= nextWorkspaceCheck) {
                    workspaceLimit = workspaceExceeded(request.workDir());
                    if (workspaceLimit) break;
                    nextWorkspaceCheck = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
                }
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
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        if (!finished) kill(name, process);
        join(outReader);
        join(errReader);
        join(stdinWriter);

        int exit = finished ? process.exitValue() : -1;
        Outcome outcome;
        if (workspaceLimit || (request.workDirWritable() && workspaceExceeded(request.workDir()))) outcome = Outcome.WORKSPACE_LIMIT;
        else if (outputLimit || (finished && out.overflowed())) outcome = Outcome.OUTPUT_LIMIT;
        else if (!finished) outcome = Outcome.TIME_LIMIT;
        else {
            ContainerState state = inspect(name);
            if (state == null || !state.status().equals("exited") || state.startFailed()) {
                outcome = Outcome.SANDBOX_ERROR;
            } else {
                exit = state.exitCode();
                // Docker daemon timestamps use wall time and can jump when a VM resumes or
                // its clock is adjusted. They cannot exceed the locally observed monotonic run.
                if (state.elapsedMillis() > 0 && state.elapsedMillis() <= elapsed) elapsed = state.elapsedMillis();
                outcome = state.oomKilled() ? Outcome.MEMORY_LIMIT
                        : elapsed > request.timeLimitMillis() ? Outcome.TIME_LIMIT : Outcome.COMPLETED;
            }
        }
        return new RunResult(outcome, exit, out.text(), err.text(), elapsed);
    }

    List<String> commandLine(String name, RunRequest request) {
        String mount = "type=bind,source=" + request.workDir().toAbsolutePath() + ",target=/work"
                + (request.workDirWritable() ? "" : ",readonly");
        List<String> command = new ArrayList<>(List.of(
                sandbox.dockerBinary(), "run", "--pull", "never", "-i",
                "--name", name,
                "--network", "none",
                "--memory", request.memoryMb() + "m",
                "--memory-swap", request.memoryMb() + "m",
                "--cpus", String.valueOf(sandbox.cpus()),
                "--pids-limit", String.valueOf(sandbox.pidsLimit()),
                "--ulimit", "fsize=" + sandbox.workspaceBytes() + ":" + sandbox.workspaceBytes(),
                "--ulimit", "core=0:0",
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

    private boolean workspaceExceeded(Path directory) {
        class Budget extends java.nio.file.SimpleFileVisitor<Path> {
            long bytes;
            int entries;
            boolean exceeded;
            private java.nio.file.FileVisitResult count(java.nio.file.attribute.BasicFileAttributes attributes) {
                exceeded = ++entries > sandbox.workspaceFiles()
                        || (attributes.isRegularFile() && attributes.size() > sandbox.workspaceBytes() - bytes);
                if (exceeded) return java.nio.file.FileVisitResult.TERMINATE;
                if (attributes.isRegularFile()) bytes += attributes.size();
                return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult preVisitDirectory(Path path,
                    java.nio.file.attribute.BasicFileAttributes attributes) { return count(attributes); }
            @Override public java.nio.file.FileVisitResult visitFile(Path path,
                    java.nio.file.attribute.BasicFileAttributes attributes) { return count(attributes); }
            @Override public java.nio.file.FileVisitResult visitFileFailed(Path path, IOException failure) throws IOException {
                // Compilers (notably Go) remove intermediate directories during a scan.
                if (!(failure instanceof java.nio.file.NoSuchFileException)) throw failure;
                return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult postVisitDirectory(Path path, IOException failure) throws IOException {
                if (failure != null && !(failure instanceof java.nio.file.NoSuchFileException)) throw failure;
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        }
        var budget = new Budget();
        try {
            Files.walkFileTree(directory, budget);
            return budget.exceeded;
        } catch (IOException inaccessible) {
            // A filesystem failure is infrastructure trouble, not proof of excessive output.
            throw new java.io.UncheckedIOException("Could not account for compiler workspace", inaccessible);
        }
    }

    private record ContainerState(String status, int exitCode, boolean oomKilled,
            boolean startFailed, long elapsedMillis) { }

    private ContainerState inspect(String name) {
        try {
            Process process = new ProcessBuilder(sandbox.dockerBinary(), "inspect", "--format",
                    "{{.State.Status}}|{{.State.ExitCode}}|{{.State.OOMKilled}}|{{.State.StartedAt}}|{{.State.FinishedAt}}|{{if .State.Error}}error{{else}}ok{{end}}",
                    name).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            var capture = new BoundedCapture(process.getInputStream(), 4096);
            Thread reader = Thread.ofVirtual().start(capture);
            if (!process.waitFor(MANAGEMENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                join(reader);
                return null;
            }
            join(reader);
            if (process.exitValue() != 0) return null;
            String[] state = capture.text().strip().split("\\|", -1);
            return new ContainerState(state[0], Integer.parseInt(state[1]), Boolean.parseBoolean(state[2]),
                    !state[5].equals("ok"), Math.max(0, java.time.Duration.between(
                            java.time.Instant.parse(state[3]), java.time.Instant.parse(state[4])).toMillis()));
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
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
