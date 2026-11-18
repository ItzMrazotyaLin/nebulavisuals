#version 330 core

in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uTexture;
uniform vec2 uHalfPixel;
uniform float uOffset;

void main() {
    vec2 halfpixel = uHalfPixel * uOffset;

    vec4 sum = vec4(0.0);
    sum += texture(uTexture, vTexCoord + vec2(-halfpixel.x * 2.0, 0.0));
    sum += texture(uTexture, vTexCoord + vec2(-halfpixel.x,  halfpixel.y)) * 2.0;
    sum += texture(uTexture, vTexCoord + vec2( 0.0,         halfpixel.y * 2.0));
    sum += texture(uTexture, vTexCoord + vec2( halfpixel.x,  halfpixel.y)) * 2.0;
    sum += texture(uTexture, vTexCoord + vec2( halfpixel.x * 2.0, 0.0));
    sum += texture(uTexture, vTexCoord + vec2( halfpixel.x, -halfpixel.y)) * 2.0;
    sum += texture(uTexture, vTexCoord + vec2( 0.0,        -halfpixel.y * 2.0));
    sum += texture(uTexture, vTexCoord + vec2(-halfpixel.x, -halfpixel.y)) * 2.0;

    fragColor = vec4(sum.rgb / 12.0, 1.0);
}
