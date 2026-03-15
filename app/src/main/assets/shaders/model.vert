#version 300 es
precision mediump float;

uniform mat4 u_MVPMatrix;
uniform mat4 u_NormalMatrix;
uniform vec4 u_Color;
uniform int u_UseVertexColor;

layout(location = 0) in vec3 a_Position;
layout(location = 1) in vec3 a_Normal;
layout(location = 2) in vec4 a_Color;

out vec4 v_Color;
out vec3 v_Intensity;

// Two directional lights (similar to SliceBeam/PrusaSlicer)
const vec3 LIGHT_TOP_DIR = normalize(vec3(-0.46, 0.46, 0.76));
const vec3 LIGHT_FRONT_DIR = normalize(vec3(0.70, 0.14, 0.70));

const float AMBIENT = 0.3;
const float DIFFUSE_TOP = 0.8;
const float DIFFUSE_FRONT = 0.3;
const float SPECULAR_TOP = 0.125;

void main() {
    gl_Position = u_MVPMatrix * vec4(a_Position, 1.0);

    vec3 normal = normalize((u_NormalMatrix * vec4(a_Normal, 0.0)).xyz);

    // Diffuse lighting
    float NdotL_top = max(dot(normal, LIGHT_TOP_DIR), 0.0);
    float NdotL_front = max(dot(normal, LIGHT_FRONT_DIR), 0.0);

    // Simple specular (Blinn-Phong approximation)
    float specular = pow(max(dot(normal, normalize(LIGHT_TOP_DIR + vec3(0.0, 0.0, 1.0))), 0.0), 10.0) * SPECULAR_TOP;

    float intensity = AMBIENT + DIFFUSE_TOP * NdotL_top + DIFFUSE_FRONT * NdotL_front + specular;
    v_Intensity = vec3(intensity);

    v_Color = (u_UseVertexColor == 1) ? a_Color : u_Color;
}
