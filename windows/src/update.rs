//! Self-update from GitHub releases (tag v<N>, from ../release.sh). The page (rxUpdate in web/index.html) asks
//! and shows the bar; this checks, downloads and swaps the exe. The exe swap is rxcord's (crates/app/src/update.rs).
use std::io::Read;
use std::path::{Path, PathBuf};
use tauri::{AppHandle, WebviewWindow};

const LATEST: &str = "https://api.github.com/repos/RexSAS/rxConnect/releases/latest";
const ASSET: &str = "rxconnect-windows-x86_64.exe";
const MAX_SIZE: usize = 100 << 20;

/// This build's N, set by the release workflow. Local builds have none and never update.
fn current() -> Option<u32> {
    option_env!("RXCONNECT_VERSION")?.parse().ok()
}

/// (N, download url) of the latest release if it's newer than this build and has the exe attached yet.
fn newer() -> Result<Option<(u32, String)>, String> {
    let Some(mine) = current() else { return Ok(None) };
    let latest: serde_json::Value = get(LATEST)?.into_json().map_err(|e| e.to_string())?;
    Ok(parse(&latest).filter(|(v, _)| *v > mine))
}

fn parse(release: &serde_json::Value) -> Option<(u32, String)> {
    let v = release["tag_name"].as_str()?.strip_prefix('v')?.parse().ok()?;
    let asset = release["assets"].as_array()?.iter().find(|a| a["name"] == ASSET)?;
    Some((v, asset["browser_download_url"].as_str()?.to_string()))
}

fn get(url: &str) -> Result<ureq::Response, String> {
    // GitHub's API refuses requests without a User-Agent.
    ureq::get(url).set("User-Agent", "rxConnect").call().map_err(|e| e.to_string())
}

fn js(window: &WebviewWindow, code: String) {
    let _ = window.eval(&code);
}

#[tauri::command]
pub fn check_update(window: WebviewWindow) {
    std::thread::spawn(move || match newer() {
        Ok(Some((v, _))) => js(&window, format!("rxUpdate.ask({v})")),
        Ok(None) => {}
        Err(e) => eprintln!("update check failed: {e}"),
    });
}

#[tauri::command]
pub fn install_update(app: AppHandle, window: WebviewWindow) {
    std::thread::spawn(move || match install(|p| js(&window, format!("rxUpdate.progress({p})"))) {
        Ok(exe) => {
            let _ = std::process::Command::new(exe).spawn();
            app.exit(0);
        }
        Err(e) => js(&window, format!("rxUpdate.failed({})", serde_json::Value::from(e))),
    });
}

/// Downloads the new exe (progress 0..100) and swaps it in; returns the path to start.
fn install(progress: impl Fn(u64)) -> Result<PathBuf, String> {
    let (_, url) = newer()?.ok_or("no newer version")?;
    let resp = get(&url)?;
    let total: u64 = resp.header("Content-Length").and_then(|l| l.parse().ok()).unwrap_or(0);
    let (mut reader, mut bytes, mut buf, mut last) = (resp.into_reader(), Vec::new(), vec![0; 64 << 10], u64::MAX);
    loop {
        let n = reader.read(&mut buf).map_err(|e| e.to_string())?;
        if n == 0 {
            break;
        }
        bytes.extend_from_slice(&buf[..n]);
        if bytes.len() > MAX_SIZE {
            return Err("download is too big".into());
        }
        let pct = if total > 0 { bytes.len() as u64 * 100 / total } else { 0 };
        if pct != last {
            progress(pct);
            last = pct;
        }
    }
    if total > 0 && bytes.len() as u64 != total {
        return Err("download cut short".into());
    }
    progress(100);
    let exe = std::env::current_exe().map_err(|e| e.to_string())?;
    replace_exe(&exe, &bytes)?;
    Ok(exe)
}

fn old_path(exe: &Path) -> PathBuf {
    exe.with_extension("old")
}

/// Writes the new exe next to the running one, then swaps it in. Windows can't overwrite a running exe but can
/// rename it, so it's moved aside first (deleted on the next start).
fn replace_exe(exe: &Path, bytes: &[u8]) -> Result<(), String> {
    let new = exe.with_extension("new");
    let err = |what: &str, e: std::io::Error| format!("{what} {}: {e}", new.parent().map(|d| d.display().to_string()).unwrap_or_default());
    std::fs::write(&new, bytes).map_err(|e| err("can't write to", e))?;
    let _ = std::fs::remove_file(old_path(exe));
    std::fs::rename(exe, old_path(exe)).map_err(|e| err("can't move the old version in", e))?;
    std::fs::rename(&new, exe).map_err(|e| err("can't install into", e))
}

/// Deletes the previous version left by an update.
pub fn cleanup() {
    if let Ok(exe) = std::env::current_exe() {
        let _ = std::fs::remove_file(old_path(&exe));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_release() {
        let r = serde_json::json!({"tag_name": "v12", "assets": [
            {"name": "rxconnect.apk", "browser_download_url": "https://x/apk"},
            {"name": ASSET, "browser_download_url": "https://x/exe"}]});
        assert_eq!(parse(&r), Some((12, "https://x/exe".into())));
        // Release published but the exe not uploaded yet: nothing to offer.
        assert_eq!(parse(&serde_json::json!({"tag_name": "v13", "assets": []})), None);
        assert_eq!(parse(&serde_json::json!({"tag_name": "latest", "assets": []})), None);
    }

    #[test]
    fn swaps_exe() {
        let dir = std::env::temp_dir().join(format!("rxconnect-update-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let exe = dir.join("rxconnect.exe");
        std::fs::write(&exe, b"old").unwrap();
        replace_exe(&exe, b"new").unwrap();
        assert_eq!(std::fs::read(&exe).unwrap(), b"new");
        assert!(!exe.with_extension("new").exists());
        std::fs::remove_dir_all(dir).unwrap();
    }
}
