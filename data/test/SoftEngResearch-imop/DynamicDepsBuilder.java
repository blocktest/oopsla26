// FRAMEWORK: junit4
// TEST_DIR: maven-plugin/src/test/java/org/iemop/util/dclasses
// WORKING_DIR: maven-plugin
package org.iemop.util.dclasses;

import org.iemop.instrumentation.library.Jar;
import org.iemop.util.Config;
import org.iemop.util.TimerUtil;
import org.iemop.util.Util;
import org.iemop.util.smethods.DependencyResults;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.blocktest.BTest;
import static org.blocktest.BTest.blocktest;
import static org.blocktest.types.EndAt.*;
import static org.blocktest.utils.Constant.*;

public class DynamicDepsBuilder {
    public static Map<Jar, Set<String>> forceInclude = null;

    public static Map<Jar, Set<String>> getForceInclude() {
        if (forceInclude == null) {
            if (Files.exists(Paths.get(Util.getArtifactDir() + File.separator + "classes-loaded-only-with-mop.iemop"))) {
                forceInclude = (Map<Jar, Set<String>>) Util.readFromDisk(Util.getArtifactDir() + File.separator + "classes-loaded-only-with-mop.iemop");
            }

            if (forceInclude == null) {
                forceInclude = new HashMap<>();
            }
        }

        return forceInclude;
    }

