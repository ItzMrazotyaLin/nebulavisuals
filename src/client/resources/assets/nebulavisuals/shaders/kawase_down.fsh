#version 330 core

in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uTexture;
uniform vec2 uHalfPixel;
uniform float uOffset;

void main() {
    vec2 halfpixel = uHalfPixel * uOffset;

    vec4 sum = texture(uTexture, vTexCoord) * 4.0;
    sum += texture(uTexture, vTexCoord - halfpixel);
    sum += texture(uTexture, vTexCoord + halfpixel);
    sum += texture(uTexture, vTexCoord + vec2(halfpixel.x, -halfpixel.y));
    sum += texture(uTexture, vTexCoord + vec2(-halfpixel.x, halfpixel.y));

    fragColor = vec4(sum.rgb / 8.0, 1.0);
}
