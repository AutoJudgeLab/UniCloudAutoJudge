package org.university.judge.worker.grading;

import java.util.List;
import org.university.judge.worker.model.JudgeJob;

/** How one programming language is stored, compiled and started inside the sandbox. */
public interface LanguageProfile {

    /** The API's ProgrammingLanguage name, e.g. JAVA. */
    String key();

    String image();

    /** Decides file name and start command for this particular submission. */
    Plan plan(JudgeJob job, int memoryMb);

    /** Memory the runtime itself needs on top of the program's own limit. */
    default int memoryOverheadMb() {
        return 0;
    }

    /** Lets a runtime that reports exhaustion itself (a JVM OutOfMemoryError) count as a memory verdict. */
    default boolean ranOutOfMemory(String stderr) {
        return false;
    }

    /**
     * @param sourceFileName  file the source is written to inside the work directory
     * @param compileCommand  empty for interpreted languages
     * @param runCommand      started once per test, with the test input on stdin
     */
    record Plan(String sourceFileName, List<String> compileCommand, List<String> runCommand) { }
}
