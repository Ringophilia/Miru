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

//!DESC FSR 1.0 Robust Contrast Adaptive Sharpening (RGB, denoise)
//!HOOK MAIN
//!BIND HOOKED

vec4 hook() {
    vec3 b = HOOKED_texOff(vec2(0.0, -1.0)).rgb;
    vec3 d = HOOKED_texOff(vec2(-1.0, 0.0)).rgb;
    vec3 e = HOOKED_tex(HOOKED_pos).rgb;
    vec3 f = HOOKED_texOff(vec2(1.0, 0.0)).rgb;
    vec3 h = HOOKED_texOff(vec2(0.0, 1.0)).rgb;
    vec3 luma = vec3(0.5, 1.0, 0.5);
    float bL = dot(b, luma), dL = dot(d, luma), eL = dot(e, luma);
    float fL = dot(f, luma), hL = dot(h, luma);
    float high = max(max(max(bL, dL), max(fL, hL)), eL);
    float low = min(min(min(bL, dL), min(fL, hL)), eL);
    float noise = abs(0.25 * (bL+dL+fL+hL) - eL) / max(high-low, 0.00001);
    noise = 1.0 - 0.5 * clamp(noise, 0.0, 1.0);
    vec3 mn = min(min(b, d), min(f, h));
    vec3 mx = max(max(b, d), max(f, h));
    vec3 hitMin = min(mn, e) / max(4.0 * mx, vec3(0.00001));
    vec3 hitMax = (vec3(1.0) - max(mx, e)) / min(4.0 * mn - vec3(4.0), vec3(-0.00001));
    vec3 lobes = max(-hitMin, hitMax);
    float lobe = max(-0.1875, min(max(max(lobes.r, lobes.g), lobes.b), 0.0));
    // FsrRcasCon sharpness = 0.5 stops (exp2(-0.5)).
    lobe *= 0.7071067811865476 * noise;
    vec3 result = (lobe * (b + d + f + h) + e) / (4.0 * lobe + 1.0);
    return vec4(clamp(result, vec3(0.0), vec3(1.0)), 1.0);
}
