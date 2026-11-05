# Shared settings for the integrator scripts. Sourced, not run.
# Every value can be overridden from the environment.
#
#   SIERRA_REPO        GitHub repo the loop works on       (default mdc159/sierra-auto)
#   SIERRA_WT          folder that holds the worktrees     (default <repo>/../sierra-auto-wt)
#   INTEGRATE_DIR      the integrator's own worktree       (default $SIERRA_WT/integrate)
#   INTEGRATE_LOGS     build logs                          (default $SIERRA_WT/integrate-logs)
#   INTEGRATE_LOG      the running integrator log          (default $SIERRA_WT/integrate-log.md)
#   SIERRA_TOOLCHAIN   JDK, Android SDK and Gradle home    (default D:/Projects/android-toolchain,
#                      the same setting scripts/_common.ps1 reads)

_here=$(cd "$(dirname "$0")" && pwd)
SIERRA_ROOT=$(cd "$_here/../.." && pwd)

SIERRA_REPO=${SIERRA_REPO:-mdc159/sierra-auto}
SIERRA_WT=${SIERRA_WT:-$(cd "$SIERRA_ROOT/.." && pwd)/sierra-auto-wt}
INTEGRATE_DIR=${INTEGRATE_DIR:-$SIERRA_WT/integrate}
INTEGRATE_LOGS=${INTEGRATE_LOGS:-$SIERRA_WT/integrate-logs}
INTEGRATE_LOG=${INTEGRATE_LOG:-$SIERRA_WT/integrate-log.md}
SIERRA_TOOLCHAIN=${SIERRA_TOOLCHAIN:-D:/Projects/android-toolchain}

use_toolchain() {
  export JAVA_HOME="${JAVA_HOME:-$SIERRA_TOOLCHAIN/jdk-17}"
  export ANDROID_HOME="${ANDROID_HOME:-$SIERRA_TOOLCHAIN/android-sdk}"
  export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
  export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$SIERRA_TOOLCHAIN/gradle-user-home}"
}
