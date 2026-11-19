package com.softino.notifier;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Static guard for the acceptance rules that are easier to assert by scanning the source than
 * by timing: no sleeping or monitor locking on the send path, no common-pool use, and no
 * unbounded thread pool.
 */
public class SourceHygieneTest {

    private static final File SOURCE_ROOT = new File("src/main/java");

    @Test
    public void sendPathNeverSleepsOrUsesSynchronized() throws IOException {
        List<File> files = javaFiles(SOURCE_ROOT);
        assertFalse("source root not found: " + SOURCE_ROOT.getAbsolutePath(), files.isEmpty());
        for (File file : files) {
            String code = stripComments(read(file));
            assertFalse(file + " contains Thread.sleep", code.contains("Thread.sleep"));
            assertFalse(file + " contains a synchronized block", code.contains("synchronized"));
        }
    }

    @Test
    public void noCommonPoolOrUnboundedThreadPool() throws IOException {
        for (File file : javaFiles(SOURCE_ROOT)) {
            String code = stripComments(read(file));
            assertFalse(file + " uses the common pool", code.contains("ForkJoinPool"));
            assertFalse(file + " uses an unbounded cached thread pool",
                    code.contains("newCachedThreadPool"));
            assertFalse(file + " uses an unbounded scheduled pool",
                    code.contains("newScheduledThreadPool"));
        }
    }

    @Test
    public void asyncEngineDoesNotCreateThreadsPerMessage() throws IOException {
        File engine = new File(SOURCE_ROOT,
                "com/softino/notifier/internal/AsyncSendEngine.java");
        assertTrue(engine + " should exist", engine.isFile());
        String code = stripComments(read(engine));
        assertFalse("the engine must use NotifierThreads, not new Thread",
                code.contains("new Thread("));
        assertTrue("the engine should use fixed pools",
                code.contains("newFixedThreadPool"));
    }

    private static List<File> javaFiles(File root) {
        List<File> out = new ArrayList<File>();
        collect(root, out);
        return out;
    }

    private static void collect(File directory, List<File> out) {
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                collect(child, out);
            } else if (child.getName().endsWith(".java")) {
                out.add(child);
            }
        }
    }

    private static String read(File file) throws IOException {
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            fail("could not read " + file + ": " + e.getMessage());
            return "";
        }
    }

    /** Naive comment stripper: good enough for a keyword scan of this code base. */
    private static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                    out.append(c);
                }
            } else if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
            } else if (c == '/' && next == '/') {
                lineComment = true;
                i++;
            } else if (c == '/' && next == '*') {
                blockComment = true;
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
