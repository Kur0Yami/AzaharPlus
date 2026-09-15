// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#pragma once

#include <string>
#include <string_view>
#include <vector>

namespace VideoCore {

// Returns a vector of the names of the shaders available in the "shaders" (or
// "shaders/anaglyph") directory in the emulator's user data directory. Backend-agnostic --
// used by both the OpenGL and Vulkan post processing shader pickers.
std::vector<std::string> GetPostProcessingShaderList(bool anaglyph);

// Returns the *raw* file contents of the shader named "shader_name", with no backend-specific
// header prepended. Each backend (OpenGL/Vulkan) is responsible for prepending its own
// compatibility header before compiling this. If the shader cannot be found/loaded, an empty
// string is returned.
std::string GetPostProcessingShaderCodeRaw(bool anaglyph, std::string_view shader_name);

} // namespace VideoCore
