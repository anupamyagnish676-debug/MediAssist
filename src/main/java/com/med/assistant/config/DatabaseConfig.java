package com.med.assistant.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.net.URI;

@Configuration
public class DatabaseConfig {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseConfig.class);

    @Bean
    @Primary
    public DataSource dataSource() {
        String dbUrl = System.getenv("DATABASE_URL");
        if (dbUrl == null || dbUrl.isBlank()) {
            dbUrl = System.getenv("SPRING_DATASOURCE_URL");
        }

        if (dbUrl != null && !dbUrl.isBlank()) {
            // Case 1: Standard JDBC URL already formatted (e.g. jdbc:postgresql://...)
            if (dbUrl.startsWith("jdbc:postgresql://")) {
                logger.info("Initializing PostgreSQL DataSource via standard JDBC URL...");
                HikariConfig config = new HikariConfig();
                config.setDriverClassName("org.postgresql.Driver");
                config.setJdbcUrl(dbUrl);
                String user = System.getenv("SPRING_DATASOURCE_USERNAME");
                String pass = System.getenv("SPRING_DATASOURCE_PASSWORD");
                if (user != null) config.setUsername(user);
                if (pass != null) config.setPassword(pass);
                config.setMaximumPoolSize(10);
                config.setMinimumIdle(2);
                config.setConnectionTimeout(30000);
                return new HikariDataSource(config);
            }

            // Case 2: Render/Heroku/Supabase format (postgres:// or postgresql://)
            if (dbUrl.startsWith("postgres://") || dbUrl.startsWith("postgresql://")) {
                logger.info("Parsing URI-format DATABASE_URL for PostgreSQL...");
                try {
                    String cleanUrl = dbUrl.replaceFirst("^postgres://", "postgresql://");
                    URI uri = new URI(cleanUrl);

                    String username = "";
                    String password = "";
                    if (uri.getUserInfo() != null) {
                        String[] userInfo = uri.getUserInfo().split(":", 2);
                        username = userInfo[0];
                        password = userInfo.length > 1 ? userInfo[1] : "";
                    }

                    String host = uri.getHost();
                    int port = uri.getPort() == -1 ? 5432 : uri.getPort();
                    String path = uri.getPath() != null && uri.getPath().startsWith("/")
                            ? uri.getPath().substring(1)
                            : (uri.getPath() != null ? uri.getPath() : "");

                    String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + path;
                    if (uri.getQuery() != null && !uri.getQuery().isBlank()) {
                        jdbcUrl += "?" + uri.getQuery();
                    }

                    HikariConfig config = new HikariConfig();
                    config.setDriverClassName("org.postgresql.Driver");
                    config.setJdbcUrl(jdbcUrl);
                    config.setUsername(username);
                    config.setPassword(password);
                    config.setMaximumPoolSize(10);
                    config.setMinimumIdle(2);
                    config.setConnectionTimeout(30000);

                    logger.info("Successfully configured PostgreSQL datasource for host: {} on database: {}", host, path);
                    return new HikariDataSource(config);
                } catch (Exception e) {
                    logger.error("Failed to parse PostgreSQL DATABASE_URL, falling back to default configuration", e);
                }
            }
        }

        // Fallback: Default H2 In-Memory Database for local development
        logger.info("Using default H2 in-memory datasource.");
        HikariConfig config = new HikariConfig();
        config.setDriverClassName("org.h2.Driver");
        config.setJdbcUrl("jdbc:h2:mem:medassistantdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        config.setUsername("sa");
        config.setPassword("");
        return new HikariDataSource(config);
    }
}
