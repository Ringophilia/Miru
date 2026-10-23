// FidelityFX Super Resolution Sample
//
// Copyright (c) 2021 Advanced Micro Devices, Inc. All rights reserved.
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files(the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and / or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions :
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
// THE SOFTWARE.

// Scalar RGB port of AMD FSR1 EASU/RCAS; exact reciprocals replace GPU approximations.
// Upstream: https://github.com/GPUOpen-Effects/FidelityFX-FSR/blob/master/ffx-fsr/ffx_fsr1.h

//!DESC FSR 1.0 Edge Adaptive Spatial Upsampling (RGB)
//!HOOK MAIN
//!BIND HOOKED
//!WIDTH OUTPUT.w
//!HEIGHT OUTPUT.h

vec3 edge(float a, float b, float c, float d, float e) {
    vec2 dir = vec2(d - b, e - a);
    vec2 len = clamp(abs(dir) / max(vec2(max(abs(d-c), abs(c-b)), max(abs(e-c), abs(c-a))), vec2(0.00001)), vec2(0.0), vec2(1.0));
    return vec3(dir, dot(len, len));
}

vec4 tap(vec2 off, vec2 dir, vec2 len, float lob, float clp, vec3 c) {
    vec2 v = vec2(dot(off, dir), dot(off, vec2(-dir.y, dir.x))) * len;
    float d2 = min(dot(v, v), clp);
    float wb = 0.4 * d2 - 1.0;
    float wa = lob * d2 - 1.0;
    wb = 1.5625 * wb * wb - 0.5625;
    float w = wb * wa * wa;
    return vec4(c * w, w);
}

vec4 hook() {
    vec2 sourcePos = HOOKED_pos * HOOKED_size - vec2(0.5);
    vec2 fp = floor(sourcePos);
    vec2 pp = sourcePos - fp;
    vec3 b = HOOKED_tex((fp + vec2(0.0, -1.0) + vec2(0.5)) * HOOKED_pt).rgb;
    float bL = dot(b, vec3(0.5, 1.0, 0.5));
    vec3 c = HOOKED_tex((fp + vec2(1.0, -1.0) + vec2(0.5)) * HOOKED_pt).rgb;
    float cL = dot(c, vec3(0.5, 1.0, 0.5));
    vec3 e = HOOKED_tex((fp + vec2(-1.0, 0.0) + vec2(0.5)) * HOOKED_pt).rgb;
    float eL = dot(e, vec3(0.5, 1.0, 0.5));
    vec3 f = HOOKED_tex((fp + vec2(0.0, 0.0) + vec2(0.5)) * HOOKED_pt).rgb;
    float fL = dot(f, vec3(0.5, 1.0, 0.5));
    vec3 g = HOOKED_tex((fp + vec2(1.0, 0.0) + vec2(0.5)) * HOOKED_pt).rgb;
    float gL = dot(g, vec3(0.5, 1.0, 0.5));
    vec3 h = HOOKED_tex((fp + vec2(2.0, 0.0) + vec2(0.5)) * HOOKED_pt).rgb;
    float hL = dot(h, vec3(0.5, 1.0, 0.5));
    vec3 i = HOOKED_tex((fp + vec2(-1.0, 1.0) + vec2(0.5)) * HOOKED_pt).rgb;
    float iL = dot(i, vec3(0.5, 1.0, 0.5));
    vec3 j = HOOKED_tex((fp + vec2(0.0, 1.0) + vec2(0.5)) * HOOKED_pt).rgb;
    float jL = dot(j, vec3(0.5, 1.0, 0.5));
    vec3 k = HOOKED_tex((fp + vec2(1.0, 1.0) + vec2(0.5)) * HOOKED_pt).rgb;
    float kL = dot(k, vec3(0.5, 1.0, 0.5));
    vec3 l = HOOKED_tex((fp + vec2(2.0, 1.0) + vec2(0.5)) * HOOKED_pt).rgb;
    float lL = dot(l, vec3(0.5, 1.0, 0.5));
    vec3 n = HOOKED_tex((fp + vec2(0.0, 2.0) + vec2(0.5)) * HOOKED_pt).rgb;
    float nL = dot(n, vec3(0.5, 1.0, 0.5));
    vec3 o = HOOKED_tex((fp + vec2(1.0, 2.0) + vec2(0.5)) * HOOKED_pt).rgb;
    float oL = dot(o, vec3(0.5, 1.0, 0.5));
    vec3 accum = edge(bL, eL, fL, gL, jL) * ((1.0-pp.x) * (1.0-pp.y))
               + edge(cL, fL, gL, hL, kL) * (pp.x * (1.0-pp.y))
               + edge(fL, iL, jL, kL, nL) * ((1.0-pp.x) * pp.y)
               + edge(gL, jL, kL, lL, oL) * (pp.x * pp.y);
    vec2 dir = accum.xy;
    float dirR = dot(dir, dir);
    dir = dirR < 1.0/32768.0 ? vec2(1.0, 0.0) : dir * inversesqrt(dirR);
    float len = accum.z * 0.5;
    len *= len;
    float stretch = dot(dir, dir) / max(abs(dir.x), abs(dir.y));
    vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);
    float lob = 0.5 + (0.21 - 0.5) * len;
    float clp = 1.0 / lob;
    vec4 color = vec4(0.0);
    color += tap(vec2(0.0, -1.0) - pp, dir, len2, lob, clp, b);
    color += tap(vec2(1.0, -1.0) - pp, dir, len2, lob, clp, c);
    color += tap(vec2(-1.0, 0.0) - pp, dir, len2, lob, clp, e);
    color += tap(vec2(0.0, 0.0) - pp, dir, len2, lob, clp, f);
    color += tap(vec2(1.0, 0.0) - pp, dir, len2, lob, clp, g);
    color += tap(vec2(2.0, 0.0) - pp, dir, len2, lob, clp, h);
    color += tap(vec2(-1.0, 1.0) - pp, dir, len2, lob, clp, i);
    color += tap(vec2(0.0, 1.0) - pp, dir, len2, lob, clp, j);
    color += tap(vec2(1.0, 1.0) - pp, dir, len2, lob, clp, k);
    color += tap(vec2(2.0, 1.0) - pp, dir, len2, lob, clp, l);
    color += tap(vec2(0.0, 2.0) - pp, dir, len2, lob, clp, n);
    color += tap(vec2(1.0, 2.0) - pp, dir, len2, lob, clp, o);
    vec3 lo = min(min(f, g), min(j, k));
    vec3 hi = max(max(f, g), max(j, k));
    return vec4(clamp(color.rgb / max(color.a, 0.00001), lo, hi), 1.0);
}
