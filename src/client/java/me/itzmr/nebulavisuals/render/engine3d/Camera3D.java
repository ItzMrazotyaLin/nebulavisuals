package me.itzmr.nebulavisuals.render.engine3d;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

/**
 * Единая система camera-relative координат и предвычисленных матриц 3D-рендера.
 *
 * <p>Не создаёт аллокаций на куче в горячем цикле кадра: переиспользует статические {@link Matrix4f}
 * и один off-heap {@link FloatBuffer} для передачи {@code uViewProj} в GLSL-шейдеры.
 * Мировые координаты переводятся в локальные смещения относительно камеры на CPU в {@code double},
 * что полностью исключает float-дрожание (jitter) на больших координатах мира.</p>
 */
public final class Camera3D {
    private static final Matrix4f CAPTURED_VIEW = new Matrix4f();
    private static final Matrix4f CAPTURED_PROJ = new Matrix4f();
    private static final Matrix4f VIEW_MATRIX = new Matrix4f();
    private static final Matrix4f PROJ_MATRIX = new Matrix4f();
    private static final Matrix4f VIEW_PROJ_MATRIX = new Matrix4f();
    private static final Matrix4f SKY_INV_PROJ = new Matrix4f();
    private static final Matrix3f SKY_INV_VIEW_ROT = new Matrix3f();
    private static final Quaternionf TEMP_QUAT = new Quaternionf();
    private static final Vector4f TEMP_CLIP = new Vector4f();

    private static FloatBuffer viewProjBuffer;

    private static boolean hasCapturedMatrices = false;
    private static boolean validFrame = false;

    private static double camX;
    private static double camY;
    private static double camZ;
    private static float tickProgress = 1.0f;

    private Camera3D() {}

    /**
     * Захватывает точные матрицы кадра из {@code WorldRenderer.render(...)},
     * включая {@code bobView}, {@code tiltViewWhenHurt} и актуальный FOV.
     */
    public static void captureMatrices(Matrix4f viewMatrix, Matrix4f projMatrix) {
        if (viewMatrix != null && projMatrix != null) {
            CAPTURED_VIEW.set(viewMatrix);
            CAPTURED_PROJ.set(projMatrix);
            hasCapturedMatrices = true;
        }
    }

    /**
     * Обновляет позицию камеры и комбинированную матрицу {@code ViewProjection} на текущий кадр.
     */
    public static boolean update(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.gameRenderer == null || client.world == null) {
            validFrame = false;
            return false;
        }

        Camera camera = client.gameRenderer.getCamera();
        Vec3d pos = null;
        if (context != null && context.worldState() != null && context.worldState().cameraRenderState != null) {
            pos = context.worldState().cameraRenderState.pos;
        }
        if (pos == null && camera != null) {
            pos = camera.getCameraPos();
        }
        if (pos == null) {
            validFrame = false;
            return false;
        }

        camX = pos.x;
        camY = pos.y;
        camZ = pos.z;

        if (client.getRenderTickCounter() != null) {
            tickProgress = client.getRenderTickCounter().getTickProgress(false);
        }

        if (hasCapturedMatrices) {
            VIEW_MATRIX.set(CAPTURED_VIEW);
            PROJ_MATRIX.set(CAPTURED_PROJ);
        } else if (camera != null) {
            // Запасной путь на случай вызова вне стандартного WorldRenderer.render
            VIEW_MATRIX.rotation(camera.getRotation().conjugate(TEMP_QUAT));
            int w = Math.max(1, client.getWindow().getFramebufferWidth());
            int h = Math.max(1, client.getWindow().getFramebufferHeight());
            float aspect = (float) w / (float) h;
            PROJ_MATRIX.setPerspective((float) Math.toRadians(70.0), aspect, 0.05f, 1024.0f);
        } else {
            validFrame = false;
            return false;
        }

        VIEW_PROJ_MATRIX.set(PROJ_MATRIX).mul(VIEW_MATRIX);
        ensureBuffer();
        viewProjBuffer.clear();
        VIEW_PROJ_MATRIX.get(0, viewProjBuffer);

