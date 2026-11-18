#version 330 core

layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;

// Per-instance attributes (divisor = 1)
layout(location = 2) in vec3 iCenter;
layout(location = 3) in float iRadius;
layout(location = 4) in vec4 iColor;
layout(location = 5) in float iGlow;

uniform mat4 uViewProj;

out vec3 vNormal;
out vec3 vViewDir;
out vec4 vColor;
out float vGlow;

void main() {
    // aPos is on a unit sphere; iCenter is already camera-relative (worldPos - camPos)
    vec3 relPos = iCenter + aPos * iRadius;
    gl_Position = uViewProj * vec4(relPos, 1.0);

    // Camera is at origin (0, 0, 0) in camera-relative space
    vNormal = aNormal;
    vViewDir = -relPos;
    vColor = iColor;
    vGlow = iGlow;
}
