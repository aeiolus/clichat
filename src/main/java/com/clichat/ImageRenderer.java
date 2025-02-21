package com.clichat;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

public class ImageRenderer {
    private static final int DEFAULT_WIDTH = 80;
    private static final int DEFAULT_HEIGHT = 24;
    private static final String[] ASCII_CHARS = {" ", ".", ":", "-", "=", "+", "*", "#", "%", "@"};

    private final boolean supportsIterm2Images;

    public ImageRenderer() {
        // Check if running in iTerm2
        String term = System.getenv("TERM_PROGRAM");
        supportsIterm2Images = "iTerm.app".equals(term);
    }

    public String renderImage(File imageFile, int maxWidth) throws IOException {
        if (supportsIterm2Images) {
            return renderIterm2Image(imageFile, maxWidth);
        } else {
            return renderAsciiImage(imageFile, maxWidth);
        }
    }

    private String renderAsciiImage(File imageFile, int maxWidth) throws IOException {
        BufferedImage image = ImageIO.read(imageFile);
        if (image == null) {
            throw new IOException("Failed to read image: " + imageFile);
        }

        // Calculate new dimensions while maintaining aspect ratio
        int originalWidth = image.getWidth();
        int originalHeight = image.getHeight();
        int newWidth = Math.min(maxWidth, DEFAULT_WIDTH);
        // Terminal characters are roughly twice as tall as they are wide
        int newHeight = (originalHeight * newWidth * 2) / originalWidth;
        newHeight = Math.min(newHeight, DEFAULT_HEIGHT);

        // Resize image
        BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, 0, 0, newWidth, newHeight, null);
        g.dispose();

        StringBuilder ascii = new StringBuilder();
        for (int y = 0; y < newHeight; y++) {
            for (int x = 0; x < newWidth; x++) {
                int pixel = resized.getRGB(x, y);
                int red = (pixel >> 16) & 0xff;
                int green = (pixel >> 8) & 0xff;
                int blue = pixel & 0xff;
                // Convert to grayscale
                float gray = 0.299f * red + 0.587f * green + 0.114f * blue;
                // Normalize and pick character
                int index = Math.round((gray * (ASCII_CHARS.length - 1)) / 255.0f);
                ascii.append(ASCII_CHARS[index]);
            }
            ascii.append("\n");
        }
        return ascii.toString();
    }

    private String renderIterm2Image(File imageFile, int maxWidth) throws IOException {
        // iTerm2 inline image protocol
        byte[] imageData = java.nio.file.Files.readAllBytes(imageFile.toPath());
        String base64Image = java.util.Base64.getEncoder().encodeToString(imageData);

        // Calculate dimensions
        BufferedImage image = ImageIO.read(imageFile);
        if (image == null) {
            throw new IOException("Failed to read image: " + imageFile);
        }

        int width = Math.min(image.getWidth(), maxWidth);
        int height = (image.getHeight() * width) / image.getWidth();

        // Format: ESC ] 1337 ; File = [arguments] : base64 ESC
        return String.format("\u001B]1337;File=inline=1;width=%dpx;height=%dpx:%s\u0007\n",
                width, height, base64Image);
    }

    public boolean isImageFile(String filePath) {
        String lower = filePath.toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".png") || lower.endsWith(".gif");
    }
}
