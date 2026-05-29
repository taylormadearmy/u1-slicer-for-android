#version 300 es
precision mediump float;

in vec4 v_Color;
in vec3 v_Intensity;
out vec4 fragColor;

// F66 — selection highlight. u_Highlight.a = 0 means "no highlight" and the
// fragment is unchanged. .a > 0 mixes the per-vertex/uniform colour toward
// u_Highlight.rgb so the highlight is visible even on per-vertex-coloured
// meshes (3MF / painted / multi-extruder), where v_Color comes from a_Color
// instead of u_Color and the old u_Color override approach was a no-op.
uniform vec4 u_Highlight;

void main() {
    vec3 base = v_Color.rgb * v_Intensity;
    vec3 tinted = mix(base, u_Highlight.rgb * v_Intensity, u_Highlight.a);
    fragColor = vec4(max(tinted, vec3(0.12)), v_Color.a);
}
