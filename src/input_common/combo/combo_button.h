// Copyright 2026 Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#pragma once

#include <string>
#include <vector>
#include "common/param_package.h"

namespace InputCommon::Combo {

/// Registers the "combo" button factory. Call once during input backend init,
/// alongside where the "sdl"/"keyboard"/etc factories are registered.
void Init();

/// Unregisters the "combo" button factory. Call during input backend shutdown.
void Shutdown();

/**
 * Builds a serialized ParamPackage string representing a combo of buttons.
 * All sub_bindings must themselves be valid serialized button ParamPackage
 * strings (e.g. as produced by any existing engine's polling/binding code).
 * The resulting device only reports "pressed" when every sub-binding is
 * pressed simultaneously.
 */
std::string BuildComboParam(const std::vector<std::string>& sub_bindings);

} // namespace InputCommon::Combo
