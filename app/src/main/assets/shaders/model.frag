#version 300 es
precision mediump float;

in vec4 v_Color;
in vec3 v_Intensity;
out vec4 fragColor;

// F66 — outline pass output. When `u_OutlineExpand > 0` (set by the
// outline-pass call in ModelRenderer.drawObjectOutline) the fragment
// returns a flat unlit `u_OutlineColor` so the silhouette is uniformly
// coloured — independent of the underlying surface tint, which means
// the outline shows up clearly on multi-colour models too.
uniform float u_OutlineExpand;
uniform vec4 u_OutlineColor;

// Kept for source-compat with renderer code that still sets u_Highlight
// (e.g. on the wipe tower). Adds a tint mix when alpha > 0. Selection
// highlight uses the dedicated outline pass instead of this uniform now.
uniform vec4 u_Highlight;

void main() {
    if (u_OutlineExpand > 0.0) {
        fragColor = u_OutlineColor;
        return;
    }
    vec3 base = v_Color.rgb * v_Intensity;
    vec3 tinted = mix(base, u_Highlight.rgb * v_Intensity, u_Highlight.a);
    fragColor = vec4(max(tinted, vec3(0.12)), v_Color.a);
}
