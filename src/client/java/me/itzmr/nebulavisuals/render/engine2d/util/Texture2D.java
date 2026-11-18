package me.itzmr.nebulavisuals.render.engine2d.util;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * Утилита загрузки 2D PNG-текстур из classpath-ресурсов в OpenGL 3.3+ с безопасным сохранением GL-состояния.
 */
public final class Texture2D implements AutoCloseable {
    private int textureId;
    private final int width;
    private final int height;

    private Texture2D(int textureId, int width, int height) {
        this.textureId = textureId;
        this.width = width;
        this.height = height;
    }

    /**
     * Загружает PNG ресурс из classpath (например, "/assets/nebulavisuals/png/logo.png")
     * в OpenGL текстуру с линейной фильтрацией и отключёнными mip-уровнями.
     */
    public static Texture2D fromResource(String resourcePath) {
        byte[] bytes = readResourceBytes(resourcePath);
        ByteBuffer nativeData = MemoryUtil.memAlloc(bytes.length);
        nativeData.put(bytes).flip();

        int tex;
        int imgW;
        int imgH;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer comp = stack.mallocInt(1);

            ByteBuffer image = STBImage.stbi_load_from_memory(nativeData, w, h, comp, 4);
            if (image == null) {
                throw new RuntimeException("Failed to decode PNG image: " + resourcePath + " (" + STBImage.stbi_failure_reason() + ")");
            }

            imgW = w.get(0);
            imgH = h.get(0);

            int prevActiveTex = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            int prevTex2D = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

            int prevUnpackAlign = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
            int prevUnpackRowLen = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
            int prevUnpackSkipPx = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS);
            int prevUnpackSkipRows = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS);

            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);

            tex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, imgW, imgH, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, image);

            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, prevUnpackAlign);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, prevUnpackRowLen);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, prevUnpackSkipPx);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, prevUnpackSkipRows);

            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex2D);
            GL13.glActiveTexture(prevActiveTex);
            STBImage.stbi_image_free(image);
        } finally {
            MemoryUtil.memFree(nativeData);
        }

        return new Texture2D(tex, imgW, imgH);
    }

    public static byte[] readResourceBytes(String path) {
        try (InputStream stream = openResourceStream(path)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read resource bytes: " + path, e);
        }
    }

    public static InputStream openResourceStream(String path) {
        String cleanPath = path.startsWith("/") ? path.substring(1) : path;
        String slashPath = path.startsWith("/") ? path : "/" + path;

        InputStream in = Texture2D.class.getResourceAsStream(slashPath);
        if (in == null) {
            in = Texture2D.class.getResourceAsStream(cleanPath);
        }
        if (in == null && Texture2D.class.getClassLoader() != null) {
            in = Texture2D.class.getClassLoader().getResourceAsStream(cleanPath);
        }
        if (in == null && Thread.currentThread().getContextClassLoader() != null) {
            in = Thread.currentThread().getContextClassLoader().getResourceAsStream(cleanPath);
        }
        if (in == null) {
            throw new IllegalArgumentException("Resource not found: " + path + " (checked: " + cleanPath + ", " + slashPath + ")");
        }
        return in;
    }

    public int getTextureId() {
        return textureId;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    @Override
    public void close() {
        if (textureId != 0) {
            GL11.glDeleteTextures(textureId);
            textureId = 0;
        }
    }
}
