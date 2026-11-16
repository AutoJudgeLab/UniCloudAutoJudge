package org.university.judge.worker.grading;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.university.judge.worker.grading.JavaSourceInspector.Layout;

class JavaSourceInspectorTest {

    @Test
    void publicClassDecidesTheFileName() {
        Layout layout = JavaSourceInspector.inspect("public class Solution { public static void main(String[] a) {} }", "upload.java");
        assertEquals(new Layout("Solution.java", "Solution"), layout);
    }

    @Test
    void packageIsPartOfTheMainClass() {
        Layout layout = JavaSourceInspector.inspect(
                "package bg.tu.hw1;\nimport java.util.*;\npublic final class Main { public static void main(String[] a) {} }", "Main.java");
        assertEquals(new Layout("Main.java", "bg.tu.hw1.Main"), layout);
    }

    @Test
    void mainInANonPublicClassIsFound() {
        Layout layout = JavaSourceInspector.inspect(
                "class Helper { int f() { return 1; } }\nclass Program { public static void main(String[] args) { } }", "Task1.java");
        assertEquals(new Layout("Task1.java", "Program"), layout);
    }

    @Test
    void nestedTypesAndTextInCommentsOrStringsDoNotConfuseIt() {
        String source = """
                // public class Fake { static void main() }
                /* class AlsoFake { public static void main(String[] a) {} } */
                public class Outer {
                    static String s = "class InString { public static void main(";
                    static class Inner { void run() { } }
                    public static void main(String[] args) { }
                }
                """;
        assertEquals(new Layout("Outer.java", "Outer"), JavaSourceInspector.inspect(source, "x.java"));
    }

    @Test
    void fallsBackToTheUploadedNameAndThenToMain() {
        assertEquals(new Layout("Task2.java", "Task2"), JavaSourceInspector.inspect("class Task2 { }", "Task2.java"));
        assertEquals(new Layout("Main.java", "Main"), JavaSourceInspector.inspect("", "../../etc/passwd"));
        assertEquals(new Layout("Main.java", "Main"), JavaSourceInspector.inspect(null, null));
    }
}
