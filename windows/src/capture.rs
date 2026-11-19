//! Our own screen / window picker and the capture behind it: WebView2 only offers its own picker for getDisplayMedia.
//! list_sources gives the picker (nativeShare in index.html) a thumbnail of every screen and window; start_capture
//! captures the chosen one on a thread, up to 30 times a second, as JPEG; the page pulls the newest frame from
//! rxcap://localhost/frame?after=N, one at a time, so a slow PC lowers the frame rate instead of piling up delay.
use base64::Engine;
use image::{codecs::jpeg::JpegEncoder, imageops, DynamicImage, RgbaImage};
use serde_json::{json, Value};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};
use tauri::http;
use tauri::UriSchemeResponder;
use xcap::{Monitor, Window};

/// The newest frame. `generation` changes on every start / stop, so an old capture thread knows to quit.
struct Latest {
    frame: Option<Arc<Vec<u8>>>,
    seq: u64,
    running: bool,
    generation: u64,
}

static STATE: (Mutex<Latest>, Condvar) =
    (Mutex::new(Latest { frame: None, seq: 0, running: false, generation: 0 }), Condvar::new());

fn jpeg(img: RgbaImage, quality: u8) -> Vec<u8> {
    let rgb = DynamicImage::ImageRgba8(img).into_rgb8(); // JPEG has no alpha
    let mut out = Vec::new();
    let _ = JpegEncoder::new_with_quality(&mut out, quality).encode_image(&rgb);
    out
}

fn thumb(img: RgbaImage) -> String {
    let (w, h) = img.dimensions();
    let small = imageops::thumbnail(&img, 320, (h as u64 * 320 / w.max(1) as u64).max(1) as u32);
    base64::engine::general_purpose::STANDARD.encode(jpeg(small, 70))
}

/// Screens and windows for the picker: [{kind: "screen" | "window", id, name, app?, thumb: base64 JPEG}].
/// Leaves out rxConnect itself, minimized windows and tiny or untitled ones (tool windows, the desktop).
#[tauri::command]
pub async fn list_sources() -> Vec<Value> {
    let mut list = Vec::new();
    for (i, m) in Monitor::all().unwrap_or_default().into_iter().enumerate() {
        let (Ok(id), Ok(img)) = (m.id(), m.capture_image()) else { continue };
        let name = if m.is_primary().unwrap_or(false) { "Main screen".to_string() } else { format!("Screen {}", i + 1) };
        list.push(json!({ "kind": "screen", "id": id, "name": name, "thumb": thumb(img) }));
    }
    let me = std::process::id();
    for w in Window::all().unwrap_or_default() {
        let title = w.title().unwrap_or_default();
        let shown = w.pid().map_or(false, |p| p != me)
            && !w.is_minimized().unwrap_or(true)
            && w.width().unwrap_or(0) > 120
            && w.height().unwrap_or(0) > 80
            && !title.trim().is_empty()
            && title != "Program Manager";
        if !shown {
            continue;
        }
        let (Ok(id), Ok(img)) = (w.id(), w.capture_image()) else { continue };
        list.push(json!({ "kind": "window", "id": id, "name": title, "app": w.app_name().unwrap_or_default(), "thumb": thumb(img) }));
    }
    list
}

/// Starts capturing a screen or window from list_sources (replacing any capture already running).
#[tauri::command]
pub fn start_capture(kind: String, id: u32) {
    let generation = {
        let mut s = STATE.0.lock().unwrap();
        s.generation += 1;
        s.running = true;
        s.frame = None;
        s.generation
    };
    std::thread::spawn(move || {
        // Looked up here: the capture handles stay on this thread.
        let monitor = if kind == "screen" { Monitor::all().ok().and_then(|all| all.into_iter().find(|m| m.id().ok() == Some(id))) } else { None };
        let window = if kind == "window" { Window::all().ok().and_then(|all| all.into_iter().find(|w| w.id().ok() == Some(id))) } else { None };
        loop {
            if STATE.0.lock().unwrap().generation != generation {
                return; // stopped, or another capture started
            }
            let started = Instant::now();
            let image = match (&monitor, &window) {
                (Some(m), _) => m.capture_image().ok(),
                (_, Some(w)) if w.is_minimized().unwrap_or(false) => {
                    std::thread::sleep(Duration::from_millis(200)); // minimized: keep the last frame until it's back
                    continue;
                }
                (_, Some(w)) => w.capture_image().ok(),
                _ => None,
            };
            let Some(image) = image else {
                stop(generation); // the window closed (or the screen went away): sharing ends
                return;
            };
            // Wider than 1080p (1440p, 4K): scaled to 1920 first. The call sends about that anyway, and encoding a full
            // 4K frame would take long enough to lower the frame rate.
            let image = if image.width() > 1920 {
                let height = (image.height() as u64 * 1920 / image.width() as u64).max(1) as u32;
                imageops::thumbnail(&image, 1920, height)
            } else {
                image
            };
            let bytes = Arc::new(jpeg(image, 80));
            {
                let mut s = STATE.0.lock().unwrap();
                if s.generation != generation {
                    return;
                }
                s.frame = Some(bytes);
                s.seq += 1;
            }
            STATE.1.notify_all();
            if let Some(rest) = Duration::from_millis(33).checked_sub(started.elapsed()) {
                std::thread::sleep(rest);
            }
        }
    });
}

/// Stops sharing (Stop sharing in the page).
#[tauri::command]
pub fn stop_capture() {
    let generation = STATE.0.lock().unwrap().generation;
    stop(generation);
}

fn stop(generation: u64) {
    let mut s = STATE.0.lock().unwrap();
    if s.generation != generation {
        return;
    }
    s.generation += 1;
    s.running = false;
    s.frame = None;
    drop(s);
    STATE.1.notify_all();
}

/// rxcap://localhost/frame?after=N: the newest frame once it's newer than N (waits up to 2 s for one), with its
/// number in X-Frame. 204: nothing new yet, ask again. 410: not capturing any more.
pub fn serve(request: http::Request<Vec<u8>>, responder: UriSchemeResponder) {
    std::thread::spawn(move || {
        let after: u64 = request.uri().query().and_then(|q| q.strip_prefix("after=")).and_then(|n| n.parse().ok()).unwrap_or(0);
        let deadline = Instant::now() + Duration::from_secs(2);
        let mut s = STATE.0.lock().unwrap();
        while s.running && (s.frame.is_none() || s.seq <= after) {
            let left = deadline.saturating_duration_since(Instant::now());
            if left.is_zero() {
                break;
            }
            s = STATE.1.wait_timeout(s, left).unwrap().0;
        }
        let response = http::Response::builder()
            .header("Access-Control-Allow-Origin", "*")
            .header("Access-Control-Expose-Headers", "X-Frame")
            .header("Cache-Control", "no-store");
        let response = match &s.frame {
            Some(frame) if s.running && s.seq > after => response
                .status(200)
                .header("Content-Type", "image/jpeg")
                .header("X-Frame", s.seq.to_string())
                .body(frame.as_ref().clone()),
            _ if s.running => response.status(204).body(Vec::new()),
            _ => response.status(410).body(Vec::new()),
        };
        drop(s);
        if let Ok(response) = response {
            responder.respond(response);
        }
    });
}
