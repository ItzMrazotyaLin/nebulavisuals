#version 330 core

in vec4 vColor;

out vec4 fragColor;

void main() {
    if (vColor.a < 0.002) {
        discard;
    }
    fragColor = vColor;
}
