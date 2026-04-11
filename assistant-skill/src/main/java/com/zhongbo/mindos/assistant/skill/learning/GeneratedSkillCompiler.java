package com.zhongbo.mindos.assistant.skill.learning;

import com.zhongbo.mindos.assistant.skill.Skill;
import org.springframework.stereotype.Component;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
public class GeneratedSkillCompiler {

    public Skill compile(ToolGenerationResult artifact) {
        Objects.requireNonNull(artifact, "artifact");
        if (artifact.sourceCode().isBlank()) {
            throw new IllegalStateException("Generated skill source is empty for " + artifact.skillName());
        }
        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("mindos-generated-skill-");
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to create temp directory for generated skill " + artifact.skillName(), ex);
        }

        Path sourceFile = tempDir.resolve(artifact.fullyQualifiedClassName().replace('.', '/') + ".java");
        try {
            Files.createDirectories(sourceFile.getParent());
            Files.writeString(sourceFile, artifact.sourceCode(), StandardCharsets.UTF_8);
            compileSource(tempDir, sourceFile);
            return loadSkill(tempDir, artifact);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to prepare generated skill source for " + artifact.skillName(), ex);
        } finally {
            cleanup(tempDir);
        }
    }

    private void compileSource(Path tempDir, Path sourceFile) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("JDK compiler unavailable; generated skill registration requires a JDK");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(tempDir.toFile()));
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromFiles(List.of(sourceFile.toFile()));
            List<String> options = List.of(
                    "--release", "17",
                    "-classpath", System.getProperty("java.class.path", "")
            );
            Boolean ok = compiler.getTask(null, fileManager, diagnostics, options, null, units).call();
            if (!Boolean.TRUE.equals(ok)) {
                throw new IllegalStateException(renderDiagnostics(artifactName(sourceFile), diagnostics));
            }
        }
    }

    private Skill loadSkill(Path tempDir, ToolGenerationResult artifact) {
        URLClassLoader classLoader = null;
        try {
            classLoader = new URLClassLoader(
                    new URL[]{tempDir.toUri().toURL()},
                    Thread.currentThread().getContextClassLoader()
            );
            Class<?> clazz = Class.forName(artifact.fullyQualifiedClassName(), true, classLoader);
            if (!Skill.class.isAssignableFrom(clazz)) {
                throw new IllegalStateException("Generated class does not implement Skill: " + artifact.fullyQualifiedClassName());
            }
            Object instance = clazz.getDeclaredConstructor().newInstance();
            return Skill.class.cast(instance);
        } catch (ReflectiveOperationException | IOException ex) {
            throw new IllegalStateException("Failed to load generated skill " + artifact.skillName(), ex);
        } finally {
            if (classLoader != null) {
                try {
                    classLoader.close();
                } catch (IOException ignored) {
                    // Best-effort cleanup only.
                }
            }
        }
    }

    private String renderDiagnostics(String artifactName, DiagnosticCollector<JavaFileObject> diagnostics) {
        return diagnostics.getDiagnostics().stream()
                .map(diagnostic -> formatDiagnostic(artifactName, diagnostic))
                .collect(Collectors.joining(System.lineSeparator()));
    }

    private String formatDiagnostic(String artifactName, Diagnostic<? extends JavaFileObject> diagnostic) {
        String source = diagnostic.getSource() == null ? artifactName : diagnostic.getSource().getName();
        return "[" + diagnostic.getKind() + "] " + source + ":" + diagnostic.getLineNumber() + ":" + diagnostic.getColumnNumber()
                + " " + diagnostic.getMessage(Locale.ROOT);
    }

    private String artifactName(Path sourceFile) {
        return sourceFile == null ? "generated-skill.java" : sourceFile.getFileName().toString();
    }

    private void cleanup(Path root) {
        if (root == null) {
            return;
        }
        try {
            if (!Files.exists(root)) {
                return;
            }
            Files.walk(root)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ex) {
                            // Best-effort cleanup only; compiled classes are already loaded.
                        }
                    });
        } catch (IOException ignored) {
            // Best-effort cleanup only.
        }
    }
}
