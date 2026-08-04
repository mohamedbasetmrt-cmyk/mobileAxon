package com.example.app_abdelbaset

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class OrbVisualizerViewLegacy(context: Context) : GLSurfaceView(context), OrbMode {

    private val renderer = LegacyRenderer()

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun setMode(t: Float) {
        renderer.targetMode = t.coerceIn(0f, 1f)
    }

    private object S {
        const val VERT = """#version 300 es
void main() {
    vec2 pos;
    if      (gl_VertexID == 0) pos = vec2(-1.0, -1.0);
    else if (gl_VertexID == 1) pos = vec2( 3.0, -1.0);
    else                       pos = vec2(-1.0,  3.0);
    gl_Position = vec4(pos, 0.0, 1.0);
}"""
        const val FRAG = """#version 300 es
precision mediump float;
uniform vec3  u_col_idle;
uniform vec3  u_col_active_a;
uniform vec3  u_col_active_b;
uniform vec3  u_col_active_c;
uniform vec3  u_col_active_d;
uniform vec3  u_col_ring;
uniform vec3  u_col_core;
uniform float u_chroma_r;
uniform float u_chroma_b;
uniform float u_time;
uniform float u_volume;
uniform float u_speed;
uniform float u_radius;
uniform float u_mode;
uniform float u_pulse;
uniform vec2  u_resolution;
out vec4 fragColor;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}
float noise(vec2 p) {
    vec2 i = floor(p); vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i),             hash(i + vec2(1,0)), u.x),
               mix(hash(i + vec2(0,1)), hash(i + vec2(1,1)), u.x), u.y);
}
float fbm(vec2 p) {
    float v = 0.0, a = 0.5;
    for (int i = 0; i < 6; i++) {
        v += a * noise(p);
        p  = p * 2.1 + vec2(1.7, 9.2);
        a *= 0.5;
    }
    return v;
}
vec3 spacePalette(float t, float time, vec3 a, vec3 b, vec3 c, vec3 d) {
    return clamp(a + b * cos(6.2832 * (c * (t + time * 0.03) + d)), 0.0, 1.0);
}

void main() {
    vec2 uv = (gl_FragCoord.xy * 2.0 - u_resolution) / min(u_resolution.x, u_resolution.y);
    float t   = u_time * u_speed;
    float vol = u_volume;

    float breath_r = u_radius + u_pulse * 0.04 * (1.0 - u_mode);
    float scale    = 1.0 - vol * 0.10 * u_mode;
    vec2  ouv      = uv * scale;

    float dist_clean = length(ouv) - (breath_r + 0.03 * sin(t * 1.8) * u_mode + vol * 0.10 * u_mode);
    float warp_mask  = clamp(1.0 - smoothstep(-0.05, 0.10, dist_clean), 0.0, 1.0);

    vec2 warp = vec2(
        fbm(ouv + vec2(t * 0.15, t * 0.12)),
        fbm(ouv + vec2(t * 0.18 + 5.2, t * 0.13))
    ) * warp_mask * (0.18 + u_mode * 0.22 + vol * 0.40 * u_mode);

    float pulse = breath_r + 0.03 * sin(t * 1.8) * u_mode + vol * 0.10 * u_mode;
    float dist  = length(ouv + warp * 0.20) - pulse - fbm((ouv + warp) * 2.0) * 0.08 * u_mode;

    float body  = 1.0 - smoothstep(-0.02, 0.04, dist);
    float glow1 = exp(-max(dist_clean, -0.03) * 9.0)  * 0.75;
    float glow2 = exp(-max(dist_clean, -0.08) * 4.5)  * 0.35;
    float glow3 = exp(-max(dist_clean,  0.0)  * 3.5)  * 0.15;

    float r_norm   = length(uv);
    float envelope = 1.0 - smoothstep(0.52, 0.72, r_norm);

    vec3 col_idle = u_col_idle + u_col_idle * 0.35 * fbm(ouv * 2.0 + t * 0.05);

    float colorT     = fbm(ouv * 1.8 + warp * 0.4 + t * 0.06);
    vec3  col_active = spacePalette(colorT, u_time, u_col_active_a, u_col_active_b, u_col_active_c, u_col_active_d);

    float ringDist = length(ouv) - breath_r;
    float rings    = 0.0;
    for (int r = 1; r <= 3; r++) {
        float rr   = float(r) * 0.06;
        float ring = exp(-abs(ringDist + rr + 0.02 * sin(t * 2.5 + float(r) * 2.1)) * 35.0);
        rings     += ring * (0.25 / float(r));
    }
    col_active = mix(col_active, u_col_ring,
                     rings * (0.5 + vol * 0.3) * warp_mask * u_mode);

    float innerDist = length(ouv + warp * 0.10) - pulse * 0.45
                    - fbm((ouv + warp) * 3.0) * 0.04;
    float innerCore = (1.0 - smoothstep(-0.05, 0.05, innerDist)) * 0.6 * u_mode;
    col_active = mix(col_active, u_col_core, innerCore * 0.5);

    vec3 col = mix(col_idle, col_active, u_mode);

    vec2  uvR   = ouv + vec2( 0.006, 0.0);
    vec2  uvB   = ouv + vec2(-0.006, 0.0);
    float glowR = exp(-max(length(uvR) - breath_r, -0.03) * 5.5) * 0.25;
    float glowB = exp(-max(length(uvB) - breath_r, -0.03) * 5.5) * 0.25;
    col.r += glowR * mix(0.40, u_chroma_r, u_mode);
    col.b += glowB * mix(0.10, u_chroma_b, u_mode);

    float rim = smoothstep(0.0, 0.06, dist_clean) * smoothstep(0.15, 0.02, dist_clean);
    col += mix(vec3(1.0, 0.3, 0.2), vec3(0.2, 0.6, 1.0), u_mode)
         * rim * (0.5 + vol * 0.5 * u_mode);

    float hl = (1.0 - smoothstep(-0.10, 0.0, dist))
             * smoothstep(0.0, 0.30, length(ouv - vec2(-0.08, 0.12)));
    col += mix(vec3(1.0, 0.6, 0.4), vec3(0.4, 1.0, 1.0), u_mode) * hl * 0.20;

    col = mix(col, vec3(0.1, 0.5, 1.0), vol * 0.25 * body * u_mode);

    float alpha_raw   = clamp(body + glow1 * 0.85 + glow2 * 0.40 + glow3, 0.0, 1.0);
    float alpha_final = alpha_raw * envelope;

    vec3 out_col = col * clamp(body + glow1 * 0.80 + glow2 * 0.35 + glow3 * 0.8, 0.0, 1.0);
    out_col      = pow(clamp(out_col, 0.0, 1.3), vec3(0.88));

    fragColor = vec4(out_col, alpha_final);
}"""
    }

    inner class LegacyRenderer : Renderer {
        private var prog = 0
        private val vao  = IntArray(1)
        private var t0   = 0L
        private var uTime=-1; private var uVol=-1; private var uSpd=-1
        private var uRad=-1;  private var uMode=-1; private var uPulse=-1; private var uRes=-1
        private var uColIdle = -1; private var uColActA = -1; private var uColActB = -1
        private var uColActC = -1; private var uColActD = -1; private var uColRing  = -1
        private var uColCore = -1; private var uChromaR = -1; private var uChromaB  = -1
        @Volatile var targetMode = 0f
        private var modeS=0f; private var pPhase=0f
        private var sw=1f;    private var sh=1f

        override fun onSurfaceCreated(gl: GL10?, c: EGLConfig?) {
            t0 = System.nanoTime()
            fun compileShader(type: Int, src: String): Int {
                val s = GLES30.glCreateShader(type)
                GLES30.glShaderSource(s, src)
                GLES30.glCompileShader(s)
                return s
            }
            prog = GLES30.glCreateProgram().also { p ->
                GLES30.glAttachShader(p, compileShader(GLES30.GL_VERTEX_SHADER,   S.VERT))
                GLES30.glAttachShader(p, compileShader(GLES30.GL_FRAGMENT_SHADER, S.FRAG))
                GLES30.glLinkProgram(p)
            }
            GLES30.glGenVertexArrays(1, vao, 0)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            uTime  = GLES30.glGetUniformLocation(prog, "u_time")
            uVol   = GLES30.glGetUniformLocation(prog, "u_volume")
            uSpd   = GLES30.glGetUniformLocation(prog, "u_speed")
            uRad   = GLES30.glGetUniformLocation(prog, "u_radius")
            uMode  = GLES30.glGetUniformLocation(prog, "u_mode")
            uPulse = GLES30.glGetUniformLocation(prog, "u_pulse")
            uRes   = GLES30.glGetUniformLocation(prog, "u_resolution")
            val uColIdle    = GLES30.glGetUniformLocation(prog, "u_col_idle")
            val uColActA    = GLES30.glGetUniformLocation(prog, "u_col_active_a")
            val uColActB    = GLES30.glGetUniformLocation(prog, "u_col_active_b")
            val uColActC    = GLES30.glGetUniformLocation(prog, "u_col_active_c")
            val uColActD    = GLES30.glGetUniformLocation(prog, "u_col_active_d")
            val uColRing    = GLES30.glGetUniformLocation(prog, "u_col_ring")
            val uColCore    = GLES30.glGetUniformLocation(prog, "u_col_core")
            val uChromaR    = GLES30.glGetUniformLocation(prog, "u_chroma_r")
            val uChromaB    = GLES30.glGetUniformLocation(prog, "u_chroma_b")

        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            GLES30.glViewport(0, 0, w, h)
            sw = w.toFloat(); sh = h.toFloat()
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glUseProgram(prog)
            val t = (System.nanoTime() - t0) / 1_000_000_000f
            modeS  += (targetMode - modeS) * 0.15f
            pPhase  = (t * 0.9f) % (2f * Math.PI.toFloat())
            val pv  = 0.5f + 0.5f * kotlin.math.sin(pPhase)
            val rad = 0.30f + (0.40f - 0.30f) * modeS
            val vs  = VisualizerState
            val vol = if (vs.isActive()) vs.getAudioLevel() else 0f
            GLES30.glUniform1f(uTime,  t)
            GLES30.glUniform1f(uVol,   vol)
            GLES30.glUniform1f(uSpd,   1f)
            GLES30.glUniform1f(uRad,   rad)
            GLES30.glUniform1f(uMode,  modeS)
            GLES30.glUniform1f(uPulse, pv)
            GLES30.glUniform2f(uRes,   sw, sh)
            GLES30.glBindVertexArray(vao[0])
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            GLES30.glUniform3f(uColIdle,  0.85f, 0.18f, 0.12f)
            GLES30.glUniform3f(uColActA,  0.08f, 0.45f, 0.70f)
            GLES30.glUniform3f(uColActB,  0.10f, 0.25f, 0.35f)
            GLES30.glUniform3f(uColActC,  1.00f, 0.90f, 0.60f)
            GLES30.glUniform3f(uColActD,  0.05f, 0.15f, 0.50f)
            GLES30.glUniform3f(uColRing,  0.30f, 0.90f, 1.00f)
            GLES30.glUniform3f(uColCore,  0.60f, 1.00f, 1.00f)
            GLES30.glUniform1f(uChromaR,  0.30f)
            GLES30.glUniform1f(uChromaB,  0.50f)
        }
    }
}