// 见山客户端 · 原生崩溃捕获（诊断用）
// 目的：App 在 dlopen 阶段被系统干掉时，Java 层什么都留不下。
// 这里装一个信号处理器，把信号、故障地址、调用栈直接写进可访问的文件。

#include <jni.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <unwind.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdlib.h>

static int g_fd = -1;
static char g_path[512];

static void w(const char *s) {
    if (g_fd >= 0 && s) {
        ssize_t r = write(g_fd, s, strlen(s));
        (void) r;
    }
}

static void wf(const char *fmt, ...) {
    char b[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(b, sizeof(b), fmt, ap);
    va_end(ap);
    w(b);
}

struct frames {
    void **arr;
    int n;
    int max;
};

static _Unwind_Reason_Code cb(struct _Unwind_Context *ctx, void *arg) {
    struct frames *f = (struct frames *) arg;
    uintptr_t ip = _Unwind_GetIP(ctx);
    if (ip && f->n < f->max) {
        f->arr[f->n++] = (void *) ip;
    }
    return _URC_NO_REASON;
}

static void handler(int sig, siginfo_t *si, void *uc) {
    (void) uc;
    if (g_fd < 0) {
        _exit(139);
    }
    w("\n");
    wf("!!! NATIVE CRASH sig=%d code=%d addr=%p\n", sig, si ? si->si_code : 0, si ? si->si_addr : 0);
    void *arr[64];
    struct frames f = {arr, 0, 64};
    _Unwind_Backtrace(cb, &f);
    for (int i = 0; i < f.n; i++) {
        Dl_info info;
        if (dladdr(arr[i], &info) && info.dli_fname) {
            wf("  #%02d %p  %s + 0x%lx\n", i, arr[i], info.dli_fname,
               (unsigned long) ((char *) arr[i] - (char *) info.dli_fbase));
        } else {
            wf("  #%02d %p\n", i, arr[i]);
        }
    }
    w("!!! 结束\n");
    _exit(139);
}

JNIEXPORT void JNICALL
Java_org_telegram_messenger_JianshanCrashGuard_nativeInstall(JNIEnv *env, jclass cls, jstring path) {
    (void) cls;
    if (path) {
        const char *p = (*env)->GetStringUTFChars(env, path, 0);
        if (p) {
            strncpy(g_path, p, sizeof(g_path) - 1);
            (*env)->ReleaseStringUTFChars(env, path, p);
            g_fd = open(g_path, O_WRONLY | O_CREAT | O_APPEND, 0666);
        }
    }
    w("\n==== 崩溃捕获已安装 ====\n");
    // 备用信号栈，防止栈溢出时信号处理本身也崩
    static char altstack[SIGSTKSZ < 32768 ? 32768 : SIGSTKSZ];
    stack_t ss;
    ss.ss_sp = altstack;
    ss.ss_size = sizeof(altstack);
    ss.ss_flags = 0;
    sigaltstack(&ss, NULL);

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = handler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGSEGV, &sa, NULL);
    sigaction(SIGBUS, &sa, NULL);
    sigaction(SIGILL, &sa, NULL);
    sigaction(SIGABRT, &sa, NULL);
    sigaction(SIGFPE, &sa, NULL);
}
