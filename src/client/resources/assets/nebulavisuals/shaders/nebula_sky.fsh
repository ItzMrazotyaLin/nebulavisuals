#version 330 core

in vec2 v_Ndc;

uniform mat4 u_InvProj;
uniform mat3 u_InvViewRot;
uniform int u_PatternMode;
uniform float u_Time;
uniform vec3 u_ZenithColor;
uniform vec3 u_HorizonColor;
uniform vec3 u_NebulaColor1;
uniform vec3 u_NebulaColor2;
uniform float u_StarsIntensity;
uniform float u_NebulaIntensity;

out vec4 fragColor;

float hash31(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

vec3 hash33(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.xxy + p.yxx) * p.zyx);
}

float noise3(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    vec3 u = f * f * (3.0 - 2.0 * f);

    float n000 = hash31(i + vec3(0.0, 0.0, 0.0));
    float n100 = hash31(i + vec3(1.0, 0.0, 0.0));
    float n010 = hash31(i + vec3(0.0, 1.0, 0.0));
    float n110 = hash31(i + vec3(1.0, 1.0, 0.0));
    float n001 = hash31(i + vec3(0.0, 0.0, 1.0));
    float n101 = hash31(i + vec3(1.0, 0.0, 1.0));
    float n011 = hash31(i + vec3(0.0, 1.0, 1.0));
    float n111 = hash31(i + vec3(1.0, 1.0, 1.0));

    float nx00 = mix(n000, n100, u.x);
    float nx10 = mix(n010, n110, u.x);
    float nx01 = mix(n001, n101, u.x);
    float nx11 = mix(n011, n111, u.x);

    float nxy0 = mix(nx00, nx10, u.y);
    float nxy1 = mix(nx01, nx11, u.y);

    return mix(nxy0, nxy1, u.z);
}

float fbm3(vec3 p) {
    float sum = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 3; i++) {
        sum += amp * noise3(p);
        p = p * 2.07 + vec3(9.2, -5.7, 11.3);
        amp *= 0.5;
    }
    return sum;
}

float fbm4(vec3 p) {
    float sum = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 4; i++) {
        sum += amp * noise3(p);
        p = p * 2.03 + vec3(13.7, -7.3, 5.1);
        amp *= 0.5;
    }
    return sum;
}

vec3 starLayer(vec3 dir, float scale, float densityThreshold, float coreRadius, float haloStrength, float speed) {
    vec3 p = dir * scale;
    vec3 id = floor(p);
    vec3 f = fract(p) - 0.5;

    vec3 rnd = hash33(id);
    if (rnd.x < densityThreshold) {
        return vec3(0.0);
    }

    vec3 jitter = (hash33(id + 17.31) - 0.5) * 0.40;
    float d = length(f - jitter);
    if (d > 0.27) {
        return vec3(0.0);
    }

    float core = pow(clamp(1.0 - d / coreRadius, 0.0, 1.0), 3.6);
    float halo = exp(-d * 17.0) * smoothstep(0.27, 0.0, d) * haloStrength;

    float phase = rnd.y * 6.2831853;
    float freq = 1.2 + rnd.z * 2.6;
    float twinkle = 0.62 + 0.38 * sin(u_Time * freq * speed + phase);

    vec3 starTint = mix(vec3(0.72, 0.86, 1.0), vec3(1.0, 0.96, 0.91), rnd.z);
    float lum = (core + halo) * twinkle * (0.55 + 0.65 * fract(rnd.x * 23.17));
    return starTint * lum;
}

vec3 computeStarfield(vec3 dir, float extraDensityBoost) {
    if (u_StarsIntensity <= 0.001) {
        return vec3(0.0);
    }
    float starFade = smoothstep(0.005, 0.22, dir.y);
    float densityShift = clamp((u_StarsIntensity - 1.0) * 0.12 + extraDensityBoost, -0.08, 0.28);

    vec3 dustStars = starLayer(dir, 118.0, 0.64 - densityShift, 0.11, 0.14, 1.15);
    vec3 brightStars = starLayer(dir, 46.0, 0.84 - densityShift * 0.6, 0.15, 0.52, 0.85);

    vec3 stars = (dustStars * 0.75 + brightStars * 1.30) * u_StarsIntensity * starFade;
    return mix(stars, stars * mix(vec3(1.0), u_NebulaColor2 + vec3(0.35), 0.35), 0.35);
}

