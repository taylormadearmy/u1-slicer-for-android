#version 300 es
precision mediump float;

uniform mat4 u_MVPMatrix;
uniform mat4 u_NormalMatrix;

layout(location = 0) in vec3 a_Position;
layout(location = 1) in vec4 a_Color;
layout(location = 2) in vec3 a_Normal;

out vec4 v_Color;
out float v_Intensity;

// Two directional lights (matching model viewer)
const vec3 LIGHT_TOP_DIR = normalize(vec3(-0.46, 0.46, 0.76));
const vec3 LIGHT_FRONT_DIR = normalize(vec3(0.70, 0.14, 0.70));

const float AMBIENT = 0.4;
const float DIFFUSE_TOP = 0.6;
const float DIFFUSE_FRONT = 0.25;
const float SPECULAR_TOP = 0.1;

void main() {
    gl_Position = u_MVPMatrix * vec4(a_Position, 1.0);
    v_Color = a_Color;

    // When normal is zero (GL_LINES fallback), skip lighting
    float normalLen = length(a_Normal);
    if (normalLen < 0.01) {
        v_Intensity = 1.0;
    } else {
        vec3 normal = normalize((u_NormalMatrix * vec4(a_Normal, 0.0)).xyz);
        float NdotL_top = max(dot(normal, LIGHT_TOP_DIR), 0.0);
        float NdotL_front = max(dot(normal, LIGHT_FRONT_DIR), 0.0);
        float specular = pow(max(dot(normal, normalize(LIGHT_TOP_DIR + vec3(0.0, 0.0, 1.0))), 0.0), 10.0) * SPECULAR_TOP;
        v_Intensity = AMBIENT + DIFFUSE_TOP * NdotL_top + DIFFUSE_FRONT * NdotL_front + specular;
    }
}
