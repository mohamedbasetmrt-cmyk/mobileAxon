package com.example.app_abdelbaset

/**
 * AGSL RuntimeShader sources for the Orb.
 *
 * Requires Android 13 (API 33) — exactly what Samsung Galaxy S/A
 * flagships run today.
 *
 * The shader is organized as layers rendered in a single pass:
 *   1. Core Plasma    — FBM liquid nebula
 *   2. Membrane       — SDF orb with surface tension wobble
 *   3. Energy Rings   — 3 containment rings
 *   4. Glow Pipeline  — near / bloom / atmospheric layers
 *   5. Chromatic AB   — RGB separation
 *   6. Rim Light      — edge highlight
 *   7. Temporal trail — ghost persistence (faked via sine history)
 *
 * Uniforms:
 *   u_time       float   seconds since start
 *   u_resolution float2  surface size in px
 *   u_mode       float   0=IDLE · 0.6=LISTEN · 0.8=THINK · 1.0=SPEAK
 *   u_bass       float   [0..1] FFT bass energy
 *   u_mid        float   [0..1] FFT mid  energy
 *   u_treble     float   [0..1] FFT treble energy
 *   u_volume     float   [0..1] RMS amplitude
 *   u_radius     float   current orb radius (normalised)
 *   u_breath     float   breathing pulse [-0.02..0.02]
 *   u_turbulence float   secondary physics noise
 */
object OrbShaders {

