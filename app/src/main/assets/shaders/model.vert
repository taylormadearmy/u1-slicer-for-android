#version 300 es
precision mediump float;

uniform mat4 u_MVPMatrix;
uniform mat4 u_NormalMatrix;
uniform vec4 u_Color;
uniform float u_UseVertexColor;

// F66 — outline pass. When `u_OutlineExpand > 0` the vertex is offset
// outward in clip space (post-MVP) along the screen-space projection of
// its model-space normal. Combined with front-face culling in the draw
// call this produces a silhouette around the selected object — visible
// as a thin outline regardless of the object's surface colour.
//
// `u_OutlineExpand` is in NDC units (after the perspective divide
// roughly equals fraction of half-viewport). Screen-space expansion is
// preferable to a fixed model-space offset because (a) the visible
// outline thickness stays constant across zoom levels — model-space
// produced a wafer-thin line when zoomed out and a chunky band when
// zoomed in — and (b) sharp-edge artifacts are smaller in clip space
// because the offset doesn't compound with depth.
uniform float u_OutlineExpand;

layout(location = 0) in vec3 a_Position;
layout(location = 1) in vec3 a_Normal;
layout(location = 2) in vec4 a_Color;

out vec4 v_Color;
out vec3 v_Intensity;

// Two directional lights (similar to SliceBeam/PrusaSlicer)
const vec3 LIGHT_TOP_DIR = normalize(vec3(-0.46, 0.46, 0.76));
const vec3 LIGHT_FRONT_DIR = normalize(vec3(0.70, 0.14, 0.70));

const float AMBIENT = 0.45;
const float DIFFUSE_TOP = 0.8;
const float DIFFUSE_FRONT = 0.3;
const float SPECULAR_TOP = 0.125;

void main() {
    vec4 clipPos = u_MVPMatrix * vec4(a_Position, 1.0);
    if (u_OutlineExpand > 0.0) {
        // Transform the model-space normal to clip space (w=0 = direction).
        vec4 clipNormal = u_MVPMatrix * vec4(a_Normal, 0.0);
        vec2 dir = clipNormal.xy;
        // Guard against zero-length normals or perfectly camera-aligned faces
        // (normal projects to (0,0) — no usable screen-space direction).
        if (length(dir) > 1e-5) {
            dir = normalize(dir);
            // Multiply by clipPos.w to cancel the upcoming perspective divide:
            // we want a fixed NDC offset = u_OutlineExpand, regardless of
            // depth.
            clipPos.xy += dir * u_OutlineExpand * clipPos.w;
        }
    }
    gl_Position = clipPos;

    vec3 normal = normalize((u_NormalMatrix * vec4(a_Normal, 0.0)).xyz);

    // Diffuse lighting
    float NdotL_top = max(dot(normal, LIGHT_TOP_DIR), 0.0);
    float NdotL_front = max(dot(normal, LIGHT_FRONT_DIR), 0.0);

    // Simple specular (Blinn-Phong approximation)
    float specular = pow(max(dot(normal, normalize(LIGHT_TOP_DIR + vec3(0.0, 0.0, 1.0))), 0.0), 10.0) * SPECULAR_TOP;

    float intensity = AMBIENT + DIFFUSE_TOP * NdotL_top + DIFFUSE_FRONT * NdotL_front + specular;
    v_Intensity = vec3(intensity);

    v_Color = (u_UseVertexColor > 0.5) ? a_Color : u_Color;
}
