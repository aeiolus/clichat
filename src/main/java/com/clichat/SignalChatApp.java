package com.clichat;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.*;
import com.googlecode.lanterna.gui2.dialogs.MessageDialog;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

public class SignalChatApp {
    private static final Pattern PHONE_NUMBER_PATTERN = Pattern.compile("^\\+[1-9]\\d{1,14}$");
    private Terminal terminal;
    private Screen screen;
    private WindowBasedTextGUI gui;
    private SignalCliWrapper signalCli;
    private Config config;
    private String currentRecipient;
    private Map<String, List<String>> messageHistory;
    private TextBox messagesBox;

    public SignalChatApp() {
        try {
            config = new Config();
            signalCli = new SignalCliWrapper(config.getPhoneNumber());
            messageHistory = new HashMap<>();
            setupTerminal();
        } catch (IOException e) {
            System.err.println("Failed to initialize Signal Chat App: " + e.getMessage());
            System.exit(1);
        }
    }

    private void setupTerminal() throws IOException {
        terminal = new DefaultTerminalFactory().createTerminal();
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
        if (currentRecipient != null && messageHistory.containsKey(currentRecipient)) {
            StringBuilder messages = new StringBuilder();
            for (String msg : messageHistory.get(currentRecipient)) {
                messages.append(msg).append("\n");
            }
            messagesBox.setText(messages.toString());
        } else {
            messagesBox.setText("");
        }
    }

    private void addMessage(String recipient, String message, boolean sent) {
        messageHistory.computeIfAbsent(recipient, k -> new ArrayList<>());
        String prefix = sent ? "You: " : "Them: ";
        messageHistory.get(recipient).add(prefix + message);
        if (recipient.equals(currentRecipient)) {
            updateMessagesView();
        }
    }

    private void showMainWindow() throws IOException {
        // Create the main window
        BasicWindow window = new BasicWindow("Signal Chat");
        Panel mainPanel = new Panel(new GridLayout(2));

        // Left column - Contacts
        Panel contactsPanel = new Panel(new GridLayout(1));
        contactsPanel.setPreferredSize(new TerminalSize(20, 20));
        
        ActionListBox contactsList = new ActionListBox(new TerminalSize(18, 18));
        contactsList.addItem("+ Add Contact", () -> {
            MessageDialog.showMessageDialog(gui, "Add Contact", "Enter phone number in the message input below");
            currentRecipient = null;
            updateMessagesView();
        });
        contactsPanel.addComponent(contactsList);
        mainPanel.addComponent(contactsPanel);

        // Right column - Messages and Input
        Panel rightPanel = new Panel(new GridLayout(1));
        rightPanel.setPreferredSize(new TerminalSize(60, 20));

        // Messages view
        messagesBox = new TextBox(new TerminalSize(58, 16));
        messagesBox.setReadOnly(true);
        rightPanel.addComponent(messagesBox);

        // Input panel at bottom
        Panel inputPanel = new Panel(new GridLayout(2));
        TextBox messageInput = new TextBox(new TerminalSize(45, 1));
        inputPanel.addComponent(messageInput);
        
        Button sendButton = new Button("Send", () -> {
            try {
                String message = messageInput.getText().trim();
                if (message.isEmpty()) {
                    return;
                }

                // If no recipient is selected, treat input as a phone number
                if (currentRecipient == null) {
                    String newContact = message;
                    if (!PHONE_NUMBER_PATTERN.matcher(newContact).matches()) {
                        MessageDialog.showMessageDialog(gui, "Error", "Invalid phone number format. Use international format (e.g., +1234567890)");
                        return;
                    }
                    currentRecipient = newContact;
                    contactsList.addItem(newContact, () -> {
                        currentRecipient = newContact;
                        updateMessagesView();
                    });
                    messageInput.setText("");
                    return;
                }

                // Send the message to current recipient
                String result = signalCli.sendMessage(currentRecipient, message);
                addMessage(currentRecipient, message, true);
                messageInput.setText("");
            } catch (IOException e) {
                MessageDialog.showMessageDialog(gui, "Error", "Failed to send message: " + e.getMessage());
            }
        });
        inputPanel.addComponent(sendButton);
        rightPanel.addComponent(inputPanel);

        mainPanel.addComponent(rightPanel);

        // Add the main panel to the window
        window.setComponent(mainPanel);

        // Show the window
        gui.addWindowAndWait(window);
    }

    public static void main(String[] args) {
        new SignalChatApp().start();
    }
}
