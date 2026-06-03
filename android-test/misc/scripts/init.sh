./build_with_key.sh
docker run --rm -v $(pwd)/out:/out assistant-debug bash -c "cp /project/app/build/outputs/apk/debug/app-debug.apk /out/" 
adb uninstall com.assistant.app
adb install out/app-debug.apk