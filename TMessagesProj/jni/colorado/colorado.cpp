#include <string_view>
#include <dirent.h>
#include <unistd.h>
#include <zlib.h>

#include "colorado.h"
#include "logging.h"
#include "obfs-string.h"
#include "utils.h"

void kill_self() {
    kill(getpid(), SIGKILL);
}

bool check_signature() {
    // ── 见山客户端：跳过 Nekogram 的原生签名校验 ──
    // 上游在这里做防二次打包：遍历 /proc/self/fd 找到已安装的 APK，
    // 读出签名证书，用 crc32 和长度跟编译期常量（CERT_HASH / CERT_SIZE）比对，
    // 只要不匹配就 kill(getpid(), SIGKILL) —— 进程被自己用 SIGKILL 干掉。
    //
    // 这就是"第三方签名打包后启动即白屏、连一行 Java 日志都没有"的真正原因：
    //   · 死在 JNI_OnLoad 第一步（System.loadLibrary("tmessages.49") 里）
    //   · SIGKILL 不可捕获，所以任何信号处理器/未捕获异常处理器都拿不到东西
    //   · ApplicationExitInfo 记录为 reason=2 (SIGNALED) status=9 (SIGKILL)
    //   · 换回官方签名（哪怕字节完全一样）就恢复正常，改包名也会因为
    //     PACKAGE_NAME 不匹配而挂
    //
    // 我们自己签的包必须放行，否则客户端永远起不来。
    return true;
}