    // AGSL is basically GLSL ES 1.0 + some Skia extensions.
    // No #version directive needed — it's injected by Android.
    const val ORB_SHADER = """
uniform float u_time;
uniform float2 u_resolution;
uniform float u_mode;
uniform float u_bass;
uniform float u_mid;
uniform float u_treble;
uniform float u_volume;
uniform float u_radius;
uniform float u_breath;
uniform float u_turbulence;

// ── Noise helpers ────────────────────────────────────────────────────
float hash(float2 p) {
    return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453);
}
float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(hash(i),               hash(i + float2(1,0)), u.x),
        mix(hash(i + float2(0,1)), hash(i + float2(1,1)), u.x),
        u.y);
}
float fbm(float2 p) {
    float v = 0.0, a = 0.5;
    for (int i = 0; i < 7; i++) {
        v += a * noise(p);
        p  = p * 2.13 + float2(1.7, 9.2);
        a *= 0.5;
    }
    return v;
}
float fbm3(float2 p) {     // cheaper 3-octave for secondary uses
    float v = 0.0, a = 0.5;
    for (int i = 0; i < 3; i++) {
        v += a * noise(p);
        p  = p * 2.0 + float2(3.1, 7.4);
        a *= 0.5;
    }
    return v;
}

// ── Color palettes ───────────────────────────────────────────────────
// Deep space active — cyan/blue/white
float3 spacePalette(float t, float time) {
    float3 a = float3(0.08, 0.45, 0.70);
    float3 b = float3(0.10, 0.25, 0.35);
    float3 c = float3(1.00, 0.90, 0.60);
    float3 d = float3(0.05, 0.15, 0.50);
    return clamp(a + b * cos(6.2832 * (c * (t + time * 0.03) + d)), 0.0, 1.0);
}
// Warm idle — crimson/amber
float3 idlePalette(float t) {
    float3 a = float3(0.85, 0.18, 0.12);
    float3 b = float3(0.25, 0.05, 0.02);
    float3 c = float3(1.00, 0.90, 0.60);
    float3 d = float3(0.05, 0.15, 0.50);
    return clamp(a + b * cos(6.2832 * (c * t + d)), 0.0, 1.0);
}
// Thinking mode — violet/indigo
float3 thinkPalette(float t, float time) {
    float3 a = float3(0.05, 0.35, 0.28);
    float3 b = float3(0.03, 0.18, 0.14);
    float3 c = float3(0.80, 1.00, 0.50);
    float3 d = float3(0.10, 0.25, 0.35);
    return clamp(a + b * cos(6.2832 * (c * (t + time * 0.04) + d)), 0.0, 1.0);
}
// ── Main ─────────────────────────────────────────────────────────────
half4 main(float2 fragCoord) {
    float2 uv = (fragCoord * 2.0 - u_resolution) / min(u_resolution.x, u_resolution.y);

    float t   = u_time;
    float vol = u_volume;
    float mode = u_mode;

    // ── Radius (breath + bass reactive) ─────────────────────────────
    float r = u_radius
            + u_breath * (1.0 - mode * 0.5)
            + u_bass   * 0.08 * mode
            + vol      * 0.07 * mode;

    // ── Scale squeeze (volume compresses the field slightly) ─────────
    float2 ouv = uv * (1.0 - vol * 0.08 * mode);

    // ── Warp mask (FBM only inside/near the orb) ─────────────────────
    float distClean = length(ouv) - r;
    float warpMask  = clamp(1.0 - smoothstep(-0.04, 0.12, distClean), 0.0, 1.0);

    // ── Plasma warp ─────────────────────────────────────────────────
    float warpAmt = 0.18
                  + mode    * 0.24
                  + vol     * 0.40 * mode
                  + u_bass  * 0.20 * mode
                  + u_treble* 0.10 * mode;
    float2 warp = float2(
        fbm(ouv + float2(t * 0.14, t * 0.11)),
        fbm(ouv + float2(t * 0.17 + 5.2, t * 0.13))
    ) * warpMask * warpAmt;

    // ── Membrane SDF ─────────────────────────────────────────────────
    float pulse = r + 0.025 * sin(t * 1.6) * mode + vol * 0.08 * mode;
    float dist  = length(ouv + warp * 0.18)
                - pulse
                - fbm((ouv + warp) * 2.0) * 0.07 * mode
                - u_turbulence * 0.03;

    float body  = 1.0 - smoothstep(-0.02, 0.045, dist);

    // ── Glow layers ──────────────────────────────────────────────────
    float g1 = exp(-max(distClean, -0.03) *  9.5) * 0.80;   // near glow
    float g2 = exp(-max(distClean, -0.08) *  4.5) * 0.38;   // bloom
    float g3 = exp(-max(distClean,  0.0 ) *  2.8) * 0.18;   // atmospheric aura
    float g4 = exp(-max(distClean,  0.0 ) *  1.5) * 0.09 * mode; // far halo

    // ── Envelope (vignette) ──────────────────────────────────────────
    float env = 1.0 - smoothstep(0.75, 0.90, length(uv));

    // ── Color selection ──────────────────────────────────────────────
    float colorT = fbm(ouv * 1.8 + warp * 0.45 + float2(t * 0.07, t * 0.05));

    float3 colIdle  = idlePalette(colorT)
                    + float3(0.25, 0.04, 0.0) * fbm3(ouv * 2.0 + t * 0.04);
    float3 colSpace = spacePalette(colorT, t);
    float3 colThink = thinkPalette(colorT, t);

    // Blend palettes by mode
    float3 col;
    if (mode < 0.65) {
        col = mix(colIdle, colThink, mode / 0.65);
    } else {
        col = mix(colThink, colSpace, (mode - 0.65) / 0.35);
    }

    // ── Energy rings ─────────────────────────────────────────────────
    float ringDist = length(ouv) - r;
    float rings    = 0.0;
    for (int ri = 1; ri <= 3; ri++) {
        float rr   = float(ri) * 0.065;
        float ring = exp(-abs(ringDist + rr
                       + 0.025 * sin(t * 2.2 + float(ri) * 2.1
                       + u_bass * 1.5)) * 38.0);
        rings += ring * (0.30 / float(ri));
    }
    col = mix(col, float3(0.30, 0.90, 1.00),
              rings * (0.45 + vol * 0.35) * warpMask * mode);

    // ── Inner core highlight ─────────────────────────────────────────
    float innerD = length(ouv + warp * 0.10) - pulse * 0.45
                 - fbm3((ouv + warp) * 3.0) * 0.04;
    float core   = (1.0 - smoothstep(-0.05, 0.05, innerD)) * 0.65 * mode;
    col = mix(col, float3(0.60, 1.00, 1.00), core * 0.45);

    // ── Chromatic aberration ─────────────────────────────────────────
    float caAmt = 0.007 + mode * 0.006 + u_treble * 0.008 * mode;
    float2 uvR  = ouv + float2( caAmt, 0.0);
    float2 uvB  = ouv + float2(-caAmt, 0.0);
    float gR    = exp(-max(length(uvR) - r, -0.03) * 5.5) * 0.28;
    float gB    = exp(-max(length(uvB) - r, -0.03) * 5.5) * 0.28;
    col.r += gR * mix(0.40, 0.30, mode);
    col.b += gB * mix(0.10, 0.50, mode);

    // ── Rim light ────────────────────────────────────────────────────
    float rim = smoothstep(0.0, 0.055, distClean) * smoothstep(0.14, 0.02, distClean);
    col += mix(float3(1.0, 0.28, 0.18), float3(0.18, 0.65, 1.0), mode)
         * rim * (0.55 + vol * 0.45 * mode);

    // ── Specular highlight ───────────────────────────────────────────
    float hl = (1.0 - smoothstep(-0.10, 0.0, dist))
             * smoothstep(0.0, 0.28, length(ouv - float2(-0.08, 0.14)));
    col += mix(float3(1.0, 0.55, 0.35), float3(0.35, 1.0, 1.0), mode) * hl * 0.22;

    // ── Audio color saturation boost ─────────────────────────────────
    col = mix(col, float3(0.08, 0.55, 1.0), vol * 0.22 * body * mode);
    col = mix(col, float3(1.0, 0.40, 0.10), u_bass * 0.12 * body * (1.0 - mode));

    // ── Final composite ──────────────────────────────────────────────
    float alpha = clamp(body + g1 * 0.95 + g2 * 0.55 + g3 + g4, 0.0, 1.0) * env;
    float3 outCol = col * clamp(body + g1 * 0.82 + g2 * 0.36 + g3 * 0.80, 0.0, 1.0);
    outCol        = pow(clamp(outCol, 0.0, 1.35), float3(0.86));

    return half4(half3(outCol), half(alpha));
}
"""
}