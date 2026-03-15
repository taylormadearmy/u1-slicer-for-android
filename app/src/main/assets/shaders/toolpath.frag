#version 300 es
precision mediump float;

in vec4 v_Color;
in float v_Intensity;
out vec4 fragColor;

void main() {
    fragColor = vec4(v_Color.rgb * v_Intensity, v_Color.a);
}