    public DependencyResults build(String logFilename, List<Jar> jars, boolean saveToDisk, boolean firstRun) {
        long startBuilder = System.currentTimeMillis();
        System.out.println("Checking loaded classes using log " + logFilename);

        List<String> lines;
        // BLOCKTEST EVAL: https://github.com/SoftEngResearch/imop/blob/fc59fb1ef0b8478e795bb6dc29bef66a3a255d43/maven-plugin/src/main/java/org/iemop/util/dclasses/DynamicDepsBuilder.java#L40-L49
        // @blocktest("testA").noInit(lines).given(logFilename, "a").mock("build(..)", null).checkTrue(lines.isEmpty()).end(FIRST_BLOCK);
        // @blocktest("testB").noInit(lines).given(logFilename, "b").mock("build(..)", null).given(loadedClassesDumpStream, System.getProperty("user.dir") + "/target/classes/org/iemop/RTSMojo.class", "String").checkTrue(lines == null).end(FIRST_BLOCK);
        try {
            lines = Files.readAllLines(Paths.get(logFilename));
        } catch(Exception e) {
            String loadedClassesDumpStream = Util.getDynamicCIAFile(false) + ".loaded";
            if (Files.exists(Paths.get(loadedClassesDumpStream)))
                return build(loadedClassesDumpStream, jars, saveToDisk, firstRun);

            e.printStackTrace();
            lines = new ArrayList<>();
        }

        Map<String, Jar> pathToJar = new HashMap<>();
        for (Jar jar : jars) {
            pathToJar.put(jar.jarPath, jar);
        }

        Map<Jar, Set<String>> jarToClasses = new HashMap<>();

        Pattern pattern = Pattern.compile("\\[Loaded (.*) from file:(.*)\\]");
        boolean matched = false;
        for (String line : lines) {
            Matcher match = pattern.matcher(line);
            if (match.find()) {
                matched = true;
                // BLOCKTEST EVAL: https://github.com/SoftEngResearch/imop/blob/fc59fb1ef0b8478e795bb6dc29bef66a3a255d43/maven-plugin/src/main/java/org/iemop/util/dclasses/DynamicDepsBuilder.java#L63-L69
                blocktest()
                        .given(pattern, Pattern.compile("\\[Loaded (.*) from file:(.*)\\]"))
                        .given(line, "[Loaded hi from file:foo]").setup(() -> {
                    match.find();
                    pathToJar.put("foo", new Jar("a", "b", "c", "d"));
                }).checkTrue(!jarToClasses.isEmpty())
                        .checkEq(jarToClasses.get(new Jar("a", "b", "c", "d")).iterator().next(), "hi");

                blocktest()
                        .given(pattern, Pattern.compile("\\[Loaded (.*) from file:(.*)\\]"))
                        .given(line, "[Loaded hi from file:foo]").setup(() -> {
                            match.find();
                        }).checkTrue(jarToClasses.isEmpty());

                String className = match.group(1);
                String jarPath = match.group(2);
                if (pathToJar.containsKey(jarPath)) {
                    jarToClasses.computeIfAbsent(pathToJar.get(jarPath), k -> new HashSet<>()).add(className.replace(".", "/"));
                }
            } else if (!matched && (line.contains("directly writing to native stream in forked") || line.contains("Corrupted stdin stream in forked JVM"))) {
                for (String word : line.split(" ")) {
                    if (word.endsWith(".dumpstream")) {
                        if (Config.lazy) {
                            // copy word to artifact directory, otherwise mvn clean will clean the loaded classes list
                            try {
                                String loadedClassesDumpStream = Util.getDynamicCIAFile(false) + ".loaded";
                                if (!Files.exists(Paths.get(word)))
                                    // dumpstream doesn't exit (e.g., mvn cleaned), check .loaded directly
                                    return build(loadedClassesDumpStream, jars, saveToDisk, firstRun);

                                Files.copy(Paths.get(word), Paths.get(loadedClassesDumpStream), StandardCopyOption.REPLACE_EXISTING);
                                System.out.println("Backing up " + word + " to " + loadedClassesDumpStream + " for future run");
                            } catch (Exception e) { e.printStackTrace(); }
                        }
                        return build(word, jars, saveToDisk, firstRun);
                    }
                }
            }
        }
        
        try {
            for (Map.Entry<Jar, Set<String>> entry : getForceInclude().entrySet()) {
                // BLOCKTEST EVAL: https://github.com/SoftEngResearch/imop/blob/fc59fb1ef0b8478e795bb6dc29bef66a3a255d43/maven-plugin/src/main/java/org/iemop/util/dclasses/DynamicDepsBuilder.java#L93-L99
                blocktest().noInit(entry).given(entry.getKey(), new Jar("a", "b", "c", "d"), "Jar")
                        .given(entry.getValue(), new HashSet<>(Arrays.asList("1", "2", "3")), "HashSet<String>").checkTrue(jarToClasses.isEmpty());
                blocktest().noInit(entry).setup(() -> {
                            pathToJar.put("a", new Jar("e", "f", "g", "h"));
                        }).given(entry.getKey(), new Jar("a", "b", "c", "d"), "Jar")
                        .given(entry.getValue(), new HashSet<>(Arrays.asList("1", "2", "3")), "HashSet<String>")
                        .checkFalse(jarToClasses.isEmpty())
                        .checkTrue(jarToClasses.get(new Jar("a", "b", "c", "d")).size() == 3)
                        .checkTrue(jarToClasses.get(new Jar("a", "b", "c", "d")).contains("1"))
                        .checkTrue(jarToClasses.get(new Jar("a", "b", "c", "d")).contains("2"))
                        .checkTrue(jarToClasses.get(new Jar("a", "b", "c", "d")).contains("3"));

                blocktest().noInit(entry).setup(() -> {
                            jarToClasses.put(new Jar("a", "b", "c", "d"), new HashSet<>(Arrays.asList("x", "y")));
                            pathToJar.put("a", new Jar("e", "f", "g", "h"));
                        }).given(entry.getKey(), new Jar("a", "b", "c", "d"), "Jar")
                        .given(entry.getValue(), new HashSet<>(Arrays.asList("1", "2", "3")), "HashSet<String>")
                        .checkTrue(jarToClasses.get(new Jar("a", "b", "c", "d")).size() == 5)
                        .checkTrue(jarToClasses.get(new Jar("a", "b", "c", "d")).contains("1"))
                        .checkTrue(jarToClasses.get(new Jar("a", "b", "c", "d")).contains("2"))
                        .checkTrue(jarToClasses.get(new Jar("a", "b", "c", "d")).contains("3"))
                        .checkTrue(jarToClasses.get(new Jar("a", "b", "c", "d")).contains("x"))
                        .checkTrue(jarToClasses.get(new Jar("a", "b", "c", "d")).contains("y"));
                if (jarToClasses.containsKey(entry.getKey())) {
                    jarToClasses.get(entry.getKey()).addAll(entry.getValue());
                } else{
                    if (pathToJar.containsKey(entry.getKey().jarPath)) {
                        jarToClasses.put(entry.getKey(), new HashSet<>(entry.getValue()));
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        DependencyResults res;
        if (firstRun) {
            res = new DependencyResults(jarToClasses, null);
        } else {
            long start = System.currentTimeMillis();
            Map<Jar, Set<String>> jarToClassesOld = (Map<Jar, Set<String>>) Util.readFromDisk(Util.getArtifactDir() + File.separator + "deps.iemop");
            TimerUtil.log("Read previous dependency map in " + (System.currentTimeMillis() - start) + " ms");
            res = new DependencyResults(jarToClasses, jarToClassesOld);
        }
        
        if (saveToDisk) {
            long start = System.currentTimeMillis();
            Util.saveToDisk(jarToClasses, Util.getArtifactDir() + File.separator + "deps.iemop");
            TimerUtil.log("Saved all dynamicCIA metadata in " + (System.currentTimeMillis() - start) + " ms");
        }
        
        return res;
    }
}
