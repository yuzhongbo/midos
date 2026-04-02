package com.zhongbo.mindos.assistant.memory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

@Component
public class FileMemoryStateStore implements MemoryStateStore {

    private static final Logger LOGGER = Logger.getLogger(FileMemoryStateStore.class.getName());

    private final ObjectMapper objectMapper;
    private final Path baseDirectory;
    private final Map<String, Object> fileLocks = new ConcurrentHashMap<>();
    private volatile boolean enabled;

    @Autowired
    public FileMemoryStateStore(ObjectProvider<ObjectMapper> objectMapperProvider,
                                @Value("${mindos.memory.state.enabled:true}") boolean enabled,
                                @Value("${mindos.memory.state.base-dir:data/memory-state}") String baseDirectory) {
        this(enabled,
                Path.of(baseDirectory),
                objectMapperProvider.getIfAvailable(ObjectMapper::new));
    }

    FileMemoryStateStore(boolean enabled, Path baseDirectory, ObjectMapper objectMapper) {
        this.enabled = enabled;
        this.baseDirectory = baseDirectory;
        this.objectMapper = objectMapper.copy()
                .findAndRegisterModules()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        initializeDirectory();
    }

    @Override
    public <T> T readState(String fileName, TypeReference<T> typeReference, Supplier<T> fallbackSupplier) {
        if (!enabled) {
            return fallbackSupplier.get();
        }
        Path filePath = resolve(fileName);
        Object lock = fileLocks.computeIfAbsent(fileName, ignored -> new Object());
        synchronized (lock) {
            if (!Files.exists(filePath)) {
                return fallbackSupplier.get();
            }
            try {
                return objectMapper.readValue(filePath.toFile(), typeReference);
            } catch (IOException ex) {
                LOGGER.log(Level.WARNING, "Failed to read memory state from " + filePath + ", using fallback", ex);
                return fallbackSupplier.get();
            }
        }
    }

    @Override
    public void writeState(String fileName, Object value) {
        if (!enabled) {
            return;
        }
        Path filePath = resolve(fileName);
        Object lock = fileLocks.computeIfAbsent(fileName, ignored -> new Object());
        synchronized (lock) {
            try {
                Files.createDirectories(baseDirectory);
                Path tempFile = Files.createTempFile(baseDirectory, sanitize(fileName) + "-", ".tmp");
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), value);
                moveAtomically(tempFile, filePath);
            } catch (IOException ex) {
                LOGGER.log(Level.WARNING, "Failed to persist memory state to " + filePath, ex);
            }
        }
    }

    private void initializeDirectory() {
        if (!enabled) {
            return;
        }
        try {
            Files.createDirectories(baseDirectory);
        } catch (IOException ex) {
            enabled = false;
            LOGGER.log(Level.WARNING, "Failed to initialize memory state directory, disabling state persistence: " + baseDirectory, ex);
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path resolve(String fileName) {
        return baseDirectory.resolve(fileName);
    }

    private String sanitize(String fileName) {
        return fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
