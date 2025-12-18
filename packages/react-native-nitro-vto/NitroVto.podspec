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
    # C++ headers and implementation
    "ios/**/*.hpp",
    "cpp/**/*.{hpp,cpp}",
  ]
  
  # Preserve paths to headers but don't include them in source_files
  # This prevents them from being treated as public by default
  s.preserve_paths = "ios/**/*.h"

  load 'nitrogen/generated/ios/NitroVto+autolinking.rb'
  add_nitrogen_files(s)
  
  # Only VTORendererBridge should be public for Swift access
  current_public_headers = Array(s.attributes_hash['public_header_files'])
  s.public_header_files = current_public_headers + ["ios/VTORendererBridge.h"]
  
  # Disable strict C++ module checking to avoid Filament header conflicts
  # Add Filament headers to search paths
  current_xcconfig = s.attributes_hash['pod_target_xcconfig'] || {}
  s.pod_target_xcconfig = current_xcconfig.merge({
    'CLANG_WARN_MODULE_CONFLICT' => 'NO',
    'HEADER_SEARCH_PATHS' => '$(inherited) "$(PODS_ROOT)/Filament/include"'
  })

  s.resource_bundles = {
    "NitroVtoAssets" => ["ios/assets/**/*.{filamat,ktx}"]
  }

  # iOS frameworks
  s.frameworks = "ARKit", "Metal", "MetalKit", "AVFoundation", "CoreVideo"

  s.dependency 'React-jsi'
  s.dependency 'React-callinvoker'

  # Filament for 3D rendering (Metal backend on iOS)
  s.dependency "Filament/filament"
  s.dependency "Filament/gltfio_core"
  s.dependency "Filament/image"
  s.dependency "Filament/ktxreader"

  install_modules_dependencies(s)
end
