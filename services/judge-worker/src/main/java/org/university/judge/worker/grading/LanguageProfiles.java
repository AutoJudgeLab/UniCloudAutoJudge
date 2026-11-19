package org.university.judge.worker.grading;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.university.judge.worker.config.WorkerProperties;
import org.university.judge.worker.model.JudgeJob;

/**
 * Languages this worker can really evaluate. The API accepts ten source formats; only the ones listed
 * in judge.worker.languages.enabled are graded, everything else ends as INTERNAL_ERROR.
 * Java is the agreed first language. Python, C and C++ are ready but disabled until the team enables them.
 */
@Component
public class LanguageProfiles {

    private final Map<String, LanguageProfile> enabled = new LinkedHashMap<>();

    public LanguageProfiles(WorkerProperties properties) {
        WorkerProperties.Languages config = properties.languages();
        Map<String, LanguageProfile> known = new LinkedHashMap<>();
        for (LanguageProfile profile : List.of(new Java(config.javaImage()), new Python(config.pythonImage()),
                new Native("C", "main.c", config.gccImage(), List.of("gcc", "-O2", "-std=c17", "-o", "main", "main.c", "-lm")),
                new Native("CPP", "main.cpp", config.gccImage(), List.of("g++", "-O2", "-std=c++20", "-o", "main", "main.cpp")))) {
            known.put(profile.key(), profile);
        }
        for (String name : config.enabled()) {
            String key = name.trim().toUpperCase(Locale.ROOT);
            if (key.isEmpty()) continue;
            LanguageProfile profile = known.get(key);
            if (profile == null) throw new IllegalArgumentException("No evaluator exists for language " + key);
            enabled.put(key, profile);
        }
    }

    public Optional<LanguageProfile> find(String programmingLanguage) {
        return programmingLanguage == null ? Optional.empty() : Optional.ofNullable(enabled.get(programmingLanguage));
    }

    public List<String> enabledKeys() {
        return List.copyOf(enabled.keySet());
    }

    private record Java(String image) implements LanguageProfile {
        @Override public String key() { return "JAVA"; }

        @Override public Plan plan(JudgeJob job, int memoryMb) {
            JavaSourceInspector.Layout layout = JavaSourceInspector.inspect(job.source(), job.originalFilename());
            return new Plan(layout.fileName(),
                    List.of("javac", "-J-XX:+UseSerialGC", "-J-Xmx256m", "-encoding", "UTF-8", "-proc:none", "-nowarn",
                            "-d", "classes", layout.fileName()),
                    List.of("java", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-XX:-UsePerfData", "-Xss16m",
                            "-Xmx" + memoryMb + "m", "-cp", "classes", layout.mainClass()));
        }

        /** Metaspace, code cache, thread stacks: the heap gets the whole limit, the container a little more. */
        @Override public int memoryOverheadMb() { return 160; }

        @Override public boolean ranOutOfMemory(String stderr) {
            return stderr != null && stderr.contains("java.lang.OutOfMemoryError");
        }
    }

    private record Python(String image) implements LanguageProfile {
        @Override public String key() { return "PYTHON"; }

        @Override public Plan plan(JudgeJob job, int memoryMb) {
            return new Plan("main.py", List.of(), List.of("python3", "-B", "main.py"));
        }

        @Override public boolean ranOutOfMemory(String stderr) {
            return stderr != null && stderr.contains("MemoryError");
        }
    }

    private record Native(String key, String fileName, String image, List<String> compile) implements LanguageProfile {
        @Override public Plan plan(JudgeJob job, int memoryMb) {
            return new Plan(fileName, compile, List.of("./main"));
        }
    }
}
