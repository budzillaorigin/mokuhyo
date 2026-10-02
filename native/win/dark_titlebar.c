/*
 * mokuhyo_win.dll — Windows-only helper (D-027): asks DWM to draw this process's top-level windows with a dark title
 * bar in the app's background colour. Kept out of mokuhyo_native so the two variants of the AI library never share
 * JNI symbols. Only system DLLs (user32, dwmapi). Unsupported Windows builds simply ignore the attributes.
 */
#include <windows.h>
#include <dwmapi.h>
#include <jni.h>

#ifndef DWMWA_USE_IMMERSIVE_DARK_MODE
#define DWMWA_USE_IMMERSIVE_DARK_MODE 20
#endif
#define DWMWA_USE_IMMERSIVE_DARK_MODE_OLD 19 /* Windows 10 before 20H1 */
#ifndef DWMWA_CAPTION_COLOR
#define DWMWA_CAPTION_COLOR 35 /* Windows 11 */
#endif
#ifndef DWMWA_TEXT_COLOR
#define DWMWA_TEXT_COLOR 36
#endif

static BOOL CALLBACK apply(HWND hwnd, LPARAM count) {
    DWORD pid = 0;
    GetWindowThreadProcessId(hwnd, &pid);
    if (pid != GetCurrentProcessId() || !IsWindowVisible(hwnd)) return TRUE;
    BOOL on = TRUE;
    if (FAILED(DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, &on, sizeof on)))
        DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE_OLD, &on, sizeof on);
    COLORREF caption = RGB(0x11, 0x15, 0x22), text = RGB(0xE3, 0xE6, 0xEE);
    DwmSetWindowAttribute(hwnd, DWMWA_CAPTION_COLOR, &caption, sizeof caption);
    DwmSetWindowAttribute(hwnd, DWMWA_TEXT_COLOR, &text, sizeof text);
    /* Repaint the frame now rather than on the next activation. */
    SetWindowPos(hwnd, NULL, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED);
    (*(int *) count)++;
    return TRUE;
}

/* Returns how many windows were updated. */
JNIEXPORT jint JNICALL Java_app_mokuhyo_desktop_WindowsTitleBar_nativeDarken(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
    int count = 0;
    EnumWindows(apply, (LPARAM) &count);
    return count;
}
