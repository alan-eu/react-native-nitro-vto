# Glasses Virtual Try-On (VTO)

A minimal Android app for virtual try-on of glasses using ARCore face tracking and Filament rendering.

## Requirements

- Android device with ARCore support and front-facing camera
- Filament `matc` tool for compiling materials
- Filament `cmgen` tool for compiling HDR to IBL

## Setup

### 1. Compile a Filament Material

Download the Filament tools and compile the camera background material:

```bash
 matc --api opengl --api vulkan --platform mobile -o ./app/src/main/assets/materials/camera_background.filamat ./app/src/main/assets/materials/camera_background.mat
```

### 2. Generate IBL from HDR env

```bash
cmgen --format=ktx --size=256 --deploy=./app/src/main/assets/envs/ ./app/src/main/assets/envs/neon_photostudio_2k.hd
```

### 3. Build and Run

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

