#version 330 core

layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aTexCoord;
layout(location = 2) in vec4 aColor;
layout(location = 3) in vec2 aLocalPos;
layout(location = 4) in vec2 aSize;
layout(location = 5) in vec4 aRadii;
layout(location = 6) in vec4 aParams; // x: type, y: outlineWidth, z: softness/blur, w: pxRange/extra
layout(location = 7) in vec4 aOutlineColor;

uniform mat4 uProjection;

out vec2 vTexCoord;
out vec4 vColor;
out vec2 vLocalPos;
out vec2 vSize;
out vec4 vRadii;
out vec4 vParams;
out vec4 vOutlineColor;

void main() {
    vTexCoord = aTexCoord;
    vColor = aColor;
    vLocalPos = aLocalPos;
    vSize = aSize;
    vRadii = aRadii;
    vParams = aParams;
    vOutlineColor = aOutlineColor;

    gl_Position = uProjection * vec4(aPos, 0.0, 1.0);
}