void main() {
    // Точное восстановление мирового направления луча из экранного NDC (фиксировано в мире при вращении камеры)
    vec4 viewPos = u_InvProj * vec4(v_Ndc, 1.0, 1.0);
    vec3 viewRay = normalize(viewPos.xyz / viewPos.w);
    vec3 dir = normalize(u_InvViewRot * viewRay);

    // 1. Базовый атмосферный градиент (зенит <-> горизонт + плавный переход вниз)
    float up = clamp(dir.y, 0.0, 1.0);
    float horizonPow = pow(1.0 - up, 3.1);
    float midGlow = pow(1.0 - up, 1.55) * 0.26;
    vec3 upperSky = mix(u_ZenithColor, u_HorizonColor, clamp(horizonPow + midGlow, 0.0, 1.0));

    float down = clamp(-dir.y, 0.0, 1.0);
    vec3 nadirColor = mix(u_HorizonColor, u_ZenithColor * 0.58, 0.72);
    vec3 baseSky = mix(upperSky, nadirColor, smoothstep(0.0, 0.65, down));

    float skyFade = smoothstep(-0.04, 0.24, dir.y);
    vec3 patternColor = vec3(0.0);
    vec3 stars = vec3(0.0);

    if (u_PatternMode == 1) {
        // === GALACTIC CORE: диагональный диск Млечного Пути с пылевыми разломами ===
        vec3 galNormal = normalize(vec3(0.42, 0.68, -0.60));
        vec3 galU = normalize(cross(vec3(0.0, 1.0, 0.0), galNormal));
        vec3 galV = cross(galNormal, galU);

        float lat = dot(dir, galNormal);
        float diskEnvelope = exp(-lat * lat * 10.5);
        float coreAlign = smoothstep(-0.25, 1.0, dot(dir, normalize(galU * 0.85 + galV * 0.28)));
        float coreBulge = exp(-lat * lat * 32.0) * (0.28 + 0.92 * pow(coreAlign, 1.7));

        vec3 galFlow = vec3(u_Time * 0.004, -u_Time * 0.002, u_Time * 0.003);
        float cloudBase = fbm4(dir * 3.8 + galFlow);
        float cloudFine = fbm4(dir * 7.4 + vec3(cloudBase * 1.4, -cloudBase * 1.1, cloudBase * 0.8));

        // Тёмные пылевые разломы (Great Rift) вдоль экватора галактического диска
        float dustFbm = fbm4(dir * 6.4 + vec3(cloudBase * 1.9, cloudFine * 0.85, u_Time * 0.003));
        float riftDetail = fbm3(dir * 13.5 - vec3(dustFbm * 1.35));
        float dustLane = smoothstep(0.42, 0.72, dustFbm * 0.68 + riftDetail * 0.36) * exp(-lat * lat * 88.0);
        float transmission = 1.0 - dustLane * 0.88;

        vec3 armGlow = u_NebulaColor1 * diskEnvelope * (0.45 + 0.85 * smoothstep(0.32, 0.78, cloudBase));
        vec3 coreGlow = u_NebulaColor2 * coreBulge * (0.65 + 0.70 * smoothstep(0.35, 0.82, cloudFine));
        vec3 brightNucleus = vec3(1.0, 0.91, 0.78) * pow(coreBulge, 1.65) * 0.55;

        patternColor = (armGlow + coreGlow + brightNucleus) * transmission * u_NebulaIntensity * skyFade;

        // Повышенная плотность звёзд внутри диска с частичным затуханием в пылевых разломах
        stars = computeStarfield(dir, diskEnvelope * 0.16) * (0.35 + 0.65 * transmission);
        stars += starLayer(dir, 165.0, 0.70 - diskEnvelope * 0.24, 0.11, 0.08, 0.75) * diskEnvelope * u_StarsIntensity * skyFade * transmission * 0.75;

    } else if (u_PatternMode == 2) {
        // === NORTHERN LIGHTS: вертикальные волнообразные занавеси полярного сияния ===
        float elev = max(dir.y, 0.035);
        vec2 auraUv = dir.xz / (elev + 0.14);

        vec2 p1 = auraUv * 0.44;
        float w1 = fbm3(vec3(p1 * 1.35, u_Time * 0.06));
        float wave1 = sin(p1.x * 2.1 + p1.y * 0.9 + w1 * 4.4 + u_Time * 0.24);
        float curtain1 = exp(-abs(wave1) * 5.0);

        vec2 p2 = auraUv * 0.62 + vec2(3.7, -2.1);
        float w2 = fbm3(vec3(p2 * 1.15, u_Time * 0.05 + 4.2));
        float wave2 = sin(p2.x * 1.7 - p2.y * 1.2 + w2 * 3.9 - u_Time * 0.19);
        float curtain2 = exp(-abs(wave2) * 6.4) * 0.85;

        float rays = 0.52 + 0.48 * noise3(vec3(auraUv * 3.6, u_Time * 0.14));
        float verticalEnv = smoothstep(0.015, 0.24, dir.y) * exp(-max(dir.y - 0.20, 0.0) * 1.55);

        float a1 = curtain1 * rays * verticalEnv;
        float a2 = curtain2 * rays * verticalEnv;

        float altMix = smoothstep(0.08, 0.54, dir.y);
        vec3 violetTip = vec3(0.62, 0.22, 0.98);
        vec3 c1 = mix(u_NebulaColor1, mix(u_NebulaColor2, violetTip, smoothstep(0.28, 0.68, dir.y)), altMix);
        vec3 c2 = mix(u_NebulaColor2, violetTip, smoothstep(0.18, 0.58, dir.y));

        patternColor = (c1 * a1 * 1.05 + c2 * a2 * 0.85) * u_NebulaIntensity;
        stars = computeStarfield(dir, 0.0);

    } else if (u_PatternMode == 3) {
        // === SOLAR ECLIPSE: чёрная дыра / затмение с пульсирующей аккреционной короной ===
        vec3 eclipseDir = normalize(vec3(0.42, 0.54, -0.73));
        vec3 ecRight = normalize(cross(vec3(0.0, 1.0, 0.0), eclipseDir));
        vec3 ecUp = cross(eclipseDir, ecRight);

        float cosTheta = dot(dir, eclipseDir);
        float angle = acos(clamp(cosTheta, -1.0, 1.0));
        float dx = dot(dir, ecRight);
        float dy = dot(dir, ecUp);
        float az = atan(dy, dx);

        float discRadius = 0.072;
        float outsideDisc = smoothstep(discRadius - 0.003, discRadius + 0.003, angle);

        float pulse = 1.0 + 0.12 * sin(u_Time * 2.1) + 0.06 * cos(az * 3.0 - u_Time * 1.5);
        float photonRing = exp(-abs(angle - (discRadius + 0.002)) * 235.0) * pulse;

        // Наклонённый релятивистский аккреционный диск вокруг горизонта событий
        float tiltX = dx * 0.88 - dy * 0.48;
        float tiltY = (dx * 0.48 + dy * 0.88) * 3.1;
        float diskR = length(vec2(tiltX, tiltY));
        float swirl = fbm3(vec3(tiltX * 14.0, tiltY * 14.0, u_Time * 0.42));
        float accretionDisk = exp(-abs(diskR - 0.118) * 34.0) * (0.45 + 0.78 * swirl) * step(0.0, cosTheta);

        float coronaRays = 0.52 + 0.48 * noise3(vec3(cos(az) * 4.5, sin(az) * 4.5, u_Time * 0.24));
        float corona = exp(-max(angle - discRadius, 0.0) * 14.0) * coronaRays * step(-0.1, cosTheta);
        float wideGlow = exp(-max(angle - discRadius, 0.0) * 5.0) * 0.42 * step(-0.2, cosTheta);

        vec3 backNebula = u_NebulaColor1 * smoothstep(0.38, 0.85, fbm4(dir * 2.1 + vec3(u_Time * 0.006))) * 0.35 * skyFade;
        vec3 eclipseGlow = (
            u_NebulaColor1 * (corona * 1.05 + wideGlow) +
            u_NebulaColor2 * (accretionDisk * 1.15) +
            vec3(1.0, 0.96, 0.88) * (photonRing * 1.85)
        ) * max(0.25, u_NebulaIntensity);

        vec3 rawStars = computeStarfield(dir, 0.0);
        // Внутри чёрного диска звёзды и фон полностью поглощаются горизонтом событий
        baseSky *= outsideDisc;
        stars = rawStars * outsideDisc;
        patternColor = backNebula * outsideDisc + eclipseGlow * mix(1.0, outsideDisc, step(angle, discRadius));

    } else {
        // === STANDARD NEBULA PATTERNS (Nebula Core, Accent Nebula, Aurora, Cyberpunk, Deep Space, Blood Moon) ===
        vec3 drift = vec3(u_Time * 0.01, -u_Time * 0.005, u_Time * 0.008);
        vec3 q = dir * 2.15 + drift;
        float warp = fbm4(q);

        float n1 = fbm4(q * 1.15 + vec3(warp * 1.35, -warp * 0.85, warp * 0.65));
        float n2 = fbm4(q * 2.10 - vec3(warp * 0.75, warp * 1.15, -warp * 0.55));

        vec3 bandNormal = normalize(vec3(0.36, 0.78, -0.51));
        float galacticBand = exp(-pow(abs(dot(dir, bandNormal)), 1.5) * 2.3) * 0.65 + 0.35;

        float cloud1 = smoothstep(0.36, 0.80, n1) * galacticBand;
        float cloud2 = smoothstep(0.42, 0.85, n2) * galacticBand;
        float gasWisp = smoothstep(0.47, 0.88, n1 * 0.58 + n2 * 0.52) * galacticBand;

        patternColor = (
            u_NebulaColor1 * cloud1 * 0.78 +
            u_NebulaColor2 * cloud2 * 0.92 +
            mix(u_NebulaColor1, u_NebulaColor2, 0.5) * gasWisp * 0.55
        ) * u_NebulaIntensity * skyFade;

        stars = computeStarfield(dir, 0.0);
    }

    vec3 finalColor = baseSky + patternColor + stars;
    finalColor = finalColor / (vec3(1.0) + max(finalColor - vec3(0.85), vec3(0.0)) * 0.45);

    fragColor = vec4(clamp(finalColor, 0.0, 1.0), 1.0);
}
