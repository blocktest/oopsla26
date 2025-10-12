// FRAMEWORK: junit4
// TEST_DIR: maven-plugin/src/test/java/org/iemop/util/hasher
// WORKING_DIR: maven-plugin
package org.iemop.util.hasher;

import org.ekstazi.hash.Hasher;
import org.iemop.util.Config;
import org.iemop.util.TimerUtil;
import org.iemop.util.Util;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.blocktest.BTest;
import static org.blocktest.BTest.blocktest;
import static org.blocktest.types.EndAt.*;
import static org.blocktest.utils.Constant.*;

public class IncrementalJarCleaner {

    public class IncrementalJarCleanerResult {

        public boolean same;

        public String hash;

        public IncrementalJarCleanerResult(boolean same, String hash) {
            this.same = same;
            this.hash = hash;
        }
    }

    static IncrementalJarCleaner instance = null;

    private ExecutorService pool;

    public static IncrementalJarCleaner getInstance() {
        if (instance == null)
            instance = new IncrementalJarCleaner();
        return instance;
    }

    private IncrementalJarCleaner() {
        pool = Executors.newFixedThreadPool(Config.threads);
    }

    public Set<Path> cleanByteCode(Set<Path> updatedFilesSet, String tmpDir, String tmpDirForCurrent) {
        if (Config.hashesSingleThread)
            return cleanByteCodeSingleThread(updatedFilesSet, tmpDir, tmpDirForCurrent);
        // Compare bytecode again after cleaning bytecode
        long start = System.currentTimeMillis();
        HashMap<String, String> newJarHashes = new HashMap<>();
        Path originalRoot = Paths.get(tmpDir);
        Path currentRoot = Paths.get(tmpDirForCurrent);
        Hasher hasher = new Hasher(Hasher.Algorithm.CRC32, 1000, true);
        Set<Path> removed = new HashSet<>();
        List<Path> updatedFiles = new ArrayList<>(updatedFilesSet);
        if (Config.storeHashes) {
            // Read original jar hashes from disk
            HashMap<String, String> tmp = (HashMap<String, String>) Util.readFromDisk(tmpDir + "-hashes.map");
            HashMap<String, String> originalJarHashes = (tmp == null ? new HashMap<>() : tmp);
            // don't compute and store hashes for `newFiles` because we don't want to pay the price at this time
            // we will compute the hashes when the files are changed in the future
            List<Future<IncrementalJarCleanerResult>> results = new ArrayList<>();
            for (Path file : updatedFiles) {
                results.add(pool.submit(() -> {
                    Path oldFile = originalRoot.resolve(currentRoot.relativize(file));
                    String newFileHash = hasher.hashURL("file:" + file);
                    String oldFileHash = originalJarHashes.containsKey(oldFile.toString()) ? originalJarHashes.get(oldFile.toString()) : hasher.hashURL("file:" + oldFile);
                    // BLOCKTEST EVAL: https://github.com/SoftEngResearch/imop/blob/fc59fb1ef0b8478e795bb6dc29bef66a3a255d43/maven-plugin/src/main/java/org/iemop/util/hasher/IncrementalJarCleaner.java#L64-L66
                    blocktest().given(originalJarHashes, new HashMap<>()).given(originalRoot, Paths.get("/abc/def")).given(currentRoot, Paths.get("/ghi")).given(file, Paths.get("/ghi/test.txt"))
                            .checkEq(oldFile.toString(), "/abc/def/test.txt").checkEq(newFileHash, "-1").checkEq(oldFileHash, "-1").start(FIRST_BLOCK);
                    blocktest().noInit(originalRoot).noInit(currentRoot).given(originalJarHashes, new HashMap<>()).given(file, Paths.get(System.getProperty("user.dir") + "/target/classes/org/iemop/RTSMojo.class"))
                            .given(oldFile, Paths.get(System.getProperty("user.dir") + "/target/classes/org/iemop/RTSMojo.class"))
                            .checkEq(newFileHash, "3773596682").checkEq(oldFileHash, "3773596682").start(FIRST_BLOCK);
                    blocktest().noInit(originalRoot).noInit(currentRoot).setup("originalJarHashes.put(oldFile.toString(), \"100\");").given(originalJarHashes, new HashMap<>()).given(file, Paths.get(System.getProperty("user.dir") + "/target/classes/org/iemop/RTSMojo.class"))
                            .given(oldFile, Paths.get(System.getProperty("user.dir") + "/target/classes/org/iemop/RTSMojo.class"))
                            .checkEq(newFileHash, "3773596682").checkEq(oldFileHash, "100").start(FIRST_BLOCK);

                    return new IncrementalJarCleanerResult(newFileHash.equals(oldFileHash), newFileHash);
                }));
            }
            try {
                int i = 0;
                for (Future<IncrementalJarCleanerResult> res : results) {
                    IncrementalJarCleanerResult result = res.get();
                    // BLOCKTEST EVAL: https://github.com/SoftEngResearch/imop/blob/fc59fb1ef0b8478e795bb6dc29bef66a3a255d43/maven-plugin/src/main/java/org/iemop/util/hasher/IncrementalJarCleaner.java#L73-L77
                    blocktest().given(removed, new HashSet<>(Arrays.asList(Paths.get("/foo/bar"), Paths.get("/foo/baz"))))
                            .given(updatedFiles, new ArrayList<>(Arrays.asList(Paths.get("/foo/a"), Paths.get("/foo/b"))))
                            .given(i, 1)
                            .given(result, (IncrementalJarCleaner.getInstance()).new IncrementalJarCleanerResult(true, "hash"), "IncrementalJarCleaner.IncrementalJarCleanerResult")
                            .checkEq(removed.size(), 3)
                            .checkTrue(removed.contains(Paths.get("/foo/b")))
                            .checkEq(newJarHashes.get("/foo/b"), "hash");
                    blocktest().given(removed, new HashSet<>(Arrays.asList(Paths.get("/foo/bar"), Paths.get("/foo/baz"))))
                            .given(updatedFiles, new ArrayList<>(Arrays.asList(Paths.get("/foo/a"), Paths.get("/foo/b"))))
                            .given(i, 1)
                            .given(result, (IncrementalJarCleaner.getInstance()).new IncrementalJarCleanerResult(false, "hash"), "IncrementalJarCleaner.IncrementalJarCleanerResult")
                            .checkEq(removed.size(), 2)
                            .checkFalse(removed.contains(Paths.get("/foo/b")))
                            .checkEq(newJarHashes.get("/foo/b"), "hash");
                    if (result.same) {
                        // true => same bytecode after cleaning, we don't need to instrument file
                        removed.add(updatedFiles.get(i));
                    }
                    newJarHashes.put(updatedFiles.get(i).toString(), result.hash);
                    i += 1;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            if (!newJarHashes.isEmpty())
                // added at least one entry to newJarHashes, save it to disk
                Util.saveToDisk(newJarHashes, tmpDirForCurrent + "-hashes.map");
        } else {
            // don't compute and store hashes for `newFiles` because we don't want to pay the price at this time
            // we will compute the hashes when the files are changed in the future
            List<Future<Boolean>> results = new ArrayList<>();
            for (Path file : updatedFiles) {
                results.add(pool.submit(() -> {
                    Path oldFile = originalRoot.resolve(currentRoot.relativize(file));
                    String newFileHash = hasher.hashURL("file:" + file);
                    String oldFileHash = hasher.hashURL("file:" + oldFile);

                    // BLOCKTEST EVAL: https://github.com/SoftEngResearch/imop/blob/fc59fb1ef0b8478e795bb6dc29bef66a3a255d43/maven-plugin/src/main/java/org/iemop/util/hasher/IncrementalJarCleaner.java#L93-L95
                    blocktest().given(originalRoot, Paths.get("/abc/def")).given(currentRoot, Paths.get("/ghi")).given(file, Paths.get("/ghi/test.txt"))
                            .checkEq(oldFile.toString(), "/abc/def/test.txt").checkEq(newFileHash, "-1").checkEq(oldFileHash, "-1").start(FIRST_BLOCK);
                    blocktest().noInit(originalRoot).noInit(currentRoot).given(file, Paths.get(System.getProperty("user.dir") + "/target/classes/org/iemop/RTSMojo.class"))
                            .given(oldFile, Paths.get(System.getProperty("user.dir") + "/target/classes/org/iemop/RTSMojo.class"))
                            .checkEq(newFileHash, "3773596682").checkEq(oldFileHash, "3773596682").start(FIRST_BLOCK);
                    blocktest().noInit(originalRoot).noInit(currentRoot).given(file, Paths.get(System.getProperty("user.dir") + "/target/classes/org/iemop/RunMojo.class"))
                            .given(oldFile, Paths.get(System.getProperty("user.dir") + "/target/classes/org/iemop/RTSMojo.class"))
                            .checkEq(newFileHash, "3992787915").checkEq(oldFileHash, "3773596682").start(FIRST_BLOCK);

                    return newFileHash.equals(oldFileHash);
                }));
            }
            try {
                int i = 0;
                for (Future<Boolean> result : results) {
                    if (result.get()) {
                        // true => same bytecode after cleaning, we don't need to instrument file
                        removed.add(updatedFiles.get(i));
                    }
                    i += 1;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        TimerUtil.log("Cleaned bytecode for " + updatedFiles.size() + " files with " + Config.threads + " threads - " + tmpDir + " in " + (System.currentTimeMillis() - start) + " ms");
        return removed;
    }

    private static Set<Path> cleanByteCodeSingleThread(Set<Path> updatedFiles, String tmpDir, String tmpDirForCurrent) {
        // Compare bytecode again after cleaning bytecode
        long start = System.currentTimeMillis();
        boolean updatedOldHashes = false;
        HashMap<String, String> originalJarHashes = new HashMap<>();
        HashMap<String, String> newJarHashes = new HashMap<>();
        if (Config.storeHashes) {
            // Read original jar hashes from disk
            HashMap<String, String> tmp = (HashMap<String, String>) Util.readFromDisk(tmpDir + "-hashes.map");
            if (tmp != null)
                originalJarHashes = tmp;
        }
        Path originalRoot = Paths.get(tmpDir);
        Path currentRoot = Paths.get(tmpDirForCurrent);
        Hasher hasher = new Hasher(Hasher.Algorithm.CRC32, 1000, true);
        Set<Path> removed = new HashSet<>();
        // don't compute and store hashes for `newFiles` because we don't want to pay the price at this time
        // we will compute the hashes when the files are changed in the future
        for (Path file : updatedFiles) {
            Path oldFile = originalRoot.resolve(currentRoot.relativize(file));
            String newFileHash = hasher.hashURL("file:" + file);
            String oldFileHash;
            if (Config.storeHashes) {
                // Save hashes to originalJarHashes and newJarHashes
                // BLOCKTEST EVAL: https://github.com/SoftEngResearch/imop/blob/fc59fb1ef0b8478e795bb6dc29bef66a3a255d43/maven-plugin/src/main/java/org/iemop/util/hasher/IncrementalJarCleaner.java#L144-L152

                /*
                @blocktest().given(originalJarHashes, new HashMap<>())
                        .setup(() -> {originalJarHashes.put("/c", "test");})
                        .given(oldFile, Paths.get("/c")).checkEq(oldFileHash, "test");
                 */
                blocktest().given(originalJarHashes, new HashMap<>())
                        .given(oldFile, Paths.get(System.getProperty("user.dir") + "/target/classes/org/iemop/RTSMojo.class")).checkEq("oldFileHash", "3773596682")
                        .checkTrue(updatedOldHashes).checkEq(originalJarHashes.get(System.getProperty("user.dir") + "/target/classes/org/iemop/RTSMojo.class"), "3773596682");
                if (originalJarHashes.containsKey(oldFile.toString())) {
                    // get old hash from map
                    oldFileHash = originalJarHashes.get(oldFile.toString());
                } else {
                    // get hash and save it to map
                    oldFileHash = hasher.hashURL("file:" + oldFile);
                    originalJarHashes.put(oldFile.toString(), oldFileHash);
                    updatedOldHashes = true;
                }
                newJarHashes.put(file.toString(), newFileHash);
            } else {
                oldFileHash = hasher.hashURL("file:" + oldFile);
            }
            // BLOCKTEST EVAL: https://github.com/SoftEngResearch/imop/blob/fc59fb1ef0b8478e795bb6dc29bef66a3a255d43/maven-plugin/src/main/java/org/iemop/util/hasher/IncrementalJarCleaner.java#L157-L160
            blocktest().given(file, Paths.get("test")).given(newFileHash, "foo").given(oldFileHash, "bar").checkTrue(removed.isEmpty());
            blocktest().given(file, Paths.get("test")).given(newFileHash, "foo").given(oldFileHash, "foo").checkFalse(removed.isEmpty()).checkEq(removed.iterator().next().toString(), "test");
            if (newFileHash.equals(oldFileHash)) {
                // same bytecode after cleaning, we don't need to instrument file
                removed.add(file);
            }
        }
        if (Config.storeHashes) {
            if (updatedOldHashes)
                // added at least one entry to originalJarHashes, save it to disk
                // probably don't even need this at all, only time it is helpful is when we have v1.0, v1.3, back to v1.2, then we can use v1.0 hashes
                Util.saveToDisk(originalJarHashes, tmpDir + "-hashes.map");
            if (!newJarHashes.isEmpty())
                // added at least one entry to newJarHashes, save it to disk
                Util.saveToDisk(newJarHashes, tmpDirForCurrent + "-hashes.map");
        }
        TimerUtil.log("Cleaned bytecode for " + tmpDir + " in " + (System.currentTimeMillis() - start) + " ms");
        return removed;
    }
}
