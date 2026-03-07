#version 300 es
precision mediump float;

uniform mat4 u_MVPMatrix;
layout(location = 0) in vec3 a_Position;
layout(location = 1) in vec2 a_TexCoord;
out vec2 v_TexCoord;

void main() {
    gl_Position = u_MVPMatrix * vec4(a_Position, 1.0);
    v_TexCoord = a_TexCoord;
}
