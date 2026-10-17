package com.markopacak.divelens.correction

/**
 * GLSL ES 3.0 source of the correction. The fragment shader is the one change 5 reuses for video.
 * It must stay equivalent to `CpuCorrection.applyInPlace` in `:core:analysis`: interpolate the
 * shift maps at the pixel, `shift = a·grey + b`, then add at the strength and clamp.
 */
internal object CorrectionShader {

    /** Full-viewport triangle; no vertex attributes. */
    const val VERTEX = """#version 300 es
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

    /** Reads the source pixel and the map texels exactly (texelFetch, no filtering). */
    const val FRAGMENT = """#version 300 es
precision highp float;
precision highp int;
uniform highp sampler2D uSource;
uniform highp sampler2D uMapA;
uniform highp sampler2D uMapB;
uniform ivec2 uOrigin;
uniform vec2 uImageSize;
uniform ivec2 uMapSize;
uniform float uStrength;
out vec4 fragColor;

vec3 bilinear(highp sampler2D m, ivec2 p0, ivec2 p1, vec2 f) {
    vec3 top = mix(texelFetch(m, ivec2(p0.x, p0.y), 0).rgb, texelFetch(m, ivec2(p1.x, p0.y), 0).rgb, f.x);
    vec3 bottom = mix(texelFetch(m, ivec2(p0.x, p1.y), 0).rgb, texelFetch(m, ivec2(p1.x, p1.y), 0).rgb, f.x);
    return mix(top, bottom, f.y);
}

void main() {
    ivec2 local = ivec2(gl_FragCoord.xy);
    vec4 c = texelFetch(uSource, local, 0);
    vec2 mapSize = vec2(uMapSize);
    vec2 u = clamp((vec2(local + uOrigin) + 0.5) * mapSize / uImageSize - 0.5, vec2(0.0), mapSize - 1.0);
    ivec2 p0 = ivec2(floor(u));
    ivec2 p1 = min(p0 + 1, uMapSize - 1);
    vec2 f = u - vec2(p0);
    float grey = (c.r + c.g + c.b) / 3.0;
    vec3 shift = bilinear(uMapA, p0, p1, f) * grey + bilinear(uMapB, p0, p1, f);
    fragColor = vec4(clamp(c.rgb + uStrength * shift, 0.0, 1.0), c.a);
}
"""
}
