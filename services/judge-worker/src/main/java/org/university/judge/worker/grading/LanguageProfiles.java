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
 * All ten API languages are enabled by default. Compilers and standard libraries are preinstalled;
 * compilation and execution never download student dependencies.
 */
@Component
public class LanguageProfiles {

    private final Map<String, LanguageProfile> enabled = new LinkedHashMap<>();

    public LanguageProfiles(WorkerProperties properties) {
        WorkerProperties.Languages config = properties.languages();
        Map<String, LanguageProfile> known = new LinkedHashMap<>();
        for (LanguageProfile profile : List.of(new Java(config.javaImage()), new Python(config.pythonImage()),
                new Native("C", "main.c", config.gccImage(), List.of("gcc", "-O2", "-std=c17", "-o", "main", "main.c", "-lm")),
                new Native("CPP", "main.cpp", config.gccImage(), List.of("g++", "-O2", "-std=c++20", "-o", "main", "main.cpp")),
                new JavaScript(config.nodeImage(), false), new JavaScript(config.typescriptImage(), true),
                new Kotlin(config.kotlinImage()),
                new Native("GO", "main.go", config.goImage(), List.of("env", "GOCACHE=/work/.go-cache",
                        "GOTMPDIR=/work", "GOPATH=/tmp/go", "GOENV=off", "GOPROXY=off", "GOSUMDB=off", "GOTOOLCHAIN=local",
                        "CGO_ENABLED=0", "GOMAXPROCS=2", "go", "build", "-p=1", "-o", "main", "main.go")),
                new Native("RUST", "main.rs", config.rustImage(), List.of("rustc", "--edition=2021", "-C",
                        "opt-level=2", "-o", "main", "main.rs")),
                new CSharp(config.csharpImage()))) {
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
            return new Plan("Submission.java",
                    List.of("java", "-XX:+UseSerialGC", "-Xmx256m", "JudgeCompile.java"),
                    List.of("sh", "-c",
                            "IFS= read -r main < .judge-main || :; exec java -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:-UsePerfData -Xss16m "
                                    + "-Xmx" + memoryMb + "m -cp classes \"$main\""),
                    Map.<String, String>of("JudgeCompile.java", compilerHelper()));
        }


        private static String compilerHelper() {
            try (var source = LanguageProfiles.class.getResourceAsStream("/sandbox/JudgeCompile.java")) {
                if (source == null) throw new IllegalStateException("Missing Java compiler helper");
                return new String(source.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
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
            return new Plan("main.py", List.of("python3", "-m", "py_compile", "main.py"),
                    List.of("python3", "-B", "main.py"));
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

    private record JavaScript(String image, boolean typescript) implements LanguageProfile {
        @Override public String key() { return typescript ? "TYPESCRIPT" : "JAVASCRIPT"; }

        @Override public Plan plan(JudgeJob job, int memoryMb) {
            return new Plan(typescript ? "main.ts" : "main.js",
                    typescript ? List.of("tsc", "--target", "ES2022", "--module", "commonjs", "--types", "node",
                            "--typeRoots", "/opt/types/node_modules/@types", "--outDir", "dist", "main.ts")
                            : List.of("node", "--check", "main.js"),
                    List.of("node", "--max-old-space-size=" + memoryMb, typescript ? "dist/main.js" : "main.js"));
        }

        @Override public int memoryOverheadMb() { return 160; }

        @Override public boolean ranOutOfMemory(String stderr) {
            return stderr != null && (stderr.contains("JavaScript heap out of memory")
                    || stderr.contains("Allocation failed - process out of memory"));
        }
    }

    private record Kotlin(String image) implements LanguageProfile {
        @Override public String key() { return "KOTLIN"; }

        @Override public Plan plan(JudgeJob job, int memoryMb) {
            return new Plan("Main.kt", List.of("kotlinc", "-J-XX:+UseSerialGC", "-J-Xmx256m",
                    "Main.kt", "-include-runtime", "-d", "main.jar"),
                    List.of("java", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-XX:-UsePerfData",
                            "-Xmx" + memoryMb + "m", "-jar", "main.jar"));
        }

        @Override public int memoryOverheadMb() { return 160; }

        @Override public boolean ranOutOfMemory(String stderr) {
            return stderr != null && stderr.contains("java.lang.OutOfMemoryError");
        }
    }

    private record CSharp(String image) implements LanguageProfile {
        @Override public String key() { return "CSHARP"; }

        @Override public Plan plan(JudgeJob job, int memoryMb) {
            // Invoke Roslyn directly: SDK first-run/NuGet migrations require process-shared mutexes
            // unavailable in the sandbox. All arguments below are worker-owned, never uploaded text.
            return new Plan("Main.cs", List.of("env", "DOTNET_EnableDiagnostics=0", "DOTNET_GCHeapHardLimit=10000000",
                    "sh", "-c", """
                    set -eu
                    set -- /usr/share/dotnet/sdk/8.0.*/Roslyn/bincore/csc.dll
                    compiler="$1"
                    set -- /usr/share/dotnet/packs/Microsoft.NETCore.App.Ref/8.0.*/ref/net8.0
                    references="$1"
                    set --
                    for reference in "$references"/*.dll; do
                        set -- "$@" "-reference:$reference"
                    done
                    exec dotnet "$compiler" -nologo -langversion:12 -nullable:enable -optimize+ \
                        -out:main.dll "$@" GlobalUsings.cs Main.cs
                    """),
                    List.of("env", "DOTNET_EnableDiagnostics=0", "DOTNET_gcServer=0",
                            "DOTNET_GCHeapHardLimit=" + Long.toHexString((long) memoryMb * 1024 * 1024),
                            "dotnet", "main.dll"),
                    Map.of("GlobalUsings.cs", """
                            global using global::System;
                            global using global::System.Collections.Generic;
                            global using global::System.IO;
                            global using global::System.Linq;
                            global using global::System.Net.Http;
                            global using global::System.Threading;
                            global using global::System.Threading.Tasks;
                            """, "main.runtimeconfig.json", """
                            {"runtimeOptions":{"tfm":"net8.0","framework":{"name":"Microsoft.NETCore.App","version":"8.0.0"}}}
                            """));
        }

        @Override public int memoryOverheadMb() { return 160; }

        @Override public boolean ranOutOfMemory(String stderr) {
            return stderr != null && (stderr.contains("Out of memory") || stderr.contains("System.OutOfMemoryException"));
        }
    }
}
