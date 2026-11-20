import com.sun.source.tree.ClassTree;
import com.sun.source.util.JavacTask;
import java.lang.reflect.Modifier;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.ToolProvider;

/** Trusted helper executed ONLY inside the bounded compilation container. */
class JudgeCompile {
    public static void main(String[] args) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var files = compiler.getStandardFileManager(null, null, java.nio.charset.StandardCharsets.UTF_8)) {
            var task = (JavacTask) compiler.getTask(null, files, null, List.of("-proc:none"), null,
                    files.getJavaFileObjects("Submission.java"));
            var unit = task.parse().iterator().next();
            String prefix = unit.getPackageName() == null ? "" : unit.getPackageName() + ".";
            var types = new ArrayList<String>();
            String filename = "Submission.java";
            for (var declaration : unit.getTypeDecls()) {
                if (declaration instanceof ClassTree type) {
                    String name = type.getSimpleName().toString();
                    if (type.getModifiers().getFlags().contains(javax.lang.model.element.Modifier.PUBLIC)) {
                        filename = name + ".java";
                        types.addFirst(prefix + name);
                    } else {
                        types.add(prefix + name);
                    }
                }
            }
            Files.createDirectories(Path.of("source"));
            Files.createDirectories(Path.of("classes"));
            Path source = Path.of("source", filename);
            Files.copy(Path.of("Submission.java"), source);
            int exit = compiler.run(null, null, null, "-encoding", "UTF-8", "-proc:none",
                    "-nowarn", "-d", "classes", source.toString());
            if (exit != 0) System.exit(exit);
            // Nested static entry points are also valid JVM launch targets. Prefer the
            // source's top-level types, then inspect remaining binary names deterministically.
            try (var compiled = Files.walk(Path.of("classes"))) {
                compiled.filter(path -> path.toString().endsWith(".class"))
                        .map(path -> Path.of("classes").relativize(path).toString()
                                .replace(java.io.File.separatorChar, '.'))
                        .map(name -> name.substring(0, name.length() - 6)).sorted()
                        .filter(name -> !types.contains(name)).forEach(types::add);
            }
            // Never initialize submitted classes while selecting the launcher target.
            try (var loader = new URLClassLoader(new java.net.URL[]{Path.of("classes").toUri().toURL()},
                    ClassLoader.getPlatformClassLoader())) {
                for (String name : types) {
                    try {
                        var main = Class.forName(name, false, loader).getMethod("main", String[].class);
                        if (Modifier.isStatic(main.getModifiers()) && main.getReturnType() == void.class) {
                            Files.writeString(Path.of(".judge-main"), name);
                            return;
                        }
                    } catch (ReflectiveOperationException | LinkageError ignored) { }
                }
            }
            // Compilation without a valid entry point becomes an ordinary JVM runtime error.
            Files.writeString(Path.of(".judge-main"), types.isEmpty() ? "Main" : types.getFirst());
        }
    }
}
