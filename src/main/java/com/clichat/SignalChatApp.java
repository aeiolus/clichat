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
import java.util.Arrays;
import java.util.regex.Pattern;

public class SignalChatApp {
    private static final Pattern PHONE_NUMBER_PATTERN = Pattern.compile("^\\+[1-9]\\d{1,14}$");
    private Terminal terminal;
    private Screen screen;
    private WindowBasedTextGUI gui;
    private SignalCliWrapper signalCli;
    private Config config;

    public SignalChatApp() {
        try {
            config = new Config();
            signalCli = new SignalCliWrapper(config.getPhoneNumber());
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

    private void showMainWindow() throws IOException {
        // Create the main window
        BasicWindow window = new BasicWindow("Signal Chat");
        Panel mainPanel = new Panel(new GridLayout(1));

        // Create the recipient input field
        Panel recipientPanel = new Panel(new GridLayout(2));
        recipientPanel.addComponent(new Label("To:"));
        TextBox recipientInput = new TextBox(new TerminalSize(40, 1));
        recipientPanel.addComponent(recipientInput);
        mainPanel.addComponent(recipientPanel);

        // Create the message input field
        Panel messagePanel = new Panel(new GridLayout(2));
        messagePanel.addComponent(new Label("Message:"));
        TextBox messageInput = new TextBox(new TerminalSize(40, 3));
        messagePanel.addComponent(messageInput);
        mainPanel.addComponent(messagePanel);

        // Create the send button
        Button sendButton = new Button("Send", () -> {
            try {
                String recipient = recipientInput.getText().trim();
                String message = messageInput.getText().trim();

                if (recipient.isEmpty() || message.isEmpty()) {
                    MessageDialog.showMessageDialog(gui, "Error", "Both recipient and message are required.");
                    return;
                }

                if (!PHONE_NUMBER_PATTERN.matcher(recipient).matches()) {
                    MessageDialog.showMessageDialog(gui, "Error", "Invalid phone number format. Use international format (e.g., +1234567890)");
                    return;
                }

                String result = signalCli.sendMessage(recipient, message);
                MessageDialog.showMessageDialog(gui, "Success", result);
                messageInput.setText("");
            } catch (IOException e) {
                MessageDialog.showMessageDialog(gui, "Error", "Failed to send message: " + e.getMessage());
            }
        });
        mainPanel.addComponent(new Panel(new GridLayout(1)).addComponent(sendButton));

        // Add the main panel to the window
        window.setComponent(mainPanel);

        // Show the window
        gui.addWindowAndWait(window);
    }

    public static void main(String[] args) {
        SignalChatApp app = new SignalChatApp();
        app.start();
    }
}
