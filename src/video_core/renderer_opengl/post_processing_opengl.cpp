// Copyright 2016 Citra Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#include <string>
#include <vector>
#include "video_core/post_processing_shader_loader.h"
#include "video_core/renderer_opengl/post_processing_opengl.h"

namespace OpenGL {

// The Dolphin shader header is added here for drop-in compatibility with most
// of Dolphin's "glsl" shaders, which use hlsl types, hence the #define's below
// It's fairly complete, but the features it's missing are:
// The font texture for the ascii shader (Citra doesn't have an overlay font)
// GetTime (not used in any shader provided by Dolphin)
// GetOption* (used in only one shader provided by Dolphin; would require more
// configuration/frontend work)
constexpr char dolphin_shader_header[] = R"(

// hlsl to glsl types
#define float2 vec2
#define float3 vec3
#define float4 vec4
#define uint2 uvec2
#define uint3 uvec3
#define uint4 uvec4
#define int2 ivec2
#define int3 ivec3
#define int4 ivec4

// hlsl to glsl function translation
#define frac fract
#define lerp mix

// Output variable
layout (location = 0) out float4 color;
// Input coordinates
layout (location = 0) in float2 frag_tex_coord;
// Resolution
uniform float4 i_resolution;
uniform float4 o_resolution;
// Layer
uniform int layer;

uniform sampler2D color_texture;
uniform sampler2D color_texture_r;

// Interfacing functions
float4 Sample()
{
    return texture(color_texture, frag_tex_coord);
}

float4 SampleLocation(float2 location)
{
    return texture(color_texture, location);
}

float4 SampleLayer(int layer)
{
    if(layer == 0)
        return texture(color_texture, frag_tex_coord);
    else
        return texture(color_texture_r, frag_tex_coord);
}

#define SampleOffset(offset) textureOffset(color_texture, frag_tex_coord, offset)

float2 GetResolution()
{
    return i_resolution.xy;
}

float2 GetInvResolution()
{
    return i_resolution.zw;
}

float2 GetIResolution()
{
    return i_resolution.xy;
}

float2 GetIInvResolution()
{
    return i_resolution.zw;
}

float2 GetWindowResolution()
{
  return o_resolution.xy;
}

float2 GetInvWindowResolution()
{
  return o_resolution.zw;
}

float2 GetOResolution()
{
    return o_resolution.xy;
}

float2 GetOInvResolution()
{
    return o_resolution.zw;
}

float2 GetCoordinates()
{
    return frag_tex_coord;
}

void SetOutput(float4 color_in)
{
    color = color_in;
}

)";

std::vector<std::string> GetPostProcessingShaderList(bool anaglyph) {
    return VideoCore::GetPostProcessingShaderList(anaglyph);
}

std::string GetPostProcessingShaderCode(bool anaglyph, std::string_view shader) {
    const std::string shader_text =
        VideoCore::GetPostProcessingShaderCodeRaw(anaglyph, shader);
    if (shader_text.empty()) {
        return "";
    }
    return dolphin_shader_header + shader_text;
}

} // namespace OpenGL
