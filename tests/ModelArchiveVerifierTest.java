package com.suj.zhuanluji;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Dependency-free test: run scripts/test_model_archive_verifier.sh. */
public final class ModelArchiveVerifierTest {
    public static void main(String[] args) throws Exception {
        File f = File.createTempFile("model-verifier-", ".tmp");
        try {
            Files.write(f.toPath(), "abc".getBytes(StandardCharsets.UTF_8));
            String digest = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
            check(ModelArchiveVerifier.matches(f, 3, digest), "valid archive");
            check(!ModelArchiveVerifier.matches(f, 4, digest), "wrong length");
            Files.write(f.toPath(), "abd".getBytes(StandardCharsets.UTF_8));
            check(!ModelArchiveVerifier.matches(f, 3, digest), "same-length corruption");
            check(f.delete(), "delete test file");
            try {
                ModelArchiveVerifier.matches(f, 0, digest);
                throw new AssertionError("unreadable file should throw");
            } catch (IOException expected) { }
            System.out.println("ModelArchiveVerifier tests passed");
        } finally { f.delete(); }
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
