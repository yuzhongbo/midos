package com.zhongbo.mindos.assistant.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
public class CentralMemoryRepositoryConfig {

    @Bean
    @Primary
    public CentralMemoryRepository centralMemoryRepository(ObjectProvider<DataSource> dataSourceProvider,
                                                           ObjectProvider<ObjectMapper> objectMapperProvider,
                                                           InMemoryCentralMemoryRepository inMemoryCentralMemoryRepository) {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            return inMemoryCentralMemoryRepository;
        }

        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        return new JdbcCentralMemoryRepository(new JdbcTemplate(dataSource), objectMapper);
    }
}

