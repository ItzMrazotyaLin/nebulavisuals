package me.itzmr.nebulavisuals.render.engine2d;

/**
 * Доступ к целевому состоянию плавного инерционного скролла в {@code EntryListWidgetMixin}.
 */
public interface SmoothScrollAccess {
    double nebulavisuals$getTargetScrollY();

    void nebulavisuals$scrollByWheel(double newTargetScrollY);
}
