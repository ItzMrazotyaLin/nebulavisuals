package me.itzmr.nebulavisuals.render.engine2d.shader;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryStack;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Обертка для компиляции, линковки и управления GLSL шейдерной программой.
 * Кеширует локации uniform-переменных для максимального быстродействия.
 */
public class ShaderProgram {
    private final int programId;
    private final Map<String, Integer> uniformLocationCache = new HashMap<>();

    public ShaderProgram(String vertexSource, String fragmentSource) {
        int vShader = compileShader(GL20.GL_VERTEX_SHADER, vertexSource);
        int fShader = compileShader(GL20.GL_FRAGMENT_SHADER, fragmentSource);

        programId = GL20.glCreateProgram();
        GL20.glAttachShader(programId, vShader);
        GL20.glAttachShader(programId, fShader);
        GL20.glLinkProgram(programId);

        if (GL20.glGetProgrami(programId, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(programId);
            GL20.glDeleteProgram(programId);
            throw new RuntimeException("Shader program link failed: " + log);
        }

        // Шейдеры можно удалить после линковки программы
        GL20.glDetachShader(programId, vShader);
        GL20.glDetachShader(programId, fShader);
        GL20.glDeleteShader(vShader);
        GL20.glDeleteShader(fShader);
    }

    public static ShaderProgram fromResources(String vertexPath, String fragmentPath) {
        String vSrc = loadResource(vertexPath);
        String fSrc = loadResource(fragmentPath);
        return new ShaderProgram(vSrc, fSrc);
    }

    private static String loadResource(String path) {
        try (InputStream in = ShaderProgram.class.getResourceAsStream(path)) {
            if (in == null) {
                // Попытка загрузить через ClassLoader контекста
                InputStream clIn = Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
                if (clIn == null) {
                    throw new IllegalArgumentException("Resource not found: " + path);
                }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(clIn, StandardCharsets.UTF_8))) {
                    return reader.lines().collect(Collectors.joining("\n"));
                }
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read shader resource: " + path, e);
        }
    }

    private static int compileShader(int type, String source) {
        int shaderId = GL20.glCreateShader(type);
        GL20.glShaderSource(shaderId, source);
        GL20.glCompileShader(shaderId);

        if (GL20.glGetShaderi(shaderId, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shaderId);
            GL20.glDeleteShader(shaderId);
            String typeName = (type == GL20.GL_VERTEX_SHADER) ? "Vertex" : "Fragment";
            throw new RuntimeException(typeName + " shader compilation failed:\n" + log + "\nSource:\n" + source);
        }
        return shaderId;
    }

    public void bind() {
        GL20.glUseProgram(programId);
    }

    public void unbind() {
        GL20.glUseProgram(0);
    }

    public int getUniformLocation(String name) {
        return uniformLocationCache.computeIfAbsent(name, n -> GL20.glGetUniformLocation(programId, n));
    }

    public void setUniform1i(String name, int value) {
        int loc = getUniformLocation(name);
        if (loc != -1) GL20.glUniform1i(loc, value);
    }

    public void setUniform1f(String name, float value) {
        int loc = getUniformLocation(name);
        if (loc != -1) GL20.glUniform1f(loc, value);
    }

    public void setUniform2f(String name, float x, float y) {
        int loc = getUniformLocation(name);
        if (loc != -1) GL20.glUniform2f(loc, x, y);
    }

    public void setUniform3f(String name, float x, float y, float z) {
        int loc = getUniformLocation(name);
        if (loc != -1) GL20.glUniform3f(loc, x, y, z);
    }

    public void setUniform4f(String name, float x, float y, float z, float w) {
        int loc = getUniformLocation(name);
        if (loc != -1) GL20.glUniform4f(loc, x, y, z, w);
    }

    public void setUniformMatrix4fv(String name, FloatBuffer matrixBuffer) {
        int loc = getUniformLocation(name);
        if (loc != -1) {
            GL20.glUniformMatrix4fv(loc, false, matrixBuffer);
        }
    }

    public void setUniformMatrix3fv(String name, FloatBuffer matrixBuffer) {
        int loc = getUniformLocation(name);
        if (loc != -1) {
            GL20.glUniformMatrix3fv(loc, false, matrixBuffer);
        }
    }

    public void setOrthoProjection(String name, float left, float right, float bottom, float top, float zNear, float zFar) {
        int loc = getUniformLocation(name);
        if (loc == -1) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer fb = stack.mallocFloat(16);
            // Столбцовое (column-major) представление ортографической матрицы:
            float tx = -(right + left) / (right - left);
            float ty = -(top + bottom) / (top - bottom);
            float tz = -(zFar + zNear) / (zFar - zNear);

            fb.put(2.0f / (right - left)).put(0.0f).put(0.0f).put(0.0f);
            fb.put(0.0f).put(2.0f / (top - bottom)).put(0.0f).put(0.0f);
            fb.put(0.0f).put(0.0f).put(-2.0f / (zFar - zNear)).put(0.0f);
            fb.put(tx).put(ty).put(tz).put(1.0f);
            fb.flip();

            GL20.glUniformMatrix4fv(loc, false, fb);
        }
    }

    public int getProgramId() {
        return programId;
    }

    public void delete() {
        GL20.glDeleteProgram(programId);
    }
}
