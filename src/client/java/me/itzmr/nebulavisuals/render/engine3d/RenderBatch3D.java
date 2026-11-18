package me.itzmr.nebulavisuals.render.engine3d;

import me.itzmr.nebulavisuals.render.engine2d.shader.ShaderProgram;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Высокопроизводительный off-heap 3D-батчер для линий, боксов, колец, дисков и объёмных шлейфов.
 *
 * <p>Хранит вершины и индексы в нативной off-heap памяти ({@link MemoryUtil#memAlloc}) и автоматически
 * сбрасывает (flush) батч при смене примитива ({@code GL_TRIANGLES} / {@code GL_LINES}),
 * режима смешивания ({@link BlendMode}), толщины линии или заполнении буфера.</p>
 */
public final class RenderBatch3D implements AutoCloseable {
    private static final int MAX_VERTICES = 65536;
    private static final int MAX_INDICES = 196608;
    // float relX, relY, relZ (12) + ubyte r,g,b,a (4) = 16 bytes per vertex
    private static final int VERTEX_STRIDE = 16;
    private static final int VERTEX_BUFFER_CAPACITY = MAX_VERTICES * VERTEX_STRIDE;
    private static final int INDEX_BUFFER_CAPACITY = MAX_INDICES * Integer.BYTES;

    private static final float TWO_PI = (float) (Math.PI * 2.0);
    private static final int STAR_POINTS = 10;
    private static final int HEART_POINTS = 16;
    private static final float[] STAR_X = new float[STAR_POINTS];
    private static final float[] STAR_Y = new float[STAR_POINTS];
    private static final float[] HEART_X = new float[HEART_POINTS];
    private static final float[] HEART_Y = new float[HEART_POINTS];

    static {
        for (int i = 0; i < STAR_POINTS; i++) {
            double a = (i * Math.PI / 5.0) + (Math.PI * 0.5);
            float rad = (i & 1) == 0 ? 1.25f : 0.50f;
            STAR_X[i] = (float) Math.cos(a) * rad;
            STAR_Y[i] = (float) Math.sin(a) * rad;
        }
        for (int i = 0; i < HEART_POINTS; i++) {
            double t = (i * Math.PI * 2.0) / HEART_POINTS;
            double sinT = Math.sin(t);
            double hx = 16.0 * sinT * sinT * sinT;
            double hy = 13.0 * Math.cos(t) - 5.0 * Math.cos(2.0 * t) - 2.0 * Math.cos(3.0 * t) - Math.cos(4.0 * t);
            HEART_X[i] = (float) (hx / 14.5);
            HEART_Y[i] = (float) (hy / 14.5 + 0.14);
        }
    }

    private final ShaderProgram shader;

    private int vao;
    private int vbo;
    private int ebo;

    private ByteBuffer vertexBuffer;
    private long vertexAddress;
    private ByteBuffer indexBuffer;
    private long indexAddress;

    private int vertexCount = 0;
    private int indexCount = 0;

    private boolean isDrawing = false;
    private int currentPrimitive = GL11.GL_TRIANGLES;
    private BlendMode currentBlendMode = BlendMode.ALPHA;
    private float currentLineWidth = 1.0f;

    // Переиспользуемые векторы для расчёта billboard-шлейфов без аллокаций
    private final Vector3f tmpTangent = new Vector3f();
    private final Vector3f tmpViewDir = new Vector3f();
    private final Vector3f tmpSide = new Vector3f();
    private final Vector3f tmpLastSide = new Vector3f(1.0f, 0.0f, 0.0f);
    private final Matrix4f tmpInvVp = new Matrix4f();
    private final Vector4f tmpClipA = new Vector4f();
    private final Vector4f tmpClipB = new Vector4f();
    private final Vector4f tmpUnproj = new Vector4f();

    public RenderBatch3D() {
        this.shader = ShaderProgram.fromResources(
                "/assets/nebulavisuals/shaders/nebula_3d.vsh",
                "/assets/nebulavisuals/shaders/nebula_3d.fsh"
        );
        initBuffers();
    }

    private void initBuffers() {
        vertexBuffer = MemoryUtil.memAlloc(VERTEX_BUFFER_CAPACITY);
        vertexAddress = MemoryUtil.memAddress(vertexBuffer);
        indexBuffer = MemoryUtil.memAlloc(INDEX_BUFFER_CAPACITY);
        indexAddress = MemoryUtil.memAddress(indexBuffer);

        vao = GL30.glGenVertexArrays();
        vbo = GL15.glGenBuffers();
        ebo = GL15.glGenBuffers();

        GL30.glBindVertexArray(vao);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, VERTEX_BUFFER_CAPACITY, GL15.GL_STREAM_DRAW);

        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, INDEX_BUFFER_CAPACITY, GL15.GL_STREAM_DRAW);

        // location 0: vec3 aPos (camera-relative)
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, VERTEX_STRIDE, 0L);

        // location 1: vec4 aColor (normalized RGBA8)
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 4, GL11.GL_UNSIGNED_BYTE, true, VERTEX_STRIDE, 12L);

        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    public void begin() {
        begin(BlendMode.ALPHA);
    }

    public void begin(BlendMode defaultBlendMode) {
        if (isDrawing) {
            throw new IllegalStateException("RenderBatch3D.begin() called while already drawing");
        }
        this.isDrawing = true;
        this.vertexCount = 0;
        this.indexCount = 0;
        this.currentPrimitive = GL11.GL_TRIANGLES;
        this.currentBlendMode = defaultBlendMode != null ? defaultBlendMode : BlendMode.ALPHA;
        this.currentLineWidth = 1.0f;
    }

    private void prepareState(int primitive, BlendMode blendMode, float lineWidth, int requiredVertices, int requiredIndices) {
        BlendMode targetMode = blendMode != null ? blendMode : BlendMode.ALPHA;
        float clampedWidth = Math.max(1.0f, lineWidth);

        boolean stateChanged = primitive != currentPrimitive
                || targetMode != currentBlendMode
                || (primitive == GL11.GL_LINES && Math.abs(clampedWidth - currentLineWidth) > 0.01f);

        boolean capacityExceeded = (vertexCount + requiredVertices > MAX_VERTICES)
                || (indexCount + requiredIndices > MAX_INDICES);

        if (stateChanged || capacityExceeded) {
            flush();
            currentPrimitive = primitive;
            currentBlendMode = targetMode;
            currentLineWidth = clampedWidth;
        }
    }

    private int putRelativeVertex(float rx, float ry, float rz, int argb) {
        int idx = vertexCount++;
        long ptr = vertexAddress + (long) idx * VERTEX_STRIDE;
        MemoryUtil.memPutFloat(ptr, rx);
        MemoryUtil.memPutFloat(ptr + 4L, ry);
        MemoryUtil.memPutFloat(ptr + 8L, rz);
        MemoryUtil.memPutByte(ptr + 12L, (byte) ((argb >>> 16) & 0xFF));
        MemoryUtil.memPutByte(ptr + 13L, (byte) ((argb >>> 8) & 0xFF));
        MemoryUtil.memPutByte(ptr + 14L, (byte) (argb & 0xFF));
        MemoryUtil.memPutByte(ptr + 15L, (byte) ((argb >>> 24) & 0xFF));
        return idx;
    }

    private void putIndex(int vertexIdx) {
        MemoryUtil.memPutInt(indexAddress + (long) (indexCount++) * Integer.BYTES, vertexIdx);
    }

    private void putTriangle(int a, int b, int c) {
        putIndex(a);
        putIndex(b);
        putIndex(c);
    }

    /**
     * Строит ориентированный на камеру (billboard) 3D-квад из 2 треугольников вместо
     * {@code GL_LINES + glLineWidth}, который запрещён (> 1.0) в OpenGL 3.3+ Core Profile.
     */
    private void emitCameraQuadLine(float rx1, float ry1, float rz1,
                                    float rx2, float ry2, float rz2,
                                    int argb1, int argb2, float lineWidth) {
        float dx = rx2 - rx1;
        float dy = ry2 - ry1;
        float dz = rz2 - rz1;
        if (dx * dx + dy * dy + dz * dz < 1e-10f) {
            return;
        }

        // Середина отрезка в camera-relative координатах (камера находится в (0,0,0))
        float mx = (rx1 + rx2) * 0.5f;
        float my = (ry1 + ry2) * 0.5f;
        float mz = (rz1 + rz2) * 0.5f;

        // Перпендикуляр в плоскости экрана: cross(dir, viewPos)
        float sx = dy * mz - dz * my;
        float sy = dz * mx - dx * mz;
        float sz = dx * my - dy * mx;
        float sLenSq = sx * sx + sy * sy + sz * sz;
        if (sLenSq < 1e-8f) {
            sx = -dz;
            sy = 0.0f;
            sz = dx;
            sLenSq = sx * sx + sz * sz;
            if (sLenSq < 1e-8f) {
                sx = 1.0f;
                sy = 0.0f;
                sz = 0.0f;
                sLenSq = 1.0f;
            }
        }

        float invLen = 1.0f / (float) Math.sqrt(sLenSq);
        float dist = (float) Math.sqrt(mx * mx + my * my + mz * mz);
        float halfW = Math.max(0.0015f, dist * 0.0011f * Math.max(1.0f, lineWidth));
        float ox = sx * invLen * halfW;
        float oy = sy * invLen * halfW;
        float oz = sz * invLen * halfW;

        int v0 = putRelativeVertex(rx1 - ox, ry1 - oy, rz1 - oz, argb1);
        int v1 = putRelativeVertex(rx1 + ox, ry1 + oy, rz1 + oz, argb1);
        int v2 = putRelativeVertex(rx2 + ox, ry2 + oy, rz2 + oz, argb2);
        int v3 = putRelativeVertex(rx2 - ox, ry2 - oy, rz2 - oz, argb2);

        putTriangle(v0, v1, v2);
        putTriangle(v0, v2, v3);
    }

    // ==================== ЛИНИИ И БОКСЫ ====================

    public void drawLine(double x1, double y1, double z1,
                         double x2, double y2, double z2,
                         int argb) {
        drawLine(x1, y1, z1, x2, y2, z2, argb, argb, 1.0f, BlendMode.ALPHA);
    }

    public void drawLine(double x1, double y1, double z1,
                         double x2, double y2, double z2,
                         int argb1, int argb2, float lineWidth, BlendMode blendMode) {
        if (!isDrawing) return;
        prepareState(GL11.GL_TRIANGLES, blendMode, 1.0f, 4, 6);
        emitCameraQuadLine(
                Camera3D.relX(x1), Camera3D.relY(y1), Camera3D.relZ(z1),
                Camera3D.relX(x2), Camera3D.relY(y2), Camera3D.relZ(z2),
                argb1, argb2, lineWidth
        );
    }

    public void drawBoxOutline(double minX, double minY, double minZ,
                               double maxX, double maxY, double maxZ,
                               int argb, float lineWidth, BlendMode blendMode) {
        if (!isDrawing) return;
        prepareState(GL11.GL_TRIANGLES, blendMode, 1.0f, 48, 72);

        float x0 = Camera3D.relX(minX), y0 = Camera3D.relY(minY), z0 = Camera3D.relZ(minZ);
        float x1 = Camera3D.relX(maxX), y1 = Camera3D.relY(maxY), z1 = Camera3D.relZ(maxZ);

        // Низ
        emitCameraQuadLine(x0, y0, z0, x1, y0, z0, argb, argb, lineWidth);
        emitCameraQuadLine(x1, y0, z0, x1, y0, z1, argb, argb, lineWidth);
        emitCameraQuadLine(x1, y0, z1, x0, y0, z1, argb, argb, lineWidth);
        emitCameraQuadLine(x0, y0, z1, x0, y0, z0, argb, argb, lineWidth);
        // Верх
        emitCameraQuadLine(x0, y1, z0, x1, y1, z0, argb, argb, lineWidth);
        emitCameraQuadLine(x1, y1, z0, x1, y1, z1, argb, argb, lineWidth);
        emitCameraQuadLine(x1, y1, z1, x0, y1, z1, argb, argb, lineWidth);
        emitCameraQuadLine(x0, y1, z1, x0, y1, z0, argb, argb, lineWidth);
        // Вертикальные рёбра
        emitCameraQuadLine(x0, y0, z0, x0, y1, z0, argb, argb, lineWidth);
        emitCameraQuadLine(x1, y0, z0, x1, y1, z0, argb, argb, lineWidth);
        emitCameraQuadLine(x1, y0, z1, x1, y1, z1, argb, argb, lineWidth);
        emitCameraQuadLine(x0, y0, z1, x0, y1, z1, argb, argb, lineWidth);
    }

    /**
     * Добавляет отрезок в нативный {@code GL_LINES}-батч (один общий буфер без 3D-коробов и лишних draw call'ов).
     */
    public void drawNativeLine(double x1, double y1, double z1,
                               double x2, double y2, double z2,
                               int argb, float lineWidth, BlendMode blendMode) {
        drawNativeLine(x1, y1, z1, x2, y2, z2, argb, argb, lineWidth, blendMode);
    }

    public void drawNativeLine(double x1, double y1, double z1,
                               double x2, double y2, double z2,
                               int argb1, int argb2, float lineWidth, BlendMode blendMode) {
        if (!isDrawing) return;
        prepareState(GL11.GL_LINES, blendMode, lineWidth, 2, 2);

        int v0 = putRelativeVertex(Camera3D.relX(x1), Camera3D.relY(y1), Camera3D.relZ(z1), argb1);
        int v1 = putRelativeVertex(Camera3D.relX(x2), Camera3D.relY(y2), Camera3D.relZ(z2), argb2);
        putIndex(v0);
        putIndex(v1);
    }

    /**
     * Добавляет все 12 рёбер 3D-бокса в общий {@code GL_LINES}-буфер с использованием 8 общих вершин.
     */
    public void drawNativeBoxOutline(double minX, double minY, double minZ,
                                     double maxX, double maxY, double maxZ,
                                     int argb, float lineWidth, BlendMode blendMode) {
        if (!isDrawing) return;
        prepareState(GL11.GL_LINES, blendMode, lineWidth, 8, 24);

        float x0 = Camera3D.relX(minX), y0 = Camera3D.relY(minY), z0 = Camera3D.relZ(minZ);
        float x1 = Camera3D.relX(maxX), y1 = Camera3D.relY(maxY), z1 = Camera3D.relZ(maxZ);

        int v000 = putRelativeVertex(x0, y0, z0, argb);
        int v100 = putRelativeVertex(x1, y0, z0, argb);
        int v101 = putRelativeVertex(x1, y0, z1, argb);
        int v001 = putRelativeVertex(x0, y0, z1, argb);

        int v010 = putRelativeVertex(x0, y1, z0, argb);
        int v110 = putRelativeVertex(x1, y1, z0, argb);
        int v111 = putRelativeVertex(x1, y1, z1, argb);
        int v011 = putRelativeVertex(x0, y1, z1, argb);

        // Низ
        putIndex(v000); putIndex(v100);
        putIndex(v100); putIndex(v101);
        putIndex(v101); putIndex(v001);
        putIndex(v001); putIndex(v000);
        // Верх
        putIndex(v010); putIndex(v110);
        putIndex(v110); putIndex(v111);
        putIndex(v111); putIndex(v011);
        putIndex(v011); putIndex(v010);
        // Вертикальные рёбра
        putIndex(v000); putIndex(v010);
        putIndex(v100); putIndex(v110);
        putIndex(v101); putIndex(v111);
        putIndex(v001); putIndex(v011);
    }

    /**
     * Добавляет горизонтальное кольцо по замкнутому контуру {@code GL_LINES} (без толстых 3D-сегментов при зуме).
     */
    public void drawNativeCircleOutline(double cx, double cy, double cz,
                                        float radius, int argb, int segments,
                                        float lineWidth, BlendMode blendMode) {
        if (!isDrawing || radius <= 0.0f || segments < 3) return;
        prepareState(GL11.GL_LINES, blendMode, lineWidth, segments, segments * 2);

        float rx = Camera3D.relX(cx);
        float ry = Camera3D.relY(cy);
        float rz = Camera3D.relZ(cz);

        int base = vertexCount;
        for (int i = 0; i < segments; i++) {
            float angle = TWO_PI * i / segments;
            putRelativeVertex(
                    rx + (float) Math.cos(angle) * radius,
                    ry,
                    rz + (float) Math.sin(angle) * radius,
                    argb
            );
        }
        for (int i = 0; i < segments; i++) {
            putIndex(base + i);
            putIndex(base + ((i + 1) % segments));
        }
    }

    /**
     * Добавляет замкнутое кольцо {@code GL_LINES} в произвольной плоскости с ортонормальным базисом {@code (u, v)}.
     */
    public void drawNativeOrientedCircleOutline(double cx, double cy, double cz,
                                                double ux, double uy, double uz,
                                                double vx, double vy, double vz,
                                                float radius, int argb, int segments,
                                                float lineWidth, BlendMode blendMode) {
        if (!isDrawing || radius <= 0.0f || segments < 3) return;
        prepareState(GL11.GL_LINES, blendMode, lineWidth, segments, segments * 2);

        float rx = Camera3D.relX(cx);
        float ry = Camera3D.relY(cy);
        float rz = Camera3D.relZ(cz);

        int base = vertexCount;
        for (int i = 0; i < segments; i++) {
            double angle = (Math.PI * 2.0 * i) / segments;
            double cos = Math.cos(angle) * radius;
            double sin = Math.sin(angle) * radius;
            putRelativeVertex(
                    rx + (float) (ux * cos + vx * sin),
                    ry + (float) (uy * cos + vy * sin),
                    rz + (float) (uz * cos + vz * sin),
                    argb
            );
        }
        for (int i = 0; i < segments; i++) {
            putIndex(base + i);
            putIndex(base + ((i + 1) % segments));
        }
    }

    /**
     * Рисует непрерывную экранно-ориентированную полосу (screen-space quad strip) заданной толщины в пикселях.
     * Смещение в clip-space умножается на {@code clip.w} после деления на {@code screenW/H}, благодаря чему
     * видимая ширина в экранных пикселях остаётся строго постоянной при приближении и в {@code Zoom} в Core Profile.
     */
    public void drawScreenPolyline(double[] xs, double[] ys, double[] zs, int[] colors,
                                   int pointCount, float pixelWidth, int screenW, int screenH, BlendMode blendMode) {
        if (!isDrawing || pointCount < 2 || pixelWidth <= 0.0f) return;

        int segCount = pointCount - 1;
        prepareState(GL11.GL_TRIANGLES, blendMode, 1.0f, pointCount * 4, segCount * 6);

        Matrix4f vp = Camera3D.getViewProjMatrix();
        vp.invert(tmpInvVp);

        double camX = Camera3D.getCamX();
        double camY = Camera3D.getCamY();
        double camZ = Camera3D.getCamZ();

        float resW = Math.max(1.0f, screenW);
        float resH = Math.max(1.0f, screenH);
        float halfW = resW * 0.5f;
        float halfH = resH * 0.5f;
        float halfPx = pixelWidth * 0.5f;
        float ndcPerPxX = 2.0f / resW;
        float ndcPerPxY = 2.0f / resH;
        final float nearW = 0.05f;

        boolean hasPrevEdge = false;
        int prevLeftIdx = -1;
        int prevRightIdx = -1;
        double prevSegX = 0.0, prevSegY = 0.0, prevSegZ = 0.0;

        for (int i = 0; i < segCount; i++) {
            float rx0 = (float) (xs[i] - camX);
            float ry0 = (float) (ys[i] - camY);
            float rz0 = (float) (zs[i] - camZ);
            float rx1 = (float) (xs[i + 1] - camX);
            float ry1 = (float) (ys[i + 1] - camY);
            float rz1 = (float) (zs[i + 1] - camZ);

            tmpClipA.set(rx0, ry0, rz0, 1.0f);
            vp.transform(tmpClipA);
            tmpClipB.set(rx1, ry1, rz1, 1.0f);
            vp.transform(tmpClipB);

            if (tmpClipA.w < nearW && tmpClipB.w < nearW) {
                hasPrevEdge = false;
                continue;
            }
            if (tmpClipA.w < nearW) {
                float t = (nearW - tmpClipA.w) / (tmpClipB.w - tmpClipA.w);
                tmpClipA.lerp(tmpClipB, t);
                hasPrevEdge = false;
            } else if (tmpClipB.w < nearW) {
                float t = (nearW - tmpClipB.w) / (tmpClipA.w - tmpClipB.w);
                tmpClipB.lerp(tmpClipA, t);
            }

            float sx0 = (tmpClipA.x / tmpClipA.w) * halfW;
            float sy0 = (tmpClipA.y / tmpClipA.w) * halfH;
            float sx1 = (tmpClipB.x / tmpClipB.w) * halfW;
            float sy1 = (tmpClipB.y / tmpClipB.w) * halfH;

            float tx = sx1 - sx0;
            float ty = sy1 - sy0;
            float lenSq = tx * tx + ty * ty;
            if (lenSq < 1e-6f) {
                continue;
            }
            float invLen = 1.0f / (float) Math.sqrt(lenSq);
            float nxPx = -ty * invLen * halfPx;
            float nyPx = tx * invLen * halfPx;

            float dNdcX = nxPx * ndcPerPxX;
            float dNdcY = nyPx * ndcPerPxY;

            boolean contiguous = hasPrevEdge
                    && Math.abs(xs[i] - prevSegX) < 1e-6
                    && Math.abs(ys[i] - prevSegY) < 1e-6
                    && Math.abs(zs[i] - prevSegZ) < 1e-6;

            int vL0;
            int vR0;
            if (contiguous) {
                vL0 = prevLeftIdx;
                vR0 = prevRightIdx;
            } else {
                vL0 = emitClipOffsetVertex(tmpClipA, -dNdcX * tmpClipA.w, -dNdcY * tmpClipA.w, colors[i]);
                vR0 = emitClipOffsetVertex(tmpClipA, +dNdcX * tmpClipA.w, +dNdcY * tmpClipA.w, colors[i]);
            }

            int vL1 = emitClipOffsetVertex(tmpClipB, -dNdcX * tmpClipB.w, -dNdcY * tmpClipB.w, colors[i + 1]);
            int vR1 = emitClipOffsetVertex(tmpClipB, +dNdcX * tmpClipB.w, +dNdcY * tmpClipB.w, colors[i + 1]);

            putTriangle(vL0, vR0, vR1);
            putTriangle(vL0, vR1, vL1);

            prevLeftIdx = vL1;
            prevRightIdx = vR1;
            prevSegX = xs[i + 1];
            prevSegY = ys[i + 1];
            prevSegZ = zs[i + 1];
            hasPrevEdge = tmpClipB.w >= nearW + 0.001f;
        }
    }

    private int emitClipOffsetVertex(Vector4f clip, float dClipX, float dClipY, int argb) {
        tmpUnproj.set(clip.x + dClipX, clip.y + dClipY, clip.z, clip.w);
        tmpInvVp.transform(tmpUnproj);
        float invW = Math.abs(tmpUnproj.w) > 1e-6f ? (1.0f / tmpUnproj.w) : 1.0f;
        return putRelativeVertex(tmpUnproj.x * invW, tmpUnproj.y * invW, tmpUnproj.z * invW, argb);
    }

    /**
     * Рисует одиночный отрезок как экранно-ориентированный quad с постоянной пиксельной толщиной в Core Profile.
     */
    public void drawScreenLine(double x1, double y1, double z1,
                               double x2, double y2, double z2,
                               int argb1, int argb2,
                               float pixelWidth, int screenW, int screenH, BlendMode blendMode) {
        if (!isDrawing || pixelWidth <= 0.0f) return;
        prepareState(GL11.GL_TRIANGLES, blendMode, 1.0f, 4, 6);

        Matrix4f vp = Camera3D.getViewProjMatrix();
        vp.invert(tmpInvVp);

        double camX = Camera3D.getCamX();
        double camY = Camera3D.getCamY();
        double camZ = Camera3D.getCamZ();

        tmpClipA.set((float) (x1 - camX), (float) (y1 - camY), (float) (z1 - camZ), 1.0f);
        vp.transform(tmpClipA);
        tmpClipB.set((float) (x2 - camX), (float) (y2 - camY), (float) (z2 - camZ), 1.0f);
        vp.transform(tmpClipB);

        final float nearW = 0.05f;
        if (tmpClipA.w < nearW && tmpClipB.w < nearW) {
            return;
        }
        if (tmpClipA.w < nearW) {
            float t = (nearW - tmpClipA.w) / (tmpClipB.w - tmpClipA.w);
            tmpClipA.lerp(tmpClipB, t);
        } else if (tmpClipB.w < nearW) {
            float t = (nearW - tmpClipB.w) / (tmpClipA.w - tmpClipB.w);
            tmpClipB.lerp(tmpClipA, t);
        }

        float resW = Math.max(1.0f, screenW);
        float resH = Math.max(1.0f, screenH);
        float sx0 = (tmpClipA.x / tmpClipA.w) * (resW * 0.5f);
        float sy0 = (tmpClipA.y / tmpClipA.w) * (resH * 0.5f);
        float sx1 = (tmpClipB.x / tmpClipB.w) * (resW * 0.5f);
        float sy1 = (tmpClipB.y / tmpClipB.w) * (resH * 0.5f);

        float tx = sx1 - sx0;
        float ty = sy1 - sy0;
        float lenSq = tx * tx + ty * ty;
        if (lenSq < 1e-6f) {
            return;
        }
        float invLen = 1.0f / (float) Math.sqrt(lenSq);
        float halfPx = pixelWidth * 0.5f;
        float dNdcX = (-ty * invLen * halfPx) * (2.0f / resW);
        float dNdcY = (tx * invLen * halfPx) * (2.0f / resH);

        int v0 = emitClipOffsetVertex(tmpClipA, -dNdcX * tmpClipA.w, -dNdcY * tmpClipA.w, argb1);
        int v1 = emitClipOffsetVertex(tmpClipA, +dNdcX * tmpClipA.w, +dNdcY * tmpClipA.w, argb1);
        int v2 = emitClipOffsetVertex(tmpClipB, +dNdcX * tmpClipB.w, +dNdcY * tmpClipB.w, argb2);
        int v3 = emitClipOffsetVertex(tmpClipB, -dNdcX * tmpClipB.w, -dNdcY * tmpClipB.w, argb2);

        putTriangle(v0, v1, v2);
        putTriangle(v0, v2, v3);
    }

    /**
     * Рисует все 12 рёбер 3D-бокса экранно-ориентированными линиями постоянной пиксельной ширины.
     */
    public void drawScreenBoxOutline(double minX, double minY, double minZ,
                                     double maxX, double maxY, double maxZ,
                                     int argb, float pixelWidth, int screenW, int screenH, BlendMode blendMode) {
        if (!isDrawing || pixelWidth <= 0.0f) return;
        // Низ
        drawScreenLine(minX, minY, minZ, maxX, minY, minZ, argb, argb, pixelWidth, screenW, screenH, blendMode);
        drawScreenLine(maxX, minY, minZ, maxX, minY, maxZ, argb, argb, pixelWidth, screenW, screenH, blendMode);
        drawScreenLine(maxX, minY, maxZ, minX, minY, maxZ, argb, argb, pixelWidth, screenW, screenH, blendMode);
        drawScreenLine(minX, minY, maxZ, minX, minY, minZ, argb, argb, pixelWidth, screenW, screenH, blendMode);
        // Верх
        drawScreenLine(minX, maxY, minZ, maxX, maxY, minZ, argb, argb, pixelWidth, screenW, screenH, blendMode);
        drawScreenLine(maxX, maxY, minZ, maxX, maxY, maxZ, argb, argb, pixelWidth, screenW, screenH, blendMode);
        drawScreenLine(maxX, maxY, maxZ, minX, maxY, maxZ, argb, argb, pixelWidth, screenW, screenH, blendMode);
        drawScreenLine(minX, maxY, maxZ, minX, maxY, minZ, argb, argb, pixelWidth, screenW, screenH, blendMode);
        // Вертикальные рёбра
        drawScreenLine(minX, minY, minZ, minX, maxY, minZ, argb, argb, pixelWidth, screenW, screenH, blendMode);
        drawScreenLine(maxX, minY, minZ, maxX, maxY, minZ, argb, argb, pixelWidth, screenW, screenH, blendMode);
        drawScreenLine(maxX, minY, maxZ, maxX, maxY, maxZ, argb, argb, pixelWidth, screenW, screenH, blendMode);
        drawScreenLine(minX, minY, maxZ, minX, maxY, maxZ, argb, argb, pixelWidth, screenW, screenH, blendMode);
    }

    public void drawFilledBox(double minX, double minY, double minZ,
                              double maxX, double maxY, double maxZ,
                              int argb, BlendMode blendMode) {
        if (!isDrawing) return;
        prepareState(GL11.GL_TRIANGLES, blendMode, 1.0f, 8, 36);

        float x0 = Camera3D.relX(minX), y0 = Camera3D.relY(minY), z0 = Camera3D.relZ(minZ);
        float x1 = Camera3D.relX(maxX), y1 = Camera3D.relY(maxY), z1 = Camera3D.relZ(maxZ);

        int v000 = putRelativeVertex(x0, y0, z0, argb);
        int v100 = putRelativeVertex(x1, y0, z0, argb);
        int v101 = putRelativeVertex(x1, y0, z1, argb);
        int v001 = putRelativeVertex(x0, y0, z1, argb);

        int v010 = putRelativeVertex(x0, y1, z0, argb);
        int v110 = putRelativeVertex(x1, y1, z0, argb);
        int v111 = putRelativeVertex(x1, y1, z1, argb);
        int v011 = putRelativeVertex(x0, y1, z1, argb);

        // Низ (-Y)
        putTriangle(v000, v100, v101);
        putTriangle(v000, v101, v001);
        // Верх (+Y)
        putTriangle(v010, v011, v111);
        putTriangle(v010, v111, v110);
        // Север (-Z)
        putTriangle(v000, v010, v110);
        putTriangle(v000, v110, v100);
        // Юг (+Z)
        putTriangle(v001, v101, v111);
        putTriangle(v001, v111, v011);
        // Запад (-X)
        putTriangle(v000, v001, v011);
        putTriangle(v000, v011, v010);
        // Восток (+X)
        putTriangle(v100, v110, v111);
        putTriangle(v100, v111, v101);
    }

    // ==================== КОЛЬЦА И ДИСКИ (JumpCircles, TargetESP) ====================

    public void drawCircleOutline(double cx, double cy, double cz,
                                  float radius, int argb, int segments,
                                  float lineWidth, BlendMode blendMode) {
        drawCircleOutline(cx, cy, cz, radius, argb, segments, 0.0f, lineWidth, blendMode);
    }

    public void drawCircleOutline(double cx, double cy, double cz,
                                  float radius, int argb, int segments,
                                  float rotationRad, float lineWidth, BlendMode blendMode) {
        if (!isDrawing || radius <= 0.0f || segments < 3) return;
        drawArcOutlineInternal(cx, cy, cz, radius, rotationRad, TWO_PI, argb, segments, lineWidth, blendMode);
    }

    /**
     * Плоское 3D-кольцо (или правильный многоугольник) с плавным градиентом от внутреннего радиуса к внешнему.
     */
    public void drawRing(double cx, double cy, double cz,
                         float innerRadius, float outerRadius,
                         int innerArgb, int outerArgb,
                         int segments, BlendMode blendMode) {
        drawRing(cx, cy, cz, innerRadius, outerRadius, innerArgb, outerArgb, segments, 0.0f, blendMode);
    }

    public void drawRing(double cx, double cy, double cz,
                         float innerRadius, float outerRadius,
                         int innerArgb, int outerArgb,
                         int segments, float rotationRad, BlendMode blendMode) {
        if (!isDrawing || segments < 3 || outerRadius <= innerRadius) return;

        int verts = (segments + 1) * 2;
        int inds = segments * 6;
        prepareState(GL11.GL_TRIANGLES, blendMode, 1.0f, verts, inds);

        float rx = Camera3D.relX(cx);
        float ry = Camera3D.relY(cy);
        float rz = Camera3D.relZ(cz);

        int base = vertexCount;
        for (int i = 0; i <= segments; i++) {
            float angle = rotationRad + TWO_PI * i / segments;
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            putRelativeVertex(rx + cos * innerRadius, ry, rz + sin * innerRadius, innerArgb);
            putRelativeVertex(rx + cos * outerRadius, ry, rz + sin * outerRadius, outerArgb);
        }

        for (int i = 0; i < segments; i++) {
            int i0 = base + i * 2;
            int o0 = i0 + 1;
            int i1 = i0 + 2;
            int o1 = i0 + 3;
            putTriangle(i0, o0, o1);
            putTriangle(i0, o1, i1);
        }
    }

    /**
     * Мягкое неоновое 3D-кольцо (или многоугольник) с двойным затуханием.
     */
    public void drawHaloRing(double cx, double cy, double cz,
                             float radius, float halfWidth,
                             int coreArgb, int edgeArgb,
                             int segments, BlendMode blendMode) {
        drawHaloRing(cx, cy, cz, radius, halfWidth, coreArgb, edgeArgb, segments, 0.0f, blendMode);
    }

    public void drawHaloRing(double cx, double cy, double cz,
                             float radius, float halfWidth,
                             int coreArgb, int edgeArgb,
                             int segments, float rotationRad, BlendMode blendMode) {
        if (!isDrawing || segments < 3 || radius <= 0.0f || halfWidth <= 0.0f) return;

        float innerR = Math.max(0.0f, radius - halfWidth);
        float outerR = radius + halfWidth;

        int verts = (segments + 1) * 3;
        int inds = segments * 12;
        prepareState(GL11.GL_TRIANGLES, blendMode, 1.0f, verts, inds);

        float rx = Camera3D.relX(cx);
        float ry = Camera3D.relY(cy);
        float rz = Camera3D.relZ(cz);

        int base = vertexCount;
        for (int i = 0; i <= segments; i++) {
            float angle = rotationRad + TWO_PI * i / segments;
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            putRelativeVertex(rx + cos * innerR, ry, rz + sin * innerR, edgeArgb);
            putRelativeVertex(rx + cos * radius, ry, rz + sin * radius, coreArgb);
            putRelativeVertex(rx + cos * outerR, ry, rz + sin * outerR, edgeArgb);
        }

        for (int i = 0; i < segments; i++) {
            int col0 = base + i * 3;
            int col1 = col0 + 3;
            // Внутренний градиентный пояс
            putTriangle(col0, col0 + 1, col1 + 1);
            putTriangle(col0, col1 + 1, col1);
            // Внешний градиентный пояс
            putTriangle(col0 + 1, col0 + 2, col1 + 2);
            putTriangle(col0 + 1, col1 + 2, col1 + 1);
        }
    }

    /**
     * Вертикальный цилиндрический градиентный шлейф от {@code cy} до {@code cy + glowHeight}.
     */
    public void drawVerticalGlowRing(double cx, double cy, double cz,
                                     float radius, float glowHeight,
                                     int baseArgb, int edgeArgb,
                                     int segments, BlendMode blendMode) {
        if (!isDrawing || segments < 3 || radius <= 0.0f || Math.abs(glowHeight) < 1e-4f) return;

        int verts = (segments + 1) * 2;
        int inds = segments * 6;
        prepareState(GL11.GL_TRIANGLES, blendMode, 1.0f, verts, inds);

        float rx = Camera3D.relX(cx);
        float ry = Camera3D.relY(cy);
        float rz = Camera3D.relZ(cz);
        float ryEnd = ry + glowHeight;

        int base = vertexCount;
        for (int i = 0; i <= segments; i++) {
            float angle = TWO_PI * i / segments;
            float px = rx + (float) Math.cos(angle) * radius;
            float pz = rz + (float) Math.sin(angle) * radius;
            putRelativeVertex(px, ry, pz, baseArgb);
            putRelativeVertex(px, ryEnd, pz, edgeArgb);
        }

        for (int i = 0; i < segments; i++) {
            int b0 = base + i * 2;
            int t0 = b0 + 1;
            int b1 = b0 + 2;
            int t1 = b0 + 3;
            putTriangle(b0, t0, t1);
            putTriangle(b0, t1, b1);
        }
    }

    /**
     * Дуговой контур 3D-кольца (используется для Halo и Segmented JumpCircles).
     */
    public void drawArcOutline(double cx, double cy, double cz,
                               float radius, float startAngle, float sweepAngle,
                               int argb, int segments, float lineWidth, BlendMode blendMode) {
        if (!isDrawing || radius <= 0.0f || segments < 1 || Math.abs(sweepAngle) < 1e-4f) return;
        drawArcOutlineInternal(cx, cy, cz, radius, startAngle, sweepAngle, argb, segments, lineWidth, blendMode);
    }

    private void drawArcOutlineInternal(double cx, double cy, double cz,
                                        float radius, float startAngle, float sweepAngle,
                                        int argb, int segments, float lineWidth, BlendMode blendMode) {
        prepareState(GL11.GL_TRIANGLES, blendMode, 1.0f, segments * 4, segments * 6);

        float rx = Camera3D.relX(cx);
        float ry = Camera3D.relY(cy);
        float rz = Camera3D.relZ(cz);

        float prevX = rx + (float) Math.cos(startAngle) * radius;
        float prevZ = rz + (float) Math.sin(startAngle) * radius;
        for (int i = 1; i <= segments; i++) {
            float angle = startAngle + sweepAngle * (i / (float) segments);
            float currX = rx + (float) Math.cos(angle) * radius;
            float currZ = rz + (float) Math.sin(angle) * radius;
            emitCameraQuadLine(prevX, ry, prevZ, currX, ry, currZ, argb, argb, lineWidth);
            prevX = currX;
            prevZ = currZ;
        }
    }

    /**
     * Заполненная градиентная дуга 3D-кольца.
     */
    public void drawArcRing(double cx, double cy, double cz,
                            float innerRadius, float outerRadius,
                            float startAngle, float sweepAngle,
                            int innerArgb, int outerArgb,
                            int segments, BlendMode blendMode) {
        if (!isDrawing || segments < 1 || outerRadius <= innerRadius || Math.abs(sweepAngle) < 1e-4f) return;

        int verts = (segments + 1) * 2;
        int inds = segments * 6;
        prepareState(GL11.GL_TRIANGLES, blendMode, 1.0f, verts, inds);

        float rx = Camera3D.relX(cx);
        float ry = Camera3D.relY(cy);
        float rz = Camera3D.relZ(cz);

        int base = vertexCount;
        for (int i = 0; i <= segments; i++) {
            float angle = startAngle + sweepAngle * (i / (float) segments);
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            putRelativeVertex(rx + cos * innerRadius, ry, rz + sin * innerRadius, innerArgb);
            putRelativeVertex(rx + cos * outerRadius, ry, rz + sin * outerRadius, outerArgb);
        }

        for (int i = 0; i < segments; i++) {
            int i0 = base + i * 2;
            int o0 = i0 + 1;
            int i1 = i0 + 2;
            int o1 = i0 + 3;
            putTriangle(i0, o0, o1);
            putTriangle(i0, o1, i1);
        }
    }

    /**
     * Заполненный 3D-диск с радиальным градиентом от центра к краю.
     */
    public void drawDisc(double cx, double cy, double cz,
                         float radius, int centerArgb, int edgeArgb,
                         int segments, BlendMode blendMode) {
        if (!isDrawing || segments < 3 || radius <= 0.0f) return;

        int verts = 1 + (segments + 1);
        int inds = segments * 3;
        prepareState(GL11.GL_TRIANGLES, blendMode, 1.0f, verts, inds);

        float rx = Camera3D.relX(cx);
        float ry = Camera3D.relY(cy);
        float rz = Camera3D.relZ(cz);

        int centerIdx = putRelativeVertex(rx, ry, rz, centerArgb);
        int ringBase = vertexCount;
        for (int i = 0; i <= segments; i++) {
            float angle = TWO_PI * i / segments;
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            putRelativeVertex(rx + cos * radius, ry, rz + sin * radius, edgeArgb);
        }

        for (int i = 0; i < segments; i++) {
            putTriangle(centerIdx, ringBase + i, ringBase + i + 1);
        }
    }

    /**
     * Полупрозрачный 3D-конус (используется для {@code ChinaHat}): вершина {@code apex} -> нижний ободок {@code baseY = apexY - height}.
     */
    public void drawCone(double apexX, double apexY, double apexZ,
                         float radius, float height,
                         int apexArgb, int rimArgb,
                         int segments, BlendMode blendMode) {
        if (!isDrawing || segments < 3 || radius <= 0.0f) return;

        int verts = 1 + (segments + 1);
        int inds = segments * 3;
        prepareState(GL11.GL_TRIANGLES, blendMode, 1.0f, verts, inds);

        float rx = Camera3D.relX(apexX);
        float ry = Camera3D.relY(apexY);
        float rz = Camera3D.relZ(apexZ);
        float baseRy = ry - height;

        int apexIdx = putRelativeVertex(rx, ry, rz, apexArgb);
        int ringBase = vertexCount;
        for (int i = 0; i <= segments; i++) {
            float angle = TWO_PI * i / segments;
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            putRelativeVertex(rx + cos * radius, baseRy, rz + sin * radius, rimArgb);
        }

        for (int i = 0; i < segments; i++) {
            putTriangle(apexIdx, ringBase + i, ringBase + i + 1);
        }
    }

    // ==================== ЛЕНТОЧНЫЕ ШЛЕЙФЫ (Trails) ====================

    /**
     * Рисует непрерывную 3D-ленту (billboard ribbon), ориентированную к камере,
     * с плавным затуханием ширины и цвета вдоль массива точек.
     */
    public void drawTrail(double[] xs, double[] ys, double[] zs,
                          int[] argbColors, float[] widths,
                          int pointCount, BlendMode blendMode) {
        if (!isDrawing || pointCount < 2) return;
        if (xs == null || ys == null || zs == null || argbColors == null || widths == null) return;
        if (xs.length < pointCount || ys.length < pointCount || zs.length < pointCount
                || argbColors.length < pointCount || widths.length < pointCount) {
            return;
        }

        int verts = pointCount * 2;
        int inds = (pointCount - 1) * 6;
        prepareState(GL11.GL_TRIANGLES, blendMode, 1.0f, verts, inds);

        double camX = Camera3D.getCamX();
        double camY = Camera3D.getCamY();
        double camZ = Camera3D.getCamZ();

        int base = vertexCount;
        tmpLastSide.set(1.0f, 0.0f, 0.0f);

        for (int i = 0; i < pointCount; i++) {
            float rx = (float) (xs[i] - camX);
            float ry = (float) (ys[i] - camY);
            float rz = (float) (zs[i] - camZ);

            int prev = Math.max(0, i - 1);
            int next = Math.min(pointCount - 1, i + 1);
            tmpTangent.set(
                    (float) (xs[next] - xs[prev]),
                    (float) (ys[next] - ys[prev]),
                    (float) (zs[next] - zs[prev])
            );

            // В camera-relative пространстве камера находится в (0,0,0)
            tmpViewDir.set(-rx, -ry, -rz);
            if (tmpTangent.lengthSquared() > 1e-8f && tmpViewDir.lengthSquared() > 1e-8f) {
                tmpSide.set(tmpTangent).cross(tmpViewDir);
                if (tmpSide.lengthSquared() > 1e-8f) {
                    tmpSide.normalize();
                    tmpLastSide.set(tmpSide);
                } else {
                    tmpSide.set(tmpLastSide);
                }
            } else {
                tmpSide.set(tmpLastSide);
            }

            float halfWidth = Math.max(0.0f, widths[i]) * 0.5f;
            float ox = tmpSide.x * halfWidth;
            float oy = tmpSide.y * halfWidth;
            float oz = tmpSide.z * halfWidth;

            int c = argbColors[i];
            putRelativeVertex(rx - ox, ry - oy, rz - oz, c);
            putRelativeVertex(rx + ox, ry + oy, rz + oz, c);
        }

        for (int i = 0; i < pointCount - 1; i++) {
            int l0 = base + i * 2;
            int r0 = l0 + 1;
            int l1 = l0 + 2;
            int r1 = l0 + 3;
            putTriangle(l0, r0, r1);
            putTriangle(l0, r1, l1);
        }
    }

    /**
     * Рисует вертикальную двустороннюю 3D-ленту (используется для {@code Trails})
     * с индивидуальной высотой и вертикальным градиентом прозрачности/цвета в каждой точке.
     */
    public void drawVerticalRibbon(double[] xs, double[] ys, double[] zs,
                                   int[] bottomColors, int[] topColors,
                                   float[] heights, int pointCount, BlendMode blendMode) {
        if (!isDrawing || pointCount < 2) return;
        if (xs == null || ys == null || zs == null || bottomColors == null || topColors == null || heights == null) return;
        if (xs.length < pointCount || ys.length < pointCount || zs.length < pointCount
                || bottomColors.length < pointCount || topColors.length < pointCount || heights.length < pointCount) {
            return;
        }

        int verts = pointCount * 2;
        int inds = (pointCount - 1) * 6;
        prepareState(GL11.GL_TRIANGLES, blendMode, 1.0f, verts, inds);

        double camX = Camera3D.getCamX();
        double camY = Camera3D.getCamY();
        double camZ = Camera3D.getCamZ();

        int base = vertexCount;
        for (int i = 0; i < pointCount; i++) {
            float rx = (float) (xs[i] - camX);
            float ry = (float) (ys[i] - camY);
            float rz = (float) (zs[i] - camZ);
            float h = Math.max(0.0f, heights[i]);

            putRelativeVertex(rx, ry, rz, bottomColors[i]);
            putRelativeVertex(rx, ry + h, rz, topColors[i]);
        }

        for (int i = 0; i < pointCount - 1; i++) {
            int b0 = base + i * 2;
            int t0 = b0 + 1;
            int b1 = b0 + 2;
            int t1 = b0 + 3;
            putTriangle(b0, t0, t1);
            putTriangle(b0, t1, b1);
        }
    }

    /**
     * Рисует плоскую горизонтальную 3D-дорожку с яркой центральной линией и мягким затуханием к боковым краям.
     */
    public void drawHorizontalRibbon(double[] xs, double[] ys, double[] zs,
                                     int[] centerColors, int[] edgeColors,
                                     float[] halfWidths, int pointCount, BlendMode blendMode) {
        if (!isDrawing || pointCount < 2) return;
        if (xs == null || ys == null || zs == null || centerColors == null || edgeColors == null || halfWidths == null) return;
        if (xs.length < pointCount || ys.length < pointCount || zs.length < pointCount
                || centerColors.length < pointCount || edgeColors.length < pointCount || halfWidths.length < pointCount) {
            return;
        }

        int verts = pointCount * 3;
        int inds = (pointCount - 1) * 12;
        prepareState(GL11.GL_TRIANGLES, blendMode, 1.0f, verts, inds);

        double camX = Camera3D.getCamX();
        double camY = Camera3D.getCamY();
        double camZ = Camera3D.getCamZ();

        int base = vertexCount;
        float lastSideX = 1.0f;
        float lastSideZ = 0.0f;

        for (int i = 0; i < pointCount; i++) {
            float rx = (float) (xs[i] - camX);
            float ry = (float) (ys[i] - camY);
            float rz = (float) (zs[i] - camZ);

            int prev = Math.max(0, i - 1);
            int next = Math.min(pointCount - 1, i + 1);
            float tx = (float) (xs[next] - xs[prev]);
            float tz = (float) (zs[next] - zs[prev]);
            float lenSq = tx * tx + tz * tz;

            float sideX;
            float sideZ;
            if (lenSq > 1e-8f) {
                float invLen = 1.0f / (float) Math.sqrt(lenSq);
                sideX = -tz * invLen;
                sideZ = tx * invLen;
                lastSideX = sideX;
                lastSideZ = sideZ;
            } else {
                sideX = lastSideX;
                sideZ = lastSideZ;
            }

            float hw = Math.max(0.0f, halfWidths[i]);
            float ox = sideX * hw;
            float oz = sideZ * hw;

            int edge = edgeColors[i];
            int center = centerColors[i];
            putRelativeVertex(rx - ox, ry, rz - oz, edge);
            putRelativeVertex(rx, ry, rz, center);
            putRelativeVertex(rx + ox, ry, rz + oz, edge);
        }

        for (int i = 0; i < pointCount - 1; i++) {
            int col0 = base + i * 3;
            int col1 = col0 + 3;
            // Левая половина (край -> центр)
            putTriangle(col0, col0 + 1, col1 + 1);
            putTriangle(col0, col1 + 1, col1);
            // Правая половина (центр -> край)
            putTriangle(col0 + 1, col0 + 2, col1 + 2);
            putTriangle(col0 + 1, col1 + 2, col1 + 1);
        }
    }

    // ==================== 3D-ЧАСТИЦЫ РАЗНЫХ ФОРМ ====================

    public void drawBillboardStar(double cx, double cy, double cz,
                                  float radius, float rotRad,
                                  float rightX, float rightY, float rightZ,
                                  float upX, float upY, float upZ,
                                  int coreArgb, int edgeArgb, BlendMode blendMode) {
        drawBillboardShape(cx, cy, cz, radius, rotRad, rightX, rightY, rightZ, upX, upY, upZ,
                STAR_X, STAR_Y, STAR_POINTS, coreArgb, edgeArgb, blendMode);
    }

    public void drawBillboardHeart(double cx, double cy, double cz,
                                   float radius, float rotRad,
                                   float rightX, float rightY, float rightZ,
                                   float upX, float upY, float upZ,
                                   int coreArgb, int edgeArgb, BlendMode blendMode) {
        drawBillboardShape(cx, cy, cz, radius, rotRad, rightX, rightY, rightZ, upX, upY, upZ,
                HEART_X, HEART_Y, HEART_POINTS, coreArgb, edgeArgb, blendMode);
    }

    private void drawBillboardShape(double cx, double cy, double cz,
                                    float radius, float rotRad,
                                    float rightX, float rightY, float rightZ,
                                    float upX, float upY, float upZ,
                                    float[] shapeX, float[] shapeY, int count,
                                    int coreArgb, int edgeArgb, BlendMode blendMode) {
        if (!isDrawing || radius <= 0.0f) return;
        prepareState(GL11.GL_TRIANGLES, blendMode, 1.0f, count + 1, count * 3);

        float rx = Camera3D.relX(cx);
        float ry = Camera3D.relY(cy);
        float rz = Camera3D.relZ(cz);

        float cos = (float) Math.cos(rotRad);
        float sin = (float) Math.sin(rotRad);
        float ux = (rightX * cos + upX * sin) * radius;
        float uy = (rightY * cos + upY * sin) * radius;
        float uz = (rightZ * cos + upZ * sin) * radius;
        float vx = (-rightX * sin + upX * cos) * radius;
        float vy = (-rightY * sin + upY * cos) * radius;
        float vz = (-rightZ * sin + upZ * cos) * radius;

        int vCenter = putRelativeVertex(rx, ry, rz, coreArgb);
        int base = vertexCount;
        for (int i = 0; i < count; i++) {
            float px = shapeX[i];
            float py = shapeY[i];
            putRelativeVertex(
                    rx + px * ux + py * vx,
                    ry + px * uy + py * vy,
                    rz + px * uz + py * vz,
                    edgeArgb
            );
        }
        for (int i = 0; i < count; i++) {
            putTriangle(vCenter, base + i, base + ((i + 1) % count));
        }
    }

    public void drawBillboardCross(double cx, double cy, double cz,
                                   float radius, float rotRad,
                                   float rightX, float rightY, float rightZ,
                                   float upX, float upY, float upZ,
                                   int coreArgb, int edgeArgb, BlendMode blendMode) {
        if (!isDrawing || radius <= 0.0f) return;
        prepareState(GL11.GL_TRIANGLES, blendMode, 1.0f, 12, 18);

        float rx = Camera3D.relX(cx);
        float ry = Camera3D.relY(cy);
        float rz = Camera3D.relZ(cz);

        float cos = (float) Math.cos(rotRad);
        float sin = (float) Math.sin(rotRad);
        float ux = rightX * cos + upX * sin;
        float uy = rightY * cos + upY * sin;
        float uz = rightZ * cos + upZ * sin;
        float vx = -rightX * sin + upX * cos;
        float vy = -rightY * sin + upY * cos;
        float vz = -rightZ * sin + upZ * cos;

        float len = radius * 1.18f;
        float thick = Math.max(0.006f, radius * 0.24f);
        float coreR = thick * 1.25f;

        emitBillboardQuad(rx, ry, rz, ux, uy, uz, vx, vy, vz, len, thick, edgeArgb);
        emitBillboardQuad(rx, ry, rz, ux, uy, uz, vx, vy, vz, thick, len, edgeArgb);
        emitBillboardQuad(rx, ry, rz, ux, uy, uz, vx, vy, vz, coreR, coreR, coreArgb);
    }

    private void emitBillboardQuad(float rx, float ry, float rz,
                                   float ux, float uy, float uz,
                                   float vx, float vy, float vz,
                                   float hw, float hh, int argb) {
        float hx = ux * hw, hy = uy * hw, hz = uz * hw;
        float vxS = vx * hh, vyS = vy * hh, vzS = vz * hh;
        int v0 = putRelativeVertex(rx - hx - vxS, ry - hy - vyS, rz - hz - vzS, argb);
        int v1 = putRelativeVertex(rx + hx - vxS, ry + hy - vyS, rz + hz - vzS, argb);
        int v2 = putRelativeVertex(rx + hx + vxS, ry + hy + vyS, rz + hz + vzS, argb);
        int v3 = putRelativeVertex(rx - hx + vxS, ry - hy + vyS, rz - hz + vzS, argb);
        putTriangle(v0, v1, v2);
        putTriangle(v0, v2, v3);
    }

    public void drawRotatedCubeWithEdges(double cx, double cy, double cz,
                                         float halfSize, float yawRad, float pitchRad,
                                         int fillArgb, int edgeArgb, float edgeWidth, BlendMode blendMode) {
        if (!isDrawing || halfSize <= 0.0f) return;
        prepareState(GL11.GL_TRIANGLES, blendMode, 1.0f, 56, 108);

        float rx = Camera3D.relX(cx);
        float ry = Camera3D.relY(cy);
        float rz = Camera3D.relZ(cz);

        float cyaw = (float) Math.cos(yawRad), syaw = (float) Math.sin(yawRad);
        float cp = (float) Math.cos(pitchRad), sp = (float) Math.sin(pitchRad);

        float axX = cyaw * halfSize, axY = 0.0f, axZ = -syaw * halfSize;
        float ayX = syaw * sp * halfSize, ayY = cp * halfSize, ayZ = cyaw * sp * halfSize;
        float azX = syaw * cp * halfSize, azY = -sp * halfSize, azZ = cyaw * cp * halfSize;

        float x000 = rx - axX - ayX - azX, y000 = ry - axY - ayY - azY, z000 = rz - axZ - ayZ - azZ;
        float x100 = rx + axX - ayX - azX, y100 = ry + axY - ayY - azY, z100 = rz + axZ - ayZ - azZ;
        float x110 = rx + axX + ayX - azX, y110 = ry + axY + ayY - azY, z110 = rz + axZ + ayZ - azZ;
        float x010 = rx - axX + ayX - azX, y010 = ry - axY + ayY - azY, z010 = rz - axZ + ayZ - azZ;
        float x001 = rx - axX - ayX + azX, y001 = ry - axY - ayY + azY, z001 = rz - axZ - ayZ + azZ;
        float x101 = rx + axX - ayX + azX, y101 = ry + axY - ayY + azY, z101 = rz + axZ - ayZ + azZ;
        float x111 = rx + axX + ayX + azX, y111 = ry + axY + ayY + azY, z111 = rz + axZ + ayZ + azZ;
        float x011 = rx - axX + ayX + azX, y011 = ry - axY + ayY + azY, z011 = rz - axZ + ayZ + azZ;

        int v000 = putRelativeVertex(x000, y000, z000, fillArgb);
        int v100 = putRelativeVertex(x100, y100, z100, fillArgb);
        int v110 = putRelativeVertex(x110, y110, z110, fillArgb);
        int v010 = putRelativeVertex(x010, y010, z010, fillArgb);
        int v001 = putRelativeVertex(x001, y001, z001, fillArgb);
        int v101 = putRelativeVertex(x101, y101, z101, fillArgb);
        int v111 = putRelativeVertex(x111, y111, z111, fillArgb);
        int v011 = putRelativeVertex(x011, y011, z011, fillArgb);

        // 6 полупрозрачных граней куба
        putTriangle(v000, v100, v110); putTriangle(v000, v110, v010);
        putTriangle(v001, v111, v101); putTriangle(v001, v011, v111);
        putTriangle(v000, v010, v011); putTriangle(v000, v011, v001);
        putTriangle(v100, v111, v110); putTriangle(v100, v101, v111);
        putTriangle(v010, v110, v111); putTriangle(v010, v111, v011);
        putTriangle(v000, v101, v100); putTriangle(v000, v001, v101);

        // Подсветка всех 12 рёбер
        emitCameraQuadLine(x000, y000, z000, x100, y100, z100, edgeArgb, edgeArgb, edgeWidth);
        emitCameraQuadLine(x100, y100, z100, x110, y110, z110, edgeArgb, edgeArgb, edgeWidth);
        emitCameraQuadLine(x110, y110, z110, x010, y010, z010, edgeArgb, edgeArgb, edgeWidth);
        emitCameraQuadLine(x010, y010, z010, x000, y000, z000, edgeArgb, edgeArgb, edgeWidth);

        emitCameraQuadLine(x001, y001, z001, x101, y101, z101, edgeArgb, edgeArgb, edgeWidth);
        emitCameraQuadLine(x101, y101, z101, x111, y111, z111, edgeArgb, edgeArgb, edgeWidth);
        emitCameraQuadLine(x111, y111, z111, x011, y011, z011, edgeArgb, edgeArgb, edgeWidth);
        emitCameraQuadLine(x011, y011, z011, x001, y001, z001, edgeArgb, edgeArgb, edgeWidth);

        emitCameraQuadLine(x000, y000, z000, x001, y001, z001, edgeArgb, edgeArgb, edgeWidth);
        emitCameraQuadLine(x100, y100, z100, x101, y101, z101, edgeArgb, edgeArgb, edgeWidth);
        emitCameraQuadLine(x110, y110, z110, x111, y111, z111, edgeArgb, edgeArgb, edgeWidth);
        emitCameraQuadLine(x010, y010, z010, x011, y011, z011, edgeArgb, edgeArgb, edgeWidth);
    }

    public void drawVelocitySpark(double cx, double cy, double cz,
                                  float dirX, float dirY, float dirZ,
                                  float halfLen, float halfWidth,
                                  int coreArgb, int headArgb, int tailArgb, BlendMode blendMode) {
        if (!isDrawing || halfLen <= 0.0f) return;

        float rx = Camera3D.relX(cx);
        float ry = Camera3D.relY(cy);
        float rz = Camera3D.relZ(cz);

        // Перпендикуляр к вектору скорости и лучу взгляда камеры
        float sx = dirY * rz - dirZ * ry;
        float sy = dirZ * rx - dirX * rz;
        float sz = dirX * ry - dirY * rx;
        float sLenSq = sx * sx + sy * sy + sz * sz;
        if (sLenSq < 1e-8f) {
            sx = -dirZ;
            sy = 0.0f;
            sz = dirX;
            sLenSq = sx * sx + sz * sz;
            if (sLenSq < 1e-8f) {
                sx = 1.0f;
                sy = 0.0f;
                sz = 0.0f;
                sLenSq = 1.0f;
            }
        }
        float invS = halfWidth / (float) Math.sqrt(sLenSq);
        sx *= invS;
        sy *= invS;
        sz *= invS;

        float tailLen = halfLen * 1.4f;
        prepareState(GL11.GL_TRIANGLES, blendMode, 1.0f, 5, 12);

        int vCenter = putRelativeVertex(rx, ry, rz, coreArgb);
        int vHead = putRelativeVertex(rx + dirX * halfLen, ry + dirY * halfLen, rz + dirZ * halfLen, headArgb);
        int vRight = putRelativeVertex(rx + sx, ry + sy, rz + sz, headArgb);
        int vTail = putRelativeVertex(rx - dirX * tailLen, ry - dirY * tailLen, rz - dirZ * tailLen, tailArgb);
        int vLeft = putRelativeVertex(rx - sx, ry - sy, rz - sz, headArgb);

        putTriangle(vCenter, vHead, vRight);
        putTriangle(vCenter, vRight, vTail);
        putTriangle(vCenter, vTail, vLeft);
        putTriangle(vCenter, vLeft, vHead);
    }

    public void flush() {
        if (!isDrawing || vertexCount <= 0 || indexCount <= 0) return;

        GL11.glEnable(GL11.GL_BLEND);
        currentBlendMode.apply();
        GL11.glDisable(GL11.GL_CULL_FACE);

        shader.bind();
        shader.setUniformMatrix4fv("uViewProj", Camera3D.getViewProjBuffer());

        GL30.glBindVertexArray(vao);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, VERTEX_BUFFER_CAPACITY, GL15.GL_STREAM_DRAW);
        vertexBuffer.limit(vertexCount * VERTEX_STRIDE);
        vertexBuffer.position(0);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, vertexBuffer);

        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, INDEX_BUFFER_CAPACITY, GL15.GL_STREAM_DRAW);
        indexBuffer.limit(indexCount * Integer.BYTES);
        indexBuffer.position(0);
        GL15.glBufferSubData(GL15.GL_ELEMENT_ARRAY_BUFFER, 0L, indexBuffer);

        GL11.glDrawElements(currentPrimitive, indexCount, GL11.GL_UNSIGNED_INT, 0L);

        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        vertexCount = 0;
        indexCount = 0;
    }

    public void end() {
        if (!isDrawing) return;
        flush();
        shader.unbind();
        isDrawing = false;
    }

    @Override
    public void close() {
        if (vertexBuffer != null) {
            MemoryUtil.memFree(vertexBuffer);
            vertexBuffer = null;
            vertexAddress = 0L;
        }
        if (indexBuffer != null) {
            MemoryUtil.memFree(indexBuffer);
            indexBuffer = null;
            indexAddress = 0L;
        }
        if (vao != 0) GL30.glDeleteVertexArrays(vao);
        if (vbo != 0) GL15.glDeleteBuffers(vbo);
        if (ebo != 0) GL15.glDeleteBuffers(ebo);
        vao = 0;
        vbo = 0;
        ebo = 0;
        shader.delete();
    }
}
