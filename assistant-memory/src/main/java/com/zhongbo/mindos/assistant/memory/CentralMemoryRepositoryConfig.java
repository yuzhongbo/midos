package com.zhongbo.mindos.assistant.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

@Configuration
public class CentralMemoryRepositoryConfig {

    private static final Logger LOGGER = Logger.getLogger(CentralMemoryRepositoryConfig.class.getName());

    @Bean
    @Primary
    public CentralMemoryRepository centralMemoryRepository(ObjectProvider<DataSource> dataSourceProvider,
                                                           ObjectProvider<ObjectMapper> objectMapperProvider,
                                                           @Value("${mindos.memory.file-repo.enabled:true}") boolean fileRepositoryEnabled,
                                                           @Value("${mindos.memory.file-repo.base-dir:data/memory-sync}") String fileRepositoryBaseDir,
                                                           InMemoryCentralMemoryRepository inMemoryCentralMemoryRepository) {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        if (dataSource == null) {
            if (fileRepositoryEnabled) {
                try {
                    return new FileCentralMemoryRepository(Path.of(fileRepositoryBaseDir), objectMapper);
                } catch (RuntimeException ex) {
                    LOGGER.log(Level.WARNING, "Failed to initialize file-backed central memory repository, fallback to in-memory", ex);
                }
            } else {
                // If file repository is explicitly disabled but the directory contains existing memory files,
                // warn the operator so upgrades don't silently lose persisted memory.
                Path base = Path.of(fileRepositoryBaseDir);
                try {
                    if (Files.exists(base) && Files.list(base).findAny().isPresent()) {
                        LOGGER.log(Level.WARNING, "File-backed memory data found at {0} but file repository is disabled. " +
                                "This run will use in-memory storage and persisted conversation history may be ignored. " +
                                "Enable 'mindos.memory.file-repo.enabled=true' to preserve memory across restarts.", base.toString());
                    }
                } catch (IOException e) {
                    LOGGER.log(Level.FINE, "Unable to inspect file-backed memory directory: " + base, e);
                }
            }
            return inMemoryCentralMemoryRepository;
        }

        return new JdbcCentralMemoryRepository(new JdbcTemplate(dataSource), objectMapper);
    }
}

