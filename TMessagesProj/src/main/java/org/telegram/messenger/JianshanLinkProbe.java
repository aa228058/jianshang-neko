package org.telegram.messenger;

import android.os.SystemClock;

/**
 * 见山客户端 · 消息链路探针（v45）
 *
 * 目的：把「后台有时候收不到消息」这件事拆成三种可判定的情况：
 *   A) state != 3（Connected）           → 网络/代理问题，根本没通
 *   B) state == 3 但 lastRx 很久没更新    → 连接看着通、实际收不到 update（代理半死 / socket 假活）
 *   C) tick 出现大 gap                    → 进程被冻，落盘时间戳不连续
 *
 * 打点位置极薄（只记两个 long / 一个 int），不会拖性能。
 */
public final class JianshanLinkProbe {

    private static final int MAX_ACC = UserConfig.MAX_ACCOUNT_COUNT;
    private static final long[] lastRxAt = new long[MAX_ACC];
    private static final int[] lastState = new int[MAX_ACC];
    private static final int[] rxCount = new int[MAX_ACC];
    private static final long[] lastStateChangeAt = new long[MAX_ACC];
    private static volatile boolean stateLogged;

    private JianshanLinkProbe() {
    }

    /** 每收到一次 update（native 回调 onUnparsedMessageReceived）调用 */
    public static void onUpdate(int account) {
        if (account < 0 || account >= MAX_ACC) {
            return;
        }
        lastRxAt[account] = SystemClock.elapsedRealtime();
        rxCount[account]++;
    }

    /** 连接状态变化时调用 */
    public static void onState(int account, int state) {
        if (account < 0 || account >= MAX_ACC) {
            return;
        }
        if (lastState[account] == state) {
            return;
        }
        lastState[account] = state;
        lastStateChangeAt[account] = SystemClock.elapsedRealtime();
        try {
            JianshanDiag.log(ApplicationLoader.applicationContext,
                    "[conn] a" + account + " -> " + stateName(state));
        } catch (Throwable ignore) {
        }
    }

    public static void onSessionCreated(int account) {
        try {
            JianshanDiag.log(ApplicationLoader.applicationContext,
                    "[conn] a" + account + " session created（开始补 difference）");
        } catch (Throwable ignore) {
        }
    }

    /** 距离上一次收到 update 多少秒；-1 表示本进程还没收到过 */
    public static long ageSeconds(int account) {
        if (account < 0 || account >= MAX_ACC) {
            return -1;
        }
        long at = lastRxAt[account];
        if (at == 0) {
            return -1;
        }
        return (SystemClock.elapsedRealtime() - at) / 1000;
    }

    public static int rxCount(int account) {
        if (account < 0 || account >= MAX_ACC) {
            return 0;
        }
        return rxCount[account];
    }

    public static int state(int account) {
        if (account < 0 || account >= MAX_ACC) {
            return -1;
        }
        return lastState[account];
    }

    public static String stateName(int state) {
        switch (state) {
            case 0:
                return "WaitingForNetwork";
            case 1:
                return "Connecting";
            case 2:
                return "WaitingForNetwork";
            case 3:
                return "Connected";
            case 4:
                return "ConnectingToProxy";
            case 5:
                return "Updating";
            default:
                return String.valueOf(state);
        }
    }

    /**
     * 服务 tick 里调用：连接看着通、但很久没有 update，就是「假活」，直接打一行 [silent]。
     * 阈值 120 秒：正常在线时 Telegram 每 19 秒至少有一次 ping/update 往返。
     */
    public static void checkSilent(int account, boolean connected) {
        if (!connected) {
            return;
        }
        long age = ageSeconds(account);
        if (age >= 120) {
            try {
                JianshanDiag.log(ApplicationLoader.applicationContext,
                        "[silent] a" + account + " 已连接但 " + age + "s 没收到任何 update（socket 假活/代理半死）");
            } catch (Throwable ignore) {
            }
        }
    }

    public static String describe(int account) {
        StringBuilder sb = new StringBuilder();
        sb.append("state=").append(stateName(state(account)));
        long age = ageSeconds(account);
        sb.append(" lastRx=").append(age < 0 ? "从未" : (age + "s前"));
        sb.append(" rx=").append(rxCount(account));
        long sinceState = lastStateChangeAt[account] == 0 ? -1
                : (SystemClock.elapsedRealtime() - lastStateChangeAt[account]) / 1000;
        sb.append(" stateAge=").append(sinceState < 0 ? "-" : (sinceState + "s"));
        return sb.toString();
    }
}
