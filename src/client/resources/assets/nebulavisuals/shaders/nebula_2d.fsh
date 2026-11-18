#version 330 core

in vec2 vTexCoord;
in vec4 vColor;
in vec2 vLocalPos;
in vec2 vSize;
in vec4 vRadii;
in vec4 vParams; // x: type, y: outlineWidth, z: softness/blur, w: pxRange/extra
in vec4 vOutlineColor;

uniform sampler2D uTexture;
uniform float u_UiScale;
uniform float u_PxRange;

out vec4 fragColor;

// MSDF median calculation
float median(float r, float g, float b) {
    return max(min(r, g), min(max(r, g), b));
}

// 4-corner rounded box SDF (IQ formulation)
// vRadii: x = Top-Left, y = Top-Right, z = Bottom-Right, w = Bottom-Left
float selectRadius(vec2 p, vec4 r) {
    return (p.x > 0.0) ? ((p.y > 0.0) ? r.z : r.y) : ((p.y > 0.0) ? r.w : r.x);
}

float sdRoundedBox(vec2 p, vec2 b, vec4 r) {
    float rad = selectRadius(p, r);
    rad = min(rad, min(b.x, b.y));
    vec2 q = abs(p) - b + vec2(rad);
    return min(max(q.x, q.y), 0.0) + length(max(q, vec2(0.0))) - rad;
}

void main() {
    int primType = int(vParams.x + 0.5);
    float safeScale = (u_UiScale > 0.0) ? max(u_UiScale, 0.25) : 1.0;

    // 0: Plain / Gradient Rect
    if (primType == 0) {
        fragColor = vColor;
        return;
    }

    vec2 b = vSize * 0.5;
    vec2 p = vLocalPos - b;

    // 1: SDF Rounded Rectangle / Outline / Shadow
    if (primType == 1) {
        float d = sdRoundedBox(p, b, vRadii);
        float softness = vParams.z;
        float outlineWidth = vParams.y;

        // Shadow / Glow mode (softness > 1.0): исключаем затенение внутри прозрачного стекла
        if (softness > 1.0) {
            float shadowAlpha = (1.0 - smoothstep(-softness, softness, d)) * smoothstep(-2.0, 2.0, d);
            fragColor = vec4(vColor.rgb, vColor.a * shadowAlpha);
            return;
        }

        // AA и толщина бордера в физических пикселях экрана (>= 1.0 физический пиксель для обводки)
        float aa = max(fwidth(d), 0.5 / safeScale);

        if (outlineWidth > 0.0) {
            float borderThickness = max(outlineWidth, 1.0 / safeScale);
            float outerAlpha = 1.0 - smoothstep(-aa, aa, d);
            float innerAlpha = 1.0 - smoothstep(-aa, aa, d + borderThickness);
            float outlineAlpha = clamp(outerAlpha - innerAlpha, 0.0, 1.0);

            if (vColor.a > 0.0) {
                vec4 fill = vec4(vColor.rgb, vColor.a * innerAlpha);
                vec4 stroke = vec4(vOutlineColor.rgb, vOutlineColor.a * outlineAlpha);
                fragColor = mix(fill, stroke, outlineAlpha);
            } else {
                fragColor = vec4(vOutlineColor.rgb, vOutlineColor.a * outlineAlpha);
            }
        } else {
            float fillAlpha = 1.0 - smoothstep(-aa, aa, d);
            fragColor = vec4(vColor.rgb, vColor.a * fillAlpha);
        }
        return;
    }

    // 2: Textured Quad (with optional rounded corner clipping)
    if (primType == 2) {
        vec4 tex = texture(uTexture, vTexCoord) * vColor;
        if (dot(vRadii, vec4(1.0)) > 0.0) {
            float d = sdRoundedBox(p, b, vRadii);
            float aa = max(fwidth(d), 0.5 / safeScale);
            float alpha = 1.0 - smoothstep(-aa, aa, d);
            fragColor = vec4(tex.rgb, tex.a * alpha);
        } else {
            fragColor = tex;
        }
        return;
    }

    // 3: MSDF Text (формула Valve/Chlumsky с динамическим screenPxRange)
    if (primType == 3) {
        vec3 msd = texture(uTexture, vTexCoord).rgb;
        float sd = median(msd.r, msd.g, msd.b);
        float pxRange = (vParams.w > 0.0) ? vParams.w : ((u_PxRange > 0.0) ? u_PxRange : 4.0);
        vec2 unitRange = vec2(pxRange) / vec2(textureSize(uTexture, 0));
        vec2 screenTexSize = vec2(1.0) / max(fwidth(vTexCoord), vec2(1e-6));
        float screenPxRange = max(0.5 * dot(unitRange, screenTexSize), 1.0);
        float screenPxDistance = screenPxRange * (sd - 0.5);
        float textAlpha = clamp(screenPxDistance + 0.5, 0.0, 1.0);
        fragColor = vec4(vColor.rgb, vColor.a * textAlpha);
        return;
    }

    fragColor = vColor;
}
