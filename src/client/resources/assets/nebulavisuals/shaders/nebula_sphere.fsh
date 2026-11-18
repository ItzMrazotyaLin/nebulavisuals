#version 330 core

in vec3 vNormal;
in vec3 vViewDir;
in vec4 vColor;
in float vGlow;

out vec4 fragColor;

void main() {
    vec3 n = normalize(vNormal);
    vec3 v = normalize(vViewDir);
    float ndotv = clamp(dot(n, v), 0.0, 1.0);

    // Плотное яркое ядро в центре и плавный спад яркости к краям без эффекта мыльного пузыря
    float softEdge = smoothstep(0.0, 0.9, ndotv);
    float falloff = pow(softEdge, 1.35);
    float core = smoothstep(0.35, 1.0, ndotv);

    float glowWeight = clamp(max(vGlow, 0.0), 0.0, 1.0);
    float radialAlpha = mix(1.0, falloff, glowWeight);

    vec3 rgb = mix(vColor.rgb, vec3(1.0), core * 0.35 * glowWeight);
    float alpha = vColor.a * radialAlpha;

    if (alpha < 0.002) {
        discard;
    }

    fragColor = vec4(rgb, alpha);
}
