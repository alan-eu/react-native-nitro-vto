# Changelog

## [0.11.6](https://github.com/alan-eu/react-native-nitro-vto/compare/v0.11.5...v0.11.6) (2026-04-28)

### 🐛 Bug Fixes

* **expo config plugin:** don't make arcore mandatory ([#51](https://github.com/alan-eu/react-native-nitro-vto/issues/51)) ([ffec68f](https://github.com/alan-eu/react-native-nitro-vto/commit/ffec68f0bed540ee7fb5316ba89a58484572ab09))

## [0.11.5](https://github.com/alan-eu/react-native-nitro-vto/compare/v0.11.4...v0.11.5) (2026-04-27)

## [0.11.4](https://github.com/alan-eu/react-native-nitro-vto/compare/v0.11.3...v0.11.4) (2026-04-25)

### 🐛 Bug Fixes

* **ios:** Update pod declaration ([#49](https://github.com/alan-eu/react-native-nitro-vto/issues/49)) ([59d91d3](https://github.com/alan-eu/react-native-nitro-vto/commit/59d91d39d5602defe3d3f87d067372a40023b594))

## [0.11.3](https://github.com/alan-eu/react-native-nitro-vto/compare/v0.11.2...v0.11.3) (2026-04-25)

### 🐛 Bug Fixes

* **ios:** Host own Filament podspec to remove arm64 exclusion ([#48](https://github.com/alan-eu/react-native-nitro-vto/issues/48)) ([8a04a3f](https://github.com/alan-eu/react-native-nitro-vto/commit/8a04a3f6348c4ea5487ba28aab91923a3ecacadf))

## [0.11.2](https://github.com/alan-eu/react-native-nitro-vto/compare/v0.11.1...v0.11.2) (2026-04-24)

### 🐛 Bug Fixes

* **ios:** strip Filament's simulator arm64 exclusion from Pods xcconfigs ([#47](https://github.com/alan-eu/react-native-nitro-vto/issues/47)) ([0341e61](https://github.com/alan-eu/react-native-nitro-vto/commit/0341e61c77338b472c0a9f07754ec67e9986e00a))

## [0.11.1](https://github.com/alan-eu/react-native-nitro-vto/compare/v0.11.0...v0.11.1) (2026-04-24)

### 🐛 Bug Fixes

* **ios:** strip Filament's simulator arm64 exclusion via Expo config plugin ([#46](https://github.com/alan-eu/react-native-nitro-vto/issues/46)) ([28215b2](https://github.com/alan-eu/react-native-nitro-vto/commit/28215b249993a8230cedf653a31e7ac367a6307a))

## [0.11.0](https://github.com/alan-eu/react-native-nitro-vto/compare/v0.10.3...v0.11.0) (2026-04-23)

### ⚠ BREAKING CHANGES

* replace switchModel/resetSession with modelUrl prop + sticky hideGlasses/showGlasses (#45)

### ✨ Features

* replace switchModel/resetSession with modelUrl prop + sticky hideGlasses/showGlasses ([#45](https://github.com/alan-eu/react-native-nitro-vto/issues/45)) ([828e1ce](https://github.com/alan-eu/react-native-nitro-vto/commit/828e1ce104961181406ba89d39e47bb8fc2cf478))

## [0.10.3](https://github.com/alan-eu/react-native-nitro-vto/compare/v0.10.2...v0.10.3) (2026-04-22)

### 🐛 Bug Fixes

* **ios:** release MTKView drawables and flush GPU commands on destroy to avoid memory-pressure jetsam after leaving VTO ([#44](https://github.com/alan-eu/react-native-nitro-vto/issues/44)) ([a405138](https://github.com/alan-eu/react-native-nitro-vto/commit/a40513841452413fd56cbc87014a3eac17bdcb2b))

## [0.10.2](https://github.com/alan-eu/react-native-nitro-vto/compare/v0.10.1...v0.10.2) (2026-04-22)

### 🐛 Bug Fixes

* **android:** destroy camera_feed material instance + buffers to prevent Filament teardown panic ([#43](https://github.com/alan-eu/react-native-nitro-vto/issues/43)) ([fc81560](https://github.com/alan-eu/react-native-nitro-vto/commit/fc81560572f2b3aab4006e2e25dfc0cd0cefc37f))

## [0.10.1](https://github.com/alan-eu/react-native-nitro-vto/compare/v0.10.0...v0.10.1) (2026-04-22)

### 🐛 Bug Fixes

* **android:** pause on detach instead of destroy to avoid Filament panic on nav-back ([#42](https://github.com/alan-eu/react-native-nitro-vto/issues/42)) ([5d66c88](https://github.com/alan-eu/react-native-nitro-vto/commit/5d66c8820446cee742da6f565b0b98bb7cc69893))

## [0.10.0](https://github.com/alan-eu/react-native-nitro-vto/compare/v0.9.1...v0.10.0) (2026-04-22)

### ✨ Features

* **android:** tweak camera feed lighting ([#37](https://github.com/alan-eu/react-native-nitro-vto/issues/37)) ([2850e2a](https://github.com/alan-eu/react-native-nitro-vto/commit/2850e2a8a1ebbe55601354020572a79f78655156))
* faceTracked and glassesDisplayed callbacks ([#39](https://github.com/alan-eu/react-native-nitro-vto/issues/39)) ([2a3069b](https://github.com/alan-eu/react-native-nitro-vto/commit/2a3069bd85d0d3430679566f2f8c03b8c91cac33))
* **iOS, android:** better HDRi and env lighthing ([#32](https://github.com/alan-eu/react-native-nitro-vto/issues/32)) ([dced686](https://github.com/alan-eu/react-native-nitro-vto/commit/dced6861f58b41cb9aecbfaf061b715d883a02f2))
* share core package and old arch support ([#40](https://github.com/alan-eu/react-native-nitro-vto/issues/40)) ([9bf5fb6](https://github.com/alan-eu/react-native-nitro-vto/commit/9bf5fb6622923c14c6ad67b86cda1fb5d512d2b0))
* unified assets (materials and envs) between iOS and Android ([#36](https://github.com/alan-eu/react-native-nitro-vto/issues/36)) ([826eeca](https://github.com/alan-eu/react-native-nitro-vto/commit/826eecac18c1726bf2497801a368cb10c8c95aca))

### 🐛 Bug Fixes

* **android:** translucent material ([#35](https://github.com/alan-eu/react-native-nitro-vto/issues/35)) ([171ca67](https://github.com/alan-eu/react-native-nitro-vto/commit/171ca6707970b645a12e28a360b5cec622b438a0))

All notable changes to this project will be documented in this file. See [Conventional Commits](https://www.conventionalcommits.org/) for commit guidelines.

## [0.9.1](https://github.com/alan-eu/react-native-nitro-vto/compare/v0.9.0...v0.9.1) (2026-01-06)

## [0.9.0](https://github.com/alan-eu/react-native-nitro-vto/compare/v0.8.0...v0.9.0) (2026-01-06)

### Features

* **iOS, android:** debug util ([#30](https://github.com/alan-eu/react-native-nitro-vto/issues/30)) ([cd19763](https://github.com/alan-eu/react-native-nitro-vto/commit/cd19763c6123cc3707c5f563d30657c77c5b5dcf))
* **iOS, android:** forward offset for glasses from react-native ([#29](https://github.com/alan-eu/react-native-nitro-vto/issues/29)) ([dec5120](https://github.com/alan-eu/react-native-nitro-vto/commit/dec512024e19ece41d89f85091b6df10d47dc2de))
* **iOS, android:** split backplane in left/right planes to be able to display temples ([#28](https://github.com/alan-eu/react-native-nitro-vto/issues/28)) ([8de1145](https://github.com/alan-eu/react-native-nitro-vto/commit/8de11454abcbb54c0e6e99d79dfc4a0f3c066505))

## [0.8.0](https://github.com/alan-eu/react-native-nitro-vto/compare/v0.7.1...v0.8.0) (2026-01-05)

### Features

* expo app config plugin and move example to expo ([#26](https://github.com/alan-eu/react-native-nitro-vto/issues/26)) ([915e0b4](https://github.com/alan-eu/react-native-nitro-vto/commit/915e0b4468a16b7ee1b83ce60af089d555deb4a0))
* flatten occlusion props ([#27](https://github.com/alan-eu/react-native-nitro-vto/issues/27)) ([b577773](https://github.com/alan-eu/react-native-nitro-vto/commit/b5777738f645b159b0f6201bf2213df413b98c66))

### Bug Fixes

* **android:** move kt classes on the right folder ([#25](https://github.com/alan-eu/react-native-nitro-vto/issues/25)) ([385bcaf](https://github.com/alan-eu/react-native-nitro-vto/commit/385bcaffab383230688a42ad8268b91653e73783))

## [0.7.1](https://github.com/alan-eu/react-native-nitro-vto/compare/v0.7.0...v0.7.1) (2026-01-04)

## [0.7.0](https://github.com/alan-eu/react-native-nitro-vto/compare/v0.6.0...v0.7.0) (2026-01-04)

### Features

* **iOS, android:** glasses occlusion by face mesh ([#24](https://github.com/alan-eu/react-native-nitro-vto/issues/24)) ([11b03ff](https://github.com/alan-eu/react-native-nitro-vto/commit/11b03ff7b0316173a96dcf6e9c8f1162cdb7029c))

## [0.6.0](https://github.com/alan-eu/react-native-nitro-vto/compare/v0.5.6...v0.6.0) (2026-01-02)

### Features

* **iOS, android:** remove modelWidthMeters prop - models are now expected in real-world meters ([#20](https://github.com/alan-eu/react-native-nitro-vto/issues/20)) ([50cb760](https://github.com/alan-eu/react-native-nitro-vto/commit/50cb7608550ef9c153fa34bde047e0888e50e78f))
* **iOS, android:** remove uneeded setViewportSize calls ([#21](https://github.com/alan-eu/react-native-nitro-vto/issues/21)) ([ba777a5](https://github.com/alan-eu/react-native-nitro-vto/commit/ba777a56567cc10ed911c9bba1e2d9cfb9ea354c))
* **iOS, android:** use world-space positioning with ARKit/ARCore camera matrices ([#19](https://github.com/alan-eu/react-native-nitro-vto/issues/19)) ([b038675](https://github.com/alan-eu/react-native-nitro-vto/commit/b0386758290e36fc07bbf790efbbf46284189e4c))

### Bug Fixes

* **android:** move implementation to the right folder ([#22](https://github.com/alan-eu/react-native-nitro-vto/issues/22)) ([675e92c](https://github.com/alan-eu/react-native-nitro-vto/commit/675e92c8d8ab441dc7cfda2de999bb067e6bce8e))
* **android:** move implementation to the right folder ([#23](https://github.com/alan-eu/react-native-nitro-vto/issues/23)) ([efe631b](https://github.com/alan-eu/react-native-nitro-vto/commit/efe631b132bcbf57ec55abe22b3da7d1c331e007))

## [0.5.6](https://github.com/alan-eu/react-native-nitro-vto/compare/v0.5.5...v0.5.6) (2025-12-27)

### Bug Fixes

* **android:** Use multiple textures for camera stream to avoid green flickering ([#18](https://github.com/alan-eu/react-native-nitro-vto/issues/18)) ([f61ff08](https://github.com/alan-eu/react-native-nitro-vto/commit/f61ff08f1839def001bfc19ac91fdc0080e67b91))

## [0.5.4](https://github.com/alan-eu/react-native-nitro-vto/compare/v0.5.3...v0.5.4) (2025-12-19)

### Bug Fixes

* Filament version mismatch for assets on iOS ([#17](https://github.com/alan-eu/react-native-nitro-vto/issues/17)) ([c99f292](https://github.com/alan-eu/react-native-nitro-vto/commit/c99f2925264bf708f2fca7486ed886118f5cff54))

## [0.5.3](https://github.com/alan-eu/react-native-nitro-vto/compare/v0.5.2...v0.5.3) (2025-12-19)

### Bug Fixes

* add missing assets to package files ([#16](https://github.com/alan-eu/react-native-nitro-vto/issues/16)) ([d6fdabf](https://github.com/alan-eu/react-native-nitro-vto/commit/d6fdabf788a94c08cb6af1cba865ef17fcb6a19b))

## [0.5.2](https://github.com/alan-eu/react-native-nitro-vto/compare/v0.5.1...v0.5.2) (2025-12-19)

## [0.5.1](https://github.com/alan-eu/react-native-nitro-vto/compare/v0.5.0...v0.5.1) (2025-12-19)

### Bug Fixes

* podspec ([#14](https://github.com/alan-eu/react-native-nitro-vto/issues/14)) ([7c5668e](https://github.com/alan-eu/react-native-nitro-vto/commit/7c5668e800492ffce7956beb97060ee6c0d69252))

## [0.5.0](https://github.com/alan-eu/react-native-nitro-vto/compare/v0.4.0...v0.5.0) (2025-12-18)

### Features

* drop mirror flip on Android to match iOS ([#12](https://github.com/alan-eu/react-native-nitro-vto/issues/12)) ([132fae4](https://github.com/alan-eu/react-native-nitro-vto/commit/132fae4be0384cc76f82ba6a63162aa9248e4510))
* ios implem ([#11](https://github.com/alan-eu/react-native-nitro-vto/issues/11)) ([f4f29e6](https://github.com/alan-eu/react-native-nitro-vto/commit/f4f29e63fe1a199ef8c0b37a63884befa86a52fd))

## [0.4.0](https://github.com/alan-eu/react-native-nitro-vto/compare/v0.3.0...v0.4.0) (2025-12-17)

### Features

* onModelLoaded callback ([#8](https://github.com/alan-eu/react-native-nitro-vto/issues/8)) ([f0b9caa](https://github.com/alan-eu/react-native-nitro-vto/commit/f0b9caacacefade3087fcad021196c6ea816cc4f))

## [0.3.0](https://github.com/alan-eu/react-native-nitro-vto/compare/v0.2.6...v0.3.0) (2025-12-17)

### Features

* load remote models ([#7](https://github.com/alan-eu/react-native-nitro-vto/issues/7)) ([647f93a](https://github.com/alan-eu/react-native-nitro-vto/commit/647f93aa470d8715ad8f063d6f825c66085dd0fc))

## [0.2.6](https://github.com/alan-eu/react-native-nitro-vto/compare/v0.2.5...v0.2.6) (2025-12-17)

## [0.2.5](https://github.com/alan-eu/react-native-nitro-vto/compare/v0.2.4...v0.2.5) (2025-12-17)

## [0.2.4](https://github.com/alan-eu/react-native-nitro-vto/compare/v0.2.3...v0.2.4) (2025-12-17)

## [0.2.3](https://github.com/alan-eu/react-native-nitro-vto/compare/v0.2.2...v0.2.3) (2025-12-17)

## [0.2.2](https://github.com/alan-eu/react-native-nitro-vto/compare/v0.2.1...v0.2.2) (2025-12-17)

## [0.2.1](https://github.com/alan-eu/react-native-nitro-vto/compare/v0.2.0...v0.2.1) (2025-12-17)

## [0.2.0](https://github.com/alan-eu/react-native-nitro-vto/compare/f31bf7b7b6ed45c3c54bd97aed20fc81ecd6d849...v0.2.0) (2025-12-17)

### Features

* cleaned updateTransform ([8f71714](https://github.com/alan-eu/react-native-nitro-vto/commit/8f717144ab6ec2a7f34a864dc8374cc18f66de95))
* indirect light based on ARCore pixel intensity and increased far for filament camera ([c54f95b](https://github.com/alan-eu/react-native-nitro-vto/commit/c54f95b97841d100835adebbe346e2c0340ca10f))
* initial commit ([f31bf7b](https://github.com/alan-eu/react-native-nitro-vto/commit/f31bf7b7b6ed45c3c54bd97aed20fc81ecd6d849))
* Kalman filter to reduce jittering ([7fc35ae](https://github.com/alan-eu/react-native-nitro-vto/commit/7fc35aec10d66bcabe919815a1a9576d2b607c79))
* migrate to Nitro modules with HybridView ([#1](https://github.com/alan-eu/react-native-nitro-vto/issues/1)) ([01ab586](https://github.com/alan-eu/react-native-nitro-vto/commit/01ab586e7e9c0186d01ac5aadb23f1b5ee38c034))
* rotation ok-ish ([201cba0](https://github.com/alan-eu/react-native-nitro-vto/commit/201cba0097273fb675aa28c0da16029f60b964b6))
* skew at roll and scale at yaw fixed ([ea7af5f](https://github.com/alan-eu/react-native-nitro-vto/commit/ea7af5ffdd242dc6de7749f5d8cc6c3d3779ca97))
* switch glasses model ([d7998ea](https://github.com/alan-eu/react-native-nitro-vto/commit/d7998ea21eb4ddf1779755b441e716bf978c904d))
* use nose bridge pos ([92701b6](https://github.com/alan-eu/react-native-nitro-vto/commit/92701b67c73ceb6047e3f5ff13d4a77c2f9034bb))
* WIP ([bcd8073](https://github.com/alan-eu/react-native-nitro-vto/commit/bcd8073675d3633f360134ae25bc017f50a85e17))

### Bug Fixes

* corrupted lock file ([2c8a4c2](https://github.com/alan-eu/react-native-nitro-vto/commit/2c8a4c22ec3d194aaba98bcf45e9ce5f319c2517))
