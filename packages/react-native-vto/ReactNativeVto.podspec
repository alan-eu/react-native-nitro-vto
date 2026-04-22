require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "ReactNativeVto"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = package["homepage"]
  s.license      = package["license"]
  s.authors      = package["author"]

  s.platforms    = { :ios => "15.1" }
  s.source       = { :git => package["repository"]["url"], :tag => "#{s.version}" }

  s.source_files = [
    # Implementation (Swift)
    "ios/**/*.{swift}",
    # Implementation files only (Objective-C++)
    "ios/**/*.{mm}",
    # Headers
    "ios/**/*.h",
  ]

  s.public_header_files = [
    # Must be public so CocoaPods adds it to the pod's module map, making the
    # ObjC++ `VTORendererBridge` class visible to the bundled `VtoView.swift`.
    # Pattern mirrors `NitroVto.podspec`.
    "ios/VTORendererBridge.h",
    "ios/VtoBridgeView.h",
  ]

  # Disable strict C++ module checking to avoid Filament header conflicts
  # Add Filament headers to search paths
  current_xcconfig = s.attributes_hash['pod_target_xcconfig'] || {}
  s.pod_target_xcconfig = current_xcconfig.merge({
    'CLANG_WARN_MODULE_CONFLICT' => 'NO',
    'HEADER_SEARCH_PATHS' => '$(inherited) "$(PODS_ROOT)/Filament/include"',
    # Enables C++ <-> Swift interop
    'SWIFT_OBJC_INTEROP_MODE' => 'objcxx',
    'DEFINES_MODULE' => 'YES',
    'CLANG_CXX_LANGUAGE_STANDARD' => 'c++20',
  })

  s.resource_bundles = {
    # Compiled Filament artifacts only — sources (.mat, .hdr) live in
    # packages/vto-core-native/ and are bundled into ios/assets/ on install.
    'ReactNativeVtoAssets' => ['ios/assets/**/*.{filamat,ktx,txt}']
  }

  s.dependency 'React-Core'
  s.dependency 'Filament', '1.69.3'
end
