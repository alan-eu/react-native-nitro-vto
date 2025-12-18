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
    "ios/**/*.hpp",on
    "ios/**/*.hpp",on
    "ios/**/*.hpp",
  
  # Preserve paths to headers but don't include them in source_files
  # This prevents them from being treated as public by default
  s.preserve_paths = "ios/**/*.h"
    "ios/VTORendererBridge.h",
  
  # Preserve paths to headers but don't include them in source_files
  
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
  # This prevents them from being treated as public by default
  s.preserve_paths = "ios/**/*.h"
    # Private headers
  
  # Preserve paths to headers but don't include them in source_files
  
  # Only VTORendererBridge should be public for Swift access
  current_public_headers = Array(s.attributes_hash['public_header_files'])
  s.public_header_files = current_public_headers + ["ios/VTORendererBridge.h"]
  
  # Disable strict C++ module checking to avoid Filament header conflicts
  current_xcconfig = s.attributes_hash['pod_target_xcconfig'] || {}
  s.pod_target_xcconfig = current_xcconfig.merge({
    'CLANG_WARN_MODULE_CONFLICT' => 'NO'
  })
  # This prevents them from being treated as public by default
  s.preserve_paths = "ios/**/*.h"
    "ios/CameraTextureRenderer.h",
    "ios/EnvironmentLightingRenderer.h",
    "ios/GlassesRenderer.h",
  
  # Only VTORendererBridge should be public for Swift access
  current_public_headers = Array(s.attributes_hash['public_header_files'])
  s.public_header_files = current_public_headers + ["ios/VTORendererBridge.h"]
    "ios/KalmanFilter.h",
    "ios/LoaderUtils.h",
    "ios/MatrixUtils.h",
    "ios/NitroVto.h",,mm}",,mm}",,mm}",,mm}",  
  # Public headers for Objective-C++ bridge
  s.public_header_files = "ios/*.h"
  
  # Add our custom public headers AFTER nitrogen autolinking
  current_public_headers = Array(s.attributes_hash['public_header_files'])
  s.public_header_files = current_public_headers + ["ios/*.h"]
  
  # Explicitly set public headers to only VTORendererBridge
  current_public_headers = Array(s.attributes_hash['public_header_files'])
  s.public_header_files = current_public_headers + ["ios/VTORendererBridge.h"]
  
  # Mark other headers as private to avoid C++ module conflicts
  current_private_headers = Array(s.attributes_hash['private_header_files'])  
  s.private_header_files = current_private_headers + [
    "ios/CameraTextureRenderer.h",
    "ios/EnvironmentLightingRenderer.h",
    "ios/GlassesRenderer.h",
    "ios/KalmanFilter.h",
    "ios/LoaderUtils.h",
    "ios/MatrixUtils.h",
    "ios/NitroVto.h"
  ]
  
  # Only expose VTORendererBridge.h as public to avoid C++ module conflicts
  # Nitrogen already added its headers, we just add ours
  current_public_headers = Array(s.attributes_hash['public_header_files'])
  s.public_header_files = current_public_headers + ["ios/VTORendererBridge.h"]
  
  # Add our custom public headers AFTER nitrogen autolinking
  # Only expose VTORendererBridge to Swift, keep utility headers private
  current_public_headers = Array(s.attributes_hash['public_header_files'])
  s.public_header_files = current_public_headers + ["ios/VTORendererBridge.h"]
  
  # Keep utility headers private to avoid C++ module conflicts with Filament
  current_private_headers = Array(s.attributes_hash['private_header_files'])
  s.private_header_files = current_private_headers + [
    "ios/CameraTextureRenderer.h",
    "ios/EnvironmentLightingRenderer.h", 
    "ios/GlassesRenderer.h",
    "ios/KalmanFilter.h",
    "ios/LoaderUtils.h",
    "ios/MatrixUtils.h",
    "ios/NitroVto.h"
  ]
