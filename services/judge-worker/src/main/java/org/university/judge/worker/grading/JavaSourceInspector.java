package org.university.judge.worker.grading;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out how a single-file Java submission must be stored and started. javac insists that a public
 * top-level type lives in a file of the same name, and the JVM needs the fully qualified main class.
 * This is a best-effort reading of the source text, not a parser: when it guesses wrong the student
 * gets an ordinary compilation or runtime error, never a wrong grade for correct output.
 */
public final class JavaSourceInspector {

    private static final String IDENTIFIER = "[A-Za-z_$][A-Za-z0-9_$]*";
    private static final Pattern COMMENTS_AND_LITERALS = Pattern.compile(
            "//[^\\n]*|/\\*.*?\\*/|\"\"\".*?\"\"\"|\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'", Pattern.DOTALL);
    private static final Pattern PACKAGE = Pattern.compile(
            "(?m)^\\s*package\\s+(" + IDENTIFIER + "(?:\\s*\\.\\s*" + IDENTIFIER + ")*)\\s*;");
    private static final Pattern PUBLIC_TYPE = Pattern.compile(
            "\\bpublic\\s+(?:(?:final|abstract|sealed|non-sealed|strictfp)\\s+)*(?:class|interface|enum|record)\\s+(" + IDENTIFIER + ")");
    private static final Pattern ANY_TYPE = Pattern.compile("\\b(?:class|interface|enum|record)\\s+(" + IDENTIFIER + ")");
    private static final Pattern MAIN = Pattern.compile("\\bstatic\\s+(?:final\\s+)?void\\s+main\\s*\\(|\\bvoid\\s+main\\s*\\(");
    private static final Pattern FILE_NAME = Pattern.compile("(" + IDENTIFIER + ")\\.java", Pattern.CASE_INSENSITIVE);

    public record Layout(String fileName, String mainClass) { }

    private JavaSourceInspector() { }

    public static Layout inspect(String source, String originalFilename) {
        String code = COMMENTS_AND_LITERALS.matcher(source == null ? "" : source).replaceAll(" ");
        String packageName = first(PACKAGE, code);
        if (packageName != null) packageName = packageName.replaceAll("\\s+", "");

        String publicType = first(PUBLIC_TYPE, code);
        String fromUpload = null;
        if (originalFilename != null) {
            Matcher name = FILE_NAME.matcher(originalFilename.trim());
            if (name.matches()) fromUpload = name.group(1);
        }
        String fileStem = publicType != null ? publicType : fromUpload != null ? fromUpload : "Main";

        String mainType = typeDeclaring(code);
        if (mainType == null) mainType = fileStem;
        String mainClass = packageName == null ? mainType : packageName + "." + mainType;
        return new Layout(fileStem + ".java", mainClass);
    }

    /** The top-most type whose body contains the first main method. */
    private static String typeDeclaring(String code) {
        Matcher main = MAIN.matcher(code);
        if (!main.find()) return null;
        int mainAt = main.start();
        String candidate = null;
        Matcher type = ANY_TYPE.matcher(code);
        while (type.find() && type.start() < mainAt) {
            if (depth(code, type.start()) == 0 && encloses(code, type.end(), mainAt)) candidate = type.group(1);
        }
        return candidate;
    }

    private static int depth(String code, int until) {
        int depth = 0;
        for (int i = 0; i < until; i++) {
            char c = code.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        return depth;
    }

    private static boolean encloses(String code, int from, int position) {
        int open = code.indexOf('{', from);
        if (open < 0 || open > position) return false;
        int depth = 0;
        for (int i = open; i < position; i++) {
            char c = code.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return false;
        }
        return depth > 0;
    }

    private static String first(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }
}
