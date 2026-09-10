// Copyright 2026 Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#include <memory>
#include <utility>
#include "common/logging/log.h"
#include "common/param_package.h"
#include "core/frontend/input.h"
#include "input_common/combo/combo_button.h"

namespace InputCommon::Combo {

namespace {

constexpr char ENGINE_NAME[] = "combo";

/// A button device that is "pressed" only when all of its sub-devices are pressed.
class ComboButton final : public Input::ButtonDevice {
public:
    explicit ComboButton(std::vector<std::unique_ptr<Input::ButtonDevice>> buttons_)
        : buttons(std::move(buttons_)) {}

    bool GetStatus() const override {
        if (buttons.empty()) {
            return false;
        }
        for (const auto& button : buttons) {
            if (!button || !button->GetStatus()) {
                return false;
            }
        }
        return true;
    }

private:
    std::vector<std::unique_ptr<Input::ButtonDevice>> buttons;
};

class ComboButtonFactory final : public Input::Factory<Input::ButtonDevice> {
public:
    std::unique_ptr<Input::ButtonDevice> Create(const Common::ParamPackage& params) override {
        const int button_count = params.Get("buttons", 0);
        if (button_count <= 0) {
            LOG_ERROR(Input, "combo device created with no sub-bindings");
            return std::make_unique<Input::ButtonDevice>();
        }

        std::vector<std::unique_ptr<Input::ButtonDevice>> buttons;
        buttons.reserve(static_cast<size_t>(button_count));
        for (int i = 0; i < button_count; ++i) {
            const std::string key = "button" + std::to_string(i);
            const std::string serialized_sub = params.Get(key, "");
            if (serialized_sub.empty()) {
                LOG_ERROR(Input, "combo device missing sub-binding {}", key);
                continue;
            }
            buttons.push_back(Input::CreateDevice<Input::ButtonDevice>(serialized_sub));
        }
        return std::make_unique<ComboButton>(std::move(buttons));
    }
};

} // namespace

void Init() {
    Input::RegisterFactory<Input::ButtonDevice>(ENGINE_NAME, std::make_shared<ComboButtonFactory>());
}

void Shutdown() {
    Input::UnregisterFactory<Input::ButtonDevice>(ENGINE_NAME);
}

std::string BuildComboParam(const std::vector<std::string>& sub_bindings) {
    Common::ParamPackage package;
    package.Set("engine", ENGINE_NAME);
    package.Set("buttons", static_cast<int>(sub_bindings.size()));
    for (size_t i = 0; i < sub_bindings.size(); ++i) {
        package.Set("button" + std::to_string(i), sub_bindings[i]);
    }
    return package.Serialize();
}

} // namespace InputCommon::Combo
