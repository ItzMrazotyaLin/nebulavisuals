#version 330 core

in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uCurrentTex;
uniform sampler2D uDepthTex;
uniform mat4 uInverseViewProjection;
uniform mat4 uPrevViewProjection;
uniform float uStrength;
uniform int uSamples;

float interleavedGradientNoise(vec2 pixelPos) {
    vec3 magic = vec3(0.06711056, 0.00583715, 52.9829189);
    return fract(magic.z * fract(dot(pixelPos, magic.xy)));
}

void main() {
    vec4 centerColor = texture(uCurrentTex, vTexCoord);
    float depth = texture(uDepthTex, vTexCoord).r;

    // Исключаем близкую глубину руки и предметов от первого лица
    float handMask = smoothstep(0.50, 0.62, depth);
    if (handMask <= 0.001) {
        fragColor = centerColor;
        return;
    }

    vec2 currNdc = vTexCoord * 2.0 - 1.0;
    vec4 clipPos = vec4(currNdc, depth * 2.0 - 1.0, 1.0);
    vec4 relWorld = uInverseViewProjection * clipPos;
    relWorld /= max(abs(relWorld.w), 1e-6) * sign(relWorld.w == 0.0 ? 1.0 : relWorld.w);

    vec4 prevClip = uPrevViewProjection * relWorld;
    vec2 prevNdc = prevClip.xy / max(abs(prevClip.w), 1e-6) * sign(prevClip.w == 0.0 ? 1.0 : prevClip.w);

    vec2 velocity = (currNdc - prevNdc) * 0.5;
    float velLen = length(velocity);
    if (velLen < 0.0001) {
        fragColor = centerColor;
        return;
    }

    // Мягкое затухание у краёв экрана (vignette clamp) + ограничение экстремальных выбросов
    vec2 edgeDist = min(vTexCoord, 1.0 - vTexCoord);
    float edgeVignette = smoothstep(0.0, 0.075, min(edgeDist.x, edgeDist.y));
    float maxVel = 0.075;
    if (velLen > maxVel) {
        velocity *= (maxVel / velLen);
    }
    velocity *= clamp(uStrength, 0.0, 1.25) * handMask * mix(0.35, 1.0, edgeVignette);

    int sampleCount = clamp(uSamples, 4, 24);
    float invSamples = 1.0 / float(sampleCount);
    float jitter = interleavedGradientNoise(gl_FragCoord.xy) - 0.5;

    vec3 accum = centerColor.rgb;
    float totalWeight = 1.0;

    for (int i = 0; i < sampleCount; i++) {
        float t = ((float(i) + 0.5 + jitter) * invSamples) - 0.5;
        vec2 sampleUv = clamp(vTexCoord + velocity * t, vec2(0.001), vec2(0.999));
        float sampleDepth = texture(uDepthTex, sampleUv).r;
        float depthValid = smoothstep(0.48, 0.60, sampleDepth);
        float w = (1.0 - abs(t) * 0.35) * depthValid;
        accum += texture(uCurrentTex, sampleUv).rgb * w;
        totalWeight += w;
    }

    fragColor = vec4(accum / max(totalWeight, 1e-4), centerColor.a);
}
