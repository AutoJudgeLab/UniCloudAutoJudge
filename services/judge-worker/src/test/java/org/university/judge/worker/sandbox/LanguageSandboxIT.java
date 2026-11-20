package org.university.judge.worker.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.university.judge.worker.ApiReportRules;
import org.university.judge.worker.Fixtures;
import org.university.judge.worker.grading.Grader;
import org.university.judge.worker.grading.LanguageProfiles;
import org.university.judge.worker.grading.Verdict;
import org.university.judge.worker.model.EvaluationReport;
import org.university.judge.worker.model.JudgeJob;

/** Real compilers, stdin, verdicts and timeouts for every language accepted by the API. */
@Timeout(value = 2, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class LanguageSandboxIT {
    private static Grader grader;
    private static final ThreadLocal<StringBuilder> DIAGNOSTICS = ThreadLocal.withInitial(StringBuilder::new);

    @BeforeAll
    static void prepare() throws Exception {
        var properties = Fixtures.properties();
        var languages = new LanguageProfiles(properties);
        for (String image : languages.enabledKeys().stream().map(key -> languages.find(key).orElseThrow().image())
                .distinct().toList()) {
            Process inspect = new ProcessBuilder("docker", "image", "inspect", image).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            boolean exited = inspect.waitFor(30, TimeUnit.SECONDS);
            if (!exited) inspect.destroyForcibly();
            assertTrue(exited && inspect.exitValue() == 0,
                    "Prepare toolchain image " + image + " using infrastructure/docker/worker-toolchains/compose.yml");
        }
        var sandbox = new DockerSandboxRunner(properties);
        grader = new Grader(new SandboxRunner() {
            @Override public void prepare(java.nio.file.Path directory) throws java.io.IOException {
                sandbox.prepare(directory);
            }

            @Override public RunResult run(RunRequest request) {
                var result = sandbox.run(request);
                DIAGNOSTICS.get().append(request.image()).append(' ').append(result.outcome())
                        .append(" exit=").append(result.exitCode()).append(" elapsedMs=").append(result.elapsedMillis())
                        .append('\n').append(result.stderr()).append('\n');
                return result;
            }
        }, languages, properties);
    }

    record Example(String language, String extension, String sum, String crash, String loop) {
        @Override public String toString() { return language; }
    }

    static Stream<Example> examples() {
        return Stream.of(
                new Example("JAVA", "java", """
                        public class Main { public static void main(String[] args) {
                            var s = new java.util.Scanner(System.in); System.out.println(s.nextInt() + s.nextInt());
                        } }
                        """, "public class Main { public static void main(String[] a) { throw new RuntimeException(); } }",
                        "public class Main { public static void main(String[] a) { while (true) {} } }"),
                new Example("PYTHON", "py", "a, b = map(int, input().split()); print(a + b)",
                        "raise RuntimeError('failed')", "while True: pass"),
                new Example("C", "c", """
                        #include <stdio.h>
                        int main(void) { int a,b; scanf("%d %d", &a, &b); printf("%d", a+b); }
                        """, "int main(void) { return 1; }", "int main(void) { for (;;) {} }"),
                new Example("CPP", "cpp", """
                        #include <iostream>
                        int main() { int a,b; std::cin >> a >> b; std::cout << a+b; }
                        """, "int main() { return 1; }", "int main() { for (;;) {} }"),
                new Example("JAVASCRIPT", "js", """
                        const [a,b] = require('node:fs').readFileSync(0, 'utf8').trim().split(' ').map(Number);
                        console.log(a+b);
                        """, "throw new Error('failed');", "while (true) {}"),
                new Example("TYPESCRIPT", "ts", """
                        import { readFileSync } from 'node:fs';
                        const [a,b]: number[] = readFileSync(0, 'utf8').trim().split(' ').map(Number);
                        console.log(a+b);
                        """, "throw new Error('failed');", "while (true) {}"),
                new Example("KOTLIN", "kt", """
                        package solution
                        fun main() { val (a,b) = readln().trim().split(" ").map { it.toInt() }; println(a+b) }
                        """, "fun main() { error(\"failed\") }", "fun main() { while (true) {} }"),
                new Example("GO", "go", """
                        package main
                        import "fmt"
                        func main() { var a,b int; fmt.Scan(&a, &b); fmt.Println(a+b) }
                        """, "package main; func main() { panic(\"failed\") }", "package main; func main() { for {} }"),
                new Example("RUST", "rs", """
                        use std::io::{self, Read};
                        fn main() {
                            let mut s = String::new(); io::stdin().read_to_string(&mut s).unwrap();
                            let a: Vec<i32> = s.split_whitespace().map(|v| v.parse().unwrap()).collect();
                            println!("{}", a[0]+a[1]);
                        }
                        """, "fn main() { panic!(\"failed\"); }", "fn main() { loop {} }"),
                new Example("CSHARP", "cs", """
                        var parts = Console.ReadLine()!.Split(' ');
                        Console.WriteLine(int.Parse(parts[0]) + int.Parse(parts[1]));
                        """, "return 1;", "while (true) {}"));
    }

    @ParameterizedTest(name = "{0}: stdin, score and independent test runs")
    @MethodSource("examples")
    void evaluatesEveryTest(Example example) {
        var job = Fixtures.job(example.language(), "upload." + example.extension(), example.sum(),
                Fixtures.test(0, "2 3\n", "5\n", "40"), Fixtures.test(1, "10 7\n", "0\n", "60"));
        var report = grade(job);
        assertEquals(Verdict.WRONG_ANSWER, report.verdict(), () -> DIAGNOSTICS.get().toString());
        assertEquals(List.of(Verdict.ACCEPTED, Verdict.WRONG_ANSWER), report.tests().stream().map(t -> t.status()).toList());
        assertEquals("17", report.tests().get(1).actualOutput().strip());
        assertEquals(0, new BigDecimal("40").compareTo(report.totalScore()));
    }

    @ParameterizedTest(name = "{0}: compilation failure")
    @MethodSource("examples")
    void syntaxErrorsAreCompilationErrors(Example example) {
        var report = grade(Fixtures.job(example.language(), "upload." + example.extension(), "@#$ invalid syntax !!!",
                Fixtures.test(0, "", "", "100")));
        assertEquals(Verdict.COMPILATION_ERROR, report.verdict(), () -> DIAGNOSTICS.get().toString());
        assertTrue(report.tests().isEmpty());
    }

    @ParameterizedTest(name = "{0}: runtime failure")
    @MethodSource("examples")
    void runtimeFailuresAreNotCompilationFailures(Example example) {
        var report = grade(Fixtures.job(example.language(), "upload." + example.extension(), example.crash(),
                Fixtures.test(0, "", "", "100")));
        assertEquals(Verdict.RUNTIME_ERROR, report.verdict(), () -> DIAGNOSTICS.get().toString());
    }

    @org.junit.jupiter.api.Test
    void unhandledDotnetExceptionsStayBounded() {
        var report = grade(Fixtures.job("CSHARP", "main.cs", "throw new System.Exception(\"failed\");",
                Fixtures.test(0, "", "", "100")));
        // The native .NET crash handler can stall on virtualized hosts. A runtime that
        // exits before the deadline is RUNTIME_ERROR; one that overruns it must be TLE.
        assertTrue(report.verdict() == Verdict.RUNTIME_ERROR || report.verdict() == Verdict.TIME_LIMIT_EXCEEDED,
                () -> DIAGNOSTICS.get().toString());
        if (report.verdict() == Verdict.TIME_LIMIT_EXCEEDED) assertTrue(report.executionTimeMs() >= 2000);
    }

    @ParameterizedTest(name = "{0}: timeout")
    @MethodSource("examples")
    void infiniteLoopsAreTerminated(Example example) {
        var report = grade(Fixtures.job(example.language(), "upload." + example.extension(), example.loop(),
                Fixtures.test(0, "", "", "100")));
        assertEquals(Verdict.TIME_LIMIT_EXCEEDED, report.verdict(), () -> DIAGNOSTICS.get().toString());
    }

    static Stream<Arguments> managedMemoryExamples() {
        String javascript = "const xs = []; while (true) xs.push(new Array(1000000).fill(1));";
        return Stream.of(
                Arguments.of("JAVASCRIPT", "js", javascript),
                Arguments.of("TYPESCRIPT", "ts", javascript),
                Arguments.of("KOTLIN", "kt", "fun main() { val xs = mutableListOf<LongArray>(); while (true) xs.add(LongArray(1000000)) }"),
                Arguments.of("CSHARP", "cs", "var xs = new System.Collections.Generic.List<byte[]>(); while (true) xs.Add(new byte[10000000]);"));
    }

    @ParameterizedTest(name = "{0}: managed heap limit")
    @MethodSource("managedMemoryExamples")
    void managedHeapExhaustionIsAMemoryVerdict(String language, String extension, String source) {
        var report = grade(Fixtures.job(language, "upload." + extension, source, Fixtures.test(0, "", "", "100")));
        assertEquals(Verdict.MEMORY_LIMIT_EXCEEDED, report.verdict(), () -> DIAGNOSTICS.get().toString());
    }

    private static EvaluationReport grade(JudgeJob job) {
        DIAGNOSTICS.get().setLength(0);
        var report = grader.grade(job);
        assertNull(ApiReportRules.violation(report, job.tests()));
        return report;
    }

    static Stream<String> javaRegressions() {
        return Stream.of(
                "public class Main { public static void main(String[] a) { String s = \"" + "x".repeat(30_000)
                        + "\"; System.out.println(42); } }",
                "class Helper { static void main(int n) {} } public class Main { public static void main(String[] a) { System.out.println(42); } }",
                "class Helper { public static class Nested {} } public class Main { public static void main(String... a) { System.out.println(42); } }",
                "package solution; public class Ответ { public static void main(java.lang.String a[]) { System.out.println(42); } }",
                "class Base { public static void main(String[] a) { System.out.println(42); } } public class Main extends Base {}",
                "public class Outer { public static class Entry { public static void main(String[] a) { System.out.println(42); } } }",
                "public class Main { static { System.out.println(42); } public static void main(String[] a) {} }");
    }

    @ParameterizedTest
    @MethodSource("javaRegressions")
    void javaSourceIsParsedAndEntryPointSelectedInsideTheSandbox(String source) {
        var result = grade(Fixtures.job("JAVA", "upload.java", source, Fixtures.test(0, "", "42", "100")));
        assertEquals(Verdict.ACCEPTED, result.verdict(), () -> DIAGNOSTICS.get().toString());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {125, 126, 127, 137})
    void userExitCodesAreRuntimeErrors(int exit) {
        var result = grade(Fixtures.job("PYTHON", "main.py", "import sys; sys.exit(" + exit + ")",
                Fixtures.test(0, "", "", "100")));
        assertEquals(Verdict.RUNTIME_ERROR, result.verdict(), () -> DIAGNOSTICS.get().toString());
    }
}
