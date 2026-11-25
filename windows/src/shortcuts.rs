// Your shortcuts (action -> keys, from the settings) system-wide, so they work while another app is in front.
// A keyboard hook that only listens, like Discord's: the keys still reach every other app, so the same shortcut can
// mute you in Discord and here. (RegisterHotKey, used before, gives a key to one app only and hid Discord's.)
use std::collections::HashMap;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Mutex, OnceLock};
use tauri::{AppHandle, Manager};
use windows_sys::Win32::Foundation::{LPARAM, LRESULT, WPARAM};
use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;
use windows_sys::Win32::System::Threading::GetCurrentProcessId;
use windows_sys::Win32::UI::Input::KeyboardAndMouse::*;
use windows_sys::Win32::UI::WindowsAndMessaging::*;

static APP: OnceLock<AppHandle> = OnceLock::new();
static KEYS: Mutex<Vec<(String, String)>> = Mutex::new(Vec::new()); // ("Ctrl+Shift+KeyM", "mic")
static HELD: AtomicU32 = AtomicU32::new(0); // the last key down: held keys repeat, and a shortcut fires once

/// The page sets them when a call starts and clears them (empty map) when it ends.
#[tauri::command]
pub fn set_shortcuts(app: AppHandle, keys: HashMap<String, String>) {
    *KEYS.lock().unwrap() = keys.into_iter().filter(|(_, k)| !k.is_empty()).map(|(a, k)| (k, a)).collect();
    if APP.set(app).is_ok() {
        std::thread::spawn(|| unsafe {
            // A low-level hook is called on the thread that set it, which needs a message loop.
            SetWindowsHookExW(WH_KEYBOARD_LL, Some(on_key), GetModuleHandleW(std::ptr::null()), 0);
            let mut msg = std::mem::zeroed();
            while GetMessageW(&mut msg, std::ptr::null_mut(), 0, 0) > 0 {}
        });
    }
}

unsafe extern "system" fn on_key(code: i32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    if code == HC_ACTION as i32 {
        let vk = (*(lparam as *const KBDLLHOOKSTRUCT)).vkCode;
        if wparam == WM_KEYDOWN as WPARAM || wparam == WM_SYSKEYDOWN as WPARAM {
            // rxConnect in front: the page has the keys itself (its keydown handler), so not twice.
            if HELD.swap(vk, Ordering::Relaxed) != vk && !in_front() {
                if let Some(name) = code_name(vk as u16) {
                    let on = |k: u16| GetAsyncKeyState(k as i32) < 0;
                    let combo = format!("{}{}{}{}{}", if on(VK_CONTROL) { "Ctrl+" } else { "" }, if on(VK_MENU) { "Alt+" } else { "" },
                        if on(VK_SHIFT) { "Shift+" } else { "" }, if on(VK_LWIN) || on(VK_RWIN) { "Win+" } else { "" }, name);
                    let action = KEYS.lock().unwrap().iter().find(|(k, _)| *k == combo).map(|(_, a)| a.clone());
                    if let (Some(action), Some(window)) = (action, APP.get().and_then(|a| a.get_webview_window("main"))) {
                        let _ = window.eval(&format!("window.rxShortcut && rxShortcut({})", serde_json::Value::from(action)));
                    }
                }
            }
        } else {
            let _ = HELD.compare_exchange(vk, 0, Ordering::Relaxed, Ordering::Relaxed);
        }
    }
    CallNextHookEx(std::ptr::null_mut(), code, wparam, lparam) // always passed on: never taken from other apps
}

fn in_front() -> bool {
    let mut pid = 0;
    unsafe { GetWindowThreadProcessId(GetForegroundWindow(), &mut pid) };
    pid == unsafe { GetCurrentProcessId() }
}

/// The page's name for a key (KeyboardEvent.code, as the settings save it).
fn code_name(vk: u16) -> Option<String> {
    let s = |n: &str| Some(n.to_owned());
    match vk {
        0x41..=0x5A => Some(format!("Key{}", vk as u8 as char)),
        0x30..=0x39 => Some(format!("Digit{}", vk - 0x30)),
        VK_NUMPAD0..=VK_NUMPAD9 => Some(format!("Numpad{}", vk - VK_NUMPAD0)),
        VK_F1..=VK_F24 => Some(format!("F{}", vk - VK_F1 + 1)),
        VK_SPACE => s("Space"), VK_RETURN => s("Enter"), VK_TAB => s("Tab"), VK_ESCAPE => s("Escape"),
        VK_BACK => s("Backspace"), VK_DELETE => s("Delete"), VK_INSERT => s("Insert"), VK_HOME => s("Home"),
        VK_END => s("End"), VK_PRIOR => s("PageUp"), VK_NEXT => s("PageDown"), VK_LEFT => s("ArrowLeft"),
        VK_UP => s("ArrowUp"), VK_RIGHT => s("ArrowRight"), VK_DOWN => s("ArrowDown"), VK_OEM_MINUS => s("Minus"),
        VK_OEM_PLUS => s("Equal"), VK_OEM_4 => s("BracketLeft"), VK_OEM_6 => s("BracketRight"), VK_OEM_5 => s("Backslash"),
        VK_OEM_1 => s("Semicolon"), VK_OEM_7 => s("Quote"), VK_OEM_3 => s("Backquote"), VK_OEM_COMMA => s("Comma"),
        VK_OEM_PERIOD => s("Period"), VK_OEM_2 => s("Slash"), VK_MULTIPLY => s("NumpadMultiply"), VK_ADD => s("NumpadAdd"),
        VK_SUBTRACT => s("NumpadSubtract"), VK_DIVIDE => s("NumpadDivide"), VK_DECIMAL => s("NumpadDecimal"),
        VK_PAUSE => s("Pause"), VK_SNAPSHOT => s("PrintScreen"), VK_SCROLL => s("ScrollLock"),
        _ => None,
    }
}
