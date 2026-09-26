package com.altair.probe;

import java.util.List;

/** 窗口快照只保留包名和窗口状态；未知、分屏、画中画与系统焦点均拒绝猜测。 */
public final class ForegroundWindows {
    public static final class Window {
        public final int type, display;
        public final boolean active, focused, pictureInPicture;
        public final String pkg;
        public Window(int type, int display, boolean active, boolean focused, boolean pip, String pkg) {
            this.type = type; this.display = display; this.active = active;
            this.focused = focused; this.pictureInPicture = pip; this.pkg = pkg;
        }
    }
    public static String resolve(List<Window> windows, boolean interactive, boolean locked, String ownPackage) {
        if (!interactive || locked) return "";
        Window app = null;
        boolean ownOverlayActive = false;
        for (Window window : windows) {
            if (window.display != 0 || window.pictureInPicture) return "";
            if (window.type == 1) { // AccessibilityWindowInfo.TYPE_APPLICATION
                if (app != null) return "";
                app = window;
            } else if ((window.type == 3 || window.type == 4) && !window.focused &&
                    ownPackage != null && ownPackage.equals(window.pkg)) {
                // 点击自家非聚焦悬浮窗时，它可能暂时成为 active；目标应用仍须独占焦点。
                ownOverlayActive |= window.active;
            } else if (window.type == 2 || window.active || window.focused) { // 输入法或其他窗口接管焦点
                return "";
            }
        }
        return app != null && (app.active || ownOverlayActive) && app.focused && app.pkg != null ? app.pkg : "";
    }
}
