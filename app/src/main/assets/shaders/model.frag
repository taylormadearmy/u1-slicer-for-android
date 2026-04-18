#version 300 es
precision mediump float;

in vec4 v_Color;
in vec3 v_Intensity;
out vec4 fragColor;

void main() {
    fragColor = vec4(max(v_Color.rgb * v_Intensity, vec3(0.12)), v_Color.a);
}
