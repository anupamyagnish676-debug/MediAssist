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
import java.sql.Connection;
import java.sql.Statement;

@Configuration
public class DatabaseConfig {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseConfig.class);

    @Bean
    @Primary
    public DataSource dataSource() {
        DataSource ds = createDataSource();
        migrateSchema(ds);
        return ds;
    }

    private DataSource createDataSource() {
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

    private void migrateSchema(DataSource ds) {
        try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
            String product = conn.getMetaData().getDatabaseProductName().toLowerCase();
            if (product.contains("postgres")) {
                logger.info("Executing idempotent schema migrations for PostgreSQL...");
                // Hospitals table columns
                stmt.execute("ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS registration_number VARCHAR(255);");
                stmt.execute("ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS contact_person_name VARCHAR(255);");
                stmt.execute("ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS contact_person_email VARCHAR(255);");
                stmt.execute("ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS contact_person_phone VARCHAR(255);");
                stmt.execute("ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS specialties VARCHAR(1000);");
                stmt.execute("ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS number_of_beds INTEGER DEFAULT 0;");
                stmt.execute("ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS application_note TEXT;");
                stmt.execute("ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS city VARCHAR(255);");
                stmt.execute("ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS state VARCHAR(255);");
                stmt.execute("ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS applied_at TIMESTAMP;");
                stmt.execute("ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMP;");
                stmt.execute("ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS rejection_reason TEXT;");
                // Drop outdated check constraint from previous enum values
                stmt.execute("ALTER TABLE hospitals DROP CONSTRAINT IF EXISTS hospitals_status_check;");
                stmt.execute("ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS status VARCHAR(50) DEFAULT 'ACTIVE';");

                // Users table columns
                stmt.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS phone_number VARCHAR(255);");
                stmt.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN DEFAULT FALSE;");
                stmt.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMP;");
                stmt.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;");

                // Patient sessions table
                stmt.execute("CREATE TABLE IF NOT EXISTS patient_sessions (phone_number VARCHAR(255) PRIMARY KEY, latitude DOUBLE PRECISION, longitude DOUBLE PRECISION, preferred_department VARCHAR(255), updated_at TIMESTAMP);");
                logger.info("PostgreSQL schema migrations executed successfully!");
            }
        } catch (Exception e) {
            logger.warn("Schema migration notice (non-fatal): {}", e.getMessage());
        }
    }
}
