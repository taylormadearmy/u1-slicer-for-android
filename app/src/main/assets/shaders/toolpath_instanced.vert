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

// Lighting
const vec3 LIGHT_TOP_DIR = normalize(vec3(-0.46, 0.46, 0.76));
const vec3 LIGHT_FRONT_DIR = normalize(vec3(0.70, 0.14, 0.70));
const float AMBIENT = 0.35;
const float DIFFUSE_TOP = 0.75;
const float DIFFUSE_FRONT = 0.30;
const float SPECULAR_TOP = 0.20;

// Hexagonal cross-section: 6 vertices around the tube profile.
// Viewed end-on (perp = right, Z = up):
//   v0 = ( 0,   +H)   top
//   v1 = (+W,  +H/2)  upper-right
//   v2 = (+W,  -H/2)  lower-right
//   v3 = ( 0,   -H)   bottom
//   v4 = (-W,  -H/2)  lower-left
//   v5 = (-W,  +H/2)  upper-left
//
// 6 faces, each a quad (2 tris, 6 verts) connecting adjacent hex verts
// at start (t=0) and end (t=1) of the move = 36 vertices total.

void main() {
    float halfW = a_Dimensions.x;
    float halfH = a_Dimensions.y;

    vec2 dir = a_End.xy - a_Start.xy;
    float len = length(dir);
    vec2 fwd = (len > 0.001) ? dir / len : vec2(1.0, 0.0);
    vec2 perp = vec2(-fwd.y, fwd.x);

    // 36 vertices = 6 faces x 2 triangles x 3 verts
    int vid = gl_VertexID;
    int faceId = vid / 6;          // which face (0-5)
    int triVert = vid - faceId * 6; // vertex within the face quad (0-5)

    // Hex profile: (s, h) pairs for vertices 0-5
    const float HS[6] = float[6]( 0.0,  1.0,  1.0,  0.0, -1.0, -1.0);
    const float HH[6] = float[6]( 1.0,  0.5, -0.5, -1.0, -0.5,  0.5);

    // Each face connects hex vertex faceId to (faceId+1)%6
    int i0 = faceId;
    int i1 = (faceId < 5) ? faceId + 1 : 0;

    // Two triangles per quad: (A0,A1,B1) and (A0,B1,B0)
    // A = start (t=0), B = end (t=1), 0/1 = hex vertex index
    float tVal, sVal, hVal;
    if (triVert == 0)      { tVal = 0.0; sVal = HS[i0]; hVal = HH[i0]; }
    else if (triVert == 1) { tVal = 0.0; sVal = HS[i1]; hVal = HH[i1]; }
    else if (triVert == 2) { tVal = 1.0; sVal = HS[i1]; hVal = HH[i1]; }
    else if (triVert == 3) { tVal = 0.0; sVal = HS[i0]; hVal = HH[i0]; }
    else if (triVert == 4) { tVal = 1.0; sVal = HS[i1]; hVal = HH[i1]; }
    else                   { tVal = 1.0; sVal = HS[i0]; hVal = HH[i0]; }

    vec3 basePos = mix(a_Start, a_End, tVal);
    vec3 pos = vec3(
        basePos.x + perp.x * sVal * halfW,
        basePos.y + perp.y * sVal * halfW,
        basePos.z + hVal * halfH
    );

    gl_Position = u_MVPMatrix * vec4(pos, 1.0);
    v_Color = a_Color;

    // Face normal: average of the two hex vertex directions
    float ns = (HS[i0] + HS[i1]) * 0.5;
    float nh = (HH[i0] + HH[i1]) * 0.5;
    vec3 normal = normalize(vec3(perp * ns, nh));

    vec3 worldNormal = normalize((u_NormalMatrix * vec4(normal, 0.0)).xyz);
    float NdotL_top = max(dot(worldNormal, LIGHT_TOP_DIR), 0.0);
    float NdotL_front = max(dot(worldNormal, LIGHT_FRONT_DIR), 0.0);
    vec3 halfVec = normalize(LIGHT_TOP_DIR + vec3(0.0, 0.0, 1.0));
    float specular = pow(max(dot(worldNormal, halfVec), 0.0), 32.0) * SPECULAR_TOP;
    v_Intensity = AMBIENT + DIFFUSE_TOP * NdotL_top + DIFFUSE_FRONT * NdotL_front + specular;
}
