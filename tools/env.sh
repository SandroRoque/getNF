# Source this: `. tools/env.sh`
# Puts the Android SDK tools on PATH for the current shell.
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
# avdmanager writes AVDs to the XDG location but the emulator only looks in ~/.android/avd unless told.
if [[ -z "${ANDROID_AVD_HOME:-}" && -d "$HOME/.config/.android/avd" ]]; then
  export ANDROID_AVD_HOME="$HOME/.config/.android/avd"
fi
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

export GETNF_AVD="${GETNF_AVD:-getnf}"
