require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "NitroVto"
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
    "ios/VTORendererBridge.h"
  ]

  # Disable strict C++ module checking to avoid Filament header conflicts
  # Add Filament headers to search paths
  current_xcconfig = s.attributes_hash['pod_target_xcconfig'] || {}
  s.pod_target_xcconfig = current_xcconfig.merge({
    'CLANG_WARN_MODULE_CONFLICT' => 'NO',
    'HEADER_SEARCH_PATHS' => '$(inherited) "$(PODS_ROOT)/Filament/include"'
  })

  s.resource_bundles = {
    'NitroVtoAssets' => ['ios/assets/**/*']
  }

  load 'nitrogen/generated/ios/NitroVto+autolinking.rb'
  add_nitrogen_files(s)

  s.dependency 'React-jsi'
  s.dependency 'React-callinvoker'
  s.dependency 'Filament'
  install_modules_dependencies(s)
end