package org.enthusia.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Properties;
import org.sqlite.JDBC;

/** Execute with only the shaded deliverable on the classpath, never a Maven test dependency. */
class SQLiteArtifactProbe {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("enthusia-sqlite-probe-");
        Path database = directory.resolve("mail.db");
        try {
            try (Connection connection = JDBC.createConnection("jdbc:sqlite:" + database, new Properties());
                 var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE probe (value INTEGER NOT NULL)");
                statement.execute("INSERT INTO probe VALUES (42)");
            }
            byte[] original = Files.readAllBytes(database);
            String readOnly = "jdbc:sqlite:" + database.toUri() + "?mode=ro";
            try (Connection connection = JDBC.createConnection(readOnly, new Properties());
                 var statement = connection.createStatement()) {
                try (var result = statement.executeQuery("SELECT value FROM probe")) {
                    if (!result.next() || result.getInt(1) != 42) throw new IllegalStateException("Read failed");
                }
                boolean rejected = false;
                try { statement.executeUpdate("INSERT INTO probe VALUES (99)"); }
                catch (SQLException expected) { rejected = true; }
                if (!rejected) throw new IllegalStateException("Read-only connection accepted a write");
            }
            if (!Arrays.equals(original, Files.readAllBytes(database)))
                throw new IllegalStateException("Read-only access changed the database");
            System.out.println("SHADED_SQLITE_READ_ONLY_OK");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
