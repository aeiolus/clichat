# Signal CLI Chat Interface

An interactive terminal-based chat interface for Signal messaging using signal-cli.

## Prerequisites

1. Java 11 or higher
2. Maven
3. signal-cli installed and configured on your system
4. A registered Signal account

## Setup

1. Replace `+YOUR_NUMBER` in `SignalChatApp.java` with your registered Signal phone number (including country code)
2. Build the project:
   ```bash
   mvn clean package
   ```

## Running the Application

```bash
java -jar target/signal-chat-1.0-SNAPSHOT-jar-with-dependencies.jar
```

## Features

- Two-column interface with contacts list and message area
- Real-time message updates
- Navigate contacts using keyboard
- Send and receive messages in real-time
- Full terminal screen utilization

## Navigation

- Use TAB to move between contacts list and message input
- Use arrow keys to navigate the contacts list
- Press ENTER to send a message

## Note

Make sure signal-cli is properly configured and working on your system before running this application. You can test it by running:
```bash
signal-cli -u +YOUR_NUMBER receive
```
