// FRAMEWORK: junit4
// TEST_DIR: maven-plugin/src/test/java/org/iemop/util/sclasses
// WORKING_DIR: maven-plugin
// Source: https://github.com/gliga/ekstazi/blob/011f9a65b55954502e592cf2465f25afd964d9c3/org.ekstazi.core/src/main/java/org/ekstazi/agent/AgentLoader.java
// Source: https://github.com/TestingResearchIllinois/starts/blob/487a8005b1ec24825b18a2a149216b23fda268f8/starts-core/src/main/java/edu/illinois/starts/maven/AgentLoader.java#
// Source: https://github.com/TestingResearchIllinois/starts/blob/487a8005b1ec24825b18a2a149216b23fda268f8/starts-core/src/main/java/edu/illinois/starts/helpers/RTSUtil.java
package org.iemop.util.sclasses;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;

import org.blocktest.BTest;
import static org.blocktest.BTest.blocktest;
import static org.blocktest.types.EndAt.*;
import static org.blocktest.utils.Constant.*;

public class JDeps {
    private static File findToolsJar() {
        String javaHome = System.getProperty("java.home");

        String CLASSES_JAR_NAME = "classes.jar";
        String TOOLS_JAR_NAME = "tools.jar";

        File javaHomeFile = new File(javaHome);
        File toolsJarFile = new File(javaHomeFile, "lib" + File.separator + TOOLS_JAR_NAME);

        if (!toolsJarFile.exists()) {
            toolsJarFile = new File(System.getenv("java_home"), "lib" + File.separator + TOOLS_JAR_NAME);
        }

        if (!toolsJarFile.exists() && javaHomeFile.getAbsolutePath().endsWith(File.separator + "jre")) {
            javaHomeFile = javaHomeFile.getParentFile();
            toolsJarFile = new File(javaHomeFile, "lib" + File.separator + TOOLS_JAR_NAME);
        }

        if (!toolsJarFile.exists() && isMac() && javaHomeFile.getAbsolutePath().endsWith(File.separator + "Home")) {
            javaHomeFile = javaHomeFile.getParentFile();
            toolsJarFile = new File(javaHomeFile, "Classes" + File.separator + CLASSES_JAR_NAME);
        }

        return toolsJarFile;
    }

    private static boolean isMac() {
        return System.getProperty("os.name").toLowerCase().contains("mac");
    }

    public static StringWriter loadAndRunJdeps(String[] args) {
        StringWriter output = new StringWriter();
        try {
            File toolsJarFile = findToolsJar();
            if (!toolsJarFile.exists()) {
                // Java 9+, load jdeps through java.util.spi.ToolProvider
                Class<?> toolProvider = ClassLoader.getSystemClassLoader().loadClass("java.util.spi.ToolProvider");
                Object jdeps = toolProvider.getMethod("findFirst", String.class).invoke(null, "jdeps");
                jdeps = Optional.class.getMethod("get").invoke(jdeps);
                toolProvider.getMethod("run", PrintWriter.class, PrintWriter.class, String[].class)
                        .invoke(jdeps, new PrintWriter(output), new PrintWriter(output), args);
            } else {
                // Java 8, load tools.jar
                URLClassLoader loader = new URLClassLoader(new URL[]{toolsJarFile.toURI().toURL()},
                        ClassLoader.getSystemClassLoader());
                Class<?> jdepsMain = loader.loadClass("com.sun.tools.jdeps.Main");
                jdepsMain.getMethod("run", String[].class, PrintWriter.class)
                        .invoke(null, args, new PrintWriter(output));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return output;
    }

    public static Map<Klass, Set<Klass>> runJdeps(String[] args) {
        StringWriter output = loadAndRunJdeps(args);
        // jdeps can return an empty output when run on .jar files with no .class files
        return output.getBuffer().length() != 0 ? getDepsFromJdepsOutput(output) : new HashMap<>();
    }

    public static Map<Klass, Set<Klass>> getDepsFromJdepsOutput(StringWriter jdepsOutput) {
        Map<Klass, Set<Klass>> deps = new HashMap<>();
        List<String> lines = Arrays.asList(jdepsOutput.toString().split(System.lineSeparator()));

        Klass currentClass = null;
        for (String line : lines) {
            if (!line.startsWith(" "))
                continue;

            line = line.trim();
            if (!line.startsWith("->")) {
                // match
                // a.b.C (classes)
                // a.b.C (foo-1.0.0.jar)
                // BLOCKTEST EVAL: https://github.com/SoftEngResearch/imop/blob/fc59fb1ef0b8478e795bb6dc29bef66a3a255d43/maven-plugin/src/main/java/org/iemop/util/sclasses/JDeps.java#L86-L96
                blocktest().given(currentClass, null).given(line, "a.b.C (classes)")
                        .checkEq(currentClass.toString(), "classes#a.b.C")
                        .checkEq(currentClass.getLocationName(), "classes")
                        .checkEq(currentClass.getClassName(), "a.b.C").end(FIRST_BLOCK);
                blocktest().given(currentClass, null).given(line, "a.b.C(classes)").checkTrue(currentClass == null);
                String[] parts = line.split(" ");
                if (parts.length == 2) {
                    currentClass = new Klass(parts[1].substring(1, parts[1].length() - 1), parts[0]);
                }
                continue;
            }

            if (currentClass == null)
                continue;

            // match
            // -> a.b.C  classes
            // -> a.b.C  foo-1.0.0.jar
            // -> a.b.C  not found
            // BLOCKTEST EVAL: https://github.com/SoftEngResearch/imop/blob/fc59fb1ef0b8478e795bb6dc29bef66a3a255d43/maven-plugin/src/main/java/org/iemop/util/sclasses/JDeps.java#L105-L110
            blocktest().given(line, "-> a.b.C foo/classes").given(currentClass, new Klass("foo", "bar"))
                    .checkEq(deps.get(new Klass("foo", "bar")).iterator().next().getLocationName(), "foo/classes");
            blocktest().given(line, "-> a.b.C not found").given(currentClass, new Klass("foo", "bar"))
                    .checkEq(deps.get(new Klass("foo", "bar")), null);
            String[] parts = line.split(" ");
            int partsLength = parts.length;
            if (partsLength < 3 || parts[partsLength - 1].equals("found"))
                continue;

            Klass usedClass = new Klass(parts[partsLength - 1], parts[1]);
            deps.computeIfAbsent(currentClass, k -> new HashSet<>()).add(usedClass);
        }

        return deps;
    }
}
