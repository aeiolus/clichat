package com.clichat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class MessageDatabase {
    private final String dbPath;
    private final String attachmentsPath;
    private final Connection connection;

    public MessageDatabase(String phoneNumber) throws SQLException, IOException {
        // Create database directory if it doesn't exist
        String baseDir = System.getProperty("user.home") + "/.clichat/" + phoneNumber;
        new File(baseDir).mkdirs();

        // Set up paths
        this.dbPath = baseDir + "/messages.db";
        this.attachmentsPath = baseDir + "/attachments";
        new File(attachmentsPath).mkdirs();

        // Initialize database
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        initializeDatabase();
    }

    private void initializeDatabase() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // Create messages table
            stmt.execute("""
                        CREATE TABLE IF NOT EXISTS messages (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            recipient TEXT NOT NULL,
                            content TEXT,
                            timestamp BIGINT NOT NULL,
                            sent BOOLEAN NOT NULL,
                            source_number TEXT
                        )
                    """);

            // Create attachments table
            stmt.execute("""
                        CREATE TABLE IF NOT EXISTS attachments (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            message_id INTEGER NOT NULL,
                            file_path TEXT NOT NULL,
                            content_type TEXT NOT NULL,
                            FOREIGN KEY (message_id) REFERENCES messages(id)
                        )
                    """);

            // Create indexes
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_messages_recipient ON messages(recipient)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_messages_timestamp ON messages(timestamp)");
        }
    }

    public void saveMessage(SignalCliWrapper.Message message) throws SQLException {
        String sql = """
                    INSERT INTO messages (recipient, content, timestamp, sent, source_number)
                    VALUES (?, ?, ?, ?, ?)
                """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setString(1, message.getSourceNumber());
            pstmt.setString(2, message.getContent());
            pstmt.setLong(3, message.getTimestamp());
            pstmt.setBoolean(4, message.isSent());
            pstmt.setString(5, message.getSourceNumber());
            pstmt.executeUpdate();
        }
    }

    public void saveAttachment(long messageId, File file, String contentType) throws IOException, SQLException {
        // Copy file to attachments directory with a unique name
        String fileName = messageId + "_" + System.currentTimeMillis() + "_" + file.getName();
        Path targetPath = Paths.get(attachmentsPath, fileName);
        Files.copy(file.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING);

        // Save attachment record
        String sql = "INSERT INTO attachments (message_id, file_path, content_type) VALUES (?, ?, ?)";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, messageId);
            pstmt.setString(2, targetPath.toString());
            pstmt.setString(3, contentType);
            pstmt.executeUpdate();
        }
    }

    public List<SignalCliWrapper.Message> loadMessages(String recipient) throws SQLException {
        List<SignalCliWrapper.Message> messages = new ArrayList<>();
        String sql = "SELECT * FROM messages WHERE recipient = ? ORDER BY timestamp ASC";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, recipient);
            ResultSet rs = pstmt.executeQuery();

            while (rs.next()) {
                messages.add(new SignalCliWrapper.Message(
                        rs.getString("content"),
                        rs.getLong("timestamp"),
                        rs.getBoolean("sent"),
                        rs.getString("source_number")
                ));
            }
        }
        return messages;
    }

    public List<Attachment> getAttachments(long messageId) throws SQLException {
        List<Attachment> attachments = new ArrayList<>();
        String sql = "SELECT * FROM attachments WHERE message_id = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, messageId);
            ResultSet rs = pstmt.executeQuery();

            while (rs.next()) {
                attachments.add(new Attachment(
                        rs.getLong("id"),
                        rs.getString("file_path"),
                        rs.getString("content_type")
                ));
            }
        }
        return attachments;
    }

    public void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    public static class Attachment {
        private final long id;
        private final String filePath;
        private final String contentType;

        public Attachment(long id, String filePath, String contentType) {
            this.id = id;
            this.filePath = filePath;
            this.contentType = contentType;
        }

        public long getId() {
            return id;
        }

        public String getFilePath() {
            return filePath;
        }

        public String getContentType() {
            return contentType;
        }
    }
}
