package me.itzmr.nebulavisuals.render.engine3d;

import me.itzmr.nebulavisuals.render.engine2d.shader.ShaderProgram;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * Аппаратный инстансинг 3D-сфер через {@code glDrawElementsInstanced}.
 *
 * <p>Геометрия единичной UV-сферы (24x16) загружается в статические VBO/EBO один раз при инициализации.
 * Данные каждого экземпляра (camera-relative центр, радиус, RGBA-цвет, сила Fresnel-свечения)
 * записываются напрямую в off-heap память ({@link MemoryUtil#memAlloc}) без аллокаций в куче Java.</p>
 */
public final class SphereBatch3D implements AutoCloseable {
    private static final int STACKS = 16;
    private static final int SECTORS = 24;

    private static final int MAX_INSTANCES = 16384;
    // float relX, relY, relZ (12) + float radius (4) + ubyte r,g,b,a (4) + float glow (4) = 24 bytes
    private static final int INSTANCE_STRIDE = 24;
    private static final int BUFFER_CAPACITY = MAX_INSTANCES * INSTANCE_STRIDE;

    private final ShaderProgram shader;

    private int vao;
    private int meshVbo;
    private int meshEbo;
    private int instanceVbo;
    private int indexCount;

    private ByteBuffer instanceBuffer;
    private long instanceAddress;
    private int instanceCount = 0;

    private boolean isDrawing = false;
    private BlendMode currentBlendMode = BlendMode.ADDITIVE;

    public SphereBatch3D() {
        this.shader = ShaderProgram.fromResources(
                "/assets/nebulavisuals/shaders/nebula_sphere.vsh",
                "/assets/nebulavisuals/shaders/nebula_sphere.fsh"
        );
        initBuffers();
    }

    private void initBuffers() {
        instanceBuffer = MemoryUtil.memAlloc(BUFFER_CAPACITY);
        instanceAddress = MemoryUtil.memAddress(instanceBuffer);

        vao = GL30.glGenVertexArrays();
        meshVbo = GL15.glGenBuffers();
        meshEbo = GL15.glGenBuffers();
        instanceVbo = GL15.glGenBuffers();

        GL30.glBindVertexArray(vao);

        uploadUnitSphereMesh();

        // Буфер инстансов (обновляется каждый кадр)
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceVbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, BUFFER_CAPACITY, GL15.GL_STREAM_DRAW);

        // location 2: vec3 iCenter
        GL20.glEnableVertexAttribArray(2);
        GL20.glVertexAttribPointer(2, 3, GL11.GL_FLOAT, false, INSTANCE_STRIDE, 0L);
        GL33.glVertexAttribDivisor(2, 1);

        // location 3: float iRadius
        GL20.glEnableVertexAttribArray(3);
        GL20.glVertexAttribPointer(3, 1, GL11.GL_FLOAT, false, INSTANCE_STRIDE, 12L);
        GL33.glVertexAttribDivisor(3, 1);

        // location 4: vec4 iColor (normalized RGBA8)
        GL20.glEnableVertexAttribArray(4);
        GL20.glVertexAttribPointer(4, 4, GL11.GL_UNSIGNED_BYTE, true, INSTANCE_STRIDE, 16L);
        GL33.glVertexAttribDivisor(4, 1);

        // location 5: float iGlow
        GL20.glEnableVertexAttribArray(5);
        GL20.glVertexAttribPointer(5, 1, GL11.GL_FLOAT, false, INSTANCE_STRIDE, 20L);
        GL33.glVertexAttribDivisor(5, 1);

        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    private void uploadUnitSphereMesh() {
        int vertCount = (STACKS + 1) * (SECTORS + 1);
        indexCount = STACKS * SECTORS * 6;

        FloatBuffer vertices = MemoryUtil.memAllocFloat(vertCount * 6);
        ShortBuffer indices = MemoryUtil.memAllocShort(indexCount);
        try {
            for (int stack = 0; stack <= STACKS; stack++) {
                double phi = Math.PI * stack / STACKS;
                double sinPhi = Math.sin(phi);
                float y = (float) Math.cos(phi);

                for (int sector = 0; sector <= SECTORS; sector++) {
                    double theta = (2.0 * Math.PI) * sector / SECTORS;
                    float x = (float) (sinPhi * Math.sin(theta));
                    float z = (float) (sinPhi * Math.cos(theta));

                    // aPos (единичный радиус) + aNormal (совпадает с позицией на единичной сфере)
                    vertices.put(x).put(y).put(z);
                    vertices.put(x).put(y).put(z);
                }
            }
            vertices.flip();

            int rowStride = SECTORS + 1;
            for (int stack = 0; stack < STACKS; stack++) {
                for (int sector = 0; sector < SECTORS; sector++) {
                    int k1 = stack * rowStride + sector;
                    int k2 = k1 + rowStride;

                    // CCW обход для внешней стороны сферы
                    indices.put((short) k1);
                    indices.put((short) k2);
                    indices.put((short) (k2 + 1));

                    indices.put((short) k1);
                    indices.put((short) (k2 + 1));
                    indices.put((short) (k1 + 1));
                }
            }
            indices.flip();

            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, meshVbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STATIC_DRAW);

            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, meshEbo);
            GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indices, GL15.GL_STATIC_DRAW);

            // location 0: vec3 aPos, location 1: vec3 aNormal
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 24, 0L);
            GL20.glEnableVertexAttribArray(1);
            GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, 24, 12L);
        } finally {
            MemoryUtil.memFree(vertices);
            MemoryUtil.memFree(indices);
        }
    }

    public void begin() {
        begin(BlendMode.ADDITIVE);
    }

    public void begin(BlendMode defaultBlendMode) {
        if (isDrawing) {
            throw new IllegalStateException("SphereBatch3D.begin() called while already drawing");
        }
        this.isDrawing = true;
        this.instanceCount = 0;
        this.currentBlendMode = defaultBlendMode != null ? defaultBlendMode : BlendMode.ADDITIVE;
    }

    public void drawSphere(double worldX, double worldY, double worldZ, float radius, int argb, float glow) {
        drawSphere(worldX, worldY, worldZ, radius, argb, glow, currentBlendMode);
    }

    public void drawSphere(double worldX, double worldY, double worldZ, float radius, int argb, float glow, BlendMode blendMode) {
        drawSphereRelative(
                Camera3D.relX(worldX),
                Camera3D.relY(worldY),
                Camera3D.relZ(worldZ),
                radius,
                argb,
                glow,
                blendMode
        );
    }

    public void drawSphereRelative(float relX, float relY, float relZ, float radius, int argb, float glow, BlendMode blendMode) {
        if (!isDrawing || radius <= 0.0f) return;

        int alphaByte = (argb >>> 24) & 0xFF;
        if (alphaByte == 0) return;

        BlendMode targetMode = blendMode != null ? blendMode : BlendMode.ADDITIVE;
        if (targetMode != currentBlendMode) {
            flush();
            currentBlendMode = targetMode;
        } else if (instanceCount >= MAX_INSTANCES) {
            flush();
        }

        long ptr = instanceAddress + (long) instanceCount * INSTANCE_STRIDE;
        MemoryUtil.memPutFloat(ptr, relX);
        MemoryUtil.memPutFloat(ptr + 4L, relY);
        MemoryUtil.memPutFloat(ptr + 8L, relZ);
        MemoryUtil.memPutFloat(ptr + 12L, radius);

        MemoryUtil.memPutByte(ptr + 16L, (byte) ((argb >>> 16) & 0xFF));
        MemoryUtil.memPutByte(ptr + 17L, (byte) ((argb >>> 8) & 0xFF));
        MemoryUtil.memPutByte(ptr + 18L, (byte) (argb & 0xFF));
        MemoryUtil.memPutByte(ptr + 19L, (byte) alphaByte);

        MemoryUtil.memPutFloat(ptr + 20L, glow);
        instanceCount++;
    }

    public void flush() {
        if (!isDrawing || instanceCount <= 0 || indexCount <= 0) return;

        GL11.glEnable(GL11.GL_BLEND);
        currentBlendMode.apply();
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glCullFace(GL11.GL_BACK);

        shader.bind();
        shader.setUniformMatrix4fv("uViewProj", Camera3D.getViewProjBuffer());

        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceVbo);

        // Buffer orphaning для исключения GPU pipeline stall
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, BUFFER_CAPACITY, GL15.GL_STREAM_DRAW);
        instanceBuffer.limit(instanceCount * INSTANCE_STRIDE);
        instanceBuffer.position(0);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, instanceBuffer);

        GL31.glDrawElementsInstanced(GL11.GL_TRIANGLES, indexCount, GL11.GL_UNSIGNED_SHORT, 0L, instanceCount);

        GL30.glBindVertexArray(0);
        instanceCount = 0;
    }

    public void end() {
        if (!isDrawing) return;
        flush();
        shader.unbind();
        isDrawing = false;
    }

    public int getPendingInstanceCount() {
        return instanceCount;
    }

    @Override
    public void close() {
        if (instanceBuffer != null) {
            MemoryUtil.memFree(instanceBuffer);
            instanceBuffer = null;
            instanceAddress = 0L;
        }
        if (vao != 0) GL30.glDeleteVertexArrays(vao);
        if (meshVbo != 0) GL15.glDeleteBuffers(meshVbo);
        if (meshEbo != 0) GL15.glDeleteBuffers(meshEbo);
        if (instanceVbo != 0) GL15.glDeleteBuffers(instanceVbo);
        vao = 0;
        meshVbo = 0;
        meshEbo = 0;
        instanceVbo = 0;
        shader.delete();
    }
}
