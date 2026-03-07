#version 300 es
precision mediump float;

uniform sampler2D u_Texture;
uniform float u_Alpha;
in vec2 v_TexCoord;
out vec4 fragColor;

void main() {
    vec4 texColor = texture(u_Texture, v_TexCoord);
    fragColor = vec4(texColor.rgb, texColor.a * u_Alpha);
}
