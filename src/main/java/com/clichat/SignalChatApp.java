package com.clichat;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.*;
import com.googlecode.lanterna.gui2.dialogs.MessageDialog;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Pattern;

public class SignalChatApp {
    private static final Pattern PHONE_NUMBER_PATTERN = Pattern.compile("^\\+[1-9]\\d{1,14}$");
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private Terminal terminal;
    private Screen screen;
    private WindowBasedTextGUI gui;
    private SignalCliWrapper signalCli;
    private Config config;
    private String currentRecipient;
    private Map<String, List<SignalCliWrapper.Message>> messageHistory;
    private TextBox messagesBox;
    private ActionListBox contactsList;
    private MessageDatabase database;
    private ImageRenderer imageRenderer;

    public SignalChatApp() {
        try {
            config = new Config();
            signalCli = new SignalCliWrapper(config.getPhoneNumber());
            messageHistory = new HashMap<>();
            database = new MessageDatabase(config.getPhoneNumber());
            imageRenderer = new ImageRenderer();
            setupTerminal();

            // Load message history from database
            loadMessageHistoryFromDatabase();

            // Start receiving messages in background
            signalCli.addMessageListener(message -> {
                // System.out.println("Message listener called with message: " + message);
                String recipient = message.getSourceNumber();
                if (recipient == null || recipient.isEmpty()) {
                    recipient = config.getPhoneNumber();
                }
                // System.out.println("Adding message to history for recipient: " + recipient + ", current recipient is: " + currentRecipient);

                try {
                    // Save message to database
                    database.saveMessage(message);

                    // Save any attachments
                    for (SignalCliWrapper.Attachment attachment : message.getAttachments()) {
                        File tempFile = File.createTempFile("signal-", "-attachment");
                        try {
                            Files.write(tempFile.toPath(), attachment.getData());
                            database.saveAttachment(message.getTimestamp(), tempFile, attachment.getContentType());
                        } finally {
                            tempFile.delete();
                        }
                    }

                    // Update in-memory history
                    messageHistory.computeIfAbsent(recipient, k -> Collections.synchronizedList(new ArrayList<>()));
                    List<SignalCliWrapper.Message> messages = messageHistory.get(recipient);
                    synchronized (messages) {
                        messages.add(message);
                        // System.out.println("Message history for " + recipient + " now has " + messages.size() + " messages");
                    }

                    if (recipient.equals(currentRecipient)) {
                        // System.out.println("Updating messages view for current recipient");
                        updateMessagesView();
                    }
                } catch (SQLException | IOException e) {
                    System.err.println("Error saving message to database: " + e.getMessage());
                    e.printStackTrace();
                }
            });
            signalCli.startReceiving();

            // Add shutdown hook to cleanup resources
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    signalCli.stopReceiving();
                    database.close();
                    if (screen != null) {
                        screen.stopScreen();
                    }
                    if (terminal != null) {
                        terminal.close();
                    }
                } catch (IOException | SQLException e) {
                    System.err.println("Error during shutdown: " + e.getMessage());
                }
            }));
        } catch (IOException | SQLException e) {
            System.err.println("Failed to initialize Signal Chat App: " + e.getMessage());
            System.exit(1);
        }
    }

    private void loadMessageHistoryFromDatabase() {
        try {
            // Clear existing history
            messageHistory.clear();

            // Load contacts
            List<SignalCliWrapper.Contact> contacts = signalCli.listContacts();
            for (SignalCliWrapper.Contact contact : contacts) {
                String recipient = contact.getNumber();
                List<SignalCliWrapper.Message> messages = database.loadMessages(recipient);
                messageHistory.put(recipient, Collections.synchronizedList(new ArrayList<>(messages)));
            }
        } catch (SQLException | IOException e) {
            System.err.println("Error loading message history from database: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void setupTerminal() throws IOException {
        DefaultTerminalFactory terminalFactory = new DefaultTerminalFactory();
        terminal = terminalFactory.createTerminal();
        screen = new TerminalScreen(terminal);
        screen.startScreen();
        gui = new MultiWindowTextGUI(screen, new DefaultWindowManager(), new EmptySpace(TextColor.ANSI.BLACK));
    }

    public void start() {
        try {
            showMainWindow();
        } catch (IOException e) {
            System.err.println("Error running Signal Chat App: " + e.getMessage());
            System.exit(1);
        }
    }

    private void updateMessagesView() {
        try {
            // System.out.println("updateMessagesView called, currentRecipient=" + currentRecipient);
            if (currentRecipient != null && messageHistory.containsKey(currentRecipient)) {
                // Format and display messages
                StringBuilder formattedMessages = new StringBuilder();
                List<SignalCliWrapper.Message> messages = messageHistory.get(currentRecipient);
                // System.out.println("Found " + messages.size() + " messages for " + currentRecipient);
                synchronized (messages) {
                    // Sort messages by timestamp
                    messages.sort(Comparator.comparingLong(SignalCliWrapper.Message::getTimestamp));

                    for (SignalCliWrapper.Message msg : messages) {
                        String timestamp = DATE_FORMAT.format(new Date(msg.getTimestamp()));
                        String prefix = msg.isSent() ? "You" : "Them";
                        formattedMessages.append(String.format("[%s] %s: %s\n", timestamp, prefix, msg.getContent()));

                        // Handle attachments
                        for (SignalCliWrapper.Attachment attachment : msg.getAttachments()) {
                            File file = new File(attachment.getFilePath());
                            if (imageRenderer.isImageFile(file.getName())) {
                                // Get terminal width for scaling
                                TerminalSize size = terminal.getTerminalSize();
                                int maxWidth = size.getColumns() - 4; // Leave some margin

                                // Render and append image
                                String renderedImage = imageRenderer.renderImage(file, maxWidth);
                                formattedMessages.append(renderedImage).append("\n");
                            } else {
                                formattedMessages.append(String.format("[Attachment: %s]\n", file.getName()));
                            }
                        }
                    }
                }
                // System.out.println("Setting messages text: " + formattedMessages.toString());
                messagesBox.setText(formattedMessages.toString());
            } else {
                // System.out.println("No messages found for current recipient");
                messagesBox.setText("");
            }
        } catch (IOException e) {
            System.err.println("Error updating messages view: " + e.getMessage());
            messagesBox.setText("[Error displaying messages]");
        }
    }

    private void addMessage(String recipient, String message, boolean sent) {
        messageHistory.computeIfAbsent(recipient, k -> Collections.synchronizedList(new ArrayList<>()));
        List<SignalCliWrapper.Message> messages = messageHistory.get(recipient);
        synchronized (messages) {
            messages.add(new SignalCliWrapper.Message(message, System.currentTimeMillis(), sent, recipient));
        }
        if (recipient.equals(currentRecipient)) {
            updateMessagesView();
        }
    }

    private void loadContacts() {
        try {
            List<SignalCliWrapper.Contact> contacts = signalCli.listContacts();
            for (SignalCliWrapper.Contact contact : contacts) {
                String number = contact.getNumber();
                contactsList.addItem(contact.getDisplayName(), () -> {
                    currentRecipient = number;
                    updateMessagesView();
                });
            }
        } catch (IOException e) {
            System.err.println("Error loading contacts: " + e.getMessage());
            MessageDialog.showMessageDialog(gui, "Error", "Failed to load contacts: " + e.getMessage());
        }
    }

    private void showMainWindow() throws IOException {
        // Create main window
        BasicWindow window = new BasicWindow("Signal Chat");
        window.setHints(Arrays.asList(Window.Hint.FULL_SCREEN));

        // Create panels with specific sizes
        Panel mainPanel = new Panel(new LinearLayout(Direction.HORIZONTAL));
        Panel leftPanel = new Panel(new LinearLayout(Direction.VERTICAL));
        Panel rightPanel = new Panel(new LinearLayout(Direction.VERTICAL));

        // Configure panel sizes
        leftPanel.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.Beginning, LinearLayout.GrowPolicy.None));
        rightPanel.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow));

        // Add components
        contactsList = new ActionListBox();
        contactsList.setPreferredSize(new TerminalSize(20, 10));
        leftPanel.addComponent(contactsList);
        loadContacts();

        messagesBox = new TextBox();
        messagesBox.setReadOnly(true);
        messagesBox.setPreferredSize(new TerminalSize(60, 20));
        rightPanel.addComponent(messagesBox);

        TextBox inputBox = new TextBox();
        inputBox.setPreferredSize(new TerminalSize(60, 1));
        Button sendButton = new Button("Send", () -> {
            try {
                String message = inputBox.getText();
                if (!message.isEmpty() && currentRecipient != null) {
                    signalCli.sendMessage(currentRecipient, message);
                    addMessage(currentRecipient, message, true);
                    inputBox.setText("");
                }
            } catch (IOException e) {
                MessageDialog.showMessageDialog(gui, "Error", "Failed to send message: " + e.getMessage());
            }
        });

        Panel inputPanel = new Panel(new LinearLayout(Direction.HORIZONTAL));
        inputPanel.addComponent(inputBox);
        inputPanel.addComponent(sendButton);
        rightPanel.addComponent(inputPanel);

        mainPanel.addComponent(leftPanel);
        mainPanel.addComponent(rightPanel);
        window.setComponent(mainPanel);

        gui.addWindowAndWait(window);
    }

    public static void main(String[] args) {
        new SignalChatApp().start();
    }
}
