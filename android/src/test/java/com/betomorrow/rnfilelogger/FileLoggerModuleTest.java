package com.betomorrow.rnfilelogger;

import static org.junit.Assert.*;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.Appender;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.Logger;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;

/**
 * Tests that FileLoggerModule works correctly with both SLF4J 1.x and 2.x.
 *
 * These tests are run twice via Gradle — once with slf4j-api:1.7.36 on the
 * classpath ({@code testSlf4j1xDebugUnitTest}) and once with slf4j-api:2.0.17
 * ({@code testSlf4j2xDebugUnitTest}). No mocking is used; the tests exercise
 * the real SLF4J binding/provider discovery path.
 *
 * Under SLF4J 1.x, logback-android binds via StaticLoggerBinder and
 * {@code getOrCreateLoggerContext()} returns the factory's LoggerContext.
 * Under SLF4J 2.x, logback-android's 1.x binding is invisible, so the method
 * creates a standalone LoggerContext. Both paths must produce working file logging.
 */
public class FileLoggerModuleTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private String logsDir;

    @Before
    public void setUp() throws Exception {
        logsDir = tempFolder.newFolder("logs").getAbsolutePath();
    }

    @After
    public void resetStaticState() throws Exception {
        // Stop and clear the static context to prevent cross-test contamination.
        Field contextField = FileLoggerModule.class.getDeclaredField("fileLoggerContext");
        contextField.setAccessible(true);
        LoggerContext ctx = (LoggerContext) contextField.get(null);
        if (ctx != null) {
            ctx.stop();
        }
        contextField.set(null, null);

        // Reset the logger field to its default (SLF4J factory logger).
        Field loggerField = FileLoggerModule.class.getDeclaredField("logger");
        loggerField.setAccessible(true);
        loggerField.set(null, org.slf4j.LoggerFactory.getLogger(FileLoggerModule.class));
    }

    @Test
    public void getOrCreateLoggerContext_returnsLoggerContext() {
        LoggerContext ctx = FileLoggerModule.getOrCreateLoggerContext();
        assertNotNull("Should return a LoggerContext regardless of SLF4J version", ctx);
    }

    @Test
    public void configureLogger_writesToFile() throws Exception {
        FileLoggerModule.configureLogger(false, 1024 * 1024, 5, logsDir, "test");

        Logger logger = getStaticLogger();
        logger.info("hello from test");

        File logFile = new File(logsDir, "test-latest.log");
        assertTrue("Log file should exist", logFile.exists());

        String contents = new String(Files.readAllBytes(logFile.toPath()));
        assertTrue("Log file should contain the message", contents.contains("hello from test"));
    }

    @Test
    public void configureLogger_withDailyRolling_startsSuccessfully() throws Exception {
        // Should not throw even under the standalone LoggerContext path.
        FileLoggerModule.configureLogger(true, 1024 * 1024, 5, logsDir, "test");

        Logger logger = getStaticLogger();
        logger.info("daily rolling test");

        File logFile = new File(logsDir, "test-latest.log");
        assertTrue("Log file should exist with daily rolling", logFile.exists());
    }

    @Test
    public void configureLogger_calledTwice_replacesAppender() throws Exception {
        FileLoggerModule.configureLogger(false, 1024 * 1024, 5, logsDir, "first");
        FileLoggerModule.configureLogger(false, 1024 * 1024, 5, logsDir, "second");

        // After reconfiguring, the root logger should have exactly one FileLoggerAppender.
        Field contextField = FileLoggerModule.class.getDeclaredField("fileLoggerContext");
        contextField.setAccessible(true);
        LoggerContext ctx = (LoggerContext) contextField.get(null);

        ch.qos.logback.classic.Logger root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
        Appender<?> appender = root.getAppender(FileLoggerModule.APPENDER_NAME);
        assertNotNull("Should have a FileLoggerAppender after reconfigure", appender);
        assertTrue("Appender should be started", appender.isStarted());

        // Write through the logger and verify it goes to the second config's file.
        Logger logger = getStaticLogger();
        logger.info("after reconfigure");

        File logFile = new File(logsDir, "second-latest.log");
        assertTrue("Second log file should exist", logFile.exists());

        String contents = new String(Files.readAllBytes(logFile.toPath()));
        assertTrue("Should write to the reconfigured file", contents.contains("after reconfigure"));
    }

    /** Reads the private static {@code logger} field via reflection. */
    private static Logger getStaticLogger() throws Exception {
        Field loggerField = FileLoggerModule.class.getDeclaredField("logger");
        loggerField.setAccessible(true);
        return (Logger) loggerField.get(null);
    }
}
