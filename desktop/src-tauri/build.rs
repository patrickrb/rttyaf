// RTTY DSP is pure Rust. Preserve bundled Windows Hamlib discovery and Tauri
// context generation; do not resurrect the removed Android FT8 C dependency.
use std::path::PathBuf;

fn main() {
    let manifest = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").unwrap());
    if std::env::var("TARGET")
        .unwrap_or_default()
        .contains("windows")
    {
        copy_hamlib_dlls(&manifest);
    }
    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-changed=hamlib");
    tauri_build::build();
}

fn copy_hamlib_dlls(manifest: &std::path::Path) {
    let hamlib = manifest.join("hamlib");
    if !hamlib.is_dir() {
        return;
    }
    let out_dir = match std::env::var("OUT_DIR") {
        Ok(d) => PathBuf::from(d),
        Err(_) => return,
    };
    let exe_dir = match out_dir.ancestors().nth(3) {
        Some(p) => p.to_path_buf(),
        None => return,
    };
    if let Ok(entries) = std::fs::read_dir(&hamlib) {
        for e in entries.flatten() {
            let p = e.path();
            if p.extension().and_then(|s| s.to_str()) == Some("dll") {
                if let Some(name) = p.file_name() {
                    let _ = std::fs::copy(&p, exe_dir.join(name));
                }
            }
        }
    }
}
