#version 300 es
precision mediump float;

uniform mat4 u_MVPMatrix;
uniform mat4 u_NormalMatrix;

// Per-instance attributes (one per extrusion move, advanced via divisor=1)
layout(location = 0) in vec3 a_Start;       // x0, y0, z
layout(location = 1) in vec3 a_End;         // x1, y1, z
layout(location = 2) in vec4 a_Color;       // RGBA (brightness-adjusted)
layout(location = 3) in vec2 a_Dimensions;  // halfWidth, halfHeight

out vec4 v_Color;
out float v_Intensity;

// Lighting (matching existing toolpath.vert)
const vec3 LIGHT_TOP_DIR = normalize(vec3(-0.46, 0.46, 0.76));
const vec3 LIGHT_FRONT_DIR = normalize(vec3(0.70, 0.14, 0.70));
const float AMBIENT = 0.20;
const float DIFFUSE_TOP = 0.65;
const float DIFFUSE_FRONT = 0.30;
const float SPECULAR_TOP = 0.25;

void main() {
    float halfW = a_Dimensions.x;
    float halfH = a_Dimensions.y;

    vec2 dir = a_End.xy - a_Start.xy;
    float len = length(dir);
    vec2 fwd = (len > 0.001) ? dir / len : vec2(1.0, 0.0);
    vec2 perp = vec2(-fwd.y, fwd.x);

    // 18 vertices = 3 faces (top, right, left) x 2 triangles x 3 verts
    int vid = gl_VertexID;

    // t: 0=start, 1=end
    const int T[18] = int[18](0,0,1, 0,1,1,  0,0,1, 0,1,1,  0,1,0, 0,1,1);
    // s: side (-1=left, +1=right)
    const int S[18] = int[18](-1,1,1, -1,1,-1,  1,1,1, 1,1,1,  -1,-1,-1, -1,-1,-1);
    // h: height (-1=bot, +1=top)
    const int H[18] = int[18](1,1,1, 1,1,1,  -1,1,1, -1,1,-1,  -1,1,1, -1,-1,1);

    float tVal = float(T[vid]);
    float sVal = float(S[vid]);
    float hVal = float(H[vid]);

    vec3 basePos = mix(a_Start, a_End, tVal);
    vec3 pos = vec3(
        basePos.x + perp.x * sVal * halfW,
        basePos.y + perp.y * sVal * halfW,
        basePos.z + hVal * halfH
    );

    gl_Position = u_MVPMatrix * vec4(pos, 1.0);
    v_Color = a_Color;

    // Normal per face
    vec3 normal;
    if (vid < 6) {
        normal = vec3(0.0, 0.0, 1.0);       // top face
    } else if (vid < 12) {
        normal = vec3(perp, 0.0);            // right face
    } else {
        normal = vec3(-perp, 0.0);           // left face
    }
    vec3 worldNormal = normalize((u_NormalMatrix * vec4(normal, 0.0)).xyz);
    float NdotL_top = max(dot(worldNormal, LIGHT_TOP_DIR), 0.0);
    float NdotL_front = max(dot(worldNormal, LIGHT_FRONT_DIR), 0.0);
    vec3 halfVec = normalize(LIGHT_TOP_DIR + vec3(0.0, 0.0, 1.0));
    float specular = pow(max(dot(worldNormal, halfVec), 0.0), 32.0) * SPECULAR_TOP;
    v_Intensity = AMBIENT + DIFFUSE_TOP * NdotL_top + DIFFUSE_FRONT * NdotL_front + specular;
}
