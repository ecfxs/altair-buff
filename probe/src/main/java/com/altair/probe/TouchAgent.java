package com.altair.probe;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/** 由 root app_process 执行；整次走位在一个进程内完成，不在事件之间启动 shell。 */
public final class TouchAgent {
    public static void main(String[] args) {
        AtomicBoolean cancelled = new AtomicBoolean();
        int code = 0;
        try {
            BufferedReader commands = new BufferedReader(new InputStreamReader(System.in));
            if (!"GO".equals(commands.readLine())) throw new InterruptedException("未启动");
            Thread watcher = new Thread(() -> {
                try { commands.readLine(); } catch (Exception ignored) { }
                // STOP 或父进程死亡导致的 EOF 均释放触摸。
                cancelled.set(true);
            }, "touch-cancel");
            watcher.setDaemon(true);
            watcher.start();
            Class<?> managerClass = Class.forName("android.hardware.input.InputManager");
            Object manager = managerClass.getDeclaredMethod("getInstance").invoke(null);
            Method inject = managerClass.getMethod("injectInputEvent", InputEvent.class, int.class);
            GestureSequence sequence = new GestureSequence(new GestureSequence.Clock() {
                public long now() { return SystemClock.uptimeMillis(); }
                public void sleep(long ms) throws InterruptedException { Thread.sleep(ms); }
            }, (action, down, time, x, y) -> {
                MotionEvent event = MotionEvent.obtain(down, time, action, x, y, 0);
                event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                try {
                    // 同步确认系统接收事件；拒绝注入必须明确失败。
                    if (!Boolean.TRUE.equals(inject.invoke(manager, event, 2))) {
                        throw new IllegalStateException("系统拒绝触摸注入");
                    }
                    if (action == MotionEvent.ACTION_UP) {
                        System.out.println("TOUCH_RELEASE " + x + "," + y + " held=" + (time - down) + "ms");
                    }
                } finally { event.recycle(); }
            }, cancelled::get);
            if (args.length == 4 && "tap".equals(args[0])) {
                int x = Integer.parseInt(args[1]), y = Integer.parseInt(args[2]);
                sequence.hold(x, y, x, y, Long.parseLong(args[3]));
            } else if (args.length == 9 && "walk".equals(args[0])) {
                sequence.walk(Integer.parseInt(args[1]), Integer.parseInt(args[2]),
                    Integer.parseInt(args[3]), Integer.parseInt(args[4]),
                    Integer.parseInt(args[5]), Integer.parseInt(args[6]),
                    Long.parseLong(args[7]), Long.parseLong(args[8]));
            } else if (args.length == 6 && "walk3".equals(args[0])) {
                // 跳跃未标记时的退路：只走三段。
                sequence.walkOnly(Integer.parseInt(args[1]), Integer.parseInt(args[2]),
                    Integer.parseInt(args[3]), Integer.parseInt(args[4]),
                    Long.parseLong(args[5]));
            } else throw new IllegalArgumentException("动作参数无效");
            System.out.println("TOUCH_OK");
        } catch (InterruptedException e) {
            code = 2;
            System.out.println("TOUCH_CANCELLED");
        } catch (Throwable e) {
            code = 1;
            Throwable cause = e.getCause() == null ? e : e.getCause();
            System.out.println("TOUCH_ERROR " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
        System.out.flush();
        System.exit(code);
    }
}