        validFrame = true;
        return true;
    }

    private static void ensureBuffer() {
        if (viewProjBuffer == null) {
            viewProjBuffer = MemoryUtil.memAllocFloat(16);
        }
    }

    public static void shutdown() {
        if (viewProjBuffer != null) {
            MemoryUtil.memFree(viewProjBuffer);
            viewProjBuffer = null;
        }
        validFrame = false;
        hasCapturedMatrices = false;
    }

    public static boolean isValidFrame() {
        return validFrame;
    }

    /**
     * Проецирует мировую 3D-точку в 2D-координаты произвольного холста (верх-лево = 0,0) без аллокаций.
     */
    public static boolean worldToScreen(double worldX, double worldY, double worldZ,
                                        float viewportW, float viewportH, float[] outXY) {
        if (!validFrame || outXY == null || outXY.length < 2) {
            return false;
        }
        TEMP_CLIP.set((float) (worldX - camX), (float) (worldY - camY), (float) (worldZ - camZ), 1.0f);
        VIEW_PROJ_MATRIX.transform(TEMP_CLIP);
        if (TEMP_CLIP.w <= 0.001f) {
            return false;
        }
        float invW = 1.0f / TEMP_CLIP.w;
        float ndcX = TEMP_CLIP.x * invW;
        float ndcY = TEMP_CLIP.y * invW;
        outXY[0] = (ndcX * 0.5f + 0.5f) * viewportW;
        outXY[1] = (1.0f - (ndcY * 0.5f + 0.5f)) * viewportH;
        return true;
    }

    public static float relX(double worldX) {
        return (float) (worldX - camX);
    }

    public static float relY(double worldY) {
        return (float) (worldY - camY);
    }

    public static float relZ(double worldZ) {
        return (float) (worldZ - camZ);
    }

    public static double getCamX() {
        return camX;
    }

    public static double getCamY() {
        return camY;
    }

    public static double getCamZ() {
        return camZ;
    }

    public static float getTickProgress() {
        return tickProgress;
    }

    public static Matrix4f getViewMatrix() {
        return VIEW_MATRIX;
    }

    public static Matrix4f getProjMatrix() {
        return PROJ_MATRIX;
    }

    public static Matrix4f getViewProjMatrix() {
        return VIEW_PROJ_MATRIX;
    }

    public static boolean hasCapturedMatrices() {
        return hasCapturedMatrices;
    }

    public static Matrix4f getCapturedViewMatrix() {
        return CAPTURED_VIEW;
    }

    public static Matrix4f getCapturedProjMatrix() {
        return CAPTURED_PROJ;
    }

    public static FloatBuffer getViewProjBuffer() {
        ensureBuffer();
        return viewProjBuffer;
    }

    /**
     * Заполняет буферы обратной матрицы проекции ({@code u_InvProj}, 16 float) и обратной
     * матрицы вращения камеры ({@code u_InvViewRot}, 9 float) для мирового скайбокса.
     */
    public static boolean fillSkyInverseMatrices(FloatBuffer invProjDest, FloatBuffer invViewRotDest) {
        if (invProjDest == null || invViewRotDest == null) return false;
        if (hasCapturedMatrices) {
            CAPTURED_PROJ.invert(SKY_INV_PROJ);
            CAPTURED_VIEW.get3x3(SKY_INV_VIEW_ROT).invert();
        } else {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.gameRenderer == null || client.getWindow() == null) {
                return false;
            }
            Camera camera = client.gameRenderer.getCamera();
            if (camera == null) {
                return false;
            }
            int w = Math.max(1, client.getWindow().getFramebufferWidth());
            int h = Math.max(1, client.getWindow().getFramebufferHeight());
            float aspect = (float) w / (float) h;
            PROJ_MATRIX.setPerspective((float) Math.toRadians(70.0), aspect, 0.05f, 1024.0f);
            PROJ_MATRIX.invert(SKY_INV_PROJ);
            SKY_INV_VIEW_ROT.set(camera.getRotation());
        }
        invProjDest.clear();
        SKY_INV_PROJ.get(0, invProjDest);
        invViewRotDest.clear();
        SKY_INV_VIEW_ROT.get(0, invViewRotDest);
        return true;
    }

    public static Vector3f getRightVector(Vector3f dest) {
        return VIEW_MATRIX.positiveX(dest);
    }

    public static Vector3f getUpVector(Vector3f dest) {
        return VIEW_MATRIX.positiveY(dest);
    }

    public static Vector3f getForwardVector(Vector3f dest) {
        return VIEW_MATRIX.positiveZ(dest).negate();
    }
}
