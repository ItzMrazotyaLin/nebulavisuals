#version 330 core

layout(location = 0) in vec2 aPos;

out vec2 v_Ndc;

void main() {
    v_Ndc = aPos;
    gl_Position = vec4(aPos, 0.9999, 1.0);
}
