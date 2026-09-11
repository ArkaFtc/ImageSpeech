@echo off
call "C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
set PATH=C:\Users\aruns\StudioProjects\ImageSpeech\benchmarks\navidc;%PATH%
benchmarks\navidc\cmake-4.4.3-windows-x86_64\bin\cmake.exe -S benchmarks/navidc/llama-src -B benchmarks/navidc/build-gpu -G Ninja -DCMAKE_MAKE_PROGRAM=C:/Users/aruns/StudioProjects/ImageSpeech/benchmarks/navidc/ninja.exe -DCMAKE_TOOLCHAIN_FILE=C:/Users/aruns/StudioProjects/ImageSpeech/benchmarks/navidc/android-ndk-r29/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF -DLLAMA_OPENSSL=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_SERVER=OFF -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod -DGGML_VULKAN=ON -DCMAKE_CXX_FLAGS=-IC:/Users/aruns/StudioProjects/ImageSpeech/benchmarks/navidc/spirv-install/include -DVulkan_INCLUDE_DIR=C:/Users/aruns/StudioProjects/ImageSpeech/benchmarks/navidc/vulkan-headers/include -DSPIRV-Headers_DIR=C:/Users/aruns/StudioProjects/ImageSpeech/benchmarks/navidc/spirv-install/share/cmake/SPIRV-Headers -DGGML_VULKAN_SHADERS_GEN_TOOLCHAIN=C:/Users/aruns/StudioProjects/ImageSpeech/benchmarks/navidc/host-toolchain.cmake -DVulkan_GLSLC_EXECUTABLE=C:/Users/aruns/StudioProjects/ImageSpeech/benchmarks/navidc/android-ndk-r29/shader-tools/windows-x86_64/glslc.exe
if errorlevel 1 exit /b 1
benchmarks\navidc\cmake-4.4.3-windows-x86_64\bin\cmake.exe --build benchmarks/navidc/build-gpu -j 12 --target llama-mtmd-cli



